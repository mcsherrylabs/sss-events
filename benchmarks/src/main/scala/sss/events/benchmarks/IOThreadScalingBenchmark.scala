package sss.events.benchmarks

import org.openjdk.jmh.annotations.*
import sss.events.{BackoffConfig, DispatcherName, EngineConfig, EventProcessingEngine}

import java.util.concurrent.{CountDownLatch, TimeUnit, TimeoutException}
import scala.compiletime.uninitialized

/**
 * Benchmark to analyze the relationship between:
 * - IO task generation rate (messages/sec from CPU actors)
 * - Number of IO threads
 * - System throughput
 *
 * Fixed parameters:
 * - IO latency: 20ms (realistic DB/API call)
 * - CPU work: minimal (fast message generation)
 *
 * Variable parameters:
 * - ioThreadCount: 1, 2, 4, 8, 16, 32, 64, 128
 * - messagesPerSecond: 50, 100, 200, 500, 1000, 2000 (IO task generation rate)
 *
 * Optimized for faster execution:
 * - 1 warmup × 2s, 2 measurement × 3s = ~8s per combination
 * - 8 thread counts × 6 task rates = 48 combinations × 8s = ~6-8 minutes
 *
 * Measures: How many messages/sec can be sustained with different thread counts
 */
@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 1, time = 2, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 2, time = 3, timeUnit = TimeUnit.SECONDS)
@Fork(1)
class IOThreadScalingBenchmark {

  @Param(Array("1", "2", "4", "8", "16", "32", "64", "128"))
  var ioThreadCount: Int = uninitialized

  @Param(Array("50", "100", "200", "500", "1000", "2000"))
  var targetIOTasksPerSecond: Int = uninitialized

  var engine: EventProcessingEngine = uninitialized
  var config: EngineConfig = uninitialized

  case class IOTask(id: Int)
  case class GenerateIOTasks(count: Int)

  @Setup(Level.Trial)
  def setup(): Unit = {
    // Build thread assignment array dynamically based on ioThreadCount
    val subscriptionsThread = Array(Array("subscriptions"))
    val ioThreads = Array.fill(ioThreadCount)(Array("io-bound"))
    val cpuThreads = Array(
      Array("cpu-generator")  // Single fast CPU thread generating IO tasks
    )

    config = EngineConfig(
      schedulerPoolSize = 2,
      threadDispatcherAssignment = subscriptionsThread ++ ioThreads ++ cpuThreads,
      defaultQueueSize = 10000,
      backoff = BackoffConfig(10, 1.5, 10000)
    )

    engine = EventProcessingEngine(config)
    engine.start()
  }

  @TearDown(Level.Trial)
  def teardown(): Unit = {
    engine.shutdown()
  }

  @Benchmark
  def measureIOThreadScaling(): Unit = {
    // Calculate how many tasks to generate for this benchmark iteration
    // JMH measures ops/sec, so we generate tasks and measure completion rate
    val totalTasks = targetIOTasksPerSecond * 5  // 5 seconds worth of work
    val latch = new CountDownLatch(totalTasks)

    val ioDispatcher = DispatcherName.validated("io-bound", config).getOrElse(
      throw new IllegalArgumentException("Invalid dispatcher: io-bound")
    )

    val cpuDispatcher = DispatcherName.validated("cpu-generator", config).getOrElse(
      throw new IllegalArgumentException("Invalid dispatcher: cpu-generator")
    )

    // Create IO workers
    val ioWorkers = (1 to Math.min(targetIOTasksPerSecond / 10, 100)).map { i =>
      engine.builder()
        .withCreateHandler { ep => {
          case IOTask(_) =>
            Thread.sleep(20)  // Fixed 20ms IO latency
            latch.countDown()
        }}
        .withDispatcher(ioDispatcher)
        .build()
    }

    // Create CPU generator that distributes work to IO workers
    var taskId = 0
    val cpuGenerator = engine.builder()
      .withCreateHandler { ep => {
        case GenerateIOTasks(count) =>
          // Fast CPU work: distribute tasks to IO workers
          (1 to count).foreach { _ =>
            val worker = ioWorkers(taskId % ioWorkers.length)
            worker ! IOTask(taskId)
            taskId += 1
          }
      }}
      .withDispatcher(cpuDispatcher)
      .build()

    // Generate all IO tasks from CPU actor
    cpuGenerator ! GenerateIOTasks(totalTasks)

    // Wait for completion with appropriate timeout
    // Timeout = (tasks × 20ms / ioThreadCount) + buffer
    val expectedSeconds = (totalTasks * 20) / (ioThreadCount * 1000)
    val timeoutSeconds = Math.max(10, expectedSeconds * 3)  // 3x buffer

    if (!latch.await(timeoutSeconds, TimeUnit.SECONDS)) {
      throw new TimeoutException(
        s"Timeout after ${timeoutSeconds}s: completed ${totalTasks - latch.getCount()}/$totalTasks tasks " +
        s"(ioThreads=$ioThreadCount, targetRate=$targetIOTasksPerSecond/s)"
      )
    }

    // Cleanup
    ioWorkers.foreach(w => engine.stop(w.id))
    engine.stop(cpuGenerator.id)
  }
}

package sss.events.benchmarks

import org.openjdk.jmh.annotations.*
import sss.events.{BackoffConfig, DispatcherName, EngineConfig, EventProcessingEngine}

import java.util.concurrent.{CountDownLatch, TimeUnit, TimeoutException}
import scala.compiletime.uninitialized

/**
 * Smart IO Thread Scaling Benchmark - only tests realistic combinations.
 *
 * Skips obvious bottlenecks (e.g., 1 thread with 2000 tasks/s).
 * Tests each thread count with task rates around its theoretical capacity.
 *
 * Theoretical capacity: threads × 50 tasks/s (with 20ms IO latency)
 *
 * Test matrix (24 realistic combinations vs 48 total):
 * - 1 thread:   50, 100 tasks/s
 * - 2 threads:  100, 200 tasks/s
 * - 4 threads:  200, 500 tasks/s
 * - 8 threads:  500, 1000 tasks/s
 * - 16 threads: 1000, 2000 tasks/s
 * - 32 threads: 2000 tasks/s
 * - 64 threads: 2000 tasks/s
 * - 128 threads: 2000 tasks/s
 */
@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 1, time = 2, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 2, time = 3, timeUnit = TimeUnit.SECONDS)
@Fork(1)
class IOThreadScalingSmartBenchmark {

  @Param(Array(
    "1:50", "1:100",
    "2:100", "2:200",
    "4:200", "4:500",
    "8:500", "8:1000",
    "16:1000", "16:2000",
    "32:2000",
    "64:2000",
    "128:2000"
  ))
  var config: String = uninitialized

  var ioThreadCount: Int = 0
  var targetIOTasksPerSecond: Int = 0

  var engine: EventProcessingEngine = uninitialized
  var engineConfig: EngineConfig = uninitialized

  case class IOTask(id: Int)
  case class GenerateIOTasks(count: Int)

  @Setup(Level.Trial)
  def setup(): Unit = {
    // Parse config "threads:taskRate"
    val parts = config.split(":")
    ioThreadCount = parts(0).toInt
    targetIOTasksPerSecond = parts(1).toInt

    // Build thread assignment array dynamically
    val subscriptionsThread = Array(Array("subscriptions"))
    val ioThreads = Array.fill(ioThreadCount)(Array("io-bound"))
    val cpuThreads = Array(Array("cpu-generator"))

    engineConfig = EngineConfig(
      schedulerPoolSize = 2,
      threadDispatcherAssignment = subscriptionsThread ++ ioThreads ++ cpuThreads,
      defaultQueueSize = 10000,
      backoff = BackoffConfig(10, 1.5, 10000)
    )

    engine = EventProcessingEngine(engineConfig)
    engine.start()
  }

  @TearDown(Level.Trial)
  def teardown(): Unit = {
    engine.shutdown()
  }

  @Benchmark
  def measureIOThreadScaling(): Unit = {
    val totalTasks = targetIOTasksPerSecond * 5  // 5 seconds worth
    val latch = new CountDownLatch(totalTasks)

    val ioDispatcher = DispatcherName.validated("io-bound", engineConfig).getOrElse(
      throw new IllegalArgumentException("Invalid dispatcher: io-bound")
    )

    val cpuDispatcher = DispatcherName.validated("cpu-generator", engineConfig).getOrElse(
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

    // Create CPU generator
    var taskId = 0
    val cpuGenerator = engine.builder()
      .withCreateHandler { ep => {
        case GenerateIOTasks(count) =>
          (1 to count).foreach { _ =>
            val worker = ioWorkers(taskId % ioWorkers.length)
            worker ! IOTask(taskId)
            taskId += 1
          }
      }}
      .withDispatcher(cpuDispatcher)
      .build()

    // Generate all IO tasks
    cpuGenerator ! GenerateIOTasks(totalTasks)

    // Wait with appropriate timeout
    val expectedSeconds = (totalTasks * 20) / (ioThreadCount * 1000)
    val timeoutSeconds = Math.max(10, expectedSeconds * 3)

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

package sss.events.benchmarks

import org.openjdk.jmh.annotations.*
import sss.events.{BackoffConfig, DispatcherName, EngineConfig, EventProcessingEngine}

import java.util.concurrent.{CountDownLatch, TimeUnit, TimeoutException}
import scala.compiletime.uninitialized

/**
 * Benchmarks for high actor churn with mixed IO/CPU workloads.
 *
 * Tests continuous processor creation/destruction with:
 * - 20% IO-bound processors (500ms simulated IO)
 * - 80% CPU-bound processors (computation)
 * - Configurable queue sizes
 * - 1 pinned IO dispatcher + 6 CPU dispatchers
 */
@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 5, time = 2, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 3, timeUnit = TimeUnit.SECONDS)
@Fork(3)
class ActorChurnBenchmark {

  @Param(Array("10", "100", "1000"))
  var actorCount: Int = uninitialized

  @Param(Array("1000", "10000", "100000"))
  var queueSize: Int = uninitialized

  var engine: EventProcessingEngine = uninitialized
  var config: EngineConfig = uninitialized

  @Setup(Level.Trial)
  def setup(): Unit = {
    config = EngineConfig(
      schedulerPoolSize = 2,
      threadDispatcherAssignment = Array(
        Array("subscriptions"),  // Thread 0: Required subscriptions
        Array("io-bound"),       // Thread 1: Pinned for IO tasks
        Array("cpu-1"),          // Threads 2-7: CPU-bound work
        Array("cpu-2"),
        Array("cpu-3"),
        Array("cpu-4"),
        Array("cpu-5"),
        Array("cpu-6")
      ),
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
  def highChurnMixedWorkload(): Unit = {
    val ioProcessorCount = Math.max(1, (actorCount * 0.2).toInt)
    val cpuProcessorCount = actorCount - ioProcessorCount
    val messagesPerProcessor = 10

    val totalMessages = actorCount * messagesPerProcessor
    val latch = new CountDownLatch(totalMessages)

    // Create IO-bound processors
    val ioProcessors = (1 to ioProcessorCount).map { i =>
      val ioDispatcher = DispatcherName.validated("io-bound", config).getOrElse(
        throw new IllegalArgumentException("Invalid dispatcher: io-bound")
      )

      engine.builder()
        .withCreateHandler { ep => {
          case msg: Int =>
            Thread.sleep(500)  // Simulate IO (blocking operation)
            latch.countDown()
        }}
        .withDispatcher(ioDispatcher)
        .withQueueSize(queueSize)
        .build()
    }

    // Create CPU-bound processors distributed across CPU dispatchers
    val cpuDispatchers = Array("cpu-1", "cpu-2", "cpu-3", "cpu-4", "cpu-5", "cpu-6")
    val cpuProcessors = (1 to cpuProcessorCount).map { i =>
      val dispatcherName = cpuDispatchers(i % cpuDispatchers.length)
      val cpuDispatcher = DispatcherName.validated(dispatcherName, config).getOrElse(
        throw new IllegalArgumentException(s"Invalid dispatcher: $dispatcherName")
      )

      engine.builder()
        .withCreateHandler { ep => {
          case msg: Int =>
            // Simulate CPU-bound work (computation)
            var result = 0.0
            (1 to 10000).foreach(j => result += Math.sqrt(j))
            latch.countDown()
        }}
        .withDispatcher(cpuDispatcher)
        .withQueueSize(queueSize)
        .build()
    }

    // Send messages to all processors
    ioProcessors.foreach(p => (1 to messagesPerProcessor).foreach(i => p ! i))
    cpuProcessors.foreach(p => (1 to messagesPerProcessor).foreach(i => p ! i))

    // Wait for all messages to be processed
    // Timeout calculation: IO processors (20%) × 10 msgs × 500ms = actorCount seconds
    // Add 2 minute buffer for CPU work and overhead
    val timeoutSeconds = actorCount + 120
    if (!latch.await(timeoutSeconds, TimeUnit.SECONDS)) {
      throw new TimeoutException(s"Benchmark timeout after ${timeoutSeconds}s: processed ${totalMessages - latch.getCount()}/$totalMessages messages")
    }

    // Destroy all processors (actor churn)
    ioProcessors.foreach(p => engine.stop(p.id))
    cpuProcessors.foreach(p => engine.stop(p.id))
  }

  @Benchmark
  def pureActorChurn(): Unit = {
    // Pure creation/destruction without message processing
    // Tests overhead of actor lifecycle alone
    val processors = (1 to actorCount).map { i =>
      val dispatcherName = if (i % 5 == 0) "io-bound" else s"cpu-${(i % 6) + 1}"
      val dispatcher = DispatcherName.validated(dispatcherName, config).getOrElse(
        throw new IllegalArgumentException(s"Invalid dispatcher: $dispatcherName")
      )

      engine.builder()
        .withCreateHandler { ep => { case _ => () } }  // Minimal handler
        .withDispatcher(dispatcher)
        .withQueueSize(queueSize)
        .build()
    }

    // Immediately destroy without sending messages
    processors.foreach(p => engine.stop(p.id))
  }

  @Benchmark
  def cpuOnlyWorkload(): Unit = {
    // CPU-only workload for comparison baseline
    val messagesPerProcessor = 10
    val totalMessages = actorCount * messagesPerProcessor
    val latch = new CountDownLatch(totalMessages)

    val cpuDispatchers = Array("cpu-1", "cpu-2", "cpu-3", "cpu-4", "cpu-5", "cpu-6")
    val processors = (1 to actorCount).map { i =>
      val dispatcherName = cpuDispatchers(i % cpuDispatchers.length)
      val dispatcher = DispatcherName.validated(dispatcherName, config).getOrElse(
        throw new IllegalArgumentException(s"Invalid dispatcher: $dispatcherName")
      )

      engine.builder()
        .withCreateHandler { ep => {
          case msg: Int =>
            var result = 0.0
            (1 to 10000).foreach(j => result += Math.sqrt(j))
            latch.countDown()
        }}
        .withDispatcher(dispatcher)
        .withQueueSize(queueSize)
        .build()
    }

    processors.foreach(p => (1 to messagesPerProcessor).foreach(i => p ! i))

    // Timeout scales with actor count - CPU work takes longer with more actors
    // 60s base + 1s per actor should be sufficient for CPU-bound work
    val timeoutSeconds = 60 + actorCount
    if (!latch.await(timeoutSeconds, TimeUnit.SECONDS)) {
      throw new TimeoutException(s"CPU workload benchmark timeout after ${timeoutSeconds}s")
    }

    processors.foreach(p => engine.stop(p.id))
  }
}

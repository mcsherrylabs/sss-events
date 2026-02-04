package sss.events.benchmarks

import org.openjdk.jmh.annotations.*
import sss.events.{BackoffConfig, BaseEventProcessor, DispatcherName, EngineConfig, EventProcessingEngine}
import sss.events.EventProcessor.EventHandler

import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger
import scala.compiletime.uninitialized

/**
 * Comprehensive throughput benchmarks for thread-to-dispatcher pinning.
 *
 * These benchmarks measure:
 * - Single dispatcher throughput under varying thread counts
 * - Multi-dispatcher throughput with different thread assignments
 * - Impact of backoff parameters on throughput
 * - Lock contention scenarios
 */
@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 2, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 3, timeUnit = TimeUnit.SECONDS)
@Fork(1)
class ThroughputBenchmark {

  case class TestMessage(id: Int)

  @Benchmark
  @BenchmarkMode(Array(Mode.Throughput))
  def singleDispatcher_2Threads(): Unit = {
    val config = EngineConfig(
      schedulerPoolSize = 2,
      threadDispatcherAssignment = Array(
        Array("subscriptions"),     // Subscriptions
        Array("work"),
        Array("work")
      ),
      defaultQueueSize = 10000,
      backoff = BackoffConfig(10, 1.5, 10000)
    )

    runThroughputTest(config, "work", 10000)
  }

  @Benchmark
  @BenchmarkMode(Array(Mode.Throughput))
  def singleDispatcher_4Threads(): Unit = {
    val config = EngineConfig(
      schedulerPoolSize = 2,
      threadDispatcherAssignment = Array(
        Array("subscriptions"),     // Subscriptions
        Array("work"),
        Array("work"),
        Array("work"),
        Array("work")
      ),
      defaultQueueSize = 10000,
      backoff = BackoffConfig(10, 1.5, 10000)
    )

    runThroughputTest(config, "work", 10000)
  }

  @Benchmark
  @BenchmarkMode(Array(Mode.Throughput))
  def singleDispatcher_8Threads(): Unit = {
    val config = EngineConfig(
      schedulerPoolSize = 2,
      threadDispatcherAssignment = Array(
        Array("subscriptions"),     // Subscriptions
        Array("work"),
        Array("work"),
        Array("work"),
        Array("work"),
        Array("work"),
        Array("work"),
        Array("work"),
        Array("work")
      ),
      defaultQueueSize = 10000,
      backoff = BackoffConfig(10, 1.5, 10000)
    )

    runThroughputTest(config, "work", 10000)
  }

  @Benchmark
  @BenchmarkMode(Array(Mode.Throughput))
  def twoDispatchers_4Threads_Shared(): Unit = {
    val config = EngineConfig(
      schedulerPoolSize = 2,
      threadDispatcherAssignment = Array(
        Array("subscriptions"),     // Subscriptions
        Array("A", "B"),
        Array("A", "B"),
        Array("A", "B"),
        Array("A", "B")
      ),
      defaultQueueSize = 10000,
      backoff = BackoffConfig(10, 1.5, 10000)
    )

    runMultiDispatcherTest(config, Array("A", "B"), 5000)
  }

  @Benchmark
  @BenchmarkMode(Array(Mode.Throughput))
  def twoDispatchers_4Threads_Dedicated(): Unit = {
    val config = EngineConfig(
      schedulerPoolSize = 2,
      threadDispatcherAssignment = Array(
        Array("subscriptions"),     // Subscriptions
        Array("A"),
        Array("A"),
        Array("B"),
        Array("B")
      ),
      defaultQueueSize = 10000,
      backoff = BackoffConfig(10, 1.5, 10000)
    )

    runMultiDispatcherTest(config, Array("A", "B"), 5000)
  }

  @Benchmark
  @BenchmarkMode(Array(Mode.Throughput))
  def fourDispatchers_8Threads(): Unit = {
    val config = EngineConfig(
      schedulerPoolSize = 2,
      threadDispatcherAssignment = Array(
        Array("subscriptions"),     // Subscriptions
        Array("A", "B"),
        Array("A", "B"),
        Array("C", "D"),
        Array("C", "D"),
        Array("A", "C"),
        Array("A", "C"),
        Array("B", "D"),
        Array("B", "D")
      ),
      defaultQueueSize = 10000,
      backoff = BackoffConfig(10, 1.5, 10000)
    )

    runMultiDispatcherTest(config, Array("A", "B", "C", "D"), 2500)
  }

  @Benchmark
  @BenchmarkMode(Array(Mode.Throughput))
  def sixteenDispatchers_16Threads_Dedicated(): Unit = {
    val config = EngineConfig(
      schedulerPoolSize = 2,
      threadDispatcherAssignment = Array(
        Array("subscriptions"),     // Subscriptions
        Array("A"),
        Array("B"),
        Array("C"),
        Array("D"),
        Array("E"),
        Array("F"),
        Array("G"),
        Array("H"),
        Array("I"),
        Array("J"),
        Array("K"),
        Array("L"),
        Array("M"),
        Array("N"),
        Array("O"),
        Array("P")
      ),
      defaultQueueSize = 10000,
      backoff = BackoffConfig(10, 1.5, 10000)
    )

    runMultiDispatcherTest(
      config,
      Array("A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M", "N", "O", "P"),
      625  // 10000 total messages / 16 dispatchers
    )
  }

  @Benchmark
  @BenchmarkMode(Array(Mode.Throughput))
  def backoff_Conservative(): Unit = {
    val config = EngineConfig(
      schedulerPoolSize = 2,
      threadDispatcherAssignment = Array(
        Array("subscriptions"),
        Array("work"),
        Array("work"),
        Array("work"),
        Array("work")
      ),
      defaultQueueSize = 10000,
      backoff = BackoffConfig(
        baseDelayMicros = 10,
        multiplier = 1.5,
        maxDelayMicros = 10000
      )
    )

    runThroughputTest(config, "work", 10000)
  }

  @Benchmark
  @BenchmarkMode(Array(Mode.Throughput))
  def backoff_Aggressive(): Unit = {
    val config = EngineConfig(
      schedulerPoolSize = 2,
      threadDispatcherAssignment = Array(
        Array("subscriptions"),
        Array("work"),
        Array("work"),
        Array("work"),
        Array("work")
      ),
      defaultQueueSize = 10000,
      backoff = BackoffConfig(
        baseDelayMicros = 100,
        multiplier = 2.0,
        maxDelayMicros = 50000
      )
    )

    runThroughputTest(config, "work", 10000)
  }

  @Benchmark
  @BenchmarkMode(Array(Mode.Throughput))
  def backoff_Minimal(): Unit = {
    val config = EngineConfig(
      schedulerPoolSize = 2,
      threadDispatcherAssignment = Array(
        Array("subscriptions"),
        Array("work"),
        Array("work"),
        Array("work"),
        Array("work")
      ),
      defaultQueueSize = 10000,
      backoff = BackoffConfig(
        baseDelayMicros = 1,
        multiplier = 1.1,
        maxDelayMicros = 1000
      )
    )

    runThroughputTest(config, "work", 10000)
  }

  private def runThroughputTest(config: EngineConfig, dispName: String, messageCount: Int): Unit = {
    implicit val engine: EventProcessingEngine = EventProcessingEngine(config)
    engine.start()

    val latch = new CountDownLatch(1)
    val received = new AtomicInteger(0)

    val dispatcher = DispatcherName.validated(dispName, config).getOrElse(
      throw new IllegalArgumentException(s"Invalid dispatcher name: $dispName")
    )

    val processor = new BaseEventProcessor {
      override def dispatcherName: DispatcherName = dispatcher

      override protected val onEvent: EventHandler = {
        case TestMessage(_) =>
          if (received.incrementAndGet() == messageCount) {
            latch.countDown()
          }
      }
    }

    // Post all messages
    (1 to messageCount).foreach(i => processor.post(TestMessage(i)))

    // Wait for completion
    latch.await(30, TimeUnit.SECONDS)

    // Cleanup
    engine.stop(processor.id)
    engine.shutdown()
  }

  private def runMultiDispatcherTest(config: EngineConfig, dispatchers: Array[String], messagesPerDispatcher: Int): Unit = {
    implicit val engine: EventProcessingEngine = EventProcessingEngine(config)
    engine.start()

    val latch = new CountDownLatch(dispatchers.length)

    val processors = dispatchers.map { dispName =>
      val received = new AtomicInteger(0)

      val dispatcher = DispatcherName.validated(dispName, config).getOrElse(
        throw new IllegalArgumentException(s"Invalid dispatcher name: $dispName")
      )

      val processor = new BaseEventProcessor {
        override def dispatcherName: DispatcherName = dispatcher

        override protected val onEvent: EventHandler = {
          case TestMessage(_) =>
            if (received.incrementAndGet() == messagesPerDispatcher) {
              latch.countDown()
            }
        }
      }

      // Post messages for this dispatcher
      (1 to messagesPerDispatcher).foreach(i => processor.post(TestMessage(i)))

      processor
    }

    // Wait for all dispatchers to complete
    latch.await(30, TimeUnit.SECONDS)

    // Cleanup
    processors.foreach(p => engine.stop(p.id))
    engine.shutdown()
  }
}

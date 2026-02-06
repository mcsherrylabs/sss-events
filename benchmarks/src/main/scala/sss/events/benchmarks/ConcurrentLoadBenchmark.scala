package sss.events.benchmarks

import org.openjdk.jmh.annotations.*
import sss.events.{BackoffConfig, BaseEventProcessor, EngineConfig, EventProcessingEngine}
import sss.events.EventProcessor.EventHandler

import java.util.concurrent.{CountDownLatch, TimeUnit}
import scala.concurrent.{ExecutionContext, Future}
import scala.compiletime.uninitialized

@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 2, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 3, timeUnit = TimeUnit.SECONDS)
@Fork(1)
class ConcurrentLoadBenchmark {

  @Param(Array("2", "4", "8"))
  var processorCount: Int = uninitialized

  @Param(Array("100", "1000"))
  var messagesPerProcessor: Int = uninitialized

  case class TestMessage(processorId: Int, messageId: Int)

  implicit val ec: ExecutionContext = ExecutionContext.global

  @Benchmark
  def measureConcurrentLoad(): Unit = {
    // Create config with 1 subscriptions thread + processorCount threads on default dispatcher
    val config = EngineConfig(
      schedulerPoolSize = 2,
      threadDispatcherAssignment = Array(Array("subscriptions")) ++ Array.fill(processorCount)(Array("")),
      defaultQueueSize = 10000,
      backoff = BackoffConfig(10, 1.5, 10000)
    )
    implicit val engine: EventProcessingEngine = EventProcessingEngine(config)
    engine.start()

    val latch = new CountDownLatch(processorCount)

    // Create multiple processors
    val processors = (0 until processorCount).map { procId =>
      var received = 0

      val processor = engine.builder()
        .withCreateHandler { ep => {
          case TestMessage(`procId`, _) =>
            received += 1
            if received == messagesPerProcessor then
              latch.countDown()
        }}
        .build()

      (procId, processor)
    }

    // Send messages to all processors concurrently
    val sendFutures = processors.map { case (procId, processor) =>
      Future {
        (1 to messagesPerProcessor).foreach { msgId =>
          processor ! TestMessage(procId, msgId)
        }
      }
    }

    // Wait for all processors to complete
    latch.await(10, TimeUnit.SECONDS)

    // Clean up
    processors.foreach { case (_, processor) => engine.stop(processor.id) }
    engine.shutdown()
  }
}

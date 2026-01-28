package sss.events.benchmarks

import org.openjdk.jmh.annotations.*
import sss.events.{BaseEventProcessor, EventProcessingEngine}
import sss.events.EventProcessor.EventHandler

import java.util.concurrent.TimeUnit
import scala.concurrent.{Await, Promise}
import scala.concurrent.duration.*
import scala.compiletime.uninitialized

@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 2, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 3, timeUnit = TimeUnit.SECONDS)
@Fork(1)
class ThroughputBenchmark {

  @Param(Array("100", "1000", "10000"))
  var messageCount: Int = uninitialized

  case class TestMessage(id: Int)
  case object Complete

  @Benchmark
  def measureThroughput(): Unit = {
    implicit val engine: EventProcessingEngine = EventProcessingEngine()
    engine.start()

    val completionPromise = Promise[Unit]()
    var received = 0

    val processor = new BaseEventProcessor {
      override protected val onEvent: EventHandler = {
        case TestMessage(_) =>
          received += 1
          if received == messageCount then
            post(Complete)
        case Complete =>
          completionPromise.success(())
      }
    }

    // Send all messages
    (1 to messageCount).foreach { i =>
      processor.post(TestMessage(i))
    }

    // Wait for completion
    Await.result(completionPromise.future, 10.seconds)
    Thread.sleep(100)
    while (processor.currentQueueSize > 0) Thread.sleep(10)
    engine.stop(processor.id)
    engine.shutdown()
  }
}

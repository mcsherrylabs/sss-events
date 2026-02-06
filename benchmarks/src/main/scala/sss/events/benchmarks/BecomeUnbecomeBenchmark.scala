package sss.events.benchmarks

import org.openjdk.jmh.annotations.*
import sss.events.{BaseEventProcessor, EventProcessingEngine}
import sss.events.EventProcessor.EventHandler

import java.util.concurrent.TimeUnit
import scala.concurrent.{Await, Promise}
import scala.concurrent.duration.*
import scala.compiletime.uninitialized

@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 2, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 3, timeUnit = TimeUnit.SECONDS)
@Fork(1)
class BecomeUnbecomeBenchmark {

  @Param(Array("10", "100", "1000"))
  var switchCount: Int = uninitialized

  case object SwitchHandler
  case object Complete

  @Benchmark
  def stackingBecomeUnbecome(): Unit = {
    implicit val engine: EventProcessingEngine = EventProcessingEngine()
    engine.start()

    val completionPromise = Promise[Unit]()
    var switchesRemaining = switchCount

    val handler2: EventHandler = {
      case SwitchHandler =>
    }

    val processor = new BaseEventProcessor {
      override protected val onEvent: EventHandler = {
        case SwitchHandler =>
          switchesRemaining -= 1
          if switchesRemaining > 0 then
            become(handler2, stackPreviousHandler = true)
            unbecome()
            post(SwitchHandler)
          else
            completionPromise.success(())
      }
    }
    engine.register(processor)

    processor.post(SwitchHandler)

    Await.result(completionPromise.future, 10.seconds)
    engine.stop(processor.id)
    engine.shutdown()
  }

  @Benchmark
  def nonStackingBecomeReplace(): Unit = {
    implicit val engine: EventProcessingEngine = EventProcessingEngine()
    engine.start()

    val completionPromise = Promise[Unit]()
    var switchesRemaining = switchCount

    val processor = new BaseEventProcessor {
      lazy val handler1: EventHandler = {
        case SwitchHandler =>
          switchesRemaining -= 1
          if switchesRemaining > 0 then
            become(handler2, stackPreviousHandler = false)
            post(SwitchHandler)
          else
            completionPromise.success(())
      }

      lazy val handler2: EventHandler = {
        case SwitchHandler =>
          switchesRemaining -= 1
          if switchesRemaining > 0 then
            become(handler1, stackPreviousHandler = false)
            post(SwitchHandler)
          else
            completionPromise.success(())
      }

      override protected val onEvent: EventHandler = handler1
    }
    engine.register(processor)

    processor.post(SwitchHandler)

    Await.result(completionPromise.future, 10.seconds)
    engine.stop(processor.id)
    engine.shutdown()
  }
}

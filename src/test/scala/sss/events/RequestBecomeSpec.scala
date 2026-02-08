package sss.events

import org.scalatest.concurrent.ScalaFutures.{PatienceConfig, convertScalaFuture}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Seconds, Span}
import sss.events.EventProcessor.EventHandler

import scala.concurrent.{ExecutionContext, Future, Promise}

/**
  * Tests for requestBecome/requestUnbecome which allow safe handler switching from external threads
  */
class RequestBecomeSpec extends AnyFlatSpec with Matchers {

  // Increase timeout for CI environments (default 150ms is too short)
  implicit val patienceConfig: PatienceConfig = PatienceConfig(timeout = Span(1, Seconds))

  implicit val sut: EventProcessingEngine = EventProcessingEngine()
  implicit val ec: ExecutionContext = ExecutionContext.global
  sut.start()

  "requestBecome" should "safely switch handlers from external thread" in {
    val becomeComplete = Promise[Unit]()
    val completionPromise = Promise[String]()

    val handler2: EventHandler = {
      case "becomeComplete" => becomeComplete.success(())
      case "test" => completionPromise.success("handler2")
    }

    val processor = new BaseEventProcessor {
      override protected val onEvent: EventHandler = {
        case "test" => completionPromise.success("handler1")
      }
    }
    sut.register(processor) // Register after construction completes

    // External thread calls requestBecome (this would fail with protected become)
    Future {
      processor.requestBecome(handler2, stackPreviousHandler = false)
      processor.post("becomeComplete")
    }

    // Wait for become to complete
    becomeComplete.future.futureValue

    // Now post the test message
    processor.post("test")

    assert(completionPromise.future.futureValue == "handler2")
  }

  "requestUnbecome" should "safely revert to previous handler from external thread" in {
    val setupComplete = Promise[Unit]()
    val unbecomeComplete = Promise[Unit]()
    val completionPromise = Promise[String]()

    val handler2: EventHandler = {
      case "setupComplete" => setupComplete.success(())
      case "test" => completionPromise.success("handler2")
    }

    val processor = new BaseEventProcessor {
      override protected val onEvent: EventHandler = {
        case "setup" =>
          become(handler2, stackPreviousHandler = true)
          post("setupComplete")
        case "unbecomeComplete" => unbecomeComplete.success(())
        case "test" => completionPromise.success("handler1")
      }
    }
    sut.register(processor) // Register after construction completes

    // Setup: switch to handler2 and wait for it to be ready
    processor.post("setup")
    setupComplete.future.futureValue

    // External thread calls requestUnbecome (would fail with protected unbecome)
    Future {
      processor.requestUnbecome()
      processor.post("unbecomeComplete")
    }

    // Wait for unbecome to complete
    unbecomeComplete.future.futureValue

    // Now post the test message
    processor.post("test")

    assert(completionPromise.future.futureValue == "handler1")
  }
}

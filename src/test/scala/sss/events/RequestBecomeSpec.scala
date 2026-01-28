package sss.events

import org.scalatest.concurrent.ScalaFutures.convertScalaFuture
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sss.events.EventProcessor.EventHandler

import scala.concurrent.{ExecutionContext, Future, Promise}

/**
  * Tests for requestBecome/requestUnbecome which allow safe handler switching from external threads
  */
class RequestBecomeSpec extends AnyFlatSpec with Matchers {

  implicit val sut: EventProcessingEngine = EventProcessingEngine()
  implicit val ec: ExecutionContext = ExecutionContext.global
  sut.start()

  "requestBecome" should "safely switch handlers from external thread" in {
    val completionPromise = Promise[String]()

    val handler2: EventHandler = {
      case "test" => completionPromise.success("handler2")
    }

    val processor = new BaseEventProcessor {
      override protected val onEvent: EventHandler = {
        case "test" => completionPromise.success("handler1")
      }
    }

    // External thread calls requestBecome (this would fail with protected become)
    Future {
      processor.requestBecome(handler2, stackPreviousHandler = false)
      processor.post("test")
    }

    assert(completionPromise.future.futureValue == "handler2")
  }

  "requestUnbecome" should "safely revert to previous handler from external thread" in {
    val completionPromise = Promise[String]()

    val handler2: EventHandler = {
      case "test" => completionPromise.success("handler2")
    }

    val processor = new BaseEventProcessor {
      override protected val onEvent: EventHandler = {
        case "setup" => become(handler2, stackPreviousHandler = true)
        case "test" => completionPromise.success("handler1")
      }
    }

    // Setup: switch to handler2
    processor.post("setup")
    Thread.sleep(50)  // Give time for setup

    // External thread calls requestUnbecome
    Future {
      processor.requestUnbecome()
      Thread.sleep(50)  // Give time for unbecome to process
      processor.post("test")
    }

    assert(completionPromise.future.futureValue == "handler1")
  }
}

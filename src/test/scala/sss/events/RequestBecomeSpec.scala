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

  // Reasonable timeout for async operations (deterministic synchronization via promises)
  implicit val patienceConfig: PatienceConfig = PatienceConfig(timeout = Span(2, Seconds))

  implicit val sut: EventProcessingEngine = EventProcessingEngine()
  implicit val ec: ExecutionContext = ExecutionContext.global
  sut.start()

  "requestBecome" should "safely switch handlers from external thread" in {
    val becomeAck = Promise[Unit]()
    val completionPromise = Promise[String]()

    val handler2: EventHandler = {
      case "ack" => becomeAck.success(())
      case "test" => completionPromise.success("handler2")
    }

    val processor = new BaseEventProcessor {
      override protected val onEvent: EventHandler = {
        case "test" => completionPromise.success("handler1")
      }
    }
    sut.register(processor) // Register after construction completes

    // External thread calls requestBecome (this would fail with protected become)
    processor.requestBecome(handler2, stackPreviousHandler = false)

    // Wait for handler to be installed by posting a message only handler2 handles
    processor.post("ack")
    becomeAck.future.futureValue

    // Now post the test message
    processor.post("test")

    assert(completionPromise.future.futureValue == "handler2")
  }

  "requestUnbecome" should "safely revert to previous handler from external thread" in {
    val setupComplete = Promise[Unit]()
    val unbecomeAck = Promise[Unit]()
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
        case "ack" => unbecomeAck.success(())
        case "test" => completionPromise.success("handler1")
      }
    }
    sut.register(processor) // Register after construction completes

    // Setup: switch to handler2 and wait for it to be ready
    processor.post("setup")
    setupComplete.future.futureValue

    // External thread calls requestUnbecome (would fail with protected unbecome)
    processor.requestUnbecome()

    // Wait for unbecome to complete by posting a message only handler1 handles
    processor.post("ack")
    unbecomeAck.future.futureValue

    // Now post the test message
    processor.post("test")

    assert(completionPromise.future.futureValue == "handler1")
  }
}

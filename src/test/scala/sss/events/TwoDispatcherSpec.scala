package sss.events

import org.scalatest.concurrent.ScalaFutures.convertScalaFuture
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sss.events.TestEventProcessor.{CompleteTest, StartTest}

import scala.concurrent.Promise
import scala.concurrent.duration.DurationInt

/**
  * Created by alan on 2/11/16.
  */
class TwoDispatcherSpec extends AnyFlatSpec with Matchers {

  val successPromise: Promise[Boolean] = Promise()
  val blockedPromise: Promise[Boolean] = Promise()

  // Create config: 1 thread for subscriptions, 1 thread for default dispatcher, 1 thread for OTHER dispatcher
  val config = EngineConfig(
    schedulerPoolSize = 2,
    threadDispatcherAssignment = Array(
      Array("subscriptions"),  // Thread 0 works on subscriptions dispatcher
      Array(""),               // Thread 1 works on default dispatcher
      Array("OTHER")           // Thread 2 works on OTHER dispatcher
    ),
    defaultQueueSize = 10000,
    backoff = BackoffConfig(
      baseDelayMicros = 10,
      multiplier = 1.5,
      maxDelayMicros = 10000
    )
  )

  implicit val sut: EventProcessingEngine = EventProcessingEngine(config)
  sut.start()

  "Free dispatcher" should "process messages when default blocked" in {

    val test1HogsTheThread = sut.builder().withHandler {
      case x =>
      Thread.sleep(100) //default dispatcher is blocked now!
    }.build()

    val test2CannotProceed = sut.builder().withHandler {
      case x =>
        if(successPromise.isCompleted) {
          blockedPromise.success(true)
        } else {
          blockedPromise.success(false)
        }

    }.build()

    val test3OtherDispatcher = sut.builder().withCreateHandler {
      ep => {
        case "START" =>
          ep.engine.scheduler.schedule(ep.id, "WORKS!", 50.millis)

        case "WORKS!" =>
          successPromise.success(true)
      }
    }.withDispatcher(DispatcherName.validated("OTHER", config).get).build()

    test1HogsTheThread ! "BLOCKS"
    test2CannotProceed ! "WONT HAPPEN"
    test3OtherDispatcher ! "START"
    val result = successPromise.future.futureValue
    assert(result, "The `other` dispatcher didn't work")
    val resultOfBlocked = blockedPromise.future.futureValue
    assert(resultOfBlocked, "The `blocked` dispatcher didn't unblock")

    sut.stop(test3OtherDispatcher.id)
    sut.stop(test1HogsTheThread.id)
    sut.stop(test2CannotProceed.id)
  }


}

package sss.events

import org.scalatest.concurrent.PatienceConfiguration
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
    defaultQueueSize = 10000
  )

  implicit val sut: EventProcessingEngine = EventProcessingEngine(config)
  sut.start()

  "Free dispatcher" should "process messages when default blocked" in {

    // 1. Create and immediately send to test1 to block the default dispatcher
    val test1HogsTheThread = sut.builder().withHandler {
      case x =>
      Thread.sleep(100) //default dispatcher is blocked now!
    }.build()
    test1HogsTheThread ! "BLOCKS"

    // 2. Create and immediately send to test3 on OTHER dispatcher
    val test3OtherDispatcher = sut.builder().withCreateHandler {
      ep => {
        case "START" =>
          ep.engine.scheduler.schedule(ep.id, "WORKS!", 50.millis)

        case "WORKS!" =>
          successPromise.success(true)
      }
    }.withDispatcher(DispatcherName.validated("OTHER", config).get).build()
    test3OtherDispatcher ! "START"

    // 3. Wait for OTHER dispatcher to complete (proves it worked while default was blocked)
    val result = successPromise.future.futureValue
    assert(result, "The `other` dispatcher didn't work")

    // 4. Create test2 and immediately send message (default dispatcher should be unblocked by now)
    val test2CannotProceed = sut.builder().withHandler {
      case x =>
        blockedPromise.success(successPromise.isCompleted)
    }.build()
    test2CannotProceed ! "WONT HAPPEN"

    // 5. Verify test2 runs and sees that successPromise was already completed
    val resultOfBlocked = blockedPromise.future.futureValue(PatienceConfiguration.Timeout(1.seconds))
    assert(resultOfBlocked, "The `blocked` dispatcher didn't unblock")

    sut.stop(test3OtherDispatcher.id)
    sut.stop(test1HogsTheThread.id)
    sut.stop(test2CannotProceed.id)
  }


}

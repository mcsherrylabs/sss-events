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

  val dispConfig = Map(("", 1), ("OTHER", 1))
  val successPromise: Promise[Boolean] = Promise()
  val blockedPromise: Promise[Boolean] = Promise()

  implicit val sut: EventProcessingEngine = EventProcessingEngine(dispatchers = dispConfig)
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
    }.withDispatcher("OTHER").build()

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

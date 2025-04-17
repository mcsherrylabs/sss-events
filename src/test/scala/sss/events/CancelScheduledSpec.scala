package sss.events

import org.scalatest.concurrent.ScalaFutures.convertScalaFuture
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sss.events.EventProcessor.CreateEventHandler
import sss.events.Scheduler.{Schedule, ScheduledResult}
import sss.events.Scheduler.ScheduledResult.ScheduledResult

import scala.concurrent.Promise
import scala.concurrent.duration.DurationInt
import scala.util.Random

/**
  * Created by alan on 2/11/16.
  */
class CancelScheduledSpec extends AnyFlatSpec with Matchers {

  implicit val sut: EventProcessingEngine = EventProcessingEngine()
  sut.start()

  "EventEngine" should "send as scheduled" in {
    val isGood = Promise[ScheduledResult]()
    val evCreate: CreateEventHandler = ep => {

      val cancellable = ep.engine.scheduler.schedule(ep.id, "MSG", 10.millis)

      {
        case "MSG" if !cancellable.isCancelled() =>
          isGood.completeWith(cancellable.outcome)

      }

    }

    sut.newEventProcessor(Left(evCreate))

    assert(isGood.future.futureValue == ScheduledResult.Posted)
  }

  it should "not send if cancelled" in {
    val isGood = Promise[ScheduledResult]()

    val evProcessing: CreateEventHandler = ep => {

      val cancellable = ep.engine.scheduler.schedule(ep.id, "MSG", 100.millis)
      ep ! cancellable

      {
        case c: Schedule if c.cancel() =>
          assert(c.isCancelled())
          isGood.completeWith(c.outcome)

        case "MSG" =>
          isGood.completeWith(cancellable.outcome)
      }

    }

    sut.newEventProcessor(Left(evProcessing))

    assert(isGood.future.futureValue == ScheduledResult.Cancelled)
  }

  it should "fail if no such id" in {

    sut.builder().withCreateHandler(ep => {

      val cancellable = ep.engine.scheduler.schedule(Random.nextString(10), "MSG", 0.millis)
      assert(cancellable.outcome.futureValue == ScheduledResult.FailedUnRegistered)

      {
        case x =>
      }

    }).build()


  }
}

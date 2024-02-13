package sss.events

import org.scalatest.concurrent.ScalaFutures.convertScalaFuture
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
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
  sut.start(1)

  "EventEngine" should "send as scdeduled" in {
    val isGood = Promise[ScheduledResult]()
    sut.newEventProcessor(ep => {

      val cancellable = ep.engine.scheduler.schedule(ep.id, "MSG", 100.millis)

      {
        case "MSG" if !cancellable.isCancelled() =>
          isGood.completeWith(cancellable.outcome)

      }

    })

    assert(isGood.future.futureValue == ScheduledResult.Posted)
  }

  it should "not send if cancelled" in {
    val isGood = Promise[ScheduledResult]()
    sut.newEventProcessor(ep => {

      val cancellable = ep.engine.scheduler.schedule(ep.id, "MSG", 100.millis)
      ep ! cancellable

      {
        case c: Schedule if c.cancel() =>
          assert(c.isCancelled())
          isGood.completeWith(cancellable.outcome)

        case "MSG" =>
          isGood.completeWith(cancellable.outcome)

      }

    })

    assert(isGood.future.futureValue == ScheduledResult.Cancelled)
  }

  it should "fail if no such id" in {

    sut.newEventProcessor(ep => {

      val cancellable = ep.engine.scheduler.schedule(Random.nextString(10), "MSG", 0.millis)
      assert(cancellable.outcome.futureValue == ScheduledResult.FailedUnRegistered)

      {
        case x =>
      }

    })


  }
}
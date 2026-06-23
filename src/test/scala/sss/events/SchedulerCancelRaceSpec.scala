package sss.events

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._

/**
 * Regression repro for the `Scheduler.Schedule` cancel-vs-fire double-completion.
 *
 * `Schedule.cancel()` completes the result Promise under `result.synchronized` after a
 * `!result.isCompleted` check, but the scheduler's fire path completes the SAME Promise
 * (`result.result.success(Posted)`) WITHOUT holding that monitor. So the fire can complete
 * the Promise between cancel's `isCompleted` check and its `success(Cancelled)` call →
 * `cancel()` throws `IllegalStateException: Promise already completed`.
 *
 * That throw surfaces on the EP dispatcher thread (a caller cancelling a just-fired timer),
 * where `EventProcessingEngine.processTask` catches it as a TaskException and re-posts the
 * tuple — silently dropping the real message. Downstream (evolver `TipGossipManager`) this
 * killed the sync watchdog and froze a follower node.
 *
 * delay=0 maximises fire/cancel overlap; over many iterations the window is hit reliably on
 * the buggy code. With completion via `trySuccess` (atomic, idempotent) it can never throw.
 */
class SchedulerCancelRaceSpec extends AnyFlatSpec with Matchers {

  "Schedule.cancel racing the scheduler fire path" should
    "never throw 'Promise already completed'" in {
    implicit val eng: EventProcessingEngine = EventProcessingEngine()
    eng.start()
    // A live processor registered under "race" so the fire path takes the post→success branch.
    eng.builder().withCreateHandler(_ => { case _ => () }).withId("race").build()

    @volatile var caught: Option[Throwable] = None
    var i = 0
    while (i < 200000 && caught.isEmpty) {
      val s = eng.scheduler.schedule("race", "tick", 0.millis) // fires ASAP on the pool thread
      try s.cancel()                                            // races the fire-path completion
      catch { case t: Throwable => caught = Some(t) }
      i += 1
    }
    eng.stop("race")

    caught.map(t => s"${t.getClass.getSimpleName}: ${t.getMessage} (iter $i)") shouldBe None
  }
}

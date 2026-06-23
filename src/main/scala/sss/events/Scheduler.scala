package sss.events

import sss.events.Scheduler.Schedule
import sss.events.Scheduler.ScheduledResult.ScheduledResult

import java.util.concurrent.{Executors, ScheduledExecutorService, TimeUnit}
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{Future, Promise}

object Scheduler {

  object ScheduledResult extends Enumeration {
    type ScheduledResult = Value
    val Posted = Value(0)
    val Cancelled = Value(1)
    val FailedUnRegistered = Value(2)
    val FailedQueueFull = Value(3)
  }
  case class Schedule(private[events] val result: Promise[ScheduledResult] = Promise()) {

    private[events] var cancelIt: Boolean = false

    def cancel(): Boolean = result.synchronized {
      val wasNotAlreadyCancelledOrCompleted = !cancelIt && !result.isCompleted
      cancelIt = true
      if(wasNotAlreadyCancelledOrCompleted) {
        // trySuccess, not success: the fire path (schedule's Runnable) completes this same
        // Promise WITHOUT holding `result`'s monitor, so it can complete between the
        // isCompleted check above and here. success() would then throw "Promise already
        // completed" on the caller's thread (surfacing as an EP TaskException that drops the
        // real message); trySuccess is an atomic, idempotent no-op when already completed.
        result.trySuccess(ScheduledResult.Cancelled)
      }
      wasNotAlreadyCancelledOrCompleted
    }

    def isCancelled(): Boolean = result.synchronized(cancelIt)

    def outcome: Future[ScheduledResult] = result.future
  }

  def apply(numThreadsInSchedulerPool: Int)(implicit registrar: Registrar): Scheduler = {
    val schedulerExecutor = Executors.newScheduledThreadPool(numThreadsInSchedulerPool)
    new Scheduler(schedulerExecutor)
  }
}
class Scheduler(scheduledExecutorService: ScheduledExecutorService)(implicit registrar: Registrar) extends Logging{

  def schedule(whos: Set[String], msg: Any, delay: FiniteDuration): Seq[Schedule] = {
    whos.toSeq.map(schedule(_, msg, delay))
  }

  def schedule(who: String, msg: Any, delay: FiniteDuration): Schedule = {
    val result = Schedule()

    scheduledExecutorService.schedule(new Runnable {
      override def run(): Unit = {
        if (!result.isCancelled()) {
          registrar.get(who) match {
            case Some(found) =>
              if (!found.post(msg)) {
                result.result.trySuccess(Scheduler.ScheduledResult.FailedQueueFull)
                log.error(s"$who q full! CATASTROPHE!")
              } else {
                result.result.trySuccess(Scheduler.ScheduledResult.Posted)
              }
            case None =>
              result.result.trySuccess(Scheduler.ScheduledResult.FailedUnRegistered)
              log.info(s"No id $who found for scheduled task")
          }
        }
      }
    }, delay.toMillis, TimeUnit.MILLISECONDS)

    result
  }
}

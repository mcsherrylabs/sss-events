package sss.events.events


import java.util.concurrent.{Executors, ScheduledExecutorService, TimeUnit}
import scala.concurrent.duration.FiniteDuration

object Scheduler {
  def apply(numThreadsInSchedulerPool: Int)(implicit registrar: Registrar): Scheduler = {
    val schedulerExecutor = Executors.newScheduledThreadPool(numThreadsInSchedulerPool)
    new Scheduler(schedulerExecutor)
  }
}
class Scheduler(scheduledExecutorService: ScheduledExecutorService)(implicit registrar: Registrar) extends Logging{

  def schedule(whos: Set[String], msg: Any, delay: FiniteDuration): Unit = {
    whos.foreach(schedule(_, msg, delay))
  }

  def schedule(who: String, msg: Any, delay: FiniteDuration): Unit = {
    scheduledExecutorService.schedule(new Runnable {
      override def run(): Unit = {
        registrar.get(who) match {
          case Some(found) =>
            if(!found.post(msg)) {
              log.error(s"$who q full! CATASTROPHE!")
            }
          case None => log.info(s"No id $who found for scheduled task")
        }
      }
    }, delay.toMillis, TimeUnit.MILLISECONDS)
  }
}

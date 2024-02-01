package sss.events.events

import sss.events.events.EventProcessor.EventProcessorId

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.ExecutionContext
import scala.util.Try

object EventProcessingEngine {
  def apply(numThreadsInSchedulerPool: Int = 2): EventProcessingEngine = {
    implicit val registrar: Registrar = new Registrar
    implicit val scheduler = Scheduler(numThreadsInSchedulerPool)
    import scala.concurrent.ExecutionContext.Implicits.global //TODO
    new EventProcessingEngine
  }
}

class EventProcessingEngine(implicit val scheduler: Scheduler,
                            val registrar: Registrar,
                            val cpuEc: ExecutionContext)
  extends Logging
  with SubscriptionSupport {

  private val MaxPollTimeMs = 40
  private val queueSize = 10000

  private val taskLock = new Object()
  private val lock = new Object()

  private val q: LinkedBlockingQueue[EventProcessor] = new LinkedBlockingQueue(queueSize)

  private var keepGoing: AtomicBoolean = new AtomicBoolean(true)
  private var threads: List[Thread] = List.empty

  protected val subscriptions: Subscriptions = new Subscriptions()(this)

  def register[T](am: EventProcessor): Unit = lock.synchronized {
    if(registrar.get(am.id).isEmpty) {
      registrar.register(am)
      q.put(am)
    }
  }

  def stop(id: EventProcessorId): Unit = lock.synchronized {
    q.removeIf(_.id == id)
    registrar.unRegister(id)
  }

  private def processTask(noTaskCount: Long): Boolean = {
    val am = q.take()
    try {
      Thread.currentThread().setName(am.id)
      Option(am.poll(noTaskCount)).map { task =>
        Try {
          taskLock.synchronized {
            am.processEvent(task)
          }
        } recover {
          case e =>
            log.warn(s"TaskException: ${e.getMessage}")
            am.post((task, e))
        }
      }.isDefined

    } finally {
      q.put(am)
    }

  }


  private def createRunnable(): Runnable = () => {
    var noTaskCount = 0 // is this var safe? TODO
    while (keepGoing.get()) {
      //get a number of ms to wait for a task, this prevents busy loops when there are no tasks
      //make sure 40ms is the worst case reaction time to a new message
      //make sure 1ms is the minimum wait time to prevent busy loops (when there are No tasks)
      val taskWaitTime = {
        if(noTaskCount == 0) 0
        else {
          val numQs = q.size()
          val tmp = if (numQs == 0) MaxPollTimeMs
          else Math.max(1, (MaxPollTimeMs / numQs).toLong)

          if (noTaskCount > tmp) tmp
          else noTaskCount
        }
      }

      if (processTask(taskWaitTime)) {
        noTaskCount = 0
      } else {
        noTaskCount = noTaskCount + 1
      }

    }
  }

  def shutdown(): Unit = {
    keepGoing.set(false)
    lock.synchronized {
      threads.foreach(_.join())
    }
  }

  def numThreadsStarted: Int = lock.synchronized(threads.size)

  def start(numThreads: Int): Unit = {
    require(numThreads > 0, s"Must start some threads")
    require(numThreads >= threads.size, s"${threads.size} threads already started, cannot start only $numThreads")

    threads.size until numThreads foreach { _ =>
      val t = new Thread(createRunnable())
      lock.synchronized {
        threads = threads :+ t
      }
      t.start()
    }

  }
}

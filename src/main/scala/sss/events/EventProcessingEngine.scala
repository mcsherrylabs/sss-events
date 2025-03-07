package sss.events

import sss.events.EventProcessor.{CreateEventHandler, EventHandler, EventProcessorId}

import java.time.Instant
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.ExecutionContext
import scala.util.Try

case class EventProcessingEngineConfig(
                                        numThreadsInSchedulerPool: Int = 2,
                                        dispatchers: Map[String, Int] = Map("" -> 1),
                                        maxQuietTimeSleepMs: Int = 4,
                                        maxQueueSize: Int = 1024,
                                        batchSize: Int = 5,
                                      ) {
  require(dispatchers.values.forall(_ > 0), s"dispatcherConfig needs to have a sensible number of threads ${dispatchers}")
  require(maxQuietTimeSleepMs >= 0, "maxQuietTimeSleepMs cannot be negative")
  require(batchSize >= 0, "batchSize cannot be negative")
  require(numThreadsInSchedulerPool > 0, "numThreadsInSchedulerPool must be greater than 1")
}

object EventProcessingEngine {

  def apply(): EventProcessingEngine = {
    apply(EventProcessingEngineConfig())

  }
  def apply(config: EventProcessingEngineConfig): EventProcessingEngine = {
    implicit val registrar: Registrar = new Registrar
    implicit val scheduler = Scheduler(config.numThreadsInSchedulerPool)
    import scala.concurrent.ExecutionContext.Implicits.global //TODO
    new EventProcessingEngine(config)
  }

}

class EventProcessingEngine(config: EventProcessingEngineConfig)(implicit val scheduler: Scheduler,
                            val registrar: Registrar,
                            val cpuEc: ExecutionContext)
  extends Logging {



  private val MaxPollTimeMs = config.maxQuietTimeSleepMs
  private val queueSize = config.maxQueueSize

  private val lock = new Object()

  private val dispatchers: Map[String, LinkedBlockingQueue[BaseEventProcessor[_]]] = config.dispatchers.map {
    case (name, _) => (name -> new LinkedBlockingQueue(queueSize))
  }


  private val keepGoing: AtomicBoolean = new AtomicBoolean(true)
  private var threads: List[Thread] = List.empty

  val subscriptions: Subscriptions = new Subscriptions()(this)

  def register[M](am: BaseEventProcessor[M]): Unit = lock.synchronized {
    if(registrar.get(am.id).isEmpty) {
      registrar.register(am)
      dispatchers(am.dispatcherName).put(am)
    }
  }

  def builder(): Builder = new Builder(this)

  def newEventProcessor(
                         createEventHandlerOrEventHandler: Either[CreateEventHandler, EventHandler],
                         anId: Option[String] = None,
                         channels: Set[String] = Set.empty,
                         parentOpt: Option[EventProcessor] = None,
                         dispatcher: String = ""): EventProcessor = {

    new BaseEventProcessor[Any]()(this) {
      override def id: EventProcessorId = anId.getOrElse(super.id)

      override def parent: EventProcessor = parentOpt.orNull

      override def dispatcherName: String = dispatcher

      override protected val onEvent: EventHandler = createEventHandlerOrEventHandler match {
        case Left(create) => {
          create(this)
        }
        case Right(handler) => handler
      }

      if (channels.nonEmpty) {
        subscribe(channels)
      }

    }
  }

  def newEventProcessor(support: EventProcessorSupport): EventProcessor = {
    newEventProcessor(Left(support.createOnEvent), support.id, support.channels, support.parent)
  }


  def stop(id: EventProcessorId): Unit = lock.synchronized {
    dispatchers.values.foreach(_.removeIf(_.id == id))
    registrar.unRegister(id)
  }

  private def processTasks(num: Int, am: BaseEventProcessor[_]): Unit = {
    0 until num foreach { _ =>
      val task = am.take()
      processTaskImpl(am, task)
    }
  }

  private def processTaskImpl(am: BaseEventProcessor[_], task: Any) = {
    Try {
        am.processEvent(task)
    } recover {
      case e =>
        log.warn(s"TaskException: ${e.getMessage}")
        am.post((task, e))
    }
  }

  private def processTask(taskWaitTimeMs: Long, q: LinkedBlockingQueue[BaseEventProcessor[_]]): Boolean = {

    val am = q.take()
    try {
      Thread.currentThread().setName(am.id)
      val numTasksToProcess = Math.min(q.size(), config.batchSize)

      if(numTasksToProcess > 0) {
        am.taskLock.synchronized {
          processTasks(numTasksToProcess, am)
        }
        true
      } else {
        Option(am.poll(taskWaitTimeMs)).map { task =>
          am.taskLock.synchronized {
            processTaskImpl(am, task)
          }
        }.isDefined
      }
    } finally {
      q.put(am)
    }

  }


  private def calculateWaitTime(noTaskCount: Int, numQs: Int): Long = {
    if (noTaskCount == 0) 0
    else {
      val tmp = if (numQs == 0) MaxPollTimeMs
      else Math.max(1, (MaxPollTimeMs / numQs).toLong)

      if (noTaskCount > tmp) tmp
      else noTaskCount
    }
  }

  private def createRunnable(dispatcherName: String): Runnable = () => Try {
    var noTaskCount = 0 // is this var safe? TODO
    val q = dispatchers(dispatcherName)
    while (keepGoing.get()) {
      //get a number of ms to wait for a task, this prevents busy loops when there are no tasks
      //make sure 40ms is the worst case reaction time to a new message
      //make sure 1ms is the minimum wait time to prevent busy loops (when there are No tasks)
      val taskWaitTime = calculateWaitTime(noTaskCount, q.size)

      if (processTask(taskWaitTime, q)) {
        noTaskCount = 0
      } else {
        noTaskCount = noTaskCount + 1
      }
    }
  } recover {
    //if we were exiting anyway, ignore interrupt
    case _: InterruptedException if !keepGoing.get() =>
  }

  def shutdown(): Unit = {
    keepGoing.set(false)
    lock.synchronized {
      threads.foreach(t => {
        t.interrupt()
        t.join()
      })
    }

  }

  def numThreadsStarted: Int = lock.synchronized(threads.size)

  def start(): Unit = {
    lock.synchronized {
      config.dispatchers.foreach {
        case (name, numThreads) =>
          0 until numThreads foreach { _ =>
            val t = new Thread(createRunnable(name))
            threads = threads :+ t
            t.start()
          }
      }
    }
  }
}

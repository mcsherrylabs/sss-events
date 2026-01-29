package sss.events

import sss.events.EventProcessor.{CreateEventHandler, EventHandler, EventProcessorId}

import java.util.concurrent.{ConcurrentLinkedQueue, LinkedBlockingQueue}
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport
import scala.util.Try

/** Factory and companion object for [[EventProcessingEngine]]. */
object EventProcessingEngine {

  /** Creates a new EventProcessingEngine with default configuration.
    *
    * @param numThreadsInSchedulerPool number of threads for the scheduled event pool (default: 2)
    * @param dispatchers map of dispatcher names to thread counts (default: single unnamed dispatcher with 1 thread)
    * @return a new EventProcessingEngine instance
    */
  def apply(numThreadsInSchedulerPool: Int = 2,
            dispatchers: Map[String, Int] = Map(("" -> 1))): EventProcessingEngine = {
    implicit val registrar: Registrar = new Registrar
    implicit val scheduler: Scheduler = Scheduler(numThreadsInSchedulerPool)
    implicit val dispatcherImp: Map[EventProcessorId, Int] = dispatchers
    new EventProcessingEngine
  }

}

/** Central event processing engine that manages thread pools and routes messages to event processors.
  *
  * The engine maintains:
  *  - Thread pools (dispatchers) for concurrent event processing
  *  - A registrar for processor lookup by ID
  *  - A subscription system for pub/sub messaging
  *  - A scheduler for time-delayed event delivery
  *
  * Each processor is assigned to a dispatcher (thread pool), and each active processor gets its own thread
  * from that pool for message processing.
  *
  * @param scheduler the scheduler for delayed event delivery
  * @param registrar the registrar for processor ID lookup
  * @param dispatcherConfig map of dispatcher names to thread counts
  */
class EventProcessingEngine(implicit val scheduler: Scheduler,
                            val registrar: Registrar,
                            dispatcherConfig: Map[String, Int])
  extends Logging {

  require(dispatcherConfig.values.forall(_ > 0), s"dispatcherConfig needs to have a sensible number of threads $dispatcherConfig")

  private val MaxPollTimeMs = 40
  private val queueSize = 10000

  private val lock = new Object()

  private val dispatchers: Map[String, ConcurrentLinkedQueue[BaseEventProcessor]] = dispatcherConfig.map {
    case (name, _) => (name -> new ConcurrentLinkedQueue[BaseEventProcessor]())
  }


  private var keepGoing: AtomicBoolean = new AtomicBoolean(true)
  private var threads: List[Thread] = List.empty

  /** The subscription system for pub/sub messaging. */
  val subscriptions: Subscriptions = new Subscriptions()(this)

  /** Registers an event processor with the engine and adds it to its dispatcher queue.
    *
    * @param am the event processor to register
    */
  def register(am: BaseEventProcessor): Unit = lock.synchronized {
    if(registrar.get(am.id).isEmpty) {
      registrar.register(am)
      if (!dispatchers(am.dispatcherName).offer(am)) {
        log.error(s"Failed to add processor ${am.id} to dispatcher queue!")
      }
    }
  }

  /** Returns a new builder for creating event processors with fluent API.
    *
    * @return a new Builder instance
    */
  def builder(): Builder = new Builder(this)

  /** Creates a new event processor with specified configuration.
    *
    * @param createEventHandlerOrEventHandler either a handler creation function or a handler directly
    * @param anId optional unique identifier for the processor
    * @param channels set of subscription channels to subscribe to
    * @param parentOpt optional parent processor
    * @param dispatcher name of the dispatcher (thread pool) to use
    * @return a new EventProcessor instance
    */
  def newEventProcessor(
                         createEventHandlerOrEventHandler: Either[CreateEventHandler, EventHandler],
                         anId: Option[String] = None,
                         channels: Set[String] = Set.empty,
                         parentOpt: Option[EventProcessor] = None,
                         dispatcher: String = ""): EventProcessor = {

    new BaseEventProcessor()(this) {
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

  /** Creates a new event processor from an EventProcessorSupport trait implementation.
    *
    * @param support the EventProcessorSupport implementation
    * @return a new EventProcessor instance
    */
  def newEventProcessor(support: EventProcessorSupport): EventProcessor = {
    newEventProcessor(Left(support.createOnEvent), support.id, support.channels, support.parent)
  }

  /** Stops and unregisters an event processor by ID.
    *
    * @param id the processor ID to stop
    */
  def stop(id: EventProcessorId): Unit = lock.synchronized {
    dispatchers.values.foreach(_.removeIf(_.id == id))
    registrar.unRegister(id)
  }

  private def processTask(taskWaitTimeMs: Long, q: ConcurrentLinkedQueue[BaseEventProcessor]): Boolean = {

    // Non-blocking poll with parking when empty
    var am = q.poll()
    while (am == null && keepGoing.get()) {
      LockSupport.parkNanos(100_000) // Park for 100 microseconds
      am = q.poll()
    }

    if (am == null) return false // Shutting down

    try {
      Thread.currentThread().setName(am.id)
      Option(am.poll(taskWaitTimeMs)).map { task =>
        Try {
          am.taskLock.synchronized {
            am.processEvent(task)
          }
        } recover {
          case e =>
            log.warn(s"TaskException: ${e.getMessage}")
            am.post((task, e))
        }
      }.isDefined

    } finally {
      if (!q.offer(am)) {
        log.error(s"Failed to return processor ${am.id} to dispatcher queue!")
      }
    }

  }


  private def calculateWaitTime(noTaskCount: Int, numQs: Int): Long = {
    if (noTaskCount == 0) 0
    else {
      val tmp: Long = if (numQs == 0) MaxPollTimeMs
      else Math.max(1, (MaxPollTimeMs / numQs).toLong)

      if (noTaskCount > tmp) tmp
      else noTaskCount
    }
  }

  private def createRunnable(dispatcherName: String): Runnable = () => {
    try {
      // Thread-safe: noTaskCount is thread-local, each dispatcher thread has its own instance
      var noTaskCount = 0
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
    } catch {
      //if we were exiting anyway, ignore interrupt
      case _: InterruptedException if !keepGoing.get() =>
    }
  }

  /** Shuts down the engine, interrupting and joining all dispatcher threads. */
  def shutdown(): Unit = {
    keepGoing.set(false)
    lock.synchronized {
      threads.foreach(t => {
        t.interrupt()
        t.join()
      })
    }

  }

  /** Returns the number of dispatcher threads currently started.
    *
    * @return count of active threads
    */
  def numThreadsStarted: Int = lock.synchronized(threads.size)

  /** Starts all dispatcher threads. Call this after creating the engine and processors. */
  def start(): Unit = {
    lock.synchronized {
      dispatcherConfig.foreach {
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

package sss.events

import sss.events.EventProcessor.{CreateEventHandler, EventHandler, EventProcessorId}

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport
import scala.util.Try

/** Factory and companion object for [[EventProcessingEngine]]. */
object EventProcessingEngine {

  /** Creates a new EventProcessingEngine from HOCON configuration.
    *
    * Loads configuration from sss-events.engine in application.conf or reference.conf.
    *
    * @return a new EventProcessingEngine instance
    * @throws RuntimeException if configuration is invalid
    */
  def apply(): EventProcessingEngine = {
    val config = EngineConfig.loadOrThrow()
    apply(config)
  }

  /** Creates a new EventProcessingEngine with explicit configuration.
    *
    * @param config Engine configuration
    * @return a new EventProcessingEngine instance
    */
  def apply(config: EngineConfig): EventProcessingEngine = {
    implicit val registrar: Registrar = new Registrar
    implicit val scheduler: Scheduler = Scheduler(config.schedulerPoolSize)
    implicit val engineConfig: EngineConfig = config
    new EventProcessingEngine
  }

}

/** Central event processing engine that manages thread pools and routes messages to event processors.
  *
  * The engine maintains:
  *  - Thread pools (dispatchers) for concurrent event processing with lock-based contention management
  *  - A registrar for processor lookup by ID
  *  - A subscription system for pub/sub messaging
  *  - A scheduler for time-delayed event delivery
  *
  * Each processor is assigned to a dispatcher, and threads work on dispatchers according to the
  * thread-dispatcher-assignment configuration. Threads use tryLock() and exponential backoff
  * to efficiently manage contention.
  *
  * @param scheduler the scheduler for delayed event delivery
  * @param registrar the registrar for processor ID lookup
  * @param config engine configuration loaded from HOCON
  */
class EventProcessingEngine(implicit val scheduler: Scheduler,
                            val registrar: Registrar,
                            config: EngineConfig)
  extends Logging {

  private val MaxPollTimeMs = 40

  private val lock = new Object()

  // Create dispatchers from configuration
  private val dispatchers: Map[String, LockedDispatcher] =
    config.validDispatcherNames.map { name =>
      name -> LockedDispatcher(name)
    }.toMap

  // Create exponential backoff strategy
  private val backoffStrategy = ExponentialBackoff.fromConfig(config.backoff)

  private var keepGoing: AtomicBoolean = new AtomicBoolean(true)
  private var threads: List[Thread] = List.empty

  /** The subscription system for pub/sub messaging. */
  val subscriptions: Subscriptions = new Subscriptions()(this)

  /** Returns the set of valid dispatcher names from configuration.
    * Processors must choose from these dispatcher names.
    *
    * @return set of valid dispatcher names
    */
  def validDispatcherNames: Set[String] = config.validDispatcherNames

  /** Registers an event processor with the engine and adds it to its dispatcher queue.
    *
    * @param am the event processor to register
    * @throws IllegalArgumentException if processor's dispatcherName is not in valid set
    */
  def register(am: BaseEventProcessor): Unit = lock.synchronized {
    // Validate dispatcher name
    val dispatcherNameStr = am.dispatcherName.value
    if (!validDispatcherNames.contains(dispatcherNameStr)) {
      throw new IllegalArgumentException(
        s"Processor ${am.id} has invalid dispatcherName '${dispatcherNameStr}'. " +
        s"Valid dispatchers: ${validDispatcherNames.mkString(", ")}"
      )
    }

    if(registrar.get(am.id).isEmpty) {
      registrar.register(am)
      val dispatcher = dispatchers(dispatcherNameStr)
      if (!dispatcher.queue.offer(am)) {
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
    * @param dispatcher name of the dispatcher (thread pool) to use (must be in validDispatcherNames)
    * @return a new EventProcessor instance
    */
  def newEventProcessor(
                         createEventHandlerOrEventHandler: Either[CreateEventHandler, EventHandler],
                         anId: Option[String] = None,
                         channels: Set[String] = Set.empty,
                         parentOpt: Option[EventProcessor] = None,
                         dispatcher: DispatcherName = DispatcherName.Default): EventProcessor = {

    new BaseEventProcessor()(this) {
      override def id: EventProcessorId = anId.getOrElse(super.id)

      override def parent: EventProcessor = parentOpt.orNull

      override def dispatcherName: DispatcherName = dispatcher

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
    dispatchers.values.foreach(d => d.queue.removeIf(_.id == id))
    registrar.unRegister(id)
  }

  private def processTask(dispatcher: LockedDispatcher, taskWaitTimeMs: Long): Boolean = {
    // Non-blocking poll with parking when empty
    var am = dispatcher.queue.poll()
    while (am == null && keepGoing.get()) {
      LockSupport.parkNanos(100_000) // Park for 100 microseconds
      am = dispatcher.queue.poll()
    }

    if (am == null) return false // Shutting down

    try {
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
      if (!dispatcher.queue.offer(am)) {
        log.error(s"Failed to return processor ${am.id} to dispatcher queue!")
      }
    }
  }

  private def calculateWaitTime(noTaskCount: Int, queueSize: Int): Long = {
    if (noTaskCount == 0) 0
    else {
      val tmp: Long = if (queueSize == 0) MaxPollTimeMs
      else Math.max(1, (MaxPollTimeMs / queueSize).toLong)

      if (noTaskCount > tmp) tmp
      else noTaskCount
    }
  }

  private def createRunnable(assignedDispatchers: Array[String]): Runnable = () => {
    // Thread-local state
    var roundRobinIndex = 0
    var consecutiveFailures = 0
    var noTaskCount = 0
    var currentBackoffDelay = backoffStrategy.initialDelay

    while (keepGoing.get()) {
      val dispatcherName = assignedDispatchers(roundRobinIndex)
      val dispatcher = dispatchers(dispatcherName)

      // Try to acquire lock non-blocking
      if (dispatcher.lock.tryLock()) {
        try {
          // Calculate wait time based on queue size and no-task history
          val taskWaitTime = calculateWaitTime(noTaskCount, dispatcher.queue.size())

          if (processTask(dispatcher, taskWaitTime)) {
            // Successfully processed work
            noTaskCount = 0
            consecutiveFailures = 0
            currentBackoffDelay = backoffStrategy.initialDelay // Reset backoff
          } else {
            noTaskCount = noTaskCount + 1
          }
        } finally {
          dispatcher.lock.unlock()
        }

        // Move to next dispatcher in round-robin
        roundRobinIndex = (roundRobinIndex + 1) % assignedDispatchers.length

      } else {
        // Lock not acquired, try next dispatcher
        roundRobinIndex = (roundRobinIndex + 1) % assignedDispatchers.length
        consecutiveFailures += 1

        // If full round-robin cycle failed, apply exponential backoff
        if (consecutiveFailures >= assignedDispatchers.length) {
          backoffStrategy.sleep(currentBackoffDelay)
          currentBackoffDelay = backoffStrategy.nextDelay(currentBackoffDelay)
        }
      }
    }
  }

  /** Shuts down the engine, unparking and joining all dispatcher threads. */
  def shutdown(): Unit = {
    keepGoing.set(false)
    lock.synchronized {
      threads.foreach(t => {
        LockSupport.unpark(t)
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
      config.threadDispatcherAssignment.zipWithIndex.foreach {
        case (assignedDispatchers, threadIdx) =>
          val t = new Thread(createRunnable(assignedDispatchers))
          t.setName(s"sss-events-dispatcher-thread-$threadIdx")
          threads = threads :+ t
          t.start()
      }
    }
  }
}

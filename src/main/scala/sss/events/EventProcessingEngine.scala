package sss.events

import sss.events.EventProcessor.{CreateEventHandler, EventHandler, EventProcessorId}

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport
import scala.jdk.CollectionConverters._
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
  * == Lock Ordering Protocol ==
  *
  * To prevent deadlock, the engine follows these lock ordering rules:
  *
  * 1. '''Single Dispatcher Lock''': Worker threads acquire locks on a single dispatcher at a time
  *    when polling for work or returning processors to the queue.
  *
  * 2. '''Multiple Dispatcher Locks''': When stop() needs to acquire locks on multiple dispatchers
  *    (when processor location is unknown), locks MUST be acquired in alphabetical order by
  *    dispatcher name. This ensures all threads acquire locks in the same order, preventing
  *    circular wait conditions.
  *
  * 3. '''Condition Variable Usage''':
  *    - `workAvailable`: Signaled when a processor is added to the dispatcher queue (register).
  *      Worker threads wait on this condition when the queue is empty.
  *    - `processorReturned`: Signaled when a worker thread returns a processor to the queue.
  *      stop() waits on this condition to coordinate with in-flight processing.
  *
  * 4. '''Lock-Free Operations''': The registrar is lock-free (ConcurrentHashMap) and can be
  *    accessed without holding dispatcher locks. However, for consistency, stop() checks the
  *    registrar before acquiring dispatcher locks.
  *
  * == Graceful Shutdown Protocol ==
  *
  * The stop() method uses a multi-phase approach to ensure clean shutdown:
  *
  * 1. '''Queue Draining''': Wait for processor's internal message queue to drain (with timeout)
  * 2. '''Stopping Flag''': Set processor.stopping = true to prevent worker threads from returning
  *    the processor to the dispatcher queue
  * 3. '''Wait for In-Flight Work''': Wait on processorReturned condition variable (with timeout)
  *    to allow any worker thread currently processing this processor to complete
  * 4. '''Queue Removal''': Remove processor from dispatcher queue (with proper lock ordering)
  * 5. '''Unregister''': Remove processor from registrar to complete shutdown
  *
  * @param scheduler the scheduler for delayed event delivery
  * @param registrar the registrar for processor ID lookup
  * @param config engine configuration loaded from HOCON
  */
class EventProcessingEngine(implicit val scheduler: Scheduler,
                            val registrar: Registrar,
                            val config: EngineConfig)
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

  // Reference is immutable; the AtomicBoolean itself provides thread-safe state changes
  private val keepGoing: AtomicBoolean = new AtomicBoolean(true)
  private var threads: List[Thread] = List.empty

  /** The subscription system for pub/sub messaging. */
  val subscriptions: Subscriptions = new Subscriptions()(this)
  register(subscriptions) // Register after construction completes

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
      } else {
        // Signal waiting threads that work is available
        dispatcher.lock.lock()
        try {
          dispatcher.workAvailable.signal()
        } finally {
          dispatcher.lock.unlock()
        }
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
    * @param queueSizeOpt optional queue size override (defaults to 100000)
    * @return a new EventProcessor instance
    */
  def newEventProcessor(
                         createEventHandlerOrEventHandler: Either[CreateEventHandler, EventHandler],
                         anId: Option[String] = None,
                         channels: Set[String] = Set.empty,
                         parentOpt: Option[EventProcessor] = None,
                         dispatcher: DispatcherName = DispatcherName.Default,
                         queueSizeOpt: Option[Int] = None): EventProcessor = {

    val processor = new BaseEventProcessor()(this) {
      // Override queueSize to use custom size if provided
      override def queueSize: Int = queueSizeOpt.getOrElse(super.queueSize)

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

    // Register after construction completes to avoid initialization order issues
    register(processor)
    processor
  }

  /** Creates a new event processor from an EventProcessorSupport trait implementation.
    *
    * @param support the EventProcessorSupport implementation
    * @return a new EventProcessor instance
    */
  def newEventProcessor(support: EventProcessorSupport): EventProcessor = {
    newEventProcessor(Left(support.createOnEvent), support.id, support.channels, support.parent)
  }

  /** Stops and unregisters an event processor by ID with graceful queue draining.
    *
    * This method waits for the processor's internal queue to drain before removing it,
    * preventing message loss during shutdown. If the queue doesn't drain within the
    * timeout period, critical errors are logged.
    *
    * The stop process is designed to avoid race conditions with active processing:
    * 1. Find which dispatcher contains the processor (before acquiring lock)
    * 2. Acquire the dispatcher's lock to coordinate with worker threads
    * 3. Remove processor from dispatcher queue while locked
    * 4. Unregister from the registrar
    *
    * @param id the processor ID to stop
    * @param timeoutMs timeout in milliseconds to wait for queue to drain (default: 30000ms)
    */
  def stop(id: EventProcessorId, timeoutMs: Long = 30000): Unit = {
    registrar.get(id) match {
      case Some(processor) =>
        val initialQueueSize = processor.currentQueueSize

        if (initialQueueSize > 0) {
          log.warn(s"Draining ${initialQueueSize} messages from processor ${id} queue before stopping (timeout: ${timeoutMs}ms)")

          val startTime = System.currentTimeMillis()
          var currentSize = initialQueueSize

          // Poll until queue is empty or timeout occurs
          while (currentSize > 0 && (System.currentTimeMillis() - startTime) < timeoutMs) {
            Thread.sleep(10) // Small sleep to avoid busy waiting
            currentSize = processor.currentQueueSize
          }

          val finalQueueSize = processor.currentQueueSize
          if (finalQueueSize > 0) {
            log.error(s"CRITICAL: Timeout waiting for processor ${id} queue to drain. ${finalQueueSize} messages remaining after ${timeoutMs}ms")
            log.error(s"CRITICAL: ${finalQueueSize} messages will be lost from processor ${id}")
          }
        }

        // Find which dispatcher contains this processor (search before setting stopping flag)
        // We need to identify the dispatcher first so we can wait on its condition variable
        val dispatcherOpt = dispatchers.values.find(d => {
          d.queue.asScala.exists(_.id == id)
        })

        // Set stopping flag AFTER queue draining to prevent worker threads from returning processor to queue
        // This must be done before we try to remove from dispatcher queue
        processor match {
          case base: BaseEventProcessor => base.stopping.set(true)
          case _ => // Non-BaseEventProcessor - shouldn't happen in practice
        }

        // Wait for in-flight processing to complete using condition variable
        // If the processor is currently being processed by a worker thread, we wait for either:
        // 1. The processor to be returned to the queue (worker finishes and sees stopping flag = false race)
        // 2. Timeout after 100ms (worker finishes and doesn't return due to stopping flag)
        dispatcherOpt.foreach { dispatcher =>
          dispatcher.lock.lock()
          try {
            val waitStartTime = System.currentTimeMillis()
            val waitTimeoutMs = 100L
            var processorInQueue = dispatcher.queue.asScala.exists(_.id == id)

            // Wait on condition variable until processor appears in queue or timeout
            while (!processorInQueue && (System.currentTimeMillis() - waitStartTime) < waitTimeoutMs) {
              val remainingMs = waitTimeoutMs - (System.currentTimeMillis() - waitStartTime)
              if (remainingMs > 0) {
                dispatcher.processorReturned.await(remainingMs, TimeUnit.MILLISECONDS)
                processorInQueue = dispatcher.queue.asScala.exists(_.id == id)
              }
            }
          } finally {
            dispatcher.lock.unlock()
          }
        }

        dispatcherOpt match {
          case Some(dispatcher) =>
            // Acquire the dispatcher's lock to coordinate with worker threads
            // This prevents the race condition where a worker thread has polled the processor
            // and is actively processing it while stop() tries to remove it
            dispatcher.lock.lock()
            try {
              // Remove from dispatcher queue while locked
              val removed = dispatcher.queue.removeIf(_.id == id)
              if (removed) {
                log.debug(s"Removed processor ${id} from dispatcher ${dispatcher.name}")
              } else {
                // Processor was not in queue - either:
                // 1. A worker has it and is processing (will see stopping flag and not return)
                // 2. Already removed by another thread
                log.debug(s"Processor ${id} not in queue (may be actively processing or already removed)")
              }
            } finally {
              dispatcher.lock.unlock()
            }

          case None =>
            // Processor not in any dispatcher queue - may already be removed or being processed
            // Check all dispatchers with locks to be safe
            log.debug(s"Processor ${id} not found in any dispatcher queue, checking with locks")

            // CRITICAL: Sort dispatchers by name to ensure consistent lock acquisition order
            // This prevents deadlock when multiple threads call stop() concurrently and need
            // to acquire locks on multiple dispatchers. All threads will acquire locks in the
            // same order (alphabetically by dispatcher name), preventing circular wait conditions.
            val sortedDispatchers = dispatchers.toSeq.sortBy(_._1).map(_._2)

            sortedDispatchers.foreach { dispatcher =>
              dispatcher.lock.lock()
              try {
                dispatcher.queue.removeIf(_.id == id)
              } finally {
                dispatcher.lock.unlock()
              }
            }
        }

        // Unregister from registrar after removal
        registrar.unRegister(id)

      case None =>
        log.warn(s"Attempted to stop processor ${id} but it was not registered")
    }
  }

  private def processTask(dispatcher: LockedDispatcher, taskWaitTimeMs: Long): Boolean = {
    // Non-blocking poll with condition variable wait when empty
    var am = dispatcher.queue.poll()
    while (am == null && keepGoing.get()) {
      // Wait on condition variable for work to arrive (100 microseconds timeout)
      dispatcher.lock.lock()
      try {
        dispatcher.workAvailable.await(100, TimeUnit.MICROSECONDS)
      } finally {
        dispatcher.lock.unlock()
      }
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
      // Critical: Check both stopping flag and registrar before returning processor to queue
      // This prevents race conditions between worker threads and stop():
      // 1. Check stopping flag - indicates stop() is in progress
      // 2. Check registrar - ensures processor is still registered
      // Either condition means we should NOT return the processor to the queue

      val isStopping = am.stopping.get()
      val isRegistered = registrar.get(am.id).isDefined

      if (isStopping) {
        log.debug(s"Processor ${am.id} is stopping (stopping flag set), not returning to queue")
      } else if (!isRegistered) {
        log.debug(s"Processor ${am.id} is not registered (removed from registrar), not returning to queue")
      } else {
        // Processor is active and registered - safe to return to queue
        if (!dispatcher.queue.offer(am)) {
          log.error(s"Failed to return processor ${am.id} to dispatcher queue!")
        } else {
          // Signal the processorReturned condition variable to wake up any threads
          // waiting for this processor to be returned (e.g., stop() method)
          dispatcher.lock.lock()
          try {
            dispatcher.processorReturned.signalAll()
          } finally {
            dispatcher.lock.unlock()
          }
        }
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

  private def createRunnable(assignedDispatchers: Array[LockedDispatcher]): Runnable = () => {
    // Thread-local state
    var roundRobinIndex = 0
    var consecutiveFailures = 0
    var noTaskCount = 0
    var currentBackoffDelay = backoffStrategy.initialDelay

    while (keepGoing.get()) {
      val dispatcher = assignedDispatchers(roundRobinIndex)

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
          // Map dispatcher names to actual LockedDispatcher objects
          val dispatcherObjects = assignedDispatchers.map(dispatchers)
          val t = new Thread(createRunnable(dispatcherObjects))
          t.setName(s"sss-events-dispatcher-thread-$threadIdx")
          threads = threads :+ t
          t.start()
      }
    }
  }
}

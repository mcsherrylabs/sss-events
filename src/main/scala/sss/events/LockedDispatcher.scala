package sss.events

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.locks.{Condition, ReentrantLock}

/**
 * Dispatcher with lock-protected queue access.
 *
 * Wraps a processor queue with a ReentrantLock to provide explicit
 * contention management via tryLock() instead of relying on CAS operations
 * in the underlying ConcurrentLinkedQueue.
 *
 * @param name Dispatcher name (e.g., "api", "batch", "" for default)
 * @param lock Non-fair ReentrantLock for queue protection
 * @param queue Queue of processors assigned to this dispatcher
 * @param workAvailable Condition variable for efficient thread wakeup when work arrives
 */
case class LockedDispatcher(
  name: String,
  lock: ReentrantLock,
  queue: ConcurrentLinkedQueue[BaseEventProcessor],
  workAvailable: Condition
)

object LockedDispatcher {
  /**
   * Create a new LockedDispatcher with non-fair lock.
   *
   * @param name Dispatcher name
   * @return New LockedDispatcher instance
   */
  def apply(name: String): LockedDispatcher = {
    val lock = new ReentrantLock(false)  // Non-fair for maximum throughput
    LockedDispatcher(
      name = name,
      lock = lock,
      queue = new ConcurrentLinkedQueue[BaseEventProcessor](),
      workAvailable = lock.newCondition()
    )
  }
}

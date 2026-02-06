package sss.events

import java.util.concurrent.locks.LockSupport

/**
 * Exponential backoff strategy for managing lock contention.
 *
 * This class is stateless and thread-safe. Each thread maintains its own
 * current delay value and passes it through the `nextDelay` method.
 *
 * Backoff progression with default settings (10μs base, 1.5x multiplier, 10ms max):
 * - Attempt 1: 10μs
 * - Attempt 2: 15μs
 * - Attempt 3: 22μs
 * - Attempt 4: 33μs
 * - Attempt 5: 50μs
 * - Attempt 10: 227μs
 * - Attempt 20: 3.3ms
 * - Attempt 30+: 10ms (capped)
 *
 * @param baseDelayNanos Initial delay in nanoseconds (e.g., 10000 = 10μs)
 * @param multiplier Factor to multiply delay by on each failure (e.g., 1.5)
 * @param maxDelayNanos Maximum delay cap in nanoseconds (e.g., 10_000_000 = 10ms)
 */
class ExponentialBackoff(
  val baseDelayNanos: Long,
  val multiplier: Double,
  val maxDelayNanos: Long
) {
  require(baseDelayNanos > 0, s"baseDelayNanos must be positive, got: $baseDelayNanos")
  require(multiplier > 1.0, s"multiplier must be > 1.0, got: $multiplier")
  require(maxDelayNanos >= baseDelayNanos,
    s"maxDelayNanos ($maxDelayNanos) must be >= baseDelayNanos ($baseDelayNanos)")

  /**
   * Calculate the next delay value after a failure.
   *
   * @param currentDelayNanos Current delay value
   * @return Next delay value (capped at maxDelayNanos)
   */
  def nextDelay(currentDelayNanos: Long): Long = {
    Math.min((currentDelayNanos * multiplier).toLong, maxDelayNanos)
  }

  /**
   * Get the initial delay value.
   * Use this to reset backoff after successful work.
   *
   * @return Initial delay in nanoseconds
   */
  def initialDelay: Long = baseDelayNanos

  /**
   * Sleep for the specified delay using LockSupport.parkNanos.
   *
   * This method responds to thread interruption but does NOT throw
   * InterruptedException or clear the interrupt status. Caller should
   * check Thread.interrupted() if interrupt handling is needed.
   *
   * @param delayNanos Delay in nanoseconds
   */
  def sleep(delayNanos: Long): Unit = {
    LockSupport.parkNanos(delayNanos)
  }
}

object ExponentialBackoff {
  /**
   * Create ExponentialBackoff from BackoffConfig.
   * Converts microseconds to nanoseconds.
   *
   * @param config Backoff configuration in microseconds
   * @return ExponentialBackoff instance
   */
  def fromConfig(config: BackoffConfig): ExponentialBackoff = {
    new ExponentialBackoff(
      baseDelayNanos = config.baseDelayMicros * 1000,  // μs → ns
      multiplier = config.multiplier,
      maxDelayNanos = config.maxDelayMicros * 1000     // μs → ns
    )
  }
}

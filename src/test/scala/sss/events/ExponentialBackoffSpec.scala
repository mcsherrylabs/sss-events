package sss.events

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Unit tests for ExponentialBackoff behavior.
 *
 * These tests verify the exponential backoff algorithm's mathematical correctness
 * without involving the event processing engine or concurrency.
 */
class ExponentialBackoffSpec extends AnyFlatSpec with Matchers {

  "ExponentialBackoff" should "start at base delay" in {
    val backoff = ExponentialBackoff.fromConfig(BackoffConfig(
      baseDelayMicros = 100,
      multiplier = 2.0,
      maxDelayMicros = 10000
    ))

    backoff.initialDelay shouldBe 100_000  // 100 microseconds in nanoseconds
  }

  it should "increase delay by multiplier" in {
    val backoff = ExponentialBackoff.fromConfig(BackoffConfig(
      baseDelayMicros = 100,
      multiplier = 2.0,
      maxDelayMicros = 10000
    ))

    val delay1 = backoff.initialDelay  // 100 micros
    val delay2 = backoff.nextDelay(delay1)  // 200 micros
    val delay3 = backoff.nextDelay(delay2)  // 400 micros

    delay1 shouldBe 100_000
    delay2 shouldBe 200_000
    delay3 shouldBe 400_000
  }

  it should "cap at maximum delay" in {
    val backoff = ExponentialBackoff.fromConfig(BackoffConfig(
      baseDelayMicros = 100,
      multiplier = 2.0,
      maxDelayMicros = 1000
    ))

    var currentDelay = backoff.initialDelay
    // Iterate until we hit the cap
    (1 to 20).foreach { _ =>
      currentDelay = backoff.nextDelay(currentDelay)
    }

    currentDelay shouldBe 1_000_000  // Capped at 1000 micros (1 ms)
  }

  it should "sleep for specified duration" in {
    val backoff = ExponentialBackoff.fromConfig(BackoffConfig(
      baseDelayMicros = 1000,  // 1 ms
      multiplier = 1.5,
      maxDelayMicros = 10000
    ))

    val start = System.nanoTime()
    backoff.sleep(1_000_000)  // Sleep for 1 ms
    val elapsed = System.nanoTime() - start

    // Should be at least 1ms (allowing for some overhead)
    elapsed should be >= 1_000_000L
    // Should be less than 10ms (sanity check)
    elapsed should be < 10_000_000L
  }
}

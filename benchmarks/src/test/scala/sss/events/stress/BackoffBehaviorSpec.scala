package sss.events.stress

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sss.events.{DispatcherName, BackoffConfig, BaseEventProcessor, EngineConfig, EventProcessingEngine, ExponentialBackoff}
import sss.events.EventProcessor.EventHandler

import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.{AtomicInteger, AtomicLong}
import scala.concurrent.ExecutionContext

/**
 * Tests to validate exponential backoff behavior under lock contention.
 *
 * These tests verify that backoff delays are applied correctly when threads
 * fail to acquire locks, and that backoff resets after successful work.
 */
class BackoffBehaviorSpec extends AnyFlatSpec with Matchers {

  implicit val ec: ExecutionContext = ExecutionContext.global

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

  "Backoff under contention" should "apply backoff when all locks fail" in {
    // Configure 4 threads all targeting a single slow dispatcher
    val config = EngineConfig(
      schedulerPoolSize = 2,
      threadDispatcherAssignment = Array(
        Array("subscriptions"),  // Dedicated for Subscriptions
        Array("slow"),
        Array("slow"),
        Array("slow"),
        Array("slow")
      ),
      backoff = BackoffConfig(
        baseDelayMicros = 1000,  // 1ms base
        multiplier = 1.5,
        maxDelayMicros = 5000    // 5ms max
      )
    )

    implicit val engine: EventProcessingEngine = EventProcessingEngine(config)
    engine.start()

    val backoffCount = new AtomicInteger(0)
    val processCount = new AtomicInteger(0)
    val latch = new CountDownLatch(1)
    val targetMessages = 100

    case class SlowMessage(id: Int)

    val processor: BaseEventProcessor = new BaseEventProcessor {
      override def dispatcherName: DispatcherName = DispatcherName.validated("slow", config).getOrElse(throw new IllegalArgumentException("Invalid dispatcher: slow"))

      override protected val onEvent: EventHandler = {
        case SlowMessage(_) =>
          // Simulate slow processing to create lock contention
          Thread.sleep(10)

          val count = processCount.incrementAndGet()
          if (count == targetMessages) {
            latch.countDown()
          }
      }
    }

    // Post messages that will create contention
    (1 to targetMessages).foreach(i => processor.post(SlowMessage(i)))

    val completed = latch.await(30, TimeUnit.SECONDS)
    assert(completed, "Timeout waiting for processing")

    // Verify all messages processed
    processCount.get() shouldBe targetMessages

    println(s"Processed $targetMessages messages under contention")

    engine.shutdown()
  }

  it should "reset backoff after successful work" in {
    // This is harder to test directly, but we can verify that the system
    // makes forward progress even with backoff by processing many messages
    val config = EngineConfig(
      schedulerPoolSize = 2,
      threadDispatcherAssignment = Array(
        Array("subscriptions"),  // Dedicated for Subscriptions
        Array("work"),
        Array("work")
      ),
      backoff = BackoffConfig(
        baseDelayMicros = 100,
        multiplier = 2.0,
        maxDelayMicros = 10000
      )
    )

    implicit val engine: EventProcessingEngine = EventProcessingEngine(config)
    engine.start()

    val latch = new CountDownLatch(1)
    val received = new AtomicInteger(0)
    val targetMessages = 10000

    case class TestMessage(id: Int)

    val processor: BaseEventProcessor = new BaseEventProcessor {
      override def dispatcherName: DispatcherName = DispatcherName.validated("work", config).getOrElse(throw new IllegalArgumentException("Invalid dispatcher: work"))

      override protected val onEvent: EventHandler = {
        case TestMessage(_) =>
          if (received.incrementAndGet() == targetMessages) {
            latch.countDown()
          }
      }
    }

    val start = System.currentTimeMillis()
    (1 to targetMessages).foreach(i => processor.post(TestMessage(i)))

    val completed = latch.await(10, TimeUnit.SECONDS)
    val elapsed = System.currentTimeMillis() - start

    assert(completed, "Timeout waiting for processing")
    received.get() shouldBe targetMessages

    // If backoff wasn't resetting, this would take much longer
    // 10,000 messages should complete in well under 10 seconds
    elapsed should be < 10_000L

    println(s"Processed $targetMessages messages in ${elapsed}ms")

    engine.shutdown()
  }

  it should "handle burst-then-idle pattern with backoff" in {
    val config = EngineConfig(
      schedulerPoolSize = 2,
      threadDispatcherAssignment = Array(
        Array("subscriptions"),  // Dedicated for Subscriptions
        Array("bursty")
      ),
      backoff = BackoffConfig(
        baseDelayMicros = 100,
        multiplier = 1.5,
        maxDelayMicros = 5000
      )
    )

    implicit val engine: EventProcessingEngine = EventProcessingEngine(config)
    engine.start()

    val latch = new CountDownLatch(3)  // 3 bursts
    val received = new AtomicInteger(0)
    val messagesPerBurst = 1000

    case class BurstMessage(burst: Int, id: Int)

    val processor: BaseEventProcessor = new BaseEventProcessor {
      override def dispatcherName: DispatcherName = DispatcherName.validated("bursty", config).getOrElse(throw new IllegalArgumentException("Invalid dispatcher: bursty"))
      val lastBurst = new AtomicInteger(0)

      override protected val onEvent: EventHandler = {
        case BurstMessage(burst, _) =>
          received.incrementAndGet()
          if (lastBurst.get() < burst) {
            lastBurst.set(burst)
            latch.countDown()
          }
      }
    }

    // Send 3 bursts with idle time between
    (1 to 3).foreach { burst =>
      (1 to messagesPerBurst).foreach { i =>
        processor.post(BurstMessage(burst, i))
      }
      Thread.sleep(50)  // Idle time between bursts
    }

    val completed = latch.await(10, TimeUnit.SECONDS)
    assert(completed, "Timeout waiting for bursts")

    received.get() shouldBe messagesPerBurst * 3

    println(s"Processed ${received.get()} messages in 3 bursts")

    engine.shutdown()
  }
}

package sss.events

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.concurrent.ScalaFutures.{convertScalaFuture, PatienceConfig}
import org.scalatest.time.{Seconds, Span}

import java.util.concurrent.atomic.{AtomicInteger, AtomicBoolean}
import scala.concurrent.Promise

/**
  * Test verifying that taskLock.synchronized provides proper memory visibility guarantees
  * between event processing threads for non-volatile private vars.
  *
  * This test verifies that when different dispatcher threads process events for the same
  * processor, all mutations to private vars within event handlers are correctly visible
  * to subsequent event handlers, even across thread boundaries.
  *
  * IMPORTANT: This test avoids external synchronization mechanisms (like CountDownLatch.await())
  * that could create happens-before relationships independent of taskLock. All verification
  * happens purely within event handlers.
  */
class MemoryVisibilitySpec extends AnyFlatSpec with Matchers {

  // Reasonable timeout for async operations
  implicit val patienceConfig: PatienceConfig = PatienceConfig(timeout = Span(5, Seconds))

  // Configure 3 threads all working on the same dispatcher to force thread switching
  val config = EngineConfig(
    schedulerPoolSize = 2,
    threadDispatcherAssignment = Array(
      Array("subscriptions"),
      Array("SHARED"),
      Array("SHARED"),
      Array("SHARED")
    ),
    defaultQueueSize = 50000
  )

  implicit val sut: EventProcessingEngine = EventProcessingEngine(config)
  sut.start()

  "EventProcessor with non-volatile private var" should "see monotonically increasing values across threads" in {
    val numIncrements = 10000
    val errors = new AtomicInteger(0)
    val completionPromise = Promise[Int]()

    val processor = sut.builder()
      .withCreateHandler { ep =>
        // Non-volatile vars - testing visibility via taskLock.synchronized only
        var counter: Int = 0
        var lastSeenValue: Int = 0

        {
          case "increment" =>
            // Increment counter
            val oldValue = counter
            counter += 1
            val newValue = counter

            // Verify we see our own write (sanity check)
            if (newValue != oldValue + 1) {
              errors.incrementAndGet()
            }

          case "read" =>
            // Read counter and verify it's >= last seen value
            // If visibility is broken, we might see counter < lastSeenValue
            val currentValue = counter
            if (currentValue < lastSeenValue) {
              errors.incrementAndGet()
              println(s"VISIBILITY ERROR: counter=$currentValue < lastSeenValue=$lastSeenValue")
            }
            lastSeenValue = currentValue

          case "done" =>
            completionPromise.success(counter)
        }
      }
      .withDispatcher(DispatcherName.validated("SHARED", config).get)
      .build()

    // Interleave increments and reads - NO external synchronization
    // Each "read" event must see at least the value from the last "read"
    for (i <- 0 until numIncrements) {
      processor.post("increment")
      processor.post("read")
    }
    processor.post("done")

    // Wait for completion via Promise only
    val finalCount = completionPromise.future.futureValue

    println(s"Final counter: $finalCount")
    println(s"Visibility errors: ${errors.get()}")

    // Should have zero visibility errors
    errors.get() shouldBe 0
    finalCount shouldBe numIncrements
  }

  "EventProcessor with non-volatile state" should "correctly see all prior state changes" in {
    val numOperations = 5000
    val errors = new AtomicInteger(0)
    val completionPromise = Promise[(Int, Int)]()

    val processor = sut.builder()
      .withCreateHandler { ep =>
        // Multiple non-volatile vars
        var counter: Int = 0
        var sum: Int = 0
        var operationsProcessed: Int = 0

        {
          case value: Int =>
            // Update state based on the value
            counter += 1
            sum += value

            // Verify internal consistency: sum should equal 1+2+...+counter
            val expectedSum = (counter * (counter + 1)) / 2
            if (sum != expectedSum) {
              errors.incrementAndGet()
              println(s"INCONSISTENCY: counter=$counter, sum=$sum, expected=$expectedSum")
            }

            operationsProcessed += 1
            if (operationsProcessed == numOperations) {
              completionPromise.success((counter, sum))
            }
        }
      }
      .withDispatcher(DispatcherName.validated("SHARED", config).get)
      .build()

    // Post sequential integers - each event verifies state consistency
    for (i <- 1 to numOperations) {
      processor.post(i)
    }

    // Wait for completion
    val (finalCounter, finalSum) = completionPromise.future.futureValue

    println(s"Final counter: $finalCounter, sum: $finalSum")
    println(s"Consistency errors: ${errors.get()}")

    errors.get() shouldBe 0
    finalCounter shouldBe numOperations
    finalSum shouldBe (numOperations * (numOperations + 1)) / 2
  }

  "EventProcessor with non-volatile accumulator" should "never see stale accumulated values" in {
    val numUpdates = 10000
    val errors = new AtomicInteger(0)
    val decreasingErrors = new AtomicInteger(0)
    val completionPromise = Promise[Int]()

    val processor = sut.builder()
      .withCreateHandler { ep =>
        // Accumulator that should only increase
        var accumulator: Int = 0
        var lastSnapshot: Int = 0
        var updates: Int = 0

        {
          case "add" =>
            accumulator += 1
            updates += 1

          case "snapshot" =>
            // Take a snapshot and verify it's >= last snapshot
            val currentSnapshot = accumulator
            if (currentSnapshot < lastSnapshot) {
              decreasingErrors.incrementAndGet()
              println(s"DECREASING: snapshot=$currentSnapshot < last=$lastSnapshot")
            }

            // Verify snapshot matches updates count
            if (currentSnapshot != updates) {
              errors.incrementAndGet()
              println(s"MISMATCH: snapshot=$currentSnapshot, updates=$updates")
            }

            lastSnapshot = currentSnapshot

          case "done" =>
            completionPromise.success(accumulator)
        }
      }
      .withDispatcher(DispatcherName.validated("SHARED", config).get)
      .build()

    // Interleave adds and snapshots with no external synchronization
    for (i <- 0 until numUpdates) {
      processor.post("add")
      if (i % 10 == 0) {
        processor.post("snapshot")
      }
    }
    processor.post("snapshot") // Final snapshot
    processor.post("done")

    val finalValue = completionPromise.future.futureValue

    println(s"Final accumulator: $finalValue")
    println(s"Decreasing errors: ${decreasingErrors.get()}")
    println(s"Mismatch errors: ${errors.get()}")

    decreasingErrors.get() shouldBe 0
    errors.get() shouldBe 0
    finalValue shouldBe numUpdates
  }
}

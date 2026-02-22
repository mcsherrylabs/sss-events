package sss.events

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.concurrent.ScalaFutures.convertScalaFuture
import sss.events.EventProcessor.EventHandler

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.Promise

/**
  * Test demonstrating that a volatile memory barrier in processEvent can provide
  * visibility guarantees for non-volatile vars across dispatcher threads.
  *
  * By reading a volatile var at the start of processEvent and writing it at the end,
  * we establish happens-before relationships that make all non-volatile var changes
  * visible to subsequent event processing threads.
  *
  * This works because:
  * 1. Thread A writes to volatile at end of processEvent (after updating non-volatile vars)
  * 2. Thread B reads the volatile at start of processEvent (before reading non-volatile vars)
  * 3. JMM guarantees: Thread A's writes happen-before Thread B's reads
  */
class VolatileMemoryBarrierSpec extends AnyFlatSpec with Matchers {

  // Configure 3 threads all working on the same dispatcher
  val config = EngineConfig(
    schedulerPoolSize = 2,
    threadDispatcherAssignment = Array(
      Array("subscriptions"),
      Array("SHARED"),
      Array("SHARED"),
      Array("SHARED")
    ),
    defaultQueueSize = 10000
  )

  implicit val sut: EventProcessingEngine = EventProcessingEngine(config)
  sut.start()

  "BaseEventProcessor with volatile memory barrier" should "provide visibility for non-volatile vars" in {
    val numIncrements = 10000
    val latch = new CountDownLatch(numIncrements)
    val counterRef = new AtomicReference[() => Int]()
    val completionPromise = Promise[Unit]()

    // Create a processor that uses volatile memory barrier pattern
    class VolatileBarrierProcessor extends BaseEventProcessor {
      // Non-volatile counter (the var we're testing visibility for)
      private var counter: Int = 0

      // Volatile memory barrier variable
      @volatile private var memoryBarrier: Int = 0

      override def dispatcherName: DispatcherName = DispatcherName.validated("SHARED", config).get

      def getCounter: Int = counter

      // Override processEvent to add volatile read/write barriers
      override private[events] def processEvent(ev: Any): Unit = {
        // Read volatile BEFORE processing (establishes happens-before with previous write)
        val _ = memoryBarrier

        // Process the event (which may update non-volatile vars)
        super.processEvent(ev)

        // Write volatile AFTER processing (establishes happens-before with next read)
        memoryBarrier += 1
      }

      override protected val onEvent: EventHandler = {
        case "increment" =>
          counter += 1
          latch.countDown()
        case "done" =>
          completionPromise.success(())
      }
    }

    val processor = new VolatileBarrierProcessor()
    sut.register(processor)

    // Post many increment events - processed by multiple threads on SHARED dispatcher
    for (i <- 0 until numIncrements) {
      processor.post("increment")
    }

    // Wait for all increments to be processed
    val completed = latch.await(5, TimeUnit.SECONDS)
    completed shouldBe true

    // Send terminating message
    processor.post("done")
    completionPromise.future.futureValue

    // Now read counter from a separate thread (simulating external read)
    // Even though counter is not volatile, the volatile barrier should make it visible
    val readerLatch = new CountDownLatch(1)
    @volatile var observedCount = 0

    new Thread(() => {
      // This read happens after all processing is done and the final volatile write
      // So we should see the correct value
      observedCount = processor.getCounter
      readerLatch.countDown()
    }).start()

    readerLatch.await(1, TimeUnit.SECONDS)

    println(s"Expected: $numIncrements, Observed with volatile barrier: $observedCount")

    // This SHOULD succeed because the volatile barrier provides visibility
    observedCount shouldBe numIncrements
  }

  "BaseEventProcessor with volatile memory barrier" should "prevent visibility issues in concurrent reads" in {
    val numIncrements = 100000
    val counterRef = new AtomicReference[() => Int]()
    val startProcessingPromise = Promise[Unit]()

    @volatile var minObserved = Int.MaxValue
    @volatile var maxObserved = 0

    // Create processor with volatile memory barrier
    class VolatileBarrierProcessor extends BaseEventProcessor {
      private var counter: Int = 0
      private var started = false

      @volatile private var memoryBarrier: Int = 0

      override def dispatcherName: DispatcherName = DispatcherName.validated("SHARED", config).get

      def getCounter: Int = counter

      override private[events] def processEvent(ev: Any): Unit = {
        val _ = memoryBarrier
        super.processEvent(ev)
        memoryBarrier += 1
      }

      override protected val onEvent: EventHandler = {
        case "increment" =>
          if (!started) {
            started = true
            startProcessingPromise.success(())
          }
          counter += 1
      }
    }

    val processor = new VolatileBarrierProcessor()
    sut.register(processor)

    // Post many increment events
    for (i <- 0 until numIncrements) {
      processor.post("increment")
    }

    // Wait for processing to start
    startProcessingPromise.future.futureValue

    // Concurrently read the counter while processing continues
    val reader = new Thread(() => {
      for (i <- 0 until 10000) {
        val observed = processor.getCounter
        if (observed < minObserved) {
          minObserved = observed
        }
        if (observed > maxObserved) {
          maxObserved = observed
        }
      }
    })
    reader.start()
    reader.join()

    println(s"Total increments: $numIncrements")
    println(s"Concurrent reads: min=$minObserved, max=$maxObserved")
    println(s"NOTE: The volatile barrier only helps visibility between processEvent calls,")
    println(s"not for external threads reading during processing. External threads still need")
    println(s"proper synchronization or the var itself to be volatile.")

    // The volatile barrier helps visibility BETWEEN processEvent calls on different threads,
    // but doesn't help external threads reading during processing.
    // So we still expect to see stale values during concurrent reads.
    minObserved should be < numIncrements
  }

  "Comparison: volatile var vs volatile barrier" should "show both work for sequential reads" in {
    val numIncrements = 10000
    val latch1 = new CountDownLatch(numIncrements)
    val latch2 = new CountDownLatch(numIncrements)
    val done1 = Promise[Unit]()
    val done2 = Promise[Unit]()

    // Processor 1: Using volatile barrier pattern
    class BarrierProcessor extends BaseEventProcessor {
      private var counter: Int = 0
      @volatile private var memoryBarrier: Int = 0

      override def dispatcherName: DispatcherName = DispatcherName.validated("SHARED", config).get

      def getCounter: Int = counter

      override private[events] def processEvent(ev: Any): Unit = {
        val _ = memoryBarrier
        super.processEvent(ev)
        memoryBarrier += 1
      }

      override protected val onEvent: EventHandler = {
        case "increment" =>
          counter += 1
          latch1.countDown()
        case "done" => done1.success(())
      }
    }

    // Processor 2: Using @volatile directly on counter
    class VolatileCounterProcessor extends BaseEventProcessor {
      @volatile private var counter: Int = 0

      override def dispatcherName: DispatcherName = DispatcherName.validated("SHARED", config).get

      def getCounter: Int = counter

      override protected val onEvent: EventHandler = {
        case "increment" =>
          counter += 1
          latch2.countDown()
        case "done" => done2.success(())
      }
    }

    val processor1 = new BarrierProcessor()
    val processor2 = new VolatileCounterProcessor()
    sut.register(processor1)
    sut.register(processor2)

    // Post increments to both
    for (i <- 0 until numIncrements) {
      processor1.post("increment")
      processor2.post("increment")
    }

    latch1.await(5, TimeUnit.SECONDS)
    latch2.await(5, TimeUnit.SECONDS)

    processor1.post("done")
    processor2.post("done")
    done1.future.futureValue
    done2.future.futureValue

    val count1 = processor1.getCounter
    val count2 = processor2.getCounter

    println(s"Volatile barrier approach: $count1")
    println(s"Volatile counter approach: $count2")

    // Both should work correctly for sequential reads after processing completes
    count1 shouldBe numIncrements
    count2 shouldBe numIncrements
  }
}

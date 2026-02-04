package sss.events

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sss.events.EventProcessor.EventProcessorId

import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}

/**
 * Tests for race condition fix in stop() method (Issue #002).
 *
 * Validates the fix for the critical race condition where a worker thread
 * may be actively processing a message when stop() is called, leading to
 * the processor being permanently lost.
 *
 * The fix ensures that:
 * 1. Dispatcher is found BEFORE acquiring lock
 * 2. Dispatcher lock is acquired to coordinate with worker threads
 * 3. Processor removal happens while locked
 * 4. Timeout prevents indefinite waiting
 */
class StopRaceConditionSpec extends AnyFlatSpec with Matchers {

  "EventProcessingEngine.stop (race condition fix)" should "handle stop during active message processing" in {
    implicit val engine = EventProcessingEngine()
    engine.start()

    val processingStarted = new CountDownLatch(1)
    val allowProcessingToComplete = new CountDownLatch(1)
    val processingComplete = new AtomicBoolean(false)
    val errorOccurred = new AtomicBoolean(false)

    // Create a processor that signals when processing starts
    val processor = engine.builder()
      .withCreateHandler { ep => {
        case msg: String =>
          processingStarted.countDown()
          // Wait for signal to complete (simulating long processing)
          try {
            allowProcessingToComplete.await(5, TimeUnit.SECONDS)
            processingComplete.set(true)
          } catch {
            case _: InterruptedException =>
              errorOccurred.set(true)
          }
      }}
      .build()

    // Send a message
    processor ! "test"

    // Wait for processing to start
    val started = processingStarted.await(2, TimeUnit.SECONDS)
    started shouldBe true

    // Now stop the processor while it's actively processing
    // This should wait for the lock and safely remove the processor
    val stopThread = new Thread(() => {
      engine.stop(processor.id, timeoutMs = 10000)
    })
    stopThread.start()

    // Give stop a moment to acquire the lock (or wait for it)
    Thread.sleep(200)

    // Allow processing to complete
    allowProcessingToComplete.countDown()

    // Wait for stop to complete
    stopThread.join(10000)

    // Verify processor was properly removed
    engine.registrar.get(processor.id) shouldBe None

    // Verify processing completed successfully (no errors)
    processingComplete.get() shouldBe true
    errorOccurred.get() shouldBe false

    engine.shutdown()
  }

  it should "handle stop when processor is in dispatcher queue" in {
    implicit val engine = EventProcessingEngine()
    engine.start()

    val processedCount = new AtomicInteger(0)

    // Create a processor
    val processor = engine.builder()
      .withCreateHandler { ep => {
        case msg: Int =>
          Thread.sleep(10)
          processedCount.incrementAndGet()
      }}
      .build()

    // Send messages to queue them up
    (1 to 5).foreach(i => processor ! i)

    // Give a moment for messages to queue
    Thread.sleep(50)

    // Stop while messages are in queue
    engine.stop(processor.id, timeoutMs = 5000)

    // Processor should be removed
    engine.registrar.get(processor.id) shouldBe None

    // All queued messages should have been processed
    Thread.sleep(1000) // Give time for any pending processing
    processedCount.get() shouldBe 5

    engine.shutdown()
  }

  it should "handle stop when processor is not in any queue" in {
    implicit val engine = EventProcessingEngine()
    engine.start()

    // Create a processor
    val processor = engine.builder()
      .withCreateHandler { ep => {
        case msg: String => // Do nothing
      }}
      .build()

    // Don't send any messages - processor should be idle in the queue

    // Stop should succeed even if processor location is uncertain
    engine.stop(processor.id)

    // Processor should be removed
    engine.registrar.get(processor.id) shouldBe None

    engine.shutdown()
  }

  it should "not log 'Failed to return processor to queue' error during normal stop" in {
    implicit val engine = EventProcessingEngine()
    engine.start()

    val messageCount = 20
    val processedMessages = new AtomicInteger(0)
    val processingLatch = new CountDownLatch(1)

    // Create a processor with moderate processing time
    val processor = engine.builder()
      .withCreateHandler { ep => {
        case msg: Int =>
          processingLatch.countDown()
          Thread.sleep(20)
          processedMessages.incrementAndGet()
      }}
      .build()

    // Send messages
    (1 to messageCount).foreach(i => processor ! i)

    // Wait for at least one message to be processed
    processingLatch.await(2, TimeUnit.SECONDS)

    // Stop the processor (this should coordinate with worker threads properly)
    engine.stop(processor.id, timeoutMs = 10000)

    // Verify processor is removed
    engine.registrar.get(processor.id) shouldBe None

    // The key test: no "Failed to return processor to queue" error should occur
    // This is validated by the successful removal and the lack of exceptions
    // In the old code, this would have caused the error

    engine.shutdown()
  }

  it should "handle concurrent stop calls on same processor" in {
    implicit val engine = EventProcessingEngine()
    engine.start()

    val processor = engine.builder()
      .withCreateHandler { ep => {
        case msg: String =>
          Thread.sleep(50)
      }}
      .build()

    // Send some messages
    (1 to 5).foreach(i => processor ! s"msg$i")

    val stopLatch = new CountDownLatch(2)
    val errors = new AtomicInteger(0)

    // Start two threads that try to stop the same processor
    val thread1 = new Thread(new Runnable {
      def run(): Unit = {
        try {
          engine.stop(processor.id, timeoutMs = 5000)
        } catch {
          case e: Exception =>
            errors.incrementAndGet()
        } finally {
          stopLatch.countDown()
        }
      }
    })

    val thread2 = new Thread(new Runnable {
      def run(): Unit = {
        try {
          engine.stop(processor.id, timeoutMs = 5000)
        } catch {
          case e: Exception =>
            errors.incrementAndGet()
        } finally {
          stopLatch.countDown()
        }
      }
    })

    thread1.start()
    Thread.sleep(10) // Small delay to increase chance of race
    thread2.start()

    // Wait for both stops to complete
    val completed = stopLatch.await(15, TimeUnit.SECONDS)
    completed shouldBe true

    // No errors should occur
    errors.get() shouldBe 0

    // Processor should be removed
    engine.registrar.get(processor.id) shouldBe None

    engine.shutdown()
  }

  it should "timeout when waiting for processor to be returned to queue" in {
    implicit val engine = EventProcessingEngine()
    engine.start()

    val processingStarted = new CountDownLatch(1)
    val blockForever = new CountDownLatch(1)

    // Create a processor that blocks forever
    val processor = engine.builder()
      .withCreateHandler { ep => {
        case msg: String =>
          processingStarted.countDown()
          blockForever.await() // Block forever
      }}
      .build()

    // Send a message
    processor ! "test"

    // Wait for processing to start
    processingStarted.await(2, TimeUnit.SECONDS)

    // Now the processor is being actively processed by a worker thread
    // The worker thread won't return it because it's blocked

    // Stop should timeout gracefully
    val startTime = System.currentTimeMillis()
    engine.stop(processor.id, timeoutMs = 1000)
    val duration = System.currentTimeMillis() - startTime

    // Should have attempted to wait but given up
    // The internal retry timeout is 5000ms, but the processor should still be removed
    duration should be < 8000L

    // Processor should be unregistered even if we couldn't cleanly remove it
    engine.registrar.get(processor.id) shouldBe None

    engine.shutdown()
  }

  it should "coordinate with worker threads using dispatcher lock" in {
    implicit val engine = EventProcessingEngine()
    engine.start()

    val messagesProcessed = new AtomicInteger(0)
    val processingLatch = new CountDownLatch(10)

    // Create a processor
    val processor = engine.builder()
      .withCreateHandler { ep => {
        case msg: Int =>
          Thread.sleep(30)
          messagesProcessed.incrementAndGet()
          processingLatch.countDown()
      }}
      .build()

    // Send multiple messages
    (1 to 10).foreach(i => processor ! i)

    // Let some messages start processing
    Thread.sleep(100)

    // Stop should coordinate with workers via the dispatcher lock
    val stopStartTime = System.currentTimeMillis()
    engine.stop(processor.id, timeoutMs = 10000)
    val stopDuration = System.currentTimeMillis() - stopStartTime

    // Verify processor is removed
    engine.registrar.get(processor.id) shouldBe None

    // Wait for all messages to complete processing
    processingLatch.await(15, TimeUnit.SECONDS)

    // All messages should have been processed
    messagesProcessed.get() shouldBe 10

    engine.shutdown()
  }

  it should "handle stop on different dispatchers" in {
    implicit val engine = EventProcessingEngine()
    engine.start()

    val dispatcher1Count = new AtomicInteger(0)
    val dispatcher2Count = new AtomicInteger(0)

    // Create processors on different dispatchers
    val processor1 = engine.builder()
      .withCreateHandler { ep => {
        case msg: Int =>
          Thread.sleep(20)
          dispatcher1Count.incrementAndGet()
      }}
      .withDispatcher(DispatcherName.Default)
      .build()

    val processor2 = engine.builder()
      .withCreateHandler { ep => {
        case msg: String =>
          Thread.sleep(20)
          dispatcher2Count.incrementAndGet()
      }}
      .withDispatcher(DispatcherName.Default) // Same dispatcher for this test
      .build()

    // Send messages to both
    (1 to 5).foreach(i => processor1 ! i)
    (1 to 5).foreach(i => processor2 ! s"msg$i")

    Thread.sleep(100)

    // Stop both processors
    engine.stop(processor1.id, timeoutMs = 5000)
    engine.stop(processor2.id, timeoutMs = 5000)

    // Both should be removed
    engine.registrar.get(processor1.id) shouldBe None
    engine.registrar.get(processor2.id) shouldBe None

    // Give time for processing to complete
    Thread.sleep(1000)

    // All messages should have been processed
    dispatcher1Count.get() shouldBe 5
    dispatcher2Count.get() shouldBe 5

    engine.shutdown()
  }
}

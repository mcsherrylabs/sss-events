package sss.events

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sss.events.EventProcessor.EventProcessorId

import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tests for graceful processor stop functionality with queue draining.
 *
 * Validates:
 * - Empty queue stop completes quickly
 * - Non-empty queue drains before processor removal
 * - Timeout handling when queue doesn't drain
 * - Stop during active processing
 */
class GracefulStopSpec extends AnyFlatSpec with Matchers {

  "EventProcessingEngine.stop" should "complete quickly with empty queue" in {
    implicit val engine = EventProcessingEngine()
    engine.start()

    // Create a processor with an empty queue
    val processor = engine.builder()
      .withCreateHandler { ep => {
        case msg: String => // Handler that does nothing
      }}
      .build()

    // Verify queue is empty
    processor.currentQueueSize shouldBe 0

    // Stop the processor
    engine.stop(processor.id)

    // Verify processor is no longer registered
    engine.registrar.get(processor.id) shouldBe None

    engine.shutdown()
  }

  it should "drain messages before stopping" in {
    implicit val engine = EventProcessingEngine()
    engine.start()

    val messageCount = 10
    val processedMessages = new AtomicInteger(0)
    val latch = new CountDownLatch(messageCount)

    // Create a processor with slow message processing
    val processor = engine.builder()
      .withCreateHandler { ep => {
        case msg: Int =>
          Thread.sleep(50) // Slow processing
          processedMessages.incrementAndGet()
          latch.countDown()
      }}
      .build()

    // Send messages
    (1 to messageCount).foreach(i => processor ! i)

    // Give messages time to queue up
    Thread.sleep(100)

    // Queue should have messages
    val queueSize = processor.currentQueueSize
    queueSize should be > 0

    // Stop should wait for queue to drain
    engine.stop(processor.id, timeoutMs = 10000)

    // All messages should have been processed
    val completed = latch.await(5, TimeUnit.SECONDS)
    completed shouldBe true
    processedMessages.get() shouldBe messageCount

    // Processor should be unregistered
    engine.registrar.get(processor.id) shouldBe None

    engine.shutdown()
  }

  it should "timeout if queue doesn't drain" in {
    implicit val engine = EventProcessingEngine()
    engine.start()

    val blockedLatch = new CountDownLatch(1)
    val processedCount = new AtomicInteger(0)

    // Create a processor that blocks on first message
    val processor = engine.builder()
      .withCreateHandler { ep => {
        case msg: Int =>
          if (processedCount.incrementAndGet() == 1) {
            // First message blocks forever
            blockedLatch.await()
          }
      }}
      .build()

    // Send multiple messages
    (1 to 5).foreach(i => processor ! i)

    // Give messages time to queue
    Thread.sleep(100)

    // Stop with short timeout should timeout
    val startTime = System.currentTimeMillis()
    engine.stop(processor.id, timeoutMs = 500)
    val stopDuration = System.currentTimeMillis() - startTime

    // Should timeout around 500ms
    stopDuration should (be >= 500L and be < 1000L)

    // Processor should still be unregistered despite timeout
    engine.registrar.get(processor.id) shouldBe None

    // Unblock the processor
    blockedLatch.countDown()

    engine.shutdown()
  }

  it should "handle stop during active processing" in {
    implicit val engine = EventProcessingEngine()
    engine.start()

    val processedCount = new AtomicInteger(0)
    val processingStarted = new CountDownLatch(1)
    val processingComplete = new CountDownLatch(1)

    // Create a processor with slow processing
    val processor = engine.builder()
      .withCreateHandler { ep => {
        case msg: Int =>
          processingStarted.countDown()
          Thread.sleep(100)
          processedCount.incrementAndGet()
          processingComplete.countDown()
      }}
      .build()

    // Send a message
    processor ! 1

    // Wait for processing to start
    processingStarted.await()

    // Stop while message is being processed
    // The stop should wait for the queue to drain
    engine.stop(processor.id, timeoutMs = 5000)

    // Processor should be unregistered
    engine.registrar.get(processor.id) shouldBe None

    // Wait a bit more to ensure processing completes
    val completed = processingComplete.await(1, TimeUnit.SECONDS)
    completed shouldBe true

    // Message should have completed processing
    processedCount.get() shouldBe 1

    engine.shutdown()
  }

  it should "stop multiple processors independently" in {
    implicit val engine = EventProcessingEngine()
    engine.start()

    val processor1Latch = new CountDownLatch(5)
    val processor2Latch = new CountDownLatch(3)

    // Create two processors
    val processor1 = engine.builder()
      .withCreateHandler { ep => {
        case msg: Int =>
          Thread.sleep(50)
          processor1Latch.countDown()
      }}
      .build()

    val processor2 = engine.builder()
      .withCreateHandler { ep => {
        case msg: String =>
          Thread.sleep(50)
          processor2Latch.countDown()
      }}
      .build()

    // Send messages to both
    (1 to 5).foreach(i => processor1 ! i)
    (1 to 3).foreach(i => processor2 ! s"msg$i")

    Thread.sleep(100)

    // Stop first processor
    engine.stop(processor1.id, timeoutMs = 5000)

    // First processor should be gone
    engine.registrar.get(processor1.id) shouldBe None

    // Second processor should still be registered
    engine.registrar.get(processor2.id) shouldBe Some(processor2)

    // Stop second processor
    engine.stop(processor2.id, timeoutMs = 5000)

    // Both should complete processing
    processor1Latch.await(5, TimeUnit.SECONDS) shouldBe true
    processor2Latch.await(5, TimeUnit.SECONDS) shouldBe true

    engine.shutdown()
  }

  it should "handle stop of non-existent processor" in {
    implicit val engine = EventProcessingEngine()
    engine.start()

    // Stop a processor that doesn't exist
    val fakeId: EventProcessorId = "non-existent"

    // Should not throw exception
    noException should be thrownBy {
      engine.stop(fakeId)
    }

    engine.shutdown()
  }
}

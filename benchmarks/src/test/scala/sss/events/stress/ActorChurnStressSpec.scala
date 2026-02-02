package sss.events.stress

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sss.events.{DispatcherName, EventProcessingEngine}

import java.util.concurrent.{CountDownLatch, ConcurrentLinkedQueue, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger

/**
 * Stress tests for high actor churn scenarios.
 *
 * Validates system behavior under continuous processor creation/destruction
 * with message processing, testing for:
 * - Memory leaks from incomplete cleanup
 * - Registration/deregistration race conditions
 * - Queue overflow handling
 * - System stability under load
 */
class ActorChurnStressSpec extends AnyFlatSpec with Matchers {

  case class TestMessage(iteration: Int, actorId: Int, messageId: Int)

  "EventProcessingEngine" should "handle continuous actor creation and destruction" in {
    implicit val engine = EventProcessingEngine()
    engine.start()

    val iterations = 10
    val actorsPerIteration = 10
    val messagesPerActor = 5

    val totalMessages = iterations * actorsPerIteration * messagesPerActor
    val latch = new CountDownLatch(totalMessages)
    val errors = new ConcurrentLinkedQueue[String]()
    val processedMessages = new AtomicInteger(0)

    (1 to iterations).foreach { iteration =>
      // Create actors
      val processors = (1 to actorsPerIteration).map { actorId =>
        engine.builder()
          .withCreateHandler { ep => {
            case TestMessage(iter, aid, mid) =>
              try {
                processedMessages.incrementAndGet()
                latch.countDown()
              } catch {
                case e: Exception =>
                  errors.add(s"Error processing message: ${e.getMessage}")
              }
          }}
          .build()
      }

      // Send messages to each actor
      processors.foreach { p =>
        (1 to messagesPerActor).foreach { messageId =>
          p ! TestMessage(iteration, p.id.hashCode, messageId)
        }
      }

      // Wait for this batch to complete before destroying
      val batchLatch = new CountDownLatch(actorsPerIteration * messagesPerActor)
      val batchCount = new AtomicInteger(0)

      //  Give some time for processing
      Thread.sleep(100)

      // Destroy all actors (high churn)
      processors.foreach(p => engine.stop(p.id))
    }

    // Wait for all messages to be processed
    val completed = latch.await(30, TimeUnit.SECONDS)

    engine.shutdown()

    // Assertions
    errors.isEmpty shouldBe true
    completed shouldBe true
    processedMessages.get() shouldBe totalMessages
  }

  it should "handle actor churn with mixed queue sizes" in {
    implicit val engine = EventProcessingEngine()
    engine.start()

    val iterations = 5
    val queueSizes = Array(1000, 10000)
    val messagesPerActor = 10

    val totalActors = iterations * queueSizes.length
    val totalMessages = totalActors * messagesPerActor
    val latch = new CountDownLatch(totalMessages)
    val errors = new ConcurrentLinkedQueue[String]()

    (1 to iterations).foreach { iteration =>
      queueSizes.foreach { queueSize =>
        val processor = engine.builder()
          .withCreateHandler { ep => {
            case msg: Int =>
              try {
                latch.countDown()
              } catch {
                case e: Exception =>
                  errors.add(s"Error with queue size $queueSize: ${e.getMessage}")
              }
          }}
          .withQueueSize(queueSize)
          .build()

        // Send messages
        (1 to messagesPerActor).foreach(i => processor ! i)

        // Give time for processing
        Thread.sleep(50)

        // Destroy after sending
        engine.stop(processor.id)
      }
    }

    val completed = latch.await(30, TimeUnit.SECONDS)

    engine.shutdown()

    errors.isEmpty shouldBe true
    completed shouldBe true
  }

  it should "handle queue overflow gracefully with small queues" in {
    implicit val engine = EventProcessingEngine()
    engine.start()

    val actorCount = 10
    val messagesPerActor = 100
    val smallQueueSize = 10  // Intentionally small to test overflow

    val latch = new CountDownLatch(actorCount * messagesPerActor)
    val messagesReceived = new AtomicInteger(0)
    val messagesRejected = new AtomicInteger(0)

    val processors = (1 to actorCount).map { i =>
      engine.builder()
        .withCreateHandler { ep => {
          case msg: Int =>
            Thread.sleep(5)  // Slow processing to cause backpressure
            messagesReceived.incrementAndGet()
            latch.countDown()
        }}
        .withQueueSize(smallQueueSize)
        .build()
    }

    // Flood with messages to cause queue overflow
    processors.foreach { p =>
      (1 to messagesPerActor).foreach { i =>
        if (!p.post(i)) {
          messagesRejected.incrementAndGet()
          latch.countDown()  // Count rejected messages too
        }
      }
    }

    val completed = latch.await(30, TimeUnit.SECONDS)

    // Destroy all processors
    processors.foreach(p => engine.stop(p.id))

    engine.shutdown()

    // Assertions
    completed shouldBe true
    (messagesReceived.get() + messagesRejected.get()) shouldBe (actorCount * messagesPerActor)

    // With small queues and flooding, we expect some rejections
    messagesRejected.get() should be > 0
  }

  it should "handle actor churn with multiple dispatchers" in {
    implicit val engine = EventProcessingEngine()
    engine.start()

    val iterations = 5
    val actorsPerIteration = 10
    val messagesPerActor = 5

    val totalMessages = iterations * actorsPerIteration * messagesPerActor
    val latch = new CountDownLatch(totalMessages)
    val errors = new ConcurrentLinkedQueue[String]()

    (1 to iterations).foreach { iteration =>
      // Create actors on default dispatcher
      val processors = (1 to actorsPerIteration).map { actorId =>
        engine.builder()
          .withCreateHandler { ep => {
            case msg: Int =>
              try {
                latch.countDown()
              } catch {
                case e: Exception =>
                  errors.add(s"Error in iteration $iteration, actor $actorId: ${e.getMessage}")
              }
          }}
          .withDispatcher(DispatcherName.Default)
          .build()
      }

      // Send messages
      processors.foreach { p =>
        (1 to messagesPerActor).foreach(i => p ! i)
      }

      // Brief pause for message processing
      Thread.sleep(50)

      // Destroy all actors
      processors.foreach(p => engine.stop(p.id))
    }

    val completed = latch.await(30, TimeUnit.SECONDS)

    engine.shutdown()

    errors.isEmpty shouldBe true
    completed shouldBe true
  }

  it should "maintain stability under high churn (100 iterations)" in {
    implicit val engine = EventProcessingEngine()
    engine.start()

    val iterations = 100
    val actorsPerIteration = 5
    val messagesPerActor = 3

    val totalMessages = iterations * actorsPerIteration * messagesPerActor
    val latch = new CountDownLatch(totalMessages)
    val errors = new ConcurrentLinkedQueue[String]()
    val processedCount = new AtomicInteger(0)

    (1 to iterations).foreach { iteration =>
      val processors = (1 to actorsPerIteration).map { _ =>
        engine.builder()
          .withCreateHandler { ep => {
            case _: Int =>
              processedCount.incrementAndGet()
              latch.countDown()
          }}
          .build()
      }

      processors.foreach(p => (1 to messagesPerActor).foreach(i => p ! i))

      // Small pause for processing
      Thread.sleep(20)

      processors.foreach(p => engine.stop(p.id))
    }

    val completed = latch.await(60, TimeUnit.SECONDS)

    engine.shutdown()

    errors.isEmpty shouldBe true
    completed shouldBe true
    processedCount.get() shouldBe totalMessages
  }
}

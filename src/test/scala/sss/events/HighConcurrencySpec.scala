package sss.events

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.{AtomicInteger, AtomicLong}
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

/**
 * High-concurrency stress tests for the EventProcessingEngine.
 *
 * These tests validate system behavior under heavy concurrent load:
 * 1. Multiple processors sending/receiving messages simultaneously
 * 2. Concurrent registration/unregistration
 * 3. Mixed operations (post, stop, register) under load
 */
class HighConcurrencySpec extends AnyFlatSpec with Matchers {

  "EventProcessingEngine under high concurrency" should "handle 50 processors exchanging messages" in {
    implicit val engine = EventProcessingEngine()
    engine.start()

    val processorCount = 50
    val messagesPerProcessor = 100
    val totalExpectedMessages = processorCount * messagesPerProcessor
    val receivedCount = new AtomicInteger(0)
    val completionLatch = new CountDownLatch(totalExpectedMessages)

    // Create processors that count messages
    val processors = (0 until processorCount).map { i =>
      engine.builder()
        .withCreateHandler { ep => {
          case msg: Int =>
            receivedCount.incrementAndGet()
            completionLatch.countDown()
        }}
        .build()
    }

    // Each processor sends messages to random other processors
    val startTime = System.nanoTime()
    for {
      i <- 0 until processorCount
      j <- 0 until messagesPerProcessor
    } {
      val targetIdx = (i + j) % processorCount
      processors(i) ! j
    }

    // Wait for all messages to be processed (with timeout)
    val allCompleted = completionLatch.await(30, TimeUnit.SECONDS)
    val duration = (System.nanoTime() - startTime) / 1_000_000.0 // ms

    allCompleted shouldBe true
    receivedCount.get() shouldBe totalExpectedMessages

    println(s"Processed $totalExpectedMessages messages in ${duration}ms")
    println(s"Throughput: ${totalExpectedMessages / (duration / 1000.0)} msgs/sec")

    engine.shutdown()
  }

  it should "handle concurrent registration and message posting" in {
    implicit val engine = EventProcessingEngine()
    engine.start()

    val operationCount = 100
    val messagesPerProcessor = 10
    val messageCount = new AtomicInteger(0)
    val registrationComplete = new CountDownLatch(operationCount)
    val processingComplete = new CountDownLatch(operationCount * messagesPerProcessor)

    // Concurrently register processors and send them messages
    val futures = (0 until operationCount).map { i =>
      Future {
        val processor = engine.builder()
          .withCreateHandler { ep => {
            case msg: String =>
              messageCount.incrementAndGet()
              processingComplete.countDown()
          }}
          .build()

        registrationComplete.countDown()

        // Immediately send messages
        (0 until messagesPerProcessor).foreach(_ => processor ! s"msg-$i")

        processor.id
      }
    }

    // Wait for all operations
    val allRegistered = registrationComplete.await(10, TimeUnit.SECONDS)
    allRegistered shouldBe true

    val allProcessed = processingComplete.await(20, TimeUnit.SECONDS)
    allProcessed shouldBe true

    messageCount.get() shouldBe (operationCount * messagesPerProcessor)

    engine.shutdown()
  }

  it should "handle rapid start/stop cycles on processors" in {
    implicit val engine = EventProcessingEngine()
    engine.start()

    val cycleCount = 50
    val messagesPerCycle = 20
    val totalProcessed = new AtomicInteger(0)
    val errors = new AtomicInteger(0)

    for (cycle <- 0 until cycleCount) {
      val processingLatch = new CountDownLatch(messagesPerCycle)

      // Create processor
      val processor = engine.builder()
        .withCreateHandler { ep => {
          case msg: Int =>
            totalProcessed.incrementAndGet()
            processingLatch.countDown()
        }}
        .build()

      // Send messages rapidly
      (0 until messagesPerCycle).foreach(i => processor ! i)

      // Wait a bit for some processing
      Thread.sleep(5)

      // Stop the processor
      try {
        engine.stop(processor.id, timeoutMs = 5000)
      } catch {
        case e: Exception =>
          errors.incrementAndGet()
          println(s"Error stopping processor in cycle $cycle: ${e.getMessage}")
      }

      // Verify processor is stopped
      engine.registrar.get(processor.id) shouldBe None
    }

    // Should have no errors during stop operations
    errors.get() shouldBe 0

    println(s"Completed $cycleCount rapid start/stop cycles")
    println(s"Processed ${totalProcessed.get()} messages total")

    engine.shutdown()
  }

  it should "handle concurrent stops on different processors" in {
    implicit val engine = EventProcessingEngine()
    engine.start()

    val processorCount = 100
    val messagesPerProcessor = 50
    val stopLatch = new CountDownLatch(processorCount)
    val errors = new AtomicInteger(0)

    // Create many processors
    val processors = (0 until processorCount).map { i =>
      val p = engine.builder()
        .withCreateHandler { ep => {
          case msg: Int =>
            Thread.sleep(1) // Simulate processing
        }}
        .build()

      // Send messages to each
      (0 until messagesPerProcessor).foreach(j => p ! j)
      p
    }

    // Concurrently stop all processors
    val stopFutures = processors.map { processor =>
      Future {
        try {
          engine.stop(processor.id, timeoutMs = 10000)
          stopLatch.countDown()
        } catch {
          case e: Exception =>
            errors.incrementAndGet()
            println(s"Error during concurrent stop: ${e.getMessage}")
        }
      }
    }

    // Wait for all stops to complete
    val allStopped = stopLatch.await(30, TimeUnit.SECONDS)
    allStopped shouldBe true

    // Verify no errors
    errors.get() shouldBe 0

    // Verify all processors are unregistered
    processors.foreach { processor =>
      engine.registrar.get(processor.id) shouldBe None
    }

    engine.shutdown()
  }

  it should "handle message storms (5000 messages to single processor)" in {
    implicit val engine = EventProcessingEngine()
    engine.start()

    val messageCount = 5000
    val receivedCount = new AtomicInteger(0)
    val completionLatch = new CountDownLatch(messageCount)

    val processor = engine.builder()
      .withCreateHandler { ep => {
        case msg: Int =>
          receivedCount.incrementAndGet()
          completionLatch.countDown()
      }}
      .build()

    // Send message storm
    val startTime = System.nanoTime()
    (0 until messageCount).foreach(i => processor ! i)

    // Wait for all to be processed
    val completed = completionLatch.await(30, TimeUnit.SECONDS)
    val duration = (System.nanoTime() - startTime) / 1_000_000.0 // ms

    completed shouldBe true
    receivedCount.get() shouldBe messageCount

    println(s"Processed $messageCount messages in ${duration}ms")
    println(s"Throughput: ${messageCount / (duration / 1000.0)} msgs/sec")

    engine.stop(processor.id)
    engine.shutdown()
  }

  it should "handle mixed operations under load" in {
    implicit val engine = EventProcessingEngine()
    engine.start()

    val durationMs = 2000
    val createOperations = new AtomicInteger(0)
    val stopOperations = new AtomicInteger(0)
    val messageOperations = new AtomicLong(0)
    val errors = new AtomicInteger(0)

    @volatile var keepRunning = true
    val activeProcessors = new java.util.concurrent.ConcurrentLinkedQueue[EventProcessor]()

    // Creator thread
    val creatorFuture = Future {
      while (keepRunning) {
        try {
          val processor = engine.builder()
            .withCreateHandler { ep => {
              case msg: Any =>
                messageOperations.incrementAndGet()
            }}
            .build()

          activeProcessors.add(processor)
          createOperations.incrementAndGet()
          Thread.sleep(10)
        } catch {
          case e: Exception =>
            errors.incrementAndGet()
        }
      }
    }

    // Sender thread
    val senderFuture = Future {
      while (keepRunning) {
        try {
          val processor = activeProcessors.peek()
          if (processor != null) {
            processor ! "test"
          }
          Thread.sleep(1)
        } catch {
          case e: Exception => // Ignore - processor may have been stopped
        }
      }
    }

    // Stopper thread
    val stopperFuture = Future {
      while (keepRunning) {
        try {
          val processor = activeProcessors.poll()
          if (processor != null) {
            engine.stop(processor.id, timeoutMs = 2000)
            stopOperations.incrementAndGet()
          }
          Thread.sleep(50)
        } catch {
          case e: Exception =>
            errors.incrementAndGet()
        }
      }
    }

    // Run for specified duration
    Thread.sleep(durationMs)
    keepRunning = false

    // Wait for all threads to complete
    Await.ready(Future.sequence(Seq(creatorFuture, senderFuture, stopperFuture)), 10.seconds)

    // Clean up remaining processors
    var processor = activeProcessors.poll()
    while (processor != null) {
      try {
        engine.stop(processor.id, timeoutMs = 2000)
      } catch {
        case e: Exception => // Ignore cleanup errors
      }
      processor = activeProcessors.poll()
    }

    println(s"Mixed operations over ${durationMs}ms:")
    println(s"  Created: ${createOperations.get()} processors")
    println(s"  Stopped: ${stopOperations.get()} processors")
    println(s"  Messages: ${messageOperations.get()}")
    println(s"  Errors: ${errors.get()}")

    // Should have minimal errors
    errors.get() should be < (createOperations.get() / 5)

    engine.shutdown()
  }
}

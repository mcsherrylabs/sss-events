package sss.events.stress

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sss.events.{DispatcherName, BackoffConfig, BaseEventProcessor, EngineConfig, EventProcessingEngine}
import sss.events.EventProcessor.EventHandler

import java.util.concurrent.{ConcurrentLinkedQueue, CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.concurrent.duration.*

/**
 * Stress tests for thread-to-dispatcher pinning with lock-based contention.
 *
 * These tests verify that the lock-based dispatcher queue protection maintains
 * correctness under high contention with many threads and processors.
 */
class ThreadPinningThreadSafetySpec extends AnyFlatSpec with Matchers {

  implicit val ec: ExecutionContext = ExecutionContext.global

  case class TestMessage(id: Int, dispatcherName: String)
  case object Complete

  "Thread pinning" should "maintain correctness with 16 threads on 4 dispatchers" in {
    // Configure 16 threads across 4 dispatchers plus subscriptions dispatcher
    // Each dispatcher gets 4 threads working on it
    val config = EngineConfig(
      schedulerPoolSize = 2,
      threadDispatcherAssignment = Array(
        Array("subscriptions"),  // Thread 0 - dedicated dispatcher for Subscriptions
        Array("A", "B"),  // Thread 1
        Array("A", "B"),  // Thread 2
        Array("A", "B"),  // Thread 3
        Array("A", "B"),  // Thread 4
        Array("C", "D"),  // Thread 5
        Array("C", "D"),  // Thread 6
        Array("C", "D"),  // Thread 7
        Array("C", "D"),  // Thread 8
        Array("A", "C"),  // Thread 9
        Array("A", "C"),  // Thread 10
        Array("B", "D"),  // Thread 11
        Array("B", "D"),  // Thread 12
        Array("A"),       // Thread 13
        Array("B"),       // Thread 14
        Array("C"),       // Thread 15
        Array("D")        // Thread 16
      ),
      backoff = BackoffConfig(
        baseDelayMicros = 10,
        multiplier = 1.5,
        maxDelayMicros = 10000
      )
    )

    implicit val engine: EventProcessingEngine = EventProcessingEngine(config)
    engine.start()

    val errors = new ConcurrentLinkedQueue[String]()
    val messagesReceived = new AtomicInteger(0)
    val completionLatch = new CountDownLatch(4) // 4 processors
    val messagesPerProcessor = 25000
    val totalMessages = messagesPerProcessor * 4  // 100,000 total

    // Create 4 processors, one per dispatcher
    val processorA: BaseEventProcessor = new BaseEventProcessor {
      override def dispatcherName: DispatcherName = DispatcherName.validated("A", config).getOrElse(throw new IllegalArgumentException("Invalid dispatcher: A"))
      override protected val onEvent: EventHandler = {
        case TestMessage(id, dispName) =>
          if (dispName != "A") {
            errors.add(s"Processor A received message for dispatcher $dispName")
          }
          messagesReceived.incrementAndGet()
        case Complete =>
          completionLatch.countDown()
      }
    }
    engine.register(processorA)

    val processorB: BaseEventProcessor = new BaseEventProcessor {
      override def dispatcherName: DispatcherName = DispatcherName.validated("B", config).getOrElse(throw new IllegalArgumentException("Invalid dispatcher: B"))
      override protected val onEvent: EventHandler = {
        case TestMessage(id, dispName) =>
          if (dispName != "B") {
            errors.add(s"Processor B received message for dispatcher $dispName")
          }
          messagesReceived.incrementAndGet()
        case Complete =>
          completionLatch.countDown()
      }
    }
    engine.register(processorB)

    val processorC: BaseEventProcessor = new BaseEventProcessor {
      override def dispatcherName: DispatcherName = DispatcherName.validated("C", config).getOrElse(throw new IllegalArgumentException("Invalid dispatcher: C"))
      override protected val onEvent: EventHandler = {
        case TestMessage(id, dispName) =>
          if (dispName != "C") {
            errors.add(s"Processor C received message for dispatcher $dispName")
          }
          messagesReceived.incrementAndGet()
        case Complete =>
          completionLatch.countDown()
      }
    }
    engine.register(processorC)

    val processorD: BaseEventProcessor = new BaseEventProcessor {
      override def dispatcherName: DispatcherName = DispatcherName.validated("D", config).getOrElse(throw new IllegalArgumentException("Invalid dispatcher: D"))
      override protected val onEvent: EventHandler = {
        case TestMessage(id, dispName) =>
          if (dispName != "D") {
            errors.add(s"Processor D received message for dispatcher $dispName")
          }
          messagesReceived.incrementAndGet()
        case Complete =>
          completionLatch.countDown()
      }
    }
    engine.register(processorD)

    // Post messages concurrently from multiple threads
    val postFutures = List(
      Future {
        (1 to messagesPerProcessor).foreach { i =>
          processorA.post(TestMessage(i, "A"))
        }
        processorA.post(Complete)
      },
      Future {
        (1 to messagesPerProcessor).foreach { i =>
          processorB.post(TestMessage(i, "B"))
        }
        processorB.post(Complete)
      },
      Future {
        (1 to messagesPerProcessor).foreach { i =>
          processorC.post(TestMessage(i, "C"))
        }
        processorC.post(Complete)
      },
      Future {
        (1 to messagesPerProcessor).foreach { i =>
          processorD.post(TestMessage(i, "D"))
        }
        processorD.post(Complete)
      }
    )

    // Wait for all posting to complete
    Await.ready(Future.sequence(postFutures), 10.seconds)

    // Wait for all messages to be processed
    val completed = completionLatch.await(30, TimeUnit.SECONDS)

    assert(completed, "Timeout waiting for message processing to complete")

    // Verify no errors occurred
    errors.size() shouldBe 0

    // Verify all messages were processed
    messagesReceived.get() shouldBe totalMessages

    engine.shutdown()
  }

  "Thread pinning" should "handle processors added after engine start" in {
    val config = EngineConfig(
      schedulerPoolSize = 2,
      threadDispatcherAssignment = Array(
        Array("subscriptions"),     // Dedicated dispatcher for Subscriptions
        Array("api"),
        Array("api"),
        Array("batch")
      ),
      backoff = BackoffConfig(
        baseDelayMicros = 10,
        multiplier = 1.5,
        maxDelayMicros = 10000
      )
    )

    implicit val engine: EventProcessingEngine = EventProcessingEngine(config)
    engine.start()

    val messagesReceived = new AtomicInteger(0)
    val latch = new CountDownLatch(2)
    val messagesPerProcessor = 1000

    // Start posting messages, then add processors dynamically
    val proc1Future = Future {
      val processor1: BaseEventProcessor = new BaseEventProcessor {
        override def dispatcherName: DispatcherName = DispatcherName.validated("api", config).getOrElse(throw new IllegalArgumentException("Invalid dispatcher: api"))
        override protected val onEvent: EventHandler = {
          case msg: Int =>
            messagesReceived.incrementAndGet()
            if (msg == messagesPerProcessor) latch.countDown()
        }
      }
      engine.register(processor1)
      (1 to messagesPerProcessor).foreach(processor1.post)
    }

    Thread.sleep(10) // Small delay to create interleaving

    val proc2Future = Future {
      val processor2: BaseEventProcessor = new BaseEventProcessor {
        override def dispatcherName: DispatcherName = DispatcherName.validated("batch", config).getOrElse(throw new IllegalArgumentException("Invalid dispatcher: batch"))
        override protected val onEvent: EventHandler = {
          case msg: Int =>
            messagesReceived.incrementAndGet()
            if (msg == messagesPerProcessor) latch.countDown()
        }
      }
      engine.register(processor2)
      (1 to messagesPerProcessor).foreach(processor2.post)
    }

    // Wait for completion
    Await.ready(proc1Future, 5.seconds)
    Await.ready(proc2Future, 5.seconds)

    val completed = latch.await(10, TimeUnit.SECONDS)
    assert(completed, "Timeout waiting for processors to complete")

    messagesReceived.get() shouldBe messagesPerProcessor * 2

    engine.shutdown()
  }
}

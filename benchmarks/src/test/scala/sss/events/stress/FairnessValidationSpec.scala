package sss.events.stress

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sss.events.{DispatcherName, BackoffConfig, BaseEventProcessor, EngineConfig, EventProcessingEngine}
import sss.events.EventProcessor.EventHandler

import java.util.concurrent.{ConcurrentHashMap, CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * Tests to validate correctness under high lock contention.
 *
 * Note: These tests use non-fair locks (as per design), so one thread may
 * dominate work distribution. The tests validate correctness and progress,
 * not fair distribution of work across threads.
 */
class FairnessValidationSpec extends AnyFlatSpec with Matchers {

  implicit val ec: ExecutionContext = ExecutionContext.global

  case class TestMessage(id: Int)
  case object Complete

  "Dispatcher threads" should "handle high contention without errors" in {
    // Configure 8 threads on single dispatcher to create maximum contention
    val config = EngineConfig(
      schedulerPoolSize = 2,
      threadDispatcherAssignment = Array(
        Array("subscriptions"),  // Dedicated for Subscriptions
        Array("workload"),
        Array("workload"),
        Array("workload"),
        Array("workload"),
        Array("workload"),
        Array("workload"),
        Array("workload"),
        Array("workload")
      ),
      backoff = BackoffConfig(
        baseDelayMicros = 10,
        multiplier = 1.5,
        maxDelayMicros = 10000
      )
    )

    implicit val engine: EventProcessingEngine = EventProcessingEngine(config)
    engine.start()

    val threadWorkCount = new ConcurrentHashMap[String, AtomicInteger]()
    val latch = new CountDownLatch(1)
    val totalMessages = 100000
    val received = new AtomicInteger(0)

    val processor: BaseEventProcessor = new BaseEventProcessor {
      override def dispatcherName: DispatcherName = DispatcherName.validated("workload", config).getOrElse(throw new IllegalArgumentException("Invalid dispatcher: workload"))

      override protected val onEvent: EventHandler = {
        case TestMessage(_) =>
          val threadName = Thread.currentThread().getName
          threadWorkCount.computeIfAbsent(threadName, _ => new AtomicInteger(0))
            .incrementAndGet()
          if (received.incrementAndGet() == totalMessages) {
            latch.countDown()
          }
        case Complete =>
          latch.countDown()
      }
    }

    // Post messages
    (1 to totalMessages).foreach(i => processor.post(TestMessage(i)))

    val completed = latch.await(30, TimeUnit.SECONDS)
    assert(completed, "Timeout waiting for message processing")

    // Verify correctness: all messages processed
    received.get() shouldBe totalMessages

    // With non-fair locks, one thread may dominate (this is expected behavior)
    val counts = threadWorkCount.values().asScala.map(_.get()).toList.filter(_ > 0)
    val totalProcessed = counts.sum
    totalProcessed shouldBe totalMessages

    println(s"Work distribution across ${counts.size} threads:")
    println(s"  Total processed: $totalProcessed")
    if (counts.size > 1) {
      println(s"  Range: ${counts.min} - ${counts.max}")
    }

    engine.shutdown()
  }

  it should "not starve any thread under sustained load" in {
    // 4 threads on 2 dispatchers
    val config = EngineConfig(
      schedulerPoolSize = 2,
      threadDispatcherAssignment = Array(
        Array("subscriptions"),     // Subscriptions processor
        Array("A", "B"),
        Array("A", "B"),
        Array("A", "B"),
        Array("A", "B")
      ),
      backoff = BackoffConfig(10, 1.5, 10000)
    )

    implicit val engine: EventProcessingEngine = EventProcessingEngine(config)
    engine.start()

    val threadFirstMessageTime = new ConcurrentHashMap[String, Long]()
    val threadLastMessageTime = new ConcurrentHashMap[String, Long]()
    val latch = new CountDownLatch(2)
    val messagesPerProcessor = 5000

    val processorA: BaseEventProcessor = new BaseEventProcessor {
      override def dispatcherName: DispatcherName = DispatcherName.validated("A", config).getOrElse(throw new IllegalArgumentException("Invalid dispatcher: A"))
      val received = new AtomicInteger(0)

      override protected val onEvent: EventHandler = {
        case TestMessage(_) =>
          val threadName = Thread.currentThread().getName
          val now = System.nanoTime()
          threadFirstMessageTime.putIfAbsent(threadName, now)
          threadLastMessageTime.put(threadName, now)
          if (received.incrementAndGet() == messagesPerProcessor) latch.countDown()
      }
    }

    val processorB: BaseEventProcessor = new BaseEventProcessor {
      override def dispatcherName: DispatcherName = DispatcherName.validated("B", config).getOrElse(throw new IllegalArgumentException("Invalid dispatcher: B"))
      val received = new AtomicInteger(0)

      override protected val onEvent: EventHandler = {
        case TestMessage(_) =>
          val threadName = Thread.currentThread().getName
          val now = System.nanoTime()
          threadFirstMessageTime.putIfAbsent(threadName, now)
          threadLastMessageTime.put(threadName, now)
          if (received.incrementAndGet() == messagesPerProcessor) latch.countDown()
      }
    }

    // Post messages concurrently
    val futures = List(
      Future { (1 to messagesPerProcessor).foreach(i => processorA.post(TestMessage(i))) },
      Future { (1 to messagesPerProcessor).foreach(i => processorB.post(TestMessage(i))) }
    )

    Await.ready(Future.sequence(futures), 5.seconds)

    val completed = latch.await(30, TimeUnit.SECONDS)
    assert(completed, "Timeout waiting for processing")

    // Verify no thread was starved (all threads that worked had reasonable time span)
    val threadNames = threadFirstMessageTime.keySet().asScala.toList
    threadNames.size should (be >= 2)  // With non-fair locks, expect at least 2 threads

    val timeSpans = threadNames.map { threadName =>
      val first = threadFirstMessageTime.get(threadName)
      val last = threadLastMessageTime.get(threadName)
      (last - first) / 1_000_000.0  // Convert to milliseconds
    }

    // All threads should be active throughout the test
    // If a thread was starved, it would have a very short time span
    val minTimeSpan = timeSpans.min
    val maxTimeSpan = timeSpans.max

    // No thread should have < 10% of the max time span (would indicate starvation)
    (minTimeSpan / maxTimeSpan) should (be >= 0.1)

    println(s"Thread activity time spans:")
    println(s"  Min: ${minTimeSpan.toInt}ms")
    println(s"  Max: ${maxTimeSpan.toInt}ms")
    println(s"  Ratio: ${((minTimeSpan / maxTimeSpan) * 100).toInt}%")

    engine.shutdown()
  }

  it should "handle mixed dispatcher assignments correctly" in {
    // Some threads work on multiple dispatchers, some on single
    val config = EngineConfig(
      schedulerPoolSize = 2,
      threadDispatcherAssignment = Array(
        Array("subscriptions"),  // Dedicated for Subscriptions
        Array("shared"),  // Exclusive threads
        Array("shared"),
        Array("shared", "mixed"),  // Shared threads
        Array("shared", "mixed"),
        Array("mixed")    // Exclusive to mixed
      ),
      backoff = BackoffConfig(10, 1.5, 10000)
    )

    implicit val engine: EventProcessingEngine = EventProcessingEngine(config)
    engine.start()

    val sharedThreadWork = new ConcurrentHashMap[String, AtomicInteger]()
    val mixedThreadWork = new ConcurrentHashMap[String, AtomicInteger]()
    val latch = new CountDownLatch(2)
    val messagesPerProcessor = 50000

    val sharedProcessor: BaseEventProcessor = new BaseEventProcessor {
      override def dispatcherName: DispatcherName = DispatcherName.validated("shared", config).getOrElse(throw new IllegalArgumentException("Invalid dispatcher: shared"))
      val received = new AtomicInteger(0)

      override protected val onEvent: EventHandler = {
        case TestMessage(_) =>
          sharedThreadWork.computeIfAbsent(
            Thread.currentThread().getName,
            _ => new AtomicInteger(0)
          ).incrementAndGet()
          if (received.incrementAndGet() == messagesPerProcessor) latch.countDown()
      }
    }

    val mixedProcessor: BaseEventProcessor = new BaseEventProcessor {
      override def dispatcherName: DispatcherName = DispatcherName.validated("mixed", config).getOrElse(throw new IllegalArgumentException("Invalid dispatcher: mixed"))
      val received = new AtomicInteger(0)

      override protected val onEvent: EventHandler = {
        case TestMessage(_) =>
          mixedThreadWork.computeIfAbsent(
            Thread.currentThread().getName,
            _ => new AtomicInteger(0)
          ).incrementAndGet()
          if (received.incrementAndGet() == messagesPerProcessor) latch.countDown()
      }
    }

    // Post messages
    (1 to messagesPerProcessor).foreach(i => sharedProcessor.post(TestMessage(i)))
    (1 to messagesPerProcessor).foreach(i => mixedProcessor.post(TestMessage(i)))

    val completed = latch.await(30, TimeUnit.SECONDS)
    assert(completed, "Timeout waiting for processing")

    // Verify correctness: all messages processed on each dispatcher
    val sharedTotal = sharedThreadWork.values().asScala.map(_.get()).sum
    val mixedTotal = mixedThreadWork.values().asScala.map(_.get()).sum

    sharedTotal shouldBe messagesPerProcessor
    mixedTotal shouldBe messagesPerProcessor

    // At least one thread should have worked on each dispatcher
    sharedThreadWork.size() should (be >= 1)
    mixedThreadWork.size() should (be >= 1)

    println(s"Shared dispatcher: ${sharedThreadWork.size()} threads participated")
    println(s"Mixed dispatcher: ${mixedThreadWork.size()} threads participated")

    engine.shutdown()
  }
}

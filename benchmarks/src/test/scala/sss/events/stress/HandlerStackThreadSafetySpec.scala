package sss.events.stress

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sss.events.{DispatcherName, BackoffConfig, BaseEventProcessor, EngineConfig, EventProcessingEngine}
import sss.events.EventProcessor.EventHandler

import java.util.concurrent.{CountDownLatch, ConcurrentLinkedQueue, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.{ExecutionContext, Future, Promise, Await}
import scala.concurrent.duration.*

/**
 * Comprehensive stress tests for handler stack thread safety.
 *
 * These tests verify that the mutable.Stack[EventHandler] in BaseEventProcessor
 * is safe under concurrent access, particularly when become/unbecome are called
 * while processEvent is reading handlers.head.
 */
class HandlerStackThreadSafetySpec extends AnyFlatSpec with Matchers {

  implicit val ec: ExecutionContext = ExecutionContext.global

  case class RegularMessage(id: Int)
  case class BecomeMessage(handler: EventHandler)
  case object UnbecomeMessage
  case object Complete

  "Handler stack" should "be thread-safe with concurrent become calls from external threads" in {
    implicit val engine: EventProcessingEngine = EventProcessingEngine()
    engine.start()

    val errors = new ConcurrentLinkedQueue[String]()
    val messagesReceived = new AtomicInteger(0)
    val completionPromise = Promise[Unit]()
    val messageCount = 1000
    val becomeThreadCount = 4
    val becomeOpsCount = 100

    lazy val handler2: EventHandler = {
      case RegularMessage(id) =>
        messagesReceived.incrementAndGet()
      case BecomeMessage(h) =>
        // Ignore - this shouldn't be called on handler2
        ()
      case UnbecomeMessage =>
        // Ignore - this shouldn't be called on handler2
        ()
      case Complete =>
        completionPromise.success(())
    }

    val processor: BaseEventProcessor = new BaseEventProcessor {
      override protected val onEvent: EventHandler = {
        case RegularMessage(id) =>
          messagesReceived.incrementAndGet()
        case BecomeMessage(h) =>
          try {
            become(h, stackPreviousHandler = true)
          } catch {
            case e: Exception => errors.add(s"become failed: ${e.getMessage}")
          }
        case UnbecomeMessage =>
          try {
            unbecome()
          } catch {
            case e: Exception => errors.add(s"unbecome failed: ${e.getMessage}")
          }
        case Complete =>
          completionPromise.success(())
      }
    }
    engine.register(processor) // Register after construction completes

    // Multiple threads posting become/unbecome messages
    val becomeThreads = (1 to becomeThreadCount).map { _ =>
      Future {
        (1 to becomeOpsCount).foreach { _ =>
          processor.post(BecomeMessage(handler2))
          processor.post(UnbecomeMessage)
        }
      }
    }

    // Main thread continuously posting regular messages
    val postFuture = Future {
      (1 to messageCount).foreach { i =>
        processor.post(RegularMessage(i))
      }
    }

    // Wait for all threads to finish posting
    Await.ready(Future.sequence(becomeThreads), 15.seconds)
    Await.ready(postFuture, 5.seconds)

    // Now post Complete message and wait for it to be processed
    processor.post(Complete)

    // Wait for completion
    try {
      Await.result(completionPromise.future, 10.seconds)
    } catch {
      case e: Exception => errors.add(s"Timeout or error: ${e.getMessage}")
    }

    engine.stop(processor.id)
    engine.shutdown()

    // Verify no errors and all messages received
    errors.size() shouldBe 0
    messagesReceived.get() shouldBe messageCount
  }

  it should "handle rapid become/unbecome cycles within handlers" in {
    implicit val engine: EventProcessingEngine = EventProcessingEngine()
    engine.start()

    val errors = new ConcurrentLinkedQueue[String]()
    val cycleCount = new AtomicInteger(0)
    val completionPromise = Promise[Unit]()
    val targetCycles = 500

    val processor = new BaseEventProcessor {
      lazy val handler1: EventHandler = {
        case RegularMessage(id) =>
          try {
            become(handler2, stackPreviousHandler = true)
            post(RegularMessage(id))
          } catch {
            case e: Exception => errors.add(s"handler1 become failed: ${e.getMessage}")
          }
      }

      lazy val handler2: EventHandler = {
        case RegularMessage(id) =>
          try {
            become(handler3, stackPreviousHandler = true)
            post(RegularMessage(id))
          } catch {
            case e: Exception => errors.add(s"handler2 become failed: ${e.getMessage}")
          }
      }

      lazy val handler3: EventHandler = {
        case RegularMessage(id) =>
          try {
            unbecome()
            unbecome()
            val count = cycleCount.incrementAndGet()
            if count < targetCycles then
              post(RegularMessage(id))
            else
              completionPromise.success(())
          } catch {
            case e: Exception => errors.add(s"handler3 unbecome failed: ${e.getMessage}")
          }
      }

      override protected val onEvent: EventHandler = handler1
    }
    engine.register(processor) // Register after construction completes

    processor.post(RegularMessage(1))

    try {
      Await.result(completionPromise.future, 10.seconds)
    } catch {
      case e: Exception => errors.add(s"Timeout: ${e.getMessage}")
    }

    engine.stop(processor.id)
    engine.shutdown()

    errors.size() shouldBe 0
    cycleCount.get() shouldBe targetCycles
  }

  it should "be thread-safe with concurrent posting during handler replacement" in {
    val config = EngineConfig(
      schedulerPoolSize = 2,
      threadDispatcherAssignment = Array(Array("subscriptions")) ++ Array.fill(3)(Array("")),
      backoff = BackoffConfig(10, 1.5, 10000)
    )
    implicit val engine: EventProcessingEngine = EventProcessingEngine(config)
    engine.start()

    val errors = new ConcurrentLinkedQueue[String]()
    val messagesReceived = new AtomicInteger(0)
    val completionPromise = Promise[Unit]()
    val messageCount = 2000
    val posterThreadCount = 4

    val processor = new BaseEventProcessor {
      lazy val handler1: EventHandler = {
        case RegularMessage(id) =>
          val count = messagesReceived.incrementAndGet()
          if count % 50 == 0 then
            try {
              become(handler2, stackPreviousHandler = false)
            } catch {
              case e: Exception => errors.add(s"handler1 become failed: ${e.getMessage}")
            }
          if count == messageCount then
            completionPromise.success(())
      }

      lazy val handler2: EventHandler = {
        case RegularMessage(id) =>
          val count = messagesReceived.incrementAndGet()
          if count % 50 == 0 then
            try {
              become(handler1, stackPreviousHandler = false)
            } catch {
              case e: Exception => errors.add(s"handler2 become failed: ${e.getMessage}")
            }
          if count == messageCount then
            completionPromise.success(())
      }

      override protected val onEvent: EventHandler = handler1
    }
    engine.register(processor) // Register after construction completes

    // Multiple threads posting messages concurrently
    val posterThreads = (1 to posterThreadCount).map { threadId =>
      Future {
        val messagesPerThread = messageCount / posterThreadCount
        (1 to messagesPerThread).foreach { i =>
          processor.post(RegularMessage(threadId * 1000 + i))
        }
      }
    }

    try {
      Await.result(completionPromise.future, 15.seconds)
    } catch {
      case e: Exception => errors.add(s"Timeout: ${e.getMessage}")
    }

    Await.ready(Future.sequence(posterThreads), 5.seconds)
    engine.stop(processor.id)
    engine.shutdown()

    errors.size() shouldBe 0
    messagesReceived.get() shouldBe messageCount
  }

  it should "handle deep handler stacks without errors" in {
    implicit val engine: EventProcessingEngine = EventProcessingEngine()
    engine.start()

    val errors = new ConcurrentLinkedQueue[String]()
    val stackDepth = new AtomicInteger(0)
    val completionPromise = Promise[Unit]()
    val maxDepth = 100

    case object PushHandler
    case object PopHandler

    val processor = new BaseEventProcessor {
      lazy val deepHandler: EventHandler = {
        case PushHandler =>
          val depth = stackDepth.incrementAndGet()
          if depth < maxDepth then
            try {
              become(deepHandler, stackPreviousHandler = true)
              post(PushHandler)
            } catch {
              case e: Exception => errors.add(s"Deep push failed at depth $depth: ${e.getMessage}")
            }
          else
            post(PopHandler)
        case PopHandler =>
          val depth = stackDepth.get()
          if depth > 1 then
            try {
              unbecome()
              stackDepth.decrementAndGet()
              post(PopHandler)
            } catch {
              case e: Exception => errors.add(s"Deep pop failed at depth $depth: ${e.getMessage}")
            }
          else
            completionPromise.success(())
      }

      override protected val onEvent: EventHandler = {
        case PushHandler =>
          val depth = stackDepth.incrementAndGet()
          if depth < maxDepth then
            try {
              become(deepHandler, stackPreviousHandler = true)
              post(PushHandler)
            } catch {
              case e: Exception => errors.add(s"Push failed at depth $depth: ${e.getMessage}")
            }
          else
            post(PopHandler)

        case PopHandler =>
          val depth = stackDepth.get()
          if depth > 1 then
            try {
              unbecome()
              stackDepth.decrementAndGet()
              post(PopHandler)
            } catch {
              case e: Exception => errors.add(s"Pop failed at depth $depth: ${e.getMessage}")
            }
          else
            completionPromise.success(())
      }
    }
    engine.register(processor) // Register after construction completes

    processor.post(PushHandler)

    try {
      Await.result(completionPromise.future, 10.seconds)
    } catch {
      case e: Exception => errors.add(s"Timeout: ${e.getMessage}")
    }

    engine.stop(processor.id)
    engine.shutdown()

    errors.size() shouldBe 0
    stackDepth.get() shouldBe 1
  }

  it should "handle message bursts during handler changes without message loss" in {
    implicit val engine: EventProcessingEngine = EventProcessingEngine()
    engine.start()

    val errors = new ConcurrentLinkedQueue[String]()
    val messagesReceived = new AtomicInteger(0)
    val completionPromise = Promise[Unit]()
    val burstSize = 1000
    val burstCount = 5
    val totalMessages = burstSize * burstCount

    lazy val handler1: EventHandler = {
      case RegularMessage(id) =>
        val count = messagesReceived.incrementAndGet()
        if count == totalMessages then
          completionPromise.success(())
    }

    lazy val handler2: EventHandler = {
      case RegularMessage(id) =>
        val count = messagesReceived.incrementAndGet()
        if count == totalMessages then
          completionPromise.success(())
    }

    val processor = new BaseEventProcessor {
      override protected val onEvent: EventHandler = handler1
    }
    engine.register(processor) // Register after construction completes

    // Send bursts of messages, switching handlers between bursts
    (1 to burstCount).foreach { burst =>
      // Send burst
      (1 to burstSize).foreach { i =>
        processor.post(RegularMessage(burst * 10000 + i))
      }

      // Switch handler (alternate between handler1 and handler2)
      if burst < burstCount then
        try {
          val nextHandler = if burst % 2 == 1 then handler2 else handler1
          processor.requestBecome(nextHandler, stackPreviousHandler = false)
        } catch {
          case e: Exception => errors.add(s"Handler switch failed at burst $burst: ${e.getMessage}")
        }
    }

    try {
      Await.result(completionPromise.future, 15.seconds)
    } catch {
      case e: Exception => errors.add(s"Timeout: ${e.getMessage}")
    }

    engine.stop(processor.id)
    engine.shutdown()

    errors.size() shouldBe 0
    messagesReceived.get() shouldBe totalMessages
  }
}

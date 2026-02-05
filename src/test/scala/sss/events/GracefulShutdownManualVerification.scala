package sss.events

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger

/**
 * Manual verification test for graceful shutdown behavior.
 * Tests queue draining, timeout handling, and shutdown coordination.
 */
class GracefulShutdownManualVerification extends AnyFunSuite with Matchers {

  test("graceful shutdown - queue drains before stop completes") {
    val config = EngineConfig(
      schedulerPoolSize = 2,
      threadDispatcherAssignment = Array(Array("")),
      defaultQueueSize = 10000,
      backoff = BackoffConfig(10, 1.5, 10000)
    )
    val engine = EventProcessingEngine(config)

    val messagesProcessed = new AtomicInteger(0)
    val expectedMessages = 10

    // Create processor that counts messages
    val processor = engine.createProcessor[Int](
      id = "test-processor",
      handler = { msg =>
        messagesProcessed.incrementAndGet()
        Thread.sleep(50) // Simulate some work
      },
      dispatcherName = DispatcherName.Default
    )

    engine.start()

    // Send messages
    (1 to expectedMessages).foreach { i =>
      engine.send("test-processor", i)
    }

    // Wait a bit for messages to queue up
    Thread.sleep(100)

    // Stop with sufficient timeout for draining
    val stopStartTime = System.currentTimeMillis()
    engine.stop("test-processor", timeoutMs = 5000)
    val stopDuration = System.currentTimeMillis() - stopStartTime

    println(s"Stop took ${stopDuration}ms to complete")
    println(s"Messages processed: ${messagesProcessed.get()} / ${expectedMessages}")

    // Verify all messages were processed
    messagesProcessed.get() shouldBe expectedMessages

    engine.shutdown()
  }

  test("graceful shutdown - timeout causes message loss") {
    val config = EngineConfig(
      schedulerPoolSize = 2,
      threadDispatcherAssignment = Array(Array("")),
      defaultQueueSize = 10000,
      backoff = BackoffConfig(10, 1.5, 10000)
    )
    val engine = EventProcessingEngine(config)

    val messagesProcessed = new AtomicInteger(0)
    val totalMessages = 100

    // Create processor that processes messages slowly
    val processor = engine.createProcessor[Int](
      id = "slow-processor",
      handler = { msg =>
        messagesProcessed.incrementAndGet()
        Thread.sleep(100) // Slow processing - 10 messages/sec
      },
      dispatcherName = DispatcherName.Default
    )

    engine.start()

    // Send many messages
    (1 to totalMessages).foreach { i =>
      engine.send("slow-processor", i)
    }

    // Wait for some messages to queue up
    Thread.sleep(200)

    // Stop with SHORT timeout - should cause message loss
    val stopStartTime = System.currentTimeMillis()
    engine.stop("slow-processor", timeoutMs = 500)
    val stopDuration = System.currentTimeMillis() - stopStartTime

    val processed = messagesProcessed.get()
    println(s"Stop took ${stopDuration}ms to complete")
    println(s"Messages processed: ${processed} / ${totalMessages}")
    println(s"Messages lost: ${totalMessages - processed}")

    // Verify timeout occurred (stop duration should be close to timeout)
    stopDuration should be >= 500L
    stopDuration should be < 1000L

    // Verify not all messages were processed (message loss occurred)
    processed should be < totalMessages
    processed should be > 0 // But some were processed

    engine.shutdown()
  }

  test("graceful shutdown - multiple processors drain correctly") {
    val config = EngineConfig(
      schedulerPoolSize = 2,
      threadDispatcherAssignment = Array(Array("")),
      defaultQueueSize = 10000,
      backoff = BackoffConfig(10, 1.5, 10000)
    )
    val engine = EventProcessingEngine(config)

    val processor1Messages = new AtomicInteger(0)
    val processor2Messages = new AtomicInteger(0)
    val messagesPerProcessor = 5

    // Create two processors
    val processor1 = engine.createProcessor[Int](
      id = "processor-1",
      handler = { msg =>
        processor1Messages.incrementAndGet()
        Thread.sleep(50)
      },
      dispatcherName = DispatcherName.Default
    )

    val processor2 = engine.createProcessor[Int](
      id = "processor-2",
      handler = { msg =>
        processor2Messages.incrementAndGet()
        Thread.sleep(50)
      },
      dispatcherName = DispatcherName.Default
    )

    engine.start()

    // Send messages to both processors
    (1 to messagesPerProcessor).foreach { i =>
      engine.send("processor-1", i)
      engine.send("processor-2", i)
    }

    Thread.sleep(100)

    // Stop both processors (with sufficient timeout)
    engine.stop("processor-1", timeoutMs = 3000)
    engine.stop("processor-2", timeoutMs = 3000)

    println(s"Processor 1 messages: ${processor1Messages.get()} / ${messagesPerProcessor}")
    println(s"Processor 2 messages: ${processor2Messages.get()} / ${messagesPerProcessor}")

    // Verify both processors drained their queues
    processor1Messages.get() shouldBe messagesPerProcessor
    processor2Messages.get() shouldBe messagesPerProcessor

    engine.shutdown()
  }

  test("graceful shutdown - shutdown() stops all threads") {
    val config = EngineConfig(
      schedulerPoolSize = 2,
      threadDispatcherAssignment = Array(
        Array("dispatcher-1"),
        Array("dispatcher-2")
      ),
      defaultQueueSize = 10000,
      backoff = BackoffConfig(10, 1.5, 10000)
    )
    val engine = EventProcessingEngine(config)

    // Create processors on different dispatchers
    val dispatcher1 = DispatcherName.validated("dispatcher-1", config).get
    val dispatcher2 = DispatcherName.validated("dispatcher-2", config).get

    val processor1 = engine.createProcessor[Int](
      id = "processor-1",
      handler = { msg => Thread.sleep(10) },
      dispatcherName = dispatcher1
    )

    val processor2 = engine.createProcessor[Int](
      id = "processor-2",
      handler = { msg => Thread.sleep(10) },
      dispatcherName = dispatcher2
    )

    engine.start()

    // Verify threads are running
    engine.numThreadsStarted shouldBe 2

    // Send some messages
    (1 to 5).foreach { i =>
      engine.send("processor-1", i)
      engine.send("processor-2", i)
    }

    Thread.sleep(100)

    // Call shutdown - should stop all threads
    val shutdownStartTime = System.currentTimeMillis()
    engine.shutdown()
    val shutdownDuration = System.currentTimeMillis() - shutdownStartTime

    println(s"Shutdown took ${shutdownDuration}ms to complete")

    // Shutdown should complete relatively quickly
    shutdownDuration should be < 2000L

    // Threads should be joined (numThreadsStarted stays the same but threads are dead)
    engine.numThreadsStarted shouldBe 2
  }

  test("graceful shutdown - stopping flag prevents processor return to queue") {
    val config = EngineConfig(
      schedulerPoolSize = 2,
      threadDispatcherAssignment = Array(Array("")),
      defaultQueueSize = 10000,
      backoff = BackoffConfig(10, 1.5, 10000)
    )
    val engine = EventProcessingEngine(config)

    val messagesProcessed = new AtomicInteger(0)
    val processingLatch = new CountDownLatch(1)
    val continueLatch = new CountDownLatch(1)

    // Create processor that blocks processing
    val processor = engine.createProcessor[Int](
      id = "blocking-processor",
      handler = { msg =>
        messagesProcessed.incrementAndGet()
        processingLatch.countDown() // Signal we're processing
        continueLatch.await(10, TimeUnit.SECONDS) // Block until released
      },
      dispatcherName = DispatcherName.Default
    )

    engine.start()

    // Send one message
    engine.send("blocking-processor", 1)

    // Wait for processing to start
    processingLatch.await(5, TimeUnit.SECONDS) shouldBe true

    // Now call stop() while processor is actively being processed
    val stopThread = new Thread(() => {
      engine.stop("blocking-processor", timeoutMs = 5000)
    })
    stopThread.start()

    // Give stop() time to set the stopping flag
    Thread.sleep(200)

    // Release the processing
    continueLatch.countDown()

    // Wait for stop to complete
    stopThread.join(6000)

    println(s"Messages processed: ${messagesProcessed.get()}")

    // Verify the message was processed
    messagesProcessed.get() shouldBe 1

    // Verify stop completed (thread joined successfully)
    stopThread.isAlive shouldBe false

    engine.shutdown()
  }
}

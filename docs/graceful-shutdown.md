# Graceful Shutdown Semantics

## Overview

The `EventProcessingEngine.stop()` method implements a comprehensive graceful shutdown process for event processors. This document details the shutdown semantics, behavior, guarantees, and best practices.

## Key Guarantees

### 1. Zero Message Loss (Best Effort)

The shutdown process attempts to process all messages remaining in a processor's queue before stopping:

- **Queue Draining**: The stop method blocks until the processor's internal message queue is empty
- **Configurable Timeout**: Default 30 seconds, configurable per stop call
- **Warning on Drain**: Logs warning when draining begins with message count and timeout
- **Critical Error on Loss**: Logs critical error if timeout expires with messages still queued

### 2. Thread-Safe Removal

The shutdown process coordinates with worker threads to avoid race conditions:

- **Lock Coordination**: Acquires dispatcher lock before removal
- **Active Processing Protection**: Waits for processor to return to queue if currently being processed
- **Timeout Protection**: 5-second timeout prevents indefinite waiting for processor return

### 3. Clean Unregistration

After safe removal from dispatcher queues:

- **Registry Cleanup**: Processor unregistered from ID registry
- **Visibility Maintained**: Unregistration happens AFTER queue draining completes

## Method Signature

```scala
def stop(id: EventProcessorId, timeoutMs: Long = 30000): Unit
```

**Parameters:**
- `id`: The EventProcessorId of the processor to stop
- `timeoutMs`: Maximum time in milliseconds to wait for queue to drain (default: 30000ms = 30 seconds)

## Shutdown Process

The stop method executes the following steps in order:

### Phase 1: Queue Draining (0-30 seconds by default)

```
1. Look up processor by ID in registrar
2. Check current queue size
3. If queue has messages:
   a. Log warning with message count and timeout
   b. Poll queue size every 10ms
   c. Continue until queue empty OR timeout expires
4. If timeout with messages remaining:
   a. Log CRITICAL error with remaining message count
   b. Log CRITICAL error stating messages will be lost
```

**Behavior:**
- **Blocking**: Caller thread blocks during this phase
- **Polling Interval**: 10ms sleep between queue size checks
- **No Interruption**: Processor continues processing during drain

### Phase 2: Dispatcher Removal (0-5 seconds)

```
1. Search all dispatchers to find which contains the processor
2. If found:
   a. Acquire dispatcher's lock
   b. Attempt to remove processor from queue
   c. If not in queue (worker has it):
      - Wait up to 5 seconds for worker to return it
      - Poll every 10ms with lock held
   d. Release lock
3. If not found:
   a. Check all dispatchers with locks held
   b. Attempt removal from each (defensive)
```

**Behavior:**
- **Lock-Protected**: All queue operations under dispatcher lock
- **Race Condition Safe**: Handles processor being actively processed by worker
- **Defensive**: Checks all dispatchers if not found in expected location

### Phase 3: Unregistration

```
1. Remove processor from ID registrar
2. Processor ID now invalid for future operations
```

**Behavior:**
- **Final Step**: Only happens after successful removal from dispatcher
- **Irreversible**: Processor cannot be restarted with same ID

## Usage Examples

### Basic Stop

```scala
implicit val engine = EventProcessingEngine(config)
engine.start()

val processor = new MyProcessor()
// ... use processor ...

// Stop with default 30-second timeout
engine.stop(processor.id)
```

### Custom Timeout

```scala
// Stop with 60-second timeout for slow processors
engine.stop(processor.id, timeoutMs = 60000)

// Stop with 5-second timeout for fast processors
engine.stop(processor.id, timeoutMs = 5000)
```

### Batch Shutdown

```scala
// Stop multiple processors
val processorIds: Seq[EventProcessorId] = // ... your processors

processorIds.foreach { id =>
  try {
    engine.stop(id, timeoutMs = 30000)
    println(s"Successfully stopped processor $id")
  } catch {
    case e: Exception =>
      println(s"Failed to stop processor $id: ${e.getMessage}")
  }
}
```

### Application Shutdown Hook

```scala
// Graceful shutdown on JVM exit
Runtime.getRuntime.addShutdownHook(new Thread {
  override def run(): Unit = {
    println("Shutting down gracefully...")

    // Stop all processors
    val allProcessors: Seq[EventProcessorId] = // ... get from registry
    allProcessors.foreach { id =>
      try {
        engine.stop(id, timeoutMs = 10000)  // 10s timeout on shutdown
      } catch {
        case _: Exception => // Log but don't fail shutdown
      }
    }

    // Shutdown engine
    engine.shutdown()
    println("Shutdown complete")
  }
})
```

## Timeout Behavior

### When Timeout Occurs

If the queue doesn't drain within the timeout period:

1. **Critical Logs Generated:**
   ```
   CRITICAL: Timeout waiting for processor <id> queue to drain. N messages remaining after Xms
   CRITICAL: N messages will be lost from processor <id>
   ```

2. **Process Continues:** Stop proceeds with dispatcher removal despite timeout

3. **Messages Lost:** Remaining messages in queue are abandoned

### Choosing Timeout Values

**Short Timeout (1-5 seconds):**
- Use for: Fast processors, low-priority work
- Risk: Higher chance of message loss
- Benefit: Quick shutdown

**Medium Timeout (10-30 seconds) - Default:**
- Use for: Most applications, moderate processing time
- Risk: Balanced
- Benefit: Good balance of safety and speed

**Long Timeout (60+ seconds):**
- Use for: Slow processors, critical data, large queues
- Risk: Slow shutdown
- Benefit: Maximum message preservation

### Calculating Appropriate Timeout

```
timeout = (queue_size × avg_processing_time) + safety_margin

Example:
- Queue size: 1000 messages
- Avg processing: 20ms per message
- Total time: 1000 × 20ms = 20 seconds
- Safety margin: 10 seconds
- Recommended timeout: 30 seconds
```

## Blocking Semantics

### Caller Thread Blocking

**The stop() method BLOCKS the calling thread:**

```scala
val startTime = System.currentTimeMillis()
engine.stop(processor.id)  // BLOCKS HERE
val duration = System.currentTimeMillis() - startTime
println(s"Stop took ${duration}ms")
```

**Implications:**
- Don't call from latency-sensitive threads
- Don't call from actor message handlers (can cause deadlock)
- Do call from shutdown hooks, cleanup tasks, or dedicated shutdown threads

### Concurrent Stop Calls

**Multiple threads can call stop() concurrently:**

```scala
// Thread 1
engine.stop(processor1.id)

// Thread 2 (concurrent)
engine.stop(processor2.id)  // OK - different processors

// Thread 3 (concurrent)
engine.stop(processor1.id)  // OK - idempotent, one will succeed
```

**Behavior:**
- **Different Processors:** Safe, independent operations
- **Same Processor:** Safe, one completes and removes it, others find it already gone
- **No Deadlock:** Lock acquisition order is well-defined per dispatcher

## Error Handling

### Processor Not Found

```scala
engine.stop(unknownId)  // Processor doesn't exist
// No error thrown, method returns normally
```

**Behavior:** If processor ID not found in registrar, stop returns immediately with no action.

### Timeout During Drain

```scala
engine.stop(processor.id, timeoutMs = 1000)
// If queue doesn't drain in 1 second:
// - CRITICAL errors logged
// - Stop continues with removal
// - Returns normally (doesn't throw)
```

**Behavior:** Timeout is not an exception - it's logged as critical but method completes.

### Active Processing Race

```scala
// Worker thread is actively processing when stop called
engine.stop(processor.id)
// - Waits up to 5 seconds for worker to return processor
// - If timeout, logs warning and proceeds
// - Returns normally
```

**Behavior:** Active processing is handled gracefully with timeout protection.

## Best Practices

### 1. Choose Appropriate Timeouts

```scala
// GOOD - Fast processors
engine.stop(fastProcessor.id, timeoutMs = 5000)

// GOOD - Slow processors with large queues
engine.stop(slowProcessor.id, timeoutMs = 60000)

// BAD - Too short for typical workload
engine.stop(processor.id, timeoutMs = 100)  // Likely message loss
```

### 2. Don't Call from Hot Paths

```scala
// BAD - Calling stop from message handler
class MyProcessor extends BaseEventProcessor {
  override protected val onEvent: EventHandler = {
    case StopCommand =>
      engine.stop(this.id)  // DON'T DO THIS - can deadlock!
  }
}

// GOOD - Use separate shutdown thread
class MyProcessor extends BaseEventProcessor {
  override protected val onEvent: EventHandler = {
    case StopCommand =>
      Future { engine.stop(this.id) }  // Non-blocking
  }
}
```

### 3. Monitor Queue Depth Before Stop

```scala
// GOOD - Check queue size before stopping
val queueSize = processor.currentQueueSize
if (queueSize > 10000) {
  println(s"Warning: Stopping with $queueSize messages queued")
  // Consider longer timeout or wait for queue to drain naturally
}
engine.stop(processor.id, timeoutMs = calculateTimeout(queueSize))
```

### 4. Handle Shutdown in Correct Order

```scala
// GOOD - Stop processors before engine shutdown
allProcessors.foreach(id => engine.stop(id))
engine.shutdown()  // After all processors stopped

// BAD - Shutdown engine first
engine.shutdown()  // Workers stopped
allProcessors.foreach(id => engine.stop(id))  // Can't drain, workers gone!
```

### 5. Log Timeout Situations

```scala
// GOOD - Monitor for timeout situations
val beforeSize = processor.currentQueueSize
engine.stop(processor.id, timeoutMs = 30000)
// Check logs for CRITICAL errors indicating timeout

// BETTER - Implement monitoring
trait MonitoredStop {
  def stopWithMetrics(id: EventProcessorId, timeout: Long): Unit = {
    val beforeSize = engine.registrar.get(id).map(_.currentQueueSize).getOrElse(0)
    val start = System.currentTimeMillis()
    engine.stop(id, timeout)
    val duration = System.currentTimeMillis() - start

    if (duration > timeout * 0.9) {
      metrics.recordSlowStop(id, duration, beforeSize)
    }
  }
}
```

## Common Pitfalls

### Pitfall 1: Insufficient Timeout

**Problem:**
```scala
// 1 second timeout with 10,000 messages in queue
engine.stop(processor.id, timeoutMs = 1000)
// Result: Message loss
```

**Solution:**
```scala
// Calculate timeout based on queue size and processing rate
val queueSize = processor.currentQueueSize
val avgProcessingTimeMs = 20  // Measure this in your app
val timeout = Math.max(30000, queueSize * avgProcessingTimeMs * 2)
engine.stop(processor.id, timeoutMs = timeout)
```

### Pitfall 2: Calling Stop from Processor Handler

**Problem:**
```scala
class SelfStoppingProcessor extends BaseEventProcessor {
  override protected val onEvent: EventHandler = {
    case "stop" => engine.stop(this.id)  // Deadlock!
  }
}
```

**Why it fails:** The stop() method tries to acquire the dispatcher lock, but the worker thread (currently executing this handler) already holds it.

**Solution:**
```scala
class SelfStoppingProcessor extends BaseEventProcessor {
  private val shutdownExecutor = Executors.newSingleThreadExecutor()

  override protected val onEvent: EventHandler = {
    case "stop" =>
      shutdownExecutor.submit(new Runnable {
        def run(): Unit = engine.stop(SelfStoppingProcessor.this.id)
      })
  }
}
```

### Pitfall 3: Stopping During Burst Load

**Problem:**
```scala
// Producer adding messages
(1 to 1000000).foreach(msg => processor.post(msg))

// Concurrent stop call
engine.stop(processor.id, timeoutMs = 30000)
// Result: Timeout almost guaranteed
```

**Solution:**
```scala
// Stop message production first
producer.stop()
Thread.sleep(100)  // Let queue start draining

// Then stop processor
val queueSize = processor.currentQueueSize
val timeout = calculateTimeoutForQueueSize(queueSize)
engine.stop(processor.id, timeoutMs = timeout)
```

### Pitfall 4: Not Handling Shutdown Hooks Correctly

**Problem:**
```scala
Runtime.getRuntime.addShutdownHook(new Thread {
  override def run(): Unit = {
    engine.stop(processor.id, timeoutMs = 60000)  // 60 seconds
  }
})
// JVM allows max ~10 seconds for shutdown hooks
// Result: Hook killed before completion
```

**Solution:**
```scala
Runtime.getRuntime.addShutdownHook(new Thread {
  override def run(): Unit = {
    // Use shorter timeout appropriate for shutdown hook
    engine.stop(processor.id, timeoutMs = 5000)
    engine.shutdown()
  }
})
```

## Monitoring and Observability

### Metrics to Track

1. **Stop Duration:**
   ```scala
   val start = System.nanoTime()
   engine.stop(processor.id)
   val durationMs = (System.nanoTime() - start) / 1_000_000
   metrics.recordStopDuration(processor.id, durationMs)
   ```

2. **Messages Lost:**
   ```scala
   val beforeSize = processor.currentQueueSize
   engine.stop(processor.id, timeoutMs = 30000)
   // Check logs for CRITICAL errors, extract lost message count
   ```

3. **Timeout Rate:**
   ```scala
   // Track how often stops timeout
   val totalStops = stopCounter.get()
   val timeoutStops = timeoutCounter.get()
   val timeoutRate = timeoutStops.toDouble / totalStops
   // Alert if > 5%
   ```

### Log Monitoring

Watch for these log patterns:

**Warning (Normal):**
```
WARN: Draining 1523 messages from processor user-123 queue before stopping (timeout: 30000ms)
```

**Critical (Problem):**
```
CRITICAL: Timeout waiting for processor user-123 queue to drain. 847 messages remaining after 30000ms
CRITICAL: 847 messages will be lost from processor user-123
```

**Debug (Informational):**
```
DEBUG: Removed processor user-123 from dispatcher api
```

## Testing Graceful Shutdown

### Test 1: Empty Queue Stop

```scala
"stop" should "complete immediately with empty queue" in {
  val processor = new TestProcessor()

  val start = System.currentTimeMillis()
  engine.stop(processor.id)
  val duration = System.currentTimeMillis() - start

  duration should be < 100L  // Should be nearly instant
}
```

### Test 2: Queue Draining

```scala
"stop" should "drain queue before returning" in {
  val latch = new CountDownLatch(1000)
  val processor = new TestProcessor {
    override protected val onEvent: EventHandler = {
      case _ =>
        Thread.sleep(10)  // Slow processing
        latch.countDown()
    }
  }

  // Queue 1000 messages
  (1 to 1000).foreach(_ => processor.post("msg"))

  // Stop should wait for drain
  engine.stop(processor.id, timeoutMs = 30000)

  // All messages should be processed
  latch.getCount shouldBe 0
}
```

### Test 3: Timeout Behavior

```scala
"stop" should "timeout if queue doesn't drain" in {
  val processor = new TestProcessor {
    override protected val onEvent: EventHandler = {
      case _ => Thread.sleep(1000)  // Very slow
    }
  }

  // Queue messages
  (1 to 100).foreach(_ => processor.post("msg"))

  val start = System.currentTimeMillis()
  engine.stop(processor.id, timeoutMs = 1000)  // 1 second timeout
  val duration = System.currentTimeMillis() - start

  // Should timeout near 1 second
  duration should be >= 1000L
  duration should be < 1500L

  // Check logs for CRITICAL error
}
```

### Test 4: Concurrent Stop Calls

```scala
"stop" should "handle concurrent calls safely" in {
  val processor = new TestProcessor()

  val futures = (1 to 10).map { _ =>
    Future { engine.stop(processor.id) }
  }

  Await.result(Future.sequence(futures), 10.seconds)

  // Should complete without exceptions
  // Processor should be stopped
  engine.registrar.get(processor.id) shouldBe None
}
```

## Performance Considerations

### Stop Overhead

**Typical overhead per stop call:**
- Empty queue: < 1ms
- Draining queue: (queue_size × processing_time) + polling overhead (~10ms per poll)
- Lock acquisition: < 1ms (unless high contention)
- Registry cleanup: < 1ms

**Example calculation:**
```
Queue size: 1000 messages
Processing time: 20ms per message
Polling overhead: 10ms × (1000/10) polls = 1000ms
Total: ~21 seconds
```

### Scalability

**Concurrent stops scale linearly:**
- Stopping processors on different dispatchers: No contention
- Stopping processors on same dispatcher: Lock contention (serialized)

**Recommendations:**
- Batch stops by dispatcher to minimize lock acquisition
- Use thread pool for parallel stops across dispatchers
- Consider staggered stops to avoid thundering herd

## Related Documentation

- **[Architecture: Dispatcher Queue Contention](architecture/dispatcher-queue-contention.md)** - Implementation details
- **[Best Practices: Thread-Dispatcher Configuration](best-practices/thread-dispatcher-configuration.md)** - General configuration guide
- **[CHANGELOG.md](../CHANGELOG.md)** - Version history and breaking changes

## Summary

**Key Takeaways:**

✅ **stop() is blocking** - Don't call from hot paths
✅ **Queue draining is best-effort** - Use appropriate timeouts
✅ **Thread-safe** - Safe to call concurrently
✅ **Message loss logged as CRITICAL** - Monitor logs
✅ **No exceptions thrown** - Timeouts are logged, not thrown
✅ **Coordinated with workers** - Prevents race conditions

**Quick Reference:**

```scala
// Standard stop
engine.stop(processor.id)

// Custom timeout
engine.stop(processor.id, timeoutMs = 60000)

// Application shutdown
Runtime.getRuntime.addShutdownHook(new Thread {
  override def run(): Unit = {
    processors.foreach(id => engine.stop(id, timeoutMs = 10000))
    engine.shutdown()
  }
})
```

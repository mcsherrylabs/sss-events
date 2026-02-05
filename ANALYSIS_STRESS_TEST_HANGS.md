# Analysis: Stress Test Hangs

**Task**: 4.3 - Analyze Stress Test Hangs
**Focus**: ActorChurnStressSpec
**Date**: 2026-02-05

---

## Executive Summary

ActorChurnStressSpec hangs during execution, never completing even after 5+ minutes. The hang occurs during rapid processor creation/destruction cycles. Analysis reveals **two potential root causes**: a race condition in the stop() logic and a livelock scenario in the wait-retry loop.

---

## Test Behavior

### What the Test Does

ActorChurnStressSpec has 5 test cases that stress-test the system with rapid processor lifecycle operations:

1. **"handle continuous actor creation and destruction"** (lines 24-81)
   - 10 iterations × 10 actors × 5 messages = 500 messages
   - Creates processors, sends messages, sleeps 100ms, then destroys all
   - Uses CountDownLatch to wait for all 500 messages (30s timeout)

2. **"handle actor churn with mixed queue sizes"** (lines 83-128)
   - 5 iterations × 2 queue sizes × 10 messages = 100 messages
   - Tests with queue sizes: 1000, 10000
   - Brief 50ms sleep between create and destroy

3. **"handle queue overflow gracefully with small queues"** (lines 130-184)
   - 3 actors × 20 messages with queue size = 2
   - Tests backpressure and message rejection
   - Uses blocking latch to prevent initial processing

4. **"handle actor churn with multiple dispatchers"** (lines 186-233)
   - 5 iterations × 10 actors × 5 messages = 250 messages
   - Explicitly uses DispatcherName.Default
   - 50ms sleep between send and destroy

5. **"maintain stability under high churn (100 iterations)"** (lines 235-274)
   - 100 iterations × 5 actors × 3 messages = 1500 messages
   - Only 20ms sleep per iteration
   - 60s timeout for completion

### Observed Behavior

From Task 3.1 results:
- Test **hangs** after 5 minutes
- Debug logs show "continuous processor removal operations"
- No error messages, just silent hang
- CountDownLatches never count down to zero

---

## Root Cause Analysis

### Issue 1: Race Condition in stop() Logic

**Location**: `EventProcessingEngine.scala:196-279` (stop method)

**The Problem**: Worker threads can hold a processor reference indefinitely during processing, creating a deadlock scenario.

#### The Race Condition Flow

1. **Test calls** `engine.stop(processor.id)` (line 69 in test)
2. **stop() searches** for processor in dispatcher queues (line 222-224)
3. **Meanwhile**: Worker thread has **already polled** the processor (line 284-293)
4. **stop() finds nothing** in queue (dispatcherOpt = None, line 259)
5. **stop() tries all dispatchers** with locks (lines 264-271)
6. **Processor still not found** because worker thread is **actively processing it**
7. **stop() completes** and unregisters the processor (line 275)
8. **Worker thread finishes** processing and tries to return processor to queue (line 312)
9. **Processor successfully offered** back to queue (line 312) - **BUT IT'S NOW UNREGISTERED**
10. **Processor sits in queue forever**, unregistered and abandoned

#### The Wait-Retry Logic Issue

When stop() doesn't find the processor initially, it has a wait-retry loop (lines 242-253):

```scala
val waitStartTime = System.currentTimeMillis()
val maxWaitMs = 5000L // 5 second timeout
var found = false

while (!found && (System.currentTimeMillis() - waitStartTime) < maxWaitMs) {
  Thread.sleep(10)
  found = dispatcher.queue.removeIf(_.id == id)
}
```

**Problem**: This loop only executes if the processor was found initially but removed between find() and lock acquisition. If the processor was **never in the queue** (because a worker already polled it), this code path isn't reached, and the processor is unregistered immediately.

#### Why This Causes Hangs

In high-churn scenarios (like ActorChurnStressSpec):
- Many processors are stopped rapidly
- Worker threads are actively processing
- High probability that processors are polled before stop() can remove them
- Processors get unregistered while still in flight
- When returned to queue, they're "ghost processors" - in the queue but not registered
- Future iterations create new processors with new IDs
- CountDownLatch never completes because some messages were handled by ghost processors that were prematurely unregistered

### Issue 2: Potential Livelock in High Churn

**The Scenario**:
1. Test creates 10 processors
2. Test sends 50 messages (5 per processor)
3. Test sleeps only 100ms (line 66)
4. Test immediately destroys all 10 processors (line 69)
5. Worker threads are still processing messages
6. Some processors have messages in their internal queues (currentQueueSize > 0)

**The Queue Drain Wait** (lines 202-219):
```scala
if (initialQueueSize > 0) {
  val startTime = System.currentTimeMillis()
  var currentSize = initialQueueSize

  while (currentSize > 0 && (System.currentTimeMillis() - startTime) < timeoutMs) {
    Thread.sleep(10)
    currentSize = processor.currentQueueSize
  }
}
```

**The Problem**:
- stop() waits for processor's internal queue to drain
- Processor needs to be polled by worker thread to process messages
- But stop() is trying to remove processor from dispatcher queue
- If processor is being actively processed, new messages might be posted
- Queue might never fully drain if messages keep arriving
- In a 100-iteration stress test with only 20ms sleep, messages are flying everywhere

### Issue 3: Missing Coordination Signal

**Observation**: The stop() method has no way to signal to a processor "stop accepting new messages".

**Current Flow**:
1. stop() waits for queue to drain
2. stop() removes processor from dispatcher
3. stop() unregisters processor

**Missing Signal**: There's no mechanism to tell the processor "you're shutting down, reject new messages". A processor can still receive messages via:
- Direct `processor ! message` calls (if test still has reference)
- Subscription messages (if subscribed to channels)
- Child-to-parent messages

**Result**: In high-churn tests, messages can arrive faster than they're drained, creating an infinite loop in the drain-wait logic.

---

## Supporting Evidence

### Evidence from Code Structure

1. **Worker Thread Returns Processor Unconditionally** (line 312-314):
   ```scala
   finally {
     if (!dispatcher.queue.offer(am)) {
       log.error(s"Failed to return processor ${am.id} to dispatcher queue!")
     }
   }
   ```
   - No check if processor is still registered
   - No check if processor is being stopped
   - Always returns to queue

2. **stop() Doesn't Signal Processors** (lines 196-279):
   - No flag on processor indicating "stopping"
   - No way to prevent new messages from being posted
   - Just waits and hopes queue drains

3. **Unregister Happens Too Early** (line 275):
   ```scala
   registrar.unRegister(id)
   ```
   - Happens even if processor wasn't found in queue
   - Happens even if worker might still be processing it
   - No verification that processor is truly idle

### Evidence from Test Behavior

From FAILURE_SUMMARY.md and Task 3.1:
- **"continuous processor removal operations"** in logs
- This suggests the loop at line 37 continues: `(1 to iterations).foreach { iteration =>`
- Tests never complete even with generous timeouts (30s, 60s)
- No error messages - everything looks "normal"
- Suggests silent deadlock or livelock, not assertion failures

### Evidence from Similar Tests

**TwoDispatcherSpec** (previously failing, now passing):
- Took 2 minutes 47 seconds to pass
- Tests blocking one dispatcher while using another
- The fix that made this pass might have improved coordination but not fully solved the race

**GracefulStopSpec** and **StopRaceConditionSpec**:
- Both timeout with similar symptoms
- Both test stop() functionality
- Strong correlation: stop() logic is the common factor

---

## Deadlock vs Livelock Analysis

### Deadlock Indicators
- ❌ No circular lock dependencies observed
- ❌ No threads waiting on each other's locks
- ✅ Some threads might be waiting forever on condition variables

### Livelock Indicators
- ✅ Continuous activity (processor removal operations)
- ✅ No progress toward completion
- ✅ Threads doing work but not advancing state
- ✅ Fits the pattern: stop() waits → messages arrive → queue never empties → stop() waits

**Conclusion**: More likely a **livelock** (busy waiting without progress) than a pure deadlock.

---

## Hypothesis Summary

### Primary Hypothesis: Race Condition in Processor Removal

**Root Cause**: Worker threads can hold processor references while stop() tries to remove them, leading to premature unregistration and "ghost processors" in the queue.

**Why It Causes Hangs**:
1. Some processors get unregistered while in flight
2. They're returned to queue but no longer in registrar
3. Their messages are never counted toward test's CountDownLatch
4. Test waits forever for messages that will never decrement the latch

**Confidence**: HIGH - This explains the continuous activity without progress.

### Secondary Hypothesis: Queue Drain Livelock

**Root Cause**: In high-churn scenarios, messages arrive faster than queue drains, preventing stop() from ever completing the drain phase.

**Why It Causes Hangs**:
1. stop() enters drain-wait loop (line 209)
2. New messages keep arriving (from subscriptions, other processors, etc.)
3. Queue never reaches zero
4. Eventually hits 30s timeout (line 73 or 267)
5. But test's overall timeout is 60s (line 267)
6. Creates cascading timeout scenario

**Confidence**: MEDIUM - Plausible but harder to trigger consistently.

---

## Related Failures

These other failures likely share the same root cause:

1. **GracefulStopSpec** - Directly tests stop() logic
2. **StopRaceConditionSpec** - Tests concurrent stop() operations
3. **HighConcurrencySpec** - Hangs after first test, likely cleanup issue
4. **HandlerStackThreadSafetySpec** - Shows "Unhandled -> BecomeMessage" warnings, suggesting delivery to unregistered processors

---

## Recommended Fixes

### Fix 1: Add Processor Stopping State

Add a flag to processors indicating they're being stopped:

```scala
// In BaseEventProcessor
@volatile private var isStopping: Boolean = false

def markStopping(): Unit = {
  isStopping = true
}

def isBeingStopped: Boolean = isStopping
```

Modify stop() to check this flag before returning processor to queue.

### Fix 2: Improve Worker Thread Coordination

Modify processTask to check if processor is still registered:

```scala
finally {
  // Only return to queue if still registered
  if (registrar.get(am.id).isDefined) {
    if (!dispatcher.queue.offer(am)) {
      log.error(s"Failed to return processor ${am.id} to dispatcher queue!")
    }
  } else {
    log.debug(s"Processor ${am.id} was unregistered, not returning to queue")
  }
}
```

### Fix 3: Wait for In-Flight Processing

Add a mechanism to wait for worker threads to finish with a processor:

```scala
// After finding processor (line 198)
processor.markStopping()  // Signal no new messages should be posted

// Before unregistering (line 274)
// Wait for processor to not be in-flight
val inFlightWaitStart = System.currentTimeMillis()
while (processorIsInFlight(processor.id) &&
       (System.currentTimeMillis() - inFlightWaitStart) < 5000) {
  Thread.sleep(10)
}
```

### Fix 4: Drain Queue with Stop Flag

Modify the drain-wait loop to stop waiting if timeout is imminent:

```scala
while (currentSize > 0 && (System.currentTimeMillis() - startTime) < timeoutMs) {
  // Check if we're making progress
  val previousSize = currentSize
  Thread.sleep(100) // Longer sleep for efficiency
  currentSize = processor.currentQueueSize

  // If no progress after 1 second, warn and break
  if (currentSize == previousSize && (System.currentTimeMillis() - startTime) > 1000) {
    log.warn(s"Queue not draining for processor ${id}, forcing stop")
    break
  }
}
```

---

## Testing Strategy

To validate the hypothesis:

1. **Add Debug Logging**: Instrument stop() and processTask to log every state transition
2. **Add Assertions**: Check registrar state before/after operations
3. **Reduce Iterations**: Run ActorChurnStressSpec with 2 iterations instead of 100
4. **Increase Sleep**: Change 20ms sleep to 1000ms to reduce churn
5. **Monitor Queue States**: Log dispatcher queue sizes during test execution

---

## Conclusion

The ActorChurnStressSpec hang is most likely caused by a **race condition** where processors are unregistered while still in flight, leading to ghost processors that never contribute to test completion. The secondary issue is a **livelock** where queue drain waits indefinitely because messages arrive faster than they're processed.

**Severity**: HIGH - Affects core shutdown functionality, not just stress tests.

**Scope**: Impacts all rapid processor lifecycle scenarios, including production applications with dynamic processor creation/destruction.

**Next Steps**: Implement Fix 2 (improve worker thread coordination) first as it's the most straightforward and addresses the primary race condition. Then proceed to Fix 1 (stopping state) for more comprehensive protection.

# Common Issues Analysis - Task 4.4

## Executive Summary

This document analyzes systemic issues in the event processing engine that contribute to test failures, particularly in high-concurrency scenarios and graceful shutdown sequences. Four critical categories of issues have been identified:

1. **Race Conditions in stop() Logic** - CRITICAL
2. **Condition Variable Usage Issues** - MEDIUM
3. **Dispatcher Lock Ordering Issues** - HIGH
4. **Missing Signals or Waits** - HIGH

---

## 1. Race Conditions in stop() Logic

### Issue 1.1: Processor Return Race in stop()

**Location**: `EventProcessingEngine.scala:197-279` (stop method)

**Description**: When `stop()` is called, a race condition exists between:
- Worker threads returning processors to the queue (line 312-314)
- stop() trying to remove processors from the queue (line 234, 248, 267)

**Critical Code Path**:
```scala
// Worker thread (processTask, line 311-315)
finally {
  if (!dispatcher.queue.offer(am)) {
    log.error(s"Failed to return processor ${am.id} to dispatcher queue!")
  }
}

// stop() method (line 234-253)
val removed = dispatcher.queue.removeIf(_.id == id)
if (removed) {
  log.debug(s"Removed processor ${id} from dispatcher ${dispatcher.name}")
} else {
  // Wait and retry logic
  while (!found && (System.currentTimeMillis() - waitStartTime) < maxWaitMs) {
    Thread.sleep(10)
    found = dispatcher.queue.removeIf(_.id == id)
  }
}
```

**Problem**: Worker threads return processors unconditionally without checking if:
1. The processor is still registered in the registrar
2. stop() is trying to remove this processor
3. The processor should be returned to the queue

**Impact**:
- stop() may timeout waiting for a processor that will never return (5s timeout at line 243)
- Processors can be returned to queue after unregistration (line 275), creating "ghost processors"
- Worker threads can continue processing unregistered processors

**Evidence from Logs**:
- GracefulStopSpec: Continuous "Draining" messages with no progress
- StopRaceConditionSpec: Timeouts in concurrent stop scenarios
- ActorChurnStressSpec: Continuous processor removal operations with hangs

### Issue 1.2: Unregister Before Queue Removal Complete

**Location**: `EventProcessingEngine.scala:275`

**Description**: The processor is unregistered from the registrar AFTER attempting to remove it from dispatcher queues, but this happens even if the removal times out or fails.

**Critical Code**:
```scala
// Line 251-253: Timeout case
if (!found) {
  log.warn(s"Timeout waiting for processor ${id} to be returned to queue after ${maxWaitMs}ms")
}
// ...
// Line 275: Unregister happens regardless
registrar.unRegister(id)
```

**Problem**: If a worker thread is actively processing the processor:
1. stop() waits up to 5 seconds (line 243)
2. Times out if processor not returned
3. Unregisters anyway (line 275)
4. Worker thread eventually returns processor to queue (line 312)
5. Queue now contains an unregistered processor

**Impact**:
- "Ghost processors" in dispatcher queues
- Future worker threads may poll unregistered processors
- Messages sent to unregistered processors are lost
- Livelock: continuous polling of ghost processors

### Issue 1.3: Lock-Free Search in stop()

**Location**: `EventProcessingEngine.scala:222-224`

**Description**: stop() searches for the processor in dispatcher queues without holding locks.

**Critical Code**:
```scala
// Line 222-224: Search without lock
val dispatcherOpt = dispatchers.values.find(d => {
  d.queue.asScala.exists(_.id == id)
})
```

**Problem**: Time-of-check-time-of-use (TOCTOU) race:
1. stop() finds processor in queue (line 222-224)
2. Worker thread polls processor between find() and lock acquisition
3. stop() acquires lock (line 231)
4. Processor is no longer in queue, enters wait/retry logic (line 240-249)

**Impact**:
- Unnecessary wait/retry loops
- Increased latency in stop operations
- Potential for timeout if worker processing time exceeds 5s

---

## 2. Condition Variable Usage Issues

### Issue 2.1: Signal Without Work Verification

**Location**: `EventProcessingEngine.scala:111-116`

**Description**: The code signals `workAvailable` condition variable after registering a processor, but doesn't verify the queue operation succeeded.

**Critical Code**:
```scala
// Line 107-116
if (!dispatcher.queue.offer(am)) {
  log.error(s"Failed to add processor ${am.id} to dispatcher queue!")
} else {
  // Signal waiting threads that work is available
  dispatcher.lock.lock()
  try {
    dispatcher.workAvailable.signal()
  } finally {
    dispatcher.lock.unlock()
  }
}
```

**Problem**: Signal is sent only on success, but:
1. If offer() fails, no signal is sent (correct)
2. But if offer() succeeds between the check and signal, race possible
3. Multiple signals can be sent for the same processor (if re-registered)

**Impact**:
- Minor: Unlikely to cause failures, but inefficient
- Could contribute to spurious wakeups

### Issue 2.2: Condition Variable Await Pattern

**Location**: `EventProcessingEngine.scala:285-294`

**Description**: Worker threads use condition variable await with a very short timeout (100 microseconds).

**Critical Code**:
```scala
// Line 285-294
var am = dispatcher.queue.poll()
while (am == null && keepGoing.get()) {
  dispatcher.lock.lock()
  try {
    dispatcher.workAvailable.await(100, TimeUnit.MICROSECONDS)
  } finally {
    dispatcher.lock.unlock()
  }
  am = dispatcher.queue.poll()
}
```

**Problem**:
1. 100 microsecond timeout is extremely short
2. If signal arrives during the 100μs, may be missed
3. Causes tight spin loop if no work available
4. No check for spurious wakeups (though poll() handles this)

**Impact**:
- Low: The pattern is functionally correct
- Performance: Tight loop may waste CPU when idle
- The short timeout means frequent re-polling, reducing latency but increasing CPU usage

---

## 3. Dispatcher Lock Ordering Issues

### Issue 3.1: Lock Acquisition Order Not Defined

**Location**: Multiple locations in `EventProcessingEngine.scala`

**Description**: There is no defined lock ordering protocol when multiple locks need to be acquired.

**Lock Types**:
1. **Engine-level lock** (`private val lock = new Object()`) - line 63
2. **Dispatcher locks** (`dispatcher.lock: ReentrantLock`) - per-dispatcher
3. **Processor task lock** (`am.taskLock`) - per-processor (line 301)

**Critical Sequences**:

**Sequence A - register()** (line 94-119):
```scala
lock.synchronized {  // Engine lock
  registrar.register(am)
  // ...
  dispatcher.lock.lock()  // Dispatcher lock
  try {
    dispatcher.workAvailable.signal()
  } finally {
    dispatcher.lock.unlock()
  }
}
```

**Sequence B - stop()** (line 197-279):
```scala
// NO engine lock held!
registrar.get(id)  // Registrar access (uses TrieMap, thread-safe)
// ...
dispatcher.lock.lock()  // Dispatcher lock
try {
  dispatcher.queue.removeIf(_.id == id)
} finally {
  dispatcher.lock.unlock()
}
registrar.unRegister(id)  // Registrar access
```

**Sequence C - processTask()** (line 282-316):
```scala
// Called from worker thread within dispatcher lock
am.taskLock.synchronized {  // Task lock
  am.processEvent(task)
}
```

**Problem**: Inconsistent lock ordering:
1. register() holds engine lock → dispatcher lock
2. stop() holds only dispatcher locks (no engine lock)
3. Worker threads hold dispatcher lock → task lock
4. No defined order when multiple dispatcher locks needed (line 264-271)

**Potential Deadlock Scenario**:
- Thread A: register() holds engine lock, waiting for dispatcher lock
- Thread B: stop() holds dispatcher lock, trying to access registrar
- If registrar ever needed engine lock, deadlock would occur
- Currently safe because registrar uses TrieMap (lock-free)

**Impact**:
- Current: LOW (no deadlock observed, registrar is lock-free)
- Future: HIGH risk if registrar implementation changes
- Code maintainability: POOR (implicit assumptions about lock-free registrar)

### Issue 3.2: Multiple Dispatcher Lock Acquisition in stop()

**Location**: `EventProcessingEngine.scala:264-271`

**Description**: When processor not found, stop() iterates through all dispatchers acquiring locks.

**Critical Code**:
```scala
// Line 264-271
dispatchers.values.foreach { dispatcher =>
  dispatcher.lock.lock()
  try {
    dispatcher.queue.removeIf(_.id == id)
  } finally {
    dispatcher.lock.unlock()
  }
}
```

**Problem**:
1. Locks acquired in arbitrary order (HashMap iteration order)
2. If multiple threads call stop() for different processors, potential for:
   - Thread A acquires dispatcher1 lock, waiting for dispatcher2
   - Thread B acquires dispatcher2 lock, waiting for dispatcher1
   - DEADLOCK

**Current Mitigation**:
- stop() is called infrequently in tests
- Most tests stop single processor at a time
- Real deadlock probability is low but non-zero

**Impact**:
- Observed: Tests that stop many processors concurrently (ActorChurnStressSpec) hang
- Root cause: Likely combination of this + Issue 1.2 (ghost processors)

---

## 4. Missing Signals or Waits

### Issue 4.1: No Signal for Processor Stop

**Location**: `EventProcessingEngine.scala:197-279` (stop method)

**Description**: When stop() removes a processor, it doesn't signal worker threads that might be waiting for work.

**Problem**: Consider this scenario:
1. All processors in queue are busy/processing
2. Worker thread calls processTask (line 282)
3. poll() returns null (line 284)
4. Worker enters condition variable wait (line 289)
5. Meanwhile, stop() removes a processor from the queue
6. No signal sent to wake waiting workers
7. Worker may wait full 100μs timeout unnecessarily

**Impact**:
- Very Low: 100μs timeout means minimal delay
- If timeout were longer, could cause significant latency

### Issue 4.2: No Wait for In-Flight Processing in stop()

**Location**: `EventProcessingEngine.scala:237-253`

**Description**: stop() waits for processor to be returned to queue (line 246-249), but uses polling instead of condition variable.

**Critical Code**:
```scala
// Line 246-249: Polling loop
while (!found && (System.currentTimeMillis() - waitStartTime) < maxWaitMs) {
  Thread.sleep(10)
  found = dispatcher.queue.removeIf(_.id == id)
}
```

**Problem**:
1. Uses Thread.sleep(10) in a polling loop
2. No condition variable wait for "processor returned" event
3. Worker thread returns processor (line 312) but doesn't signal
4. stop() may wait up to 10ms extra after processor already returned

**Impact**:
- Performance: Unnecessary latency (up to 10ms per iteration)
- Observed in logs: Multiple polling iterations in stop operations

### Issue 4.3: shutdown() Doesn't Wait for In-Flight Work

**Location**: `EventProcessingEngine.scala:375-383`

**Description**: shutdown() sets keepGoing to false and joins threads, but doesn't ensure queues are drained.

**Critical Code**:
```scala
// Line 375-383
def shutdown(): Unit = {
  keepGoing.set(false)
  lock.synchronized {
    threads.foreach(t => {
      LockSupport.unpark(t)
      t.join()
    })
  }
}
```

**Problem**:
1. Sets keepGoing to false (line 376)
2. Worker threads will exit processTask when am == null (line 296)
3. But doesn't wait for current processTask to complete
4. Doesn't drain remaining messages from processor queues
5. Could lose messages in flight

**Impact**:
- Message loss: Any messages in processor queues at shutdown
- Observed: Tests use stop() before shutdown(), so individual processor queues drained
- But if shutdown() called directly, messages could be lost

---

## 5. Root Cause Analysis Summary

### Primary Root Cause: Issue 1.2
The most critical issue is **"Unregister Before Queue Removal Complete"** (Issue 1.2):

1. Worker thread processing takes >5 seconds
2. stop() times out waiting for processor return
3. stop() unregisters processor anyway
4. Worker eventually returns unregistered processor to queue
5. Queue contains ghost processor
6. Other workers poll ghost processor → livelock

**Evidence**:
- All hanging tests show continuous queue activity with no progress
- ActorChurnStressSpec logs show processor removal operations repeating
- GracefulStopSpec shows message draining that never completes

### Secondary Root Causes

**Issue 1.1** (Processor Return Race): Exacerbates Issue 1.2 by making processor return timing unpredictable.

**Issue 3.2** (Multiple Dispatcher Lock Acquisition): Could cause deadlock in concurrent stop() scenarios (ActorChurnStressSpec).

**Issue 4.2** (Polling in stop()): Adds latency and reduces ability to coordinate with worker threads.

### Contributing Factors

- **Issue 2.2**: Short condition variable timeout contributes to CPU usage but not functional issues
- **Issue 3.1**: Lock ordering not defined, future maintenance risk
- **Issue 4.3**: shutdown() doesn't drain queues, but masked by individual stop() calls in tests

---

## 6. Recommendations

### Critical Fixes (Required)

1. **Fix Issue 1.2**: Add processor stopping state
   - Add `AtomicBoolean stopping` field to processors
   - Check stopping flag in worker thread before returning to queue
   - Set stopping flag before unregister

2. **Fix Issue 1.1**: Improve worker thread coordination
   - Check registrar.get(am.id) before returning processor
   - If unregistered, don't return to queue

3. **Fix Issue 3.2**: Define lock ordering for multiple dispatcher locks
   - Sort dispatchers by name before acquiring locks
   - Or use tryLock with backoff

### High Priority Fixes (Recommended)

4. **Fix Issue 4.2**: Use condition variable for processor return
   - Add "processor returned" condition variable
   - Signal when processor returned to queue
   - Wait on condition variable in stop() instead of polling

5. **Fix Issue 3.1**: Document lock ordering protocol
   - Add comments documenting lock hierarchy
   - Consider extracting lock management to separate class

### Medium Priority Fixes (Optional)

6. **Fix Issue 2.2**: Increase condition variable timeout
   - Change from 100μs to 1-10ms to reduce CPU usage when idle
   - Profile to find optimal value

7. **Fix Issue 4.3**: Improve shutdown() to drain queues
   - Wait for all processor queues to drain
   - Set timeout for drain operation

---

## 7. Test Failure Mapping

### Tests Affected by Issue 1.2 (Primary)
- **GracefulStopSpec** (timeout/hang) - stop() times out, creates ghost processors
- **StopRaceConditionSpec** (timeout/hang) - concurrent stop() with active processing
- **ActorChurnStressSpec** (hang) - rapid create/destroy with ghost processor accumulation
- **HandlerStackThreadSafetySpec** (timeout) - high concurrency with long processing times

### Tests Affected by Issue 3.2 (Secondary)
- **ActorChurnStressSpec** (hang) - concurrent stop() on multiple processors
- **HighConcurrencySpec** (timeout) - concurrent stop() under load

### Tests Affected by Issue 1.1 + 1.2 (Combined)
- **ThreadPinningThreadSafetySpec** (partial failure) - 16-thread test times out
- **FairnessValidationSpec** (partial failure) - high contention tests timeout

---

## 8. Verification Strategy

### For Each Fix

1. **Unit Test**: Create isolated test reproducing the specific issue
2. **Integration Test**: Re-run affected test suite
3. **Stress Test**: Run with increased iterations and reduced sleep times
4. **Full Suite**: Run complete test suite to check for regressions

### Success Criteria

- All hanging tests complete within expected time
- All timeout tests pass consistently
- No new failures introduced
- Performance metrics (throughput, latency) maintained or improved

---

## 9. Additional Observations

### Positive Patterns

1. **Lock-Free Registrar**: Using TrieMap prevents many potential deadlocks
2. **Condition Variables**: Correct pattern for work signaling
3. **Non-Fair Locks**: Good choice for throughput optimization
4. **Exponential Backoff**: Proper implementation for lock contention

### Areas for Improvement

1. **Error Handling**: Some error conditions log warnings but continue
2. **Metrics**: No metrics for queue sizes, wait times, timeouts
3. **Diagnostics**: Limited visibility into lock contention, wait times
4. **Documentation**: Lock ordering and threading model not documented

---

## Conclusion

The analysis identifies **Issue 1.2 (Unregister Before Queue Removal Complete)** as the primary root cause of test failures. This issue, combined with **Issue 1.1 (Processor Return Race)**, creates "ghost processors" in dispatcher queues that cause livelocks in high-concurrency scenarios.

**Issue 3.2 (Multiple Dispatcher Lock Acquisition)** is a secondary root cause that can cause deadlocks when multiple stop() operations occur concurrently.

Fixing these three issues should resolve the majority of test failures. The remaining issues are lower priority but should be addressed for code quality, maintainability, and performance optimization.

**Next Steps**: Proceed to Phase 5 (Fix Failures) and implement the Critical Fixes (#1-3) first.

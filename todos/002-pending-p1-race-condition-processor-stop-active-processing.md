---
status: pending
priority: p1
issue_id: "002"
tags: [code-review, thread-safety, critical, race-condition]
dependencies: ["001"]
---

# Race Condition in Stop - Processor May Be Processing During Removal

## Problem Statement

**CRITICAL RACE CONDITION**: A worker thread may be actively processing a message when `stop()` is called. The processor is removed from the dispatcher queue, but the thread still has a reference and will attempt to return it after processing. This causes the processor to be permanently lost.

**Why This Matters**: Silent failures where processors disappear from the system without warning. Messages continue to queue up in the lost processor but are never processed, leading to memory leaks and stuck operations.

## Findings

**Location**: `/home/alan/develop/sss-events/src/main/scala/sss/events/EventProcessingEngine.scala:175-178, 204-206`

**Race Condition Timeline**:
```
T1 (Worker Thread):              T2 (Stop Call):
1. poll() gets processor P
                                 2. removeIf(P.id == "P")
3. Process message
                                 3. unRegister("P")
4. Try to offer(P) back          [Returns false - queue rejects]
5. Log error but processor lost
```

**Evidence**: Line 204-206 has error handling that suggests this scenario occurs:
```scala
if (!dispatcher.queue.offer(am)) {
  log.error(s"Failed to return processor ${am.id} to dispatcher queue!")
}
```

This error handling exists because the developers anticipated this race condition, but only log it - they don't recover from it.

**Agent Source**: data-integrity-guardian review agent

## Proposed Solutions

### Solution 1: Add Stopping Flag with Coordination
**Pros**:
- Prevents race condition at source
- Clean coordination between stop and processing
- No complex locking needed

**Cons**:
- Requires adding state to BaseEventProcessor
- Need to ensure stopping flag is volatile
- Slight overhead checking flag

**Effort**: Medium (4-6 hours)
**Risk**: Low

**Implementation**:
```scala
// In BaseEventProcessor:
@volatile private[events] var isStopping: Boolean = false

// In EventProcessingEngine.stop():
def stop(id: EventProcessorId, timeout: Duration = 30.seconds): Unit = {
  // Phase 1: Mark processor as stopping (outside lock)
  registrar.get(id).foreach { processor =>
    processor.isStopping = true
    log.info(s"Marked processor $id as stopping")
  }

  // Phase 2: Wait for processor to not be actively processing
  val startTime = System.nanoTime()
  var activelyProcessing = true
  while (activelyProcessing) {
    lock.synchronized {
      // Check if processor is in any dispatcher queue
      val inQueue = dispatchers.values.exists(_.queue.contains(processor))
      activelyProcessing = inQueue
    }

    if (activelyProcessing) {
      val elapsed = Duration.fromNanos(System.nanoTime() - startTime)
      if (elapsed > timeout) {
        log.error(s"Timeout waiting for processor $id to finish - forcing stop")
        break
      }
      Thread.sleep(1)
    }
  }

  // Phase 3: Safe to remove now (inside lock)
  lock.synchronized {
    dispatchers.values.foreach(d => d.queue.removeIf(_.id == id))
    registrar.unRegister(id)
  }
}

// In processTask (line 193-210):
if (am.isStopping) {
  // Don't return to queue, it's being stopped
  log.info(s"Processor ${am.id} stopped during processing - not returning to queue")
} else {
  if (!dispatcher.queue.offer(am)) {
    log.error(s"Failed to return processor ${am.id} to dispatcher queue!")
    // Now this should never happen
  }
}
```

### Solution 2: Two-Phase Commit Stop
**Pros**:
- Formal transaction semantics
- Clear rollback strategy
- Atomic removal guarantee

**Cons**:
- More complex implementation
- Higher overhead
- Requires distributed coordination pattern

**Effort**: Large (2-3 days)
**Risk**: Medium

**Implementation**:
```scala
sealed trait StopPhase
case object PrepareStop extends StopPhase
case object CommitStop extends StopPhase
case object RollbackStop extends StopPhase

def stop(id: EventProcessorId): Unit = {
  // Phase 1: Prepare
  val processor = registrar.get(id).getOrElse {
    log.warn(s"Processor $id not found")
    return
  }

  processor ! PrepareStop

  // Wait for acknowledgment
  val ack = waitForAck(processor, timeout = 5.seconds)

  if (ack) {
    // Phase 2: Commit
    lock.synchronized {
      dispatchers.values.foreach(d => d.queue.removeIf(_.id == id))
      registrar.unRegister(id)
    }
    processor ! CommitStop
  } else {
    // Phase 2: Rollback
    processor ! RollbackStop
    log.error(s"Failed to stop processor $id - rolled back")
  }
}
```

### Solution 3: Reference Counting
**Pros**:
- Precise tracking of processor usage
- Natural coordination mechanism
- No polling needed

**Cons**:
- Must track references carefully
- Risk of reference leaks
- More invasive change

**Effort**: Large (3-4 days)
**Risk**: High (easy to get wrong)

## Recommended Action

**Implement Solution 1** - Stopping flag with coordination. It's the right balance of correctness, simplicity, and performance.

## Technical Details

**Affected Files**:
- `src/main/scala/sss/events/EventProcessingEngine.scala` (stop, processTask methods)
- `src/main/scala/sss/events/EventProcessor.scala` (add isStopping flag)

**Components**:
- EventProcessingEngine.stop()
- EventProcessingEngine.processTask()
- BaseEventProcessor (new isStopping field)

**Database/State Changes**: None

## Acceptance Criteria

- [ ] stop() method waits for active processing to complete
- [ ] isStopping flag added to BaseEventProcessor
- [ ] processTask checks isStopping before returning processor to queue
- [ ] No "Failed to return processor to queue" errors during normal stop
- [ ] Timeout mechanism prevents indefinite waiting
- [ ] All stress tests pass
- [ ] Add specific test for stop-during-processing scenario

## Work Log

### 2026-02-02 - Initial Assessment
- **Discovered**: data-integrity-guardian agent identified race condition during code review
- **Evidence**: Error handling code at line 204-206 shows developers knew about this issue
- **Root Cause**: Lack of coordination between stop() and active message processing
- **Dependencies**: Should be fixed together with #001 (message draining)

## Resources

- **Related Issue**: #001 (message loss during stop)
- **Related Code**: Line 204-206 error handling (evidence this happens)
- **Similar Patterns**: Linux kernel reference counting, POSIX thread cancellation
- **Documentation**: Add graceful shutdown documentation to README

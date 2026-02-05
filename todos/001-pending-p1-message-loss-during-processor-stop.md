---
status: pending
priority: p1
issue_id: "001"
tags: [code-review, data-integrity, critical, message-loss]
dependencies: []
---

# Message Loss During Processor Stop - No Drain Guarantee

## Problem Statement

**CRITICAL DATA LOSS**: The `stop()` method in EventProcessingEngine immediately removes a processor from the dispatcher queue and unregisters it without draining pending messages in the processor's internal queue. This causes guaranteed data loss in production during processor lifecycle management.

**Why This Matters**: Any messages queued in the processor's LinkedBlockingQueue (which can hold up to 100,000 messages) are permanently lost when the processor is stopped. This violates basic data integrity guarantees and makes graceful shutdown impossible.

## Findings

**Location**: `/home/alan/develop/sss-events/src/main/scala/sss/events/EventProcessingEngine.scala:175-178`

**Current Code**:
```scala
def stop(id: EventProcessorId): Unit = lock.synchronized {
  dispatchers.values.foreach(d => d.queue.removeIf(_.id == id))
  registrar.unRegister(id)
}
```

**Data Loss Scenario**:
1. Processor has 1000 messages in its LinkedBlockingQueue
2. User calls `engine.stop(processorId)`
3. Processor is immediately removed from dispatcher queue
4. All 1000 unprocessed messages are **permanently lost**
5. No error or warning is issued

**Evidence from Tests**: ActorChurnStressSpec tests fail with message loss:
- "should handle actor churn with mixed queue sizes" - FAILED
- "should handle queue overflow gracefully" - FAILED
- "should maintain stability under high churn (100 iterations)" - FAILED

**Agent Source**: data-integrity-guardian review agent

## Proposed Solutions

### Solution 1: Block Until Queue Drains (Graceful Shutdown)
**Pros**:
- Guarantees zero message loss
- Simple implementation
- Predictable behavior

**Cons**:
- May block indefinitely if processor is stuck
- Requires timeout mechanism
- Not suitable for forced shutdown

**Effort**: Medium (4-6 hours)
**Risk**: Low

**Implementation**:
```scala
def stop(id: EventProcessorId, timeout: Duration = 30.seconds): Unit = lock.synchronized {
  registrar.get(id).foreach { processor =>
    val startTime = System.nanoTime()
    val remainingMessages = processor.q.size()

    if (remainingMessages > 0) {
      log.warn(s"Stopping processor $id with $remainingMessages unprocessed messages - draining...")

      while (processor.q.size() > 0 && keepGoing.get()) {
        val elapsed = Duration.fromNanos(System.nanoTime() - startTime)
        if (elapsed > timeout) {
          val lost = processor.q.size()
          log.error(s"CRITICAL: Timeout draining processor $id - losing $lost messages")
          break
        }
        Thread.sleep(10)
      }

      val finalRemaining = processor.q.size()
      if (finalRemaining > 0) {
        log.error(s"CRITICAL: Lost $finalRemaining messages for processor $id during shutdown")
      }
    }
  }

  dispatchers.values.foreach(d => d.queue.removeIf(_.id == id))
  registrar.unRegister(id)
}
```

### Solution 2: Dead Letter Queue (Industry Standard)
**Pros**:
- No blocking - immediate stop
- Messages recoverable later
- Better observability
- Follows Akka/actor system patterns

**Cons**:
- More complex implementation
- Requires DLQ infrastructure
- Need to decide DLQ persistence strategy

**Effort**: Large (2-3 days)
**Risk**: Medium (new infrastructure)

**Implementation**:
```scala
case class DeadLetterQueue(maxSize: Int = 10000) {
  private val queue = new ConcurrentLinkedQueue[(EventProcessorId, Any)]()

  def add(processorId: EventProcessorId, message: Any): Boolean = {
    if (queue.size() < maxSize) {
      queue.offer((processorId, message))
    } else {
      log.error(s"DLQ overflow - dropping message for $processorId")
      false
    }
  }

  def drain(processorId: EventProcessorId): List[Any] = {
    queue.asScala.filter(_._1 == processorId).map(_._2).toList
  }
}

def stop(id: EventProcessorId): Unit = lock.synchronized {
  registrar.get(id).foreach { processor =>
    // Drain remaining messages to DLQ
    var msg = processor.q.poll()
    while (msg != null) {
      deadLetterQueue.add(id, msg)
      msg = processor.q.poll()
    }
  }

  dispatchers.values.foreach(d => d.queue.removeIf(_.id == id))
  registrar.unRegister(id)
}
```

### Solution 3: Minimum - Add Logging and Metrics
**Pros**:
- Quick fix (30 minutes)
- Better visibility
- Helps detect problem in production

**Cons**:
- Does not solve data loss
- Only makes problem observable

**Effort**: Small (30 minutes)
**Risk**: None

**Implementation**:
```scala
def stop(id: EventProcessorId): Unit = lock.synchronized {
  registrar.get(id).foreach { processor =>
    val remainingMessages = processor.q.size()
    if (remainingMessages > 0) {
      log.error(s"CRITICAL: Stopping processor $id with $remainingMessages unprocessed messages - DATA LOSS")
      metrics.incrementCounter("processor.stop.message_loss", remainingMessages)
    }
  }

  dispatchers.values.foreach(d => d.queue.removeIf(_.id == id))
  registrar.unRegister(id)
}
```

## Recommended Action

**Immediate**: Implement Solution 3 (logging) to make data loss visible
**Short-term**: Implement Solution 1 (graceful drain with timeout)
**Long-term**: Implement Solution 2 (dead letter queue) for production-grade reliability

## Technical Details

**Affected Files**:
- `src/main/scala/sss/events/EventProcessingEngine.scala` (stop method)
- Tests: `benchmarks/src/test/scala/sss/events/stress/ActorChurnStressSpec.scala` (currently failing)

**Components**:
- EventProcessingEngine.stop()
- BaseEventProcessor.q (LinkedBlockingQueue)

**Database/State Changes**: None

## Acceptance Criteria

- [ ] stop() method drains messages before removing processor
- [ ] Timeout mechanism prevents indefinite blocking
- [ ] Remaining message count logged at ERROR level
- [ ] ActorChurnStressSpec tests pass without message loss
- [ ] Metrics added for message loss tracking
- [ ] Documentation updated with graceful shutdown semantics

## Work Log

### 2026-02-02 - Initial Assessment
- **Discovered**: data-integrity-guardian agent identified critical data loss during code review
- **Evidence**: 3 failing stress tests confirm message loss during processor churn
- **Impact**: Production blocker - guaranteed data loss during normal operations
- **Next**: Implement Solution 3 immediately, then Solution 1

## Resources

- **Related Issue**: #002 (race condition during stop)
- **Similar Patterns**: Akka actor system shutdown, Erlang OTP graceful termination
- **Documentation**: [Akka Coordinated Shutdown](https://doc.akka.io/docs/akka/current/coordinated-shutdown.html)
- **Tests Affected**: ActorChurnStressSpec (3 tests currently failing)

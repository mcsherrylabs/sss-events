---
status: pending
priority: p1
issue_id: "006"
tags: [code-review, performance, architecture, latency]
dependencies: []
---

# LockSupport Missed Wakeup Pattern Causes Artificial 100μs Latency

## Problem Statement

**PERFORMANCE DEGRADATION**: The current LockSupport.park pattern introduces artificial minimum 100μs latency because threads park themselves without a proper unpark mechanism when new work arrives. This violates reactive design principles and degrades P99 latency.

**Why This Matters**: Every message experiences minimum 100μs latency even when threads are idle and ready to process. This is a 100x-1000x increase over optimal latency (sub-microsecond message passing).

## Findings

**Location**: `/home/alan/develop/sss-events/src/main/scala/sss/events/EventProcessingEngine.scala:182-186`

**Current Code**:
```scala
var am = dispatcher.queue.poll()
while (am == null && keepGoing.get()) {
  LockSupport.parkNanos(100_000) // Park for 100 microseconds
  am = dispatcher.queue.poll()
}
```

**The Problem**:
1. Thread polls empty queue
2. Thread parks itself for 100μs
3. New event arrives during park period
4. Event sits idle for remaining park duration
5. Thread wakes up and processes event

**Missed Wakeup Timeline**:
```
T=0μs:   Thread polls empty queue, prepares to park
T=1μs:   New event arrives, queue becomes non-empty
T=2μs:   Thread parks for 100μs (doesn't know event arrived!)
T=102μs: Thread wakes up, sees event, processes it
Result:  100μs latency for event that arrived 1μs after poll
```

**Agent Source**: security-sentinel review agent (CRITICAL severity, Finding #3)

## Proposed Solutions

### Solution 1: Add Condition Variables for Signaling
**Pros**:
- Industry standard pattern
- Zero artificial latency
- Proper unpark coordination
- Works with ReentrantLock

**Cons**:
- More complex than current spin-park
- Requires careful condition management

**Effort**: Medium (4-6 hours)
**Risk**: Low

**Implementation**:
```scala
case class LockedDispatcher(
  name: String,
  lock: ReentrantLock,
  queue: ConcurrentLinkedQueue[BaseEventProcessor],
  workAvailable: Condition  // ADD THIS
)

object LockedDispatcher {
  def apply(name: String): LockedDispatcher = {
    val lock = new ReentrantLock(false)
    LockedDispatcher(
      name,
      lock,
      new ConcurrentLinkedQueue[BaseEventProcessor](),
      lock.newCondition()  // Create condition from lock
    )
  }
}

// In processTask:
dispatcher.lock.lock()
try {
  var am = dispatcher.queue.poll()
  while (am == null && keepGoing.get()) {
    dispatcher.workAvailable.await(100, TimeUnit.MICROSECONDS)  // Timed wait
    am = dispatcher.queue.poll()
  }
  if (am != null) {
    // process...
  }
} finally {
  dispatcher.lock.unlock()
}

// In register/post:
dispatcher.lock.lock()
try {
  dispatcher.queue.offer(processor)
  dispatcher.workAvailable.signal()  // Wake one waiting thread
} finally {
  dispatcher.lock.unlock()
}
```

### Solution 2: Reduce Park Duration to 1μs
**Pros**:
- Minimal code change
- Reduces latency from 100μs to 1μs

**Cons**:
- Still artificial latency (not zero)
- More CPU usage (tighter spin)
- Doesn't solve fundamental design issue

**Effort**: Trivial (1 minute)
**Risk**: None

**Implementation**:
```scala
LockSupport.parkNanos(1_000)  // Park for 1μs instead of 100μs
```

### Solution 3: Adaptive Parking with Backoff
**Pros**:
- Low latency when busy
- Low CPU usage when idle
- Best of both worlds

**Cons**:
- More complex logic
- Still has some artificial latency

**Effort**: Medium (2-3 hours)
**Risk**: Low

**Implementation**:
```scala
var parkDuration = 1_000L  // Start with 1μs
var am = dispatcher.queue.poll()
while (am == null && keepGoing.get()) {
  LockSupport.parkNanos(parkDuration)
  am = dispatcher.queue.poll()

  if (am == null) {
    // No work found, increase park duration (exponential backoff)
    parkDuration = Math.min(parkDuration * 2, 100_000)  // Max 100μs
  } else {
    // Work found, reset to fast polling
    parkDuration = 1_000L
  }
}
```

## Recommended Action

**SHORT-TERM**: Implement Solution 2 (reduce to 1μs) - immediate latency improvement
**LONG-TERM**: Implement Solution 1 (condition variables) - proper reactive design

## Technical Details

**Affected Files**:
- `src/main/scala/sss/events/EventProcessingEngine.scala` (line 182-186, processTask method)
- `src/main/scala/sss/events/LockedDispatcher.scala` (add Condition field)

**Components**:
- EventProcessingEngine.processTask()
- LockedDispatcher (add workAvailable Condition)

**Performance Impact**:
- **Current**: P99 latency >= 100μs (artificial floor)
- **With Solution 2**: P99 latency >= 1μs (100x improvement)
- **With Solution 1**: P99 latency <1μs (optimal)

**CPU Usage**:
- **Current**: Low (threads sleep for 100μs)
- **With Solution 2**: Higher (threads sleep for 1μs = 100x more wakeups)
- **With Solution 1**: Optimal (threads only wake when signaled)

## Acceptance Criteria

- [ ] Implement condition variable signaling
- [ ] Remove fixed 100μs park duration
- [ ] Add condition.signal() when posting to queue
- [ ] Measure P99 latency improvement (should be <10μs)
- [ ] Add latency benchmark comparing before/after
- [ ] Document reactive signaling pattern in architecture docs

## Work Log

### 2026-02-02 - Initial Assessment
- **Discovered**: security-sentinel agent identified during architecture review
- **Category**: Performance degradation / missed wakeup pattern
- **Impact**: 100μs artificial latency floor on all messages
- **OWASP**: A04:2021 - Insecure Design (performance)
- **Priority**: P1 because latency is critical for event processing systems

### 2026-02-02 - Latency Analysis
- Current P99: Unknown but >= 100μs
- Target P99: <10μs (sub-millisecond)
- Benchmarks needed: Add latency measurement to ThroughputBenchmark

## Resources

- **LockSupport Best Practices**: [Oracle Concurrency Tutorial](https://docs.oracle.com/javase/tutorial/essential/concurrency/index.html)
- **Condition Variables**: [ReentrantLock conditions](https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/locks/Condition.html)
- **Akka Mailbox**: Similar reactive pattern with signaling
- **Disruptor Pattern**: Wait strategies for low-latency systems
- **Related**: Performance oracle identified this in thread coordination analysis

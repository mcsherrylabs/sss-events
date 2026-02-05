---
status: pending
priority: p1
issue_id: "004"
tags: [code-review, security, thread-safety, race-condition]
dependencies: []
---

# Handler Stack Race Condition - Concurrent Lazy Initialization

## Problem Statement

**CRITICAL THREAD SAFETY BUG**: The handler stack is lazily initialized without synchronization, creating a race condition where multiple threads can trigger initialization simultaneously, resulting in multiple Stack instances and lost handler updates.

**Why This Matters**: Handler stack corruption can cause messages to be routed to wrong handlers, dropped entirely, or cause NullPointerExceptions. This violates the actor model's single-threaded handler guarantee.

## Findings

**Location**: `/home/alan/develop/sss-events/src/main/scala/sss/events/EventProcessor.scala:105`

**Vulnerable Code**:
```scala
lazy private val handlers: mutable.Stack[EventHandler] = mutable.Stack(onEvent)
```

**Race Scenario**:
```
Thread 1: Processes first event -> triggers lazy init of handlers -> Stack A created
Thread 2: Concurrently posts BecomeRequest -> triggers lazy init -> Stack B created
Result: Two Stack instances exist, updates to one are invisible to the other
```

**Why taskLock Doesn't Help**:
- Lazy initialization happens on FIRST ACCESS
- If two threads access handlers concurrently during first access, both initialize
- Even though processEvent() is protected by taskLock, the lazy val initialization is NOT

**Agent Source**: security-sentinel review agent (CRITICAL severity)

## Proposed Solutions

### Solution 1: Remove Lazy Initialization (RECOMMENDED)
**Pros**:
- Simplest fix
- No performance impact (initialization is cheap)
- Guaranteed thread-safe
- Initialization happens during construction under superclass lock

**Cons**:
- None

**Effort**: Trivial (2 minutes)
**Risk**: None

**Implementation**:
```scala
// CHANGE FROM:
lazy private val handlers: mutable.Stack[EventHandler] = mutable.Stack(onEvent)

// CHANGE TO:
private val handlers: mutable.Stack[EventHandler] = mutable.Stack(onEvent)
```

### Solution 2: Synchronized Lazy Initialization
**Pros**:
- Delays initialization until needed
- Thread-safe

**Cons**:
- Unnecessary complexity
- Synchronization overhead on every access
- No benefit since initialization is cheap

**Effort**: Small (30 minutes)
**Risk**: Low but adds overhead

**Implementation**:
```scala
private object HandlersLock
private var _handlers: Option[mutable.Stack[EventHandler]] = None

private def handlers: mutable.Stack[EventHandler] = HandlersLock.synchronized {
  _handlers match {
    case Some(stack) => stack
    case None =>
      val stack = mutable.Stack(onEvent)
      _handlers = Some(stack)
      stack
  }
}
```

### Solution 3: @volatile with Double-Checked Locking
**Pros**:
- Minimal synchronization overhead
- Industry standard pattern

**Cons**:
- Overly complex for this use case
- Still unnecessary since initialization is cheap

**Effort**: Medium (1 hour)
**Risk**: Medium (easy to get DCL wrong)

## Recommended Action

**Implement Solution 1** - Remove lazy keyword. This is a 1-line fix with zero downsides.

## Technical Details

**Affected Files**:
- `src/main/scala/sss/events/EventProcessor.scala` (line 105)

**Components**:
- BaseEventProcessor.handlers field

**Current Protection**:
- `taskLock.synchronized` in processEvent() (line 193) protects handler modifications
- BUT lazy initialization happens before lock is acquired

**Database/State Changes**: None

## Acceptance Criteria

- [ ] Remove `lazy` keyword from handlers field
- [ ] Verify all tests pass
- [ ] Add specific test for concurrent first-access
- [ ] Document that handlers are initialized during construction

## Work Log

### 2026-02-02 - Initial Assessment
- **Discovered**: security-sentinel agent found during OWASP audit
- **Severity**: CRITICAL - Thread safety violation
- **OWASP Category**: A04:2021 - Insecure Design (race condition)
- **Evidence**: No synchronization on lazy val initialization
- **Fix Complexity**: Trivial (1 line change)

## Resources

- **Java Memory Model**: [JSR-133](https://www.cs.umd.edu/~pugh/java/memoryModel/jsr-133-faq.html)
- **Scala Lazy Val Thread Safety**: [SI-7666](https://github.com/scala/bug/issues/7666) - lazy val initialization is thread-safe in Scala 2.11+ but can cause double initialization
- **Related Issue**: #005 (keepGoing var should be val)
- **Security Analysis**: security-sentinel agent report, Finding #1

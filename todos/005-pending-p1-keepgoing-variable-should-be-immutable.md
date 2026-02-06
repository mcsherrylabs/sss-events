---
status: pending
priority: p1
issue_id: "005"
tags: [code-review, security, thread-safety, memory-visibility]
dependencies: []
---

# keepGoing Variable Should Be Immutable - Memory Visibility Risk

## Problem Statement

**CRITICAL MEMORY VISIBILITY**: The `keepGoing` field is declared as `var` (mutable) instead of `val` (immutable), creating a potential memory visibility issue where thread-local caches may never see shutdown signals.

**Why This Matters**: Threads may never observe the shutdown command, leading to hung threads that prevent clean application termination. This is a classic memory visibility bug in concurrent programming.

## Findings

**Location**: `/home/alan/develop/sss-events/src/main/scala/sss/events/EventProcessingEngine.scala:72`

**Vulnerable Code**:
```scala
private var keepGoing: AtomicBoolean = new AtomicBoolean(true)
```

**The Problem**:
- `keepGoing` is declared as `var` (mutable reference)
- The reference itself is NOT volatile
- AtomicBoolean provides atomicity for its VALUE, not for the REFERENCE
- Declaring as `var` allows reassignment (though code doesn't currently do this)

**Memory Visibility Scenario**:
```
Thread 1 (initialization):
  keepGoing = new AtomicBoolean(true)  // Creates reference R1

Thread 2 (worker, cached R1):
  while (keepGoing.get()) { ... }      // Reads R1 from thread-local cache

Thread 3 (hypothetical future code):
  keepGoing = new AtomicBoolean(false) // Reassigns to R2

Thread 2 (still cached R1):
  while (keepGoing.get()) { ... }      // Still reading old R1!
```

**Current Code Safety**: The current implementation never reassigns `keepGoing`, so this is more of a defensive fix. However, declaring as `var` signals to future developers that reassignment is allowed, which would be dangerous.

**Agent Source**: security-sentinel review agent (CRITICAL severity, Finding #2)

## Proposed Solutions

### Solution 1: Change var to val (RECOMMENDED)
**Pros**:
- Simplest fix
- Makes immutability explicit
- Prevents future bugs from reassignment
- No performance impact

**Cons**:
- None

**Effort**: Trivial (1 minute)
**Risk**: None

**Implementation**:
```scala
// CHANGE FROM:
private var keepGoing: AtomicBoolean = new AtomicBoolean(true)

// CHANGE TO:
private val keepGoing: AtomicBoolean = new AtomicBoolean(true)
```

### Solution 2: Make keepGoing @volatile var
**Pros**:
- Allows reassignment (if needed in future)
- Ensures visibility

**Cons**:
- More complex than needed
- Encourages mutable design
- Reassignment is never needed (just toggle AtomicBoolean value)

**Effort**: Small (5 minutes)
**Risk**: Low but encourages bad patterns

**Implementation**:
```scala
@volatile private var keepGoing: AtomicBoolean = new AtomicBoolean(true)
```

## Recommended Action

**Implement Solution 1** - Change to `val`. This is a 1-character fix (var → val) with no downsides.

## Technical Details

**Affected Files**:
- `src/main/scala/sss/events/EventProcessingEngine.scala` (line 72)

**Components**:
- EventProcessingEngine.keepGoing field
- Used in shutdown() (line 270)
- Read by all worker threads in main loop

**Memory Model**:
- AtomicBoolean provides happens-before guarantees for VALUE updates
- But the REFERENCE to AtomicBoolean needs happens-before via:
  - Final fields (if val)
  - Volatile fields (if @volatile var)
  - Proper synchronization

**Current Safety**: Safe because keepGoing is never reassigned, but declaring as var is misleading and dangerous for future code.

## Acceptance Criteria

- [ ] Change `var keepGoing` to `val keepGoing`
- [ ] Verify all tests pass (especially shutdown tests)
- [ ] Add test for shutdown signal visibility (multiple threads)
- [ ] Document that keepGoing reference is immutable

## Work Log

### 2026-02-02 - Initial Assessment
- **Discovered**: security-sentinel agent during OWASP A04:2021 audit
- **Category**: Memory visibility issue
- **Current Risk**: LOW (code doesn't reassign)
- **Future Risk**: HIGH (var signals reassignment is allowed)
- **Fix**: Trivial (1-character change)

## Resources

- **Java Memory Model**: [JSR-133 FAQ](https://www.cs.umd.edu/~pugh/java/memoryModel/jsr-133-faq.html)
- **Happens-Before**: [Oracle JMM Guide](https://docs.oracle.com/javase/specs/jls/se17/html/jls-17.html#jls-17.4.5)
- **AtomicBoolean**: [Java docs](https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/atomic/AtomicBoolean.html)
- **Related Issue**: #004 (lazy handlers race condition)
- **Security Analysis**: security-sentinel agent report, Finding #2

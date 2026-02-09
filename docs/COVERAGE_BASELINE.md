# Test Coverage Baseline Analysis

**Generated**: 2026-02-09
**Version**: 0.0.10
**Branch**: fix/request-become-race-condition

## Executive Summary

The sss-events library demonstrates **strong test coverage** with solid fundamentals:

- **Statement Coverage**: 69.49% (exceeds 60% threshold ✅)
- **Branch Coverage**: 63.83% (exceeds 50% threshold ✅)
- **Test Suite**: 70 tests passing (100% success rate)
- **Test Execution Time**: ~11 seconds (fast and efficient)

Coverage levels already exceed configured thresholds, indicating a well-tested codebase. This analysis identifies specific edge cases and error scenarios that could benefit from additional testing.

## Coverage Metrics

### Overall Coverage

```
Statement Coverage: 69.49%
Branch Coverage:    63.83%
Method Coverage:    Not reported by scoverage
```

### Coverage by Component

Based on source file analysis:

| Component | Responsibility | Coverage Priority |
|-----------|---------------|-------------------|
| EventProcessingEngine | Core dispatch loop, processor lifecycle | HIGH - Core correctness |
| Subscriptions | Pub/sub, NotDelivered handling | MEDIUM - User-facing feature |
| Scheduler | Delayed message delivery, cancellation | MEDIUM - Error handling |
| EventProcessor | Handler stack, become/unbecome | MEDIUM - Already well-tested |
| EngineConfig | Validation, defaults | LOW - Comprehensive tests (12 tests) |
| Supporting classes | Utilities, builders | LOW - Simple implementations |

## Subscription Test Coverage Analysis

### Current Tests (SubscriptionsSpec.scala)

The subscription system has **4 tests** covering happy paths:

✅ **Tested Scenarios:**
1. `setSubscriptions` - Set all subscriptions at once (line 26-31)
2. `subscribe` - Add subscription to existing set (line 33-39)
3. `unsubscribe` - Remove specific subscription (line 41-49)
4. `broadcast` - Send message to all subscribers on channel (line 51-67)

### Implementation Analysis (Subscriptions.scala)

**Critical Paths:**
- **Lines 94-113**: `broadcast` implementation with NotDelivered handling
  ```scala
  case b@Broadcast(sendr, targetChannels, a) =>
    subscriptions.foreach {
      case (k, values) if targetChannels.contains(k) =>
        values.foreach { ep =>
          val ok = ep ! a
          if (!ok) {
            sendr ! NotDelivered(ep, b)  // ❌ NOT TESTED
          }
        }
  ```

- **Lines 75-89**: Thread-safe subscription management with `lock.synchronized`
- **Lines 36-48**: `unsubscribeImpl` - subscription removal logic
- **Lines 50-65**: `updateSubImpl` - subscription update logic

### Missing Test Scenarios

| Priority | Scenario | Why Critical | Lines Affected |
|----------|----------|-------------|----------------|
| **P0** | NotDelivered handling when subscriber queue full | User-facing error path - affects reliability | 106-110 |
| **P1** | Concurrent subscribe/unsubscribe during broadcast | Race condition risk - safety-critical | 94-113 + 75-89 |
| **P2** | Memory cleanup on processor stop | Memory leak risk if subscriptions not removed | Implicit cleanup |
| **P3** | Broadcast to non-existent channel | Edge case - should be no-op | 103-113 |
| **P3** | Multiple subscribers unsubscribe simultaneously | Concurrency edge case | 87-89 |

### Gap Analysis

**NotDelivered Scenario** (P0):
- **What's tested**: Broadcast when queue has space
- **What's missing**: Broadcast when target processor queue is full
- **Why it matters**: This is a user-facing failure mode - users need to handle NotDelivered messages

**Concurrent Operations** (P1):
- **What's tested**: Sequential operations only
- **What's missing**: Concurrent subscribe/unsubscribe during active broadcast
- **Why it matters**: `lock.synchronized` protects subscription map but broadcast reads it outside lock
- **Potential race**: Thread 1 broadcasts, Thread 2 unsubscribes during broadcast iteration

**Memory Cleanup** (P2):
- **What's tested**: Normal subscription/unsubscription
- **What's missing**: Verification that subscriptions are cleaned up when processor stops
- **Why it matters**: Memory leak if processor stops but subscriptions remain in map

## Scheduler Test Coverage Analysis

### Current Tests (CancelScheduledSpec.scala)

The scheduler has **3 tests** covering core functionality:

✅ **Tested Scenarios:**
1. `schedule` → message posted successfully (line 22-39)
2. `cancel` → cancelled message not posted (line 41-63)
3. Schedule to unregistered processor → FailedUnRegistered (line 65-79)

### Implementation Analysis (Scheduler.scala)

**Critical Paths:**
- **Lines 48-71**: `schedule` method - delay, lookup, post logic
  ```scala
  def schedule(who: String, msg: Any, delay: FiniteDuration): Schedule = {
    scheduledExecutorService.schedule(new Runnable {
      override def run(): Unit = {
        if (!result.isCancelled()) {
          registrar.get(who) match {
            case Some(found) =>
              if (!found.post(msg)) {
                result.result.success(ScheduledResult.FailedQueueFull)  // ❌ NOT TESTED
              } else {
                result.result.success(ScheduledResult.Posted)
              }
            case None =>
              result.result.success(ScheduledResult.FailedUnRegistered)  // ✅ TESTED
          }
        }
      }
    }, delay.toMillis, TimeUnit.MILLISECONDS)
    result
  }
  ```

- **Lines 23-30**: `cancel` method - thread-safe cancellation with promise
- **Lines 32**: `isCancelled` check - used in execution path
- **Lines 19-35**: `Schedule` class - promise-based result tracking

### Missing Test Scenarios

| Priority | Scenario | Why Critical | Lines Affected |
|----------|----------|-------------|----------------|
| **P0** | FailedQueueFull when target queue full | Error handling - affects reliability | 56-58 |
| **P1** | Concurrent scheduling to same processor | Thread pool behavior under contention | 48-71 |
| **P2** | Cancel during message delivery (race) | Cancel happens after `isCancelled()` check but before post | 53-61 |
| **P3** | Multiple schedules with varying delays | Scheduler pool thread management | 44-46 |
| **P3** | Schedule to processor that stops before delivery | Edge case - processor removed from registrar | 54-64 |

### Gap Analysis

**FailedQueueFull Scenario** (P0):
- **What's tested**: Posted successfully, FailedUnRegistered
- **What's missing**: Schedule to processor with full queue → FailedQueueFull
- **Why it matters**: This is an error recovery path - outcome Future should contain FailedQueueFull

**Concurrent Scheduling** (P1):
- **What's tested**: Single schedule operations
- **What's missing**: Multiple threads scheduling to same processor simultaneously
- **Why it matters**: Tests scheduler thread pool behavior and registrar lookup under contention

**Cancel Timing Race** (P2):
- **What's tested**: Cancel before execution (`100.millis` delay gives time to cancel)
- **What's missing**: Cancel that arrives *during* execution window (after `isCancelled()` check)
- **Why it matters**: Tests race condition between cancellation and message posting

## Prioritized Testing Gaps

### High Priority (Implement for Release)

1. **Subscriptions: NotDelivered handling**
   - **Test**: Fill processor queue, broadcast, verify NotDelivered sent to sender
   - **Why**: User-facing error path - affects API contract
   - **Effort**: 15-20 minutes

2. **Scheduler: FailedQueueFull outcome**
   - **Test**: Fill processor queue, schedule message, verify outcome Future contains FailedQueueFull
   - **Why**: Error handling path - affects API contract
   - **Effort**: 15-20 minutes

### Medium Priority (Consider for Release)

3. **Subscriptions: Concurrent unsubscribe during broadcast**
   - **Test**: Start broadcast in thread 1, unsubscribe in thread 2, verify no crashes/corruption
   - **Why**: Safety-critical race condition
   - **Effort**: 25-30 minutes

4. **Scheduler: Concurrent cancel during execution**
   - **Test**: Schedule with minimal delay, cancel immediately, verify deterministic outcome
   - **Why**: Tests cancellation race condition
   - **Effort**: 20-25 minutes

### Lower Priority (Defer to Future Release)

5. **Subscriptions: Memory cleanup on processor stop** - Medium risk, complex to test
6. **Scheduler: Concurrent scheduling** - Already implicitly tested via high concurrency tests
7. **Subscriptions: Broadcast to non-existent channel** - Low impact edge case
8. **Scheduler: Multiple schedules with varying delays** - Already implicitly tested

## Recommended Test Additions

Based on priority and effort analysis, recommend adding **2-4 high-priority tests**:

### Must-Have (P0):

1. **NotDelivered Subscription Test**
   ```scala
   "Subscriptions" should "return NotDelivered when subscriber queue is full" in {
     // Create processor with small queue (1-2 items)
     // Fill queue completely
     // Attempt broadcast
     // Verify sender receives NotDelivered message
     // Use CountDownLatch for deterministic coordination
   }
   ```

2. **FailedQueueFull Scheduler Test**
   ```scala
   "Scheduler" should "return FailedQueueFull when target queue is full" in {
     // Create processor with small queue
     // Fill queue completely
     // Schedule message with 0ms delay
     // Verify outcome Future contains ScheduledResult.FailedQueueFull
   }
   ```

### Should-Have (P1):

3. **Concurrent Unsubscribe During Broadcast Test**
   ```scala
   "Subscriptions" should "handle concurrent unsubscribe during broadcast" in {
     // Create 2 subscribers
     // Start broadcast in thread 1 (use slow message handler)
     // Unsubscribe in thread 2 during broadcast
     // Verify: no crashes, no duplicate deliveries
     // Use CountDownLatch to coordinate threads
   }
   ```

4. **Concurrent Cancel During Execution Test**
   ```scala
   "Scheduler" should "handle cancel during message delivery" in {
     // Schedule with 1ms delay
     // Cancel immediately after scheduling
     // Verify outcome is either Cancelled or Posted (both acceptable)
     // Test is deterministic if it checks "one of" not "exactly"
   }
   ```

## Implementation Guidelines

All new tests must follow project testing standards from `memory/MEMORY.md`:

- ✅ Use `CountDownLatch` or `Semaphore` for thread coordination
- ✅ **NO `Thread.sleep()`** - only explicit synchronization
- ✅ Keep timeouts ≤ 1 second
- ✅ Deterministic design - no flakiness
- ✅ Use `AtomicInteger` for shared counters

### Example Pattern (CountDownLatch):

```scala
val latch = new CountDownLatch(expectedCount)
// ... code that calls latch.countDown()
val completed = latch.await(1, TimeUnit.SECONDS)
completed shouldBe true
```

## Coverage Improvement Estimation

**Current Coverage**: 69.49% statement, 63.83% branch

**Estimated Impact of Priority Tests**:
- Adding 2 P0 tests: +1-2% statement coverage
- Adding 2 P1 tests: +1-2% statement coverage
- **Total**: 71-73% statement coverage achievable

**Target**: Maintain >60% statement, >50% branch (both currently exceeded)

## Top 10 Uncovered Code Blocks

To view detailed uncovered blocks, open the HTML coverage report:
```bash
xdg-open target/scala-3.6.4/scoverage-report/index.html
```

Key uncovered areas (based on implementation review):
1. `Subscriptions.scala:108-110` - NotDelivered handling in broadcast
2. `Scheduler.scala:56-58` - FailedQueueFull error handling
3. Error handling paths in EventProcessingEngine (graceful shutdown edge cases)
4. Builder pattern utility methods (low priority)
5. Logging and toString methods (not critical for functionality)

## Conclusion

The sss-events library has **strong baseline coverage (69.49%)** that already exceeds configured thresholds. The test suite is fast, deterministic, and follows best practices.

**Key Findings**:
- ✅ Core functionality well-tested (70 passing tests)
- ✅ Coverage exceeds minimum thresholds
- ⚠️ NotDelivered handling not tested (user-facing error path)
- ⚠️ FailedQueueFull scenario not tested (error recovery)
- ⚠️ Some concurrent operation edge cases not tested

**Recommendation**: Add 2-4 high-priority tests to cover error paths and concurrent edge cases, then proceed with release. The cost is low (1-2 hours), and the benefit is high (critical error paths validated).

**Next Steps**:
1. Implement P0 tests (NotDelivered, FailedQueueFull)
2. Consider P1 tests (concurrent operations)
3. Regenerate coverage report to measure improvement
4. Update plan document with completion checkmarks

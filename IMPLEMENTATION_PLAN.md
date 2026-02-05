# TODO Implementation Plan

## Overview
This plan addresses 6 P1 issues identified in the todos folder. Each issue includes implementation details, test requirements, and verification steps.

---

## 001: Message Loss During Processor Stop
**Solution**: Solution 1 - Block Until Queue Drains (Graceful Shutdown)

### Implementation Tasks
- [x] Add timeout parameter to `stop()` method (default: 30 seconds)
- [x] Implement queue draining logic before removing processor
- [x] Add warning log when draining messages
- [x] Add critical error log if timeout occurs with remaining messages
- [x] Add critical error log for final message count if any lost
- [x] Update dispatcher queue removal to happen after drain
- [x] Update registrar unRegister to happen after drain

### Test Tasks
- [x] Test graceful stop with empty queue
- [x] Test graceful stop with messages in queue (verify all processed)
- [x] Test timeout scenario (verify error logging)
- [x] Test stop during active processing
- [x] Fix ActorChurnStressSpec: "should handle actor churn with mixed queue sizes"
- [x] Fix ActorChurnStressSpec: "should handle queue overflow gracefully"
- [x] Fix ActorChurnStressSpec: "should maintain stability under high churn (100 iterations)"

### Files to Modify
- `src/main/scala/sss/events/EventProcessingEngine.scala` (stop method at line 175-178)

---

## 002: Race Condition in Stop - Processor Active Processing
**Solution**: Lock dispatcher during stop, search for correct dispatcher before locking

### Implementation Tasks
- [x] Find the dispatcher containing the processor BEFORE acquiring lock
- [x] Acquire lock on the specific dispatcher found
- [x] Check processor is not currently being processed by a worker thread
- [x] Remove processor from dispatcher queue while locked
- [x] Unregister processor from registrar
- [x] Add timeout mechanism to prevent indefinite waiting

### Test Tasks
- [x] Test stop during active message processing
- [x] Test stop when processor in dispatcher queue
- [x] Test stop when processor not in any queue
- [x] Verify "Failed to return processor to queue" error never occurs during normal stop
- [x] Test concurrent stop calls on same processor
- [x] Test stop timeout scenario

### Files to Modify
- `src/main/scala/sss/events/EventProcessingEngine.scala` (stop method at line 175-178)
- `src/main/scala/sss/events/EventProcessingEngine.scala` (processTask at line 204-206)

---

## 003: Default Queue Size Memory Explosion
**Solution**: Solution 2 - Make Default Configurable via HOCON

### Implementation Tasks
- [x] Add `defaultQueueSize` field to `EngineConfig` case class (default: 10000)
- [x] Add validation: `defaultQueueSize` must be in range [1, 1000000]
- [x] Update `EventProcessor.queueSize` to use `engine.config.defaultQueueSize`
- [x] Add `default-queue-size = 10000` to `reference.conf`
- [x] Update documentation with queue sizing guidance

### Test Tasks
- [x] Test default queue size is 10000 when not configured
- [x] Test custom queue size from config
- [x] Test queueSizeOverride still works (takes precedence over default)
- [x] Test validation rejects invalid queue sizes (<1 or >1000000)
- [x] Test memory usage with 100 processors
- [x] Test memory usage with 500 processors

### Files to Modify
- `src/main/scala/sss/events/EngineConfig.scala` (add defaultQueueSize)
- `src/main/scala/sss/events/EventProcessor.scala` (line 97)
- `src/main/resources/reference.conf` (add default-queue-size)
- `docs/best-practices/thread-dispatcher-configuration.md` (add queue sizing section)

---

## 004: Handler Stack Lazy Initialization Race
**Decision**: Verify if taskLock synchronises access

### Verification Tasks
- [x] Review all code paths that access `handlers` field
- [x] Verify `processEvent()` acquires taskLock before accessing handlers
- [x] Verify `post(BecomeRequest)` path is synchronized
- [x] Document findings in code comments

### Test Tasks
- [x] Test concurrent first access to handlers
- [x] Test concurrent become requests
- [x] Verify handler stack integrity under concurrent access

### Files to Review
- `src/main/scala/sss/events/EventProcessor.scala` (line 105, lazy handlers)
- `src/main/scala/sss/events/EventProcessor.scala` (processEvent method)

### Decision Point
After verification:
- [x] **If taskLock DOES protect lazy init**: Add documentation comment explaining why it's safe
- [x] **If taskLock DOES NOT protect lazy init**: Skip this task (do not remove lazy keyword, do not mark as done)

**DECISION**: taskLock DOES protect lazy init. All accesses to handlers occur within:
1. `processEvent()` - called from synchronized block (EventProcessingEngine.scala:286-288)
2. `become()` - only called from processEvent()
3. `unbecome()` - only called from processEvent() or become()

The lazy initialization is thread-safe because Scala's lazy val provides safe publication, and all subsequent accesses are serialized by taskLock.

---

## 005: keepGoing Variable Should Be Immutable
**Solution**: Solution 1 - Change var to val

### Implementation Tasks
- [x] Change `private var keepGoing` to `private val keepGoing`
- [x] Verify no code attempts to reassign keepGoing
- [x] Add comment documenting that reference is immutable

### Test Tasks
- [x] Verify all existing tests pass
- [x] Test shutdown signal visibility across multiple threads
- [x] Test graceful shutdown completes properly

### Files to Modify
- `src/main/scala/sss/events/EventProcessingEngine.scala` (line 72)

---

## 006: LockSupport Missed Wakeup Latency
**Solution**: Solution 1 - Add Condition Variables (signal in register only, NOT in post)

### Implementation Tasks
- [x] Add `workAvailable: Condition` field to `LockedDispatcher` case class
- [x] Update `LockedDispatcher.apply` to create condition from lock: `lock.newCondition()`
- [x] Update `processTask` to use `dispatcher.workAvailable.await(100, TimeUnit.MICROSECONDS)`
- [x] Update `processTask` to acquire/release lock around wait
- [x] Update `register` method to signal condition after adding processor to queue
- [x] **IMPORTANT**: DO NOT signal condition in `post` method (only signal in register)

### Test Tasks
- [x] Test latency improvement (should be <10μs)
- [x] Add latency benchmark comparing before/after
- [x] Test thread wakeup when new work arrives
- [x] Test multiple threads waiting on same condition
- [x] Test condition signaling under load
- [x] Measure P99 latency improvement

### Files to Modify
- `src/main/scala/sss/events/EventProcessingEngine.scala` (LockedDispatcher case class)
- `src/main/scala/sss/events/EventProcessingEngine.scala` (LockedDispatcher.apply at line 46-53)
- `src/main/scala/sss/events/EventProcessingEngine.scala` (processTask at line 182-186)
- `src/main/scala/sss/events/EventProcessingEngine.scala` (register method - add signal)

---

## 007: Refactor createRunnable to Accept Dispatcher Type
**Solution**: Change createRunnable parameter from Array[String] to Array[LockedDispatcher]

### Implementation Tasks
- [x] Change `createRunnable` parameter from `Array[String]` to `Array[LockedDispatcher]`
- [x] Remove dispatcher lookup inside createRunnable (line 322: `val dispatcher = dispatchers(dispatcherName)`)
- [x] Update local variables: remove `dispatcherName`, rename/update `roundRobinIndex` usage
- [x] Update call site at line 382 to pass dispatcher objects instead of dispatcher names
- [x] Map `config.threadDispatcherAssignment` to `Array[LockedDispatcher]` before passing to createRunnable

### Test Tasks
- [x] Verify all existing tests pass after refactoring
- [x] Test round-robin dispatcher assignment still works correctly
- [x] Test worker thread initialization with multiple dispatchers
- [x] Verify performance improvement (eliminate map lookup on every iteration)

### Files to Modify
- `src/main/scala/sss/events/EventProcessingEngine.scala` (createRunnable method at line 313-357)
- `src/main/scala/sss/events/EventProcessingEngine.scala` (call site at line 382)

### Benefits
- **Performance**: Eliminates map lookup (`dispatchers(dispatcherName)`) on every worker loop iteration
- **Type Safety**: Stronger typing with actual dispatcher objects instead of string names
- **Simplicity**: Clearer code with direct dispatcher access

---

## General Testing Requirements

### Integration Tests
- [f] Run full test suite after all changes (REGRESSION FOUND: TwoDispatcherSpec failing, stress tests hung)
- [f] Verify no regressions in existing tests (TwoDispatcherSpec: "should process messages when default blocked" FAILED)
- [f] Run benchmarks to measure performance impact

### Stress Tests
- [f] Run ActorChurnStressSpec with all fixes
- [f] Run QueueOverflowSpec (test doesn't exist)
- [f] Run high-concurrency scenarios

### Documentation
- [x] Update CHANGELOG.md with all fixes
- [x] Update architecture documentation
- [x] Document graceful shutdown semantics
- [x] Document queue sizing best practices
- [ ] Document reactive signaling pattern

---

## Implementation Order

### Phase 1: Critical Fixes (Simple)
1. **005** - Change keepGoing var to val (1 minute)
2. **004** - Verify taskLock protection (5 minutes - may skip if not protected)

### Phase 2: Performance & Configuration
3. **003** - Make default queue size configurable (1-2 hours)
4. **006** - Add condition variable signaling (4-6 hours)

### Phase 3: Data Integrity (Complex)
5. **002** - Fix race condition in stop (4-6 hours)
6. **001** - Add graceful queue draining (4-6 hours)

---

## Success Criteria

- [ ] All actionable P1 issues resolved
- [ ] All tests passing (including previously failing stress tests)
- [ ] No new regressions introduced
- [ ] Performance benchmarks show improvement
- [ ] Documentation updated
- [ ] Code review completed

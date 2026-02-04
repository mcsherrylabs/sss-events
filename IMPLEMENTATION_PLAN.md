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
- [ ] Fix ActorChurnStressSpec: "should handle actor churn with mixed queue sizes"
- [ ] Fix ActorChurnStressSpec: "should handle queue overflow gracefully"
- [ ] Fix ActorChurnStressSpec: "should maintain stability under high churn (100 iterations)"

### Files to Modify
- `src/main/scala/sss/events/EventProcessingEngine.scala` (stop method at line 175-178)

---

## 002: Race Condition in Stop - Processor Active Processing
**Solution**: Lock dispatcher during stop, search for correct dispatcher before locking

### Implementation Tasks
- [ ] Find the dispatcher containing the processor BEFORE acquiring lock
- [ ] Acquire lock on the specific dispatcher found
- [ ] Check processor is not currently being processed by a worker thread
- [ ] Remove processor from dispatcher queue while locked
- [ ] Unregister processor from registrar
- [ ] Add timeout mechanism to prevent indefinite waiting

### Test Tasks
- [ ] Test stop during active message processing
- [ ] Test stop when processor in dispatcher queue
- [ ] Test stop when processor not in any queue
- [ ] Verify "Failed to return processor to queue" error never occurs during normal stop
- [ ] Test concurrent stop calls on same processor
- [ ] Test stop timeout scenario

### Files to Modify
- `src/main/scala/sss/events/EventProcessingEngine.scala` (stop method at line 175-178)
- `src/main/scala/sss/events/EventProcessingEngine.scala` (processTask at line 204-206)

---

## 003: Default Queue Size Memory Explosion
**Solution**: Solution 2 - Make Default Configurable via HOCON

### Implementation Tasks
- [ ] Add `defaultQueueSize` field to `EngineConfig` case class (default: 10000)
- [ ] Add validation: `defaultQueueSize` must be in range [1, 1000000]
- [ ] Update `EventProcessor.queueSize` to use `engine.config.defaultQueueSize`
- [ ] Add `default-queue-size = 10000` to `reference.conf`
- [ ] Update documentation with queue sizing guidance

### Test Tasks
- [ ] Test default queue size is 10000 when not configured
- [ ] Test custom queue size from config
- [ ] Test queueSizeOverride still works (takes precedence over default)
- [ ] Test validation rejects invalid queue sizes (<1 or >1000000)
- [ ] Test memory usage with 100 processors
- [ ] Test memory usage with 500 processors

### Files to Modify
- `src/main/scala/sss/events/EngineConfig.scala` (add defaultQueueSize)
- `src/main/scala/sss/events/EventProcessor.scala` (line 97)
- `src/main/resources/reference.conf` (add default-queue-size)
- `docs/best-practices/thread-dispatcher-configuration.md` (add queue sizing section)

---

## 004: Handler Stack Lazy Initialization Race
**Decision**: Verify if taskLock synchronises access

### Verification Tasks
- [ ] Review all code paths that access `handlers` field
- [ ] Verify `processEvent()` acquires taskLock before accessing handlers
- [ ] Verify `post(BecomeRequest)` path is synchronized
- [ ] Document findings in TODO file

### Test Tasks
- [ ] Test concurrent first access to handlers
- [ ] Test concurrent become requests
- [ ] Verify handler stack integrity under concurrent access

### Files to Review
- `src/main/scala/sss/events/EventProcessor.scala` (line 105, lazy handlers)
- `src/main/scala/sss/events/EventProcessor.scala` (processEvent method)

### Decision Point
After verification:
- [ ] **If taskLock DOES protect lazy init**: Add documentation comment explaining why it's safe
- [ ] **If taskLock DOES NOT protect lazy init**: Skip this task (do not remove lazy keyword, do not mark as done)

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
- [ ] Add `workAvailable: Condition` field to `LockedDispatcher` case class
- [ ] Update `LockedDispatcher.apply` to create condition from lock: `lock.newCondition()`
- [ ] Update `processTask` to use `dispatcher.workAvailable.await(100, TimeUnit.MICROSECONDS)`
- [ ] Update `processTask` to acquire/release lock around wait
- [ ] Update `register` method to signal condition after adding processor to queue
- [ ] **IMPORTANT**: DO NOT signal condition in `post` method (only signal in register)

### Test Tasks
- [ ] Test latency improvement (should be <10μs)
- [ ] Add latency benchmark comparing before/after
- [ ] Test thread wakeup when new work arrives
- [ ] Test multiple threads waiting on same condition
- [ ] Test condition signaling under load
- [ ] Measure P99 latency improvement

### Files to Modify
- `src/main/scala/sss/events/EventProcessingEngine.scala` (LockedDispatcher case class)
- `src/main/scala/sss/events/EventProcessingEngine.scala` (LockedDispatcher.apply at line 46-53)
- `src/main/scala/sss/events/EventProcessingEngine.scala` (processTask at line 182-186)
- `src/main/scala/sss/events/EventProcessingEngine.scala` (register method - add signal)

---

## General Testing Requirements

### Integration Tests
- [ ] Run full test suite after all changes
- [ ] Verify no regressions in existing tests
- [ ] Run benchmarks to measure performance impact

### Stress Tests
- [ ] Run ActorChurnStressSpec with all fixes
- [ ] Run QueueOverflowSpec
- [ ] Run high-concurrency scenarios

### Documentation
- [ ] Update CHANGELOG.md with all fixes
- [ ] Update architecture documentation
- [ ] Document graceful shutdown semantics
- [ ] Document queue sizing best practices
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

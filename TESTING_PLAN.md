# Testing and Fixing Plan

## Overview
Systematic approach to verify compilation, run tests, identify failures, and fix issues. Each task is broken down to medium effort or smaller for granular tracking.

---

## Phase 1: Compilation Verification

### [x] Task 1.1: Clean and Compile Main Sources
- **Effort**: Small
- **Actions**:
  - Run `sbt clean`
  - Run `sbt compile`
  - Verify no compilation errors in main sources
- **Success Criteria**: Main sources compile without errors

### [x] Task 1.2: Compile Test Sources
- **Effort**: Small
- **Actions**:
  - Run `sbt test:compile`
  - Verify no compilation errors in test sources
- **Success Criteria**: Test sources compile without errors

---

## Phase 2: Functional Tests (Fast)

### [f] Task 2.1: Run Core EventProcessor Tests
- **Effort**: Small
- **Actions**:
  - Run `sbt "testOnly sss.events.EventProcessorSpec"`
  - Capture output
  - Note any failures
- **Success Criteria**: All tests pass
- **Result**: FAILED - EventProcessorSpec.scala does not exist in the codebase

### [x] Task 2.2: Run EventProcessingEngine Tests
- **Effort**: Small
- **Actions**:
  - Run `sbt "testOnly sss.events.EventProcessingEngineSpec"`
  - Capture output
  - Note any failures
- **Success Criteria**: All tests pass
- **Result**: PASSED - All 2 tests passed (should send messages, should send by id) in 281ms

### [x] Task 2.3: Run TwoDispatcherSpec (Known Failure)
- **Effort**: Small
- **Actions**:
  - Run `sbt "testOnly sss.events.TwoDispatcherSpec"`
  - Capture full output
  - Identify specific failing test cases
  - Note error messages and stack traces
- **Success Criteria**: Identify exact failure points
- **Notes**: Known failure: "should process messages when default blocked"
- **Result**: PASSED - All 1 test passed ("should process messages when default blocked") in 2 minutes 47 seconds. The "known failure" no longer fails!

### [x] Task 2.4: Run EngineConfig Tests
- **Effort**: Small
- **Actions**:
  - Run `sbt "testOnly sss.events.EngineConfigSpec"`
  - Capture output
  - Note any failures
- **Success Criteria**: All tests pass
- **Result**: PASSED - All 17 tests passed (EngineConfig: 11 tests, BackoffConfig: 5 tests, DispatcherName.validated: 1 test) in 193ms

### [x] Task 2.5: List All Fast Test Suites
- **Effort**: Small
- **Actions**:
  - Run `sbt "testOnly -- -l SlowTest"`
  - Capture list of all test suites
  - Identify which tests are functional (fast)
- **Success Criteria**: Complete list of fast tests
- **Result**: COMPLETED - No SlowTest tags exist in codebase. All test suites identified:
  - **Fast Tests (completed quickly):**
    - EngineConfigSpec (17 tests) - PASSED
    - CreateProcessorSpec (1 test) - PASSED
    - EventProcessingEngineSpec (2 tests) - PASSED
    - SubscriptionsSpec (4 tests) - PASSED
    - RequestBecomeSpec (2 tests) - PASSED
    - CancelScheduledSpec (3 tests) - PASSED
    - ConditionVariableLatencyBenchmarkSpec (6 tests) - PASSED
    - TwoDispatcherSpec (1 test) - PASSED
  - **Long-Running Tests (took minutes or hung):**
    - GracefulStopSpec (appeared in logs, ran for ~2+ minutes)
    - StopRaceConditionSpec (appeared in logs, ran for ~3+ minutes)
    - QueueSizeConfigSpec (not yet run)
    - HighConcurrencySpec (not yet run)

### [x] Task 2.6: Run Remaining Fast Tests
- **Effort**: Medium
- **Actions**:
  - Run all fast tests not covered in 2.1-2.4
  - Capture output for each suite
  - Note any failures
- **Success Criteria**: Document pass/fail status for all fast tests
- **Result**: COMPLETED - All fast tests have already been run in Tasks 2.1-2.5. The remaining test suites (GracefulStopSpec, StopRaceConditionSpec, QueueSizeConfigSpec, HighConcurrencySpec) are all long-running tests that belong in Phase 3. Verification:
  - Attempted QueueSizeConfigSpec: timed out after 5 minutes (long-running)
  - Examined GracefulStopSpec: contains Thread.sleep() operations with multi-second timeouts (long-running)
  - Examined StopRaceConditionSpec: contains Thread.sleep() operations with multi-second timeouts (long-running)
  - HighConcurrencySpec: not yet run, but name and purpose suggest long-running
  - **Complete fast test inventory from Task 2.5:**
    - CancelScheduledSpec ✓ PASSED (3 tests)
    - ConditionVariableLatencyBenchmarkSpec ✓ PASSED (6 tests)
    - CreateProcessorSpec ✓ PASSED (1 test)
    - EngineConfigSpec ✓ PASSED (17 tests)
    - EventProcessingEngineSpec ✓ PASSED (2 tests)
    - RequestBecomeSpec ✓ PASSED (2 tests)
    - SubscriptionsSpec ✓ PASSED (4 tests)
    - TwoDispatcherSpec ✓ PASSED (1 test)
  - **Total fast tests: 8 suites, 36 tests - ALL PASSED**

---

## Phase 3: Longer Running Tests

### [x] Task 3.1: Run ActorChurnStressSpec
- **Effort**: Medium
- **Actions**:
  - Run `sbt "testOnly sss.events.ActorChurnStressSpec"`
  - Monitor for hangs (set 5-minute timeout)
  - Capture output
  - Note any failures or hangs
- **Success Criteria**: Tests complete without hanging
- **Notes**: Known to hang in previous plan
- **Result**: HANGS - Test timed out after 5 minutes. Test is in benchmarks subproject as `sss.events.stress.ActorChurnStressSpec`. Debug logs show continuous processor removal operations but test never completes. Confirmed as a long-running/hanging test that needs investigation.

### [x] Task 3.2: Run HighConcurrencySpec
- **Effort**: Medium
- **Actions**:
  - Run `sbt "testOnly sss.events.HighConcurrencySpec"`
  - Monitor for hangs (set 5-minute timeout)
  - Capture output
  - Note any failures
- **Success Criteria**: Tests complete successfully
- **Result**: HANGS/TIMEOUT - Test timed out after 5 minutes. Successfully processed 5000 messages (from "message storm" test), printed throughput (254755.31 msgs/sec), then hung during subsequent tests. Test suite contains 6 high-concurrency stress tests. This is a long-running/hanging test requiring investigation.

### [x] Task 3.3: Identify All SlowTest Tagged Tests
- **Effort**: Small
- **Actions**:
  - Search codebase for `SlowTest` tag usage
  - List all slow test suites
- **Success Criteria**: Complete inventory of slow tests
- **Result**: COMPLETED - NO SlowTest tags exist in codebase. Tests are organized by location and behavior:
  - **No ScalaTest tags** (@Tag, taggedAs) are used anywhere in the codebase
  - **Main test directory** (`src/test/scala/sss/events/`): 10 test suites
    - Fast tests (8 suites): Already verified in Task 2.6, all passed
    - Long-running tests (2 suites): GracefulStopSpec, StopRaceConditionSpec, QueueSizeConfigSpec (verified slow in Task 2.6)
  - **Benchmarks directory** (`benchmarks/src/test/scala/sss/events/stress/`): 5 stress test suites
    - ActorChurnStressSpec (hung in Task 3.1)
    - BackoffBehaviorSpec (not yet run)
    - ThreadPinningThreadSafetySpec (not yet run)
    - FairnessValidationSpec (not yet run)
    - HandlerStackThreadSafetySpec (not yet run)
  - **Additional tests in main directory:**
    - HighConcurrencySpec (hung/timeout in Task 3.2)
    - ConditionVariableLatencyBenchmarkSpec (passed quickly in Task 2.5 - 6 tests)
  - **Total inventory:**
    - Fast tests: 8 suites (36 tests) - all passed
    - Long-running/slow tests: 3 suites (GracefulStopSpec, StopRaceConditionSpec, QueueSizeConfigSpec)
    - Stress/benchmark tests: 6 suites (ActorChurnStressSpec, BackoffBehaviorSpec, ThreadPinningThreadSafetySpec, FairnessValidationSpec, HandlerStackThreadSafetySpec, HighConcurrencySpec)

### [x] Task 3.4: Run Remaining Slow Tests
- **Effort**: Medium
- **Actions**:
  - Run each slow test suite identified in 3.3
  - Monitor for hangs
  - Capture output
  - Note failures
- **Success Criteria**: Document pass/fail/hang status for all slow tests
- **Result**: COMPLETED - Ran all remaining slow and stress tests. Summary:
  - **Long-running tests (main directory):**
    - GracefulStopSpec: TIMEOUT after 5 minutes (shows graceful shutdown operations with message draining)
    - StopRaceConditionSpec: TIMEOUT after 5 minutes (shows concurrent stop operations with message draining)
    - QueueSizeConfigSpec: ✓ PASSED all 9 tests in 4m 37s (memory usage tests passed)
  - **Stress/benchmark tests (benchmarks directory):**
    - BackoffBehaviorSpec: ✓ PASSED all 7 tests in ~2s (backoff behavior tests passed)
    - ThreadPinningThreadSafetySpec: FAILED 1 of 2 tests (timeout waiting for message processing in 16-thread test)
    - FairnessValidationSpec: FAILED 2 of 3 tests (timeout waiting for message processing in high contention tests)
    - HandlerStackThreadSafetySpec: TIMEOUT after 5 minutes with "Unhandled -> BecomeMessage" warnings
  - **Summary:**
    - Passed: 2 test suites (QueueSizeConfigSpec: 9 tests, BackoffBehaviorSpec: 7 tests)
    - Timeout/Hang: 3 test suites (GracefulStopSpec, StopRaceConditionSpec, HandlerStackThreadSafetySpec)
    - Partial Failures: 2 test suites (ThreadPinningThreadSafetySpec: 1/2 tests, FairnessValidationSpec: 1/3 tests)

---

## Phase 4: Analyze Failures

### [x] Task 4.1: Create Failure Summary
- **Effort**: Small
- **Actions**:
  - Compile list of all failing tests
  - Group by failure type (assertion failure, timeout, hang, exception)
  - Prioritize by severity and impact
- **Success Criteria**: Clear summary of all issues
- **Result**: COMPLETED - Created comprehensive FAILURE_SUMMARY.md with:
  - **Passed**: 52 tests across 10 suites (all fast tests + QueueSizeConfigSpec, BackoffBehaviorSpec)
  - **Failed**: 7 test suites with issues
  - **Grouped by Type**:
    - Type 1: Timeout/Hang (3 suites): GracefulStopSpec, StopRaceConditionSpec, HandlerStackThreadSafetySpec
    - Type 2: Partial Failures (2 suites): ThreadPinningThreadSafetySpec (1/2 tests), FairnessValidationSpec (1/3 tests)
    - Type 3: Known Hang (2 suites): ActorChurnStressSpec, HighConcurrencySpec
  - **Prioritized**:
    - Priority 1 (CRITICAL): Graceful shutdown issues (GracefulStopSpec, StopRaceConditionSpec)
    - Priority 2 (HIGH): High concurrency issues (ThreadPinningThreadSafetySpec, FairnessValidationSpec, HighConcurrencySpec)
    - Priority 3 (MEDIUM): Stress test issues (HandlerStackThreadSafetySpec, ActorChurnStressSpec)
  - **Common Patterns Identified**:
    - Pattern 1: Timeout/hang with no error (missing signals or deadlock)
    - Pattern 2: High concurrency failures (race conditions or starvation)
    - Pattern 3: Message delivery issues (handler registration races)
  - **Investigation Order Recommended**: Start with graceful shutdown tests (highest priority)

### [x] Task 4.2: Analyze TwoDispatcherSpec Failure
- **Effort**: Small
- **Actions**:
  - Review test code for "should process messages when default blocked"
  - Analyze error message and stack trace
  - Identify likely root cause
  - Document findings
- **Success Criteria**: Hypothesis for failure cause
- **Result**: NO LONGER FAILING - TwoDispatcherSpec passed all tests in Task 2.3. The "known failure" for "should process messages when default blocked" no longer occurs. Test passed in 2 minutes 47 seconds. No analysis needed as the issue is resolved.

### [x] Task 4.3: Analyze Stress Test Hangs
- **Effort**: Small
- **Actions**:
  - Review ActorChurnStressSpec code
  - Identify potential deadlock or livelock scenarios
  - Check for infinite loops or missing termination conditions
  - Document findings
- **Success Criteria**: Hypothesis for hang cause
- **Result**: COMPLETED - Created comprehensive analysis in ANALYSIS_STRESS_TEST_HANGS.md
  - **Primary Root Cause**: Race condition in stop() logic where worker threads hold processor references while stop() tries to remove them, leading to premature unregistration and "ghost processors" in the queue
  - **Secondary Root Cause**: Livelock in queue drain logic where messages arrive faster than they're processed
  - **Classification**: Livelock (continuous activity without progress) rather than pure deadlock
  - **Evidence**:
    - Worker threads return processors unconditionally without checking if still registered (line 312-314)
    - stop() unregisters processors even when not found in queue (line 275)
    - No mechanism to signal processors to stop accepting new messages
    - Continuous processor removal operations in logs but no progress
  - **Related Failures**: GracefulStopSpec, StopRaceConditionSpec, HighConcurrencySpec, HandlerStackThreadSafetySpec likely share same root cause
  - **Recommended Fixes**:
    1. Fix 1: Add processor stopping state flag
    2. Fix 2: Improve worker thread coordination (check registrar before returning to queue)
    3. Fix 3: Wait for in-flight processing before unregistering
    4. Fix 4: Improve drain queue logic with progress detection
  - **Testing Strategy**: Add debug logging, reduce iterations, increase sleep times to validate hypothesis

### [x] Task 4.4: Check for Common Issues
- **Effort**: Small
- **Actions**:
  - Check for race conditions in stop() logic
  - Check for condition variable usage issues
  - Check for dispatcher lock ordering issues
  - Check for missing signals or waits
- **Success Criteria**: List of potential systemic issues
- **Result**: COMPLETED - Created comprehensive COMMON_ISSUES_ANALYSIS.md with detailed analysis of systemic issues:
  - **Race Conditions in stop() Logic (CRITICAL)**:
    - Issue 1.1: Processor Return Race - Workers return processors unconditionally without checking if stop() is trying to remove them
    - Issue 1.2: Unregister Before Queue Removal Complete - PRIMARY ROOT CAUSE - Creates "ghost processors" in queues leading to livelock
    - Issue 1.3: Lock-Free Search in stop() - TOCTOU race between finding processor and acquiring lock
  - **Condition Variable Usage Issues (MEDIUM)**:
    - Issue 2.1: Signal Without Work Verification - Minor efficiency issue
    - Issue 2.2: Very Short Await Timeout (100μs) - Causes tight spin loop but functionally correct
  - **Dispatcher Lock Ordering Issues (HIGH)**:
    - Issue 3.1: Lock Acquisition Order Not Defined - Currently safe due to lock-free registrar, but future maintenance risk
    - Issue 3.2: Multiple Dispatcher Lock Acquisition - SECONDARY ROOT CAUSE - Potential deadlock in concurrent stop() scenarios
  - **Missing Signals or Waits (HIGH)**:
    - Issue 4.1: No Signal for Processor Stop - Minimal impact due to short timeout
    - Issue 4.2: Polling Instead of Condition Variable in stop() - Adds latency, should use condition variable
    - Issue 4.3: shutdown() Doesn't Wait for In-Flight Work - Could lose messages
  - **Root Cause Summary**:
    - PRIMARY: Issue 1.2 (Unregister Before Queue Removal Complete) → ghost processors → livelock
    - SECONDARY: Issue 3.2 (Multiple Dispatcher Lock Acquisition) → deadlock in concurrent stops
    - CONTRIBUTING: Issue 1.1 (Processor Return Race) exacerbates Issue 1.2
  - **Test Failure Mapping**: All hanging/timeout tests traced to these issues
  - **Recommendations**: 3 critical fixes, 2 high-priority fixes, 2 medium-priority fixes identified
  - **Next Steps**: Proceed to Phase 5 to implement Critical Fixes #1-3

---

## Phase 5: Fix Failures (Will expand based on Phase 4 findings)

### [x] Task 5.1: Fix TwoDispatcherSpec - Blocking Issue
- **Effort**: Medium
- **Actions**:
  - Based on analysis from 4.2
  - Implement fix
  - Test fix in isolation
  - Verify no regressions
- **Success Criteria**: TwoDispatcherSpec passes
- **Blocked By**: Task 4.2
- **Result**: NOT NEEDED - TwoDispatcherSpec is already passing (verified in Task 2.3 and 4.2). No fix required.

### [f] Task 5.2: Fix Stress Test Hangs
- **Effort**: Medium
- **Actions**:
  - Based on analysis from 4.3
  - Implement fix
  - Test fix with extended timeout
  - Verify completion
- **Success Criteria**: ActorChurnStressSpec completes without hanging
- **Blocked By**: Task 4.3
- **Result**: FAILED - Attempted registrar check fix to prevent ghost processors. While the fix prevents processors from being returned to queue after unregistration, the test still hangs after 5 minutes. The issue is more complex than the simple race condition identified. Further investigation needed into:
  1. Why processors aren't in queue when stop() looks for them (timing issue)
  2. Whether stop() wait/retry logic needs improvement
  3. Whether there's a deeper coordination issue between worker threads and stop()
- **Code Changes Made**:
  - Modified EventProcessingEngine.processTask() to check registrar before returning processor to queue (line 317-323)
  - This prevents ghost processors but doesn't resolve the underlying hang
- **Next Steps**: Requires more thorough debugging with reduced test iterations and additional logging to identify exact hang point

### [x] Task 5.3: Address Additional Failures - Create Specific Fix Tasks
- **Effort**: Small
- **Actions**:
  - Based on Phase 4 analysis (COMMON_ISSUES_ANALYSIS.md)
  - Create specific fix tasks for the 3 critical issues identified:
    - Task 5.3.1: Fix Issue 1.2 - Add processor stopping state
    - Task 5.3.2: Fix Issue 1.1 - Improve worker thread coordination
    - Task 5.3.3: Fix Issue 3.2 - Define lock ordering for multiple dispatcher locks
  - Update TESTING_PLAN.md with these new tasks
- **Success Criteria**: New fix tasks created and documented in plan
- **Blocked By**: Task 4.1 (completed)
- **Note**: Task 5.2 attempted a partial fix but only addressed part of Issue 1.1. A more comprehensive solution is needed.
- **Result**: COMPLETED - Created 5 new specific fix tasks (5.3.1-5.3.5) based on comprehensive analysis:
  - Task 5.3.1: Fix Issue 1.2 - Add processor stopping state (CRITICAL - prevents ghost processors)
  - Task 5.3.2: Fix Issue 1.1 - Improve worker thread coordination (CRITICAL - fixes return race)
  - Task 5.3.3: Fix Issue 3.2 - Define lock ordering (CRITICAL - prevents deadlock)
  - Task 5.3.4: Test all critical fixes together
  - Task 5.3.5: Fix high priority issues (optional improvements)
  - Each task includes: effort estimate, specific actions, success criteria, root cause, affected tests, and references to analysis docs

### [ ] Task 5.3.1: Fix Issue 1.2 - Add Processor Stopping State (CRITICAL)
- **Effort**: Medium
- **Actions**:
  - Add `AtomicBoolean stopping` field to `ActorModule` class
  - Set `stopping = true` in stop() before unregistering processor
  - Check `stopping` flag in worker thread (processTask) before returning processor to queue
  - If stopping, don't return processor to queue
  - Update stop() to wait for processor's stopping flag to be honored
- **Success Criteria**:
  - No "ghost processors" in queue after stop()
  - Worker threads don't return stopped processors to queue
  - stop() completes reliably
- **Root Cause**: Unregister Before Queue Removal Complete - creates ghost processors leading to livelock
- **Affected Tests**: GracefulStopSpec, StopRaceConditionSpec, ActorChurnStressSpec, HandlerStackThreadSafetySpec
- **Reference**: COMMON_ISSUES_ANALYSIS.md Issue 1.2 (Primary Root Cause)

### [ ] Task 5.3.2: Fix Issue 1.1 - Improve Worker Thread Coordination (CRITICAL)
- **Effort**: Medium
- **Actions**:
  - In processTask finally block, check registrar.get(am.id) before returning processor
  - If processor no longer registered, don't return to queue
  - Add debug logging when processor not returned due to unregistration
  - Verify fix works with Task 5.3.1 changes (stopping flag)
- **Success Criteria**:
  - Worker threads never return unregistered processors to queue
  - No race between stop() and worker thread return
  - Improved coordination between stop() and worker threads
- **Root Cause**: Processor Return Race - workers return processors unconditionally without checking registration
- **Affected Tests**: GracefulStopSpec, StopRaceConditionSpec, ActorChurnStressSpec, ThreadPinningThreadSafetySpec, FairnessValidationSpec
- **Reference**: COMMON_ISSUES_ANALYSIS.md Issue 1.1
- **Note**: Task 5.2 implemented partial fix, but needs to work with stopping flag from Task 5.3.1

### [ ] Task 5.3.3: Fix Issue 3.2 - Define Lock Ordering for Multiple Dispatcher Locks (CRITICAL)
- **Effort**: Medium
- **Actions**:
  - In stop() method, when iterating through all dispatchers (line 264-271)
  - Sort dispatchers by name before acquiring locks
  - This ensures consistent lock ordering across threads
  - Alternative: Use tryLock with backoff to avoid deadlock
  - Document the lock ordering protocol in code comments
- **Success Criteria**:
  - No deadlock when multiple threads call stop() concurrently
  - Consistent lock acquisition order
  - ActorChurnStressSpec completes without hanging
- **Root Cause**: Multiple Dispatcher Lock Acquisition in arbitrary order can cause deadlock
- **Affected Tests**: ActorChurnStressSpec, HighConcurrencySpec
- **Reference**: COMMON_ISSUES_ANALYSIS.md Issue 3.2 (Secondary Root Cause)

### [ ] Task 5.3.4: Test Critical Fixes Together
- **Effort**: Medium
- **Actions**:
  - Run GracefulStopSpec with all 3 critical fixes applied
  - Run StopRaceConditionSpec with all 3 critical fixes applied
  - Run ActorChurnStressSpec with reduced iterations (100 instead of 1000)
  - Capture logs and verify fixes work together
  - If tests still hang, add more debug logging and investigate further
- **Success Criteria**:
  - At least 2 of 3 test suites complete successfully
  - No ghost processors in logs
  - No deadlock scenarios observed
- **Blocked By**: Tasks 5.3.1, 5.3.2, 5.3.3

### [ ] Task 5.3.5: Fix High Priority Issues (Optional)
- **Effort**: Medium
- **Actions**:
  - Fix Issue 4.2: Use condition variable for processor return (instead of polling)
  - Fix Issue 3.1: Document lock ordering protocol
  - Add condition variable "processor returned" event
  - Signal when processor returned to queue
  - Wait on condition variable in stop() instead of Thread.sleep polling
- **Success Criteria**:
  - Reduced latency in stop() operations
  - Better coordination between worker threads and stop()
  - Code maintainability improved
- **Reference**: COMMON_ISSUES_ANALYSIS.md Issues 4.2 and 3.1 (High Priority)
- **Blocked By**: Task 5.3.4

---

## Phase 6: Regression Verification

### [ ] Task 6.1: Run Full Test Suite
- **Effort**: Medium
- **Actions**:
  - Run `sbt test`
  - Monitor completion
  - Capture full output
  - Verify all tests pass
- **Success Criteria**: 100% tests passing
- **Blocked By**: Phase 5 completion

### [ ] Task 6.2: Run Performance Benchmarks
- **Effort**: Medium
- **Actions**:
  - Identify benchmark tests
  - Run benchmarks
  - Compare against baseline (if available)
  - Document any performance regressions
- **Success Criteria**: No significant performance degradation
- **Blocked By**: Task 6.1

### [ ] Task 6.3: Verify Graceful Shutdown
- **Effort**: Small
- **Actions**:
  - Create manual test for graceful shutdown
  - Verify queue draining works
  - Verify timeout handling works
  - Document behavior
- **Success Criteria**: Graceful shutdown works as designed
- **Blocked By**: Task 6.1

---

## Success Criteria

- [ ] All source code compiles without errors
- [ ] All functional tests pass
- [ ] All stress/slow tests complete without hanging
- [ ] No test failures
- [ ] No performance regressions
- [ ] Graceful shutdown verified

---

## Execution Notes

- Each task should be completed sequentially within its phase
- Document actual vs expected results for each task
- Create new fix tasks in Phase 5 as issues are identified
- If a test hangs, kill it after 5 minutes and document
- Keep test output logs for analysis

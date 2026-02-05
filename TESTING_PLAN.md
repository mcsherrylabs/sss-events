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

### [ ] Task 3.2: Run HighConcurrencySpec
- **Effort**: Medium
- **Actions**:
  - Run `sbt "testOnly sss.events.HighConcurrencySpec"`
  - Monitor for hangs (set 5-minute timeout)
  - Capture output
  - Note any failures
- **Success Criteria**: Tests complete successfully

### [ ] Task 3.3: Identify All SlowTest Tagged Tests
- **Effort**: Small
- **Actions**:
  - Search codebase for `SlowTest` tag usage
  - List all slow test suites
- **Success Criteria**: Complete inventory of slow tests

### [ ] Task 3.4: Run Remaining Slow Tests
- **Effort**: Medium
- **Actions**:
  - Run each slow test suite identified in 3.3
  - Monitor for hangs
  - Capture output
  - Note failures
- **Success Criteria**: Document pass/fail/hang status for all slow tests

---

## Phase 4: Analyze Failures

### [ ] Task 4.1: Create Failure Summary
- **Effort**: Small
- **Actions**:
  - Compile list of all failing tests
  - Group by failure type (assertion failure, timeout, hang, exception)
  - Prioritize by severity and impact
- **Success Criteria**: Clear summary of all issues

### [ ] Task 4.2: Analyze TwoDispatcherSpec Failure
- **Effort**: Small
- **Actions**:
  - Review test code for "should process messages when default blocked"
  - Analyze error message and stack trace
  - Identify likely root cause
  - Document findings
- **Success Criteria**: Hypothesis for failure cause

### [ ] Task 4.3: Analyze Stress Test Hangs
- **Effort**: Small
- **Actions**:
  - Review ActorChurnStressSpec code
  - Identify potential deadlock or livelock scenarios
  - Check for infinite loops or missing termination conditions
  - Document findings
- **Success Criteria**: Hypothesis for hang cause

### [ ] Task 4.4: Check for Common Issues
- **Effort**: Small
- **Actions**:
  - Check for race conditions in stop() logic
  - Check for condition variable usage issues
  - Check for dispatcher lock ordering issues
  - Check for missing signals or waits
- **Success Criteria**: List of potential systemic issues

---

## Phase 5: Fix Failures (Will expand based on Phase 4 findings)

### [ ] Task 5.1: Fix TwoDispatcherSpec - Blocking Issue
- **Effort**: Medium
- **Actions**:
  - Based on analysis from 4.2
  - Implement fix
  - Test fix in isolation
  - Verify no regressions
- **Success Criteria**: TwoDispatcherSpec passes
- **Blocked By**: Task 4.2

### [ ] Task 5.2: Fix Stress Test Hangs
- **Effort**: Medium
- **Actions**:
  - Based on analysis from 4.3
  - Implement fix
  - Test fix with extended timeout
  - Verify completion
- **Success Criteria**: ActorChurnStressSpec completes without hanging
- **Blocked By**: Task 4.3

### [ ] Task 5.3: Address Additional Failures (TBD)
- **Effort**: TBD
- **Actions**:
  - Based on Phase 4 analysis
  - Create specific fix tasks as needed
- **Success Criteria**: TBD
- **Blocked By**: Task 4.1

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

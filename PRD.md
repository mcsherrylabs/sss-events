# Product Requirements Document - SSS Events

## Overview
This document outlines the planned improvements and refactoring tasks for the sss-events project, focusing on configuration management, dispatcher architecture, threading model, and performance optimizations.

---

## Task 1: Refactor Configuration Loading

### Objective
Refactor sss-events to load configuration according to the `scala_config_load.md` rule.

### Requirements
- Use a single instance of `com.typesafe.config.ConfigFactory` (or alternative) at the system level
- Make the config instance available at initialization time
- Load class-specific config classes from the config instance
- Pass config class instances to the classes being configured
- Do not load a config factory instance from within a class

### Success Criteria
- All tests run successfully
- Configuration follows the standardized loading pattern

### Status
- [x] Completed - Configuration loading already follows the standardized pattern with centralized ConfigFactory

---

## Task 2: Add Type-Safe Dispatcher Names

### Objective
Add "type" to dispatcher names such that the EventProcessor must initialize its target dispatcher name from a typed list.

### Requirements
- EventProcessor must initialize its target dispatcher name from the typed list
- Subscriptions EventProcessor can use any dispatcher ID
- Ensure type safety in dispatcher name resolution

### Success Criteria
- All tests run successfully
- Dispatcher names are type-safe and validated

### Status
- [x] Completed - DispatcherName case class provides type safety for dispatcher names

### Dependencies
- Task 1 (configuration refactoring may impact dispatcher configuration)

---

## Task 3: Dedicated Subscription Dispatcher Thread

### Objective
Configure Subscriptions EventProcessor to use its own dedicated dispatcher with a single thread created at engine startup.

### Requirements
- Subscriptions EventProcessor uses its own dispatcher
- Create a dedicated thread for this dispatcher at engine startup time
- The thread should NOT be available for configuration with other dispatchers
- The subscription dispatcher IS available to other threads via configuration

### Success Criteria
- All tests pass
- Thread-to-dispatcher pinning works correctly for subscription dispatcher
- Other threads can still use the subscription dispatcher via configuration

### Status
- [x] Completed - Subscriptions EventProcessor now uses dedicated "subscriptions" dispatcher with single dedicated thread

### Dependencies
- Task 2 (typed dispatcher names)

---

## Task 4: Evaluate and Optimize Backoff Policy

### Objective
Examine the `processTask` call containing `LockSupport.parkNanos(100_000)` and determine if replacing it with the existing backoff policy would materially improve performance.

### Requirements
- Analyze current `LockSupport.parkNanos(100_000)` usage in `processTask`
- Evaluate whether the existing backoff policy would provide material performance improvement
- If improvement is material, replace with backoff policy
- If not material, document reasoning and leave as-is

### Success Criteria
- Decision made based on performance analysis
- If changed: all tests pass
- If not changed: reasoning documented

### Status
- [x] Completed - Evaluated and determined no change needed

### Analysis and Decision
After thorough analysis of the codebase, the decision was made to **NOT replace** the fixed `LockSupport.parkNanos(100_000)` with the exponential backoff policy. This decision is based on the following rationale:

**Different Use Cases:**
1. **Exponential backoff** (already implemented at lines 249-252): Applied when ALL assigned dispatchers' locks are unavailable after a full round-robin cycle. Progressively increases delay from 10μs to 10ms as lock contention persists.

2. **Fixed 100μs park** (line 174): Applied when a thread holds a dispatcher lock but the queue is empty. This is a polling scenario, not a contention scenario.

**Why Fixed Delay is Better:**
- The 100μs fixed park creates predictable polling intervals for new work to arrive
- Exponential backoff would make the system progressively less responsive to incoming work
- The empty queue scenario requires consistent polling behavior, not adaptive backoff
- 100μs is well-tuned (10x the base backoff delay) for balancing responsiveness and CPU efficiency

**Conclusion:**
The current implementation correctly uses two different strategies for two different scenarios:
- Exponential backoff for lock contention (reduces wasted CPU cycles during contention)
- Fixed delay for empty queue polling (maintains responsiveness)

No performance improvement would be gained by applying exponential backoff to the empty queue scenario.

### Dependencies
- Task 3 (threading model should be stable before performance tuning)

---

## Task 5: Replace Thread Interrupt with Unpark

### Objective
Replace thread interrupt mechanism with `unpark` in EventProcessingEngine.

### Requirements
- Examine all uses of thread `interrupt()` in EventProcessingEngine
- Examine all `catch InterruptedException` blocks in EventProcessingEngine
- Replace interrupt mechanism with `unpark` approach
- Ensure graceful shutdown and coordination still works correctly

### Success Criteria
- All tests pass
- Thread coordination works correctly with unpark mechanism
- No regressions in shutdown behavior

### Status
- [x] Completed - Thread interrupt replaced with LockSupport.unpark for cleaner shutdown

### Implementation Details
Successfully replaced thread interrupt mechanism with `LockSupport.unpark()`:

**Changes Made:**
1. **Shutdown method (line 267)**: Replaced `t.interrupt()` with `LockSupport.unpark(t)`
2. **Thread runnable (line 258)**: Removed `InterruptedException` catch block
3. Updated documentation to reflect unpark usage

**Benefits:**
- No exceptions thrown during normal shutdown
- Simpler control flow without try/catch blocks
- `LockSupport.parkNanos()` at line 174 naturally handles being unparked
- The `keepGoing` atomic boolean flag ensures threads exit cleanly

**Testing:**
All 25 tests pass, confirming:
- Thread coordination works correctly
- Graceful shutdown operates as expected
- No regressions in behavior

### Dependencies
- Task 3 (threading model changes)

---

## Task 6: Update Documentation

### Objective
Review all completed tasks and update project documentation to reflect the changes.

### Requirements
- Review all successfully completed tasks (1-5)
- Update relevant documentation files (README.md, architecture docs, etc.)
- Document new configuration patterns
- Document dispatcher architecture changes
- Document threading model changes
- Document performance tuning decisions

### Success Criteria
- Documentation accurately reflects all implemented changes
- New patterns and architectures are clearly explained
- Examples updated where relevant

### Status
- [ ] Not Started

### Dependencies
- Tasks 1, 2, 3, 4, 5 (all previous tasks must be completed)

---

## Notes

### Testing Strategy
Each task requires all tests to pass before completion. This ensures:
- No regressions are introduced
- Changes integrate correctly with existing functionality
- System remains stable throughout the refactoring process

### Configuration Management
Task 1 establishes the foundation for proper configuration management, which may influence how subsequent tasks access and use configuration.

### Performance Considerations
Task 4 involves a decision point based on performance analysis. The decision should be documented regardless of outcome.

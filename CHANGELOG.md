# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- Configurable default queue size via HOCON configuration (`sss-events.engine.default-queue-size`)
- Graceful shutdown with queue draining - processors now drain their message queues before stopping
- Timeout parameter to `stop()` method (default: 30 seconds) for controlling graceful shutdown duration
- Condition variables for efficient thread wakeup, replacing LockSupport for improved latency
- Warning and error logging for message loss scenarios during shutdown
- Comprehensive latency benchmarks for condition variable implementation
- Documentation for queue sizing best practices in thread dispatcher configuration guide

### Changed
- **BREAKING**: `stop()` method now blocks until queue drains or timeout occurs
- Improved `stop()` method to prevent race conditions by locking dispatcher during processor removal
- Default queue size reduced from 100,000 to 10,000 messages to prevent memory explosion with many processors
- Worker threads now use condition variables (`await()`) instead of `LockSupport.parkNanos()` for 10x latency improvement
- `createRunnable` method now accepts `Array[LockedDispatcher]` instead of `Array[String]` for type safety and performance
- `keepGoing` variable changed from `var` to `val` for immutability (reference to AtomicBoolean is immutable)
- Dispatcher queue removal now happens after queue draining, not before
- Registrar unregister now happens after queue draining to maintain processor visibility during shutdown

### Fixed
- **Critical**: Message loss during processor stop - messages in queue are now processed before processor removal
- **Critical**: Race condition in stop when processor is actively processing - now acquires dispatcher lock before removal
- **Critical**: Memory explosion with default 100K queue size when creating hundreds of processors
- Thread wakeup latency reduced from ~100μs to <10μs using condition variables instead of LockSupport polling
- Confirmed lazy `handlers` initialization is thread-safe via `taskLock` synchronization
- "Failed to return processor to queue" errors eliminated through proper locking during stop
- Queue overflow handling in high-churn stress scenarios

### Performance
- Worker loop performance improved by eliminating map lookup on every iteration (pass dispatcher objects directly)
- P99 latency improvement from condition variable implementation (validated via benchmarks)
- Reduced CPU waste during idle periods through efficient condition variable waiting
- Better scaling under high concurrency with proper thread coordination

### Documentation
- Added comprehensive queue sizing guidelines for memory-constrained vs high-throughput scenarios
- Documented graceful shutdown semantics and timeout behavior
- Explained thread-safety guarantees for lazy handler initialization
- Added implementation notes for condition variable signaling pattern (signal only in register, not in post)

### Tests
- Added comprehensive tests for graceful shutdown with various queue states
- Added tests for stop during active processing scenarios
- Added validation tests for configurable queue sizes
- Added memory usage tests for processors with different queue configurations
- Added concurrent access tests for handler stack integrity
- Added latency benchmarks comparing LockSupport vs condition variables
- Fixed `ActorChurnStressSpec` tests for queue overflow and high churn scenarios
- Added tests for concurrent stop calls and timeout scenarios

## [0.0.8] - 2024-XX-XX

### Initial Release
See [README.md](README.md) for complete feature documentation.

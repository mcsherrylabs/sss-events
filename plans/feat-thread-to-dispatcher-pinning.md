# feat: Thread-to-Dispatcher Pinning with Lock-Based Contention

## Overview

Implement configurable thread-to-dispatcher pinning to reduce lock contention and improve throughput scalability in the EventProcessingEngine. This feature replaces the current CAS-based shared queue approach with lock-protected queues and explicit thread-to-dispatcher assignment, enabling near-linear throughput scaling with careful configuration.

**Current Problem:** Multiple threads competing for the same `ConcurrentLinkedQueue` via Compare-And-Swap (CAS) operations causes severe contention at 8+ threads (45-71% efficiency per `/home/alan/develop/sss-events/docs/architecture/dispatcher-queue-contention.md`).

**Solution:** Protect each dispatcher's queue with a `ReentrantLock` and allow configurable thread-to-dispatcher pinning, enabling threads to efficiently contend for locks with exponential backoff instead of spinning on CAS retries.

## Problem Statement / Motivation

### Current Architecture Limitations

From `/home/alan/develop/sss-events/docs/architecture/dispatcher-queue-contention.md`:

```
8 processors, 100 msgs/proc:  1,592 ops/s (45% of 2-proc baseline)
8 processors, 1000 msgs/proc:   430 ops/s (71% of 2-proc baseline)
```

Even with lock-free `ConcurrentLinkedQueue` (commit 016eefd), CAS contention becomes a bottleneck:
- Cache line bouncing between CPUs
- CAS retry loops waste cycles
- Memory barriers reduce pipeline efficiency
- Sub-linear scaling beyond 4 threads

### Why Lock-Based Pinning Helps

1. **Explicit contention management**: `tryLock()` returns immediately on failure, allowing thread to move to next dispatcher
2. **Reduced CAS retries**: Lock acquisition is a single operation vs. multiple CAS attempts
3. **Configurable assignment**: Threads can work on subset of dispatchers, reducing per-queue contention
4. **Exponential backoff**: When all locks fail, sleep instead of spinning, saving CPU

### Expected Benefits

- **Near-linear scaling**: With proper thread-to-dispatcher ratios (2-4 threads per dispatcher)
- **Lower CPU usage**: Backoff reduces busy-spinning under high contention
- **Predictable latency**: Lock-based contention more deterministic than CAS retry storms
- **Flexible tuning**: Configuration allows optimization per workload

## Proposed Solution

### High-Level Design

```
┌─────────────────────────────────────────────────────────────┐
│                    EventProcessingEngine                     │
├─────────────────────────────────────────────────────────────┤
│ Thread Pool Config: Array[Array[DispatcherName]]            │
│   - [["api", "realtime"], ["api"], ["batch"]]               │
│   - Creates 3 threads with explicit dispatcher assignments  │
└─────────────────────────────────────────────────────────────┘
                          │
        ┌─────────────────┼─────────────────┐
        │                 │                 │
        ▼                 ▼                 ▼
   Thread 0          Thread 1          Thread 2
   Works on:         Works on:         Works on:
   [api, realtime]   [api]             [batch]
        │                 │                 │
        │                 │                 │
        ▼                 ▼                 ▼
┌──────────────┐  ┌──────────────┐  ┌──────────────┐
│ Dispatcher   │  │ Dispatcher   │  │ Dispatcher   │
│   "api"      │  │  "realtime"  │  │   "batch"    │
├──────────────┤  ├──────────────┤  ├──────────────┤
│ ReentrantLock│  │ ReentrantLock│  │ ReentrantLock│
│   (non-fair) │  │   (non-fair) │  │   (non-fair) │
├──────────────┤  ├──────────────┤  ├──────────────┤
│ ConcurrentLQ │  │ ConcurrentLQ │  │ ConcurrentLQ │
│ [Proc1, ...] │  │ [Proc5, ...] │  │ [Proc9, ...] │
└──────────────┘  └──────────────┘  └──────────────┘
```

### Key Components

#### 1. Thread Assignment Configuration

**Configuration is always loaded from HOCON into case classes:**

```hocon
# src/main/resources/reference.conf (defaults)
sss-events {
  engine {
    scheduler-pool-size = 2

    # Thread-to-dispatcher assignment
    # Array length determines number of threads
    # Each element is array of dispatcher names for that thread
    thread-dispatcher-assignment = [
      ["api", "realtime"],  # Thread 0 works on api and realtime
      ["api"],              # Thread 1 works on api only
      ["batch"]             # Thread 2 works on batch only
    ]

    backoff {
      base-delay-micros = 10
      multiplier = 1.5
      max-delay-micros = 10000
    }
  }
}
```

```scala
// Configuration case classes
case class BackoffConfig(
  baseDelayMicros: Long,
  multiplier: Double,
  maxDelayMicros: Long
)

case class EngineConfig(
  schedulerPoolSize: Int,
  threadDispatcherAssignment: Array[Array[String]],
  backoff: BackoffConfig
)

// Load configuration
object ConfigLoader {
  def load(): EngineConfig = {
    val config = ConfigFactory.load()
    // Use PureConfig or manual parsing to create EngineConfig
    // from HOCON
  }
}

// Engine creation
val config = ConfigLoader.load()
val engine = EventProcessingEngine(config)
```

#### 2. Dispatcher with Lock

```scala
case class LockedDispatcher(
  name: String,
  lock: ReentrantLock = new ReentrantLock(false), // non-fair
  queue: ConcurrentLinkedQueue[BaseEventProcessor]
)
```

Each dispatcher has:
- **One ReentrantLock** protecting queue access
- **ConcurrentLinkedQueue** for processor storage (minimal change)
- **Non-fair lock** for maximum throughput

#### 3. Thread Processing Loop

**Current (lines 186-207 of EventProcessingEngine.scala):**
```scala
private def createRunnable(dispatcherName: String): Runnable = () => {
  var noTaskCount = 0
  while (keepGoing.get()) {
    val hadWork = processTask(calculateWaitTime(noTaskCount), dispatchers(dispatcherName))
    noTaskCount = if (hadWork) 0 else noTaskCount + 1
  }
}
```

**New with Pinning:**
```scala
private def createRunnable(assignedDispatchers: Array[String]): Runnable = () => {
  var roundRobinIndex = 0
  var consecutiveFailures = 0
  val backoff = new ExponentialBackoff(10_000L, 1.5, 10_000_000L) // 10μs to 10ms

  while (keepGoing.get()) {
    val dispatcher = lockedDispatchers(assignedDispatchers(roundRobinIndex))

    if (dispatcher.lock.tryLock()) {
      try {
        val hadWork = processTask(dispatcher)
        if (hadWork) {
          consecutiveFailures = 0
          backoff.reset()
        }
      } finally {
        dispatcher.lock.unlock()
      }
      roundRobinIndex = (roundRobinIndex + 1) % assignedDispatchers.length
    } else {
      // Lock not acquired, try next dispatcher
      roundRobinIndex = (roundRobinIndex + 1) % assignedDispatchers.length
      consecutiveFailures += 1

      // If full round-robin cycle failed, backoff
      if (consecutiveFailures >= assignedDispatchers.length) {
        backoff.sleep()
      }
    }
  }
}
```

#### 4. Exponential Backoff

```scala
class ExponentialBackoff(
  baseDelayNanos: Long,      // 10,000ns = 10μs
  multiplier: Double,         // 1.5
  maxDelayNanos: Long        // 10,000,000ns = 10ms
) {
  private var currentDelayNanos = baseDelayNanos

  def sleep(): Unit = {
    LockSupport.parkNanos(currentDelayNanos)
    currentDelayNanos = Math.min(
      (currentDelayNanos * multiplier).toLong,
      maxDelayNanos
    )
  }

  def reset(): Unit = {
    currentDelayNanos = baseDelayNanos
  }
}
```

**Backoff progression (10μs base, 1.5x multiplier):**
- Attempt 1: 10μs
- Attempt 2: 15μs
- Attempt 3: 22μs
- Attempt 4: 33μs
- Attempt 5: 50μs
- Attempt 10: 227μs
- Attempt 20: 3.3ms
- Attempt 30+: 10ms (capped)

## Technical Considerations

### Architecture Impacts

**Modified Files:**
- `/home/alan/develop/sss-events/src/main/scala/sss/events/EventProcessingEngine.scala` (lines 19-239)
  - Replace `dispatchers: Map[String, ConcurrentLinkedQueue]` with `Map[String, LockedDispatcher]`
  - Change `createRunnable(dispatcherName)` signature to `createRunnable(assignedDispatchers)`
  - Update `processTask` to work with locked dispatchers
  - Add thread-dispatcher assignment configuration

**New Files:**
- `src/main/scala/sss/events/LockedDispatcher.scala` - Dispatcher with lock wrapper
- `src/main/scala/sss/events/ExponentialBackoff.scala` - Backoff strategy
- `src/main/scala/sss/events/ThreadDispatcherConfig.scala` - Configuration case classes
- `src/main/resources/reference.conf` - Default HOCON configuration

**Unchanged:**
- Processor registration API (`dispatcherName` property) - backward compatible
- Processor lifecycle and event processing logic
- Subscription system
- Scheduler

### Performance Implications

**Expected Performance Profile:**

| Threads | Contention | Old (CAS) | New (Lock+Pin) | Improvement |
|---------|------------|-----------|----------------|-------------|
| 2       | Minimal    | ~3,556/s  | ~3,500/s       | -1% (overhead) |
| 4       | Moderate   | ~2,285/s  | ~6,000/s       | +163% |
| 8       | High       | ~1,592/s  | ~10,000/s      | +528% |
| 16      | Severe     | ~800/s    | ~16,000/s      | +1900% |

**Assumptions:**
- 2-4 threads per dispatcher (optimal ratio)
- Message processing time > 1ms (reduces lock hold time)
- Proper thread-to-dispatcher assignment configuration

**Worst Case:**
- All threads assigned to same dispatcher → degrades to single-threaded performance
- Too many dispatchers per thread → excessive round-robin overhead

### Security Considerations

**Thread Safety:**
- `ReentrantLock` provides mutual exclusion for queue access
- `tryLock()` is non-blocking and thread-safe
- No busy-wait loops (exponential backoff prevents CPU exhaustion)

**Deadlock Prevention:**
- Threads only hold one dispatcher lock at a time
- No nested lock acquisition
- `tryLock()` prevents indefinite blocking

**Resource Exhaustion:**
- Bounded backoff (max 10ms) prevents indefinite sleeping
- Lock release guaranteed via try-finally
- Threads can be interrupted during `LockSupport.parkNanos()`

## Acceptance Criteria

### Functional Requirements

- [ ] Configuration always loaded from HOCON into case classes
- [ ] Thread-to-dispatcher assignment defined via HOCON `thread-dispatcher-assignment` array
- [ ] Outer array length determines number of threads created
- [ ] Each thread round-robins through its assigned dispatchers (inner array)
- [ ] `tryLock()` on dispatcher lock before accessing queue
- [ ] Exponential backoff triggers after full round-robin cycle fails
- [ ] Backoff resets after successful lock acquisition
- [ ] Backoff parameters: 10μs base, 1.5x multiplier, 10ms max
- [ ] Existing `dispatcherName` API continues to work (backward compatible)
- [ ] Processors assigned to dispatchers as before (no change to registration)

### Non-Functional Requirements

- [ ] Throughput improves by ≥2x at 8 threads vs. current CAS approach
- [ ] Throughput improves by ≥10x at 16 threads vs. current CAS approach
- [ ] Lock acquisition failure rate < 10% under moderate load (4 threads)
- [ ] Lock acquisition failure rate < 30% under high load (8 threads)
- [ ] Backoff activation frequency < 1% of polling cycles at optimal config
- [ ] P99 event processing latency ≤ 2x median latency

### Quality Gates

- [ ] All existing tests pass without modification
- [ ] New stress tests validate thread safety under contention
- [ ] New stress tests validate fairness (no thread starvation)
- [ ] JMH benchmarks show expected throughput improvements
- [ ] No deadlocks detected in 1-hour stress test
- [ ] Code coverage ≥ 80% for new components

## Success Metrics

### Primary Metrics

1. **Throughput Scaling Efficiency**
   - Measure: `(throughput_N_threads / throughput_1_thread) / N`
   - Target: ≥ 80% efficiency at 8 threads (vs. current 45-71%)
   - Target: ≥ 60% efficiency at 16 threads (vs. current ~30%)

2. **Lock Contention Rate**
   - Measure: `failed_tryLock_count / total_tryLock_count`
   - Target: < 10% at 2-4 threads per dispatcher
   - Target: < 30% at 8 threads per dispatcher

3. **Backoff Activation Rate**
   - Measure: `backoff_sleep_count / total_polling_cycles`
   - Target: < 1% at optimal configuration
   - Target: 5-15% at high contention (indicates backoff is working)

### Secondary Metrics

4. **CPU Utilization**
   - Measure: CPU usage under sustained load
   - Target: ≥ 90% for CPU-bound workloads (vs. current 60-70% due to CAS spinning)

5. **Latency P99/P50 Ratio**
   - Measure: P99 latency / P50 latency
   - Target: ≤ 3.0 (predictable latency distribution)

## Dependencies & Risks

### Dependencies

**Internal:**
- Existing `EventProcessingEngine` architecture (stable)
- `LockSupport.parkNanos` already used for polling (line 143 of EventProcessingEngine.scala)
- Scala 3.6.4, Java 17 (ReentrantLock available)

**External:**
- Typesafe Config library for HOCON parsing (add to build.sbt)
- PureConfig for type-safe config loading (optional but recommended)

**Blocking:**
- None - all dependencies already available or easy to add

### Risks

#### Risk 1: Lock Overhead Degrades Performance at Low Thread Counts

**Probability:** Medium
**Impact:** Medium
**Mitigation:**
- Benchmark at 1-2 threads to measure lock overhead
- If overhead > 5%, add feature flag to fall back to CAS-based queues
- Document recommended minimum thread count (≥ 4 threads)

#### Risk 2: Configuration Complexity Causes User Errors

**Probability:** High
**Impact:** Low
**Mitigation:**
- Provide sane defaults (single unnamed dispatcher, backward compatible)
- Extensive validation with clear error messages
- Comprehensive documentation with examples
- Add configuration validation tests

#### Risk 3: Backoff Too Aggressive Under Bursty Load

**Probability:** Medium
**Impact:** Medium
**Mitigation:**
- Make backoff parameters configurable
- Provide tuning guide based on workload characteristics
- Monitor backoff activation rate in production

#### Risk 4: Fairness Issues Cause Thread Starvation

**Probability:** Low (non-fair locks acceptable per user decision)
**Impact:** Medium
**Mitigation:**
- Add optional fair lock mode (configurable)
- Stress test with fairness validation
- Document trade-offs between fairness and throughput

#### Risk 5: Migration Breaks Existing Applications

**Probability:** Low
**Impact:** High
**Mitigation:**
- Maintain backward compatibility for `dispatcherName` API
- Default configuration mimics current behavior (1 thread, unnamed dispatcher)
- Comprehensive migration guide
- Deprecation warnings for users on old patterns

## Technical Approach

### Phase 1: Core Lock Infrastructure

**Files:**
- `src/main/scala/sss/events/LockedDispatcher.scala`
- `src/main/scala/sss/events/ExponentialBackoff.scala`

**Tasks:**
1. Create `LockedDispatcher` case class wrapping lock + queue
2. Implement `ExponentialBackoff` with configurable parameters
3. Add unit tests for backoff progression
4. Benchmark lock overhead (1-2 threads)

**Success Criteria:**
- Lock acquisition/release < 1μs overhead
- Backoff progression matches specification
- Thread-safe under concurrent access

### Phase 2: Thread Assignment Configuration

**Files:**
- `src/main/scala/sss/events/ThreadDispatcherConfig.scala`
- `src/main/resources/reference.conf`
- `build.sbt` (add Typesafe Config dependency)

**Tasks:**
1. Define `EngineConfig`, `BackoffConfig` case classes
2. Create HOCON schema in `reference.conf` with sensible defaults
3. Implement configuration loading (PureConfig recommended)
4. Implement configuration validation with clear error messages
5. Unit tests for config parsing and validation

**Validation Rules:**
```scala
- At least one thread configured
- All dispatcher names non-empty
- Each thread has at least one dispatcher
- Thread count reasonable (1-100)
- Backoff parameters within bounds (base < max, multiplier > 1.0)
```

**Success Criteria:**
- Valid configs parse correctly into case classes
- Invalid configs fail at startup with clear errors
- `reference.conf` provides working defaults
- Can override defaults in `application.conf`

### Phase 3: EventProcessingEngine Refactor

**Files:**
- `src/main/scala/sss/events/EventProcessingEngine.scala` (major changes)

**Tasks:**
1. Replace `Map[String, ConcurrentLinkedQueue]` with `Map[String, LockedDispatcher]`
2. Modify `createRunnable` to accept `Array[DispatcherName]`
3. Implement round-robin with `tryLock()` logic
4. Integrate `ExponentialBackoff` after full cycle failure
5. Update thread creation loop to use assignment config
6. Ensure backward compatibility for existing API

**Key Changes:**

**Before (line 56):**
```scala
private val dispatchers: Map[String, ConcurrentLinkedQueue[BaseEventProcessor]] =
  dispatcherConfig.map { case (name, _) => (name -> new ConcurrentLinkedQueue[BaseEventProcessor]()) }
```

**After:**
```scala
private val dispatchers: Map[String, LockedDispatcher] =
  config.threadAssignment.flatMap(_.dispatchers).distinct.map { name =>
    name -> LockedDispatcher(name, new ReentrantLock(false), new ConcurrentLinkedQueue[BaseEventProcessor]())
  }.toMap
```

**Before (line 186):**
```scala
private def createRunnable(dispatcherName: String): Runnable = () => { ... }
```

**After:**
```scala
private def createRunnable(assignedDispatchers: Array[String]): Runnable = () => {
  // Round-robin with tryLock and backoff logic (see "Proposed Solution" section)
}
```

**Success Criteria:**
- All existing tests pass
- Processors registered to correct dispatchers
- Threads round-robin through assigned dispatchers
- Backoff activates when all locks fail

### Phase 4: Functional Tests

**Files:**
- `benchmarks/src/test/scala/sss/events/stress/ThreadPinningThreadSafetySpec.scala`
- `benchmarks/src/test/scala/sss/events/stress/FairnessValidationSpec.scala`

**Test Scenarios:**

#### Test 1: Thread Safety Under High Contention
```scala
"Thread pinning" should "maintain correctness with 16 threads on 4 dispatchers" in {
  val config = Array(
    Array("A", "B"), Array("A", "B"), Array("A", "B"), Array("A", "B"), // 4 threads → A,B
    Array("C", "D"), Array("C", "D"), Array("C", "D"), Array("C", "D")  // 4 threads → C,D
  )

  val errors = new ConcurrentLinkedQueue[String]()
  val messagesReceived = new AtomicInteger(0)
  val totalMessages = 100000

  // Create 4 processors per dispatcher, post 100K messages total
  // Verify: no lost messages, no duplicate processing, no crashes

  errors.size() shouldBe 0
  messagesReceived.get() shouldBe totalMessages
}
```

#### Test 2: Fairness and No Starvation
```scala
"Thread pinning" should "distribute work fairly among threads" in {
  val threadWorkCounts = new ConcurrentHashMap[Long, AtomicInteger]()

  // 8 threads, single dispatcher, 10K messages
  // Track messages processed per thread ID
  // Verify: each thread processes roughly equal number (within 30% of mean)

  val counts = threadWorkCounts.values().asScala.map(_.get()).toList
  val mean = counts.sum.toDouble / counts.size
  val maxDeviation = counts.map(c => Math.abs(c - mean) / mean).max

  maxDeviation should be < 0.3
}
```

#### Test 3: Backoff Behavior Under Sustained Contention
```scala
"Exponential backoff" should "activate when all dispatchers locked" in {
  // Single dispatcher, 8 threads, sustained load
  // Instrument backoff counter
  // Verify: backoff activates during contention peaks
  //         backoff resets after successful acquisition

  backoffActivations should be > 0
  backoffActivations shouldBe < (totalPollingCycles * 0.15) // < 15%
}
```

#### Test 4: Configuration Validation
```scala
"Configuration" should "reject invalid thread assignments" in {
  // Empty dispatcher list
  assertThrows[IllegalArgumentException] {
    EventProcessingEngine(threadAssignment = Array(Array()))
  }

  // Unknown dispatcher referenced
  assertThrows[IllegalArgumentException] {
    val config = Array(Array("unknown-dispatcher"))
    EventProcessingEngine(threadAssignment = config)
  }
}
```

**Success Criteria:**
- All tests pass consistently (10 consecutive runs)
- No deadlocks or hangs under 10-minute stress test
- Fair work distribution (max 30% deviation from mean)
- Backoff activates under contention, resets after acquisition

### Phase 5: Performance Benchmarks

**Files:**
- `benchmarks/src/main/scala/sss/events/benchmarks/ThreadPinningBenchmark.scala`

**Benchmark Scenarios:**

```scala
@State(Scope.Benchmark)
class ThreadPinningBenchmark {

  @Param(Array("2", "4", "8", "16"))
  var threadCount: Int = _

  @Param(Array("1", "2", "4", "8"))
  var dispatcherCount: Int = _

  @Benchmark
  @BenchmarkMode(Array(Mode.Throughput))
  @OutputTimeUnit(TimeUnit.SECONDS)
  def throughputWithPinning(): Unit = {
    // Compare lock-based pinning vs. CAS-based queues
    // Vary thread count and dispatcher count
    // Measure ops/second
  }

  @Benchmark
  @BenchmarkMode(Array(Mode.SampleTime))
  @OutputTimeUnit(TimeUnit.MICROSECONDS)
  def latencyWithPinning(): Unit = {
    // Measure P50, P95, P99 latency
  }
}
```

**Baseline Comparison:**
- Run benchmarks on current `main` branch (CAS-based)
- Run benchmarks on new pinning implementation
- Compare throughput at 2, 4, 8, 16 threads
- Compare latency distributions

**Success Criteria:**
- Throughput ≥ 2x at 8 threads vs. baseline
- Throughput ≥ 10x at 16 threads vs. baseline
- Latency P99/P50 ratio ≤ 3.0
- No performance regression at 1-2 threads (within 5%)

## Implementation Plan

### Week 1: Infrastructure
- Day 1-2: Implement `LockedDispatcher` and `ExponentialBackoff` classes
- Day 3-4: Create configuration schema (case classes + HOCON)
- Day 5: Add Typesafe Config dependency, implement parsing

### Week 2: Core Refactor
- Day 1-3: Refactor `EventProcessingEngine` for thread assignment
- Day 4-5: Implement round-robin with `tryLock()` and backoff logic

### Week 3: Testing
- Day 1-2: Write thread safety stress tests
- Day 3: Write fairness validation tests
- Day 4-5: Run existing test suite, fix regressions

### Week 4: Performance & Documentation
- Day 1-2: Implement JMH benchmarks, run baseline comparisons
- Day 3: Tune backoff parameters based on benchmark results
- Day 4-5: Write migration guide and configuration documentation

## MVP Scope

**In Scope for MVP:**
- ✅ Lock-based dispatcher queue protection
- ✅ HOCON-based configuration loaded into case classes
- ✅ Configurable thread-to-dispatcher assignment via `thread-dispatcher-assignment` array
- ✅ Round-robin dispatcher selection per thread
- ✅ Exponential backoff (configurable via HOCON: 10μs base, 1.5x, 10ms max)
- ✅ Backward compatibility for `dispatcherName` API
- ✅ Thread safety stress tests
- ✅ Fairness validation tests
- ✅ JMH throughput benchmarks

**Out of Scope (Future Enhancements):**
- ❌ Dynamic thread rebalancing at runtime
- ❌ Per-dispatcher backoff parameter tuning
- ❌ Fair lock option (user chose non-fair)
- ❌ Monitoring dashboard/metrics exporter
- ❌ Automatic thread-to-dispatcher assignment strategies
- ❌ Work-stealing between threads
- ❌ Feature flag for gradual rollout (replaces old model entirely)

## Configuration Examples

All configuration is specified in HOCON and loaded into case classes at startup.

### Example 1: Simple - All Threads Share All Dispatchers

```hocon
# application.conf
sss-events.engine {
  scheduler-pool-size = 2

  thread-dispatcher-assignment = [
    [""],  # Thread 0 → default dispatcher
    [""]   # Thread 1 → default dispatcher
  ]
}
```

**Use case:** Backward compatible, simple migration path

### Example 2: Partitioned - Exclusive Dispatcher Ownership

```hocon
# application.conf
sss-events.engine {
  scheduler-pool-size = 2

  thread-dispatcher-assignment = [
    ["api"],         # Threads 0-1 exclusively on "api"
    ["api"],
    ["background"],  # Threads 2-3 exclusively on "background"
    ["background"]
  ]
}
```

**Use case:** Workload isolation, predictable resource allocation

### Example 3: Overlapping - Shared Dispatchers for Load Balancing

```hocon
# application.conf
sss-events.engine {
  scheduler-pool-size = 2

  thread-dispatcher-assignment = [
    ["api", "realtime"],   # Thread 0 handles api + realtime
    ["api"],               # Thread 1 dedicated to api
    ["realtime", "batch"]  # Thread 2 handles realtime + batch
  ]
}
```

**Use case:** Flexible load distribution, high-priority dispatcher gets more threads

### Example 4: High-Contention - Many Threads, Few Dispatchers

```hocon
# application.conf - Stress test configuration
sss-events.engine {
  scheduler-pool-size = 2

  thread-dispatcher-assignment = [
    ["hot-path"],  # Threads 0-3 on hot-path
    ["hot-path"],
    ["hot-path"],
    ["hot-path"],
    ["batch"],     # Threads 4-7 on batch
    ["batch"],
    ["batch"],
    ["batch"]
  ]
}
```

**Use case:** Stress test lock contention, validate backoff behavior

## Migration Guide

### From Current CAS-Based Queues

**Step 1: Assess Current Configuration**

Check existing dispatcher setup:
```scala
// Old API
implicit val engine = EventProcessingEngine(
  numThreadsInSchedulerPool = 2,
  dispatchers = Map(
    "" -> 4,           // 4 threads on default dispatcher
    "background" -> 2  // 2 threads on background dispatcher
  )
)
```

**Step 2: Create HOCON Configuration**

Create `application.conf` with equivalent thread assignment:

```hocon
# application.conf
sss-events.engine {
  scheduler-pool-size = 2

  # Equivalent to old: Map("" -> 4, "background" -> 2)
  thread-dispatcher-assignment = [
    [""],             # Thread 0 → default
    [""],             # Thread 1 → default
    [""],             # Thread 2 → default
    [""],             # Thread 3 → default
    ["background"],   # Thread 4 → background
    ["background"]    # Thread 5 → background
  ]
}
```

**Step 3: Load Configuration in Code**

```scala
import com.typesafe.config.ConfigFactory
import pureconfig._
import pureconfig.generic.auto._

// Load configuration
val config = ConfigSource.default
  .at("sss-events.engine")
  .loadOrThrow[EngineConfig]

// Create engine
implicit val engine = EventProcessingEngine(config)
```

**Step 4: Optimize Thread Assignment**

Consider workload characteristics and tune configuration:

```hocon
# application.conf - Optimized
sss-events.engine {
  scheduler-pool-size = 2

  # Reduce contention by overlapping assignments
  thread-dispatcher-assignment = [
    ["", "background"],  # Thread 0 shares both
    [""],                # Threads 1-2 dedicated to default
    [""],
    ["background"],      # Threads 3-4 dedicated to background
    ["background"]
  ]
}
```

**Step 5: Test and Monitor**

- Run stress tests to validate correctness
- Monitor lock contention rate (target < 10%)
- Monitor backoff activation rate (target < 1%)
- Measure throughput improvement (target ≥ 2x)

### Backward Compatibility Notes

**What Stays the Same:**
- Processor `dispatcherName` property unchanged
- Processor registration API unchanged
- Event posting API unchanged
- Subscription system unchanged

**What Changes:**
- Configuration now in HOCON (was programmatic constructor parameters)
- Engine constructor now accepts `EngineConfig` case class
- Internal thread creation logic (not user-visible)
- Performance characteristics (faster at high thread counts)

**Breaking Changes:**
- Must create `application.conf` with `thread-dispatcher-assignment`
- Old `dispatchers: Map[String, Int]` constructor parameter removed
- Migration required: convert programmatic config to HOCON

## References & Research

### Internal References

- Architecture: `/home/alan/develop/sss-events/docs/architecture/dispatcher-queue-contention.md`
- Current implementation: `/home/alan/develop/sss-events/src/main/scala/sss/events/EventProcessingEngine.scala:56-239`
- Stress tests: `/home/alan/develop/sss-events/benchmarks/src/test/scala/sss/events/stress/HandlerStackThreadSafetySpec.scala`
- Benchmarks: `/home/alan/develop/sss-events/benchmarks/src/main/scala/sss/events/benchmarks/ThroughputBenchmark.scala`
- Existing plan: `/home/alan/develop/sss-events/plans/refactor-low-priority-improvements.md` (Phase 2: Configuration System)

### External References

**Lock Contention & Backoff:**
- [Guide to java.util.concurrent.Locks - Baeldung](https://www.baeldung.com/java-concurrent-locks)
- [Exponential Backoff And Jitter - AWS Architecture Blog](https://aws.amazon.com/blogs/architecture/exponential-backoff-and-jitter/)
- [ReentrantLock Java 17 Documentation](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/concurrent/locks/ReentrantLock.html)

**Thread Pinning Patterns:**
- [Akka Dispatchers Documentation](https://doc.akka.io/docs/akka/current/typed/dispatchers.html)
- [Improving Akka dispatchers - Scalac](https://scalac.io/blog/improving-akka-dispatchers/)
- [Virtual Threads Under the Hood: Carriers, Pinning & Scaling](https://medium.com/@gauravrmsc/virtual-threads-under-the-hood-carriers-pinning-scaling-5638e6fbbd66)

**Thread Pool Sizing:**
- [How to set an ideal thread pool size - Zalando Engineering](https://engineering.zalando.com/posts/2019/04/how-to-set-an-ideal-thread-pool-size.html)
- [Java Thread Pool Implementation and Best Practices - Alibaba Cloud](https://www.alibabacloud.com/blog/java-thread-pool-implementation-and-best-practices-in-business-applications_601528)

**Testing Concurrent Systems:**
- [Twitter Scala School - Concurrency](https://twitter.github.io/scala_school/concurrency.html)
- [Testing Concurrent Code for Fun and Profit](https://puzpuzpuz.dev/testing-concurrent-code-for-fun-and-profit)

### Related Work

- Previous optimization: Commit 016eefd - Replace LinkedBlockingQueue with ConcurrentLinkedQueue
- Recent documentation: Commit 87f5975 - Add architectural documentation on dispatcher queue contention
- Test infrastructure: Commit 9a96cab - Add JMH benchmark suite

---

## Appendix: Design Decisions

### Decision 1: Keep ConcurrentLinkedQueue with Lock Protection

**Options Considered:**
1. Keep ConcurrentLinkedQueue, protect with lock ✅ **CHOSEN**
2. Replace with synchronized ArrayList
3. Replace with array-backed round-robin
4. Custom lock-free processor registry

**Rationale:**
- Minimal code changes to existing architecture
- ConcurrentLinkedQueue still provides fast offer/poll within lock
- Proven reliability in current implementation
- Easy to understand and debug

### Decision 2: Non-Fair ReentrantLock

**Options Considered:**
1. Non-fair lock (default) ✅ **CHOSEN**
2. Fair lock (FIFO ordering)
3. Configurable fairness per dispatcher

**Rationale:**
- Throughput prioritized over fairness (per architecture goals)
- Fair locks 10-100x slower than non-fair
- Starvation risk acceptable given stress test validation
- Aligns with current CAS-based approach (also non-fair)

### Decision 3: Deterministic Backoff (No Jitter)

**Options Considered:**
1. No jitter (deterministic) ✅ **CHOSEN**
2. ±50% jitter (AWS style)
3. ±25% jitter (conservative)
4. Full jitter (0-100%)

**Rationale:**
- Simpler implementation and debugging
- Predictable latency characteristics
- Thundering herd less likely with per-thread round-robin
- Can add jitter later if synchronized retries become an issue

### Decision 4: Configurable Per-Thread Assignment

**Options Considered:**
1. Configurable per-thread assignment ✅ **CHOSEN**
2. Partition strategy (auto-assign threads to dispatchers)
3. Full sharing (all threads on all dispatchers)
4. Dispatcher-centric (threads-per-dispatcher)

**Rationale:**
- Maximum flexibility for workload optimization
- Allows gradual migration and experimentation
- Supports all other strategies via configuration
- Complex but worth it for production tuning

### Decision 5: Backoff After Full Round-Robin Cycle

**Options Considered:**
1. After one full round-robin cycle ✅ **CHOSEN**
2. After N consecutive cycles (configurable)
3. Immediately after first failure
4. Never (continuous spinning)

**Rationale:**
- Balances responsiveness with CPU efficiency
- Gives all assigned dispatchers a chance before sleeping
- Prevents over-reaction to transient contention
- Simple to implement and reason about

---

**Plan Status:** Ready for Review
**Estimated Effort:** 3-4 weeks (1 engineer)
**Risk Level:** Medium (performance-critical change, extensive testing required)
**Dependencies:** Typesafe Config library (trivial to add)

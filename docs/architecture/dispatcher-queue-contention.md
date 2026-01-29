# Dispatcher Queue Contention - Architectural Analysis

## Overview

The EventProcessingEngine uses a shared queue architecture where all processors assigned to the same dispatcher share a single `ConcurrentLinkedQueue`. While this design simplifies resource management and provides good performance in typical scenarios, it can create a scalability bottleneck under specific conditions.

## Architecture

### Queue Structure

```
Dispatcher "" (default)
    │
    ├─→ ConcurrentLinkedQueue[BaseEventProcessor]
    │       ├─ Processor A (with own message queue)
    │       ├─ Processor B (with own message queue)
    │       ├─ Processor C (with own message queue)
    │       └─ Processor D (with own message queue)
    │
    └─→ N dispatcher threads (all competing for same queue)
```

### Processing Loop

Each dispatcher thread repeatedly:

1. **Poll** the dispatcher queue for a processor (`q.poll()`)
2. **Poll** that processor's message queue for work (`processor.poll(timeout)`)
3. **Process** the message if one exists
4. **Return** the processor to the dispatcher queue (`q.offer(processor)`)

## The Contention Problem

### Root Cause

Even though `ConcurrentLinkedQueue` is lock-free, it uses Compare-And-Swap (CAS) operations on shared queue head/tail pointers. When multiple threads simultaneously access the queue, they compete for these atomic updates, causing:

- **CAS retry loops** - Failed CAS operations require retries
- **Cache line bouncing** - Queue metadata ping-pongs between CPU caches
- **Memory barriers** - Synchronization points reduce CPU pipeline efficiency

### When Contention Becomes Severe

Dispatcher queue contention is **most severe** when:

1. **Many threads per dispatcher** (4+)
   - More threads = more competition for queue operations
   - Example: 8 threads on single dispatcher

2. **Trivial message processing** (< 1ms per message)
   - Threads spend more time fighting over the queue than doing work
   - Queue synchronization becomes dominant cost
   - Example: Simple counter increment, boolean flags

3. **Single dispatcher for all processors**
   - All threads compete for ONE shared queue
   - No parallel dispatch paths
   - Example: Default dispatcher `""` with all processors

4. **High message throughput**
   - Constant queue activity maximizes contention window
   - No idle periods for contention to subside

### Benchmark Evidence

#### Before (LinkedBlockingQueue with OS-level locks)
```
8 processors, 100 msgs/proc:  1,160 ops/s (31% of 2-proc baseline) ← Catastrophic
8 processors, 1000 msgs/proc:   210 ops/s (41% of 2-proc baseline) ← Severe
```

#### After (ConcurrentLinkedQueue with lock-free CAS)
```
8 processors, 100 msgs/proc:  1,592 ops/s (45% of 2-proc baseline) ← Improved +37%
8 processors, 1000 msgs/proc:   430 ops/s (71% of 2-proc baseline) ← Improved +105%
```

**Key Insight:** Lock-free queues eliminate OS blocking but still face CAS contention. The improvement is dramatic but scaling remains sub-linear.

## When This Issue Is NOT Significant

### ✅ Heavy Message Processing (> 10ms per message)

**Example:** Database queries, HTTP requests, file I/O

```scala
override protected val onEvent: EventHandler = {
  case Query(sql) =>
    val result = database.execute(sql)  // 50ms database operation
    sender ! Result(result)
}
```

**Why it's fine:**
- Threads spend 99% of time processing, 1% on queue operations
- Queue contention becomes negligible compared to work duration
- Natural serialization from slow operations reduces concurrent queue access

**Performance profile:**
```
Time breakdown per operation:
  Queue poll:    0.001ms  (0.002%)
  Message poll:  0.001ms  (0.002%)
  Processing:   50.000ms  (99.996%)  ← Dominates
  Queue offer:   0.001ms  (0.002%)
```

### ✅ Multiple Dispatchers

**Example:** Different processor types on separate dispatchers

```scala
implicit val engine = EventProcessingEngine(
  numThreadsInSchedulerPool = 2,
  dispatchers = Map(
    "io-bound"      -> 8,  // HTTP, DB processors
    "cpu-bound"     -> 4,  // Computation processors
    "low-priority"  -> 2   // Background tasks
  )
)

// Processors distributed across dispatchers
class HttpProcessor extends BaseEventProcessor {
  override def dispatcherName = "io-bound"
  // ...
}

class ComputeProcessor extends BaseEventProcessor {
  override def dispatcherName = "cpu-bound"
  // ...
}
```

**Why it's fine:**
- Each dispatcher has its own independent queue
- 8 threads split across 3 queues = ~2-3 threads per queue
- Reduces contention by sharding the workload
- Natural separation by processor type/priority

**Contention reduction:**
```
Single dispatcher (8 threads):
  Queue contention: HIGH (8 threads → 1 queue)

Multiple dispatchers (8 threads total):
  Queue 1 (io-bound):      MEDIUM (4 threads → 1 queue)
  Queue 2 (cpu-bound):     LOW    (3 threads → 1 queue)
  Queue 3 (low-priority):  LOW    (1 thread  → 1 queue)
```

### ✅ Few Threads Per Dispatcher (1-2 threads)

**Example:** Conservative thread allocation

```scala
implicit val engine = EventProcessingEngine(
  dispatchers = Map("" -> 2)  // Only 2 threads
)
```

**Why it's fine:**
- Minimal contention with only 2 threads
- CAS operations succeed most of the time
- Cache line bouncing is minimal
- Near-linear scaling achievable

**Benchmark data:**
```
2 threads: 3,556 ops/s (100% baseline)
4 threads: 2,285 ops/s (64% baseline)   ← Moderate degradation
8 threads: 1,592 ops/s (45% baseline)   ← Severe degradation
```

### ✅ Bursty Workloads with Idle Periods

**Example:** Request/response patterns with natural pauses

```scala
// User-facing API with think time between requests
override protected val onEvent: EventHandler = {
  case UserRequest(data) =>
    processRequest(data)  // 5ms
    // Natural pause while user reads response (100-1000ms)
}
```

**Why it's fine:**
- Idle periods allow queue pressure to dissipate
- Not all threads active simultaneously
- CAS retries less frequent when queue traffic is intermittent

### ✅ I/O-Bound Workloads

**Example:** Network or disk-bound operations

```scala
override protected val onEvent: EventHandler = {
  case FetchUrl(url) =>
    val response = httpClient.get(url)  // Blocks on I/O
    sender ! Response(response)
}
```

**Why it's fine:**
- Threads naturally blocked waiting for I/O
- Reduces concurrent queue access
- Effective thread count < nominal thread count
- Self-throttling behavior

## When This Issue IS Significant

### ⚠️ High-Frequency, Low-Latency Systems

**Example:** Trading systems, real-time analytics

```scala
// Sub-millisecond processing requirements
override protected val onEvent: EventHandler = {
  case Tick(price) =>
    val signal = calculateSignal(price)  // 0.1ms
    if (signal.isTrade) executeTrade(signal)
}
```

**Problem:**
- Queue overhead becomes significant portion of latency
- Cannot tolerate 30-50% throughput loss
- Microsecond-level latency requirements

**Solution:** Consider per-processor thread pinning or work-stealing architecture

### ⚠️ CPU-Bound Embarrassingly Parallel Workloads

**Example:** Image processing, batch computations

```scala
override protected val onEvent: EventHandler = {
  case ProcessBatch(items) =>
    items.foreach { item =>
      val result = computeIntensiveOperation(item)  // 2ms CPU-bound
      results += result
    }
}
```

**Problem:**
- Want to maximize CPU utilization across all cores
- Queue contention prevents linear scaling
- Leaving performance on the table

**Solution:** Use multiple dispatchers or consider Fork/Join framework for data parallelism

### ⚠️ Single Dispatcher with Many Threads (8+)

**Problem:**
- Severe CAS contention
- Sub-linear scaling (45-71% efficiency at 8 threads)
- Wasted CPU cycles on retry loops

**Solution:** Either:
1. Reduce thread count to 2-4 per dispatcher
2. Split processors across multiple dispatchers
3. Re-architect for per-processor thread pools

## Design Guidelines

### Rule of Thumb: Threads Per Dispatcher

| Threads | Contention Level | Efficiency | Recommendation |
|---------|-----------------|------------|----------------|
| 1       | None            | 100%       | ✅ Optimal for single-core |
| 2       | Minimal         | ~95%       | ✅ Recommended baseline |
| 4       | Moderate        | ~65-90%    | ⚠️ Acceptable if work > 10ms |
| 8       | High            | ~45-70%    | ❌ Only for I/O-bound or multiple dispatchers |
| 16+     | Severe          | < 30%      | ❌ Avoid unless heavy I/O |

### Dispatcher Design Patterns

#### Pattern 1: Single Default Dispatcher (Simple)
```scala
// Good for: Small apps, I/O-bound work, < 4 threads
EventProcessingEngine(dispatchers = Map("" -> 2))
```

#### Pattern 2: Workload-Based Dispatchers (Recommended)
```scala
// Good for: Mixed workloads, clear separation of concerns
EventProcessingEngine(dispatchers = Map(
  "api"        -> 4,  // Request handlers
  "background" -> 2,  // Long-running tasks
  "realtime"   -> 2   // Time-sensitive operations
))
```

#### Pattern 3: Priority-Based Dispatchers
```scala
// Good for: SLA requirements, quality of service
EventProcessingEngine(dispatchers = Map(
  "high-priority" -> 2,
  "normal"        -> 4,
  "low-priority"  -> 2
))
```

## Monitoring Recommendations

Track these metrics to identify contention:

1. **Thread utilization** - Should be > 80% if CPU-bound
2. **Queue sizes** - Growing queues indicate throughput bottleneck
3. **Message latency** - P95/P99 should be stable under load
4. **Thread parking** - Excessive `LockSupport.park()` calls indicate starvation

## Future Optimization Paths

If dispatcher queue contention becomes a bottleneck:

### Option 1: Per-Processor Thread Pinning
- Each processor gets dedicated thread
- Eliminates dispatcher queue entirely
- Best latency, highest resource cost

### Option 2: Work-Stealing Queues
- Per-thread work queues
- Idle threads steal from busy threads
- Complex but excellent scaling (e.g., Fork/Join)

### Option 3: Sharded Dispatcher Queues
- Multiple queues per dispatcher
- Thread affinity reduces contention
- Middle ground complexity/performance

### Option 4: JCTools MPMC Array Queue
- Bounded, array-based queue
- Better cache locality than linked queue
- 2-5x faster under high contention

## Conclusion

The current `ConcurrentLinkedQueue` architecture provides:

✅ **Excellent performance** for typical applications:
   - I/O-bound workloads
   - Message processing > 10ms
   - 2-4 threads per dispatcher
   - Multiple dispatchers

⚠️ **Adequate performance** for moderate CPU load:
   - Light CPU work (< 10ms)
   - Up to 8 threads with heavy work
   - Single dispatcher if necessary

❌ **Suboptimal for** extreme cases:
   - High-frequency trading / real-time systems
   - Embarrassingly parallel CPU workloads
   - Single dispatcher with 8+ threads doing trivial work

**Recommendation:** Start with 2 threads per dispatcher. Add more threads only if:
1. Workload is I/O-bound, OR
2. Message processing > 10ms, OR
3. You can split processors across multiple dispatchers

Monitor throughput and latency under realistic load to validate thread counts.

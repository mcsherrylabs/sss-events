# Understanding ops/s vs Messages/Second

## TL;DR

**1 op/s ≠ 1 message/s**

Each "operation" processes **multiple messages**, so actual message throughput is **much higher** than ops/s numbers suggest.

## Benchmark Breakdown

### ThroughputBenchmark (Most Important)

**What is measured:** Complete lifecycle of processing N messages

**Messages per operation:**
- Single dispatcher tests: **10,000 messages**
- Multi-dispatcher tests: **10,000 messages total** (split across dispatchers)

**Example calculations:**

| Benchmark | ops/s | Messages per op | **Actual Messages/s** |
|-----------|-------|-----------------|---------------------|
| singleDispatcher_4Threads | 225 | 10,000 | **2,250,000** |
| sixteenDispatchers_16Threads | 307 | 10,000 | **3,070,000** |
| twoDispatchers_4Threads_Dedicated | 283 | 10,000 | **2,830,000** |

### ActorChurnBenchmark

**What is measured:** Actor creation + message processing + destruction

**Messages per operation:**
- **10 messages per actor**
- Total messages = actorCount × 10

**Example calculations:**

| Benchmark | ops/s | Actors | Msgs/op | **Actual Messages/s** |
|-----------|-------|--------|---------|---------------------|
| cpuOnlyWorkload (10 actors) | 399 | 10 | 100 | **39,900** |
| cpuOnlyWorkload (100 actors) | 71 | 100 | 1,000 | **71,000** |
| highChurnMixedWorkload (10) | 0.1 | 10 | 100 | **10** |

**Note:** Low ops/s here reflects expensive actor lifecycle overhead, not message processing speed.

### ConcurrentLoadBenchmark

**What is measured:** Concurrent message processing across multiple processors

**Messages per operation:**
- messagesPerProcessor × processorCount
- Default: 100 or 1,000 messages per processor

**Example calculations:**

| Benchmark | ops/s | Config | Msgs/op | **Actual Messages/s** |
|-----------|-------|--------|---------|---------------------|
| 2 processors, 100 msgs | 638 | 2 × 100 | 200 | **127,600** |
| 4 processors, 100 msgs | 638 | 4 × 100 | 400 | **255,200** |
| 8 processors, 1000 msgs | 207 | 8 × 1,000 | 8,000 | **1,656,000** |

### BecomeUnbecomeBenchmark

**What is measured:** Handler switching latency (microseconds per operation)

**NOT throughput-based** - measures latency in µs/op

## Real-World Throughput Numbers

### From Full Benchmark Run

| Configuration | ops/s | **Messages/Second** | Notes |
|--------------|-------|---------------------|-------|
| **Best Overall** | 307 | **3.07 million** | 16 dedicated dispatchers ⭐ |
| Dedicated 4 threads | 283 | **2.83 million** | Good balance |
| Single 4 threads | 225 | **2.25 million** | Baseline |
| Shared 4 threads | 196 | **1.96 million** | Contention visible |

### Realistic Sustained Load

Accounting for:
- Production overhead (logging, monitoring, business logic)
- Network I/O
- Database operations
- GC pauses

**Conservative estimate:** **1-2 million messages/second** sustained throughput

## Key Takeaways

1. **Raw throughput is impressive:** 2-3 million messages/second
2. **ops/s is a multiplier:** Each operation processes 1,000-10,000 messages
3. **Benchmark metric:** ops/s measures "complete batch processing rate"
4. **Real metric:** messages/second = ops/s × messages_per_operation

## Why This Matters

When comparing with other systems:
- **Akka, Erlang, etc.** typically report messages/second
- **Our benchmarks report ops/s** where 1 op = batch of messages
- **Always multiply by batch size** to compare apples-to-apples

## Quick Reference

```
ThroughputBenchmark:    1 op = 10,000 messages
ActorChurnBenchmark:    1 op = (actorCount × 10) messages
ConcurrentLoad:         1 op = (processors × msgs_per_processor) messages
BecomeUnbecome:         latency metric (not throughput)
```

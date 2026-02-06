---
status: pending
priority: p1
issue_id: "003"
tags: [code-review, performance, memory, critical]
dependencies: []
---

# Default Queue Size Increased 10x Causing Memory Explosion Risk

## Problem Statement

**CRITICAL MEMORY ISSUE**: The default queue size was increased from 10,000 to 100,000 per processor (10x increase) without performance validation. This creates severe memory pressure in production systems with many processors.

**Why This Matters**:
- 1000 processors = 8GB memory just for queues
- Risk of OutOfMemoryError in production
- No benchmark data supports this change
- Breaks backward compatibility

## Findings

**Location**: `/home/alan/develop/sss-events/src/main/scala/sss/events/EventProcessor.scala:97`

**Current Code**:
```scala
def queueSize: Int = queueSizeOverride.getOrElse(100000)  // Was 10000 in main branch
```

**Memory Impact Analysis**:
```
Per-processor memory: ~8MB (100K messages × 80 bytes)

Scenarios:
- 100 processors:  800MB memory footprint
- 500 processors:  4GB memory footprint
- 1000 processors: 8GB memory footprint
```

**Comparison to main branch**:
```
Main branch:    500 × 10,000 × 80 bytes = 400MB
Feature branch: 500 × 100,000 × 80 bytes = 4GB  (+3.6GB increase!)
```

**Critical Gap**: No benchmark data comparing queue size impact on throughput. The ActorChurnBenchmark in the documentation does not include queue size comparisons.

**Agent Source**: performance-oracle review agent

## Proposed Solutions

### Solution 1: Revert to 10,000 Default (IMMEDIATE)
**Pros**:
- Restores backward compatibility
- Proven safe in production
- Immediate fix (5 minutes)
- Reduces memory risk

**Cons**:
- If 100K was needed for performance, this removes that benefit
- But no data shows 100K is better

**Effort**: Trivial (5 minutes)
**Risk**: None

**Implementation**:
```scala
def queueSize: Int = queueSizeOverride.getOrElse(10000)  // Revert to original
```

### Solution 2: Make Default Configurable via HOCON
**Pros**:
- Allows users to tune for their workload
- No hardcoded default
- Can be changed without recompile

**Cons**:
- Need to choose sensible default
- More configuration complexity

**Effort**: Small (1-2 hours)
**Risk**: Low

**Implementation**:
```scala
// In EngineConfig:
case class EngineConfig(
  schedulerPoolSize: Int,
  threadDispatcherAssignment: Array[Array[String]],
  backoff: BackoffConfig,
  defaultQueueSize: Int = 10000  // Add this
) derives ConfigReader {
  require(defaultQueueSize > 0 && defaultQueueSize <= 1_000_000,
    s"defaultQueueSize must be in range [1, 1000000], got: $defaultQueueSize")
}

// In EventProcessor:
def queueSize: Int = queueSizeOverride.getOrElse(engine.config.defaultQueueSize)
```

```hocon
sss-events.engine {
  default-queue-size = 10000  # Conservative default
  # ...
}
```

### Solution 3: Add Queue Size Benchmark + Data-Driven Default
**Pros**:
- Evidence-based decision
- Optimal default for performance
- Validates the change properly

**Cons**:
- Takes time to benchmark properly
- Blocks merge until data available

**Effort**: Medium (4-8 hours for benchmarks)
**Risk**: Low

**Implementation**:
```scala
// Add to benchmarks/src/main/scala/sss/events/benchmarks/QueueSizeImpactBenchmark.scala
@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.Throughput))
class QueueSizeImpactBenchmark {
  @Param(Array("1000", "10000", "50000", "100000", "500000"))
  var queueSize: Int = _

  @Benchmark
  def measureThroughputWithQueueSize(): Unit = {
    // Test with different queue sizes
    // Measure throughput and memory
  }
}
```

## Recommended Action

**IMMEDIATE (TODAY)**: Implement Solution 1 - revert to 10,000 default
**SHORT-TERM (THIS WEEK)**: Implement Solution 2 - make default configurable
**LONG-TERM (NEXT SPRINT)**: Implement Solution 3 - benchmark and validate

## Technical Details

**Affected Files**:
- `src/main/scala/sss/events/EventProcessor.scala` (line 97)
- `src/main/scala/sss/events/EngineConfig.scala` (add defaultQueueSize config)
- `src/main/resources/reference.conf` (add default-queue-size setting)
- `benchmarks/src/main/scala/sss/events/benchmarks/QueueSizeImpactBenchmark.scala` (new file)

**Components**:
- BaseEventProcessor.queueSize
- EngineConfig (if making configurable)

**Memory Characteristics**:
- LinkedBlockingQueue pre-allocates array-backed storage
- Memory allocated immediately at processor creation (not lazy)
- 100K capacity = ~8MB per processor (Object overhead + queue structure)

## Acceptance Criteria

- [ ] Default queue size reverted to 10,000
- [ ] Configuration option added for default-queue-size
- [ ] Benchmark added comparing 1K/10K/50K/100K queue sizes
- [ ] Documentation updated with queue sizing guidance
- [ ] Memory usage measured for different scenarios
- [ ] Performance impact documented (if any)

## Work Log

### 2026-02-02 - Initial Assessment
- **Discovered**: performance-oracle agent identified 10x memory increase
- **Evidence**: No benchmark data supporting 100K queue size
- **Risk**: OutOfMemoryError in production with many processors
- **Urgency**: Production blocker - must revert immediately

### 2026-02-02 - Investigation
- Compared to main branch: 10K was original default
- Checked benchmarks: No QueueSizeImpactBenchmark exists
- Checked docs: No justification for 100K default documented
- Conclusion: Change was not validated, should be reverted

## Resources

- **Performance Oracle Analysis**: See agent report section 2.4 "Queue Size Configuration"
- **Memory Calculation**: 80 bytes per queue slot (Object + Node + overhead)
- **LinkedBlockingQueue Documentation**: [Java docs](https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/LinkedBlockingQueue.html)
- **Related**: docs/best-practices/thread-dispatcher-configuration.md (should include queue sizing)

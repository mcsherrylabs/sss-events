# sss-events Performance Testing

This directory contains JMH (Java Microbenchmark Harness) benchmarks and stress tests for the sss-events library.

## Quick Start

```bash
# Run all benchmarks with proper warmup and measurement
sbt "benchmarks/Jmh/run"

# Run stress tests to verify thread safety
sbt "benchmarks/test"

# Run specific benchmark
sbt "benchmarks/Jmh/run ThroughputBenchmark"

# Quick smoke test (reduced iterations)
sbt "benchmarks/Jmh/run -wi 1 -i 1"
```

## Contents

### Benchmarks

#### 1. ThroughputBenchmark

**Purpose**: Measures single processor message throughput.

**Parameters**:
- `messageCount`: 100, 1000, 10000

**What it tests**: How many messages per second a single EventProcessor can handle with sequential message posting.

**Expected results** (modern hardware):
- 100 messages: ~50,000-100,000 ops/sec
- 1,000 messages: ~30,000-60,000 ops/sec
- 10,000 messages: ~20,000-40,000 ops/sec

#### 2. ConcurrentLoadBenchmark

**Purpose**: Measures multi-processor concurrent load handling with scaling analysis.

**Parameters**:
- `processorCount`: 2, 4, 8
- `messagesPerProcessor`: 100, 1000

**What it tests**: Throughput when multiple processors handle messages concurrently. Tests how well the system scales with parallel load.

**Expected results**:
- Linear scaling up to thread pool size
- 2 processors: ~80,000-150,000 ops/sec total
- 4 processors: ~150,000-250,000 ops/sec total
- 8 processors: ~200,000-350,000 ops/sec total

#### 3. BecomeUnbecomeBenchmark

**Purpose**: Measures handler switching overhead.

**Parameters**:
- `switchCount`: 10, 100, 1000

**Tests two patterns**:
1. **Stacking become/unbecome**: Pushes handlers onto stack, then pops them
2. **Non-stacking become (replacement)**: Alternates between two handlers by replacing

**What it tests**: The cost of changing event handlers at runtime.

**Expected results**:
- Average time per switch: 50-200 microseconds
- Non-stacking should be slightly faster than stacking
- Overhead should be consistent regardless of switch count

### Stress Tests

Located in `src/test/scala/sss/events/stress/HandlerStackThreadSafetySpec.scala`

These tests verify thread safety of the `mutable.Stack[EventHandler]` in BaseEventProcessor under concurrent access.

#### Test 1: Concurrent become calls from external threads

**What it tests**: Multiple threads posting become/unbecome messages while the main thread continuously posts regular messages.

**Verifies**: `handlers.head` is safe during concurrent stack modifications.

**Pattern**:
- 4 threads performing 100 become/unbecome cycles each
- Main thread posting 1000 regular messages
- All messages should be processed without errors

#### Test 2: Rapid become/unbecome cycles within handlers

**What it tests**: Handlers rapidly calling become/unbecome in sequence.

**Pattern**: handler1 → become(handler2) → become(handler3) → unbecome() → unbecome() → repeat 500 times

**Verifies**: Stack integrity during fast cycling within the event processing loop.

#### Test 3: Concurrent posting during handler replacement

**What it tests**: Multiple threads posting messages while handlers frequently call `become(h, stackPreviousHandler=false)`.

**Pattern**:
- 4 threads posting 500 messages each (2000 total)
- Handler switches every 50 messages (40 switches total)
- Uses non-stacking become (replacement)

**Verifies**: The unbecome+push combination is atomic and safe.

#### Test 4: Deep handler stacks

**What it tests**: Building a handler stack to 100 handlers deep, then unwinding back to base.

**Verifies**:
- No stack overflow
- Handler head access safe at all depths
- Push and pop operations maintain stack integrity

#### Test 5: Message bursts during handler changes

**What it tests**: Sending 1000-message bursts (5 bursts = 5000 total messages) with handler switches between bursts.

**Verifies**:
- No message loss
- Correct handler execution
- Handler switches during high load are safe

## Running Benchmarks

### Run All Benchmarks

```bash
sbt "benchmarks/Jmh/run"
```

This runs all benchmarks with default settings:
- 3 warmup iterations (2 seconds each)
- 5 measurement iterations (3 seconds each)
- 1 fork

### Run Specific Benchmark

```bash
# By name
sbt "benchmarks/Jmh/run ThroughputBenchmark"
sbt "benchmarks/Jmh/run ConcurrentLoadBenchmark"
sbt "benchmarks/Jmh/run BecomeUnbecomeBenchmark"

# By pattern (regex)
sbt 'benchmarks/Jmh/run .*Throughput.*'
```

### Customize Parameters

```bash
# More iterations for higher accuracy
sbt "benchmarks/Jmh/run -wi 5 -i 10"

# Longer measurement time
sbt "benchmarks/Jmh/run -wi 3 -w 5 -i 5 -r 5"

# Multiple forks for statistical confidence
sbt "benchmarks/Jmh/run -f 3"

# Specific parameter values
sbt "benchmarks/Jmh/run ThroughputBenchmark -p messageCount=1000"
```

### Quick Smoke Test

For fast feedback during development:

```bash
sbt "benchmarks/Jmh/run -wi 1 -i 1"
```

This reduces warmup and measurement to 1 iteration each (less accurate but fast).

### Profiling

Run with JMH profilers to analyze performance:

```bash
# Stack profiling
sbt "benchmarks/Jmh/run -prof stack"

# GC profiling
sbt "benchmarks/Jmh/run -prof gc"

# CPU profiling (Linux perf)
sbt "benchmarks/Jmh/run -prof perfasm"
```

### Output Formats

```bash
# JSON output
sbt "benchmarks/Jmh/run -rf json -rff results.json"

# CSV output
sbt "benchmarks/Jmh/run -rf csv -rff results.csv"

# Text output (default)
sbt "benchmarks/Jmh/run -rf text -rff results.txt"
```

## Running Stress Tests

```bash
# Run all stress tests
sbt "benchmarks/test"

# Run specific test
sbt 'benchmarks/testOnly *HandlerStackThreadSafetySpec'

# Run with verbose output
sbt "benchmarks/testOnly *HandlerStackThreadSafetySpec -- -oF"
```

### What to Look For

**If tests pass**:
- Handler stack is thread-safe under current usage patterns
- `taskLock.synchronized` in EventProcessingEngine provides sufficient protection
- No changes needed to BaseEventProcessor

**If tests fail**:
- Watch for `ConcurrentModificationException`
- Check for incorrect message counts
- Look for handler execution errors
- Race condition confirmed - synchronization needed

## Understanding Results

### Throughput Benchmarks

Results are in operations/second (messages/second).

**Example output**:
```
Benchmark                                    (messageCount)   Mode  Cnt      Score     Error  Units
ThroughputBenchmark.measureThroughput                  100  thrpt    5  85432.123 ± 2341.456  ops/s
ThroughputBenchmark.measureThroughput                 1000  thrpt    5  45678.234 ± 1234.567  ops/s
ThroughputBenchmark.measureThroughput                10000  thrpt    5  28976.345 ± 987.654   ops/s
```

**Interpretation**:
- Higher score = better performance
- Error margin indicates consistency
- Compare before/after changes to detect regressions

### Average Time Benchmarks

Results are in microseconds (µs) per operation.

**Example output**:
```
Benchmark                                           (switchCount)  Mode  Cnt    Score    Error  Units
BecomeUnbecomeBenchmark.stackingBecomeUnbecome                 10  avgt    5   87.234 ±  3.456  us/op
BecomeUnbecomeBenchmark.stackingBecomeUnbecome                100  avgt    5  125.678 ±  5.678  us/op
BecomeUnbecomeBenchmark.nonStackingBecomeReplace               10  avgt    5   76.543 ±  2.345  us/op
```

**Interpretation**:
- Lower score = better performance
- Non-stacking should be faster than stacking
- Use to identify handler switching overhead

## CI Integration

### GitHub Actions

Add to your workflow (`.github/workflows/benchmarks.yml`):

```yaml
name: Benchmarks

on:
  pull_request:
    branches: [ main ]

jobs:
  benchmark:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      - uses: actions/setup-java@v3
        with:
          distribution: 'temurin'
          java-version: '17'
      - name: Run stress tests
        run: sbt "benchmarks/test"
      - name: Run quick benchmarks
        run: sbt "benchmarks/Jmh/run -wi 1 -i 3 -rf json -rff benchmark-results.json"
      - name: Upload results
        uses: actions/upload-artifact@v3
        with:
          name: benchmark-results
          path: benchmark-results.json
```

### Benchmark on Merge

Add to main build workflow (`.github/workflows/build.yml`):

```yaml
- name: Run stress tests
  run: sbt "benchmarks/test"
```

## Interpreting Thread Safety Results

### Expected Behavior

All 5 stress tests should pass consistently:
1. No `ConcurrentModificationException`
2. All messages accounted for (correct counts)
3. No handler execution errors
4. Tests complete within timeout

### If Tests Fail

**Symptoms of race conditions**:
- Intermittent `ConcurrentModificationException`
- Message count mismatches
- Tests timeout
- Inconsistent failures (pass sometimes, fail others)

**Next steps**:
1. Confirm failure is consistent (run multiple times)
2. Check which specific test(s) fail
3. Review synchronization in EventProcessor and EventProcessingEngine
4. Consider implementing fixes from Phase 1 of improvement plan:
   - Add `handlers.synchronized { }` around all handler stack access
   - Replace with `AtomicReference[List[EventHandler]]`
   - Use `ConcurrentLinkedDeque`

## Performance Baselines

Record baseline results after initial implementation:

**Hardware**: (Record your specs)
- CPU:
- RAM:
- JVM:

**Baseline Results**: (From initial run)

```
# ThroughputBenchmark
messageCount=100:   _____ ops/s
messageCount=1000:  _____ ops/s
messageCount=10000: _____ ops/s

# ConcurrentLoadBenchmark
processorCount=2, messagesPerProcessor=100:  _____ ops/s
processorCount=4, messagesPerProcessor=100:  _____ ops/s
processorCount=8, messagesPerProcessor=100:  _____ ops/s

# BecomeUnbecomeBenchmark
Stacking (switchCount=100):     _____ µs/op
Non-stacking (switchCount=100): _____ µs/op

# Stress Tests
All tests: PASS/FAIL
```

Use these baselines to detect performance regressions in future changes.

## Troubleshooting

### Benchmarks Don't Compile

```bash
# Clean and reload
sbt clean
sbt reload
sbt "benchmarks/compile"
```

### Tests Timeout

Increase timeout in test code or reduce message counts for slower hardware.

### Inconsistent Results

- Ensure no other heavy processes are running
- Use more warmup iterations: `-wi 5`
- Use multiple forks: `-f 3`
- Run on dedicated benchmark hardware

### JMH Not Found

Ensure sbt-jmh plugin is in `project/plugins.sbt`:
```scala
addSbtPlugin("pl.project13.scala" % "sbt-jmh" % "0.4.8")
```

## References

- [JMH Documentation](https://github.com/openjdk/jmh)
- [sbt-jmh Plugin](https://github.com/sbt/sbt-jmh)
- [JMH Samples](https://github.com/openjdk/jmh/tree/master/jmh-samples/src/main/java/org/openjdk/jmh/samples)

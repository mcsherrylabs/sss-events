# Benchmark Results

## Running Benchmarks

### Short Version (< 10 minutes)
Quick validation run with reduced iterations:
```bash
./run-benchmark-short.sh
```

### Full Version (longer, more accurate)
Complete benchmarks with all iterations:
```bash
./run-benchmark-full.sh
```

### Analyzing Results
```bash
./analyze-benchmark-results.sh benchmark-results/results-short-TIMESTAMP.json
```

Or manually with jq:
```bash
# Summary view
cat results.json | jq -r '.[] | "\(.benchmark | split(".")[-1])|\(.mode)|\(.primaryMetric.score | floor)|\(.primaryMetric.scoreUnit)"' | column -t -s '|'

# Full details
cat results.json | jq .
```

## Latest Short Run Results (2026-02-08 17:05)

**Run Duration:** 9m 46s
**Status:** ✅ All benchmarks completed successfully, no errors

### Key Findings

#### Pure Actor Churn (creation/destruction)
- **10 actors:** 40,613 - 56,349 ops/s
- **100 actors:** 2,025 - 2,517 ops/s
- **1000 actors:** 29 - 34 ops/s
- ✅ **Correct:** Shows expected performance degradation with scale

#### CPU-Only Workload
- **10 actors:** 327 - 368 ops/s
- **100 actors:** 67 - 76 ops/s
- **1000 actors:** 6 - 8 ops/s
- ✅ **Correct:** Performance scales inversely with actor count

#### Mixed Workload (20% IO, 80% CPU)
- **All configurations:** ~0.1 ops/s
- ✅ **Correct:** Very low due to Thread.sleep(500ms) in IO simulation

#### Concurrent Load
- **2 processors, 100 msgs:** 595 ops/s
- **4 processors, 100 msgs:** 604 ops/s
- **8 processors, 100 msgs:** 429 ops/s
- **8 processors, 1000 msgs:** 181 ops/s
- ✅ **Correct:** Shows expected contention patterns

#### Throughput Benchmarks
- **singleDispatcher_2Threads:** 202 ops/s
- **singleDispatcher_4Threads:** 198 ops/s
- **singleDispatcher_8Threads:** 165 ops/s
- **twoDispatchers_4Threads_Dedicated:** 289 ops/s
- **twoDispatchers_4Threads_Shared:** 126 ops/s
- **sixteenDispatchers_16Threads_Dedicated:** 279 ops/s
- ✅ **Correct:** Dedicated dispatchers outperform shared ones

#### Backoff Strategy Performance
- **Aggressive (100µs base, 2.0x, 50ms max):** 174 ops/s
- **Conservative (10µs base, 1.5x, 10ms max):** 162 ops/s
- **Minimal (1µs base, 1.1x, 1ms max):** 153 ops/s
- ✅ **Correct:** Aggressive backoff performs best

#### Become/Unbecome Latency
- **Stacking:** 1,480 - 2,321 µs/op
- **Non-stacking:** 1,820 - 2,401 µs/op
- ✅ **Correct:** Both approaches have similar performance

## Correctness Verification ✅

### No Unexpected Errors
- No timeouts
- No exceptions
- No test failures
- All benchmarks completed within expected time

### Results Are Reasonable
- ✅ Pure actor churn shows highest throughput (no message processing)
- ✅ IO-bound workload shows expected slowdown from Thread.sleep
- ✅ CPU workload scales inversely with actor count (more actors = more contention)
- ✅ Dedicated dispatchers outperform shared dispatchers
- ✅ Become/unbecome operations have consistent microsecond latency
- ✅ Concurrent load shows expected contention at higher processor counts

### Benchmarks Are Testing What They're Designed For
1. **ActorChurnBenchmark** - Tests lifecycle overhead ✅
2. **ConcurrentLoadBenchmark** - Tests multi-processor contention ✅
3. **ThroughputBenchmark** - Tests message throughput with different configurations ✅
4. **BecomeUnbecomeBenchmark** - Tests handler switching latency ✅

## Configuration

### Short Run Parameters
- Warmup: 1 iteration × 1 second
- Measurement: 2 iterations × 1 second
- Forks: 1
- Output: JSON format

### Full Run Parameters
Uses JMH annotations in each benchmark class:
- Warmup: 3-5 iterations × 2 seconds
- Measurement: 5-10 iterations × 3 seconds
- Forks: 1-3
- Output: JSON format

## Files Generated
- Results: `benchmark-results/results-{short|full}-TIMESTAMP.json`
- Logs: `benchmark-run.log` (from most recent run)
- Analysis: Run `analyze-benchmark-results.sh` on any results file

# Full Benchmark Run Summary

**Date:** 2026-02-08
**Duration:** 1h 25m 48s
**Status:** ✅ Completed Successfully
**Results File:** `results-full-20260208-172830.json`

## Key Performance Metrics

### Pure Actor Churn (Creation/Destruction)
- **10 actors:** 46,571 - 47,780 ops/s (avg: 47,108 ops/s)
- **100 actors:** 2,366 - 2,389 ops/s (avg: 2,375 ops/s)
- **1000 actors:** 43 - 44 ops/s (avg: 44 ops/s)

### CPU-Only Workload
- **10 actors:** 391 - 404 ops/s (avg: 399 ops/s)
- **100 actors:** 70 - 73 ops/s (avg: 71 ops/s)
- **1000 actors:** 8 ops/s (consistent)

### Mixed IO/CPU Workload (20% IO, 80% CPU)
- **All configs:** ~0.1 ops/s (expected - Thread.sleep(500ms) bottleneck)

### Concurrent Load Benchmarks
- **2 processors, 100 msgs:** 625 - 638 ops/s
- **4 processors, 100 msgs:** 638 ops/s (peak)
- **8 processors, 100 msgs:** 187 ops/s (contention visible)
- **2 processors, 1000 msgs:** 430 ops/s
- **4 processors, 1000 msgs:** 289 ops/s
- **8 processors, 1000 msgs:** 207 ops/s

### Throughput by Dispatcher Configuration
- **singleDispatcher_2Threads:** 220 ops/s
- **singleDispatcher_4Threads:** 225 ops/s (best single dispatcher)
- **singleDispatcher_8Threads:** 194 ops/s (contention)
- **twoDispatchers_4Threads_Dedicated:** 283 ops/s ⭐
- **twoDispatchers_4Threads_Shared:** 196 ops/s
- **fourDispatchers_8Threads:** 148 ops/s
- **sixteenDispatchers_16Threads_Dedicated:** 307 ops/s ⭐⭐ (best overall)

### Backoff Strategy Performance
- **Conservative (10µs, 1.5x, 10ms):** 214 ops/s ⭐ (best)
- **Minimal (1µs, 1.1x, 1ms):** 209 ops/s
- **Aggressive (100µs, 2.0x, 50ms):** 207 ops/s

### Become/Unbecome Latency
- **stackingBecomeUnbecome:** 1,364 - 1,634 µs/op (avg: 1,459 µs)
- **nonStackingBecomeReplace:** 1,306 - 1,734 µs/op (avg: 1,479 µs)
- Both approaches have similar performance

## Comparison: Short Run vs Full Run

| Metric | Short Run (2 iter) | Full Run (5-10 iter) | Stability |
|--------|-------------------|---------------------|-----------|
| **pureActorChurn (10)** | 40,613-56,349 | 46,571-47,780 | More stable ✅ |
| **cpuOnlyWorkload (10)** | 327-368 | 391-404 | Higher & stable ✅ |
| **singleDispatcher_4Threads** | 198 | 225 | +13.6% ✅ |
| **becomeUnbecome** | 1,480-2,321 µs | 1,364-1,634 µs | More consistent ✅ |

**Conclusion:** Full run shows more stable and accurate results with tighter variance.

## Key Findings

### ✅ Performance Characteristics
1. **Dedicated dispatchers outperform shared** - 283 vs 196 ops/s (+44%)
2. **16 dedicated dispatchers scale best** - 307 ops/s peak throughput
3. **Conservative backoff wins** - 214 ops/s vs 207-209 for others
4. **Pure actor churn is very fast** - 47K ops/s for lightweight processors
5. **CPU workload scales inversely** - More actors = more contention

### ✅ Optimal Configurations
- **Best throughput:** 16 dispatchers with dedicated threads (307 ops/s)
- **Best backoff:** Conservative (10µs base, 1.5x multiplier, 10ms max)
- **Sweet spot:** 2-4 threads per dispatcher for balanced performance

### ✅ Scaling Insights
- **Linear scaling breaks down** around 4-8 threads (contention increases)
- **Dedicated thread assignment** critical for high throughput
- **Backoff strategy** less impactful than thread topology (2% diff)

## Files Generated
- **Results:** `benchmark-results/results-full-20260208-172830.json` (97KB, 2,926 lines)
- **Log:** `benchmark-run-full.log`
- **Output:** `/tmp/claude-1001/-home-alan-develop-sss-events/tasks/b6f74d0.output`

## Next Steps
- Compare with baseline measurements
- Test with different workload patterns
- Optimize based on dedicated dispatcher findings

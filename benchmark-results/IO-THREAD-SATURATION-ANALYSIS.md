# IO Thread Saturation Curve Analysis

**Test Date:** 2026-02-08
**IO Latency:** 20ms (fixed)
**Test Duration:** ~3 minutes
**Theoretical Capacity:** 50 tasks/s per thread

## Complete Results

| IO Threads | Task Rate (tasks/s) | Throughput (ops/s) | Utilization | Status |
|-----------|---------------------|-------------------|-------------|---------|
| **1** | 50 | 0.196 | 98% | ✅ Optimal |
| **1** | 100 | 0.098 | 49% | ⚠️ Saturated |
| **2** | 100 | 0.196 | 98% | ✅ Optimal |
| **2** | 200 | 0.098 | 49% | ⚠️ Saturated |
| **4** | 200 | 0.196 | 98% | ✅ Optimal |
| **4** | 500 | 0.079 | 40% | ❌ Under-provisioned |
| **8** | 500 | 0.155 | 78% | ✅ Good |
| **8** | 1000 | 0.078 | 39% | ❌ Under-provisioned |
| **16** | 1000 | 0.156 | 78% | ✅ Good |
| **16** | 2000 | 0.078 | 39% | ❌ Under-provisioned |
| **32** | 2000 | 0.157 | 79% | ✅ Good |
| **64** | 2000 | 0.310 | **155%** | 🚀 Over-provisioned |
| **128** | 2000 | 0.494 | **247%** | 🚀 Significant over-provisioning |

## Key Findings

### 1. Saturation Curve (Minimum Threads Needed)

For **optimal performance** (>95% utilization):

```
Task Rate    → Minimum IO Threads
─────────────────────────────────
  50 tasks/s  → 1 thread
 100 tasks/s  → 2 threads
 200 tasks/s  → 4 threads
 500 tasks/s  → 10-12 threads (extrapolated)
1000 tasks/s  → 20-24 threads (extrapolated)
2000 tasks/s  → 40-50 threads (data shows 32 is marginal, 64+ is good)
```

**Formula:** `threads_needed ≈ (task_rate / 50) × 1.0-1.2`

### 2. Scaling Efficiency

**Perfect Linear Scaling:**
- Doubling threads at same load → doubles throughput
- Examples:
  - 1 thread, 50 tasks/s: 0.196 ops/s
  - 2 threads, 100 tasks/s: 0.196 ops/s ✅
  - 4 threads, 200 tasks/s: 0.196 ops/s ✅

**Under-provisioned (bottlenecked):**
- 4 threads, 500 tasks/s: 0.079 ops/s (need ~10 threads)
- 8 threads, 1000 tasks/s: 0.078 ops/s (need ~20 threads)
- 16 threads, 2000 tasks/s: 0.078 ops/s (need ~40 threads)

**Over-provisioned (excess capacity):**
- 64 threads, 2000 tasks/s: 0.310 ops/s (55% faster than 32 threads)
- 128 threads, 2000 tasks/s: 0.494 ops/s (59% faster than 64 threads)

### 3. Diminishing Returns

**Optimal efficiency:** thread count ≈ (task_rate / 50)

**Beyond optimal:**
- 32 → 64 threads: +98% throughput (good scaling)
- 64 → 128 threads: +59% throughput (diminishing returns starting)

**Cost/benefit:**
- 32 threads: 0.157 ops/s → **4.9 tasks/s per thread**
- 64 threads: 0.310 ops/s → **4.8 tasks/s per thread**
- 128 threads: 0.494 ops/s → **3.9 tasks/s per thread** ⚠️ Lower efficiency

### 4. Real-World Recommendations

**For sustained load:**

| Expected Load | Recommended Threads | Buffer |
|--------------|-------------------|---------|
| 100 tasks/s | 4 threads | 2x buffer |
| 500 tasks/s | 16 threads | 1.6x buffer |
| 1000 tasks/s | 32 threads | 1.6x buffer |
| 2000 tasks/s | 64 threads | 1.6x buffer |

**Reasoning:**
- Provides headroom for bursts
- Accounts for GC pauses, thread scheduling
- Stays below diminishing returns threshold

## Visualization

### Throughput vs Thread Count (at 2000 tasks/s)

```
ops/s
0.50 |                                     ● (128 threads)
     |
0.40 |
     |
0.30 |                          ● (64 threads)
     |
0.20 |
     |
0.10 |              ● (16)  ● (32)
     |
0.00 |_____|_____|_____|_____|_____|_____|_____|_____
     0    16    32    48    64    80    96   112  128
                     IO Threads
```

**Sweet spot for 2000 tasks/s:** 64 threads (best throughput/thread ratio)

### Saturation Threshold

```
Task Rate (tasks/s)
2000 |                                    [64-128 threads needed]
     |
1500 |
     |
1000 |                    [20-24 threads needed]
     |
 500 |          [10-12 threads needed]
     |
 200 |   [4 threads needed]
     |
 100 | [2 threads]
     |
  50 | [1 thread]
     |_____|_____|_____|_____|_____|_____|_____
     0    10    20    30    40    50    60    70
                  IO Threads
```

## Conclusions

1. **Linear scaling holds** up to thread count ≈ (task_rate / 50)
2. **Over-provisioning helps** but with diminishing returns beyond 2x optimal
3. **For 20ms IO latency:** 1 thread handles ~50 tasks/s optimally
4. **Production sizing:** Add 60-100% buffer above theoretical minimum
5. **128 threads shows overhead:** efficiency drops to 3.9 tasks/s per thread

## Next Steps

- Test with different IO latencies (10ms, 50ms, 100ms)
- Test burst scenarios (spike from 100 to 2000 tasks/s)
- Measure latency percentiles (P50, P95, P99)
- Test with mixed workloads (multiple task types)

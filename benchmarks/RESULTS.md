# Phase 1 benchmark results

Machine: Apple Silicon macOS (darwin 25.5.0), JDK 17, Kotlin 2.4.10, orbit-core
`feature/orbit-core-observer-spi`, 2026-07-28.

## Microbenchmark (`./gradlew :benchmarks:benchmark`, JMH avgt, 5×1s iterations)

| Benchmark | Score | Per unit |
|---|---|---|
| `reduce_observer_null` (100 reductions/op) | 1.847 ± 0.052 µs/op | **~18 ns/reduction** |
| `reduce_recording` (100 reductions/op) | 6.801 ± 0.229 µs/op | **~68 ns/reduction** |
| `intent_observer_null` (dispatch+join) | 6.484 ± 2.805 µs/op | per intent |
| `intent_recording` (dispatch+join) | 7.147 ± 2.961 µs/op | per intent |

Recording costs **~50 ns per reduction** at steady state (ring full, eviction active,
per-container retention engaged). The observer-null path is today's exact hot path.

## Frame-loop simulation (`./gradlew :benchmarks:frameSim`)

30 000 frames per variant, interleaved in 30 blocks to cancel JIT/GC drift. Each frame:
1 intent dispatch + 5 reductions + 1 side effect (= 8 recorded events).

| percentile | recorder off | recorder on | delta |
|---|---|---|---|
| P50 | 6.8 µs | 8.2 µs | +1.3 µs |
| P90 | 12.1 µs | 13.5 µs | +1.4 µs |
| P99 | 27.2 µs | 31.4 µs | +4.2 µs |
| P99.9 | 54.9 µs | 67.4 µs | +12.5 µs |

Against a 16.7 ms frame budget the added work is ~0.03 % at P99 — recording cost is not the
risk; retention memory is, exactly as the plan's cost model predicts.

**Caveat:** this is a JVM proxy for per-frame CPU work, not device frame scheduling. Run the
on-device macrobenchmark (P50/P99 frame times, recorder on/off, dogfood-profile capacity 300)
before the Statsig-gated rollout.

# Reduce interception invocation overhead: PRs opened

The changes and performance tables are now in the upstream PRs:

- [Weld 7, #3545](https://github.com/weld/core/pull/3545), target `main`.
- [Weld 6 backport, #3546](https://github.com/weld/core/pull/3546), target `6.0`.

Each PR includes the prerequisite client-proxy, proxy-2 and proxy-3 changes, because none of those series is upstream yet. Fork-only CI workflow commits are excluded.

## Measured gains

Throughput in ops/µs, same-runner proxy-3 → proxy-4 comparison, one thread:

| benchmark | Weld 7 | gain | Weld 6 | gain |
|---|---:|---:|---:|---:|
| method interception | 18.18 → **33.63** | **+85%** | 18.46 → **33.19** | **+80%** |
| class interception | 18.65 → **33.60** | **+80%** | 18.65 → **32.72** | **+75%** |

Four-thread method-interception point estimates also rise (Weld 7 +383%, Weld 6 +104%), but both measurements have wide intervals. Other single-thread scenarios remain within measurement noise. Cold startup with the first intercepted calls shows no clear regression.

The PR bodies include the full timing intervals and the links to CI, full benchmarks, allocation profiling and cold-start measurements. The complete recovery report is [here](weld-proxy-4-status.md).

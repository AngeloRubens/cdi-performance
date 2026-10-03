# DRAFT – Backport pull request for weld/core `6.0` branch — not submitted

**Source branches:**
* https://github.com/AngeloRubens/core/tree/perf/client-proxy-6.0 (3 commits, cherry-picked unchanged from `perf/client-proxy`)
* https://github.com/AngeloRubens/core/tree/perf/request-cache-6.0 (additionally the 2 request-cache commits; separate PR, see the 7.x request-cache draft)

Drop the top `[fork-only] Run Weld CI ...` commit before opening the PR. Base: branch `6.0` (6.0.5-SNAPSHOT,
30 commits after 6.0.4.Final), not the 6.0.4.Final tag.

**Title:** [6.0] WELD-XXXX Reduce per-invocation overhead of client proxies and intercepted subclasses

## Summary

Backport of #NNNN (7.x). Same three changes, cherry-picked without conflicts:
1. `InterceptionDecorationContext.startIfNotEmpty()` no longer creates and removes an empty stack;
2. `Stack.removeIfEmpty()` sets the thread-local to `null` instead of `remove()`; smaller initial `ArrayDeque`;
3. `ContextBeanInstance` avoids the `Container.isSet()` registry lookup while its container is alive.

## Benchmarks (JMH, JDK 21, ops/µs, same GitHub runner for all columns)

Full run https://github.com/AngeloRubens/cdi-performance/actions/runs/37113133484:

| benchmark | 6.0.4.Final 1t | this PR 1t | 6.0.4.Final 4t | this PR 4t |
|---|---|---|---|---|
| applicationScoped | 25.04 ± 0.12 | **244.79 ± 1.32** | 33.31 ± 9.68 | **557.67 ± 4.25** |
| classIntercepted | 8.79 ± 0.17 | **19.34 ± 0.19** | 14.36 ± 0.82 | **27.24 ± 0.82** |
| methodIntercepted | 8.84 ± 0.08 | **19.41 ± 0.22** | 15.70 ± 2.78 | **26.94 ± 0.89** |
| methodNotIntercepted | 12.33 ± 0.11 | **47.84 ± 5.02** | 23.16 ± 3.85 | **44.34 ± 2.71** |
| requestScoped | 12.52 ± 0.10 | **22.54 ± 2.70** | 24.68 ± 2.84 | **68.34 ± 2.70** |
| fireEvent (untouched) | 55.08 ± 1.69 | 51.30 ± 7.40 | 137.86 ± 7.97 | 138.97 ± 5.10 |
| bootAndShutdown (ms, lower is better) | 34.7 ± 2.5 | 32.5 ± 2.4 | | |

Quick run (1 thread) https://github.com/AngeloRubens/cdi-performance/actions/runs/37113771427 confirms it:
applicationScoped 19.77 ± 0.42 → 186.82 ± 2.34, methodIntercepted 7.14 ± 0.09 → 15.81 ± 0.29,
requestScoped 10.48 ± 0.35 → 20.40 ± 1.07 (→ 106.51 ± 0.43 with the request-cache backport).

## Testing

Weld CI copy (JDK 21) on the fork, all green:
* `perf/client-proxy-6.0`: https://github.com/AngeloRubens/core/actions/runs/37113033900 —
  w/o container 2807 tests, in-container (WildFly) 1811, CDI TCK (WildFly) 1903, CDI TCK SE 34, relaxed mode 4697; 0 failures/errors.
* `perf/request-cache-6.0`: https://github.com/AngeloRubens/core/actions/runs/37113764340 — same counts, 0 failures/errors.

The 6.0 CI workflow has no "CDI Signature Test" job, so none was run there. JDK 17/25 not run.

---

# DRAFT – Second backport for the `6.0` branch (interception context) — not submitted

**Source branch:** https://github.com/AngeloRubens/core/tree/perf/proxy-2-6.0 (on top of `perf/request-cache-6.0`;
drop the `[fork-only]` commit). The three commits of the 7.x draft `weld-pr-proxy-2-draft.md` cherry-picked without
conflicts:

1. Reuse a per-thread `InterceptionDecorationContext` stack (holder `Object[]`, strong while non-empty, weak otherwise).
2. Skip the thread-local lookup in client proxies until an interception context has ever been used
   (`SwitchPoint`-guarded constant `MethodHandle`; Java 7+ API, fine for the Java 17 baseline of 6.0).
3. `ContextBeanInstance` goes straight to the `RIBean`'s `ContextualInstanceStrategy`.

**Testing:** https://github.com/AngeloRubens/core/actions/runs/37141452229 — all green: 2807 tests w/o container
(8 skipped), 1811 in-container, CDI TCK 1903 + SE 34, relaxed 4697, examples, SE-Servlet. (The 6.0 CI has no
signature-test job.)

**Benchmarks:** https://github.com/AngeloRubens/cdi-performance/actions/runs/37141471463 — vs `perf/request-cache-6.0`: applicationScoped 187.1 → 326.9, interception used 187.5 → 199.7, requestScoped 106.5 → 141.2, methodIntercepted 15.4 → 16.7, methodNotIntercepted 38.5 → 46.3 ops/µs (same as 7.x).

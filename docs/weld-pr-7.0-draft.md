# DRAFT – Pull request for weld/core `main` (7.0.x) — not submitted

**Source branch:** https://github.com/AngeloRubens/core/tree/perf/client-proxy
(the top commit `[fork-only] Run Weld CI ...` must be dropped before opening the PR; it only enables CI in the fork)

**Title:** WELD-XXXX Reduce per-invocation overhead of client proxies and intercepted subclasses

## Summary

Invoking a business method through a client proxy outside of a request (Weld SE, background threads,
`@ApplicationScoped` services called from non-request threads, ...) allocated and set/removed a `ThreadLocal`
on every call. This PR removes that overhead and a registry lookup, without changing semantics:

1. **`InterceptionDecorationContext.startIfNotEmpty()`** returns `null` right away when there is no stack on the
   current thread instead of creating a `Stack` and removing it again (`getStack()` + `removeIfEmpty()`).
   An absent stack is an empty stack, and the old code never left anything behind in this case.
2. **`Stack.removeIfEmpty()`** sets the thread-local to `null` instead of calling `ThreadLocal.remove()`.
   `remove()` clears the weak reference of the map entry (native `Reference.clear0`) and expunges it, so the next
   `set()` allocated a new entry: this was the most expensive part of every intercepted invocation outside of a request.
   A `null` value does not reference any Weld class (the entry key is a weak reference to a `java.lang.ThreadLocal`),
   so there is no class loader leak. The `ArrayDeque` is now created with a small initial capacity.
3. **`ContextBeanInstance`** keeps the `Container` it was created for; the `Container.isSet(contextId)` registry
   lookup is only performed after that container has been cleaned up (new volatile `cleanedUp` flag set at the very
   beginning of `Container.cleanup()`). The "contextual reference not valid after shutdown" behaviour is unchanged.

## Benchmarks

JMH, JDK 21 (Temurin), GitHub-hosted `ubuntu-latest` runner, Weld SE, 1 thread, ops/µs (higher is better),
all variants in the same job: https://github.com/AngeloRubens/cdi-performance/actions/runs/37112321830
(benchmark source: https://github.com/AngeloRubens/cdi-performance/blob/jakarta-2026/src/main/java/at/struct/cdi/performance/jmh/CdiBenchmark.java)

| benchmark | 7.0.0.Final | + commit 1 | + commit 2 | + commit 3 (this PR) | OWB 4.1.1 (reference) |
|---|---|---|---|---|---|
| applicationScoped | 20.4 ± 0.6 | 138.3 ± 2.1 | 138.7 ± 0.7 | **187.3 ± 0.6** | 717.1 ± 3.9 |
| classIntercepted | 7.0 ± 0.3 | 10.0 ± 0.2 | 14.8 ± 0.0 | **15.8 ± 0.1** | 9.9 ± 0.5 |
| methodIntercepted | 7.0 ± 0.1 | 9.9 ± 0.1 | 14.8 ± 0.1 | **15.5 ± 0.2** | 9.4 ± 0.1 |
| methodNotIntercepted | 9.5 ± 0.1 | 16.7 ± 0.1 | 35.8 ± 0.3 | **38.4 ± 0.2** | 504.4 ± 1.1 |
| requestScoped | 10.0 ± 0.8 | 21.5 ± 0.3 | 20.4 ± 1.5 | **21.8 ± 1.0** | 92.0 ± 0.2 |
| fireEvent (unaffected) | 43.7 ± 0.4 | 43.5 ± 0.4 | 43.0 ± 1.5 | 43.8 ± 1.5 | 3.0 ± 0.0 |

`-prof gc`: allocation per `@ApplicationScoped` / `@RequestScoped` proxy invocation 168 B → 0 B.

Second, full run on another runner (2 forks × 5 iterations, 1 and 4 threads, boot, legacy loop test): https://github.com/AngeloRubens/cdi-performance/actions/runs/37113133484

| benchmark (1 thread) | 7.0.0.Final | this PR | speed-up |
|---|---|---|---|
| applicationScoped | 24.96 ± 0.21 | 244.03 ± 2.09 | x9.8 |
| methodIntercepted | 8.86 ± 0.05 | 19.46 ± 0.27 | x2.2 |
| methodNotIntercepted | 12.44 ± 0.11 | 50.90 ± 0.65 | x4.1 |
| requestScoped | 12.94 ± 0.11 | 26.24 ± 0.05 | x2.0 |
| fireEvent | 54.97 ± 1.59 | 51.84 ± 3.29 | ≈ (within noise, untouched code) |
| bootAndShutdown (ms) | 31.7 ± 2.0 | 32.6 ± 2.0 | ≈ |

Absolute numbers differ between the two runs because they ran on different runner hardware; only compare columns of the same run.

## Testing

Full Weld CI copied from `ci-actions.yml` (JDK 21 only; examples job JDK 17) on the fork, run
https://github.com/AngeloRubens/core/actions/runs/37113100181 — all jobs green:

| job | tests run | failures | errors | skipped |
|---|---|---|---|---|
| Weld Tests w/o Container | 3267 | 0 | 0 | 16 |
| Weld In-container Tests (WildFly) | 2086 | 0 | 0 | 25 |
| CDI TCK (WildFly) | 2089 | 0 | 0 | 0 |
| CDI TCK SE | 36 | 0 | 0 | 0 |
| Relaxed mode testing | 5342 | 0 | 0 | 16 |
| CDI Signature Test, Weld SE-Servlet Cooperation, Examples | green | | | |

Not run: JDK 17 and JDK 25 matrix entries of the upstream CI.

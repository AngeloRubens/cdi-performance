# DRAFT – Pull requests for weld/core: RequestScopedCache in the unbound request context — not submitted

| target | head branch (fork) | stacked on |
|---|---|---|
| `main` (Weld 7) | `AngeloRubens:pr/request-cache-main` | PR #3545 (`pr/proxy4-main`) |
| `6.0` (Weld 6) | `AngeloRubens:pr/request-cache-6.0` | PR #3546 (`pr/proxy4-6.0`) |

Each branch adds two commits on top of the corresponding open PR:
- "Enable RequestScopedCache for the unbound request context"
- "Flush RequestScopedCache when a bound context is deactivated"

Until #3545/#3546 are merged, the GitHub diff of these PRs also shows the commits of those PRs. Reviewers should look only at the last two commits.

---

**Title (main):** [7.0] Use RequestScopedCache in the unbound request context
**Title (6.0):** [6.0] Use RequestScopedCache in the unbound request context

## Summary

The unbound `RequestContext` never begins a `RequestScopedCache`, unlike `BoundRequestContextImpl` and the HTTP request context. The unbound context is the one used by `RequestContextController`, `@ActivateRequestContext` and Weld SE. As a result, every invocation of a `@RequestScoped` client proxy goes through `BeanManagerImpl.getContext()` and the bean store.

This change makes two edits:

1. `RequestContextImpl.activate()` begins the cache. `deactivate()` ends it *before* the instances are destroyed.
2. `AbstractBoundContext.deactivate()` flushes the cache with `RequestScopedCache.invalidate()`.
   - The cache is now active in more situations, so a bound context (for example the session context) can be deactivated, dissociated and associated with another bean store while the cache is active.
   - `NaiveClusterTest.testMultipleDependentObjectsSessionReplication` caught exactly this: it fails with change 1 alone and passes with change 2.

This is stacked on #3545 (main) / #3546 (6.0) and is submitted separately because it changes behaviour more than those PRs do.

## Semantic considerations for reviewers

- **Thread-local caching.** While the unbound request context is active, `@RequestScoped`, `@SessionScoped` and `@ConversationScoped` instances are cached in thread-locals, as in an HTTP request.
- **Stale instances.** A code path that swaps the bean store of those contexts without deactivating them would now see stale cached instances. `AbstractContext.destroy(Contextual)` and `clearAndSet()` already invalidate the cache.
- **Nested activation.** Activating the unbound request context while another request cache is active on the same thread (not a valid state anyway) ends the outer cache; the outer request then runs uncached.

## Testing

Full Weld CI on JDK 21: unit tests, in-container tests, CDI TCK on WildFly, CDI TCK SE, relaxed mode and examples. All runs used the exact PR content plus a fork-only CI workflow commit.

| branch | run | result |
|---|---|---|
| #3545 head (without this change) | https://github.com/AngeloRubens/core/actions/runs/37904857772 | all jobs green, incl. CDI TCK + TCK SE + signature test |
| #3546 head (without this change) | https://github.com/AngeloRubens/core/actions/runs/37904857540 | all jobs green, incl. CDI TCK + TCK SE |
| this PR, main | https://github.com/AngeloRubens/core/actions/runs/37904858396 | all jobs green, incl. CDI TCK + TCK SE + signature test |
| this PR, 6.0 | https://github.com/AngeloRubens/core/actions/runs/37904858262 | all jobs green, incl. CDI TCK + TCK SE |

An earlier version without the `AbstractBoundContext` flush failed `NaiveClusterTest.testMultipleDependentObjectsSessionReplication`: https://github.com/AngeloRubens/core/actions/runs/37112296518.

## Performance

All columns were run sequentially on the same GitHub Actions runner, with one runner for each thread count. Values are JMH throughput in ops/µs (99.9 % CI); higher is better.
- Full run: https://github.com/AngeloRubens/cdi-performance/actions/runs/37904967425
- 1 thread: 2 forks × 5 × 2 s.
- 4 threads: 3 forks × 7 × 2 s.

| `@RequestScoped` client proxy call | Weld 7.0.0 | #3545 | **#3545 + this PR** | Weld 6.0.4 | #3546 | **#3546 + this PR** | OWB 4.1.1 |
|---|---:|---:|---:|---:|---:|---:|---:|
| requestScoped, 1 thread | 10.58 ± 0.14 | 22.48 ± 0.53 | **142.51 ± 2.56** | 10.53 ± 0.09 | 22.62 ± 0.32 | **140.94 ± 0.56** | 88.06 ± 0.98 |
| requestScoped (no interceptors in deployment), 1 thread | 10.59 ± 0.12 | 22.47 ± 0.21 | **142.54 ± 1.88** | 10.66 ± 0.08 | 21.85 ± 0.46 | **141.13 ± 0.61** | 97.20 ± 1.09 |
| requestScoped, 4 threads | 19.57 ± 1.59 | 52.09 ± 0.88 | **309.54 ± 3.66** | 19.24 ± 0.54 | 52.97 ± 2.48 | **309.01 ± 3.57** | 222.80 ± 3.45 |

- **Speed-up over the base PR:** about 6.3× (1 thread) and 5.9× (4 threads).
- **Speed-up over the released versions:** about 13.5× (Weld 7.0.0) and 13.4× (Weld 6.0.4).
- **Other scenarios** are unchanged within error with respect to #3545/#3546: application-scoped, intercepted and non-intercepted methods, events, and boot/shutdown (Weld 7: 46.9 ± 3.5 → 47.9 ± 3.3 ms).

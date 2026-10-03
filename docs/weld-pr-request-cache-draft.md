# DRAFT – Pull request for weld/core `main`: RequestScopedCache for the unbound request context — not submitted

**Source branch:** https://github.com/AngeloRubens/core/tree/perf/request-cache
(stacked on top of `perf/client-proxy`; drop the `[fork-only]` commit before opening the PR)

**Title:** WELD-XXXX Use RequestScopedCache in the unbound request context

## Summary

The unbound `RequestContext` (used by `RequestContextController`, `@ActivateRequestContext` and Weld SE) never
began a `RequestScopedCache`, unlike `BoundRequestContextImpl` and the HTTP request context. Every invocation of a
`@RequestScoped` client proxy therefore went through `BeanManagerImpl.getContext()` and the bean store, and the
interception/decoration context stack could not be cached for the request.

1. `RequestContextImpl.activate()` begins the cache, `deactivate()` ends it *before* the instances are destroyed.
2. `AbstractBoundContext.deactivate()` flushes the cache (`RequestScopedCache.invalidate()`): with the cache now active
   in more situations, a bound context (e.g. the session context) may be deactivated, dissociated and associated with
   another bean store while the cache is active. `NaiveClusterTest.testMultipleDependentObjectsSessionReplication`
   caught exactly this (it failed with change 1 alone and passes with change 2).

## Semantic considerations for reviewers

* While the unbound request context is active, `@RequestScoped`, `@SessionScoped` and `@ConversationScoped` instances
  are cached in thread-locals (as in an HTTP request). Any code path that swaps the bean store of those contexts
  without deactivating them would now see stale cached instances; `AbstractContext.destroy(Contextual)` and
  `clearAndSet()` already invalidate the cache.
* Activating the unbound request context while another request cache is active on the thread (not a valid state
  anyway) ends the outer cache; the outer request then simply runs uncached.

## Benchmarks

Full run https://github.com/AngeloRubens/cdi-performance/actions/runs/37113133484 (same runner for all columns, ops/µs):

| requestScoped | 7.0.0.Final | client-proxy PR | this PR (stacked) | OWB 4.1.1 |
|---|---|---|---|---|
| 1 thread | 12.94 ± 0.11 | 26.24 ± 0.05 | **132.70 ± 0.23** | 99.84 ± 1.58 |
| 4 threads | 26.24 ± 4.24 | 62.78 ± 3.77 | **272.63 ± 2.83** | 270.52 ± 2.18 |

Legacy loop test, `@RequestScoped`: 4529 ms (7.0.0) → 2794 ms (client-proxy) → 461 ms (this PR); OWB 525 ms.
Other benchmarks are unchanged with respect to the client-proxy PR (within error).
Weld 6.0 backport (`perf/request-cache-6.0`): requestScoped 10.48 ± 0.35 (6.0.4) → 106.51 ± 0.43,
run https://github.com/AngeloRubens/cdi-performance/actions/runs/37113771427.

## Testing

All green, JDK 21:
* 7.x: https://github.com/AngeloRubens/core/actions/runs/37113016403 (w/o container 3267 tests, in-container 2086, CDI TCK 2089, TCK SE 36, relaxed 5342, 0 failures)
* 6.0 backport: https://github.com/AngeloRubens/core/actions/runs/37113764340 (w/o container 2807, in-container 1811, CDI TCK 1903, TCK SE 34, relaxed 4697, 0 failures)

An earlier version without the `AbstractBoundContext` flush failed `NaiveClusterTest.testMultipleDependentObjectsSessionReplication`
(run https://github.com/AngeloRubens/core/actions/runs/37112296518, cancelled after the failure).

# DRAFT – WELD Jira ticket (not submitted)

**Project:** WELD  
**Type:** Enhancement  
**Component:** Proxies / Interceptors / Contexts  
**Affects versions:** 7.0.0.Final, 6.0.4.Final  
**Summary:** Client proxy and intercepted-subclass invocations allocate and set/remove a ThreadLocal on every call outside of a request

## Description

A JMH comparison of Weld and OpenWebBeans (https://github.com/AngeloRubens/cdi-performance, branch `jakarta-2026`)
shows that invoking a method through a Weld client proxy is ~40x slower than through an OpenWebBeans proxy
(`@ApplicationScoped`: Weld 7.0.0 ≈ 20 ops/µs vs OWB 4.1.1 ≈ 720–850 ops/µs on GitHub-hosted runners, JDK 21).

Profiling with async-profiler and `-prof gc` (Weld 7.0.0.Final, Weld SE, no request context active):

* 168 B allocated per invocation of an `@ApplicationScoped` / `@RequestScoped` client proxy, 400 B for an intercepted method;
* ~78 % of the CPU time of an `@ApplicationScoped` proxy invocation is spent in
  `InterceptionDecorationContext.startIfNotEmpty()`: `getStack()` creates a new `Stack` + `ArrayDeque`, sets it into the
  thread-local, and `removeIfEmpty()` immediately calls `ThreadLocal.remove()` (which clears the entry's weak reference
  via the native `Reference.clear0` and expunges the entry). The net effect is nothing, because the stack was empty.
* The same set/remove cycle happens for every invocation of an intercepted subclass (`startIfNotOnTop()`), including
  non-intercepted methods of an intercepted bean.
* `ContextBeanInstance.getInstance()` performs `Container.isSet(contextId)` (a `ConcurrentHashMap` lookup) on every call.
* The unbound request context (`RequestContextController`, `@ActivateRequestContext`, Weld SE) does not begin a
  `RequestScopedCache`, so `@RequestScoped` proxies always go through `BeanManagerImpl.getContext()` and the bean store.

## Proposed changes

1. `InterceptionDecorationContext.startIfNotEmpty()`: return `null` immediately when there is no stack on the current
   thread, instead of creating and removing one (semantically identical).
2. `Stack.removeIfEmpty()`: `ThreadLocal.set(null)` instead of `remove()` (entry is reused; a null value does not retain any
   Weld class, so no class loader leak), and a smaller initial `ArrayDeque` capacity.
3. `ContextBeanInstance`: keep the `Container` it was created for and only do the registry lookup once that container has
   been cleaned up (new volatile `Container.cleanedUp` flag set at the beginning of `cleanup()`).
4. (separate, more invasive) begin/end the `RequestScopedCache` in the unbound `RequestContextImpl` like
   `BoundRequestContextImpl` does, and flush the cache when a bound context is deactivated.

## Measured effect (JMH, 1 thread, ops/µs, GitHub runner, JDK 21; cumulative, run https://github.com/AngeloRubens/cdi-performance/actions/runs/37112321830 — (4) from the full run https://github.com/AngeloRubens/cdi-performance/actions/runs/37113133484)

| benchmark | 7.0.0.Final | + (1) | + (2) | + (3) | + (4) |
|---|---|---|---|---|---|
| applicationScoped | 20.4 | 138.3 | 138.7 | 187.3 | = |
| requestScoped (unbound request context) | 10.0 | 21.5 | 20.4 | 21.8 | 132.7 vs 12.9 baseline in the full run |
| methodIntercepted | 7.0 | 9.9 | 14.8 | 15.5 | = |
| methodNotIntercepted (non-intercepted method of an intercepted bean) | 9.5 | 16.7 | 35.8 | 38.4 | = |

Allocation per `@ApplicationScoped`/`@RequestScoped` invocation drops from 168 B to 0 B, for an intercepted invocation from 400 B to 152 B.

Weld 6.0.x is affected in the same way; the patches apply cleanly to the `6.0` branch (backport branches
`perf/client-proxy-6.0`, `perf/request-cache-6.0` in https://github.com/AngeloRubens/core), with the same speed-ups.

Prototype branches (all with green Weld CI incl. CDI TCK on JDK 21): https://github.com/AngeloRubens/core/tree/perf/client-proxy ,
https://github.com/AngeloRubens/core/tree/perf/request-cache

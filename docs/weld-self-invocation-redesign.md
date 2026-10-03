# Weld: why client proxies need the interception-context ThreadLocal, and how to get rid of it

*Design note (no code). Status: draft for discussion with the Weld maintainers, nothing has been sent upstream.
Numbers refer to the JMH benchmarks in this repository (`CdiBenchmark`, `CdiNoInterceptorBenchmark`), GitHub
runners, JDK 21, 1 thread; see `readme.md` for the runs.*

## 1. What the ThreadLocal is for

Weld implements interception and decoration by **subclassing**: the contextual instance of an intercepted bean
*is* an instance of the generated `Foo$Proxy$_$$_WeldSubclass`, so inside the bean `this` is the subclass. Every
call through `this` therefore goes through the overriding subclass method again. To give self-invocations the
semantics Weld has always had (a call through `this` while a business method of the same instance is running is
**not** intercepted again), Weld keeps a per-thread stack of "interception contexts",
`InterceptionDecorationContext` (IDC):

| who | what it does with the IDC |
|---|---|
| intercepted method of the subclass | `getStack()`, `isDisabledHandler(stack)`: if its own handler is on top this is a self-invocation, call `super` directly; otherwise push the handler, run the chain, pop |
| **non-intercepted** method of an intercepted subclass | `startIfNotOnTop(handler)`: push its handler, call `super`, pop. Without it a self-invocation of an intercepted method from a non-intercepted one *would* be intercepted |
| lifecycle callbacks, final observer methods, `@AutoClose` | push the handler so that methods called from them are not intercepted |
| **client proxy** (every method of every normal-scoped bean) | `startIfNotEmpty()`: if the current thread is inside an interception context, push `NULL_INSTANCE` for the duration of the call |

### Why the client proxy has to push `NULL_INSTANCE`

Calling a method on a client proxy means leaving the interception context of the caller. Concrete case
(`SelfInvokingClassTest` in Weld's test suite covers a variant with two normal-scoped beans):

```
A  @Dependent, intercepted (handler hA)       B  @ApplicationScoped (client proxy)

A.foo()                       -- via A's subclass: hA pushed, interceptors run, then super.foo()
  b.bar()                     -- via B's client proxy
    a.baz()                   -- B holds a *direct* reference to A's subclass instance
                                 (A is @Dependent and passed `this`, or B got it injected)
```

`a.baz()` is a business method invocation by another bean and must be intercepted. But when it reaches A's
subclass, the top of the IDC is still `hA` (pushed by `A.foo()`), so `isDisabledHandler` says "self-invocation"
and the interceptors are skipped. The `NULL_INSTANCE` pushed by B's client proxy hides `hA`, so `a.baz()` is
intercepted as it should. Not pushing it in client proxies of non-intercepted beans is therefore wrong in general:
B itself has no interceptors, but it calls back into A.

So **every client proxy call** has to look at the thread-local, even for beans and deployments without
interceptors. That `ThreadLocal.get()` is the single largest cost of a Weld client proxy invocation after the
first round of patches (about 40 % of `applicationScoped` in the async-profiler profile).

## 2. How OpenWebBeans avoids it (verified in the OWB 4.1.1 sources)

`org.apache.webbeans.proxy.InterceptorDecoratorProxyFactory` generates a subclass too, but it is a **delegating**
proxy: the generated class has a field `owbIntDecProxiedInstance` holding a *separate*, plain instance of the bean
class, created by the normal constructor. Its javadoc:

> Any non-intercepted or decorated method will get delegated natively, all intercepted and decorated methods will
> get invoked via an InvocationHandler chain.

- `delegateNonInterceptedMethods()` emits `getfield owbIntDecProxiedInstance; invokevirtual method` and nothing else.
- `delegateInterceptedMethods()` forwards to the `InterceptorHandler` (`DefaultInterceptorHandler`) with the
  `Method` object.
- Inside the bean, `this` is the plain inner instance, so **self-invocation is never intercepted**, structurally,
  without any thread state. A reference to the bean that escapes via `this` is the raw instance as well, so calls
  through it are never intercepted either (in the example above `a.baz()` would *not* be intercepted in OWB).
- Normal-scoped client proxies (`NormalScopedBeanInterceptorHandler`) therefore carry no interception state at all.
  `ApplicationScopedBeanInterceptorHandler` additionally caches the contextual instance in a plain (non-volatile)
  field and never invalidates it, i.e. after `AlterableContext.destroy()` of an `@ApplicationScoped` bean the proxy
  keeps using the destroyed instance (the javadoc also warns it is unsafe in EAR scenarios). Weld cannot copy that
  shortcut as is.

## 3. What has been done so far (fork `AngeloRubens/core`, branch `perf/proxy-2`)

| change | effect on the IDC |
|---|---|
| (a) `startIfNotEmpty()` does not create a stack | client proxies: one `ThreadLocal.get()` instead of get + allocate + set + remove |
| (a2) `set(null)` instead of `remove()` | cheaper churn for intercepted calls outside a request |
| (e) per-thread stack reused, held via an `Object[]` holder (strong while non-empty, weak while empty) | intercepted and non-intercepted subclass methods no longer allocate a stack or set the thread-local; no Weld class is retained by an idle thread |
| (f) `SwitchPoint`-guarded constant `MethodHandle` in `startIfNotEmpty()`, invalidated when the first per-thread holder is created | client proxies skip the `ThreadLocal.get()` as long as no thread has ever entered an interception context (always true for deployments without interceptors/decorators). Exactly equivalent to the old code: a thread can only have a stack if it created the holder itself, and the invalidation happens before that. The JIT compiles the guard away, so applications that use interception pay nothing (a plain static boolean flag cost ~8 % there and was replaced) |

(f) helps only applications that never invoke an intercepted method; in a typical application the switch point is
invalidated during the first request and the client proxy is back to one `ThreadLocal.get()`.

## 4. Alternatives

Gains are rough estimates from the profiles and the experiments in the report; "TL" = the `ThreadLocal.get()` in
client proxies, "NI" = the push/pop in non-intercepted subclass methods.

| # | idea | removes | gain (estimate) | risk | notes |
|---|---|---|---|---|---|
| 1 | **status quo + (a), (a2), (e), (f)** | allocation and set/remove churn | done | low | baseline for everything below |
| 2 | global counter of active stacks (`int`/`LongAdder`), client proxies skip TL while it is 0 | TL while no thread is intercepting | big when interception is rare, nothing under load with interceptors | medium | contended writes on every outermost intercepted call on every thread (cache-line ping-pong); a stale read must never skip a needed push, which needs a volatile read plus careful ordering |
| 3 | **bytecode analysis of non-intercepted methods**: call `super` directly (no NI) when the method body provably cannot self-invoke (no `invoke*` on `aload_0`, `this` never stored/passed, no lambdas capturing `this`) | NI for trivial methods (getters, setters, pure computations) | `methodNotIntercepted` close to OWB for such methods; nothing for methods that call other methods on `this` | medium | needs a bytecode reader at proxy generation time (Weld only has a writer, classfilewriter); must be conservative; costs boot time; inherited/private helper methods complicate it |
| 4 | **do not override non-intercepted methods** (experiment `perf/exp-no-override`) | NI completely | `methodNotIntercepted` x4.2 (55 → 233 ops/µs, run 37134628349) | **semantic change**: a self-invocation of an intercepted method from a non-intercepted one becomes intercepted | CDI TCK (WildFly and SE) stays green, but 3 Weld tests fail (`SelfInvocationTest.testSelfInvocationOfInterceptedMethod`, `SelfInvocationInterceptionTest.testDirectMethodInvocation`, `SelfInjectionBeanTest.testMethodB`, run https://github.com/AngeloRubens/core/actions/runs/37134570418): Weld documents the behaviour in its own tests. Would need a maintainer decision and probably an opt-in flag |
| 5 | **delegating interceptor proxy like OWB** | TL and NI completely; also the `return this` check | `applicationScoped` and `methodNotIntercepted` at OWB level | **high** | changes observable behaviour: `this` escapes as the raw instance (the example in §1 would no longer be intercepted), identity of the contextual instance vs subclass, two objects per bean, constructor and field-initialiser semantics, passivation format, `WeldSubclass`/`InterceptionFactory`, EJB and WildFly integration, private-method observers. A multi-release project, not a patch |
| 6 | `ScopedValue` (JDK 25) instead of `ThreadLocal` | TL cost partially | small/unclear | medium | `ScopedValue.get()` is cheap but binding needs a lambda/`call()` frame per push, changing generated code; not available on the Java 17 baseline of Weld 6 |
| 7 | push only for intercepted beans and remember the "outer" handler per proxy | – | – | – | does not work: the problem in §1 is caused by a *non-intercepted* bean (B) that calls back into an intercepted one |
| 8 | per-instance "in call" flag instead of a thread stack | TL | – | – | not thread-safe for shared (normal-scoped) instances: thread 2 would see thread 1's flag |
| 9 | leaner stack: a plain array + `int` index instead of `ArrayDeque`, and pass the stack obtained by the client proxy on instead of a second `ThreadLocal.get()` in the subclass | part of NI | profile of `methodNotIntercepted` after (e): ~45 % in `ArrayDeque`/`Stack` push/pop, ~15 % in the two `ThreadLocal.get()`; estimate +30–60 %, still far from OWB | low | not tried; the second part needs a change of the generated bytecode |

Recommended order: keep 1 (upstream as small PRs), prototype 3 behind a system property and measure on real
applications, discuss 4 and 5 only if the maintainers consider the current self-invocation semantics negotiable.

## 5. Questions for the Weld maintainers

1. Is "a self-invocation is never intercepted, also not from a non-intercepted method" a documented guarantee of
   Weld or an implementation detail? The CDI specification leaves self-interception largely unspecified (CDI Lite
   explicitly makes it non-portable). Are there users relying on it?
2. Is the `NULL_INSTANCE` push in client proxies (the §1 case) considered required behaviour? It is what forces the
   thread-local lookup into every client proxy call.
3. Change (e) drops the `RequestScopedCache` registration of the IDC stack (WELD-1812). Was there any reason for it
   besides avoiding the thread-local set/remove churn?
4. Change (f) uses a JVM-wide (per class loader) sticky flag. In WildFly Weld is a shared module, so one deployment
   using interceptors disables the shortcut for all; is that acceptable, or should it be per container?
5. Would a bytecode-analysis based shortcut (alternative 3) be acceptable in principle, e.g. behind a configuration
   key (`org.jboss.weld.proxy.nonInterceptedMethodAnalysis`)?
6. For `@ApplicationScoped`, would you accept caching the instance in the client proxy itself (with invalidation
   from `ApplicationScopedContextualInstanceStrategy.destroy()` through a per-bean generation counter)? The measured
   gain of a smaller shortcut is in the report; the full cache only pays off once the TL is gone too.

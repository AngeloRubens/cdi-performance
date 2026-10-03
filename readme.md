# A small application to run a lightweight micro benchmark for CDI beans.

Currently it only fires up 100 concurrent Threads and does 10 Million invocations on a very simple bean.


## The numbers I've collected on my MBP15 so far:

All tests are run with Java7.

Apache Maven 3.2.1 (ea8b2b07643dbb1b84b6d16e1f08391b666bc1e9; 2014-02-14T18:37:52+01:00)
Maven home: /opt/apache/maven
Java version: 1.7.0_51, vendor: Oracle Corporation
Java home: /Library/Java/JavaVirtualMachines/jdk1.7.0_51.jdk/Contents/Home/jre
Default locale: de_DE, platform encoding: UTF-8
OS name: "mac os x", version: "10.10", arch: "x86_64", family: "mac"
 
### OWB-1.2.6
<pre>$> mvn clean install</pre>
* Test invocation on @ApplicationScoped bean which got injected into another @ApplicationScoped bean TOOK: 13 ms
* Test invocation on ApplicationScoped bean TOOK: 20 ms
* Test invocation on @RequestScoped bean TOOK: 648 ms

### OWB-1.2.0
<pre>$> mvn clean install -Dowb.version=1.2.0</pre>
* Test invocation on @ApplicationScoped bean which got injected into another @ApplicationScoped bean TOOK: 40 ms
* Test invocation on ApplicationScoped bean TOOK: 18 ms
* Test invocation on @RequestScoped bean TOOK: 918 ms

### OWB-1.1.6
<pre>$> mvn clean install -Dowb.version=1.1.6</pre>
* Test invocation on @ApplicationScoped bean which got injected into another @ApplicationScoped bean TOOK: 7138 ms
* Test invocation on ApplicationScoped bean TOOK: 7008 ms
* Test invocation on @RequestScoped bean TOOK: 11979 ms

### OWB-1.1.8<pre>
<pre>$> mvn clean install -Dowb.version=1.1.8</pre>
* Test invocation on @ApplicationScoped bean which got injected into another @ApplicationScoped bean TOOK: 7192 ms
* Test invocation on ApplicationScoped bean TOOK: 6914 ms
* Test invocation on @RequestScoped bean TOOK: 12111 ms

### OWB-1.5.0-SNAPSHOT (CDI-1.2)
<pre>$> mvn clean install -POWB15 -Dowb.version=1.5.0-SNAPSHOT</pre>
* Test invocation on @ApplicationScoped bean which got injected into another @ApplicationScoped bean TOOK: 23 ms
* Test invocation on ApplicationScoped bean TOOK: 18 ms
* Test invocation on @RequestScoped bean TOOK: 625 ms

### Weld-1.1.23.Final
<pre>$> mvn clean install -PWeld -Dweld.version=1.1.23.Final</pre>
* Test invocation on @ApplicationScoped bean which got injected into another @ApplicationScoped bean TOOK: 16258 ms
* Test invocation on ApplicationScoped bean TOOK: 15447 ms
* Test invocation on @RequestScoped bean TOOK: 2592 ms

### Weld-2.2.5.Final
<pre>$> mvn clean install -PWeld -Dweld.version=2.2.5.Final</pre>
* Test invocation on @ApplicationScoped bean which got injected into another @ApplicationScoped bean TOOK: 18408 ms
* Test invocation on ApplicationScoped bean TOOK: 17812 ms
* Test invocation on @RequestScoped bean TOOK: 2552 ms

### Weld-2.2.6.Final
<pre>$> mvn clean install -PWeld -Dweld.version=2.2.6.Final</pre>
* Test invocation on @ApplicationScoped bean which got injected into another @ApplicationScoped bean TOOK: 17751 ms
* Test invocation on ApplicationScoped bean TOOK: 16916 ms
* Test invocation on @RequestScoped bean TOOK: 2593 ms



# disk footprint

I've also collected numbers about the size of all the jars needed:

### OpenWebBeans-1.2.6

<pre>
$> mvn clean dependency:copy-dependencies -DincludeScope=compile
$> du -hs target/dependency/
   952K target/dependency/
</pre>

One can see that the flexible plugin structure of Apache OpenWebBeans really pays off.

### Weld-2.2.6.Final

<pre>
$> mvn clean dependency:copy-dependencies -DincludeScope=compile -PWeld
$> du -hs target/dependency/
3.8M    target/dependency/
</pre>


[1] For @ApplicationScoped beans our proxies resolve the contextual instance only once. 
Thus you get the benefits of a Proxy (serializability, interceptors, decorators, cycle prevention, shield against scope differences)
for the costs of (almost) native invocation (Creating 'underTest' via new instead of the CDI bean will run the test in 8ms). 

# 2026 results (Jakarta CDI, JMH)

Branch `jakarta-2026` of https://github.com/AngeloRubens/cdi-performance ports the benchmark to Jakarta CDI and adds a
JMH version (`at.struct.cdi.performance.jmh.CdiBenchmark`, results consumed by a `Blackhole`), profiling
(`.github/workflows/profile.yml`: JMH `-prof gc` + async-profiler flamegraphs) and a CI job that runs every
implementation sequentially on the same GitHub runner (`.github/workflows/benchmark.yml`, `scripts/bench.sh`,
`scripts/report.py`). It can also build and benchmark patched Weld branches (`.github/weld-refs`, `scripts/bench-patched.sh`).

JDK 21, ubuntu-latest runner, Weld SE / OWB SE, ops/µs (higher is better), 1 thread, 2 forks × 5 iterations, all
columns on the same runner. Run: https://github.com/AngeloRubens/cdi-performance/actions/runs/37141471463

| benchmark | OWB 4.1.1 | Weld 6.0.4 | Weld 7.0.0 | Weld 7 main (unpatched) | Weld 7 request-cache¹ | Weld 7 proxy-2² | Weld 6 proxy-2² |
|---|---|---|---|---|---|---|---|
| applicationScoped | 718.9 ± 2.3 | 19.8 ± 0.7 | 19.8 ± 0.8 | 19.1 ± 1.6 | 187.1 ± 0.5 | 327.1 ± 6.3 | 326.9 ± 4.1 |
| applicationScoped, interception used³ | 717.4 ± 4.6 | 20.4 ± 0.2 | 20.0 ± 0.3 | 19.9 ± 1.1 | 187.4 ± 0.3 | 199.3 ± 1.1 | 199.7 ± 0.5 |
| applicationScoped, no interceptors³ | 717.6 ± 2.6 | 20.5 ± 0.0 | 20.2 ± 0.1 | 20.4 ± 0.5 | 187.4 ± 0.6 | 325.0 ± 6.3 | 324.9 ± 1.5 |
| requestScoped | 90.8 ± 1.8 | 10.4 ± 0.1 | 10.5 ± 0.1 | 10.6 ± 0.0 | 106.4 ± 0.6 | 135.8 ± 8.7 | 141.2 ± 0.6 |
| classIntercepted | 10.1 ± 0.3 | 7.1 ± 0.0 | 7.1 ± 0.0 | 7.1 ± 0.0 | 15.9 ± 0.1 | 16.5 ± 0.2 | 17.1 ± 0.4 |
| methodIntercepted | 9.7 ± 0.6 | 7.1 ± 0.0 | 7.1 ± 0.2 | 7.1 ± 0.0 | 14.9 ± 0.1 | 16.8 ± 0.7 | 16.7 ± 0.1 |
| methodNotIntercepted | 505.2 ± 0.9 | 9.3 ± 0.6 | 9.7 ± 0.1 | 9.6 ± 0.0 | 38.6 ± 0.1 | 46.5 ± 0.1 | 46.3 ± 0.3 |
| fireEvent | 3.1 ± 0.0 | 44.8 ± 1.9 | 43.5 ± 0.1 | 43.5 ± 0.2 | 43.4 ± 0.1 | 42.9 ± 1.1 | 43.4 ± 0.1 |
| boot + shutdown (ms, lower is better) | 46.2 ± 2.3 | 47.7 ± 3.6 | 45.5 ± 2.8 | 46.6 ± 2.8 | 45.4 ± 3.3 | 50.7 ± 3.1 | 47.3 ± 3.0 |

Prototype branches of https://github.com/AngeloRubens/core, not part of any Weld release:
¹ `perf/request-cache`: no empty interception-context stack per call, no `ThreadLocal.remove()` churn, no registry
  lookup per call (`perf/client-proxy`), plus Weld's `RequestScopedCache` for the unbound request context.
² `perf/proxy-2` / `perf/proxy-2-6.0`: additionally a reused per-thread interception stack, a `SwitchPoint` that
  skips the thread-local lookup in client proxies until any interception context was ever used, and a shortcut to
  the bean's contextual-instance strategy.
³ `CdiNoInterceptorBenchmark`: a container without interceptors/decorators, and the regular deployment after one
  intercepted call. `CdiBenchmark.applicationScoped` never invokes an intercepted method in its JVM, so it behaves
  like the no-interceptor case for proxy-2.

Findings: released Weld spends most of a client proxy invocation creating and removing a thread-local
interception-context stack (168 B per call). With the prototypes Weld is faster than OWB for intercepted methods and
`@RequestScoped` beans and 2.2x (no interception in use) to 3.6x (interception in use) slower for
`@ApplicationScoped`, whose OWB proxy caches the instance without invalidation. `methodNotIntercepted` stays ~11x
slower because Weld deliberately suppresses interception of self-invocations from non-intercepted methods (see
`docs/weld-self-invocation-redesign.md`). Weld's event delivery is ~14x faster than OWB's here. The unpatched
Weld `main` snapshot performs like 7.0.0.Final.

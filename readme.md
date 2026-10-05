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
columns on the same runner. Run: https://github.com/AngeloRubens/cdi-performance/actions/runs/37188810113

| benchmark | OWB 4.1.1 | Weld 6.0.4 | Weld 7.0.0 | Weld 7 proxy-2¹ | Weld 7 proxy-3² | Weld 6 proxy-2¹ | Weld 6 proxy-3² |
|---|---|---|---|---|---|---|---|
| applicationScoped | 713.7 ± 3.1 | 20.3 ± 0.1 | 19.9 ± 0.5 | 326.4 ± 5.7 | 324.4 ± 2.6 | 326.5 ± 3.3 | 325.8 ± 4.1 |
| applicationScoped, interception used³ | 718.6 ± 4.3 | 20.4 ± 0.2 | 20.4 ± 0.2 | 199.5 ± 0.9 | 199.5 ± 0.9 | 200.2 ± 0.2 | 199.7 ± 0.2 |
| applicationScoped, no interceptors³ | 718.4 ± 3.3 | 20.1 ± 0.5 | 20.5 ± 0.2 | 325.2 ± 2.8 | 327.0 ± 3.2 | 325.9 ± 2.4 | 326.8 ± 1.4 |
| requestScoped | 90.4 ± 1.6 | 10.1 ± 0.1 | 10.6 ± 0.0 | 142.6 ± 2.6 | 141.4 ± 0.5 | 142.8 ± 2.2 | 142.5 ± 2.3 |
| classIntercepted | 10.0 ± 0.1 | 7.1 ± 0.1 | 7.1 ± 0.0 | 16.7 ± 0.4 | 18.3 ± 0.7 | 16.6 ± 0.3 | 17.8 ± 0.4 |
| methodIntercepted | 9.9 ± 0.1 | 7.1 ± 0.1 | 6.7 ± 0.2 | 16.6 ± 0.4 | 19.0 ± 0.1 | 16.4 ± 0.0 | 18.4 ± 0.8 |
| methodNotIntercepted | 505.7 ± 0.5 | 9.7 ± 0.1 | 9.7 ± 0.1 | 46.3 ± 0.6 | 60.1 ± 2.4 | 46.4 ± 0.1 | 61.5 ± 0.3 |
| fireEvent | 3.1 ± 0.2 | 41.0 ± 3.1 | 43.3 ± 0.9 | 43.6 ± 0.2 | 41.8 ± 2.9 | 44.4 ± 1.4 | 41.7 ± 2.9 |
| boot + shutdown (ms, lower is better) | 43.1 ± 2.5 | 46.5 ± 3.5 | 45.4 ± 2.8 | 45.6 ± 2.9 | 45.6 ± 2.9 | 46.4 ± 3.1 | 48.2 ± 4.0 |

Prototype branches of https://github.com/AngeloRubens/core, not part of any Weld release:
¹ `perf/proxy-2` / `perf/proxy-2-6.0`: no empty interception-context stack per call, no `ThreadLocal.remove()`
  churn, no registry lookup per call, a reused per-thread interception stack, a `SwitchPoint` that skips the
  thread-local lookup in client proxies until any interception context was ever used, and a shortcut to the bean's
  contextual-instance strategy. (The earlier `perf/request-cache` branches additionally enable Weld's
  `RequestScopedCache` for the unbound request context; previous run with them and Weld `main`:
  https://github.com/AngeloRubens/cdi-performance/actions/runs/37141471463.)
² `perf/proxy-3` / `perf/proxy-3-6.0`: proxy-2 plus an array based interception-context stack instead of an
  `ArrayDeque` (`docs/weld-pr-proxy-3-draft.md`).
³ `CdiNoInterceptorBenchmark`: a container without interceptors/decorators, and the regular deployment after one
  intercepted call. `CdiBenchmark.applicationScoped` never invokes an intercepted method in its JVM, so it behaves
  like the no-interceptor case for the prototypes.

Findings: released Weld spends most of a client proxy invocation creating and removing a thread-local
interception-context stack (168 B per call). With the prototypes Weld is faster than OWB for intercepted methods
(x1.9) and `@RequestScoped` beans (x1.6) and 2.2x (no interception in use) to 3.6x (interception in use) slower for
`@ApplicationScoped`, whose OWB proxy caches the instance without invalidation. `methodNotIntercepted` stays ~8x
slower because Weld deliberately suppresses interception of self-invocations from non-intercepted methods (see
`docs/weld-self-invocation-redesign.md`); proxy-3 makes that path 30 % faster. Weld's event delivery is ~14x faster
than OWB's here.

## Proxy-4 work in progress

The interceptor optimization candidate and Weld 6 backport await final CI and benchmark validation. See the [recovery status and measured incremental results](docs/weld-proxy-4-status.md) and [draft PR](docs/weld-pr-proxy-4-draft.md). Existing proxy-3 results above remain the last completed comparison.

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

JDK 21, ubuntu-latest runner, Weld SE / OWB SE, ops/µs (higher is better), 1 thread, 2 forks × 5 iterations.
Run: https://github.com/AngeloRubens/cdi-performance/actions/runs/37113133484

| benchmark | OWB 4.1.1 | Weld 6.0.4 | Weld 7.0.0 | Weld 7 patched¹ | Weld 7 patched + request cache² | Weld 6 patched¹ |
|---|---|---|---|---|---|---|
| applicationScoped | 979.3 ± 8.5 | 25.0 ± 0.1 | 25.0 ± 0.2 | 244.0 ± 2.1 | 244.4 ± 1.4 | 244.8 ± 1.3 |
| requestScoped | 99.8 ± 1.6 | 12.5 ± 0.1 | 12.9 ± 0.1 | 26.2 ± 0.0 | 132.7 ± 0.2 | 22.5 ± 2.7 |
| classIntercepted | 12.8 ± 0.0 | 8.8 ± 0.2 | 8.8 ± 0.1 | 19.2 ± 0.2 | 17.5 ± 2.9 | 19.3 ± 0.2 |
| methodIntercepted | 12.9 ± 0.2 | 8.8 ± 0.1 | 8.9 ± 0.1 | 19.5 ± 0.3 | 19.5 ± 0.6 | 19.4 ± 0.2 |
| methodNotIntercepted | 696.5 ± 4.5 | 12.3 ± 0.1 | 12.4 ± 0.1 | 50.9 ± 0.7 | 50.5 ± 0.2 | 47.8 ± 5.0 |
| fireEvent | 4.4 ± 0.0 | 55.1 ± 1.7 | 55.0 ± 1.6 | 51.8 ± 3.3 | 55.4 ± 2.0 | 51.3 ± 7.4 |
| boot + shutdown (ms, lower is better) | 34.5 ± 2.6 | 34.7 ± 2.5 | 31.7 ± 2.0 | 32.6 ± 2.0 | 32.9 ± 2.5 | 32.5 ± 2.4 |

¹ prototype branches `perf/client-proxy` / `perf/client-proxy-6.0` of https://github.com/AngeloRubens/core
  (no empty interception-context stack per call, no `ThreadLocal.remove()` churn, no registry lookup per call).
² `perf/request-cache`: additionally enables Weld's `RequestScopedCache` for the unbound request context.
  These patches are experimental and not (yet) part of any Weld release.

Findings: OWB's `@ApplicationScoped` proxy caches the contextual instance in the proxy and is still ~4x faster than
the patched Weld (and ~40x faster than released Weld). Released Weld spent most of a client proxy invocation
creating and removing a thread-local interception context stack (168 B allocated per call); with the patches
Weld is faster than OWB for intercepted methods and, with the request cache, for `@RequestScoped` beans.
Weld's event delivery is ~12x faster than OWB's in this benchmark.

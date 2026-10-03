#!/usr/bin/env bash
# Runs the legacy TestNG loop benchmark and the JMH benchmarks for one CDI implementation.
# usage: scripts/bench.sh <label> <maven args...>   e.g. scripts/bench.sh weld7 -PWeld -Dweld.version=7.0.0.Final
# env:   LEGACY=0      skip the legacy TestNG loop test
#        BOOT=0        skip the boot/shutdown benchmark
#        THREADS="1 4" thread counts for the throughput benchmarks
#        BENCH=regex   JMH benchmark selection (default: all throughput benchmarks of CdiBenchmark and CdiNoInterceptorBenchmark)
#        BENCH_MT=regex selection for the runs with more than 1 thread (default: CdiBenchmark only)
#        JMH_THROUGHPUT="-f 2 -wi 3 -w 2s -i 5 -r 2s"
set -euo pipefail
label=$1; shift
out=results/$label
mkdir -p "$out"

if [ "${LEGACY:-1}" = 1 ]; then
  mvn -B -ntp clean verify "$@" | tee "$out/legacy-test.log"
  grep -E '^Test .* TOOK' "$out/legacy-test.log" > "$out/legacy.txt"
else
  mvn -B -ntp -q clean compile "$@"
fi

mvn -B -ntp -q dependency:build-classpath -Dmdep.outputFile=target/cp.txt "$@"
cp="target/classes:$(cat target/cp.txt)"
mvn -B -ntp -q dependency:list -DexcludeTransitive=false -DincludeScope=runtime "$@" -DoutputFile="$(pwd)/$out/dependencies.txt" || true

JMH_THROUGHPUT=${JMH_THROUGHPUT:--f 2 -wi 3 -w 2s -i 5 -r 2s}
BENCH=${BENCH:-'Cdi(NoInterceptor)?Benchmark\.(?!bootAndShutdown)'}
# multi-threaded runs only for CdiBenchmark (keeps the full run within the job timeout)
BENCH_MT=${BENCH_MT:-'CdiBenchmark\.(?!bootAndShutdown)'}
for t in ${THREADS:-1 4}; do
  sel=$BENCH; [ "$t" != 1 ] && sel=$BENCH_MT
  java -cp "$cp" org.openjdk.jmh.Main "$sel" \
       $JMH_THROUGHPUT -t $t -rf json -rff "$out/jmh-t$t.json"
done
if [ "${BOOT:-1}" = 1 ]; then
  java -cp "$cp" org.openjdk.jmh.Main 'CdiBenchmark\.bootAndShutdown' \
       -bm ss -tu ms -f 3 -wi 10 -i 20 -rf json -rff "$out/jmh-boot.json"
fi

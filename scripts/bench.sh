#!/usr/bin/env bash
# Runs the legacy TestNG loop benchmark and the JMH benchmarks for one CDI implementation.
# usage: scripts/bench.sh <label> <maven args...>   e.g. scripts/bench.sh weld7 -PWeld -Dweld.version=7.0.0.Final
set -euo pipefail
label=$1; shift
out=results/$label
mkdir -p "$out"

mvn -B -ntp clean verify "$@" | tee "$out/legacy-test.log"
grep -E '^Test .* TOOK' "$out/legacy-test.log" > "$out/legacy.txt"

mvn -B -ntp -q dependency:build-classpath -Dmdep.outputFile=target/cp.txt "$@"
cp="target/classes:$(cat target/cp.txt)"
mvn -B -ntp -q dependency:list -DexcludeTransitive=false -DincludeScope=runtime "$@" -DoutputFile="$(pwd)/$out/dependencies.txt" || true

JMH_THROUGHPUT=${JMH_THROUGHPUT:--f 2 -wi 3 -w 2s -i 5 -r 2s}
for t in 1 4; do
  java -cp "$cp" org.openjdk.jmh.Main 'CdiBenchmark\.(?!bootAndShutdown)' \
       $JMH_THROUGHPUT -t $t -rf json -rff "$out/jmh-t$t.json"
done
java -cp "$cp" org.openjdk.jmh.Main 'CdiBenchmark\.bootAndShutdown' \
     -bm ss -tu ms -f 3 -wi 10 -i 20 -rf json -rff "$out/jmh-boot.json"

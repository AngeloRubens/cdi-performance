#!/usr/bin/env bash
# Profiles selected JMH benchmarks with -prof gc and async-profiler (flamegraph + collapsed stacks).
# usage: ASYNC_PROFILER=/path/to/libasyncProfiler.so scripts/profile.sh <label> <maven args...>
# env:   PROFILE_BENCHMARKS="applicationScoped requestScoped methodIntercepted"
set -euo pipefail
label=$1; shift
out=results/profile-$label
mkdir -p "$out"
mvn -B -ntp -q clean compile "$@"
mvn -B -ntp -q dependency:build-classpath -Dmdep.outputFile=target/cp.txt "$@"
cp="target/classes:$(cat target/cp.txt)"
for b in ${PROFILE_BENCHMARKS:-applicationScoped requestScoped methodIntercepted}; do
  java -cp "$cp" org.openjdk.jmh.Main "Cdi(NoInterceptor)?Benchmark\.$b\$" -f 1 -wi 3 -w 2s -i 3 -r 3s -t 1 \
       -prof gc -rf json -rff "$out/gc-$b.json" | tee "$out/gc-$b.txt"
  java -cp "$cp" org.openjdk.jmh.Main "Cdi(NoInterceptor)?Benchmark\.$b\$" -f 1 -wi 3 -w 2s -i 3 -r 3s -t 1 \
       -prof "async:libPath=$ASYNC_PROFILER;output=flamegraph,collapsed;dir=$out/async-$b" | tee "$out/async-$b.txt"
done

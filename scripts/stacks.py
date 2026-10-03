#!/usr/bin/env python3
"""Summarises async-profiler collapsed stacks: inclusive and self share of the hottest frames.
usage: scripts/stacks.py <collapsed.txt> [top]"""
import collections, sys

path = sys.argv[1]
top = int(sys.argv[2]) if len(sys.argv) > 2 else 25
incl, self_, total = collections.Counter(), collections.Counter(), 0
for line in open(path):
    line = line.rstrip()
    if not line:
        continue
    stack, _, n = line.rpartition(" ")
    n = int(n)
    total += n
    frames = [f.replace("/", ".") for f in stack.split(";")]
    for f in set(frames):
        incl[f] += n
    self_[frames[-1]] += n
print(f"#### {path} ({total} samples)\n")
print("| inclusive % | self % | frame |\n|---:|---:|---|")
for f, n in incl.most_common(top * 3):
    if (f.startswith(("java.lang.Thread.run", "org.openjdk.jmh.runner", "java.util.concurrent", "jdk.internal.reflect",
                     "java.lang.reflect", "java.lang.invoke", "java.lang.Thread", "org.openjdk.jmh", "["))
            or "jmh_generated" in f or "FutureTask" in f):
        continue
    print(f"| {100*n/total:.1f} | {100*self_[f]/total:.1f} | `{f}` |")
    top -= 1
    if top == 0:
        break
print("\nTop self frames:\n")
for f, n in self_.most_common(15):
    print(f"- {100*n/total:.1f}% `{f}`")
print()

#!/usr/bin/env python3
"""Builds a markdown comparison table from results/<label>/*.json.

The "winner" column names the best label and its advantage over the best label of a *different*
implementation (labels are grouped by the prefix before the first '-', e.g. owb-4.1.1 vs weld-7.0.0),
so two Weld versions are never compared with each other there.
For each (patched, baseline) pair in PAIRS with results, an extra column shows the patched speed-up."""
import json, pathlib, sys

root = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else "results")
labels = sorted(p.name for p in root.iterdir() if p.is_dir() and not p.name.startswith("profile-"))
# (patched, baseline) speed-up columns, only shown when both labels have results
PAIRS = [("weld-7-proxy4", "weld-7-proxy3"), ("weld-6-proxy4", "weld-6-proxy3"),
         ("weld-7-proxy4", "weld-7.0.0"), ("weld-6-proxy4", "weld-6.0.4"),
         ("weld-7-main", "weld-7.0.0"), ("weld-7-proxy2", "weld-7.0.0"), ("weld-7-proxy2", "weld-7-reqcache"),
         ("weld-6-proxy2", "weld-6.0.4"), ("weld-6-proxy2", "weld-6-reqcache"),
         ("weld-7-patched", "weld-7.0.0"), ("weld-6-patched", "weld-6.0.4")]


def key(benchmark):
    # CdiBenchmark.x -> x (as in older reports), CdiNoInterceptorBenchmark.x -> NoInterceptor.x
    cls, method = benchmark.rsplit(".", 2)[-2:]
    return method if cls == "CdiBenchmark" else cls.replace("Cdi", "").replace("Benchmark", "") + "." + method

def impl(label):
    return label.split("-", 1)[0]

def load(label, name):
    f = root / label / name
    if not f.exists():
        return {}
    return {key(r["benchmark"]): r["primaryMetric"] for r in json.loads(f.read_text())}

out = []
for name, title, higher_better in [("jmh-t1.json", "Throughput, 1 thread (ops/µs, higher is better)", True),
                                   ("jmh-t4.json", "Throughput, 4 threads (ops/µs, higher is better)", True),
                                   ("jmh-boot.json", "Container boot + shutdown (ms/op, lower is better)", False)]:
    data = {l: load(l, name) for l in labels}
    benches = sorted({b for d in data.values() for b in d})
    if not benches:
        continue
    pairs = [(p, q) for p, q in PAIRS if data.get(p) and data.get(q)]
    out.append(f"### {title}\n")
    out.append("| benchmark | " + " | ".join(labels) + " | winner |" + "".join(f" {p} vs {q} |" for p, q in pairs))
    out.append("|---" * (len(labels) + 2 + len(pairs)) + "|")
    pick = max if higher_better else min
    for b in benches:
        cells, scores = [], {}
        for l in labels:
            m = data[l].get(b)
            if m:
                scores[l] = m["score"]
                cells.append(f"{m['score']:.3f} ± {m['scoreError']:.3f}")
            else:
                cells.append("–")
        best = pick(scores, key=scores.get) if scores else ""
        rivals = {k: v for k, v in scores.items() if impl(k) != impl(best)}
        ratio = ""
        if rivals:
            ref_label = pick(rivals, key=rivals.get)
            ref = rivals[ref_label]
            r = scores[best] / ref if higher_better else ref / scores[best]
            ratio = f" (x{r:.2f} vs {ref_label})"
        row = f"| {b} | " + " | ".join(cells) + f" | **{best}**{ratio} |"
        for pl, ql in pairs:
            p, q = scores.get(pl), scores.get(ql)
            row += (f" x{(p / q if higher_better else q / p):.2f} |" if p and q else " – |")
        out.append(row)
    out.append("")

out.append("### Legacy loop test (original methodology, ms, lower is better)\n")
for l in labels:
    f = root / l / "legacy.txt"
    if f.exists():
        out.append(f"**{l}**\n```\n{f.read_text().strip()}\n```")
print("\n".join(out))

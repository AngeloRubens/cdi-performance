#!/usr/bin/env python3
"""Builds a markdown comparison table from results/<label>/*.json."""
import json, pathlib, sys

root = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else "results")
labels = sorted(p.name for p in root.iterdir() if p.is_dir())

def load(label, name):
    f = root / label / name
    if not f.exists():
        return {}
    return {r["benchmark"].rsplit(".", 1)[1]: r["primaryMetric"] for r in json.loads(f.read_text())}

out = []
for name, title, higher_better in [("jmh-t1.json", "Throughput, 1 thread (ops/µs, higher is better)", True),
                                   ("jmh-t4.json", "Throughput, 4 threads (ops/µs, higher is better)", True),
                                   ("jmh-boot.json", "Container boot + shutdown (ms/op, lower is better)", False)]:
    data = {l: load(l, name) for l in labels}
    benches = sorted({b for d in data.values() for b in d})
    if not benches:
        continue
    out.append(f"### {title}\n")
    out.append("| benchmark | " + " | ".join(labels) + " | winner |")
    out.append("|---" * (len(labels) + 2) + "|")
    for b in benches:
        cells, scores = [], {}
        for l in labels:
            m = data[l].get(b)
            if m:
                scores[l] = m["score"]
                cells.append(f"{m['score']:.3f} ± {m['scoreError']:.3f}")
            else:
                cells.append("–")
        best = (max if higher_better else min)(scores, key=scores.get) if scores else ""
        others = [v for k, v in scores.items() if k != best]
        ratio = ""
        if others:
            ref = sorted(others, reverse=higher_better)[0]  # runner-up
            r = scores[best] / ref if higher_better else ref / scores[best]
            ratio = f" (x{r:.2f})"
        out.append(f"| {b} | " + " | ".join(cells) + f" | **{best}**{ratio} |")
    out.append("")

out.append("### Legacy loop test (original methodology, ms, lower is better)\n")
for l in labels:
    f = root / l / "legacy.txt"
    if f.exists():
        out.append(f"**{l}**\n```\n{f.read_text().strip()}\n```")
print("\n".join(out))

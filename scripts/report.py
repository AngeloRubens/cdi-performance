#!/usr/bin/env python3
"""Builds a markdown comparison table from results/<label>/*.json.

The "winner" column names the best label and its advantage over the best label of a *different*
implementation (labels are grouped by the prefix before the first '-', e.g. owb-4.1.1 vs weld-7.0.0),
so two Weld versions are never compared with each other there.
If both weld-7.0.0 and weld-7-patched results exist, an extra column shows the patched speed-up."""
import json, pathlib, sys

root = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else "results")
labels = sorted(p.name for p in root.iterdir() if p.is_dir() and not p.name.startswith("profile-"))
BASE, PATCHED = "weld-7.0.0", "weld-7-patched"

def impl(label):
    return label.split("-", 1)[0]

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
    show_patch = bool(data.get(BASE)) and bool(data.get(PATCHED))
    out.append(f"### {title}\n")
    out.append("| benchmark | " + " | ".join(labels) + " | winner |" + (" patched vs 7.0.0 |" if show_patch else ""))
    out.append("|---" * (len(labels) + 2 + show_patch) + "|")
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
        if show_patch:
            p, q = scores.get(PATCHED), scores.get(BASE)
            row += (f" x{(p / q if higher_better else q / p):.2f} |" if p and q else " – |")
        out.append(row)
    out.append("")

out.append("### Legacy loop test (original methodology, ms, lower is better)\n")
for l in labels:
    f = root / l / "legacy.txt"
    if f.exists():
        out.append(f"**{l}**\n```\n{f.read_text().strip()}\n```")
print("\n".join(out))

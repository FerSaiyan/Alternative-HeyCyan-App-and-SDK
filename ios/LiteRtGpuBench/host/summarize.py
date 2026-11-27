#!/usr/bin/env python3
"""Markdown tables from the pulled bench results of one Device Run session folder.

Finds every results.jsonl below <session dir> (one per device job, under .../artifacts/bench_out/),
falls back to the "LRTBENCH " lines of system.log when a job pulled nothing, and writes
<session dir>/SUMMARY.md (and prints it): per device, one row per (model, config) with total and
run-only median ms, create ms (cold / cached), fully_accelerated, min cosine and Jaccard(>=0.35)
vs the model's own CPU run and vs the reference model.

Usage: python3 -I summarize.py <session dir>
"""

import json
import sys
from pathlib import Path


def load_rows(job: Path):
    rows = []
    for f in sorted(job.rglob("results.jsonl")):
        rows += [json.loads(line) for line in f.read_text().splitlines() if line.strip()]
    if rows:
        return rows, "results.jsonl"
    for f in sorted(job.rglob("system.log")):
        for line in f.read_text(errors="replace").splitlines():
            i = line.find("LRTBENCH {")
            if i >= 0:
                try:
                    rows.append(json.loads(line[i + len("LRTBENCH "):]))
                except json.JSONDecodeError:
                    pass
    return rows, "system.log"


def fmt(v, digits=1):
    if v is None:
        return "-"
    if isinstance(v, str):
        return v
    return f"{v:.{digits}f}"


def worst(cmp, key):
    vals = [c.get(key) for c in (cmp or []) if isinstance(c.get(key), (int, float))]
    return min(vals) if vals else None


def main() -> None:
    root = Path(sys.argv[1])
    jobs = sorted({p.parent for p in root.rglob("junit.xml") if p.parent.name.startswith("job-")}) or [root]
    out = []
    for job in jobs:
        rows, source = load_rows(job)
        device = next((r for r in rows if r.get("event") == "device"), {})
        probe = next((r for r in rows if r.get("event") == "gpu_probe"), None)
        out.append(f"## {device.get('machine', '?')} {device.get('os', '')} ({job.name}, from {source})\n")
        out.append(f"GPU: {device.get('gpu', '?')}, RAM {device.get('physical_memory_mb', '?')} MB, "
                   f"thermal {device.get('thermal', '?')}, low power {device.get('low_power', '?')}")
        if probe:
            out.append(f"GPU probe: chosen={probe.get('chosen')} attempts={json.dumps(probe.get('attempts'))}")
        out.append("")
        out.append("| model | config | total ms | run ms | p90 ms | create ms | full | cos vs CPU | J>=.35 CPU "
                   "| cos vs ref | J>=.35 ref | thermal |")
        out.append("|---|---|---:|---:|---:|---:|:-:|---:|---:|---:|---:|:-:|")
        for r in rows:
            if r.get("event") != "run":
                continue
            model = r.get("model", "?").replace(".tflite", "")
            if "error" in r:
                out.append(f"| {model} | {r.get('config')} | ERROR: {str(r['error'])[:90]} | | | | | | | | | |")
                continue
            create = fmt(r.get("create_ms"), 0)
            if r.get("create_warm_ms") is not None:
                create += f" ({fmt(r['create_warm_ms'], 0)})"
            out.append(
                f"| {model} | {r.get('config')} | {fmt(r.get('median_ms'))} | {fmt(r.get('run_median_ms'))} "
                f"| {fmt(r.get('p90_ms'))} | {create} | {'y' if r.get('fully_accelerated') else 'n'} "
                f"| {fmt(worst(r.get('vs_cpu'), 'min_cos'), 4)} | {fmt(worst(r.get('vs_cpu'), 'jaccard035'), 3)} "
                f"| {fmt(worst(r.get('vs_ref'), 'min_cos'), 4)} | {fmt(worst(r.get('vs_ref'), 'jaccard035'), 3)} "
                f"| {r.get('thermal_before', '?')}-{r.get('thermal_after', '?')} |")
        errors = [r for r in rows if r.get("event") == "error"]
        for e in errors:
            out.append(f"\nerror: {json.dumps(e)}")
        out.append("")
    text = "\n".join(out)
    (root / "SUMMARY.md").write_text(text + "\n")
    print(text)


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Execute one GPU lane of the offline ablation matrix; fail closed on errors.

Run on the remote host, after staging paired datasets, pinned source/checkpoint
directories and isolated environments under --root. Never opens final/calibration.
"""
import argparse
import hashlib
import json
import os
import subprocess
import sys
import time
from pathlib import Path


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--root", type=Path, required=True)
    p.add_argument("--lane", choices=("laya", "needle"), required=True)
    p.add_argument("--resume", action="store_true", help="Reuse successfully journalled identical commands and existing artifacts")
    args = p.parse_args()
    root = args.root.resolve()
    scripts = Path(__file__).resolve().parent
    logs, reports = root/"logs", root/"reports"
    logs.mkdir(exist_ok=True)
    reports.mkdir(exist_ok=True)
    env = dict(os.environ, CUDA_VISIBLE_DEVICES="0" if args.lane == "laya" else "1", XLA_PYTHON_CLIENT_PREALLOCATE="false", NEEDLE_TELEMETRY="0", DO_NOT_TRACK="1", HF_HOME=str(root/"cache"), HF_HUB_OFFLINE="1", TRITON_CACHE_DIR=str(root/"cache/triton"), TORCHINDUCTOR_CACHE_DIR=str(root/"cache/torchinductor"), XDG_CACHE_HOME=str(root/"cache"), OMP_NUM_THREADS="4", TOKENIZERS_PARALLELISM="false", PYTHONPATH=str(root/"sources/needle-source"))
    python = root/f"{args.lane}-venv/bin/python"
    started, jobs = time.time(), []
    journal = reports/f"{args.lane}-jobs.json"
    previous = {j["name"]: j for j in json.loads(journal.read_text())["jobs"]} if args.resume and journal.exists() else {}

    def run(name, command):
        command = [str(x) for x in command]
        prior = previous.get(name)
        output = command[command.index("--out")+1] if "--out" in command else command[command.index("--report")+1] if "--report" in command else None
        if prior and prior["returncode"] == 0 and prior["command"] == command and output and Path(output).exists():
            jobs.append(dict(prior, resumed=True))
            print("REUSE", name, flush=True)
            return
        before = time.time()
        print("START", name, flush=True)
        with (logs/f"{name}.log").open("w") as handle:
            rc = subprocess.run(command, stdout=handle, stderr=subprocess.STDOUT, env=env).returncode
        jobs.append({"name": name, "command": [str(x) for x in command], "returncode": rc, "seconds": time.time()-before})
        (reports/f"{args.lane}-jobs.json").write_text(json.dumps({"jobs": jobs, "elapsedSeconds": time.time()-started, "finalTestOpened": False}, indent=2)+"\n")
        if rc:
            print((logs/f"{name}.log").read_text()[-6000:], flush=True)
            raise RuntimeError(f"{name} failed ({rc})")
        print("DONE", name, f"{time.time()-before:.1f}s", flush=True)

    if args.lane == "laya":
        for specialist, updates in (("voice", 300), ("config", 120)):
            for data in ("positive", "with_negatives"):
                name = f"laya-{specialist}-{data}-head"
                run(name, [python, scripts/"train_laya_specialist.py", "--source", root/"sources/laya-current", "--base", root/"sources/laya-multilingual", "--data", root/"data"/f"{specialist}_{data}", "--out", root/"models"/name, "--updates", updates, "--eval-every", updates//3, "--encoder", "frozen"])
        for specialist, updates in (("voice", 300), ("config", 120)):
            name = f"laya-{specialist}-with_negatives-full"
            run(name, [python, scripts/"train_laya_specialist.py", "--source", root/"sources/laya-current", "--base", root/"sources/laya-multilingual", "--data", root/"data"/f"{specialist}_with_negatives", "--out", root/"models"/name, "--updates", updates, "--eval-every", updates//3, "--encoder", "full"])
    else:
        cli = [python, "-c", "from needle.cli import main; main()"]
        base = root/"sources/needle3/needle3.safetensors"
        library = root/"sources/needle3/wheel/needle/libneedle3.so"
        models = root/"models"

        def build(name, out, layers, adapter=None):
            command = [python, scripts/"build_needle_offline.py", "--checkpoint", base, "--base-archive", root/"sources/needle3/needle3.cact", "--archive-sha256", "c9d915eca282ed42d1a09b143b592adb4cc6744ffe2d294adf5cfc5548170c38", "--layers", layers, "--out", out]
            if adapter:
                command += ["--lora", adapter]
            run(name, command)

        def evaluate(name, model, specialist, reverse=False):
            report = reports/f"{name}.json"
            command = [python, scripts/"eval_needle_specialist.py", "--library", library, "--model", model, "--data", root/"data"/f"{specialist}_with_negatives", "--report", report]
            if reverse:
                command.append("--reverse")
            run(name, command)

        for specialist in ("voice", "config"):
            evaluate(f"needle-{specialist}-base-20l", root/"sources/needle3/needle3.cact", specialist)
        for specialist, epochs in (("voice", (6, 2)), ("config", (9, 3))):
            for data, count in zip(("positive", "with_negatives"), epochs):
                name = f"needle-{specialist}-{data}"
                adapter = models/f"{name}.safetensors"
                run(name+"-train", cli+["finetune", root/"data"/f"{specialist}_{data}"/"train.needle.jsonl", "--checkpoint", base, "--max-len", "512", "--val-split", "0", "--epochs", count, "--batch-size", "16", "--seed", "20261003", "--checkpoint-dir", models, "--out", adapter])
                model = models/f"{name}-20l.cact"
                build(name+"-build-20l", model, 20, adapter)
                evaluate(name+"-20l", model, specialist)
        for layers in (8, 4):
            base_rung = models/f"needle-base-{layers}l.cact"
            build(f"needle-base-build-{layers}l", base_rung, layers)
            for specialist in ("voice", "config"):
                evaluate(f"needle-{specialist}-base-{layers}l", base_rung, specialist)
                name = f"needle-{specialist}-with_negatives"
                model = models/f"{name}-{layers}l.cact"
                build(name+f"-build-{layers}l", model, layers, models/f"{name}.safetensors")
                evaluate(name+f"-{layers}l", model, specialist)
        for specialist in ("voice", "config"):
            name = f"needle-{specialist}-with_negatives"
            evaluate(name+"-20l-reversed", models/f"{name}-20l.cact", specialist, reverse=True)
    hashes = {}
    for path in (root/"models").glob("*"):
        if path.is_file():
            hashes[path.name] = hashlib.sha256(path.read_bytes()).hexdigest()
    (reports/f"{args.lane}-completed.json").write_text(json.dumps({"jobs": jobs, "elapsedSeconds": time.time()-started, "artifactHashes": hashes, "finalTestOpened": False, "calibrationOpened": False}, indent=2)+"\n")


if __name__ == "__main__":
    main()

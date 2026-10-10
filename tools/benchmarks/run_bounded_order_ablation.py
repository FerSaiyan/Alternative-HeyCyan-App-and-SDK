#!/usr/bin/env python3
"""Evaluate unchanged bounded checkpoints with reversed candidate presentation."""
import argparse
import hashlib
import json
import os
import subprocess
import time
from pathlib import Path


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("--root",type=Path,required=True)
    p.add_argument("--lane",choices=("laya","needle"),required=True)
    p.add_argument("--view",choices=("hierarchy","flat"),help="Run one remaining view without replacing completed reports")
    args=p.parse_args()
    root=args.root.resolve()
    scripts=Path(__file__).resolve().parent
    reports=root/"reports/order-reversed"
    reports.mkdir(parents=True,exist_ok=True)
    views=(args.view,) if args.view else ("hierarchy","flat")
    run_name=args.lane+("-"+args.view if args.view else "")
    outputs=[reports/f"{args.lane}-bounded-{view}-v3-{label}-rollout.json"
             for view in views for label in ("base","tuned")]
    if any(path.exists() for path in outputs):
        p.error("Fresh order-ablation reports required; refusing to overwrite")
    env=dict(os.environ,CUDA_VISIBLE_DEVICES="0" if args.lane=="laya" else "1",
             HF_HUB_OFFLINE="1",HF_HOME=str(root/"cache"),TRITON_CACHE_DIR=str(root/"cache/triton"),
             XDG_CACHE_HOME=str(root/"cache"),TOKENIZERS_PARALLELISM="false",OMP_NUM_THREADS="4",
             NEEDLE_TELEMETRY="0",PYTHONPATH=str(root/"sources/needle-source"))
    journal=[]
    provenance={"scripts":{path.name:hashlib.sha256(path.read_bytes()).hexdigest() for path in scripts.glob("*.py")},"data":{}}
    for view in views:
        data=root/f"bounded-{view}-v3"
        manifest=json.loads((data/"manifest.json").read_text())
        for name in ("development.canonical.jsonl","development.episodes.jsonl"):
            if hashlib.sha256((data/name).read_bytes()).hexdigest()!=manifest["files"][name]["sha256"]:
                raise ValueError("Development bytes no longer match audited manifest")
        provenance["data"][view]=manifest
        (reports/f"{run_name}-provenance.json").write_text(json.dumps(provenance,indent=2)+"\n")
        name=f"{args.lane}-bounded-{view}-v3"
        base=root/("sources/laya-multilingual" if args.lane=="laya" else "sources/needle3/needle3.cact")
        tuned=root/"models"/(name if args.lane=="laya" else name+".cact")
        extra=["--source",str(root/"sources/laya-current")] if args.lane=="laya" else ["--library",str(root/"sources/needle3/wheel/needle/libneedle3.so")]
        for label,model in (("base",base),("tuned",tuned)):
            command=[str(root/f"{args.lane}-venv/bin/python"),str(scripts/"eval_bounded_specialist.py"),
                     "--backend",args.lane,"--model",str(model),"--data",str(data),
                     "--report",str(reports/f"{name}-{label}-rollout.json"),"--reverse-options"]+extra+(["--flat"] if view=="flat" else [])
            print("START",name,label,flush=True)
            started=time.monotonic()
            with (root/"logs"/f"{name}-{label}-reversed.log").open("x") as log:
                rc=subprocess.run(command,env=env,stdout=log,stderr=subprocess.STDOUT).returncode
            journal.append({"command":command,"returncode":rc,"seconds":time.monotonic()-started})
            (reports/f"{run_name}-jobs.json").write_text(json.dumps(journal,indent=2)+"\n")
            if rc:
                raise RuntimeError("Order evaluation failed: "+name+" "+label)
            print("DONE",name,label,flush=True)


if __name__=="__main__":
    main()

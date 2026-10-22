#!/usr/bin/env python3
"""Two remote GPU lanes: same episodes, state-matched flat vs narrowing views."""
import argparse
import json
import os
import subprocess
import time
import hashlib
from pathlib import Path


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("--root",type=Path,required=True)
    p.add_argument("--lane",choices=("laya","needle"),required=True)
    p.add_argument("--version",choices=("v2","v3"),default="v3")
    p.add_argument("--reasoning-targets",action="store_true",help="Needle-only matched supervised-reasoning target ablation")
    args=p.parse_args()
    if args.reasoning_targets and (args.lane!="needle" or args.version!="v3"):
        p.error("Reasoning target ablation requires Needle v3")
    root=args.root.resolve()
    scripts=Path(__file__).resolve().parent
    env=dict(os.environ,CUDA_VISIBLE_DEVICES="0" if args.lane=="laya" else "1",XLA_PYTHON_CLIENT_PREALLOCATE="false",HF_HUB_OFFLINE="1",HF_HOME=str(root/"cache"),TRITON_CACHE_DIR=str(root/"cache/triton"),XDG_CACHE_HOME=str(root/"cache"),TOKENIZERS_PARALLELISM="false",OMP_NUM_THREADS="4",NEEDLE_TELEMETRY="0",PYTHONPATH=str(root/"sources/needle-source"))
    python=root/f"{args.lane}-venv/bin/python"
    journal=[]
    reports=root/"reports"/("reasoning-targets" if args.reasoning_targets else "")
    reports.mkdir(parents=True,exist_ok=True)
    provenance={"scripts":{path.name:hashlib.sha256(path.read_bytes()).hexdigest() for path in scripts.glob("*.py")},"data":{}}

    def run(name,command):
        started=time.monotonic()
        command=[str(x) for x in command]
        print("START",name,flush=True)
        with (root/"logs"/f"{name}.log").open("w") as out:
            rc=subprocess.run(command,env=env,stdout=out,stderr=subprocess.STDOUT).returncode
        journal.append({"name":name,"command":command,"returncode":rc,"seconds":time.monotonic()-started})
        (reports/f"bounded-{args.lane}-{args.version}-jobs.json").write_text(json.dumps(journal,indent=2)+"\n")
        if rc:
            print((root/"logs"/f"{name}.log").read_text()[-6000:],flush=True)
            raise RuntimeError(name)
        print("DONE",name,flush=True)

    for view in ("hierarchy","flat"):
        data=root/(f"bounded-{view}-{args.version}"+("-reasoning" if args.reasoning_targets else ""))
        manifest=json.loads((data/"manifest.json").read_text())
        provenance["data"][view]=manifest
        for name,entry in manifest["files"].items():
            if name.startswith(("train.","development.")) and hashlib.sha256((data/name).read_bytes()).hexdigest()!=entry["sha256"]:
                raise ValueError("Staged train/development bytes do not match audited manifest")
        (reports/f"bounded-{args.lane}-{args.version}-provenance.json").write_text(json.dumps(provenance,indent=2)+"\n")
        report_name=f"{args.lane}-bounded-{view}-{args.version}"
        name=report_name+("-reasoning" if args.reasoning_targets else "")
        if args.lane=="laya":
            model=root/"models"/name
            run(name+"-train",[python,scripts/"train_laya_specialist.py","--source",root/"sources/laya-current","--base",root/"sources/laya-multilingual","--data",data,"--out",model,"--updates",180,"--eval-every",60,"--encoder","full"])
            base=root/"sources/laya-multilingual"
            extra=["--source",root/"sources/laya-current"]
        else:
            base=root/"sources/needle3/needle3.cact"
            adapter=root/"models"/(name+".safetensors")
            checkpoint=root/"sources/needle3/needle3.safetensors"
            # Match optimizer updates/batch within Needle, without rounding epoch
            # counts or allowing larger batches to exceed the available VRAM.
            run(name+"-train",[python,scripts/"train_needle_bounded.py","--checkpoint",checkpoint,"--data",data,"--out",adapter,"--updates",180,"--batch",8,"--max-len",768,"--seed",20261003])
            model=root/"models"/(name+".cact")
            run(name+"-build",[python,scripts/"build_needle_offline.py","--checkpoint",checkpoint,"--base-archive",base,"--archive-sha256","c9d915eca282ed42d1a09b143b592adb4cc6744ffe2d294adf5cfc5548170c38","--layers",20,"--lora",adapter,"--out",model])
            extra=["--library",root/"sources/needle3/wheel/needle/libneedle3.so"]
        for label,artifact in (("base",base),("tuned",model)):
            run(name+"-"+label+"-rollout",[python,scripts/"eval_bounded_specialist.py","--backend",args.lane,"--model",artifact,"--data",data,"--report",reports/(report_name+"-"+label+"-rollout.json")]+extra+(["--flat"] if view=="flat" else []))


if __name__=="__main__":
    main()

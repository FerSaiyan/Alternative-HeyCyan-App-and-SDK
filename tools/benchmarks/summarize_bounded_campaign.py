#!/usr/bin/env python3
"""Compact state-matched ablation report with no hidden predictions or gold rescue."""
import argparse
import json
import hashlib
from pathlib import Path
from bounded_specialist_contract import choice_metrics, fingerprint


def summarize(root, version, allow_partial=False, reports=None, reverse_options=False, backends=("laya","needle"), reasoning_targets=False):
    expected={f"{backend}-bounded-{view}-{version}-{label}-rollout.json"
              for backend in backends for view in ("hierarchy","flat") for label in ("base","tuned")}
    paths=sorted((reports or root/"reports").glob(f"*-bounded-*-{version}-*-rollout.json"))
    names={path.name for path in paths}
    if names-expected:
        raise ValueError("Unexpected campaign reports: "+", ".join(sorted(names-expected)))
    complete=names==expected
    if not complete and not allow_partial:
        raise ValueError("Campaign incomplete; missing: "+", ".join(sorted(expected-names)))
    suffix="-reasoning" if reasoning_targets else ""
    data={view:json.loads((root/f"bounded-{view}-{version}{suffix}/manifest.json").read_text()) for view in ("hierarchy","flat")}
    if reasoning_targets and any(not value.get("reasoningAblation",{}).get("inputAnswersGroupsUnchanged") for value in data.values()):
        raise ValueError("Reasoning data lacks unchanged-input/cohort provenance")
    if data["hierarchy"]["contract"]!=data["flat"]["contract"]:
        raise ValueError("Different view contracts")
    for split in ("train","development","calibration","sealed"):
        if data["hierarchy"]["files"][f"{split}.episodes.jsonl"]!=data["flat"]["files"][f"{split}.episodes.jsonl"]:
            raise ValueError("Different episode cohorts: "+split)
    entries=[]
    hashes={}
    legacy_metadata=[]
    for path in paths:
        report=json.loads(path.read_text())
        backend,_,view,_,_,_=path.stem.split("-")
        missing_legacy=[key for key in ("contract","modelSha256","hostVerifiedMutation") if key not in report]
        if version=="v2" and missing_legacy:
            legacy_metadata.append({"report":path.name,"missingFields":missing_legacy})
        contract=report.get("contract",data[view]["contract"] if version=="v2" else None)
        if (report["backend"]!=backend or report["flat"] is not (view=="flat")
                or contract!=data[view]["contract"] or report.get("reversedOptions",False) is not reverse_options):
            raise ValueError("Report/view/contract mismatch: "+path.name)
        if any(report.get(key,False if version=="v2" and key=="hostVerifiedMutation" else None) is not False
               for key in ("calibrationOpened","finalTestOpened","hostVerifiedMutation")):
            raise ValueError("Unexpected evaluation scope: "+path.name)
        oracle=report["oracleTurns"]
        measured=choice_metrics(oracle["results"])
        for key,value in measured.items():
            if oracle.get(key)!=value:
                raise ValueError("Oracle metrics disagree with predictions: "+path.name+" / "+key)
        rollouts=report["rollouts"]
        if any(r["correct"] is not (fingerprint(r["proposedEffect"])==fingerprint(r["expectedEffect"])) for r in rollouts):
            raise ValueError("Episode success disagrees with proposed effect: "+path.name)
        cases=len(rollouts)
        correct=sum(r["correct"] is True for r in rollouts)
        if (cases!=data[view]["episodes"]["development"] or oracle["cases"]!=data[view]["counts"]["development"]
                or report["rolloutEpisodes"]!=cases or report["rolloutCorrect"]!=correct
                or report["rolloutAccuracy"]!=correct/max(1,cases)):
            raise ValueError("Episode counts/metrics disagree: "+path.name)
        artifact=Path(report["model"])
        if backend=="laya":
            artifact=artifact/"model.safetensors"
        if not artifact.is_file():
            raise ValueError("Cannot verify missing model artifact: "+str(artifact))
        if str(artifact) not in hashes:
            with artifact.open("rb") as handle:
                digest=hashlib.sha256()
                while chunk:=handle.read(8*1024*1024):
                    digest.update(chunk)
            hashes[str(artifact)]=digest.hexdigest()
        if (report.get("modelSha256")!=hashes[str(artifact)]
                and not (version=="v2" and "modelSha256" not in report)):
            raise ValueError("Prediction/model hash mismatch: "+path.name)
        entries.append({"name":path.stem,"backend":backend,"view":"flat" if report["flat"] else "hierarchical",
                        "modelSha256":hashes[str(artifact)],"reportSha256":hashlib.sha256(path.read_bytes()).hexdigest(),
                        "oracleTurns":oracle["cases"],"oracleCorrect":oracle["correct"],"oracleChoiceAccuracy":oracle["choiceAccuracy"],
                        "oracleControlFalseActionRate":oracle["controlFalseActionRate"],"invalidOracleChoices":oracle["invalidChoices"],
                        "episodes":cases,"correctEpisodes":correct,"rolloutAccuracy":report["rolloutAccuracy"],
                        "meanRolloutTurns":sum(len(r["trace"]) for r in rollouts)/cases,
                        "evaluationModelCalls":oracle["cases"]+sum(len(r["trace"]) for r in rollouts),"evaluationWallSeconds":report["wallSeconds"]})
    summary={"contract":data["hierarchy"]["contract"],"complete":complete,"artifactHashesVerified":True,
            "reversedOptions":reverse_options,
            "evaluatedBackends":list(backends),"supervisedReasoningTargets":reasoning_targets,
            "predictionModelHashesVerified":not any("modelSha256" in item["missingFields"] for item in legacy_metadata),
            "legacyV2MissingMetadata":legacy_metadata,"results":entries,
            "dataset":{view:{k:v[k] for k in ("episodes","counts","maxOptions")} for view,v in data.items()},
            "budgets":{"laya":{"updates":180,"effectiveBatch":16,"encoder":"full","selection":"best of three development checkpoints"},"needle":{"updates":180,"batch":8,"valSplit":0,"selection":"fixed final adapter"}},
            "calibrationEvaluated":False,"finalTestEvaluated":False,"hostVerifiedMutation":False,
            "limits":["Generated written examples and hypothetical app state; shared concepts/catalog and only two development phrasing families", "Oracle-turn accuracy is not self-routed episode accuracy; one wrong ancestor is never replaced by gold scope", "Leaf effects are proposed setting changes/download offers; no actual execution, download, setting readback or Tasker completion", "Optimizer updates match within each model; turn/goal exposure and total per-episode inference cost differ", "This is a bounded local-model/AI-route/Live-refresh slice, not the complete plugin/settings manifest"]}
    summary["budgets"]={backend:summary["budgets"][backend] for backend in backends}
    return summary


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("--root",type=Path,required=True)
    p.add_argument("--out",type=Path,required=True)
    p.add_argument("--version",choices=("v2","v3"),default="v3")
    p.add_argument("--allow-partial",action="store_true",help="Mark an in-progress summary explicitly; final publication requires all eight reports")
    p.add_argument("--reports",type=Path,help="Alternate report directory for a separate presentation-order ablation")
    p.add_argument("--reverse-options",action="store_true",help="Require reports evaluated with reversed presentation")
    p.add_argument("--backend",choices=("laya","needle"),help="A deliberately single-model ablation requires four reports")
    p.add_argument("--reasoning-targets",action="store_true",help="Read the audited supervised-reasoning dataset manifests")
    args=p.parse_args()
    if args.reasoning_targets and (args.backend!="needle" or args.version!="v3"):
        p.error("Reasoning target ablation requires --backend needle --version v3")
    try:
        summary=summarize(args.root,args.version,args.allow_partial,args.reports,args.reverse_options,
                          (args.backend,) if args.backend else ("laya","needle"),args.reasoning_targets)
    except ValueError as error:
        p.error(str(error))
    args.out.parent.mkdir(parents=True,exist_ok=True)
    args.out.write_text(json.dumps(summary,indent=2)+"\n")
    print("| Variant | Oracle turns | Self-routed episodes | Mean turns |")
    print("|---|---:|---:|---:|")
    for r in summary["results"]:
        print(f"| {r['name']} | {r['oracleCorrect']}/{r['oracleTurns']} ({r['oracleChoiceAccuracy']:.1%}) | {r['correctEpisodes']}/{r['episodes']} ({r['rolloutAccuracy']:.1%}) | {r['meanRolloutTurns']:.2f} |")


if __name__=="__main__":
    main()

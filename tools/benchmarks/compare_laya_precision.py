#!/usr/bin/env python3
"""Compare resident FP16/BF16 Laya with its reference and upstream-style Q8 ONNX.

Uses only the open development cohort. Q8 matches upstream's default dynamic,
per-tensor INT8 MatMul quantization and runs on CPU. No fit/calibration occurs.
"""
import argparse
import gc
import hashlib
import json
import math
import os
import resource
import statistics
import subprocess
import sys
import time
from pathlib import Path
import numpy as np


def sha256(path):
    digest=hashlib.sha256()
    with Path(path).open("rb") as stream:
        while chunk:=stream.read(8*1024*1024):
            digest.update(chunk)
    return digest.hexdigest()


def process_memory():
    values={}
    for line in Path("/proc/self/status").read_text().splitlines():
        if line.startswith(("VmRSS:","VmHWM:")):
            key,value=line.split(":",1)
            values[key]=value.strip()
    return values


def quantiles(values):
    ordered=sorted(values)
    return {"count":len(ordered),"meanMs":statistics.fmean(ordered) if ordered else 0.0,
            "p50Ms":ordered[round((len(ordered)-1)*0.50)] if ordered else 0.0,
            "p95Ms":ordered[round((len(ordered)-1)*0.95)] if ordered else 0.0}


def ensure_graphs(model,source,fp32_graph,q8_graph,sequence_length,max_options):
    if fp32_graph.exists() or q8_graph.exists():
        raise FileExistsError("Choose fresh ONNX paths; precision artifacts are never overwritten")
    fp32_graph.parent.mkdir(parents=True,exist_ok=True)
    env=dict(os.environ,HF_HUB_OFFLINE="1",PYTHONPATH=str(source)+os.pathsep+os.environ.get("PYTHONPATH",""),
             TOKENIZERS_PARALLELISM="false",TRANSFORMERS_NO_TF="1",USE_TF="0")
    exporter=Path(__file__).with_name("export_laya_fixed_onnx.py")
    command=[sys.executable,str(exporter),"--model",str(model),"--source",str(source),
             "--output",str(fp32_graph),"--sequence-length",str(sequence_length),"--max-options",str(max_options)]
    print("Exporting tuned checkpoint to fixed-shape FP32 ONNX:"," ".join(command),flush=True)
    subprocess.run(command,env=env,cwd=source,check=True)
    if not fp32_graph.is_file() or fp32_graph.stat().st_size==0:
        raise RuntimeError("FP32 ONNX export did not produce a nonempty graph")

    import onnx
    from onnxruntime.quantization import QuantType,quantize_dynamic
    graph=onnx.load(str(fp32_graph))
    # Keep upstream main's #790 shape-inference workaround and exact defaults.
    del graph.graph.value_info[:]
    quantize_dynamic(model_input=graph,model_output=str(q8_graph),
                     op_types_to_quantize=["MatMul"],weight_type=QuantType.QInt8,
                     per_channel=False)
    if not q8_graph.is_file() or q8_graph.stat().st_size==0:
        raise RuntimeError("Q8 quantization did not produce a nonempty graph")


def orchestrate(args):
    manifest=json.loads((args.data/"manifest.json").read_text())
    config=json.loads((args.model/"rl_agent_config.json").read_text())
    sequence_length=int(config["max_len"])
    max_options=int(manifest["maxOptions"])
    if not args.reuse_graphs:
        ensure_graphs(args.model,args.source,args.fp32_onnx,args.q8_onnx,sequence_length,max_options)
    elif not args.fp32_onnx.is_file() or not args.q8_onnx.is_file():
        raise FileNotFoundError("--reuse-graphs requires both existing ONNX files")
    if args.out.exists():
        raise FileExistsError("Refusing to overwrite report: "+str(args.out))
    worker_dir=args.out.parent/(args.out.stem+"-workers")
    worker_dir.mkdir(parents=True,exist_ok=False)
    command_script=Path(__file__).resolve()
    env=dict(os.environ,HF_HUB_OFFLINE="1",USE_TF="0",TRANSFORMERS_NO_TF="1",
             PYTHONPATH=str(args.source)+os.pathsep+str(command_script.parent)+os.pathsep+os.environ.get("PYTHONPATH",""),
             TOKENIZERS_PARALLELISM="false",NEEDLE_TELEMETRY="0")
    names=("reference_fp32_weights_bf16_autocast","fp16_resident","bf16_resident","q8_int8_matmul_per_tensor_cpu")
    workers={}
    for name in names:
        path=worker_dir/(name+".json")
        command=[sys.executable,str(command_script),"--model",str(args.model),"--data",str(args.data),
                 "--source",str(args.source),"--fp32-onnx",str(args.fp32_onnx),"--q8-onnx",str(args.q8_onnx),
                 "--reuse-graphs","--worker-mode",name,"--worker-output",str(path),"--out",str(args.out)]
        print("START isolated worker",name,flush=True)
        subprocess.run(command,env=env,check=True)
        workers[name]=json.loads(path.read_text())
        print("DONE",name,"oracle",workers[name]["oracle"]["correct"],"/",workers[name]["oracle"]["cases"],
              "episodes",workers[name]["correctEpisodes"],"/",workers[name]["episodes"],
              "memory",workers[name]["memory"],"latency",workers[name]["latency"],flush=True)

    reference=workers[names[0]]
    reference_logits=reference["logitsByTurnId"]
    canonical=[json.loads(line) for line in (args.data/"development.canonical.jsonl").read_text().splitlines()]
    row_by_id={row["turn"]["turnId"]:row for row in canonical}
    results=[]
    for name in names:
        item=workers[name]
        actual_logits=item["logitsByTurnId"]
        ids=set(reference_logits)&set(actual_logits)
        if ids!=set(reference_logits) or ids!=set(actual_logits):
            raise ValueError("Precision variants evaluated different turns")
        changes=[];diffs=[];same=0
        for turn_id in sorted(ids):
            row=row_by_id[turn_id]
            keys=list(row["turn"]["options"])
            ref_choice=keys[int(np.argmax(reference_logits[turn_id]))]
            act_choice=keys[int(np.argmax(actual_logits[turn_id]))]
            same+=ref_choice==act_choice
            diffs.extend(np.abs(np.asarray(reference_logits[turn_id])-np.asarray(actual_logits[turn_id])).tolist())
            if ref_choice!=act_choice:
                changes.append({"turnId":turn_id,"reference":ref_choice,"precision":act_choice,
                                "expected":row["expectedChoice"]})
        item["selectedAgreementWithReference"]=same/len(ids)
        item["changedCasesVsReference"]=changes
        item["meanAbsLogitDifferenceVsReference"]=float(np.mean(diffs))
        item["maxAbsLogitDifferenceVsReference"]=float(np.max(diffs))
        result=dict(item)
        del result["logitsByTurnId"]
        result["workerReportSha256"]=sha256(worker_dir/(name+".json"))
        results.append(result)

    manifest=json.loads((args.data/"manifest.json").read_text())
    summary={"contract":manifest["contract"],"view":manifest["view"],"split":"development",
             "episodes":120,"oracleTurns":120,"model":str(args.model),
             "onnxFixedInputShape":{"batch":1,"sequenceLength":sequence_length,"maxOptions":max_options},
             "modelSha256":sha256(args.model/"model.safetensors"),
             "dataManifestSha256":sha256(args.data/"manifest.json"),
             "developmentCanonicalSha256":sha256(args.data/"development.canonical.jsonl"),
             "developmentEpisodesSha256":sha256(args.data/"development.episodes.jsonl"),
             "fp32Onnx":{"path":str(args.fp32_onnx),"bytes":args.fp32_onnx.stat().st_size,"sha256":sha256(args.fp32_onnx)},
             "q8Onnx":{"path":str(args.q8_onnx),"bytes":args.q8_onnx.stat().st_size,"sha256":sha256(args.q8_onnx),
                       "scheme":"dynamic QInt8 MatMul weights; per_channel=False; CPUExecutionProvider"},
             "results":results,"calibrationOpened":False,"finalTestOpened":False,"hostVerifiedMutation":False,
             "workerReportsDirectory":str(worker_dir),
             "limits":["Generated written development episodes/hypothetical host state; no real ASR or app mutation", "No calibration or sealed cohort was read; development confidence was not calibrated", "Q8 uses the upstream default dynamic per-tensor ONNX INT8 path on CPU, so latency/device/memory are not GPU-matched", "FP16/BF16-resident tests keep low-precision parameters in VRAM; this is not an Android or LiteRT measurement"]}
    args.out.parent.mkdir(parents=True,exist_ok=True)
    args.out.write_text(json.dumps(summary,indent=2)+"\n")
    print("| Mode | Oracle turns | Correct episodes | Agreement vs reference | Peak VRAM allocated | p50 inference |")
    print("|---|---:|---:|---:|---:|---:|")
    for r in results:
        peak=r["memory"].get("peakAllocatedMiB")
        peak=f"{peak:.0f} MiB" if peak is not None else f"RSS {r['memory']['processRssAfterMiB']:.0f} MiB"
        print(f"| {r['name']} | {r['oracle']['correct']}/120 | {r['correctEpisodes']}/120 | {r['selectedAgreementWithReference']:.1%} | {peak} | {r['latency']['p50Ms']:.2f} ms |")
    print("REPORT",args.out)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model",type=Path,required=True)
    parser.add_argument("--data",type=Path,required=True)
    parser.add_argument("--source",type=Path,required=True)
    parser.add_argument("--fp32-onnx",type=Path,required=True)
    parser.add_argument("--q8-onnx",type=Path,required=True)
    parser.add_argument("--out",type=Path,required=True)
    parser.add_argument("--reuse-graphs",action="store_true",help="Reuse both named graphs instead of exporting; requires both files")
    parser.add_argument("--worker-mode",choices=("reference_fp32_weights_bf16_autocast","fp16_resident","bf16_resident","q8_int8_matmul_per_tensor_cpu"))
    parser.add_argument("--worker-output",type=Path)
    args=parser.parse_args()
    args.model=args.model.resolve();args.data=args.data.resolve();args.source=args.source.resolve()
    args.fp32_onnx=args.fp32_onnx.resolve();args.q8_onnx=args.q8_onnx.resolve();args.out=args.out.resolve()
    if not args.model.is_dir() or not args.data.is_dir() or not args.source.is_dir():
        parser.error("Model, dataset and source must be existing directories")
    if args.worker_mode is None:
        if args.worker_output:
            parser.error("--worker-output requires --worker-mode")
        orchestrate(args)
        return
    if args.worker_output is None or not args.fp32_onnx.is_file() or not args.q8_onnx.is_file():
        parser.error("A worker requires --worker-output and both prepared ONNX graphs")

    sys.path.insert(0,str(args.source))
    sys.path.insert(0,str(Path(__file__).resolve().parent))
    import numpy as np
    import torch
    import onnxruntime as ort
    from safetensors.torch import load_file
    from transformers import AutoTokenizer
    from laya.common import build_model,build_sequence
    from bounded_specialist_contract import INSTRUCTIONS,choice_metrics,choice_result,rollout_episode

    manifest=json.loads((args.data/"manifest.json").read_text())
    if manifest.get("contract")!="bounded-specialist-v3" or manifest.get("view")!="flat_state_matched":
        raise ValueError("This ablation expects the audited bounded-specialist-v3 flat view")
    for name in ("development.canonical.jsonl","development.episodes.jsonl","development.laya.jsonl"):
        content=(args.data/name).read_bytes()
        if hashlib.sha256(content).hexdigest()!=manifest["files"][name]["sha256"]:
            raise ValueError("Development data hash mismatch: "+name)
    canonical=[json.loads(line) for line in (args.data/"development.canonical.jsonl").read_text().splitlines()]
    episodes=[json.loads(line) for line in (args.data/"development.episodes.jsonl").read_text().splitlines()]
    contract=manifest["contract"]
    episodes=[dict(ep,contract=contract) for ep in episodes]
    if len(canonical)!=120 or len(episodes)!=120:
        raise ValueError("Expected the pinned 120-episode development cohort")

    config=json.loads((args.model/"rl_agent_config.json").read_text())
    tok=AutoTokenizer.from_pretrained(args.model/"tokenizer",local_files_only=True)
    fixed_length=int(config["max_len"])
    max_options=int(manifest["maxOptions"])
    rows_by_id={row["turn"]["turnId"]:row for row in canonical}
    if len(rows_by_id)!=len(canonical):
        raise ValueError("Duplicate development turn IDs")

    token_counts=[];option_counts=[]

    def sequence(turn):
        criteria={key:bound["label"] for key,bound in turn["options"].items()}
        question={"t":"choice","ins":INSTRUCTIONS,"crit":criteria}
        ids,markers=build_sequence(tok,turn["query"],question,config["max_len"],config["head_max_len"])
        if len(markers)!=len(criteria) or not markers or len(ids)>fixed_length or len(markers)>max_options:
            raise ValueError("Clipped choice menu/context in "+turn["turnId"])
        return ids,markers,list(criteria)

    def run_predictor(name,backend,model_dtype=None,autocast_dtype=None,graph=None):
        device="cuda" if backend=="torch" else "cpu"
        model=None;session=None
        if backend=="torch":
            if not torch.cuda.is_available():
                raise RuntimeError("CUDA disappeared; refusing CPU fallback")
            model=build_model(config,encoder_dir=str(args.model/"encoder"))
            model.encoder.config.reference_compile=False
            model.load_state_dict(load_file(str(args.model/"model.safetensors")),strict=True)
            model=model.to(device="cuda",dtype=model_dtype).eval()
            resident_allocated=torch.cuda.memory_allocated()
            resident_reserved=torch.cuda.memory_reserved()
            torch.cuda.reset_peak_memory_stats()
        else:
            options=ort.SessionOptions()
            options.graph_optimization_level=ort.GraphOptimizationLevel.ORT_ENABLE_ALL
            options.intra_op_num_threads=min(4,os.cpu_count() or 1)
            session=ort.InferenceSession(str(graph),sess_options=options,providers=["CPUExecutionProvider"])
            if session.get_providers()!=["CPUExecutionProvider"]:
                raise RuntimeError("Q8 probe must be an explicit CPU execution")
            resident_allocated=resident_reserved=None

        timings=[];logits_by_id={};warm_turn=canonical[0]["turn"]

        def predict(turn,measure=True):
            ids,markers,keys=sequence(turn)
            input_ids=np.full((fixed_length,),int(tok.pad_token_id or 0),dtype=np.int64)
            input_ids[:len(ids)]=ids
            attention=np.zeros((fixed_length,),dtype=np.int64)
            attention[:len(ids)]=1
            padded_markers=np.zeros((max_options,),dtype=np.int64)
            padded_markers[:len(markers)]=markers
            marker_mask=np.zeros((max_options,),dtype=np.bool_)
            marker_mask[:len(markers)]=True
            started=time.perf_counter()
            if backend=="torch":
                input_tensor=torch.as_tensor(input_ids[None,:],dtype=torch.long,device="cuda")
                attention_tensor=torch.as_tensor(attention[None,:],dtype=torch.long,device="cuda")
                marker_pos=torch.as_tensor(padded_markers[None,:],dtype=torch.long,device="cuda")
                marker_mask_tensor=torch.as_tensor(marker_mask[None,:],dtype=torch.bool,device="cuda")
                qtype=torch.zeros(1,dtype=torch.long,device="cuda")
                with torch.no_grad():
                    if autocast_dtype is None:
                        output=model(input_tensor,attention_tensor,marker_pos,marker_mask_tensor,qtype)
                    else:
                        with torch.autocast("cuda",dtype=autocast_dtype):
                            output=model(input_tensor,attention_tensor,marker_pos,marker_mask_tensor,qtype)
                values=output[0][0,:len(keys)].float().cpu().numpy()
            else:
                values=session.run(["logits"],{
                    "input_ids":input_ids[None,:],
                    "attention_mask":attention[None,:],
                    "marker_pos":padded_markers[None,:],
                    "marker_mask":marker_mask[None,:],
                    "qtype":np.zeros((1,),dtype=np.int64),
                })[0][0,:len(keys)]
            if backend=="torch":torch.cuda.synchronize()
            elapsed=(time.perf_counter()-started)*1000
            if not np.isfinite(values).all():
                raise RuntimeError(name+" returned a non-finite logit")
            selected=keys[int(np.argmax(values))]
            if measure:
                timings.append(elapsed)
                token_counts.append(len(ids));option_counts.append(len(keys))
                logits_by_id[turn["turnId"]]=values.astype(np.float32).tolist()
            return selected,{"logits":values.astype(np.float32).tolist()}

        for _ in range(5):predict(warm_turn,measure=False)
        if backend=="torch":torch.cuda.reset_peak_memory_stats()
        oracle=[]
        for row in canonical:
            selected,_=predict(row["turn"])
            oracle.append(choice_result(row,selected))
        rollouts=[rollout_episode(ep,predict,flat=True) for ep in episodes]
        if backend=="torch":
            torch.cuda.synchronize()
            memory={"residentAllocatedMiB":round(resident_allocated/2**20,2),
                    "residentReservedMiB":round(resident_reserved/2**20,2),
                    "peakAllocatedMiB":round(torch.cuda.max_memory_allocated()/2**20,2),
                    "peakReservedMiB":round(torch.cuda.max_memory_reserved()/2**20,2),
                    "device":torch.cuda.get_device_name(0),"residentDtype":str(model_dtype),
                    "autocastDtype":str(autocast_dtype) if autocast_dtype else None}
        else:memory={"processRssAfterMiB":round(int(next(line.split()[1] for line in Path("/proc/self/status").read_text().splitlines() if line.startswith("VmRSS:")))/1024,2),
                     "processHwmMiB":round(resource.getrusage(resource.RUSAGE_SELF).ru_maxrss/1024,2),
                     "provider":session.get_providers(),"intraOpThreads":min(4,os.cpu_count() or 1)}
        result={"name":name,"backend":backend,"memory":memory,"oracle":dict(choice_metrics(oracle),results=oracle),
                "episodes":len(rollouts),"correctEpisodes":sum(r["correct"] for r in rollouts),
                "episodeAccuracy":sum(r["correct"] for r in rollouts)/len(rollouts),
                "meanEpisodeTurns":sum(len(r["trace"]) for r in rollouts)/len(rollouts),
                "latency":quantiles(timings),"logitsByTurnId":logits_by_id,
                "inputCoverage":{"maxUnpaddedTokens":max(token_counts),"meanUnpaddedTokens":statistics.fmean(token_counts),
                                  "maxOptions":max(option_counts),"paddedSequenceLength":fixed_length,
                                  "paddedMarkerCount":max_options},
                "rolloutEffects":[{"episodeId":r["episodeId"],"correct":r["correct"],"proposedEffect":r["proposedEffect"]} for r in rollouts],
                "rssAfterMiB":round(int(next(line.split()[1] for line in Path("/proc/self/status").read_text().splitlines() if line.startswith("VmRSS:")))/1024,2),
                "rssHighWaterMiB":round(resource.getrusage(resource.RUSAGE_SELF).ru_maxrss/1024,2)}
        if backend=="torch":
            del model
            gc.collect();torch.cuda.empty_cache()
        del session
        return result

    if args.worker_output.exists():raise FileExistsError("Refusing to overwrite worker report: "+str(args.worker_output))
    choices={
        "reference_fp32_weights_bf16_autocast":("torch",torch.float32,torch.bfloat16,None),
        "fp16_resident":("torch",torch.float16,torch.float16,None),
        "bf16_resident":("torch",torch.bfloat16,torch.bfloat16,None),
        "q8_int8_matmul_per_tensor_cpu":("onnx",None,None,args.q8_onnx),
    }
    backend,model_dtype,autocast_dtype,graph=choices[args.worker_mode]
    print("START isolated precision",args.worker_mode,flush=True)
    result=run_predictor(args.worker_mode,backend,model_dtype,autocast_dtype,graph)
    result["modelSha256"]=sha256(args.model/"model.safetensors")
    args.worker_output.parent.mkdir(parents=True,exist_ok=True)
    args.worker_output.write_text(json.dumps(result,indent=2)+"\n")
    print("DONE",args.worker_mode,"oracle",result["oracle"]["correct"],"/",result["oracle"]["cases"],
          "episodes",result["correctEpisodes"],"/",result["episodes"],"memory",result["memory"],
          "latency",result["latency"],flush=True)


if __name__=="__main__":
    main()

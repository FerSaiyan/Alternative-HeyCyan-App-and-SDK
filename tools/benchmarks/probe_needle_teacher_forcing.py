#!/usr/bin/env python3
"""Quant-aware teacher-forcing diagnostic on fixed development prompts, not action scoring."""
import argparse
import hashlib
import json
from pathlib import Path


def main():
    p = argparse.ArgumentParser(description=__doc__)
    for name in ("checkpoint", "data", "report"):
        p.add_argument("--"+name, type=Path, required=True)
    p.add_argument("--adapter",type=Path,help="Omit to probe the same base without LoRA")
    p.add_argument("--max-len",type=int,default=512)
    p.add_argument("--limit",type=int,default=12)
    args = p.parse_args()
    if min(args.max_len,args.limit)<=0 or args.report.exists():
        p.error("Positive context/row limits and a fresh report required")
    import jax
    import jax.numpy as jnp
    import numpy as np
    from needle.model.run import load_checkpoint
    from needle.model.architecture import SimpleAttentionNetwork
    from needle.model.checkpoints import read_adapter
    from needle.model.finetune import merge_lora, render_example
    from needle.model.tokenizer import get_tokenizer, BOS_ID, EOS_ID, PAD_ID, THINK_START
    from needle.model.quantize import configure_deploy, cq_ste_params, WEIGHT_BITS
    if jax.default_backend()!="gpu":
        raise RuntimeError("Teacher-forcing probe requires CUDA; no CPU fallback")
    params, config = load_checkpoint(str(args.checkpoint))
    config.dtype = "float32"
    params = jax.tree.map(lambda v: jnp.asarray(v,dtype=jnp.float32),params)
    if args.adapter:
        adapter = read_adapter(str(args.adapter))
        lora = {tuple(key.split("/")): {"A":jnp.asarray(v["A"]),"B":jnp.asarray(v["B"])} for key,v in adapter["lora"].items()}
        params = merge_lora(params,lora,adapter["scale"])
    params = cq_ste_params(params,WEIGHT_BITS)
    configure_deploy(act_bits=getattr(config,"act_bits",8),kv_bits=getattr(config,"kv_bits",8))
    tok, model = get_tokenizer(), SimpleAttentionNetwork(config)
    raw=(args.data/"development.needle.jsonl").read_bytes()
    rows = [json.loads(s) for s in raw.splitlines()][:args.limit]
    if not rows or any(row["split"]!="development" for row in rows):
        raise ValueError("Probe requires nonempty development rows")
    arrays, offsets, targets = [], [], []
    for row in rows:
        prompt, target = render_example(row)
        prompt_ids,target_ids = tok.encode(prompt),tok.encode(target)
        ids = [BOS_ID]+prompt_ids+target_ids+[EOS_ID]
        if len(ids)>args.max_len:
            raise ValueError("Teacher-forcing prompt/target would be clipped")
        arrays.append(ids+[PAD_ID]*(args.max_len-len(ids)))
        offsets.append(len(prompt_ids))
        targets.append(target_ids+[EOS_ID])

    @jax.jit
    def forward(ids):
        return model.apply({"params":params},ids,quant=True)

    # Keep the same padded training context/quantization contract. This is a
    # teacher-forced target/token diagnostic, not free-generation accuracy.
    predicted = np.asarray(jnp.argmax(forward(jnp.asarray(arrays,dtype=jnp.int32)),axis=-1))
    results = []
    for row,offset,target,pred in zip(rows,offsets,targets,predicted):
        actual = pred[offset:offset+len(target)].tolist()
        results.append({"caseId":row["caseId"],"stage":row["stage"],"firstExpected":tok.decode(target[:1]),"firstPredicted":tok.decode(actual[:1]),"targetTokens":len(target),"teacherForcedCorrectTokens":sum(a==b for a,b in zip(actual,target)),"teacherForcedExactTarget":actual==target})
    report = {"diagnostic":"quant_aware_teacher_forcing_only", "rows":results,"backend":jax.default_backend(),
              "checkpointSha256":hashlib.sha256(args.checkpoint.read_bytes()).hexdigest(),
              "adapterSha256":hashlib.sha256(args.adapter.read_bytes()).hexdigest() if args.adapter else None,
              "developmentRowsSha256":hashlib.sha256(raw).hexdigest(),"maxLen":args.max_len,
              "calibrationOpened":False,"finalTestOpened":False,"nativeGenerationMeasured":False,
              "limits":"Token accuracy under the SFT prompt is not free-generation/native call accuracy; it can expose a render/runtime mismatch."}
    args.report.parent.mkdir(parents=True,exist_ok=True)
    args.report.write_text(json.dumps(report,indent=2)+"\n")
    print(json.dumps(report,indent=2),flush=True)


if __name__ == "__main__":
    main()

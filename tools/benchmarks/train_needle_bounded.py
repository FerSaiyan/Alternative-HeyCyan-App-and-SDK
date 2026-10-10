#!/usr/bin/env python3
"""Fixed-update, quant-aware upstream Needle LoRA with audited bounded choices.

Reuses pinned upstream initialization/LoRA/quantization/checkpoint routines.
No random validation split; no dataset synthesis or silent target truncation.
"""
import argparse
import hashlib
import json
import os
import time
from pathlib import Path


def main():
    p=argparse.ArgumentParser(description=__doc__)
    for name in ("checkpoint","data","out"):
        p.add_argument("--"+name,type=Path,required=True)
    p.add_argument("--updates",type=int,default=180)
    p.add_argument("--batch",type=int,default=8)
    p.add_argument("--max-len",type=int,default=768)
    p.add_argument("--seed",type=int,default=20261003)
    args=p.parse_args()
    if min(args.updates,args.batch,args.max_len)<=0 or args.out.exists():
        p.error("Positive budget and a fresh output adapter required")
    os.environ.setdefault("XLA_PYTHON_CLIENT_PREALLOCATE","false")
    import jax
    import jax.numpy as jnp
    import numpy as np
    import optax
    from needle.model.run import load_checkpoint
    from needle.model.architecture import SimpleAttentionNetwork
    from needle.model.finetune import init_lora, lora_target_paths, merge_lora, load_jsonl, render_example
    from needle.model.tokenizer import get_tokenizer
    from needle.model.quantize import configure_deploy, cq_ste_params, WEIGHT_BITS
    from needle.model.checkpoints import write_adapter
    if jax.default_backend()!="gpu":
        raise RuntimeError("This campaign requires CUDA; CPU fallback is not allowed")
    raw=(args.data/"train.needle.jsonl").read_bytes()
    rows=[json.loads(s) for s in raw.splitlines()]
    dev=[json.loads(s) for s in (args.data/"development.needle.jsonl").read_bytes().splitlines()]
    if not rows or {r["groupId"] for r in rows}&{r["groupId"] for r in dev}:
        raise ValueError("Empty training set or cross-cohort groups")
    params,config=load_checkpoint(str(args.checkpoint))
    config.dtype="float32"
    params=jax.device_put(jax.tree.map(lambda a:np.asarray(a).astype(np.float32),params))
    tokenizer=get_tokenizer(config.vocab_size)
    maximum=0
    for row in rows:
        available={t["name"] for t in row["tools"]}
        if len(row["answers"])!=1 or row["answers"][0]["name"] not in available or row["answers"][0]["arguments"]!={}:
            raise ValueError("A bounded row must supervise one offered zero-argument choice")
        prompt,target=render_example(row)
        maximum=max(maximum,len(tokenizer.encode(prompt))+len(tokenizer.encode(target))+2)
    if maximum>args.max_len:
        raise ValueError("Prompt/target would be truncated")
    seqs,masks=load_jsonl(str(args.data/"train.needle.jsonl"),tokenizer,args.max_len)
    model=SimpleAttentionNetwork(config)
    configure_deploy(act_bits=getattr(config,"act_bits",8),kv_bits=getattr(config,"kv_bits",8))
    rank,alpha=16,32
    lora=init_lora(params,lora_target_paths(params),rank,jax.random.PRNGKey(args.seed))
    scale=alpha/rank
    warmup=min(max(1,int(args.updates*0.05)),args.updates-1)
    schedule=optax.warmup_cosine_decay_schedule(init_value=0.,peak_value=1e-4,warmup_steps=warmup,decay_steps=args.updates,end_value=1e-5)
    optimizer=optax.chain(optax.clip_by_global_norm(1.0),optax.adamw(schedule,weight_decay=0.0))
    state=optimizer.init(lora)

    def loss_fn(adapter,ids,mask):
        deployed=cq_ste_params(merge_lora(params,adapter,scale),WEIGHT_BITS)
        logits=model.apply({"params":deployed},ids,quant=True)
        loss=optax.softmax_cross_entropy_with_integer_labels(logits[:,:-1],ids[:,1:])
        target_mask=mask[:,1:]
        return jnp.sum(loss*target_mask)/jnp.maximum(jnp.sum(target_mask),1.)

    @jax.jit
    def train_step(adapter,state,ids,mask):
        loss,grads=jax.value_and_grad(loss_fn)(adapter,ids,mask)
        updates,state=optimizer.update(grads,state,adapter)
        return optax.apply_updates(adapter,updates),state,loss

    rng=np.random.default_rng(args.seed)
    order=rng.permutation(len(seqs))
    cursor=0
    history=[]
    started=time.monotonic()
    print("gpu; fixed updates",args.updates,"batch",args.batch,"rank",rank,"max tokens",maximum,"cap",args.max_len,flush=True)
    for step in range(args.updates):
        indexes=[]
        while len(indexes)<args.batch:
            if cursor==len(order):
                order=rng.permutation(len(seqs));cursor=0
            take=min(args.batch-len(indexes),len(order)-cursor)
            indexes.extend(order[cursor:cursor+take]);cursor+=take
        lora,state,loss=train_step(lora,state,jnp.asarray(seqs[indexes]),jnp.asarray(masks[indexes]))
        if (step+1)%30==0 or step==args.updates-1:
            value=float(loss)
            if not np.isfinite(value):
                raise RuntimeError("Non-finite loss")
            history.append({"update":step+1,"loss":value})
            print("update",step+1,"loss",value,flush=True)
    args.out.parent.mkdir(parents=True,exist_ok=True)
    adapter={"lora":{"/".join(path):{k:np.asarray(v) for k,v in weights.items()} for path,weights in lora.items()},"scale":scale,"rank":rank,"alpha":alpha,"base":str(args.checkpoint),"examples":len(seqs),"epochs":args.updates*args.batch/len(seqs),"max_len":args.max_len,"seed":args.seed,"layers":config.num_layers}
    write_adapter(str(args.out),adapter)
    report={"updates":args.updates,"batch":args.batch,"examples":len(seqs),"trainingRowsSha256":hashlib.sha256(raw).hexdigest(),"adapterSha256":hashlib.sha256(args.out.read_bytes()).hexdigest(),"cap":args.max_len,"maxTokens":maximum,"history":history,"wallSeconds":time.monotonic()-started,"valSplit":0,"calibrationOpened":False,"finalTestOpened":False,"limits":"Training loss is not runtime call/rollout accuracy; use exported C-ABI evaluation."}
    args.out.with_suffix(".training.json").write_text(json.dumps(report,indent=2)+"\n")


if __name__=="__main__":
    main()

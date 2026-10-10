#!/usr/bin/env python3
"""Export an exact-shape Laya ONNX graph for bounded mobile-menu benchmarking.

Older pinned PyTorch/Transformers tracing bakes encoder reshape dimensions into
dynamic-axis ONNX exports. A fixed context/menu shape avoids that unsupported
dynamic graph while preserving the model inputs and masked-padding semantics.
"""
import argparse
import json
import sys
from pathlib import Path


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model",type=Path,required=True)
    parser.add_argument("--source",type=Path,required=True)
    parser.add_argument("--output",type=Path,required=True)
    parser.add_argument("--sequence-length",type=int,required=True)
    parser.add_argument("--max-options",type=int,required=True)
    args=parser.parse_args()
    args.model=args.model.resolve();args.source=args.source.resolve();args.output=args.output.resolve()
    if args.output.exists():raise FileExistsError("Refusing to overwrite ONNX graph: "+str(args.output))
    if not args.model.is_dir() or not args.source.is_dir():
        parser.error("Model and Laya source must be existing directories")
    if min(args.sequence_length,args.max_options)<2 or args.max_options>args.sequence_length:
        parser.error("Need at least two tokens/options and enough positions for every marker")
    sys.path.insert(0,str(args.source))
    import onnx
    import torch
    from laya.agent import Agent
    agent=Agent(str(args.model),compile=False,device="cpu")
    pad=int(agent.tok.pad_token_id or 0)
    input_ids=torch.full((1,args.sequence_length),pad,dtype=torch.long)
    attention=torch.zeros((1,args.sequence_length),dtype=torch.long)
    attention[:,0]=1
    marker_pos=torch.arange(args.max_options,dtype=torch.long).reshape(1,-1)
    marker_mask=torch.ones((1,args.max_options),dtype=torch.bool)
    qtype=torch.zeros((1,),dtype=torch.long)
    args.output.parent.mkdir(parents=True,exist_ok=True)
    torch.onnx.export(agent.model,(input_ids,attention,marker_pos,marker_mask,qtype),str(args.output),
                      export_params=True,opset_version=18,do_constant_folding=True,
                      input_names=["input_ids","attention_mask","marker_pos","marker_mask","qtype"],
                      output_names=["logits","act_logits"])
    graph=onnx.load(str(args.output))
    onnx.checker.check_model(graph)
    expected={"input_ids":[1,args.sequence_length],"attention_mask":[1,args.sequence_length],
              "marker_pos":[1,args.max_options],"marker_mask":[1,args.max_options],"qtype":[1]}
    shapes={value.name:[d.dim_value if d.HasField("dim_value") else None for d in value.type.tensor_type.shape.dim]
            for value in graph.graph.input}
    for name,shape in expected.items():
        if shapes.get(name)!=shape:
            raise ValueError(f"Unexpected exported input shape for {name}: {shapes.get(name)} != {shape}")
    print(json.dumps({"output":str(args.output),"bytes":args.output.stat().st_size,
                      "sequenceLength":args.sequence_length,"maxOptions":args.max_options,
                      "inputShapes":shapes,"sha256":__import__("hashlib").sha256(args.output.read_bytes()).hexdigest()},indent=2),flush=True)


if __name__=="__main__":main()

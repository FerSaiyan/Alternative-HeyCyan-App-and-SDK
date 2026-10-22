#!/usr/bin/env python3
"""AOT-compile a .tflite for a Qualcomm SoC and compare QNN HTP emulation with LiteRT CPU.

The QAIRT SDK's x86 libQnnHtp.so executes a serialized HTP context on the host,
with the DSP's fp16/quantized numerics. This catches graphs that compile fully but
compute garbage on the NPU (e.g. an fp16-overflowing front end) before a device run.
A clean result is necessary, not sufficient: device runs still decide.

Inputs: an .npz/.npy of N samples for the model's single input (first axis = sample).
Run with the NPU venv (ai-edge-litert + ai-edge-litert-sdk-qualcomm of the same
QAIRT as --qairt-root), e.g. ~/nvme_tmp/npu-litert230/env.
"""
import argparse
import json
import subprocess
import tempfile
from pathlib import Path

import numpy as np
import tflite
from flatbuffers import flexbuffers


def contexts(model_path):
    data = model_path.read_bytes()
    model = tflite.Model.GetRootAsModel(data, 0)
    found = []
    for s in range(model.SubgraphsLength()):
        subgraph = model.Subgraphs(s)
        for o in range(subgraph.OperatorsLength()):
            op = subgraph.Operators(o)
            if model.OperatorCodes(op.OpcodeIndex()).CustomCode() == b"DISPATCH_OP":
                options = flexbuffers.Loads(bytes(op.CustomOptionsAsNumpy()))
                found.append(data[options["bytecode_offset"]:options["bytecode_offset"] + options["bytecode_size"]])
    return found


def aot_compile(model_path, out_dir, soc):
    from ai_edge_litert.aot import aot_compile as aot
    from ai_edge_litert.aot.vendors.qualcomm import target as qnn_target
    result = aot.aot_compile(str(model_path), output_dir=str(out_dir), target=qnn_target.Target(qnn_target.SocModel[soc]),
                             keep_going=False)
    assert len(result.models_with_backend) == 1, result.compilation_report()
    _, compiled = result.models_with_backend[0]
    stats = [(s.num_ops_offloaded, s.num_total_ops, s.num_partitions_offloaded) for s in compiled.partition_stats.subgraph_stats]
    return Path(compiled.path), stats


def cpu_reference(model_path, samples):
    from ai_edge_litert.interpreter import Interpreter
    interpreter = Interpreter(model_path=str(model_path))
    interpreter.allocate_tensors()
    inp, out = interpreter.get_input_details()[0], interpreter.get_output_details()[0]
    outputs = []
    for x in samples:
        interpreter.set_tensor(inp["index"], x.reshape(inp["shape"]).astype(inp["dtype"]))
        interpreter.invoke()
        outputs.append(interpreter.get_tensor(out["index"]).copy())
    return outputs


def emulate(context, samples, qairt_root, work):
    work.mkdir(parents=True, exist_ok=True)
    (work / "ctx.bin").write_bytes(context)
    lines = []
    for i, x in enumerate(samples):
        path = work / f"in{i}.raw"
        x.astype(np.float32).tofile(path)
        lines.append(str(path))
    (work / "inputs.txt").write_text("\n".join(lines) + "\n")
    bin_dir, lib_dir = qairt_root / "bin/x86_64-linux-clang", qairt_root / "lib/x86_64-linux-clang"
    # The dynamic loader runs the tool even where the SDK lost its executable bits.
    subprocess.run(["/lib64/ld-linux-x86-64.so.2", str(bin_dir / "qnn-net-run"), "--backend", str(lib_dir / "libQnnHtp.so"),
                    "--retrieve_context", str(work / "ctx.bin"), "--input_list", str(work / "inputs.txt"),
                    "--output_dir", str(work / "out")], check=True, capture_output=True, env={"LD_LIBRARY_PATH": str(lib_dir)})
    results = []
    for i in range(len(samples)):
        raws = sorted((work / "out" / f"Result_{i}").glob("*.raw"))
        assert len(raws) == 1, f"expected one output tensor, got {raws}"
        results.append(np.fromfile(raws[0], np.float32))
    return results


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("model", type=Path, help="plain (not yet AOT-compiled) .tflite")
    parser.add_argument("--inputs", type=Path, required=True, help=".npy with N samples on axis 0")
    parser.add_argument("--qairt-root", type=Path, required=True)
    parser.add_argument("--soc", default="SM8650")
    parser.add_argument("--min-cos", type=float, default=0.99)
    parser.add_argument("--report", type=Path)
    parser.add_argument("--keep", type=Path, help="keep the AOT model, context, inputs and HTP outputs here")
    args = parser.parse_args()
    samples = list(np.load(args.inputs))
    with tempfile.TemporaryDirectory() as tmp:
        work = args.keep or Path(tmp)
        compiled, stats = aot_compile(args.model, work / "aot", args.soc)
        ctx = contexts(compiled)
        assert len(ctx) == 1, f"expected one NPU partition context, got {len(ctx)} (partition stats {stats})"
        htp = emulate(ctx[0], samples, args.qairt_root, work / "emu")
    cpu = [r.ravel().astype(np.float64) for r in cpu_reference(args.model, samples)]
    rows = []
    for i, (h, c) in enumerate(zip(htp, cpu)):
        h = h.astype(np.float64)
        assert h.size == c.size, f"sample {i}: HTP {h.size} values vs CPU {c.size}"
        rows.append({"sample": i, "finite": bool(np.isfinite(h).all()),
                     "cos": float(h @ c / (np.linalg.norm(h) * np.linalg.norm(c) + 1e-30)),
                     "maxAbsErr": float(np.max(np.abs(h - c))), "cpuMaxAbs": float(np.max(np.abs(c)))})
    report = {"model": str(args.model), "soc": args.soc, "partitionStats": stats,
              "minCos": min(r["cos"] for r in rows), "allFinite": all(r["finite"] for r in rows), "samples": rows}
    print(json.dumps({k: report[k] for k in ("model", "partitionStats", "minCos", "allFinite")}))
    if args.report:
        args.report.write_text(json.dumps(report, indent=2) + "\n")
    raise SystemExit(0 if report["allFinite"] and report["minCos"] >= args.min_cos else 1)


if __name__ == "__main__":
    main()

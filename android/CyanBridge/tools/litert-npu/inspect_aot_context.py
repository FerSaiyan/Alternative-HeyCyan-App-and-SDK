#!/usr/bin/env python3
"""Report the QAIRT build, SoC and DSP architecture of each QNN context in an AOT .tflite.

LiteRT's Qualcomm AOT output stores serialized QNN contexts in the file and points at
them from DISPATCH_OP custom options (bytecode_offset/bytecode_size). Each context is
copied verbatim to a temporary file and read with the SDK's qnn-context-binary-utility.
Use it to confirm a model targets the intended SoC and was compiled with the QAIRT
version the app ships (docs/NPU_ON_DEVICE.md).
"""
import argparse
import json
import subprocess
import tempfile
from pathlib import Path

import tflite
from flatbuffers import flexbuffers


def dispatch_contexts(model_bytes):
    model = tflite.Model.GetRootAsModel(model_bytes, 0)
    ranges = set()
    for s in range(model.SubgraphsLength()):
        subgraph = model.Subgraphs(s)
        for o in range(subgraph.OperatorsLength()):
            op = subgraph.Operators(o)
            if model.OperatorCodes(op.OpcodeIndex()).CustomCode() != b"DISPATCH_OP":
                continue
            options = flexbuffers.Loads(bytes(op.CustomOptionsAsNumpy()))
            ranges.add((options["bytecode_offset"], options["bytecode_size"]))
    return sorted(ranges)


def find(info, key):
    """First value for key anywhere in the utility's JSON."""
    if isinstance(info, dict):
        if key in info:
            return info[key]
        values = info.values()
    elif isinstance(info, list):
        values = info
    else:
        return None
    for value in values:
        found = find(value, key)
        if found is not None:
            return found
    return None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("model", type=Path)
    parser.add_argument("--qairt-root", type=Path, required=True)
    parser.add_argument("--expect-soc", type=int, help="QNN socModel, e.g. 57 for SM8650")
    parser.add_argument("--expect-dsp-arch", type=int, help="e.g. 75 for SM8650")
    parser.add_argument("--expect-build", help="QAIRT build prefix, e.g. v2.50.0.260828")
    args = parser.parse_args()
    tool = args.qairt_root / "bin/x86_64-linux-clang/qnn-context-binary-utility"
    data = args.model.read_bytes()
    contexts = dispatch_contexts(data)
    assert contexts, "No DISPATCH_OP contexts: not an AOT-compiled model"
    report = []
    with tempfile.TemporaryDirectory() as tmp:
        for i, (offset, size) in enumerate(contexts):
            binary, info_path = Path(tmp, f"ctx{i}.bin"), Path(tmp, f"ctx{i}.json")
            binary.write_bytes(data[offset:offset + size])
            subprocess.run([str(tool), "--context_binary", str(binary), "--json_file", str(info_path)],
                           check=True, capture_output=True,
                           env={"LD_LIBRARY_PATH": str(args.qairt_root / "lib/x86_64-linux-clang")})
            info = json.loads(info_path.read_text())
            report.append({"bytes": size, "buildId": find(info, "buildId"), "socModel": find(info, "socModel"),
                           "dspArch": find(info, "dspArch"), "coreApiVersion": find(info, "coreApiVersion"),
                           "backendApiVersion": find(info, "backendApiVersion"),
                           "graphs": [g.get("info", g).get("graphName") for g in find(info, "graphs") or []]})
    print(json.dumps({"model": str(args.model), "contexts": report}, indent=2))
    for ctx in report:
        if args.expect_soc is not None:
            assert ctx["socModel"] == args.expect_soc, f"socModel {ctx['socModel']} != {args.expect_soc}"
        if args.expect_dsp_arch is not None:
            assert ctx["dspArch"] == args.expect_dsp_arch, f"dspArch {ctx['dspArch']} != {args.expect_dsp_arch}"
        if args.expect_build:
            assert str(ctx["buildId"]).startswith(args.expect_build), f"buildId {ctx['buildId']} != {args.expect_build}*"


if __name__ == "__main__":
    main()

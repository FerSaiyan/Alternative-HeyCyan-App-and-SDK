#!/usr/bin/env python3
"""Inspect the audio-encoder sections of LiteRT-LM bundles for the shared-encoder speaker head.

Read-only. Reuses the header reader from ``litert_lm_builder`` (as
``gemma4-sm8650-port/scripts/extract_litertlm_sections.py`` does) but tolerates section data
types that the installed builder does not know (EmbeddingGemma 2 bundles use type 8, and
type 7 = ``TFLiteWeights`` external weight sections).

Subcommands:
  sections <bundle>...           list sections (index, raw data type, items, offsets, 16 KiB alignment)
  layers <bundle> [model_type]   constant bytes per encoder layer of a TFLite section, plus tensor
                                 types (TFLite type 19 is INT2 in the current schema)
  rss <model.tflite> [threads]   load + allocate + invoke with ai_edge_litert (XNNPACK) and print
                                 VmRSS / RssAnon / RssFile at each stage and the median invoke time

Run with the gemma4-sm8650-port ``NPU_env`` interpreter (litert_lm_builder, tflite, ai_edge_litert).
"""

from __future__ import annotations

import collections
import io
import json
import mmap
import re
import sys
import time

import numpy as np


def sections(path: str):
    from litert_lm_builder.litertlm_peek import kvp_to_dict, read_litertlm_header

    md = read_litertlm_header(path, io.StringIO())
    s = md.SectionMetadata()
    out = []
    for i in range(s.ObjectsLength()):
        sec = s.Objects(i)
        items = {}
        for j in range(sec.ItemsLength()):
            d = kvp_to_dict(sec.Items(j))
            items[d["key"]] = d["value"]
        out.append({"index": i, "data_type": sec.DataType(), "items": items, "begin": sec.BeginOffset(),
                    "end": sec.EndOffset(), "length": sec.EndOffset() - sec.BeginOffset(),
                    "aligned_16k": sec.BeginOffset() % 16384 == 0})
    return out


def layer_bytes(path: str, model_type: str = "tf_lite_audio_encoder_hw") -> dict:
    import tflite

    sec = next(s for s in sections(path) if s["items"].get("model_type") == model_type and s["data_type"] == 3)
    with open(path, "rb") as f:
        m = mmap.mmap(f.fileno(), 0, access=mmap.ACCESS_READ)
        model = tflite.Model.GetRootAsModel(memoryview(m)[sec["begin"] : sec["end"]], 0)
        sg = model.Subgraphs(0)
        by, types, seen = collections.Counter(), collections.Counter(), set()
        for o in range(sg.OperatorsLength()):
            op = sg.Operators(o)
            name = sg.Tensors(op.Outputs(0)).Name().decode() if op.OutputsLength() else ""
            mm = re.search(r"layer_(\d+)\.", name)
            key = f"layer_{int(mm.group(1)):02d}" if mm else ("subsample" if "feature" in name.lower() else "other")
            for k in range(op.InputsLength()):
                ti = op.Inputs(k)
                if ti < 0 or ti in seen:
                    continue
                t = sg.Tensors(ti)
                buf = model.Buffers(t.Buffer())
                n = buf.DataLength() or buf.Size()
                if n:
                    seen.add(ti)
                    by[key] += n
                    types[t.Type()] += n
        io_ = {"inputs": [], "outputs": []}
        for kind, getn, get in (("inputs", sg.InputsLength, sg.Inputs), ("outputs", sg.OutputsLength, sg.Outputs)):
            for x in range(getn()):
                t = sg.Tensors(get(x))
                io_[kind].append([t.Name().decode(), [t.Shape(k) for k in range(t.ShapeLength())], t.Type()])
    return {"section": sec, "bytes_by_part": dict(sorted(by.items())), "bytes_by_tensor_type": dict(types),
            "ops": sg.OperatorsLength(), "io": io_}


def rss_probe(model: str, threads: int = 4, n: int = 6) -> dict:
    from ai_edge_litert.interpreter import Interpreter

    def rss():
        d = {}
        for line in open("/proc/self/status"):
            if line.startswith(("VmRSS", "RssAnon", "RssFile", "VmHWM")):
                k, v = line.split(":")
                d[k] = int(v.split()[0]) // 1024
        return d

    res = {"imports_mb": rss()}
    it = Interpreter(model_path=model, num_threads=threads)
    res["load_mb"] = rss()
    it.allocate_tensors()
    res["alloc_mb"] = rss()
    rng = np.random.default_rng(0)
    for d in it.get_input_details():
        x = np.zeros(d["shape"], d["dtype"])
        if d["dtype"] == np.bool_:
            x[...] = True
        else:
            x = (rng.standard_normal(d["shape"]) * 0.1).astype(d["dtype"])
        it.set_tensor(d["index"], x)
    ts = []
    for _ in range(n):
        t0 = time.perf_counter()
        it.invoke()
        ts.append(time.perf_counter() - t0)
    res["invoke_mb"] = rss()
    res["invoke_ms_median"] = 1000 * float(np.median(ts[1:]))
    res["threads"] = threads
    return res


def main() -> None:
    cmd, *rest = sys.argv[1:]
    if cmd == "sections":
        print(json.dumps({p: sections(p) for p in rest}, indent=1))
    elif cmd == "layers":
        print(json.dumps(layer_bytes(*rest), indent=1))
    elif cmd == "rss":
        print(json.dumps(rss_probe(rest[0], int(rest[1]) if len(rest) > 1 else 4), indent=1))
    else:
        raise SystemExit(__doc__)


if __name__ == "__main__":
    main()

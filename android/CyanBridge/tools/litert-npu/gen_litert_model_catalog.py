#!/usr/bin/env python3
"""Generate the shared (KMP commonMain) catalog of the LiteRT CPU/GPU model files from the published manifest.

Reads manifest.json of Fertroll10/cyanbridge-npu-models at a pinned revision, keeps the entries with platform run
info (today: the `ios` field, written by ~/tools/npu-model-repo/publish_npu_models.py after device runs), downloads
each .tflite once to read its tensors (name, shape, dtype) with ai_edge_litert, checks size + sha256, and writes
  shared/src/commonMain/kotlin/com/fersaiyan/cyanbridge/shared/litert/LiteRtModelCatalog.kt
Every URL is pinned to the revision, so a later repo change never changes what an installed app downloads.

  ~/nvme_tmp/rt1126/qenv/bin/python -I tools/litert-npu/gen_litert_model_catalog.py --revision <sha>
(run from android/CyanBridge; the env needs ai_edge_litert + numpy). Refuses to run if the output is not under git.
"""

import argparse
import hashlib
import json
import sys
import urllib.request
from pathlib import Path

REPO = "Fertroll10/cyanbridge-npu-models"
OUT = Path("shared/src/commonMain/kotlin/com/fersaiyan/cyanbridge/shared/litert/LiteRtModelCatalog.kt")
KINDS = {
    "yolo11n-640": "YOLO11N",
    "ppocrv6-small-det-256x512": "OCR_DET",
    "ppocrv6-small-rec-48x320": "OCR_REC",
    "ppocrv6-small-rec-dict": "OCR_DICT",
    "ppocrv6-ocr-spec": "OCR_SPEC",
    "wespeaker-resnet34-2s": "WESPEAKER",
}
PRECISION = {None: "null", "DEFAULT": "LiteRtGpuPrecision.DEFAULT", "FP16": "LiteRtGpuPrecision.FP16",
             "FP32": "LiteRtGpuPrecision.FP32", "FP16_WITH_FP32_ACCUM": "LiteRtGpuPrecision.FP16_WITH_FP32_ACCUM"}


def url(rev: str, path: str) -> str:
    return f"https://huggingface.co/{REPO}/resolve/{rev}/{path}"


def fetch(u: str, dst: Path) -> None:
    with urllib.request.urlopen(u) as r, dst.open("wb") as f:
        while True:
            b = r.read(1 << 20)
            if not b:
                break
            f.write(b)


def sha256(p: Path) -> str:
    h = hashlib.sha256()
    with p.open("rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""):
            h.update(b)
    return h.hexdigest()


def kstr(s: str) -> str:
    return '"' + s.replace("\\", "\\\\").replace('"', '\\"').replace("$", "\\$") + '"'


def tensors(model: Path):
    from ai_edge_litert.interpreter import Interpreter
    it = Interpreter(model_path=str(model))
    def spec(d):
        shape = ", ".join(str(int(x)) for x in d["shape"])
        return f"LiteRtTensorSpec({kstr(d['name'])}, listOf({shape}), {kstr(d['dtype'].__name__)})"
    return [spec(d) for d in it.get_input_details()], [spec(d) for d in it.get_output_details()]


def hint(ios: dict) -> str:
    acc = "LiteRtAccelerator.GPU" if ios.get("accelerator") == "gpu" else "LiteRtAccelerator.CPU"
    fb = "LiteRtAccelerator.GPU" if ios.get("fallback") == "gpu" else "LiteRtAccelerator.CPU"
    cos = ios.get("min_cos_vs_cpu")
    return (f"LiteRtRunHint(accelerator = {acc}, gpuPrecision = {PRECISION[ios.get('gpu_precision')]}, "
            f"fallback = {fb}, programCache = {str(bool(ios.get('program_cache'))).lower()}, "
            f"minCosVsCpu = {cos if cos is not None else 'null'}, preferred = {str(bool(ios.get('preferred'))).lower()})")


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--revision", required=True, help="full commit sha of the HF repo")
    ap.add_argument("--cache", type=Path, default=Path.home() / ".cache/cyanbridge-litert-catalog")
    a = ap.parse_args()
    if len(a.revision) != 40:
        sys.exit("--revision must be a full 40-char commit sha")
    a.cache.mkdir(parents=True, exist_ok=True)
    man_path = a.cache / f"manifest-{a.revision}.json"
    if not man_path.exists():
        fetch(url(a.revision, "manifest.json"), man_path)
    manifest = json.loads(man_path.read_text())
    rows = []
    for e in manifest["entries"]:
        ios = e.get("ios")
        if not ios or e["model_id"] not in KINDS:
            continue
        path = e["path"]
        local = a.cache / f"{e['sha256'][:16]}-{Path(path).name}"
        if not local.exists() or sha256(local) != e["sha256"]:
            fetch(url(a.revision, path), local)
        if local.stat().st_size != e["bytes"] or sha256(local) != e["sha256"]:
            sys.exit(f"{path}: size/sha256 do not match the manifest")
        ins, outs = tensors(local) if path.endswith(".tflite") else ([], [])
        is_model = path.endswith(".tflite")
        rows.append(
            "        LiteRtModelFile(\n"
            f"            kind = LiteRtModelKind.{KINDS[e['model_id']]},\n"
            f"            modelId = {kstr(e['model_id'])},\n"
            f"            path = {kstr(path)},\n"
            f"            url = {kstr(url(a.revision, path))},\n"
            f"            sha256 = {kstr(e['sha256'])},\n"
            f"            bytes = {e['bytes']}L,\n"
            f"            inputs = listOf({', '.join(ins)}),\n"
            f"            outputs = listOf({', '.join(outs)}),\n"
            f"            embeddingVariant = {kstr(e['embedding_variant']) if e.get('embedding_variant') else 'null'},\n"
            f"            ios = {hint(ios) if is_model else 'null'},\n"
            f"            iosSupportFile = {str(not is_model).lower()},\n"
            "        ),")
    if not rows:
        sys.exit("no entries with platform run info in the manifest")
    text = (
        "package com.fersaiyan.cyanbridge.shared.litert\n\n"
        "/**\n"
        f" * Generated by tools/litert-npu/gen_litert_model_catalog.py from {REPO} @ {a.revision[:12]} (manifest.json,\n"
        " * entries with platform run info). URLs are pinned to that revision. Regenerate; do not edit.\n"
        " */\n"
        "object LiteRtModelCatalog {\n"
        f"    const val REVISION: String = {kstr(a.revision)}\n\n"
        "    val files: List<LiteRtModelFile> = listOf(\n" + "\n".join(rows) + "\n    )\n}\n")
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(text)
    print(f"wrote {OUT} ({len(rows)} files @ {a.revision[:12]})")


if __name__ == "__main__":
    main()

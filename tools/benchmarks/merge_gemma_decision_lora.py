#!/usr/bin/env python3
"""Merge a decision LoRA into its base Gemma checkpoint for LiteRT export.

Writes full merged weights plus the base tokenizer/template/processor files, and
records base/adapter/output hashes. The exporter pins an older transformers, so
the output keeps the base config.json and every base tensor key: tensors newer
transformers omit on save (Gemma 4 KV-shared layers' unused k/v projections,
which LoRA therefore never touched) are copied verbatim from the base. The
merged checkpoint is what the litert-torch exporter consumes; LiteRT-LM bundles
exported without LoRA slots cannot load the adapter at runtime.
"""
import argparse
import hashlib
import json
import shutil
from pathlib import Path

COPY_FROM_BASE = ("config.json", "tokenizer.json", "tokenizer_config.json", "chat_template.jinja",
                  "processor_config.json", "generation_config.json")


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def restore_missing_base_tensors(base, out):
    """Add base tensors absent from the merged single-file checkpoint."""
    from safetensors import safe_open
    from safetensors.torch import save_file
    merged_path = out / "model.safetensors"
    base_files = sorted(base.glob("*.safetensors"))
    with safe_open(merged_path, "pt") as merged:
        tensors = {key: merged.get_tensor(key) for key in merged.keys()}
        metadata = merged.metadata()
    restored = []
    for path in base_files:
        with safe_open(path, "pt") as source:
            for key in source.keys():
                if key not in tensors:
                    tensors[key] = source.get_tensor(key)
                    restored.append(key)
    if restored:
        save_file(tensors, merged_path, metadata=metadata)
    return sorted(restored)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    for name in ("base", "adapter", "out"):
        p.add_argument("--" + name, type=Path, required=True)
    args = p.parse_args()
    if args.out.exists() and any(args.out.iterdir()):
        p.error("Refuse to overwrite a non-empty --out")
    import torch
    from peft import PeftModel
    from transformers import AutoModelForCausalLM
    model = AutoModelForCausalLM.from_pretrained(args.base, dtype=torch.bfloat16)
    model = PeftModel.from_pretrained(model, args.adapter).merge_and_unload()
    model.save_pretrained(args.out, safe_serialization=True)
    for name in COPY_FROM_BASE:
        if (args.base / name).exists():
            shutil.copy2(args.base / name, args.out / name)
    restored = restore_missing_base_tensors(args.base, args.out)
    weights = sorted(args.out.glob("*.safetensors"))
    manifest = {"base": str(args.base), "adapter": str(args.adapter),
                "adapterSha256": sha256(args.adapter / "adapter_model.safetensors"),
                "restoredBaseTensors": restored,
                "outputs": {w.name: sha256(w) for w in weights}}
    (args.out / "merge_manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print(json.dumps(manifest, indent=2))


if __name__ == "__main__":
    main()

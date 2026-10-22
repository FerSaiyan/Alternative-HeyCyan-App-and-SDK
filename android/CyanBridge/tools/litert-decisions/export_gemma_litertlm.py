#!/usr/bin/env python3
"""Export a (merged) Hugging Face Gemma 4 checkpoint to a CPU `.litertlm` bundle.

Text-only, external embedder, fixed KV cache, default litert-torch INT8 dynamic
recipe. Run inside the pinned exporter environment (litert-torch 0.9.1,
torch 2.12.1+cpu, transformers 5.5.0); see LITERT_SHARED_DECISIONS.md.
"""
import argparse
import hashlib
import json
from pathlib import Path


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--model", required=True, help="Local HF checkpoint directory")
    p.add_argument("--output-dir", type=Path, required=True)
    p.add_argument("--cache-length", type=int, default=4096)
    p.add_argument("--prefill-lengths", type=int, nargs="+", default=[128])
    p.add_argument("--recipe", default="dynamic_wi8_afp32")
    p.add_argument("--chat-template", default="litert-community/gemma-4-E2B-it-litert-lm")
    args = p.parse_args()
    if args.output_dir.exists() and any(args.output_dir.rglob("*.litertlm")):
        p.error("Refuse to overwrite an existing bundle; use a fresh --output-dir")
    from litert_torch.generative.export_hf import export
    export.export(
        model=args.model, output_dir=str(args.output_dir), task="text_generation",
        prefill_lengths=args.prefill_lengths, cache_length=args.cache_length,
        quantization_recipe=args.recipe, externalize_embedder=True,
        export_vision_encoder=False, bundle_litert_lm=True,
        jinja_chat_template_override=args.chat_template,
    )
    bundles = sorted(args.output_dir.rglob("*.litertlm"))
    if len(bundles) != 1:
        raise SystemExit(f"Expected one .litertlm, found {bundles}")
    manifest = {"source": args.model, "bundle": str(bundles[0]), "bytes": bundles[0].stat().st_size,
                "sha256": sha256(bundles[0]), "cacheLength": args.cache_length,
                "prefillLengths": args.prefill_lengths, "recipe": args.recipe}
    (args.output_dir / "bundle_manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print(json.dumps(manifest, indent=2))


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Use upstream quantized builder with a checksum-verified local base archive.

The pinned upstream build_main otherwise force-downloads latest Hub weights to
obtain its tokenizer blob, even with a local checkpoint. This wrapper changes
only archive acquisition, preserving its LoRA merge/rung/W4 export algorithm.
"""
import argparse
import hashlib
from pathlib import Path


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--checkpoint", required=True)
    p.add_argument("--base-archive", type=Path, required=True)
    p.add_argument("--archive-sha256", required=True)
    p.add_argument("--lora")
    p.add_argument("--layers", type=int, default=20)
    p.add_argument("--out", required=True)
    args = p.parse_args()
    assert hashlib.sha256(args.base_archive.read_bytes()).hexdigest() == args.archive_sha256, "Unverified base archive"
    from needle.agent import fetch
    from needle.model.finetune import build_main

    def pinned_archive(generation=3, dest_dir=None, force=False):
        if generation != 3:
            raise ValueError("Only the pinned Needle 3 archive is supported")
        return str(args.base_archive.resolve())

    fetch.fetch_weights = pinned_archive
    args.platform, args.upload = None, False
    build_main(args)


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Device fixture for WalkingAidNpuDetectorTest (Qualcomm AI Hub style uint8 YOLO).

Writes each image preprocessed like the app (640x640 RGB uint8, raw HWC bytes), and a
manifest with the host LiteRT CPU and QNN HTP-emulated detections (score >= 0.35,
class-aware NMS 0.45, corners normalised to [0, 1]). The phone's NPU should
reproduce the emulated detections; its CPU path should reproduce the CPU ones. Use
public images only (e.g. COCO val2017); the fixture stays local, not in Git.
With a Qualcomm phone attached, --skip-htp avoids the slow host emulation: the device
test then dumps raw NPU outputs (-e yolo_dump_dir) for yolo_htp_parity.py --npu-raws.
"""
import argparse
import hashlib
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from yolo_htp_parity import load_pixels, model_detections, run_cpu, run_htp  # noqa: E402


def detections(raw, outs):
    return [{"label": label, "score": round(score, 6), "box": [round(float(v), 6) for v in box]}
            for score, label, box in model_detections(raw, outs)]


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--plain", type=Path, required=True)
    p.add_argument("--aot", type=Path, required=True)
    p.add_argument("--images", type=Path, required=True)
    p.add_argument("--qairt-root", type=Path, required=True)
    p.add_argument("--out", type=Path, required=True)
    p.add_argument("--skip-htp", action="store_true", help="no host HTP emulation (device-first evaluation)")
    args = p.parse_args()
    args.out.mkdir(parents=True, exist_ok=False)
    images = sorted(args.images.glob("*.jpg"))
    _, pixels = load_pixels(images)
    cpu, inp, outs = run_cpu(args.plain, pixels)
    htp = [None] * len(pixels) if args.skip_htp else run_htp(args.aot, pixels, inp, outs, args.qairt_root)
    rows = []
    for f, x, c, h in zip(images, pixels, cpu, htp):
        name = f.stem + ".rgb"
        (args.out / name).write_bytes(x.tobytes())
        rows.append({"file": name, "source": f.name, "cpu": detections(c, outs), "htp": detections(h, outs) if h else None})
    manifest = {"width": 640, "height": 640, "format": "rgb_u8_hwc",
                "plain_sha256": hashlib.sha256(args.plain.read_bytes()).hexdigest(),
                "aot_sha256": hashlib.sha256(args.aot.read_bytes()).hexdigest(), "images": rows}
    (args.out / "manifest.json").write_text(json.dumps(manifest, indent=1) + "\n")
    print(f"{len(rows)} images, {sum(len(r['cpu']) for r in rows)} CPU and {sum(len(r['htp'] or []) for r in rows)} HTP detections")


if __name__ == "__main__":
    main()

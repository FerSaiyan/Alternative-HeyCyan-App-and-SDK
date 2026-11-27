#!/usr/bin/env python3
"""Build the file pack that submit_device_run.sh pushes into the bench app's Documents folder.

Writes <out>/ with the models, float32/uint8 inputs and bench_plan.json (all flat: Device Run pushes
each file to BUNDLE_ID:/Documents/<name>), plus PACK_MANIFEST.json with sha256 per file.

Inputs (public data only):
  YOLO11n      4 COCO val2017 images as 640x640 RGB uint8 letterboxed (.rgb, the Android sweep's files)
  PP-OCR det   the same 4 images letterboxed top-left into 256x512, BGR, ImageNet mean/std (ocr_spec.json)
  PP-OCR rec   4 rendered text lines (DejaVu Sans) resized to 48 x <=320, BGR, (x/255-0.5)/0.5, pad 0.0
  WeSpeaker    4 LibriSpeech test-clean 2 s clips (eval_sub48 rows), float32 PCM [-1, 1];
               the body model gets their 198x80 fbank from wespeaker_frontend_2s.tflite on the host

Run with an env that has numpy, Pillow and ai_edge_litert, e.g.
  ~/nvme_tmp/rt1126/qenv/bin/python -I prepare_pack.py --out ~/nvme_tmp/ios-bench/pack_YYYYMMDD
Refuses to overwrite an existing output directory.
"""

import argparse
import hashlib
import json
import shutil
import sys
import urllib.request
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont

HF_REPO = "Fertroll10/cyanbridge-npu-models"
HF_REV = "22d594777ca4e5962daf59ce0f287efa4e3e2f4e"

# name -> (sha256, local candidates, HF path or None)
MODELS = {
    "yolo11n_640_v4noargmax_fp16w.tflite": (
        "8b3058eeeec6d573e71a2599651e47ebca65d4fab4608ead050ee788f9d7b7ed", "gpu/yolo11n_640_v4noargmax_fp16w.tflite"),
    "yolo11n_640_fp32_v4noargmax.tflite": (
        "989bf845258630098915c71359eb8896a4fd22abf68975fe2c5ab129194c3d94", "cpu_gpu/ANY/yolo11n_640_fp32_v4noargmax.tflite"),
    "ppv6small_det_256x512_fp32_v2.tflite": (
        "b1e685bd96347b344ffd2ce61191900c9ad4e5b3fdd5489ef33063b5651edbf1", "cpu_gpu/ANY/ppv6small_det_256x512_fp32_v2.tflite"),
    "ppv6small_rec_48x320_fp32_v2.tflite": (
        "97a3302e27131496e9edb8f3c7e20b51f885ded02b72ee1b4a6364066b79121a", "cpu_gpu/ANY/ppv6small_rec_48x320_fp32_v2.tflite"),
    "wespeaker_2s_fp32_v3.tflite": (
        "08f0e4b8e1e420d9b60ba096421b7eaf7d6e2fc8f9d5ca7d907b112deda403a4", "cpu_gpu/ANY/wespeaker_2s_fp32_v3.tflite"),
    # Not on HF: the WeSpeaker ResNet body after the CPU front end (input = 198x80 fbank).
    "wespeaker_body_fbank198.tflite": (
        "5bd45ce852516e667d586ddd8555d2c80bf0ff03200b7f23f2318a73c3d13915", None),
}
FRONTEND = ("wespeaker_frontend_2s.tflite", "a85196422fe4df69d22be41d563f5e9eda8ba0a59f678bc6ec50847d1881f23d")

COCO_RGB = ["000000000139.rgb", "000000000724.rgb", "000000000285.rgb", "000000000632.rgb"]
TEXT_LINES = ["STOP", "BUS 15 CENTRO", "Rua Augusta 1200", "Saida de emergencia"]
WSP_ROWS = [0, 12, 24, 36]

CONFIGS = ["cpu", "gpu:DEFAULT", "gpu:FP32", "gpu:FP16", "gpu:FP16_WITH_FP32_ACCUM", "gpu:FP16:tex", "gpu:FP16:cache"]


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def fetch_model(name: str, want: str, hf_path, local_dirs, out: Path) -> None:
    dst = out / name
    for d in local_dirs:
        src = d / name
        if src.is_file() and sha256(src) == want:
            shutil.copyfile(src, dst)
            return
    if hf_path is None:
        sys.exit(f"{name}: not found locally with sha256 {want} (searched {', '.join(map(str, local_dirs))})")
    url = f"https://huggingface.co/{HF_REPO}/resolve/{HF_REV}/{hf_path}"
    with urllib.request.urlopen(url) as r, dst.open("wb") as f:
        shutil.copyfileobj(r, f)
    got = sha256(dst)
    if got != want:
        dst.unlink()
        sys.exit(f"{name}: sha256 {got} != {want} from {url}")


def det_input(rgb640: np.ndarray) -> np.ndarray:
    """ocr_spec.json det: letterbox top-left into 256x512 (black canvas), BGR, ImageNet mean/std."""
    h, w = rgb640.shape[:2]
    r = min(256 / h, 512 / w)
    nh, nw = round(h * r), round(w * r)
    img = np.asarray(Image.fromarray(rgb640).resize((nw, nh), Image.BILINEAR))
    canvas = np.zeros((256, 512, 3), np.uint8)
    canvas[:nh, :nw] = img
    bgr = canvas[..., ::-1].astype(np.float32) / 255.0
    mean = np.array([0.485, 0.456, 0.406], np.float32)
    std = np.array([0.229, 0.224, 0.225], np.float32)
    return ((bgr - mean) / std).astype(np.float32)


def rec_input(text: str, font_path: Path) -> np.ndarray:
    """A rendered text line, then ocr_spec.json rec: keep aspect to 48 x <=320, BGR, (x-0.5)/0.5, pad 0."""
    font = ImageFont.truetype(str(font_path), 40)
    left, top, right, bottom = font.getbbox(text)
    img = Image.new("RGB", (right - left + 16, 56), (255, 255, 255))
    ImageDraw.Draw(img).text((8 - left, 8 - top), text, fill=(20, 20, 20), font=font)
    w, h = img.size
    rw = max(1, min(320, int(np.ceil(48 * w / h))))
    line = np.asarray(img.resize((rw, 48), Image.BILINEAR))[..., ::-1].astype(np.float32) / 255.0
    x = np.zeros((48, 320, 3), np.float32)
    x[:, :rw] = (line - 0.5) / 0.5
    return x


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out", required=True, type=Path)
    ap.add_argument("--models-dir", type=Path, action="append",
                    default=[Path.home() / "nvme_tmp/rt1126/gpu_models"])
    ap.add_argument("--coco-rgb-dir", type=Path, default=Path.home() / "nvme_tmp/rt1126/gs_in")
    ap.add_argument("--speech-npy", type=Path,
                    default=Path.home() / "nvme_tmp/speaker-npu-20261030/inputs/eval_sub48.npy",
                    help="LibriSpeech test-clean 2 s clips, float32 [N, 32000]")
    ap.add_argument("--font", type=Path, default=Path("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"))
    ap.add_argument("--iters", type=int, default=20)
    args = ap.parse_args()

    out = args.out.expanduser()
    if out.exists():
        sys.exit(f"{out} exists; pick a new name")
    out.mkdir(parents=True)

    for name, (want, hf_path) in MODELS.items():
        fetch_model(name, want, hf_path, args.models_dir, out)

    plan_models = []

    # YOLO: the Android sweep's 640x640 RGB letterboxed COCO frames, used as pixel / 255.
    rgbs = []
    for name in COCO_RGB:
        src = args.coco_rgb_dir / name
        data = src.read_bytes()
        if len(data) != 640 * 640 * 3:
            sys.exit(f"{src}: {len(data)} bytes, want 640*640*3")
        shutil.copyfile(src, out / f"coco_{name}")
        rgbs.append(np.frombuffer(data, np.uint8).reshape(640, 640, 3))
    coco = [f"coco_{n}" for n in COCO_RGB]
    plan_models.append({"file": "yolo11n_640_v4noargmax_fp16w.tflite", "inputs": coco,
                        "ref": "yolo11n_640_fp32_v4noargmax.tflite"})
    plan_models.append({"file": "yolo11n_640_fp32_v4noargmax.tflite", "inputs": coco})

    det = []
    for name, rgb in zip(COCO_RGB, rgbs):
        fn = f"det_{Path(name).stem}.f32"
        det_input(rgb).tofile(out / fn)
        det.append(fn)
    plan_models.append({"file": "ppv6small_det_256x512_fp32_v2.tflite", "inputs": det})

    rec = []
    for k, text in enumerate(TEXT_LINES):
        fn = f"rec_line{k}.f32"
        rec_input(text, args.font).tofile(out / fn)
        rec.append(fn)
    plan_models.append({"file": "ppv6small_rec_48x320_fp32_v2.tflite", "inputs": rec})

    # WeSpeaker: public LibriSpeech clips; fbank for the body model from the CPU front end.
    from ai_edge_litert.interpreter import Interpreter

    speech = np.load(args.speech_npy, mmap_mode="r")
    frontend_path = None
    for d in args.models_dir:
        p = d / FRONTEND[0]
        if p.is_file() and sha256(p) == FRONTEND[1]:
            frontend_path = p
    if frontend_path is None:
        sys.exit(f"{FRONTEND[0]} with sha256 {FRONTEND[1]} not found")
    fe = Interpreter(model_path=str(frontend_path))
    fe.allocate_tensors()
    fe_in, fe_out = fe.get_input_details()[0], fe.get_output_details()[0]
    pcm, fbank = [], []
    for row in WSP_ROWS:
        clip = np.ascontiguousarray(speech[row], np.float32).reshape(1, 32000)
        fn = f"wsp_pcm_{row:02d}.f32"
        clip.tofile(out / fn)
        pcm.append(fn)
        fe.set_tensor(fe_in["index"], clip)
        fe.invoke()
        feats = fe.get_tensor(fe_out["index"]).astype(np.float32)
        if feats.size != 198 * 80:
            sys.exit(f"front end output {feats.shape}, want 198x80")
        fn = f"wsp_fbank_{row:02d}.f32"
        feats.tofile(out / fn)
        fbank.append(fn)
    plan_models.append({"file": "wespeaker_body_fbank198.tflite", "inputs": fbank})
    plan_models.append({"file": "wespeaker_2s_fp32_v3.tflite", "inputs": pcm})

    plan = {"configs": CONFIGS, "iters": args.iters, "warmup": 3, "count": 4, "save_outputs": False,
            "models": plan_models}
    (out / "bench_plan.json").write_text(json.dumps(plan, indent=1) + "\n")

    manifest = {
        "hf": f"{HF_REPO}@{HF_REV}",
        "sources": {"coco": "COCO val2017 (CC BY 4.0)", "speech": "LibriSpeech test-clean (CC BY 4.0), rows "
                    + ",".join(map(str, WSP_ROWS)), "text": TEXT_LINES},
        "files": {p.name: {"bytes": p.stat().st_size, "sha256": sha256(p)} for p in sorted(out.iterdir())},
    }
    (out / "PACK_MANIFEST.json").write_text(json.dumps(manifest, indent=1) + "\n")
    total = sum(f["bytes"] for f in manifest["files"].values())
    print(f"pack: {out} ({len(manifest['files'])} files, {total / 1e6:.1f} MB)")


if __name__ == "__main__":
    main()

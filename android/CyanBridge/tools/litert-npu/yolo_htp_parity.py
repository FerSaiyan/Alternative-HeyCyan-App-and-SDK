#!/usr/bin/env python3
"""Compare Walking Aid YOLO detections: LiteRT CPU vs the AOT context in QNN HTP emulation.

Mirrors LiteRtVisionBackend for the two detector formats it accepts:
- Qualcomm AI Hub style uint8 export (YOLO11n: image [1,640,640,3] -> boxes [1,8400,4]
  corners in pixels, scores, class_idx);
- split float export (YOLO-World: float image in [0, 1] -> boxes [1,8400,4] corners in
  pixels, scores [1,8400,80]).

Both use the app's stretch-resize to 640x640 (bilinear, like createScaledBitmap), score
>= 0.35 and class-aware NMS at IoU 0.45. Tensors stay in their native types on both
sides. A CPU detection counts as reproduced when the NPU has the same label at IoU >= 0.5.
With --coco-annotations (COCO val2017 instances JSON), both backends are also scored
against ground truth: pycocotools mAP@[.5:.95] and mAP@.5 over all candidates with
score >= 0.01, and precision/recall at IoU 0.5 for the app's 0.35 threshold.
Run with the NPU venv; images are any public test set (e.g. COCO val2017).
"""
import argparse
import json
import re
import subprocess
import sys
import tempfile
from pathlib import Path

import numpy as np
from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parent))
from htp_emulator_parity import contexts  # noqa: E402

SIZE, SCORE_MIN, NMS_IOU, MATCH_IOU = 640, 0.35, 0.45, 0.5
# YOLO class index (80 COCO classes) -> COCO category id.
COCO80_TO_91 = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 27, 28, 31, 32,
                33, 34, 35, 36, 37, 38, 39, 40, 41, 42, 43, 44, 46, 47, 48, 49, 50, 51, 52, 53, 54, 55, 56, 57, 58, 59, 60,
                61, 62, 63, 64, 65, 67, 70, 72, 73, 74, 75, 76, 77, 78, 79, 80, 81, 82, 84, 85, 86, 87, 88, 89, 90]


def iou(a, b):
    w = max(0.0, min(a[2], b[2]) - max(a[0], b[0]))
    h = max(0.0, min(a[3], b[3]) - max(a[1], b[1]))
    inter = w * h
    union = (a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - inter
    return inter / union if union > 0 else 0.0


def decode(boxes, scores, classes, score_min=SCORE_MIN):
    keep = np.nonzero(scores >= score_min)[0]
    cands = sorted(((float(scores[i]), int(classes[i]), np.clip(boxes[i] / SIZE, 0, 1)) for i in keep),
                   key=lambda c: -c[0])
    selected = []
    while cands:
        best = cands.pop(0)
        selected.append(best)
        cands = [c for c in cands if not (c[1] == best[1] and iou(best[2], c[2]) >= NMS_IOU)]
    return selected


def load_pixels(images):
    """App preprocessing: RGB, stretch-resize to 640x640 (bilinear), uint8."""
    originals = [Image.open(f).convert("RGB") for f in images]
    return originals, [np.asarray(im.resize((SIZE, SIZE), Image.BILINEAR), dtype=np.uint8) for im in originals]


def roles(details):
    """Output tensor details by role: boxes, scores and (AI Hub exports) class_idx."""
    out = {}
    for d in details:
        if "class" in d["name"]:
            out["class_idx"] = d
        elif "box" in d["name"] or (d["shape"][-1] == 4 and "boxes" not in out):
            out["boxes"] = d
        else:
            out["scores"] = d
    assert {"boxes", "scores"} <= out.keys(), [d["name"] for d in details]
    return out


def dequantize(outs, name, raw):
    scale, zero = outs[name]["quantization"]
    return raw.astype(np.float32) if scale == 0 else (raw.astype(np.float32) - zero) * scale


def decode_anchor_distances(boxes):
    """LiteRtVisionBackend.decodeAnchorDistancesIfNeeded: ltrb distances on the 8/16/32 grid."""
    centers, strides = [], []
    for stride in (8, 16, 32):
        n = SIZE // stride
        cols, rows = np.meshgrid(np.arange(n), np.arange(n))
        centers.append(np.stack([cols.ravel() + 0.5, rows.ravel() + 0.5], 1))
        strides.append(np.full(n * n, stride))
    if sum(len(c) for c in centers) != len(boxes) or not 1.5 < boxes.max() <= 16.5 or boxes.min() < 0:
        return boxes
    c, st = np.concatenate(centers), np.concatenate(strides)[:, None]
    return np.concatenate([(c - boxes[:, :2]) * st, (c + boxes[:, 2:]) * st], 1)


def model_detections(raw, outs, score_min=SCORE_MIN):
    boxes = dequantize(outs, "boxes", raw["boxes"])[0]
    scores = dequantize(outs, "scores", raw["scores"])[0]
    if "class_idx" in raw:
        return decode(boxes, scores, raw["class_idx"][0], score_min)
    return decode(decode_anchor_distances(boxes), scores.max(axis=1), scores.argmax(axis=1), score_min)


def model_input(inp, x):
    if inp["dtype"] == np.uint8:
        assert abs(inp["quantization"][0] - 1 / 255) < 1e-6 and inp["quantization"][1] == 0, inp
        return x
    assert inp["dtype"] == np.float32, inp
    return x.astype(np.float32) / 255.0


def run_cpu(plain, pixels):
    """Native outputs per image (by role) from LiteRT CPU, plus input details and outputs by role."""
    from ai_edge_litert.interpreter import Interpreter
    interpreter = Interpreter(model_path=str(plain), num_threads=8)
    interpreter.allocate_tensors()
    inp = interpreter.get_input_details()[0]
    details = interpreter.get_output_details()
    for position, d in enumerate(details):
        d["position"] = position
    outs = roles(details)
    raws = []
    for x in pixels:
        interpreter.set_tensor(inp["index"], model_input(inp, x)[None])
        interpreter.invoke()
        raws.append({r: interpreter.get_tensor(d["index"]).copy() for r, d in outs.items()})
    return raws, inp, outs


def run_htp(aot, pixels, inp, outs, qairt_root):
    """Native outputs per image (by role) from the AOT context in QNN HTP emulation."""
    ctx = contexts(aot)
    assert len(ctx) == 1
    lib = qairt_root / "lib/x86_64-linux-clang"
    sanitize = lambda name: re.sub(r"\W", "_", name)  # noqa: E731 - QNN tensor file names
    with tempfile.TemporaryDirectory() as tmp:
        tmp = Path(tmp)
        (tmp / "ctx.bin").write_bytes(ctx[0])
        lines = []
        for i, x in enumerate(pixels):
            model_input(inp, x).tofile(tmp / f"in{i}.raw")
            lines.append(str(tmp / f"in{i}.raw"))
        (tmp / "inputs.txt").write_text("\n".join(lines) + "\n")
        subprocess.run(["/lib64/ld-linux-x86-64.so.2", str(qairt_root / "bin/x86_64-linux-clang/qnn-net-run"),
                        "--backend", str(lib / "libQnnHtp.so"), "--retrieve_context", str(tmp / "ctx.bin"),
                        "--input_list", str(tmp / "inputs.txt"), "--output_dir", str(tmp / "out"),
                        "--use_native_input_files", "--use_native_output_files"],
                       check=True, capture_output=True, env={"LD_LIBRARY_PATH": str(lib)})
        raws = []
        for i in range(len(pixels)):
            files = {f.stem: f for f in (tmp / "out" / f"Result_{i}").glob("*.raw")}
            raws.append({r: np.fromfile(next(f for stem, f in files.items() if stem.startswith(sanitize(d["name"]))),
                                        d["dtype"]).reshape(d["shape"])
                         for r, d in outs.items()})
    return raws


def load_device_raws(directory, images, outs):
    """Raw NPU outputs dumped by WalkingAidNpuDetectorTest (-e yolo_dump_dir): <image stem>.out<position>.bin."""
    return [{r: np.fromfile(directory / f"{f.stem}.out{d['position']}.bin", d["dtype"]).reshape(d["shape"]) for r, d in outs.items()}
            for f in images]


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--plain", type=Path, required=True)
    p.add_argument("--aot", type=Path, required=True)
    p.add_argument("--images", type=Path, required=True)
    p.add_argument("--qairt-root", type=Path, required=True)
    p.add_argument("--out", type=Path, required=True)
    p.add_argument("--coco-annotations", type=Path)
    p.add_argument("--cpu-only", action="store_true", help="skip HTP emulation (CPU ground-truth metrics only)")
    p.add_argument("--npu-raws", type=Path, help="use raw outputs dumped on a phone's NPU instead of host emulation")
    args = p.parse_args()
    images = sorted(args.images.glob("*.jpg"))
    originals, pixels = load_pixels(images)
    cpu_raw, inp, outs = run_cpu(args.plain, pixels)
    if args.npu_raws:
        htp_raw = load_device_raws(args.npu_raws, images, outs)
    else:
        htp_raw = cpu_raw if args.cpu_only else run_htp(args.aot, pixels, inp, outs, args.qairt_root)

    rows, total_cpu, reproduced, extra, ious, score_diffs = [], 0, 0, 0, [], []
    for f, c, h in zip(images, cpu_raw, htp_raw):
        cpu, npu = model_detections(c, outs), model_detections(h, outs)
        used = set()
        hit = 0
        for score, label, box in cpu:
            best = max(((iou(box, nb), j) for j, (_, nl, nb) in enumerate(npu) if nl == label and j not in used), default=(0, None))
            if best[0] >= MATCH_IOU:
                used.add(best[1])
                hit += 1
                ious.append(best[0])
                score_diffs.append(abs(score - npu[best[1]][0]))
        rows.append({"image": f.name, "cpuDetections": len(cpu), "npuDetections": len(npu), "reproduced": hit})
        total_cpu += len(cpu)
        reproduced += hit
        extra += len(npu) - hit
    report = {"images": len(images), "cpuDetections": total_cpu, "reproducedByNpu": reproduced,
              "npuExtraDetections": extra, "recallVsCpu": reproduced / max(total_cpu, 1),
              "meanMatchedIou": float(np.mean(ious)) if ious else None,
              "maxMatchedScoreDiff": float(np.max(score_diffs)) if score_diffs else None, "perImage": rows}
    if args.coco_annotations:
        report["coco"] = {name: coco_eval(args.coco_annotations, images, originals, raws, outs)
                          for name, raws in (("cpu", cpu_raw), ("htp", htp_raw))}
    args.out.write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps({k: v for k, v in report.items() if k != "perImage"}, indent=2))


def coco_eval(annotations, images, originals, raws, outs):
    """Ground-truth accuracy of one backend's outputs (boxes stretched back to the image)."""
    import contextlib
    import io
    from pycocotools.coco import COCO
    from pycocotools.cocoeval import COCOeval
    with contextlib.redirect_stdout(io.StringIO()):
        gt = COCO(str(annotations))
    by_name = {img["file_name"]: img["id"] for img in gt.dataset["images"]}
    ids = [by_name[f.name] for f in images]
    results, tp, n_pred = [], 0, 0
    n_gt = 0
    for image_id, im, raw in zip(ids, originals, raws):
        w, h = im.size
        dets = model_detections(raw, outs, 0.01)
        for score, label, box in dets:
            x0, y0, x1, y1 = box[0] * w, box[1] * h, box[2] * w, box[3] * h
            results.append({"image_id": image_id, "category_id": COCO80_TO_91[label],
                            "bbox": [float(x0), float(y0), float(x1 - x0), float(y1 - y0)], "score": score})
        # Operating point of the app: score >= 0.35, greedy match at IoU 0.5 per class.
        gts = [a for a in gt.loadAnns(gt.getAnnIds(imgIds=[image_id], iscrowd=False))]
        n_gt += len(gts)
        used = set()
        for score, label, box in dets:
            if score < SCORE_MIN:
                continue
            n_pred += 1
            pb = (box[0] * w, box[1] * h, box[2] * w, box[3] * h)
            best = max(((iou(pb, (a["bbox"][0], a["bbox"][1], a["bbox"][0] + a["bbox"][2], a["bbox"][1] + a["bbox"][3])), k)
                        for k, a in enumerate(gts) if a["category_id"] == COCO80_TO_91[label] and k not in used), default=(0, None))
            if best[0] >= 0.5:
                used.add(best[1])
                tp += 1
    with contextlib.redirect_stdout(io.StringIO()):
        evaluator = COCOeval(gt, gt.loadRes(results), "bbox")
        evaluator.params.imgIds = ids
        evaluator.evaluate()
        evaluator.accumulate()
        evaluator.summarize()
    return {"mAP50_95": round(float(evaluator.stats[0]), 4), "mAP50": round(float(evaluator.stats[1]), 4),
            "precisionAt035": round(tp / max(n_pred, 1), 4), "recallAt035": round(tp / max(n_gt, 1), 4),
            "detectionsAt035": n_pred, "groundTruth": n_gt}


if __name__ == "__main__":
    main()

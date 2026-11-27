#!/bin/bash
# Downloads 5 public CyanBridge models (Hugging Face, pinned revision, sha256-checked) into a small
# pack for the simulator smoke test, with a plan that uses synthetic inputs (no data files needed).
#
# Usage: ./scripts/fetch_smoke_pack.sh [<pack dir>]   (default: out/smoke_pack)
# Exit status: 1 on a download or checksum failure.
set -euo pipefail
HERE="$(cd "$(dirname "$0")/.." && pwd)"
PACK="${1:-$HERE/out/smoke_pack}"
REV=22d594777ca4e5962daf59ce0f287efa4e3e2f4e
BASE="https://huggingface.co/Fertroll10/cyanbridge-npu-models/resolve/$REV"
mkdir -p "$PACK"

fetch() {  # <repo path> <sha256>
  local name dst got
  name="$(basename "$1")"
  dst="$PACK/$name"
  if [ -f "$dst" ] && [ "$(shasum -a 256 "$dst" | cut -d' ' -f1)" = "$2" ]; then
    echo "have $name"
    return
  fi
  echo "fetch $name"
  curl -fsSL --retry 3 -o "$dst.part" "$BASE/$1"
  got="$(shasum -a 256 "$dst.part" | cut -d' ' -f1)"
  if [ "$got" != "$2" ]; then
    rm -f "$dst.part"
    echo "checksum mismatch for $name: $got != $2" >&2
    exit 1
  fi
  mv "$dst.part" "$dst"
}

fetch gpu/yolo11n_640_v4noargmax_fp16w.tflite 8b3058eeeec6d573e71a2599651e47ebca65d4fab4608ead050ee788f9d7b7ed
fetch cpu_gpu/ANY/yolo11n_640_fp32_v4noargmax.tflite 989bf845258630098915c71359eb8896a4fd22abf68975fe2c5ab129194c3d94
fetch cpu_gpu/ANY/ppv6small_det_256x512_fp32_v2.tflite b1e685bd96347b344ffd2ce61191900c9ad4e5b3fdd5489ef33063b5651edbf1
fetch cpu_gpu/ANY/ppv6small_rec_48x320_fp32_v2.tflite 97a3302e27131496e9edb8f3c7e20b51f885ded02b72ee1b4a6364066b79121a
fetch cpu_gpu/ANY/wespeaker_2s_fp32_v3.tflite 08f0e4b8e1e420d9b60ba096421b7eaf7d6e2fc8f9d5ca7d907b112deda403a4

cat > "$PACK/bench_plan.json" <<'EOF'
{
 "configs": ["cpu", "gpu:DEFAULT", "gpu:FP16:cache"],
 "iters": 3, "warmup": 1, "count": 1, "synthetic_inputs": 1, "save_outputs": false,
 "models": [
  {"file": "yolo11n_640_v4noargmax_fp16w.tflite", "ref": "yolo11n_640_fp32_v4noargmax.tflite"},
  {"file": "ppv6small_det_256x512_fp32_v2.tflite"},
  {"file": "ppv6small_rec_48x320_fp32_v2.tflite"},
  {"file": "wespeaker_2s_fp32_v3.tflite"}
 ]
}
EOF
rm -rf "$PACK/bench_out"
echo "smoke pack: $PACK"

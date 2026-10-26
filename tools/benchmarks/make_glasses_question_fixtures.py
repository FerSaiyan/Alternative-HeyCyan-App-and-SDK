#!/usr/bin/env python3
"""Build the CyanBridge glasses-question fixtures (catalog -> photos + stock-TTS audio).

The catalog lives in android/CyanBridge/training/glasses_questions_v1/questions.jsonl. This script turns it into
  <out>/images/<image_id>.jpg + images/manifest.json      (Wikimedia Commons, pinned by title + Commons SHA-1)
  <out>/audio/<row_id>__<voice>.wav + manifest.json + SHA256SUMS   (16 kHz mono PCM16, 300 ms lead / 600 ms tail)
and copies a small hermetic subset into app/src/androidTest/assets/glasses_questions/.

Only stock voices are used (Piper published voices, Kokoro preset voices, Supertonic preset voices); no cloning and
no reference recordings. Each TTS engine runs inside the interpreter that already has it installed, so the script is
split into subcommands that the `all` subcommand chains through subprocesses:

  images     (any Python with Pillow)            fetch pinned Commons originals, resize to max 1024 px JPEG q85
  synth      (engine interpreter, run per engine) synthesize raw float audio at the engine's native rate (.npy)
  finalize   (Python with numpy + scipy)          trim, resample_poly to 16 kHz, pad, peak-normalize, write WAV
  ci-subset  (any Python)                          copy the en/pt-BR test subset into the androidTest assets
  all        (any Python)                          run the above in order with the configured interpreters

Run Python with -I (isolated) because the voices/images are downloaded files. See the catalog README for the exact
commands used for v1.
"""
import argparse
import hashlib
import json
import os
import shutil
import subprocess
import sys
import time
from pathlib import Path

HOME = Path.home()
REPO = Path(__file__).resolve().parents[2]
CATALOG = REPO / "android/CyanBridge/training/glasses_questions_v1/questions.jsonl"
CI_ASSETS = REPO / "android/CyanBridge/app/src/androidTest/assets/glasses_questions"

VERSION = "v1.1 (20261023)"
VERSION_NOTES = ("v1.1 adds classes web (needs fresh information) and time (device clock/calendar): 11 new families x 9 langs; "
                 "weather_tomorrow plain->web (split train->test), time_zone plain->time. v1.0 clips are unchanged "
                 "(same raw audio). New Kokoro clips were synthesized with kokoro 0.9.4 / torch 2.6 (TRIBE env) instead of "
                 "torch 2.14 (removed venv): same weights/voices, ~1e-5 numeric drift. Supertonic reinstalled at the same pins "
                 "reproduces v1.0 bit-exactly.")
SR_OUT = 16000
LEAD_S, TAIL_S = 0.300, 0.600
PEAK_DBFS = -3.0
TRIM_DB = -50.0  # trim the engine's own leading/trailing silence below this level (relative to peak)
TRIM_KEEP_S = 0.040

PIPER_REV = "5041a7a49ae5b3a0f30e6837769eb03fdbb231bd"  # rhasspy/piper-voices
KOKORO_REV = "f3ff3571791e39611d31c381e3a41a3af07b4987"  # hexgrad/Kokoro-82M
SUPERTONIC2_REV = "75e6727618a02f323c720cba9478152d4bc16ca4"  # Supertone/supertonic-2 (pinned by supertonic 1.3.1)

# Piper voice -> (repo path, sha256 of the .onnx, dataset license from the voice MODEL_CARD)
PIPER_VOICES = {
    "en_US-lessac-medium": ("en/en_US/lessac/medium", "5efe09e69902187827af646e1a6e9d269dee769f9877d17b16b1b46eeaaf019f",
                            "Blizzard 2013 Lessac license (non-commercial research; see MODEL_CARD)"),
    "pt_BR-faber-medium": ("pt/pt_BR/faber/medium", "858555e3a064209c57088fe6bd70c4c3dc54d03eaa00c45d5ecaf43a33f95aa7", "CC0"),
    "es_ES-davefx-medium": ("es/es_ES/davefx/medium", "6658b03b1a6c316ee4c265a9896abc1393353c2d9e1bca7d66c2c442e222a917", "CC0"),
    "de_DE-thorsten-medium": ("de/de_DE/thorsten/medium", "7e64762d8e5118bb578f2eea6207e1a35a8e0c30595010b666f983fc87bb7819", "CC0"),
    "fr_FR-siwis-medium": ("fr/fr_FR/siwis/medium", "641d1ab097da2b81128c076810edb052b385decc8be3381814802a64a73baf99", "CC-BY 4.0"),
    "it_IT-paola-medium": ("it/it_IT/paola/medium", "6fc918b5a0ea6137382833dddfa567bffbe6a5060c02043c87192ee59c04210c",
                           "CC0-1.0 (dataset paolapersico1/Voice-Dataset-Italian)"),
    "ru_RU-dmitri-medium": ("ru/ru_RU/dmitri/medium", "f073356ebc4bd0f80c5af58df2953a5988bd5bdab1eb38635ce960b071fbefcb", "CC0"),
    # The CC0 zh_CN-chaowen voice needs the g2pW phonemizer, which is not installed; huayan uses espeak-ng (cmn).
    "zh_CN-huayan-medium": ("zh/zh_CN/huayan/medium", "9929917bf8cabb26fd528ea44d3a6699c11e87317a14765312420be230be0f3d",
                            "Unknown (MODEL_CARD; dataset PlayVoice/HuaYan_TTS)"),
}
KOKORO_LANG = {"af_heart": "a", "pf_dora": "p"}

# lang -> list of (voice_id, engine, model). The first entry is the primary voice (used for the CI subset).
VOICES = {
    "en": [("piper-lessac", "piper", "en_US-lessac-medium"), ("kokoro-af_heart", "kokoro", "af_heart")],
    "pt-BR": [("piper-faber", "piper", "pt_BR-faber-medium"), ("kokoro-pf_dora", "kokoro", "pf_dora")],
    "es": [("piper-davefx", "piper", "es_ES-davefx-medium")],
    "de": [("piper-thorsten", "piper", "de_DE-thorsten-medium")],
    "fr": [("piper-siwis", "piper", "fr_FR-siwis-medium")],
    "it": [("piper-paola", "piper", "it_IT-paola-medium")],
    "ru": [("piper-dmitri", "piper", "ru_RU-dmitri-medium")],
    "zh-CN": [("piper-huayan", "piper", "zh_CN-huayan-medium")],
    "ko": [("supertonic2-F1", "supertonic", "F1")],
}
SUPERTONIC_LANG = {"ko": "ko", "en": "en", "es": "es", "pt-BR": "pt", "fr": "fr"}

# Wikimedia Commons originals, pinned by title + Commons SHA-1 of the original upload.
IMAGES = {
    "socks_yellow": ("File:Bright yellow toe socks.jpg", "1a0d74dfbcc2014b57842ad2f99ac718f4065226"),
    "sign_no_parking": ("File:No Parking Any Time sign with crackle patina, New Orleans.jpg", "5d8daa81250d4d0870fa78fe9eff82b617938670"),
    "banana_ripe": ("File:Ripe-bananas.jpg", "07dc7be6265bfae5f0826e7e33043b0d2e2a8b85"),
    "price_tag_399": ("File:\"wow\" price tag.jpg", "51d47f4e3b645d882957ffce7b15f39dd57f8c4e"),
    "bus_london_2": ("File:London Bus route 2.jpg", "2b0d067ebcc5b76bb8979684b212cf253deca483"),
    "cereal_box_krave": ("File:Box of Kellog's Krave Double Chocolate cereal.jpg", "e52a602171f175fb13272b178a8ca35f79853da9"),
    "menu_fr_chalkboard": ("File:Menu de restaurant à Pont-Saint-Esprit.JPG", "f32137bb5b9eab16eb1dbacb0c4f2a57a304f512"),
    "plant_aloe": ("File:Aloe vera D190512.jpg", "1ac200da2d06b1ef2757931c8bc2795c71a3c2f2"),
    "people_three": ("File:Three walking students, Tulane University New Orleans, March 2008.jpg", "37bd9f5671ec3cdff7c38c3ac88d33d4fad281da"),
}
IMAGE_MAX_PX, IMAGE_QUALITY = 1024, 85

# CI subset: en + pt-BR, primary voice, these families (all in the test split).
CI_LANGS = ("en", "pt-BR")
CI_FAMILIES = ("color_of_object", "read_sign", "capital_city", "tell_joke", "tell_me_more", "weather_today", "next_holiday")

UA = {"User-Agent": "CyanBridgeTestFixtures/0.1 (offline test fixture builder; python-urllib)"}


def sha256_file(p):
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""):
            h.update(b)
    return h.hexdigest()


def load_rows(path):
    rows = [json.loads(line) for line in Path(path).read_text(encoding="utf-8").splitlines() if line.strip()]
    ids = [r["id"] for r in rows]
    assert len(ids) == len(set(ids)), "duplicate ids"
    return rows


def voice_file_id(voice_id):
    return voice_id


# ---------------------------------------------------------------- images
def http_get(url, tries=6):
    import urllib.error
    import urllib.request
    for i in range(tries):
        try:
            return urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=120).read()
        except urllib.error.HTTPError as e:
            if e.code != 429 or i == tries - 1:
                raise
            time.sleep(max(int(e.headers.get("Retry-After") or 0), 5 * (i + 1)))


def cmd_images(a):
    import html
    import re
    import urllib.parse
    from PIL import Image, ImageOps
    dl, out = Path(a.download_dir), Path(a.out) / "images"
    dl.mkdir(parents=True, exist_ok=True)
    out.mkdir(parents=True, exist_ok=True)
    titles = [t for t, _ in IMAGES.values()]
    q = urllib.parse.urlencode({"action": "query", "format": "json", "titles": "|".join(titles), "prop": "imageinfo",
                                "iiprop": "url|size|sha1|extmetadata",
                                "iiextmetadatafilter": "LicenseShortName|LicenseUrl|Artist|Credit"})
    meta = {p["title"]: p["imageinfo"][0] for p in json.loads(http_get("https://commons.wikimedia.org/w/api.php?" + q))["query"]["pages"].values()}
    strip = lambda s: html.unescape(re.sub(r"<[^>]+>", "", s or "")).strip()
    entries = []
    for image_id, (title, sha1) in IMAGES.items():
        ii = meta[title]
        if ii["sha1"] != sha1:
            raise SystemExit(f"{title}: Commons file changed (sha1 {ii['sha1']} != pinned {sha1})")
        src = ii["url"].split("?")[0]
        raw = dl / (image_id + Path(src).suffix.lower())
        if not raw.exists() or hashlib.sha1(raw.read_bytes()).hexdigest() != sha1:
            raw.write_bytes(http_get(src))
            time.sleep(1.0)
        if hashlib.sha1(raw.read_bytes()).hexdigest() != sha1:
            raise SystemExit(f"{title}: downloaded bytes do not match Commons sha1")
        im = ImageOps.exif_transpose(Image.open(raw)).convert("RGB")
        im.thumbnail((IMAGE_MAX_PX, IMAGE_MAX_PX), Image.LANCZOS)
        dst = out / f"{image_id}.jpg"
        im.save(dst, "JPEG", quality=IMAGE_QUALITY, optimize=True)
        m = {k: v.get("value") for k, v in ii["extmetadata"].items()}
        entries.append({"image_id": image_id, "file": f"images/{image_id}.jpg", "width": im.width, "height": im.height,
                        "bytes": dst.stat().st_size, "sha256": sha256_file(dst),
                        "source_title": title, "source_page": ii["descriptionurl"], "source_url": src,
                        "source_sha1": sha1, "source_size": [ii["width"], ii["height"]],
                        "author": strip(m.get("Artist")), "credit": strip(m.get("Credit")),
                        "license": m.get("LicenseShortName"), "license_url": m.get("LicenseUrl"),
                        "processing": f"EXIF-transposed, RGB, LANCZOS to max {IMAGE_MAX_PX}px, JPEG q{IMAGE_QUALITY}"})
        print(f"{image_id}: {im.width}x{im.height} {entries[-1]['license']} {entries[-1]['author']}")
    (out / "manifest.json").write_text(json.dumps({"images": entries}, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")


# ---------------------------------------------------------------- synth (runs inside the engine interpreter)
class PiperEngine:
    def __init__(self, a):
        from piper import PiperVoice, SynthesisConfig
        self.PiperVoice, self.cfg = PiperVoice, SynthesisConfig(noise_scale=0.0, noise_w_scale=0.0)
        self.dirs = [Path(d).expanduser() for d in a.piper_voice_dir]
        self.cache = {}

    def path(self, model):
        for d in self.dirs:
            p = d / f"{model}.onnx"
            if p.exists():
                return p
        raise SystemExit(f"missing Piper voice {model}.onnx in {self.dirs}; download "
                         f"https://huggingface.co/rhasspy/piper-voices/resolve/{PIPER_REV}/{PIPER_VOICES[model][0]}/{model}.onnx(.json)")

    def synth(self, text, lang, model):
        import numpy as np
        if model not in self.cache:
            p = self.path(model)
            if sha256_file(p) != PIPER_VOICES[model][1]:
                raise SystemExit(f"{p}: sha256 does not match pinned {PIPER_VOICES[model][1]}")
            self.cache[model] = self.PiperVoice.load(str(p))
        ch = list(self.cache[model].synthesize(text, syn_config=self.cfg))
        return np.concatenate([c.audio_float_array for c in ch]).astype("float32"), ch[0].sample_rate


class KokoroEngine:
    def __init__(self, a):
        from huggingface_hub import hf_hub_download
        from kokoro import KPipeline
        self.hf, self.KPipeline, self.dev = hf_hub_download, KPipeline, a.device
        self.pipes, self.model = {}, None

    def synth(self, text, lang, model):
        import numpy as np
        lc = KOKORO_LANG[model]
        if lc not in self.pipes:
            kw = {"model": self.model} if self.model is not None else {}
            self.pipes[lc] = self.KPipeline(lang_code=lc, repo_id="hexgrad/Kokoro-82M", device=self.dev, **kw)
            self.model = self.pipes[lc].model
        vp = self.hf("hexgrad/Kokoro-82M", f"voices/{model}.pt", revision=KOKORO_REV)
        import torch
        torch.manual_seed(int(hashlib.sha256(text.encode()).hexdigest()[:8], 16))  # vocoder noise -> deterministic
        out = [r.audio.cpu().numpy() if hasattr(r.audio, "cpu") else r.audio
               for r in self.pipes[lc](text, voice=vp, speed=1) if r.audio is not None]
        return np.concatenate(out).astype("float32"), 24000


class SupertonicEngine:
    def __init__(self, a):
        import numpy as np
        os.environ.setdefault("SUPERTONIC_CACHE_DIR", str(HOME / "nvme_tmp/glasses_q_fixtures/models/supertonic2"))
        from supertonic import TTS
        self.np, self.tts, self.styles = np, TTS(model="supertonic-2"), {}

    def synth(self, text, lang, model):
        if model not in self.styles:
            self.styles[model] = self.tts.get_voice_style(model)
        seed = int(hashlib.sha256(text.encode()).hexdigest()[:8], 16)
        self.np.random.seed(seed)  # the flow-matching initial noise comes from np.random
        wav, _ = self.tts.synthesize(text, voice_style=self.styles[model], lang=SUPERTONIC_LANG[lang])
        return wav.reshape(-1).astype("float32"), self.tts.sample_rate


ENGINES = {"piper": PiperEngine, "kokoro": KokoroEngine, "supertonic": SupertonicEngine}


def cmd_synth(a):
    import numpy as np
    rows = load_rows(a.catalog)
    raw = Path(a.out) / "raw"
    raw.mkdir(parents=True, exist_ok=True)
    eng = ENGINES[a.engine](a)
    n = 0
    t0 = time.time()
    for r in rows:
        for voice_id, engine, model in VOICES.get(r["lang"], []):
            if engine != a.engine:
                continue
            stem = f"{r['id']}__{voice_file_id(voice_id)}"
            if (raw / f"{stem}.json").exists() and not a.force:
                continue
            audio, sr = eng.synth(r["text"], r["lang"], model)
            np.save(raw / f"{stem}.npy", audio)
            (raw / f"{stem}.json").write_text(json.dumps({"id": r["id"], "voice": voice_id, "engine": engine, "model": model,
                                                          "sr": int(sr), "text": r["text"]}, ensure_ascii=False))
            n += 1
    print(f"{a.engine}: synthesized {n} clips in {time.time() - t0:.1f}s")


# ---------------------------------------------------------------- finalize
def process(audio, sr):
    import numpy as np
    from math import gcd
    from scipy.signal import resample_poly
    x = np.asarray(audio, dtype=np.float64).reshape(-1)
    if sr != SR_OUT:
        g = gcd(SR_OUT, sr)
        x = resample_poly(x, SR_OUT // g, sr // g)
    peak = float(np.max(np.abs(x))) if x.size else 0.0
    if peak <= 0:
        raise ValueError("silent clip")
    # trim the engine's own silence with a 5 ms frame envelope, keeping a small margin
    f = int(0.005 * SR_OUT)
    nf = len(x) // f
    env = np.abs(x[: nf * f]).reshape(nf, f).max(axis=1)
    on = np.where(env > peak * 10 ** (TRIM_DB / 20))[0]
    keep = int(TRIM_KEEP_S * SR_OUT)
    s, e = max(0, on[0] * f - keep), min(len(x), (on[-1] + 1) * f + keep)
    x = x[s:e]
    x = x * (10 ** (PEAK_DBFS / 20) / float(np.max(np.abs(x))))
    x = np.concatenate([np.zeros(int(LEAD_S * SR_OUT)), x, np.zeros(int(TAIL_S * SR_OUT))])
    pcm = np.round(x * 32767.0)
    assert np.max(np.abs(pcm)) < 32767, "clipping"
    return pcm.astype("<i2")


def write_wav(path, pcm):
    import wave
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR_OUT)
        w.writeframes(pcm.tobytes())


def cmd_finalize(a):
    import numpy as np
    rows = {r["id"]: r for r in load_rows(a.catalog)}
    out = Path(a.out)
    raw, audio_dir = out / "raw", out / "audio"
    audio_dir.mkdir(parents=True, exist_ok=True)
    entries, sums = [], []
    for j in sorted(raw.glob("*.json")):
        m = json.loads(j.read_text(encoding="utf-8"))
        r = rows[m["id"]]
        if r["text"] != m["text"]:
            raise SystemExit(f"{m['id']}: catalog text changed since synthesis; re-run synth with --force")
        pcm = process(np.load(j.with_suffix(".npy")), m["sr"])
        fn = f"{j.stem}.wav"
        write_wav(audio_dir / fn, pcm)
        h = sha256_file(audio_dir / fn)
        peak = float(np.max(np.abs(pcm.astype(np.int32)))) / 32768.0
        entries.append({"id": r["id"], "lang": r["lang"], "class": r["class"], "family": r["family"], "split": r["split"],
                        "image": r["image"], "voice": m["voice"], "engine": m["engine"], "model": m["model"],
                        "source_sr": m["sr"], "file": f"audio/{fn}", "duration_s": round(len(pcm) / SR_OUT, 3),
                        "peak_dbfs": round(20 * np.log10(peak), 2), "sha256": h})
        sums.append(f"{h}  audio/{fn}")
    img_manifest = out / "images/manifest.json"
    if img_manifest.exists():
        for im in json.loads(img_manifest.read_text(encoding="utf-8"))["images"]:
            sums.append(f"{im['sha256']}  {im['file']}")
    meta = {"version": VERSION, "notes": VERSION_NOTES, "catalog": str(CATALOG.relative_to(REPO)), "catalog_sha256": sha256_file(a.catalog), "sample_rate": SR_OUT,
            "format": "WAV PCM16 mono", "lead_silence_s": LEAD_S, "tail_silence_s": TAIL_S, "peak_dbfs": PEAK_DBFS,
            "resampler": "scipy.signal.resample_poly", "trim_db_rel_peak": TRIM_DB,
            "voices": {lang: [{"voice": v, "engine": e, "model": mo} for v, e, mo in vs] for lang, vs in VOICES.items()},
            "pins": {"piper_voices_rev": PIPER_REV, "kokoro_rev": KOKORO_REV, "supertonic2_rev": SUPERTONIC2_REV},
            "piper_voice_sha256": {k: v[1] for k, v in PIPER_VOICES.items()},
            "licenses": {"piper_code": "MIT (rhasspy/piper-voices repo)",
                         "piper_voice_datasets": {k: v[2] for k, v in PIPER_VOICES.items()},
                         "kokoro": "Apache-2.0 (hexgrad/Kokoro-82M weights + preset voices)",
                         "supertonic2": "BigScience OpenRAIL-M weights, MIT code (Supertone/supertonic-2)"},
            "clips": entries}
    (out / "manifest.json").write_text(json.dumps(meta, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    (out / "SHA256SUMS").write_text("\n".join(sorted(sums, key=lambda s: s.split("  ")[1])) + "\n")
    print(f"finalized {len(entries)} clips -> {out}")


# ---------------------------------------------------------------- ci subset
def cmd_ci_subset(a):
    out, dst = Path(a.out), Path(a.ci_dir)
    meta = json.loads((out / "manifest.json").read_text(encoding="utf-8"))
    rows = {r["id"]: r for r in load_rows(a.catalog)}
    imgs = {i["image_id"]: i for i in json.loads((out / "images/manifest.json").read_text(encoding="utf-8"))["images"]}
    primary = {lang: vs[0][0] for lang, vs in VOICES.items()}
    pick = [c for c in meta["clips"] if c["lang"] in CI_LANGS and c["family"] in CI_FAMILIES and c["voice"] == primary[c["lang"]]]
    dst.mkdir(parents=True, exist_ok=True)
    clips, used = [], {}
    for c in sorted(pick, key=lambda c: c["id"]):
        fn = Path(c["file"]).name
        shutil.copyfile(out / c["file"], dst / fn)
        assert sha256_file(dst / fn) == c["sha256"]
        r = rows[c["id"]]
        clips.append({"id": c["id"], "lang": c["lang"], "class": c["class"], "family": c["family"], "split": c["split"],
                      "text": r["text"], "expect": r["expect"], "image": (f"{r['image']}.jpg" if r["image"] else None),
                      "audio": fn, "voice": c["voice"], "model": c["model"], "duration_s": c["duration_s"], "sha256": c["sha256"]})
        if r["image"]:
            used[r["image"]] = imgs[r["image"]]
    images = []
    for image_id, im in sorted(used.items()):
        shutil.copyfile(out / im["file"], dst / f"{image_id}.jpg")
        assert sha256_file(dst / f"{image_id}.jpg") == im["sha256"]
        images.append({k: im[k] for k in ("image_id", "width", "height", "sha256", "source_page", "source_url", "author", "license", "license_url")}
                      | {"file": f"{image_id}.jpg"})
    man = {"source": "android/CyanBridge/training/glasses_questions_v1 (see README there)", "sample_rate": SR_OUT,
           "format": "WAV PCM16 mono, 300 ms lead / 600 ms tail silence, peak -3 dBFS", "clips": clips, "images": images}
    (dst / "manifest.json").write_text(json.dumps(man, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    total = sum(p.stat().st_size for p in dst.iterdir())
    print(f"ci subset: {len(clips)} clips, {len(images)} images, {total / 1e6:.2f} MB -> {dst}")
    if total >= 2_000_000:
        raise SystemExit("CI subset is >= 2 MB")


# ---------------------------------------------------------------- all
def cmd_all(a):
    me = str(Path(__file__).resolve())
    common = ["--catalog", str(a.catalog), "--out", str(a.out)]
    def run(py, *args, **env):
        cmd = [str(Path(py).expanduser()), "-I", me, *args]
        print("+", " ".join(cmd), flush=True)
        subprocess.run(cmd, check=True, env=dict(os.environ, PYTHONHASHSEED="0", **env))
    if not a.skip_images:
        run(a.images_python, "images", *common, "--download-dir", str(a.download_dir))
    pv = [x for d in a.piper_voice_dir for x in ("--piper-voice-dir", d)]
    run(a.piper_python, "synth", "--engine", "piper", *common, *pv)
    run(a.kokoro_python, "synth", "--engine", "kokoro", "--device", "cpu", *common, HF_HOME=str(a.kokoro_hf_home), HF_HUB_OFFLINE="1")
    run(a.supertonic_python, "synth", "--engine", "supertonic", *common)
    run(a.finalize_python, "finalize", *common)
    run(a.finalize_python, "ci-subset", *common, "--ci-dir", str(a.ci_dir))


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    def add(name, fn):
        p = sub.add_parser(name)
        p.add_argument("--catalog", type=Path, default=CATALOG)
        p.add_argument("--out", type=Path, default=HOME / "nvme_tmp/glasses_q_fixtures/build")
        p.set_defaults(fn=fn)
        return p
    p = add("images", cmd_images)
    p.add_argument("--download-dir", type=Path, default=HOME / "nvme_tmp/glasses_q_fixtures/dl_commons")
    p = add("synth", cmd_synth)
    p.add_argument("--engine", choices=sorted(ENGINES), required=True)
    p.add_argument("--piper-voice-dir", action="append", default=None)
    p.add_argument("--device", default="cpu")
    p.add_argument("--force", action="store_true")
    add("finalize", cmd_finalize)
    p = add("ci-subset", cmd_ci_subset)
    p.add_argument("--ci-dir", type=Path, default=CI_ASSETS)
    p = add("all", cmd_all)
    p.add_argument("--download-dir", type=Path, default=HOME / "nvme_tmp/glasses_q_fixtures/dl_commons")
    p.add_argument("--piper-voice-dir", action="append", default=None)
    p.add_argument("--ci-dir", type=Path, default=CI_ASSETS)
    p.add_argument("--skip-images", action="store_true")
    p.add_argument("--images-python", default=str(HOME / "anaconda3/bin/python"))
    p.add_argument("--piper-python", default=str(HOME / "anaconda3/bin/python"))
    # v1.0 (20261022) used ~/nvme_tmp/tts_bench/venv_{kokoro,supertonic}; those were removed before v1.1. Kokoro now
    # runs from the TRIBE env (kokoro/misaki 0.9.4, torch 2.6: ~1e-5 numeric drift vs v1.0, same voices); Supertonic was
    # reinstalled at the same pins (supertonic 1.3.1, onnxruntime 1.31.0, numpy 2.4.6) and reproduces v1.0 bit-exactly.
    p.add_argument("--kokoro-python", default=str(HOME / "nvme_tmp/tribev2/env/bin/python"))
    p.add_argument("--kokoro-hf-home", default=str(HOME / "nvme_tmp/tribev2/hf"))
    p.add_argument("--supertonic-python", default=str(HOME / "nvme_tmp/glasses_q_fixtures/venv_supertonic/bin/python"))
    p.add_argument("--finalize-python", default=str(HOME / "anaconda3/bin/python"))
    a = ap.parse_args()
    if getattr(a, "piper_voice_dir", "x") is None:
        a.piper_voice_dir = [str(HOME / "nvme_tmp/tribev2/inputs/voices"), str(HOME / "nvme_tmp/glasses_q_fixtures/dl_piper_voices")]
    a.fn(a)


if __name__ == "__main__":
    main()

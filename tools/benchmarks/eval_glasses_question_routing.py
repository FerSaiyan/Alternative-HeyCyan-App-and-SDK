#!/usr/bin/env python3
"""Zero-shot "does this glasses question need the camera?" decisions with plain Gemma 4 (no LoRA).

Host replica of a decide-once flow: the question is given as its transcript (text mode) or as the
recorded audio (audio mode), optionally with the photo already taken (image-mapped wake word). The
model scores two labels after the U+22B2 delimiter, normal and reversed menu order with fresh
position labels, averaged like SharedGemmaDecisionEngine:

  answer  -> answer by voice now, no camera
  camera  -> needs what the user is looking at (use the attached photo, or take one)

Expected choice (glasses_questions_v1 README): visual -> camera; plain -> answer;
ambiguous -> camera when a photo is attached, else answer.

  flock /tmp/tribe_gpu.lock <qat_lora_env>/bin/python -I eval_glasses_question_routing.py \
      --model <gemma-4-E2B-it transformers dir> --questions questions.jsonl \
      --fixtures /mnt/seagate/ML/cyanbridge-glasses-questions-20261022 --output out.jsonl
"""
import argparse
import json
import time
from collections import defaultdict
from pathlib import Path

DELIM = "⊲"
OPTIONS = {
    "answer": "Answer right away by voice. The question can be answered without seeing anything "
              "(general knowledge, math, advice, conversation).",
    "camera": "Look with the glasses camera. The question is about something the user is looking at, "
              "holding or pointing to (for example this, these, here, read this, what color, how much).",
}


# Exact strings of the app's decide-once turn (localmodels/engine/DecideOnce.kt, MainActivity).
APP_AUDIO_PROMPT = "Answer the question spoken in the attached audio. Do not merely transcribe it."
APP_ROUTING_INSTRUCTION = (
    "First decide whether you need to see what the user is looking at. If the question is about "
    "something in front of them (this, these, here, read this, what color, how much, which one), "
    "reply only ⊲B⊳. Otherwise reply ⊲A⊳ followed directly by your answer."
)


# v2: heard question first, then the label (DecideOnce.kt ROUTING_INSTRUCTION / RESPONSE_REGEX).
APP_V2_ROUTING_INSTRUCTION = (
    'Do not answer yet. First write the user\'s question on one line starting with "Q: ", in the '
    "language they spoke. On the next line write ⊲B⊳ if answering needs to see what the "
    "user is looking at, holding or pointing to (this, these, here, read this, what color, how much, "
    "which one), or ⊲A⊳ if it can be answered without looking."
)


# v3: turn 1 only writes the heard question; turn 2 is a separate text decision (same conversation).
APP_V3_TRANSCRIBE_INSTRUCTION = (
    'Do not answer yet. Write the user\'s question on one line starting with "Q: ", in the language '
    "they spoke. Write nothing else."
)
APP_V3_DECISION = (
    "Decide how to handle the question you just wrote. Do not answer it.\n"
    "⊲A⊳ " + "Answer right away by voice. The question can be answered without seeing anything "
    "(general knowledge, math, advice, conversation).\n"
    "⊲B⊳ " + "Look with the glasses camera. The question is about something the user is looking at, "
    "holding or pointing to (for example this, these, here, read this, what color, how much).\n"
    "Respond with only the best option label, written as ⊲label⊳."
)


def instruction(photo: bool, mode: str) -> str:
    question = "The user's spoken question is in the attached audio." if mode == "audio" else "The user's question is below."
    photo_line = ("A photo of what the user is looking at was already taken and is attached."
                  if photo else "No photo has been taken yet; looking costs a few seconds.")
    return ("You route questions asked to smart glasses. Do not answer the question.\n"
            f"{question}\n{photo_line}\nChoose exactly one option.")


def read_wav(path):
    """16 kHz mono PCM16 fixture -> float32 samples (no librosa needed)."""
    import wave
    import numpy as np
    with wave.open(str(path)) as w:
        assert w.getframerate() == 16000 and w.getnchannels() == 1 and w.getsampwidth() == 2, path
        return np.frombuffer(w.readframes(w.getnframes()), dtype="<i2").astype(np.float32) / 32768.0


def expected(row, photo):
    if row["class"] == "visual":
        return "camera"
    if row["class"] == "plain":
        return "answer"
    return "camera" if photo else "answer"


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--model", type=Path, required=True)
    ap.add_argument("--questions", type=Path, required=True)
    ap.add_argument("--fixtures", type=Path, required=True, help="dir with manifest.json, audio/, images/")
    ap.add_argument("--output", type=Path, required=True)
    ap.add_argument("--modes", default="text,audio")
    ap.add_argument("--splits", default="dev,test")
    ap.add_argument("--langs", default="")
    ap.add_argument("--limit", type=int, default=0)
    args = ap.parse_args()

    import torch
    from transformers import AutoModelForImageTextToText, AutoProcessor

    processor = AutoProcessor.from_pretrained(args.model)
    model = AutoModelForImageTextToText.from_pretrained(args.model, dtype=torch.bfloat16).cuda().eval()
    tok = processor.tokenizer
    label_ids = {c: tok(c, add_special_tokens=False).input_ids for c in "AB"}
    delim_ids = tok(DELIM, add_special_tokens=False).input_ids
    assert all(len(v) == 1 for v in label_ids.values()), label_ids

    manifest = json.loads((args.fixtures / "manifest.json").read_text())
    entries = manifest["clips"] if isinstance(manifest, dict) else manifest
    audio_by_id = {}
    for entry in entries:
        audio_by_id.setdefault(entry["id"], entry)
    images_dir = args.fixtures / "images"
    langs = {s for s in args.langs.split(",") if s}
    rows = [json.loads(s) for s in args.questions.read_text().splitlines() if s.strip()]
    rows = [r for r in rows if r["split"] in args.splits.split(",") and (not langs or r["lang"] in langs)]
    if args.limit:
        rows = rows[: args.limit]

    def image_path(row):
        if not row.get("image"):
            return None
        hits = sorted(images_dir.glob(row["image"] + ".*"))
        return hits[0] if hits else None

    transcripts = {}

    def self_transcribe(row):
        """selftx mode: the same model writes the question down from the audio (no external STT)."""
        if row["id"] in transcripts:
            return transcripts[row["id"]]
        content = [{"type": "audio", "audio": read_wav(args.fixtures / audio_by_id[row["id"]]["file"])},
                   {"type": "text", "text": "Write down exactly what the speaker asks, in the language they "
                                            "speak. Output only the question."}]
        inputs = processor.apply_chat_template(
            [{"role": "user", "content": content}], add_generation_prompt=True, tokenize=True,
            return_dict=True, return_tensors="pt", enable_thinking=False,
        ).to(model.device)
        with torch.no_grad():
            out = model.generate(**inputs, max_new_tokens=48, do_sample=False)
        text = processor.decode(out[0, inputs["input_ids"].shape[1]:], skip_special_tokens=True).strip()
        transcripts[row["id"]] = text
        return text

    def score_app(row, mode):
        """app_audio / app_text: the app's decide-once first turn (no photo yet), greedy label after ⊲."""
        content = []
        if mode == "app_audio":
            content.append({"type": "audio", "audio": read_wav(args.fixtures / audio_by_id[row["id"]]["file"])})
            text = APP_AUDIO_PROMPT
        else:
            text = f"Answer the user's question: {row['text']}"
        content.append({"type": "text", "text": f"{text}\n\n{APP_ROUTING_INSTRUCTION}"})
        started = time.perf_counter()
        inputs = processor.apply_chat_template(
            [{"role": "user", "content": content}], add_generation_prompt=True, tokenize=True,
            return_dict=True, return_tensors="pt", enable_thinking=False,
        ).to(model.device)
        delim = torch.tensor([delim_ids], device=model.device)
        inputs["input_ids"] = torch.cat([inputs["input_ids"], delim], 1)
        inputs["attention_mask"] = torch.cat([inputs["attention_mask"], torch.ones_like(delim)], 1)
        with torch.no_grad():
            logp = torch.log_softmax(model(**inputs).logits[0, -1].float(), -1)
        lp = torch.tensor([logp[label_ids[l][0]].item() for l in "AB"])
        p_camera = torch.softmax(lp, 0)[1].item()
        return ("camera" if p_camera > 0.5 else "answer"), [1 - p_camera, p_camera], round((time.perf_counter() - started) * 1000)

    def score_app_v2(row, mode):
        """app2_audio / app2_text: greedy "Q: ..." line (as the regex forces), then the label after ⊲."""
        content = []
        if mode == "app2_audio":
            content.append({"type": "audio", "audio": read_wav(args.fixtures / audio_by_id[row["id"]]["file"])})
            text = APP_AUDIO_PROMPT
        else:
            text = f"Answer the user's question: {row['text']}"
        content.append({"type": "text", "text": f"{text}\n\n{APP_V2_ROUTING_INSTRUCTION}"})
        started = time.perf_counter()
        inputs = processor.apply_chat_template(
            [{"role": "user", "content": content}], add_generation_prompt=True, tokenize=True,
            return_dict=True, return_tensors="pt", enable_thinking=False,
        ).to(model.device)
        prefix = torch.tensor([tok("Q: ", add_special_tokens=False).input_ids], device=model.device)
        ids = torch.cat([inputs["input_ids"], prefix], 1)
        extra = {k: v for k, v in inputs.items() if k not in ("input_ids", "attention_mask")}
        with torch.no_grad():
            out = model.generate(input_ids=ids, attention_mask=torch.ones_like(ids), **extra,
                                 max_new_tokens=60, do_sample=False)
        heard_ids = out[0, ids.shape[1]:].tolist()
        heard = tok.decode(heard_ids, skip_special_tokens=True).split("\n")[0].strip()
        line = tok(f"Q: {heard}\n{DELIM}", add_special_tokens=False).input_ids
        full = torch.cat([inputs["input_ids"], torch.tensor([line], device=model.device)], 1)
        with torch.no_grad():
            logp = torch.log_softmax(model(input_ids=full, attention_mask=torch.ones_like(full), **extra).logits[0, -1].float(), -1)
        lp = torch.tensor([logp[label_ids[l][0]].item() for l in "AB"])
        p_camera = torch.softmax(lp, 0)[1].item()
        transcripts[row["id"]] = heard
        return ("camera" if p_camera > 0.5 else "answer"), [1 - p_camera, p_camera], round((time.perf_counter() - started) * 1000)

    def score_app_v3(row):
        """app3_audio: turn 1 writes "Q: ..." (greedy), turn 2 asks for the label (fixed order, as on device)."""
        audio = read_wav(args.fixtures / audio_by_id[row["id"]]["file"])
        turn1 = [{"role": "user", "content": [
            {"type": "audio", "audio": audio},
            {"type": "text", "text": f"{APP_AUDIO_PROMPT}\n\n{APP_V3_TRANSCRIBE_INSTRUCTION}"}]}]
        started = time.perf_counter()
        inputs = processor.apply_chat_template(turn1, add_generation_prompt=True, tokenize=True,
                                               return_dict=True, return_tensors="pt", enable_thinking=False).to(model.device)
        with torch.no_grad():
            out = model.generate(**inputs, max_new_tokens=60, do_sample=False)
        reply = tok.decode(out[0, inputs["input_ids"].shape[1]:], skip_special_tokens=True).strip()
        heard = reply.split("Q:", 1)[-1].split("\n")[0].strip()
        convo = turn1 + [{"role": "assistant", "content": [{"type": "text", "text": f"Q: {heard}"}]},
                         {"role": "user", "content": [{"type": "text", "text": APP_V3_DECISION}]}]
        inputs = processor.apply_chat_template(convo, add_generation_prompt=True, tokenize=True,
                                               return_dict=True, return_tensors="pt", enable_thinking=False).to(model.device)
        delim = torch.tensor([delim_ids], device=model.device)
        inputs["input_ids"] = torch.cat([inputs["input_ids"], delim], 1)
        inputs["attention_mask"] = torch.cat([inputs["attention_mask"], torch.ones_like(delim)], 1)
        with torch.no_grad():
            logp = torch.log_softmax(model(**inputs).logits[0, -1].float(), -1)
        lp = torch.tensor([logp[label_ids[l][0]].item() for l in "AB"])
        p_camera = torch.softmax(lp, 0)[1].item()
        transcripts[row["id"]] = heard
        return ("camera" if p_camera > 0.5 else "answer"), [1 - p_camera, p_camera], round((time.perf_counter() - started) * 1000)

    def score(row, mode, image):
        photo = image is not None
        keys = list(OPTIONS)
        probs = [0.0, 0.0]
        started = time.perf_counter()
        for order in ([0, 1], [1, 0]):
            labels = ["A", "B"]
            menu = "\n".join(f"{DELIM}{labels[i]}⊳ {OPTIONS[keys[o]]}" for i, o in enumerate(order))
            text = instruction(photo, mode)
            if mode == "text":
                text += f"\n\nQuestion: {row['text']}"
            text += f"\n\n# Options\n{menu}\n\nRespond with only the best option label, written as {DELIM}label⊳."
            content = []
            if photo:
                content.append({"type": "image", "image": str(image)})
            if mode == "audio":
                content.append({"type": "audio", "audio": read_wav(args.fixtures / audio_by_id[row["id"]]["file"])})
            content.append({"type": "text", "text": text})
            inputs = processor.apply_chat_template(
                [{"role": "user", "content": content}], add_generation_prompt=True, tokenize=True,
                return_dict=True, return_tensors="pt", enable_thinking=False,
            ).to(model.device)
            delim = torch.tensor([delim_ids], device=model.device)
            inputs["input_ids"] = torch.cat([inputs["input_ids"], delim], 1)
            inputs["attention_mask"] = torch.cat([inputs["attention_mask"], torch.ones_like(delim)], 1)
            with torch.no_grad():
                logp = torch.log_softmax(model(**inputs).logits[0, -1].float(), -1)
            lp = torch.tensor([logp[label_ids[l][0]].item() for l in labels])
            sm = torch.softmax(lp, 0)
            for position, original in enumerate(order):
                probs[original] += sm[position].item() / 2
        winner = max(range(2), key=probs.__getitem__)
        return keys[winner], probs, round((time.perf_counter() - started) * 1000)

    out, stats = [], defaultdict(lambda: [0, 0])
    # A photo is taken for every image-mapped wake, so plain rows borrow a fixture photo for that case.
    fallback_image = next(iter(sorted(images_dir.glob("*.jpg"))), None)
    for row in rows:
        image = image_path(row) or fallback_image
        for mode in args.modes.split(","):
            if mode in ("audio", "selftx", "app_audio", "app2_audio", "app3_audio") and row["id"] not in audio_by_id:
                continue
            # The app's decide-once turn never sees the photo, so app modes have one condition.
            for photo in ((False,) if mode.startswith("app") else (False, True) if image else (False,)):
                if mode == "app3_audio":
                    choice, probs, ms = score_app_v3(row)
                elif mode.startswith("app2_"):
                    choice, probs, ms = score_app_v2(row, mode)
                elif mode.startswith("app_"):
                    choice, probs, ms = score_app(row, mode)
                elif mode == "selftx":
                    heard = self_transcribe(row)
                    choice, probs, ms = score(dict(row, text=heard), "text", image if photo else None)
                else:
                    choice, probs, ms = score(row, mode, image if photo else None)
                want = expected(row, photo)
                ok = choice == want
                for key in (f"{mode}|photo={photo}", f"{mode}|{row['class']}|photo={photo}", f"{mode}|{row['lang']}"):
                    stats[key][0] += ok
                    stats[key][1] += 1
                out.append({"id": row["id"], "lang": row["lang"], "class": row["class"], "split": row["split"],
                            "mode": mode, "photo": photo, "choice": choice, "expected": want, "ok": ok,
                            "pCamera": round(probs[1], 4), "ms": ms,
                            **({"heard": transcripts.get(row["id"])} if mode in ("selftx", "app2_audio", "app3_audio") else {})})
                print(json.dumps(out[-1]), flush=True)
    summary = {k: {"correct": v[0], "total": v[1], "acc": round(v[0] / v[1], 3)} for k, v in sorted(stats.items())}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text("".join(json.dumps(r) + "\n" for r in out) + json.dumps({"summary": summary}) + "\n")
    print(json.dumps(summary, indent=1))


if __name__ == "__main__":
    main()

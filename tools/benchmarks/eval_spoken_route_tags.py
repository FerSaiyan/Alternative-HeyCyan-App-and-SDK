#!/usr/bin/env python3
"""Host check of the spoken-question decision prompt: does Gemma 4 start its reply with the right route tag?

The phone sends a glasses question as the recording alone, with the instructions in the system turn
(ImageQuestionDefaults.spokenQuestionInstruction). The decision layout asks the model to begin its reply
with one tag and act on it:

  [answer] <answer>   [camera]   [search] <query>   [phone] <task>   [live]   [ignore]

For every recording this script records the greedy reply (what the phone would get) and the tag
probabilities at the tag position (prefix + "[", softmax over the six tag words' first tokens), so prompt
variants can be compared for greedy accuracy and for what a calibrated threshold could do.

Expected tag per catalog class: visual -> camera (no photo attached), plain/time -> answer, web -> search,
action -> phone, live -> live, ignore -> ignore; ambiguous rows are reported but not scored.

  flock /tmp/tribe_gpu.lock ~/nvme_tmp/akio_oral_vlm_local/env/bin/python -I eval_spoken_route_tags.py \
      --model ~/nvme_tmp/akio_oral_vlm_local/models/gemma-4-E2B-it-qat-mobile-transformers \
      --catalog <questions.jsonl>=<fixtures dir> [--catalog ...] --prompts v1,v2 --output out.jsonl
"""
import argparse
import json
import time
from collections import Counter, defaultdict
from pathlib import Path

TAGS = ["answer", "camera", "search", "phone", "live", "ignore"]
EXPECTED = {"visual": "camera", "plain": "answer", "time": "answer", "web": "search", "action": "phone",
            "live": "live", "ignore": "ignore", "memory": "answer"}

GLASSES = (
    "You are the voice assistant in the user's smart glasses. The user talks to you out loud: their message is a "
    "recording of their voice. Reply directly to what they said, in the language they spoke. Never repeat, quote or "
    "transcribe their words."
)

ROUTES_V1 = (
    "Begin every reply with one tag in square brackets:\n"
    "[answer] then your answer: you can answer from what they said and what you know.\n"
    "[camera] and nothing else: answering needs to see what they are looking at.\n"
    "[search] then a short web search query: answering needs current information from the internet (weather, news, "
    "prices, scores, opening hours, schedules).\n"
    "[phone] then the task in one short sentence: they ask you to do something on their phone (open an app, play "
    "music, call, send a message, set a timer, navigate).\n"
    "[live] and nothing else: they want a continuous live conversation with you, or want you to keep watching and "
    "describing while they move.\n"
    "[ignore] and nothing else: they are talking to someone else, not to you, or they want to stop.\n"
    "Examples (the user's words are written here; in the conversation they are spoken):\n"
    "\"What's 15 percent of 80?\" -> [answer] 12.\n"
    "\"What does this label say?\" -> [camera]\n"
    "\"Will it rain tomorrow?\" -> [search] rain forecast tomorrow\n"
    "\"Play some jazz on YouTube.\" -> [phone] Play jazz on YouTube.\n"
    "\"Let's just talk for a while.\" -> [live]\n"
    "\"Mom, dinner's ready!\" -> [ignore]"
)

# v2: camera rule made explicit (they cannot be answered without looking; never guess what they see),
# ignore cues (a name or pet name addressing someone else, phone-call talk, "never mind"), more examples.
ROUTES_V2 = (
    "Begin every reply with exactly one tag in square brackets, chosen by what answering needs:\n"
    "[camera] and nothing else: the question is about something the user is looking at, holding or pointing to "
    "(\"this\", \"these\", \"that\", \"here\", read this, what color, how much is this, which one, what is this). You "
    "cannot see without the camera, so never guess what they see.\n"
    "[search] then a short web search query: it needs current information from the internet (weather, news, prices, "
    "exchange rates, scores, opening hours, schedules, traffic).\n"
    "[phone] then the task in one short sentence: they ask you to do something on their phone (open an app, play "
    "music, call, send a message, set a timer or alarm, turn something on or off, navigate).\n"
    "[live] and nothing else: they want a continuous live conversation, or want you to keep watching and describing "
    "while they move.\n"
    "[ignore] and nothing else: they are talking to someone else (a person's name or a pet name like honey or mom, "
    "talk from a phone call) or they want to stop (never mind, that's all).\n"
    "[answer] then your answer: anything else you can answer from what they said, the current time below and what "
    "you know.\n"
    "Examples (the user's words are written here; in the conversation they are spoken):\n"
    "\"What color is this shirt?\" -> [camera]\n"
    "\"What does this sign say?\" -> [camera]\n"
    "\"What's 15 percent of 80?\" -> [answer] 12.\n"
    "\"What day is it today?\" -> [answer] It's Friday.\n"
    "\"Will it rain tomorrow?\" -> [search] rain forecast tomorrow\n"
    "\"Play some jazz on YouTube.\" -> [phone] Play jazz on YouTube.\n"
    "\"Let's just talk for a while.\" -> [live]\n"
    "\"Honey, where are the keys?\" -> [ignore]\n"
    "\"Okay, thanks, that's all.\" -> [ignore]"
)

# v3: v2 plus the misses of the 2026-10-24 host run: "open Gemini Live" was [phone] in every language, talk
# on a phone call / to another person was answered. Examples use phrasings that are not in the fixtures.
ROUTES_V3 = (
    "Begin every reply with exactly one tag in square brackets, chosen by what answering needs:\n"
    "[camera] and nothing else: the question is about something the user is looking at, holding or pointing to "
    "(\"this\", \"these\", \"that\", \"here\", read this, what color, how much is this, which one, what is this). You "
    "cannot see without the camera, so never guess what they see.\n"
    "[search] then a short web search query: it needs current information from the internet (weather, news, prices, "
    "exchange rates, scores, opening hours, schedules, traffic).\n"
    "[live] and nothing else: they want to start Gemini Live or live mode, a continuous conversation with you, or "
    "want you to keep watching and describing while they move. Starting Gemini Live is always [live], never [phone].\n"
    "[phone] then the task in one short sentence: they ask you to do something on their phone (open an app, play "
    "music, call, send a message, set a timer or alarm, turn something on or off, navigate).\n"
    "[ignore] and nothing else: they are not talking to you. They talk to another person (calling them by a name or a "
    "pet name like honey or mom, or talking on a phone call: greetings, goodbyes, \"I'll be there soon\"), or they "
    "want to stop (never mind, that's all, forget it). Do not reply to these.\n"
    "[answer] then your answer: anything else you can answer from what they said, the current time below and what "
    "you know.\n"
    "Examples (the user's words are written here; in the conversation they are spoken):\n"
    "\"What color is this shirt?\" -> [camera]\n"
    "\"What does this sign say?\" -> [camera]\n"
    "\"What's 15 percent of 80?\" -> [answer] 12.\n"
    "\"What day is it today?\" -> [answer] It's Friday.\n"
    "\"Will it rain tomorrow?\" -> [search] rain forecast tomorrow\n"
    "\"Play some jazz on YouTube.\" -> [phone] Play jazz on YouTube.\n"
    "\"Turn on do not disturb.\" -> [phone] Turn on do not disturb.\n"
    "\"Can you start Gemini Live?\" -> [live]\n"
    "\"Let's just talk for a while.\" -> [live]\n"
    "\"Sure, I'm leaving the office now, talk later.\" -> [ignore]\n"
    "\"Pedro, pass me the salt.\" -> [ignore]\n"
    "\"Okay, thanks, that's all.\" -> [ignore]"
)

# v4: v3 with the search payload written as a complete question in English. DuckDuckGo Search Assist (the app's
# keyless web answers) only answers English queries and skips bare keywords ("bitcoin price", "weather today").
ROUTES_V4 = ROUTES_V3.replace(
    "[search] then a short web search query: it needs",
    "[search] then their question as one short, complete question in English, keeping any place, name or date they "
    "said: it needs",
).replace(
    "\"Will it rain tomorrow?\" -> [search] rain forecast tomorrow\n",
    "\"Will it rain tomorrow?\" -> [search] Will it rain tomorrow?\n"
    "\"Quem ganhou o jogo do Palmeiras ontem?\" -> [search] Who won the Palmeiras game yesterday?\n",
)
assert ROUTES_V4.count("complete question in English") == 1 and "Palmeiras" in ROUTES_V4

# v4 (2026-10-24, partial): plain questions went to [search] ("[search] What is the capital of Australia?").
# v5a only asks for the query in English; v5b also makes the weather example a question with a place.
ROUTES_V5A = ROUTES_V3.replace("[search] then a short web search query:", "[search] then a short web search query in English:")
ROUTES_V5B = ROUTES_V5A.replace(
    "\"Will it rain tomorrow?\" -> [search] rain forecast tomorrow\n",
    "\"Will it rain tomorrow in Lisbon?\" -> [search] will it rain tomorrow in Lisbon\n",
)
assert ROUTES_V5A != ROUTES_V3 and ROUTES_V5B != ROUTES_V5A

# Spoken-question memory block (MainActivity.spokenMemoryContext): fictional facts, the four the memory questions ask
# about (questions_memory_followup_draft.jsonl) among unrelated ones, as a user's USER_FACTS.md would hold.
MEMORY = (
    "What you know about the user (use it only when their question needs it):\n"
    "- Name: Rafael. Lives in Ribeirão Preto, Brazil; works as a software developer.\n"
    "- Wife: Ana. Dog: Biscuit, a beagle.\n"
    "- Allergic to peanuts.\n"
    "- Dentist appointment on Thursday at 3 pm.\n"
    "- Likes Pokémon TCG, running and Palmeiras.\n"
    "- Prefers short answers and Celsius.\n"
    "- Today: had a meeting about the glasses app at 10:00; bought coffee beans."
)

PROMPTS = {"v1": f"{GLASSES}\n\n{ROUTES_V1}", "v2": f"{GLASSES}\n\n{ROUTES_V2}", "v3": f"{GLASSES}\n\n{ROUTES_V3}",
           "v4": f"{GLASSES}\n\n{ROUTES_V4}", "v5a": f"{GLASSES}\n\n{ROUTES_V5A}", "v5b": f"{GLASSES}\n\n{ROUTES_V5B}",
           "v5a_mem": f"{GLASSES}\n\n{ROUTES_V5A}\n\n{MEMORY}",
           # Memory first: v5a_mem lost ignore/live rows (names in memory make talk to others look addressed).
           "v5a_memfirst": f"{MEMORY}\n\n{GLASSES}\n\n{ROUTES_V5A}"}
CLOCK = "Current local time: Saturday 2026-10-24 14:05 (America/Sao_Paulo), device region BR."


def read_wav(path):
    import wave
    import numpy as np
    with wave.open(str(path)) as w:
        assert w.getframerate() == 16000 and w.getnchannels() == 1 and w.getsampwidth() == 2, path
        return np.frombuffer(w.readframes(w.getnframes()), dtype="<i2").astype(np.float32) / 32768.0


def parse_tag(text):
    import re
    m = re.match(r"\s*\[\s*([a-z]+)\s*\]", text, re.I)
    return m.group(1).lower() if m and m.group(1).lower() in TAGS else None


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--model", type=Path, required=True)
    ap.add_argument("--catalog", action="append", required=True, help="questions.jsonl=fixtures_dir")
    ap.add_argument("--prompts", default="v1,v2")
    ap.add_argument("--langs", default="")
    ap.add_argument("--splits", default="test,dev")
    ap.add_argument("--per-class", type=int, default=0, help="max rows per (lang, class); 0 = all")
    ap.add_argument("--max-new-tokens", type=int, default=24)
    ap.add_argument("--output", type=Path, required=True)
    args = ap.parse_args()

    import torch
    from transformers import AutoModelForImageTextToText, AutoProcessor

    processor = AutoProcessor.from_pretrained(args.model)
    model = AutoModelForImageTextToText.from_pretrained(args.model, dtype=torch.bfloat16).cuda().eval()
    tok = processor.tokenizer
    bracket = tok("[", add_special_tokens=False).input_ids
    tag_first = {}
    for t in TAGS:
        ids = tok("[" + t, add_special_tokens=False).input_ids
        assert ids[: len(bracket)] == bracket, (t, ids)
        tag_first[t] = ids[len(bracket)]
    assert len(set(tag_first.values())) == len(TAGS), tag_first

    langs = {s for s in args.langs.split(",") if s}
    rows = []
    for spec in args.catalog:
        qpath, fdir = spec.split("=", 1)
        fdir = Path(fdir)
        manifest = json.loads((fdir / "manifest.json").read_text())
        clips = manifest["clips"] if isinstance(manifest, dict) else manifest
        audio = {}
        for c in clips:
            audio.setdefault(c["id"], fdir / c["file"])
        count = Counter()
        for line in Path(qpath).read_text().splitlines():
            if not line.strip():
                continue
            r = json.loads(line)
            if r["split"] not in args.splits.split(",") or (langs and r["lang"] not in langs) or r["id"] not in audio:
                continue
            key = (r["lang"], r["class"])
            if args.per_class and count[key] >= args.per_class:
                continue
            count[key] += 1
            rows.append((r, audio[r["id"]]))
    print(f"{len(rows)} recordings", flush=True)

    out = args.output.open("x")
    stats = defaultdict(lambda: defaultdict(Counter))
    for name in args.prompts.split(","):
        system = PROMPTS[name] + "\n\n" + CLOCK
        for r, wav in rows:
            started = time.perf_counter()
            messages = [
                {"role": "system", "content": [{"type": "text", "text": system}]},
                {"role": "user", "content": [{"type": "audio", "audio": read_wav(wav)}]},
            ]
            inputs = processor.apply_chat_template(messages, add_generation_prompt=True, tokenize=True,
                                                   return_dict=True, return_tensors="pt",
                                                   enable_thinking=False).to(model.device)
            with torch.no_grad():
                gen = model.generate(**inputs, max_new_tokens=args.max_new_tokens, do_sample=False)
                reply = tok.decode(gen[0, inputs["input_ids"].shape[1]:], skip_special_tokens=True)
                b = torch.tensor([bracket], device=model.device)
                scored = dict(inputs)
                scored["input_ids"] = torch.cat([inputs["input_ids"], b], 1)
                scored["attention_mask"] = torch.cat([inputs["attention_mask"], torch.ones_like(b)], 1)
                logp = torch.log_softmax(model(**scored).logits[0, -1].float(), -1)
            tag_lp = torch.tensor([logp[tag_first[t]].item() for t in TAGS])
            probs = dict(zip(TAGS, [round(p, 4) for p in torch.softmax(tag_lp, 0).tolist()]))
            mass = float(torch.exp(tag_lp).sum())
            greedy = parse_tag(reply)
            want = EXPECTED.get(r["class"])
            rec = {"prompt": name, "id": r["id"], "lang": r["lang"], "class": r["class"], "expected": want,
                   "greedy": greedy, "argmax": max(probs, key=probs.get), "probs": probs, "tag_mass": round(mass, 4),
                   "reply": reply[:200], "ms": round((time.perf_counter() - started) * 1000)}
            out.write(json.dumps(rec, ensure_ascii=False) + "\n")
            out.flush()
            if want:
                stats[name][r["class"]]["n"] += 1
                stats[name][r["class"]]["greedy"] += greedy == want
                stats[name][r["class"]]["argmax"] += rec["argmax"] == want
        print(f"== {name}", flush=True)
        for cls, c in stats[name].items():
            print(f"  {cls:7s} greedy {c['greedy']}/{c['n']}  argmax {c['argmax']}/{c['n']}", flush=True)
    out.close()


if __name__ == "__main__":
    main()

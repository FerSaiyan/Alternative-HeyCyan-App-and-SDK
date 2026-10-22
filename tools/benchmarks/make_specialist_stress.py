#!/usr/bin/env python3
"""Hand-written configuration development challenges; excluded from fitting."""
import argparse
import json
import random
from pathlib import Path

from make_specialist_training import CONFIG_TOOLS, paired

CASES = [
    ("Switch AI wake-word questions to image", "set_ai_route", {"route": "image"}),
    ("Keep the AI wake-word route on voice questions", "set_ai_route", {"route": "voice"}),
    ("Use image questions, not voice questions, for the AI wake word", "set_ai_route", {"route": "image"}),
    ("Don't use image questions; change the AI wake word to voice questions", "set_ai_route", {"route": "voice"}),
    ("Use every speech turn in Gemini Live, not initial image only", "set_live_refresh", {"mode": "every_turn"}),
    ("In Gemini Live keep the first image; don't refresh on every turn", "set_live_refresh", {"mode": "initial_only"}),
    ("Please enable Walking Aid, but don't start it", "enable_plugin", {"plugin": "walking_aid"}),
    ("Not Visual Diary; enable Hands Free Translator", "enable_plugin", {"plugin": "hands_free_translator"}),
    ("Run Visual Diary now; it's already enabled", "start_plugin", {"plugin": "visual_diary"}),
    ("Don't turn Visual Diary off; start it", "start_plugin", {"plugin": "visual_diary"}),
    ("Set Visual Diary to every 20 minutes instead of 15 minutes", "set_diary_interval", {"minutes": 20}),
    ("Set the Visual Diary interval to twenty minutes", "set_diary_interval", {"minutes": 20}),
    ("I'd like Visual Diary to capture every 45 minutes", "set_diary_interval", {"minutes": 45}),
    ("Set the translator target to Spanish, not French", "set_translation_target", {"language": "es"}),
    ("Change the translator's target language to Portuguese", "set_translation_target", {"language": "pt"}),
    ("Don't change the translator target language", None, {}),
    ("Tell me how to enable Walking Aid", None, {}),
    ("What does Visual Diary do?", None, {}),
    ("Set Visual Diary to every 20 seconds", None, {}),
    ("Set Visual Diary to every 500 minutes", None, {}),
    ("Make Visual Diary less frequent", None, {}),
    ("Enable the Quick Notes plugin", None, {}),
    ("Disable Visual Diary", None, {}),
    ("Don't start Live Caption Relay", None, {}),
]


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--out", type=Path, required=True)
    args = p.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    canonical, laya, needle = [], [], []
    for i, (query, name, arguments) in enumerate(CASES):
        rng = random.Random(20261003+i)
        menu = rng.sample([t for t in CONFIG_TOOLS if t["name"] != name], 2 if name else 3)
        if name:
            menu.append(next(t for t in CONFIG_TOOLS if t["name"] == name))
        elif any("Visual Diary" in query and word in query.lower() for word in ("minutes", "seconds", "frequent")):
            menu = [CONFIG_TOOLS[4], CONFIG_TOOLS[2], CONFIG_TOOLS[3]]
        elif "Walking Aid" in query or "Quick Notes" in query:
            menu = [CONFIG_TOOLS[2], CONFIG_TOOLS[3], CONFIG_TOOLS[4]]
        rng.shuffle(menu)
        case = {"caseId": f"config-stress-{i}", "groupId": f"handwritten-challenge-{i}", "split": "development", "source": "handwritten_generated_development_challenge", "specialist": "configuration", "augmentation": "none", "stage": "positive_correction_or_paraphrase" if name else "negative_unsupported_or_ambiguous", "query": query, "tools": menu, "answers": [{"name": name, "arguments": arguments}] if name else []}
        l, n = paired(case)
        canonical.append(case)
        laya.append(l)
        needle.append(n)
    for kind, rows in (("canonical", canonical), ("laya", laya), ("needle", needle)):
        (args.out/f"development.{kind}.jsonl").write_text("".join(json.dumps(r)+"\n" for r in rows))
    (args.out/"README.txt").write_text("24 hand-written development challenges. No fitting, calibration, independent recorded-speech or Tasker claims. All evaluations are open development evidence.\n")
    print("Wrote", len(canonical), "development-only challenges", args.out)


if __name__ == "__main__":
    main()

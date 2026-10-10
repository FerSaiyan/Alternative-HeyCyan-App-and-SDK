#!/usr/bin/env python3
"""Generated state-derived hierarchical choices; source-goal families split first."""
from __future__ import annotations

import argparse
import hashlib
import json
from collections import Counter
from pathlib import Path

from bounded_specialist_contract import VERSION, build_turn, fingerprint, paired_turn, resolve_choice

SPLITS = ("train", "development", "calibration", "sealed")
FAMILY_SPLITS = ["train"]*5 + ["development"]*2 + ["calibration", "sealed"]
MODEL_NAMES = [("qwen3.5-0.8b-q4", "Qwen3.5 0.8B"), ("gemma4-e2b-it-litert", "Gemma 4 E2B"), ("gemma4-e4b-it-litert", "Gemma 4 E4B")]
TEXTS = {
    "model": ["Switch my local model to {value}", "Use {value} for on-device replies", "Change the selected offline AI model to {value}", "I want {value} as my local model", "Choose {value} for local inference", "Make {value} my on-device assistant model", "Instead of the current model, use {value} locally", "Please select {value} for offline answers", "I'd like the device to answer with the {value} local model"],
    "route": ["Set future AI wake-word questions to {value}", "Change my default AI question route to {value}", "Use the {value} route whenever I ask the AI wake word", "Make {value} the persistent wake-word question mode", "Configure the AI wake-word route as {value}", "For later wake-word requests, switch the question mode to {value}", "Don't ask a question now; set future wake-word questions to {value}", "Set the default wake-word assistant to {value} questions", "I'd like subsequent AI wake-word requests handled as {value} questions"],
    "refresh": ["Set Gemini Live to {value}", "Change Gemini Live image refresh to {value}", "Use {value} as the Gemini Live refresh setting", "Configure Gemini Live pictures for {value}", "Make Gemini Live use {value}", "For Gemini Live, the image update policy should be {value}", "Don't start a live session; configure its images to {value}", "Set the Gemini Live picture policy to {value}", "I'd like Gemini Live's image updates set to {value}"],
    "image": ["What is in this image?", "Describe what the camera sees now", "Ask AI about this picture", "Tell me about the current photo", "Analyze this image for me", "Can you explain what I'm seeing in this photo?", "Take an AI look at the image now, leave settings alone", "Explain this photo to me", "What can you tell me about the picture?"],
    "voice": ["Why is the sky blue?", "Explain photosynthesis", "Answer a voice question: what is gravity?", "Tell me how rain forms", "What causes thunder?", "Can you explain how tides work?", "I want an answer now, not a setting change: why do leaves change color?", "What makes a rainbow?", "Explain how the moon affects tides"],
    "stop": ["Stop speaking", "Stop reading that aloud", "Quiet, stop the spoken reply", "Cancel the current speech", "Stop your spoken response", "Please be quiet now", "Don't keep talking; stop speaking", "Pause the spoken answer now", "End the voice response"],
    "live": ["Start Gemini Live", "Begin a live Gemini conversation", "Open a Gemini Live session now", "I want to talk with Gemini Live", "Turn on a Gemini Live conversation", "Let's begin talking in Gemini Live", "Don't change settings; start Gemini Live now", "Launch the live Gemini assistant", "I'd like a live conversation with Gemini"],
    "phone": ["Search YouTube for a video on my phone", "Open Android Settings on my phone", "Use my phone to find a video", "Find a channel in the YouTube app", "Navigate to my phone's Wi-Fi settings", "Can you open YouTube on my phone?", "Don't alter CyanBridge settings; navigate in the phone Settings app", "Go to the Clock app on my phone", "Find the Android display settings on my phone"],
    "cancel": ["Don't change my local model", "Keep all my settings unchanged", "Cancel that settings change", "Leave my model alone", "Don't switch the AI question mode", "No, do not change any settings", "Keep the current model; don't download anything", "I no longer want a settings change", "Leave the configuration as it is"],
    "unsupported": ["Flash new firmware onto my glasses now", "Run a full AI training job on the glasses", "Reprogram the glasses firmware", "Rewrite the glasses camera driver", "Install a replacement glasses operating system", "Can you retrain the camera hardware model on the glasses?", "Don't answer a question; replace the glasses firmware now", "Change the glasses firmware source code", "Rebuild the glasses operating system from source"],
    "ambiguous": ["Change my local model", "Use a different local model", "Switch the offline model", "Pick another model for local answers", "Change the local AI model", "I want another model on this device", "Don't change the voice route; choose a different local model", "Could we use a different offline model?", "I'd like to change the on-device model"],
}


def snapshot(index=0):
    models = [{"id": id, "label": name, "installed": i != 2, "usable": True, "enabled": True, "comingSoon": False, "supported": True, "sourceUrl": "fixture://catalog/"+id, "gatedDownload": False} for i, (id, name) in enumerate(MODEL_NAMES)]
    return {"fresh": True, "revision": "fixture-"+fingerprint(index)[:12], "settingsAvailable": True, "aiSettingsAvailable": True,
            "localModelsAvailable": True, "wakeRouteAvailable": True, "liveSettingsAvailable": True,
            "imageQuestionAvailable": True, "voiceQuestionAvailable": True, "liveAvailable": True,
            "liveRunning": False, "speaking": True, "taskerReady": False, "downloadInFlight": False,
            "hasDownloadToken": False, "selectedModelId": models[0]["id"], "models": models,
            "wakeRoute": "voice", "liveRefresh": "initial_only"}


def gold_route(goal, state):
    """Task semantics separately defined from the candidate-menu implementation."""
    kind, value = goal["kind"], goal.get("value")
    if kind in ("image", "voice", "stop", "live", "phone", "cancel", "unsupported"):
        key = {"image": "ai_image_question", "voice": "ai_voice_question", "stop": "stop_speaking", "live": "start_gemini_live", "phone": "phone_task", "cancel": "no_change", "unsupported": "defer"}[kind]
        if kind == "stop" and state["speaking"] is False:
            key = "no_change"
        if kind == "live" and state["liveRunning"] is True:
            key = "no_change"
        return [key]
    if kind in ("model", "ambiguous"):
        route = ["alter_settings", "ai_settings", "local_models", "active_model"]
        if kind == "ambiguous":
            return route+["clarify"]
        requested = next(m for m in state["models"] if m["id"] == value)
        if state["selectedModelId"] == value and requested["installed"] and requested["usable"]:
            return route+["no_change"]
        if requested["installed"]:
            return route+["use_installed", "model:"+value] if requested["usable"] else route+["clarify"]
        downloadable = (requested["enabled"] and not requested["comingSoon"] and requested["sourceUrl"] and requested["supported"] and not state["downloadInFlight"] and (not requested["gatedDownload"] or state["hasDownloadToken"]))
        return route+["offer_download", "download:"+value] if downloadable else route+["clarify"]
    if kind == "route":
        return ["alter_settings", "ai_settings", "wake_route", "no_change" if value == state["wakeRoute"] else "route:"+value]
    if kind == "refresh":
        return ["alter_settings", "live_settings", "image_refresh", "no_change" if value == state["liveRefresh"] else "refresh:"+value]
    raise ValueError(kind)


def episode(request, state, route, id, group, split):
    path, turns = [], []
    for index, key in enumerate(route):
        turn = build_turn(state, request, path, f"{id}:{index}")
        matches = [name for name, binding in turn["options"].items() if binding["key"] == key]
        recall = bool(matches)
        if not recall:
            matches = [name for name, binding in turn["options"].items() if binding["key"] == "clarify"]
        selected = matches[0]
        bound = resolve_choice(turn, selected, state)
        case = {"caseId": f"{id}:{index}", "episodeId": id, "groupId": group, "split": split, "stage": "/".join(path) or "voice_route", "source": "generated_host_state_hierarchy", "turn": turn, "expectedChoice": selected, "expectedEffect": bound.get("effect"), "requestedBranchRecall": recall}
        turns.append(case)
        if "nextPath" not in bound:
            break
        path = bound["nextPath"]
    if not turns[-1]["expectedEffect"]:
        raise ValueError("Incomplete gold path")
    return {"contract": VERSION, "episodeId": id, "groupId": group, "split": split, "request": request, "snapshot": state,
            "expectedEffect": turns[-1]["expectedEffect"], "goldKeys": [r["turn"]["options"][r["expectedChoice"]]["key"] for r in turns]}, turns


def generate():
    goals = ([{"kind": "model", "value": id, "textValue": name} for id, name in MODEL_NAMES]
             + [{"kind": "route", "value": value, "textValue": value} for value in ("voice", "image")]
             + [{"kind": "refresh", "value": value, "textValue": phrase} for value, phrase in (("initial_only", "initial image only"), ("every_turn", "refresh on every significant speech turn"))]
             + [{"kind": kind} for kind in ("image", "voice", "stop", "live", "phone", "cancel", "unsupported", "ambiguous")])
    episodes, turns = [], []
    for goal in goals:
        kind = goal["kind"]
        for family, split in enumerate(FAMILY_SPLITS):
            # Shared phrase families stay together across goals, state changes
            # and augmentation. No random per-turn split or ASR provenance claim.
            group = f"written-family-{family}"
            request = TEXTS[kind][family].format(value=goal.get("textValue"))
            for variant in range(4):
                state = snapshot(len(episodes))
                if kind == "model":
                    target = next(m for m in state["models"] if m["id"] == goal["value"])
                    target.update(installed=variant in (0, 2), usable=True)
                    state["selectedModelId"] = goal["value"] if variant == 2 else next(m["id"] for m in state["models"] if m["id"] != goal["value"])
                    next(m for m in state["models"] if m["id"] == state["selectedModelId"])["installed"] = True
                    if variant == 3:
                        target["supported"] = False
                elif kind == "route":
                    state["wakeRoute"] = goal["value"] if variant % 2 else ("voice" if goal["value"] == "image" else "image")
                elif kind == "refresh":
                    state["liveRefresh"] = goal["value"] if variant % 2 else ("initial_only" if goal["value"] == "every_turn" else "every_turn")
                elif kind == "stop":
                    state["speaking"] = variant % 2 == 0
                elif kind == "image":
                    state["imageQuestionAvailable"] = variant != 3
                elif kind == "ambiguous" and variant == 3:
                    state["localModelsAvailable"] = False
                state["taskerReady"] = variant == 1
                state["liveAvailable"] = variant != 2
                if kind == "phone":
                    state["taskerReady"] = variant != 3
                if kind == "live":
                    state["liveRunning"] = variant == 1
                id = f"{kind}-{goal.get('value','none')}-{family}-{variant}"
                ep, rows = episode(request, state, gold_route(goal, state), id, group, split)
                episodes.append(ep)
                turns.extend(rows)
    return episodes, turns


def write_records(out, episodes, turns, view="hierarchical"):
    if out.exists():
        raise ValueError("Refuse to overwrite a bounded dataset")
    out.mkdir(parents=True)
    manifest = {"contract": VERSION, "generated": True, "finalTestOpened": False, "files": {},
                "view": view,
                "sources": ["LocalModelCatalog.kt", "LocalModelsConfigureActivity.canDownload", "LocalModelsPresentation.kt", "LocalModelStorageRepository.kt"],
                "limits": "Written synthetic families, shared concepts/catalog; not real ASR, actual downloads, Tasker observations or deployment validation."}
    for split in SPLITS:
        rows = [r for r in turns if r["split"] == split]
        files = {f"{split}.canonical.jsonl": rows, f"{split}.episodes.jsonl": [ep for ep in episodes if ep["split"] == split],
                 f"{split}.laya.jsonl": [paired_turn(r)[0] for r in rows], f"{split}.needle.jsonl": [paired_turn(r)[1] for r in rows]}
        for name, items in files.items():
            content = "".join(json.dumps(r, ensure_ascii=False)+"\n" for r in items)
            (out/name).write_text(content)
            manifest["files"][name] = {"rows": len(items), "sha256": hashlib.sha256(content.encode()).hexdigest()}
    manifest["counts"] = dict(Counter(r["split"] for r in turns))
    manifest["episodes"] = dict(Counter(r["split"] for r in episodes))
    manifest["maxOptions"] = max(len(r["turn"]["options"]) for r in turns)
    (out/"manifest.json").write_text(json.dumps(manifest, indent=2)+"\n")
    print(json.dumps(manifest, indent=2))


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--out", type=Path, required=True)
    args = p.parse_args()
    write_records(args.out, *generate())


if __name__ == "__main__":
    main()

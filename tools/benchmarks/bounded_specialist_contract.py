"""Offline reference contract: host state -> legal choices -> one bound transition.

No Android side effects. Both models receive the SAME short status/request and
choose one option. Leaf payloads, including model IDs/enum values, are host-owned.
"""
from __future__ import annotations

import copy
import hashlib
import json
import random

VERSION = "bounded-specialist-v3"
CONTROLS = {
    "clarify": "Ask the user: missing, ambiguous or unavailable requested choice",
    "no_change": "No change requested, cancelled, or current value already matches",
    "defer": "Hand off: this request cannot be resolved by the offered choices",
}
INSTRUCTIONS = "Choose exactly one offered option for this request and current status. Narrow scope until a valid value/action is offered. Never invent an option. Clarify ambiguous/unavailable requests; no_change on cancellation or an already matching value."


def fingerprint(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def option(key, label, next_path=None, effect=None):
    result = {"key": key, "label": label}
    if next_path is not None:
        result["nextPath"] = next_path
    if effect is not None:
        result["effect"] = effect
    return result


def eligible_catalog(snapshot):
    """Use typed host assessment, not a classifier's guess about compatibility.

    Mirrors LocalModelsConfigureActivity.canDownload plus observed freshness.
    installed/usable and supported require explicit booleans, never defaults.
    """
    installed = {m["id"] for m in snapshot["models"] if m.get("installed") is True}
    return [m for m in snapshot["models"] if m["id"] not in installed
            and m.get("enabled") is True and m.get("comingSoon") is False
            and bool(m.get("sourceUrl")) and m.get("supported") is True
            and snapshot.get("downloadInFlight") is False
            and (m.get("gatedDownload") is False or snapshot.get("hasDownloadToken") is True)]


def validate_snapshot(snapshot):
    if snapshot.get("fresh") is not True or not isinstance(snapshot.get("revision"), str) or not snapshot["revision"]:
        raise ValueError("Fresh, versioned host state is required")
    ids = [m["id"] for m in snapshot["models"]]
    if any(not isinstance(id, str) or not id for id in ids) or len(ids) != len(set(ids)):
        raise ValueError("Duplicate model binding IDs")
    if snapshot["wakeRoute"] not in ("voice", "image") or snapshot["liveRefresh"] not in ("initial_only", "every_turn"):
        raise ValueError("Unknown current semantic setting")


def choices(snapshot, path):
    """Only state-eligible options at the chosen hierarchy level."""
    if path == []:
        result = [option("alter_settings", "Change a persistent setting", ["settings"])] if snapshot.get("settingsAvailable") is True else []
        if snapshot.get("imageQuestionAvailable") is True:
            result.append(option("ai_image_question", "Ask AI about an image now", effect={"kind": "invoke_image_question"}))
        if snapshot.get("voiceQuestionAvailable") is True:
            result.append(option("ai_voice_question", "Ask AI a voice question now", effect={"kind": "invoke_voice_question"}))
        if snapshot.get("liveAvailable") is True and snapshot.get("liveRunning") is False:
            result.append(option("start_gemini_live", "Start a Gemini Live conversation now", effect={"kind": "start_live"}))
        if snapshot.get("speaking") is True:
            result.append(option("stop_speaking", "Stop the current spoken response", effect={"kind": "stop_speaking"}))
        if snapshot.get("taskerReady") is True:
            result.append(option("phone_task", "Delegate a phone UI task to the phone planner", effect={"kind": "handoff_phone_planner"}))
        return result
    if path == ["settings"]:
        result = []
        if snapshot.get("aiSettingsAvailable") is True:
            result.append(option("ai_settings", "AI settings: local model and wake-word route", path+["ai"]))
        if snapshot.get("liveSettingsAvailable") is True:
            result.append(option("live_settings", "Gemini Live image refresh settings", path+["live"]))
        return result
    if path == ["settings", "ai"]:
        result = []
        if snapshot.get("localModelsAvailable") is True:
            result.append(option("local_models", "On-device local model selection/download", path+["local_models"]))
        if snapshot.get("wakeRouteAvailable") is True:
            result.append(option("wake_route", "Persistent AI wake-word voice/image question route", path+["wake_route"]))
        return result
    if path == ["settings", "ai", "local_models"]:
        return [option("active_model", "Change the active local model", path+["active_model"])]
    if path == ["settings", "ai", "local_models", "active_model"]:
        result = []
        if any(m.get("installed") is True and m.get("usable") is True and m["id"] != snapshot["selectedModelId"] for m in snapshot["models"]):
            result.append(option("use_installed", "Select an installed usable local model", path+["installed"]))
        if eligible_catalog(snapshot):
            result.append(option("offer_download", "Offer the user a compatible missing model download; do not select it yet", path+["download"]))
        return result
    if path == ["settings", "ai", "local_models", "active_model", "installed"]:
        return [option("model:"+m["id"], "Use installed "+m["label"], effect={"kind": "set_local_model", "modelId": m["id"]}) for m in snapshot["models"] if m.get("installed") is True and m.get("usable") is True and m["id"] != snapshot["selectedModelId"]]
    if path == ["settings", "ai", "local_models", "active_model", "download"]:
        return [option("download:"+m["id"], "Offer download of "+m["label"], effect={"kind": "offer_model_download", "modelId": m["id"], "selectAfterDownload": False}) for m in eligible_catalog(snapshot)]
    if path == ["settings", "ai", "wake_route"]:
        return [option("route:"+value, "Set future AI wake-word questions to "+value, effect={"kind": "set_wake_route", "route": value}) for value in ("voice", "image") if value != snapshot["wakeRoute"]]
    if path == ["settings", "live"]:
        return [option("image_refresh", "Change Gemini Live image refresh mode", path+["image_refresh"])]
    if path == ["settings", "live", "image_refresh"]:
        return [option("refresh:"+mode, label, effect={"kind": "set_live_refresh", "mode": mode, "wireValue": wire}) for mode, label, wire in (("initial_only", "Use the initial image only", 0), ("every_turn", "Refresh each significant speech turn", 2)) if mode != snapshot["liveRefresh"]]
    raise ValueError(f"Unknown hierarchy path {path}")


def validate_path(snapshot, path):
    for index in range(len(path)):
        parent, child = path[:index], path[:index+1]
        if not any(bound.get("nextPath") == child for bound in choices(snapshot, parent)):
            raise ValueError("Scope is not reachable through the current valid branches")


def build_turn(snapshot, request, path, turn_id, shuffle=True, version=VERSION):
    validate_snapshot(snapshot)
    validate_path(snapshot, path)
    items = choices(snapshot, path) + [option(k, label, effect={"kind": k}) for k, label in CONTROLS.items()]
    if shuffle:
        random.Random(int(fingerprint(turn_id)[:8], 16)).shuffle(items)
    # Fresh bound IDs carry no answer semantics, target argument or position cue.
    options = {f"option_{i}": item for i, item in enumerate(items)}
    status = {"phase": "voice_route" if not path else "settings_select", "scope": path,
              "revision": snapshot["revision"], "priorSelections": path,
              "selectedModelId": snapshot["selectedModelId"], "wakeRoute": snapshot["wakeRoute"],
              "liveRefresh": snapshot["liveRefresh"], "speaking": snapshot["speaking"],
              "models": [{"name": m["label"], "id": m["id"], "status": "installed_usable" if m.get("installed") is True and m.get("usable") is True else "installed_unusable" if m.get("installed") is True else "download_eligible" if m in eligible_catalog(snapshot) else "unavailable"} for m in snapshot["models"]]}
    if version == VERSION:
        status.update(liveRunning=snapshot.get("liveRunning"), liveAvailable=snapshot.get("liveAvailable"),
                      taskerReady=snapshot.get("taskerReady"), downloadInFlight=snapshot.get("downloadInFlight"))
    elif version != "bounded-specialist-v2":
        raise ValueError("Unknown contract version")
    query = json.dumps({"contract": version, "status": status, "request": request}, separators=(",", ":"), ensure_ascii=False)
    return {"contract": version, "turnId": turn_id, "path": path, "snapshotHash": fingerprint(snapshot), "options": options, "query": query}


def resolve_choice(turn, selected, snapshot):
    validate_snapshot(snapshot)
    if snapshot.get("fresh") is not True or fingerprint(snapshot) != turn["snapshotHash"]:
        raise ValueError("State changed: rebuild the menu before resolving a choice")
    validate_path(snapshot, turn["path"])
    if not isinstance(selected, str) or selected not in turn["options"]:
        raise ValueError("Choice not offered on this turn")
    bound = turn["options"][selected]
    # Recompute legality so a fabricated binding cannot grant a missing action.
    legal = choices(snapshot, turn["path"]) + [option(k, label, effect={"kind": k}) for k, label in CONTROLS.items()]
    if bound not in legal:
        raise ValueError("Invalid host binding")
    return copy.deepcopy(bound)


def decode_choice(case, calls):
    """A valid Needle response selects exactly one of this turn's declared tools."""
    if not isinstance(calls, list) or len(calls) != 1 or not isinstance(calls[0], dict):
        return None
    call = calls[0]
    if set(call) != {"name", "arguments"} or not isinstance(call["name"], str) or call["arguments"] != {}:
        return None
    return call["name"] if call["name"] in case["turn"]["options"] else None


def paired_turn(case):
    turn = case["turn"]
    gold = case["expectedChoice"]
    metadata = {k: case[k] for k in ("caseId", "groupId", "episodeId", "split", "stage", "source")}
    common = dict(metadata, specialist="bounded_choice", augmentation="synthetic_host_state", turn=turn,
                  expectedChoice=gold, expectedEffect=case.get("expectedEffect"))
    expected = [{"name": gold, "arguments": {}}]
    criteria = {key: value["label"] for key, value in turn["options"].items()}
    laya = dict(common, state=turn["query"], questions={"next_action": {"type": "choice", "instructions": INSTRUCTIONS, "criteria": criteria}}, expected={"next_action": gold}, expectedCalls=expected, tags=[case["stage"]])
    # Each zero-argument tool is a choice, NEVER the actual setting dispatcher.
    # Its payload/IDs are bound by the host after membership and freshness checks.
    needle = dict(common, query=turn["query"], system=INSTRUCTIONS,
                  tools=[{"name": key, "description": value["label"], "parameters": {"type": "object", "properties": {}, "required": []}} for key, value in turn["options"].items()], answers=expected)
    return laya, needle


def choice_metrics(results):
    """Clarify/no-change/defer are explicit choices, not emitted-action successes."""
    stages = {}
    for row in results:
        bucket = stages.setdefault(row["stage"], {"cases": 0, "correct": 0})
        bucket["cases"] += 1
        bucket["correct"] += row["selected"] == row["expectedChoice"]
    substantive = [r for r in results if r["expectedKey"] not in CONTROLS]
    controls = [r for r in results if r["expectedKey"] in CONTROLS]
    false = sum(r["selectedKey"] is not None and r["selectedKey"] not in CONTROLS for r in controls)
    return {"cases": len(results), "correct": sum(r["selected"] == r["expectedChoice"] for r in results),
            "choiceAccuracy": sum(r["selected"] == r["expectedChoice"] for r in results) / max(1, len(results)),
            "substantiveChoiceAccuracy": sum(r["selected"] == r["expectedChoice"] for r in substantive) / max(1, len(substantive)),
            "controlCases": len(controls), "controlFalseActionRate": false / max(1, len(controls)),
            "invalidChoices": sum(r["selected"] is None for r in results), "stages": stages}


def choice_result(case, selected):
    items = case["turn"]["options"]
    return {"caseId": case["caseId"], "episodeId": case["episodeId"], "stage": case["stage"],
            "expectedChoice": case["expectedChoice"], "expectedKey": items[case["expectedChoice"]]["key"],
            "selected": selected, "selectedKey": items[selected]["key"] if selected in items else None}


def laya_selected_calls(case, selected):
    if case.get("specialist") == "bounded_choice":
        return [{"name": selected, "arguments": {}}] if selected in case["turn"]["options"] else None
    from make_specialist_training import bind_config
    if selected == "DEFER":
        return []
    arguments = bind_config(case["state"], selected) if case["specialist"] == "configuration" else {}
    return [{"name": selected, "arguments": arguments}] if arguments is not None else None


def phone_choices(candidates, observation_revision, fresh):
    """Reference UI menu adapter: explicit host eligibility/fresh target binding.

    Caller derives eligibility from real evidence; absent flags cannot prove it.
    This does not reinterpret the older Tasker false/zero placeholder fields.
    """
    if fresh is not True or not isinstance(observation_revision, str) or not observation_revision:
        return []
    eligible = [copy.deepcopy(c) for c in candidates if c.get("eligible") is True
                and isinstance(c.get("id"), str) and bool(c["id"])
                and c.get("observationRevision") == observation_revision
                and c.get("bindingVerified") is True]
    if len({c["id"] for c in eligible}) != len(eligible):
        raise ValueError("Duplicate phone binding IDs")
    return eligible


def rollout_episode(ep, predict, flat=False, max_turns=8):
    """Route solely on the model's own choices; gold is read only for scoring."""
    path, effect, trace = [], None, []
    for index in range(max_turns):
        id = f"{ep['episodeId']}:{index}"
        if flat:
            from make_bounded_specialist_flat import flat_turn, resolve_flat_choice
            turn = flat_turn(ep["snapshot"], ep["request"], id, version=ep.get("contract",VERSION))
            resolver = resolve_flat_choice
        else:
            turn = build_turn(ep["snapshot"], ep["request"], path, id, version=ep.get("contract",VERSION))
            resolver = resolve_choice
        selected, raw = predict(turn)
        trace.append({"path": path, "selected": selected, "selectedKey": turn["options"][selected]["key"] if selected in turn["options"] else None, "raw": raw})
        if selected not in turn["options"]:
            break
        bound = resolver(turn, selected, ep["snapshot"])
        if "effect" in bound:
            effect = bound["effect"]
            break
        path = bound["nextPath"]
    return {"episodeId": ep["episodeId"], "expectedEffect": ep["expectedEffect"], "proposedEffect": effect,
            "correct": fingerprint(effect) == fingerprint(ep["expectedEffect"]), "trace": trace}

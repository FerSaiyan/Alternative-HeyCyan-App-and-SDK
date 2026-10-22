#!/usr/bin/env python3
"""Build grouped, paired voice/configuration pilot records from pinned sources.

Public route labels discard source arguments: this is capability selection, not
Android execution. Product examples and all counterfactuals are generated. No
Tasker observations, inspected benchmark rows, or official evaluation rows fit.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import random
import re
from collections import Counter, defaultdict
from pathlib import Path

SEED = 20261003
SPLITS = ("train", "development", "calibration", "sealed")
EQUIVALENT_CAPABILITIES = (
    {"open_wifi_settings", "open_settings"},
    {"show_map", "search_location"},
    {"create_contact", "ACTION_INSERT_CONTACT"},
    {"create_calendar_event", "ACTION_INSERT_EVENT"},
    {"ACTION_IMAGE_CAPTURE", "INTENT_ACTION_STILL_IMAGE_CAMERA"},
    {"ACTION_VIDEO_CAPTURE", "INTENT_ACTION_VIDEO_CAMERA"},
    {"ACTION_PICK", "ACTION_GET_CONTENT", "ACTION_OPEN_DOCUMENT", "ACTION_CREATE_DOCUMENT"},
    {"get_contact_info", "get_contact_info_from_uri", "ACTION_VIEW_CONTACT"},
    {"ACTION_SET_ALARM", "ACTION_SHOW_ALARMS"},
)


def equivalent_names(name):
    return next((family for family in EQUIVALENT_CAPABILITIES if name in family), {name})


def digest(value):
    return hashlib.sha256(value.encode()).hexdigest()


def partition(group):
    n = int(digest(group)[:8], 16) % 100
    return "train" if n < 70 else "development" if n < 82 else "calibration" if n < 90 else "sealed"


def values(obj):
    if isinstance(obj, dict):
        return [v for x in obj.values() for v in values(x)]
    if isinstance(obj, list):
        return [v for x in obj for v in values(x)]
    return [str(obj)] if isinstance(obj, (str, int, float)) else []


def goal_family(query, arguments):
    text = query.lower()
    for value in sorted(values(arguments), key=len, reverse=True):
        if len(value) >= 3:
            text = text.replace(value.lower(), " VALUE ")
    text = re.sub(r"\b\d+\b", "VALUE", text)
    return " ".join(re.findall(r"[a-z]+", text))


def source_routes(raw):
    records, exclusions, descriptions = [], Counter(), {}
    for source, filename in (("mobile_actions", "mobile_actions.jsonl"), ("droidcall", "droidcall_train.jsonl")):
        for index, line in enumerate((raw / filename).read_text().splitlines()):
            row = json.loads(line)
            if source == "mobile_actions":
                if row["metadata"] != "train":
                    exclusions["official_mobile_eval"] += 1
                    continue
                calls = [c["function"] for m in row["messages"] if m["role"] == "assistant" for c in m.get("tool_calls", [])]
                query = "\n".join(m["content"] for m in row["messages"] if m["role"] == "user")
                tools = [t["function"] for t in row["tools"]]
            else:
                calls, query, tools = row["answers"], row["query"], row["tools"]
            if len(calls) != 1:
                exclusions[f"{source}_multi_or_zero_call"] += 1
                continue
            call = calls[0]
            declared = {t["name"]: t for t in tools}
            if call["name"] not in declared:
                exclusions[f"{source}_undeclared_answer"] += 1
                continue
            arguments = call.get("arguments", {})
            if isinstance(arguments, str):
                try:
                    arguments = json.loads(arguments)
                except json.JSONDecodeError:
                    exclusions[f"{source}_invalid_arguments"] += 1
                    continue
            if any("$" in v or "result" in v.lower() for v in values(arguments)):
                exclusions[f"{source}_result_reference"] += 1
                continue
            # Only operation names are supervised. Arguments are retained for
            # delexicalized grouping, never presented as a generated gold span.
            name = call["name"]
            description = declared[name].get("description", name)
            descriptions.setdefault(name, " ".join(description.split())[:110])
            records.append({"source": source, "sourceRow": index, "query": query,
                            "operation": name, "family": goal_family(query, arguments)})
    # Exact delexicalized templates and high-overlap lexical goal families are
    # joined BEFORE sampling/splitting. This is a bounded pilot grouping audit,
    # not proof that all semantic paraphrases have been found.
    parent = list(range(len(records)))

    def find(i):
        while parent[i] != i:
            parent[i] = parent[parent[i]]
            i = parent[i]
        return i

    def union(i, j):
        a, b = find(i), find(j)
        parent[max(a, b)] = min(a, b)

    exact, raw_exact, buckets = {}, {}, defaultdict(list)
    features = []
    for i, row in enumerate(records):
        raw_key = " ".join(re.findall(r"[a-z0-9]+", row["query"].lower()))
        if raw_key in raw_exact:
            union(i, raw_exact[raw_key])
        else:
            raw_exact[raw_key] = i
        words = row["family"].split()
        grams = set(" ".join(words[j:j+3]) for j in range(max(1, len(words)-2)))
        features.append(grams)
        if row["family"] in exact:
            union(i, exact[row["family"]])
        else:
            exact[row["family"]] = i
        # Minhash candidate generation followed by actual Jaccard verification.
        for salt in range(4):
            key = (salt, min(digest(f"{salt}:{g}")[:12] for g in grams))
            for j in buckets[key][-200:]:
                other = features[j]
                if len(grams & other) / len(grams | other) >= 0.80:
                    union(i, j)
            buckets[key].append(i)
    group_names = {}
    for i, row in enumerate(records):
        root = find(i)
        group_names.setdefault(root, "public-" + digest(records[root]["family"])[:20])
        row["groupId"] = group_names[root]
        row["split"] = partition(row["groupId"])
    return records, descriptions, exclusions


def tool(name, description, properties=None, required=None):
    return {"name": name, "description": description,
            "parameters": {"type": "object", "properties": properties or {}, "required": required or []}}


CONFIG_TOOLS = [
    tool("set_ai_route", "Set AI wake-word question route", {"route": {"type": "string", "enum": ["voice", "image"]}}, ["route"]),
    tool("set_live_refresh", "Set Gemini Live image refresh mode", {"mode": {"type": "string", "enum": ["initial_only", "every_turn"]}}, ["mode"]),
    tool("enable_plugin", "Enable a native plugin without starting it", {"plugin": {"type": "string", "enum": ["visual_diary", "live_caption_relay", "walking_aid", "hands_free_translator"]}}, ["plugin"]),
    tool("start_plugin", "Start an enabled native plugin now", {"plugin": {"type": "string", "enum": ["visual_diary", "live_caption_relay", "walking_aid", "hands_free_translator"]}}, ["plugin"]),
    tool("set_diary_interval", "Set Visual Diary interval in minutes, 1 to 240", {"minutes": {"type": "integer", "minimum": 1, "maximum": 240}}, ["minutes"]),
    tool("set_translation_target", "Set translator target language", {"language": {"type": "string", "enum": ["en", "es", "pt", "fr"]}}, ["language"]),
]
PLUGINS = {"visual_diary": "Visual Diary", "live_caption_relay": "Live Caption Relay", "walking_aid": "Walking Aid", "hands_free_translator": "Hands Free Translator"}
LANGUAGES = {"en": "English", "es": "Spanish", "pt": "Portuguese", "fr": "French"}


def bind_config(query, name):
    """Independent host value extraction; does not read training labels."""
    q = query.lower()
    if name == "set_diary_interval":
        matches = re.findall(r"\b(\d+)\s*(?:minutes?|mins?)\b", q)
        return {"minutes": int(matches[0])} if len(matches) == 1 and 1 <= int(matches[0]) <= 240 else None
    if name in ("enable_plugin", "start_plugin"):
        matches = [k for k, v in PLUGINS.items() if v.lower() in q]
        return {"plugin": matches[-1]} if len(matches) == 1 else None
    if name == "set_translation_target":
        matches = [k for k, v in LANGUAGES.items() if v.lower() in q]
        return {"language": matches[0]} if len(matches) == 1 else None
    if name == "set_ai_route":
        matches = [v for v in ("image", "voice") if re.search(rf"\b{v}\b", q)]
        return {"route": matches[0]} if len(matches) == 1 else None
    if name == "set_live_refresh":
        if "initial" in q or "first image" in q:
            return {"mode": "initial_only"}
        if "every" in q or "each" in q:
            return {"mode": "every_turn"}
    return None


def paired(case):
    def compact_schema(t):
        fields = []
        for key, field in t["parameters"]["properties"].items():
            options = "|".join(field["enum"]) if "enum" in field else f"{field.get('minimum', '')}..{field.get('maximum', '')}"
            fields.append(f"{key}={options}")
        return "; ".join(fields)
    criteria = {t["name"]: t["description"] + ("; " + compact_schema(t) if case["specialist"] == "configuration" else "") for t in case["tools"]}
    criteria["DEFER"] = "No supported requested change: unavailable, negated, ambiguous, invalid or conversation"
    pairs = list(criteria.items())
    random.Random(int(digest(case["caseId"])[:8], 16)).shuffle(pairs)
    label = case["answers"][0]["name"] if case["answers"] else "DEFER"
    common = {k: case[k] for k in ("caseId", "groupId", "source", "split", "stage", "specialist", "augmentation")}
    laya = dict(common, state=case["query"], questions={"next_action": {"type": "choice", "instructions": "Select the requested available operation; defer on negation, invalid values or unavailable operations.", "criteria": dict(pairs)}}, expected={"next_action": label}, tags=[case["stage"]], expectedCalls=case["answers"])
    needle = dict(common, query=case["query"], system="", tools=case["tools"], answers=case["answers"])
    return laya, needle


def build_public(records, descriptions, limit):
    selected = []
    # Round-robin balancing only within preassigned training/development cohorts.
    for split in SPLITS:
        by_class = defaultdict(list)
        for row in records:
            if row["split"] == split:
                by_class[(row["source"], row["operation"])].append(row)
        for key, rows in by_class.items():
            random.Random(SEED + int(digest(str(key))[:8], 16)).shuffle(rows)
        cap = limit if split == "train" else 160
        while by_class and len([r for r in selected if r["split"] == split]) < cap:
            for key in sorted(list(by_class)):
                selected.append(by_class[key].pop())
                if not by_class[key]:
                    del by_class[key]
                if len([r for r in selected if r["split"] == split]) >= cap:
                    break
    names, output = sorted(descriptions), []
    for row in selected:
        case_id = f"{row['source']}-{row['sourceRow']}"
        rng = random.Random(int(digest(case_id)[:8], 16))
        gold = row["operation"]
        # Equivalent/wider sibling capabilities would invalidate an unavailable
        # label and make positive menus have multiple defensible source answers.
        alternatives = [n for n in names if n not in equivalent_names(gold)]
        menu = rng.sample(alternatives, 2) + [gold]
        rng.shuffle(menu)
        common = {"caseId": case_id, "groupId": row["groupId"], "split": row["split"], "source": row["source"], "specialist": "voice_capability", "augmentation": "none", "stage": "public_positive"}
        schema = [tool(n, descriptions[n]) for n in menu]
        output.append(dict(common, query=row["query"], tools=schema, answers=[{"name": gold, "arguments": {}}]))
        # Both counterfactuals stay in their ORIGINAL source-goal cohort.
        missing = [tool(n, descriptions[n]) for n in rng.sample(alternatives, 3)]
        output.append(dict(common, caseId=case_id+"-unavailable", augmentation="menu_counterfactual", stage="unavailable", query=row["query"], tools=missing, answers=[]))
        output.append(dict(common, caseId=case_id+"-negated", augmentation="synthetic_negation", stage="negated", query="Do not carry out this request: " + row["query"], tools=schema, answers=[]))
    return output


def build_config():
    # Phrasing families, not shuffled rows, define the synthetic cohorts.
    wrappers = [("train", "{}"), ("train", "Please {}"), ("train", "I want you to {}"), ("train", "Could you {}"),
                ("development", "Go ahead and {}"), ("development", "Would you {} for me?"),
                ("calibration", "I'd like you to {}"), ("sealed", "Can you help me {}?")]
    clauses = {
        "set_ai_route": [("use {route} questions for the AI wake word", {"route": route}) for route in ("image", "voice")],
        "set_live_refresh": [("set Gemini Live to {phrase}", {"mode": mode}) for mode, phrase in (("initial_only", "initial image only"), ("every_turn", "refresh on every speech turn"))],
        "enable_plugin": [("enable {display} without starting it", {"plugin": p}) for p, display in PLUGINS.items()],
        "start_plugin": [("start {display} now", {"plugin": p}) for p, display in PLUGINS.items()],
        "set_diary_interval": [("set Visual Diary to every {minutes} minutes", {"minutes": v}) for v in (2, 3, 5, 7, 10, 12, 15, 18, 20, 25, 30, 45, 60, 90, 120, 180, 240)],
        "set_translation_target": [("set the translator target language to {display}", {"language": language}) for language, display in LANGUAGES.items()],
    }
    rows = []
    for family, (split, wrapper) in enumerate(wrappers):
        # Wrapper alone is a weak language holdout. Also use operation-specific
        # alternate clauses on development/calibration/sealed to expose transfer.
        alternates = {"set_ai_route": "change the AI wake-word route to {route} questions", "set_live_refresh": "use {phrase} in Gemini Live", "enable_plugin": "turn on the {display} plugin", "start_plugin": "run {display} immediately", "set_diary_interval": "change the Visual Diary interval to {minutes} minutes", "set_translation_target": "translate into {display}"}
        for name, cases in clauses.items():
            for value_index, (clause, arguments) in enumerate(cases):
                substitutions = dict(arguments)
                if "plugin" in arguments:
                    substitutions["display"] = PLUGINS[arguments["plugin"]]
                if "language" in arguments:
                    substitutions["display"] = LANGUAGES[arguments["language"]]
                if "mode" in arguments:
                    substitutions["phrase"] = "initial image only" if arguments["mode"] == "initial_only" else "refresh on every speech turn"
                query = wrapper.format((clause if split == "train" else alternates[name]).format(**substitutions))
                group = f"product-phrasing-{family}"
                case_id = f"config-{family}-{name}-{value_index}"
                rng = random.Random(int(digest(case_id)[:8], 16))
                menu = rng.sample([t for t in CONFIG_TOOLS if t["name"] != name], 2) + [next(t for t in CONFIG_TOOLS if t["name"] == name)]
                rng.shuffle(menu)
                common = {"caseId": case_id, "groupId": group, "split": split, "source": "generated_product_contract", "specialist": "configuration", "augmentation": "synthetic_product", "stage": name}
                assert bind_config(query, name) == arguments, (query, arguments)
                rows.append(dict(common, query=query, tools=menu, answers=[{"name": name, "arguments": arguments}]))
                for variant, q, tools in (("negated", "Do not " + query, menu), ("unavailable", query, [t for t in CONFIG_TOOLS if t["name"] != name][:3])):
                    rows.append(dict(common, caseId=case_id+"-"+variant, augmentation="synthetic_product_"+variant, stage=variant, query=q, tools=tools, answers=[]))
        for i, query in enumerate(("What's the weather today?", "Don't change any settings", "Set Visual Diary to every 500 minutes", "Set Visual Diary to every 0 minutes", "Enable the imaginary Star Tracker plugin", "Make the diary less frequent")):
            rows.append({"caseId": f"config-{family}-reject-{i}", "groupId": f"product-phrasing-{family}", "split": split, "source": "generated_product_contract", "specialist": "configuration", "augmentation": "synthetic_product_negative", "stage": "invalid_or_ambiguous", "query": wrapper.format(query), "tools": CONFIG_TOOLS[:3] if i == 4 else CONFIG_TOOLS[3:], "answers": []})
    return rows


def write_dataset(directory, cases):
    directory.mkdir(parents=True, exist_ok=True)
    manifest = {"seed": SEED, "observer": "none_offline_language_only", "finalTestOpened": False, "files": {}, "groups": {}, "stages": {}}
    groups = {}
    for split in SPLITS:
        rows = [r for r in cases if r["split"] == split]
        manifest["groups"][split] = sorted(set(r["groupId"] for r in rows))
        manifest["stages"][split] = dict(Counter(r["stage"] for r in rows))
        for row in rows:
            assert groups.setdefault(row["groupId"], split) == split
        for model in ("canonical", "laya", "needle"):
            path = directory / f"{split}.{model}.jsonl"
            content = "".join(json.dumps(r if model == "canonical" else paired(r)[model == "needle"], ensure_ascii=False) + "\n" for r in rows)
            path.write_text(content)
            manifest["files"][path.name] = {"rows": len(rows), "sha256": digest(content)}
    manifest["limits"] = "Public operation-only labels; generated counterfactuals/product templates; not recorded speech, Tasker data, deployment precision or unseen app evaluation. Near-duplicate grouping is approximate."
    (directory / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--raw", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--train-limit", type=int, default=1200)
    args = parser.parse_args()
    records, descriptions, exclusions = source_routes(args.raw)
    public = build_public(records, descriptions, args.train_limit)
    positive = [r for r in public if r["split"] != "train" or r["augmentation"] == "none"]
    write_dataset(args.out / "voice_positive", positive)
    write_dataset(args.out / "voice_with_negatives", public)
    config = build_config()
    write_dataset(args.out / "config_positive", [r for r in config if r["split"] != "train" or r["answers"]])
    write_dataset(args.out / "config_with_negatives", config)
    audit = {"sourceExclusions": dict(exclusions), "originalPublicRows": len(records), "publicGroups": len(set(r["groupId"] for r in records)), "sourceGoalsSplitBeforeAugmentation": True, "officialEvalExcluded": True, "datasetCounts": {"voice_with_negatives": dict(Counter(r["split"] for r in public)), "config_with_negatives": dict(Counter(r["split"] for r in config))}}
    (args.out / "audit.json").write_text(json.dumps(audit, indent=2) + "\n")
    print(json.dumps(audit, indent=2))


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Collect verified exact-literal YouTube field-entry tasks on isolated Artemis AVD.

These tasks STOP before search submission. A RETRY/network-error result must
never be mislabeled as a completed search, much less verified video playback.
"""

from __future__ import annotations

import argparse
import asyncio
import hashlib
import json
import re
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

from collect_artemis_settings import clickable_controls, choose, merge_verified_rows, preflight


PACKAGE = "com.google.android.youtube"
QUERIES = (
    "open source hardware", "beginner guitar chords", "astronomy sky map",
    "bluetooth audio latency", "python data analysis", "electric bicycle repair",
    "cloud formations", "ceramic glazing tutorial", "ancient maps collection",
    "robotics sensor calibration",
)


def focused_field(xml: str, controls: list[dict]) -> dict | None:
    fields = [node for node in ET.fromstring(xml).iter("node")
              if (node.get("class") or "").endswith("EditText") and node.get("focused") == "true"
              and node.get("enabled") == "true"]
    if len(fields) != 1:
        return None
    field = fields[0]
    bounds = tuple(map(int, re.findall(r"\d+", field.get("bounds") or "")))
    matching = [c for c in controls if c["bounds"] == bounds and c["class"].endswith("EditText")]
    if len(matching) != 1:
        return None
    return {"candidateId": matching[0]["id"], "text": (field.get("text") or "").strip(),
            "hint": (field.get("hint") or field.get("content-desc") or "").strip()}


def export(snapshot, controls):
    field = focused_field(snapshot.ui_hierarchy_xml, controls)
    page = field["hint"] if field else ""
    identity = [PACKAGE, page, [(c["label"], c["class"]) for c in controls]]
    result = {"provider": "ARTEMIS_UIAUTOMATOR2", "packageName": PACKAGE,
              "hierarchySha256": hashlib.sha256(snapshot.ui_hierarchy_xml.encode()).hexdigest(),
              "imageSha256": hashlib.sha256(snapshot.screenshot_bytes).hexdigest(),
              "screenSignature": hashlib.sha256(json.dumps(identity).encode()).hexdigest(),
              "pageTitle": page,
              "candidates": [{key: c[key] for key in ("id", "label", "description", "class")}
                             for c in controls]}
    if field:
        result.update(focusedFieldCandidateId=field["candidateId"], focusedFieldText=field["text"])
    return result


async def capture(driver, raw, episode, phase):
    snapshot = await driver.get_screen_data()
    if await driver.get_current_package() != PACKAGE or not snapshot.ui_hierarchy_xml or not snapshot.screenshot_bytes:
        raise RuntimeError("Artemis did not observe a complete YouTube screen")
    folder = raw / episode
    folder.mkdir(parents=True, exist_ok=True)
    (folder / f"{phase}.xml").write_text(snapshot.ui_hierarchy_xml)
    (folder / f"{phase}.jpg").write_bytes(snapshot.screenshot_bytes)
    controls = clickable_controls(snapshot.ui_hierarchy_xml)
    return controls, export(snapshot, controls)


async def collect(args):
    preflight(args.serial)
    sys.path.insert(0, str(args.artemis_src.resolve()))
    from adbutils import AdbClient
    from artemis.clients.ui_automator_client import UIAutomatorClient
    from artemis.drivers.android.adb_driver import AndroidAdbDriver

    prior = [json.loads(line) for line in args.output.read_text().splitlines() if line.strip()] if args.output.exists() else []
    already = {r["episodeId"] for r in prior if r.get("step") == 2 and r.get("expected", {}).get("operation") == "DONE"}
    driver = AndroidAdbDriver(args.serial, AdbClient(), ui_adb_client=UIAutomatorClient(args.serial))
    raw = args.raw.resolve()
    rows, failures = [], []
    await driver.connect()
    try:
        for query in QUERIES:
            episode = "youtube_query_" + re.sub(r"[^a-z0-9]+", "_", query)
            if episode in already:
                continue
            try:
                if not await driver.press_key("home") or not await driver.stop_app(PACKAGE) or not await driver.launch_app(PACKAGE):
                    raise RuntimeError("Could not open YouTube")
                home_controls, home = await capture(driver, raw, episode, "home")
                search = choose(home_controls, "Search")
                if search is None or len(home_controls) < 3:
                    raise RuntimeError("YouTube home lacks a unique Search affordance")
                x1, y1, x2, y2 = search["bounds"]
                if not await driver.tap((x1 + x2) // 2, (y1 + y2) // 2):
                    raise RuntimeError("Artemis failed to tap Search")
                controls, editing = await capture(driver, raw, episode, "editing")
                field_id = editing.get("focusedFieldCandidateId")
                if not field_id or editing["pageTitle"] != "Search YouTube" or editing["hierarchySha256"] == home["hierarchySha256"]:
                    raise RuntimeError("Search did not reveal an observed focused field")
                if not await driver.input_text(query, clear_existing=True):
                    raise RuntimeError("Artemis field-entry action failed")
                _, typed = await capture(driver, raw, episode, "typed")
                if typed.get("focusedFieldText") != query or typed["hierarchySha256"] == editing["hierarchySha256"]:
                    raise RuntimeError("The exact user literal was not observed in the focused field")
                base = {"version": 1, "episodeId": episode,
                        "goal": f"In YouTube, enter {query} into Search and stop before submitting.",
                        "appFamily": "youtube-query-entry", "device": "fresh_isolated_android_36_avd",
                        "sourceRevision": args.revision, "oracle": "focused_search_field_exact_literal",
                        "collectedUtcMs": int(time.time() * 1000)}
                rows.extend([
                    {**base, "step": 0, "subgoal": "Focus YouTube Search", "observation": home,
                     "expected": {"operation": "TAP", "candidateId": search["id"]},
                     "postObservation": editing, "outcomeVerified": True},
                    {**base, "step": 1, "subgoal": f"Type exact query {query}", "observation": editing,
                     "expected": {"operation": "TYPE_LITERAL", "candidateId": field_id, "literal": query},
                     "postObservation": typed, "outcomeVerified": True},
                    {**base, "step": 2, "subgoal": "Confirm query is present; do not submit", "observation": typed,
                     "expected": {"operation": "DONE"}, "postObservation": typed,
                     "outcomeVerified": True, "verification": {"observedFieldValue": query}},
                ])
                print(f"verified {episode}", flush=True)
            except (OSError, RuntimeError, ET.ParseError, ValueError) as error:
                failures.append({"episodeId": episode, "reason": str(error)})
                print(f"excluded {episode}: {error}", flush=True)
    finally:
        await driver.disconnect()
    result = merge_verified_rows(prior, rows)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    pending = args.output.with_suffix(".tmp")
    pending.write_text("".join(json.dumps(row, ensure_ascii=False) + "\n" for row in result))
    pending.replace(args.output)
    report = {"source": "Artemis AndroidAdbDriver/UIAutomatorClient", "avdName": "CyanBridge_Artemis_Data",
              "includedEpisodes": len(result) // 3, "includedDecisions": len(result),
              "excludedThisRun": failures, "taskerCompatible": False,
              "limitations": "Stops before network search; no result/video verification. Repeated search UI is one screen family."}
    args.output.with_suffix(".audit.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps({"episodes": len(result) // 3, "decisions": len(result),
                      "excludedThisRun": len(failures)}, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--artemis-src", type=Path, required=True)
    parser.add_argument("--raw", type=Path, default=Path("/tmp/opencode/artemis-phone-captures"))
    parser.add_argument("--output", type=Path, default=Path("android/CyanBridge/training/artemis_real_v1/youtube_query.jsonl"))
    parser.add_argument("--revision", default="371aa6df56880643da57b30da936e9812fb0ec66")
    asyncio.run(collect(parser.parse_args()))

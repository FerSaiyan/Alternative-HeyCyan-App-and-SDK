#!/usr/bin/env python3
"""Collect Clock-tab decisions from Artemis-observed UI with selected-tab proof."""

from __future__ import annotations

import argparse
import asyncio
import hashlib
import json
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

from collect_artemis_settings import clickable_controls, choose, merge_verified_rows, preflight


PACKAGE = "com.google.android.deskclock"
TABS = ("Alarm", "Clock", "Timer", "Stopwatch", "Bedtime")


def current_tab(xml: str) -> str | None:
    selected = []
    for node in ET.fromstring(xml).iter("node"):
        if node.get("selected") == "true" and "tab_menu_" in (node.get("resource-id") or ""):
            selected.append((node.get("resource-id") or "").split("tab_menu_", 1)[1].title())
    return selected[0] if len(selected) == 1 else None


def export(snapshot, controls):
    title = current_tab(snapshot.ui_hierarchy_xml) or ""
    identity = [PACKAGE, title, [(x["label"], x["class"]) for x in controls]]
    return {"provider": "ARTEMIS_UIAUTOMATOR2", "packageName": PACKAGE,
            "hierarchySha256": hashlib.sha256(snapshot.ui_hierarchy_xml.encode()).hexdigest(),
            "imageSha256": hashlib.sha256(snapshot.screenshot_bytes).hexdigest(),
            "screenSignature": hashlib.sha256(json.dumps(identity).encode()).hexdigest(),
            "pageTitle": title,
            "candidates": [{k: x[k] for k in ("id", "label", "description", "class")} for x in controls]}


async def capture(driver, raw, episode, phase):
    snapshot = await driver.get_screen_data()
    if await driver.get_current_package() != PACKAGE or not snapshot.ui_hierarchy_xml or not snapshot.screenshot_bytes:
        raise RuntimeError("Artemis did not observe a complete Clock screen")
    folder = raw / episode
    folder.mkdir(parents=True, exist_ok=True)
    (folder / f"{phase}.xml").write_text(snapshot.ui_hierarchy_xml)
    (folder / f"{phase}.jpg").write_bytes(snapshot.screenshot_bytes)
    controls = clickable_controls(snapshot.ui_hierarchy_xml)
    return snapshot, controls, export(snapshot, controls)


async def tap_control(driver, control):
    x1, y1, x2, y2 = control["bounds"]
    if not await driver.tap((x1 + x2) // 2, (y1 + y2) // 2):
        raise RuntimeError("Artemis failed to tap the observed Clock tab")


async def collect(args):
    preflight(args.serial)
    sys.path.insert(0, str(args.artemis_src.resolve()))
    from adbutils import AdbClient
    from artemis.clients.ui_automator_client import UIAutomatorClient
    from artemis.drivers.android.adb_driver import AndroidAdbDriver

    previous = [json.loads(s) for s in args.output.read_text().splitlines() if s.strip()] if args.output.exists() else []
    complete = {r["episodeId"] for r in previous if r.get("step") == 1 and r.get("expected", {}).get("operation") == "DONE"}
    driver = AndroidAdbDriver(args.serial, AdbClient(), ui_adb_client=UIAutomatorClient(args.serial))
    raw = args.raw.resolve()
    rows, failures = [], []
    await driver.connect()
    try:
        for target in TABS:
            episode = f"clock_tab_{target.lower()}"
            if episode in complete:
                continue
            try:
                if not await driver.press_key("home") or not await driver.stop_app(PACKAGE) or not await driver.launch_app(PACKAGE):
                    raise RuntimeError("Could not open Clock")
                _, controls, start = await capture(driver, raw, episode, "start")
                # Present an *unselected* target in every training row; setup
                # taps are not exported as successful task actions.
                setup = "Alarm" if target == "Clock" else "Clock"
                if start["pageTitle"] != setup:
                    control = choose(controls, setup)
                    if control is None:
                        raise RuntimeError("Missing a unique setup tab")
                    await tap_control(driver, control)
                _, offered, before = await capture(driver, raw, episode, "before")
                if before["pageTitle"] != setup or len(offered) < 3:
                    raise RuntimeError("Setup tab not selected or too few visible controls")
                chosen = choose(offered, target)
                if chosen is None:
                    raise RuntimeError("Target tab is missing or ambiguous")
                await tap_control(driver, chosen)
                _, _, after = await capture(driver, raw, episode, "after")
                if after["pageTitle"] != target or after["hierarchySha256"] == before["hierarchySha256"]:
                    raise RuntimeError("Post-action selected-tab flag did not change to target")
                base = {"version": 1, "episodeId": episode, "goal": f"Open the {target} tab in Clock.",
                        "appFamily": "google-clock", "device": "fresh_isolated_android_36_avd",
                        "sourceRevision": args.revision, "oracle": "selected_tab_flag_plus_post_hierarchy",
                        "collectedUtcMs": int(time.time() * 1000)}
                rows.extend([
                    {**base, "step": 0, "subgoal": f"Open {target}", "observation": before,
                     "expected": {"operation": "TAP", "candidateId": chosen["id"]},
                     "postObservation": after, "outcomeVerified": True},
                    {**base, "step": 1, "subgoal": "Confirm the target tab is selected", "observation": after,
                     "expected": {"operation": "DONE"}, "postObservation": after,
                     "outcomeVerified": True, "verification": {"observedPageTitle": target}},
                ])
                print(f"verified {episode}", flush=True)
            except (OSError, RuntimeError, ET.ParseError, ValueError) as error:
                failures.append({"episodeId": episode, "reason": str(error)})
                print(f"excluded {episode}: {error}", flush=True)
    finally:
        await driver.disconnect()
    result = merge_verified_rows(previous, rows)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    temp = args.output.with_suffix(".tmp")
    temp.write_text("".join(json.dumps(r, ensure_ascii=False) + "\n" for r in result))
    temp.replace(args.output)
    report = {"source": "Artemis AndroidAdbDriver/UIAutomatorClient", "avdName": "CyanBridge_Artemis_Data",
              "includedEpisodes": len(result) // 2, "includedDecisions": len(result),
              "excludedThisRun": failures, "taskerCompatible": False,
              "limitations": "Repeated Clock tabs are one screen family; no independent validation split."}
    args.output.with_suffix(".audit.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps({"episodes": len(result) // 2, "decisions": len(result),
                      "excludedThisRun": len(failures)}, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--artemis-src", type=Path, required=True)
    parser.add_argument("--raw", type=Path, default=Path("/tmp/opencode/artemis-phone-captures"))
    parser.add_argument("--output", type=Path, default=Path("android/CyanBridge/training/artemis_real_v1/clock.jsonl"))
    parser.add_argument("--revision", default="371aa6df56880643da57b30da936e9812fb0ec66")
    asyncio.run(collect(parser.parse_args()))

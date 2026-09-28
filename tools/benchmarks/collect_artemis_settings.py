#!/usr/bin/env python3
"""Collect verified Android Settings decisions using Artemis on an isolated AVD.

The source is Artemis/UIAutomator2, NEVER Tasker/AutoInput. Export only
machine-checked navigation rows; raw screenshots and XML stay outside Git.
Artemis's LLM/agent is not involved: deterministic scripted paths give an
independent post-screen oracle instead of an unverified teacher prediction.
"""

from __future__ import annotations

import argparse
import asyncio
import hashlib
import json
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path


AVD_NAME = "CyanBridge_Artemis_Data"
SETTINGS = "com.android.settings"
PAID = ("net.dinglisch.android.taskerm", "com.joaomgcd.autoinput")
# Curated read-only navigation targets. The collector verifies every resulting
# Android page title and excludes transitions that differ across system builds.
PATHS = {
    "Network & internet": ("Internet", "Hotspot & tethering", "Data Saver", "VPN"),
    "Connected devices": ("Connection preferences",),
    "Apps": ("Special app access",),
    "Notifications": ("Notification history", "Conversations", "Bubbles"),
    "Modes": ("Do Not Disturb",),
    "Display & touch": ("Lock screen", "Screen saver", "Display size and text", "Navigation mode"),
}


def adb(serial: str, *args: str) -> str:
    return subprocess.run(["adb", "-s", serial, *args], check=True,
                          capture_output=True, text=True, timeout=25).stdout.strip()


def preflight(serial: str, *, adb_call=adb):
    if not re.fullmatch(r"emulator-[0-9]+", serial):
        raise ValueError("Only an explicitly named isolated emulator is supported")
    name = adb_call(serial, "emu", "avd", "name").splitlines()[0].strip()
    if name != AVD_NAME:
        raise ValueError(f"Refusing {name}: expected isolated {AVD_NAME}")
    if adb_call(serial, "shell", "getprop", "sys.boot_completed") != "1":
        raise ValueError("Emulator is not fully booted")
    packages = adb_call(serial, "shell", "pm", "list", "packages")
    if any(f"package:{package}" in packages.splitlines() for package in PAID):
        raise ValueError("Refusing Tasker/AutoInput AVD")
    accounts = adb_call(serial, "shell", "dumpsys", "account")
    if not re.search(r"\bAccounts:\s*0\b", accounts):
        raise ValueError("Refusing an emulator with an Android account")


def clickable_controls(xml: str):
    """Use Artemis-captured UI XML to retain clickable parent/child bindings."""
    root = ET.fromstring(xml)
    controls = []
    for node in root.iter("node"):
        if node.get("clickable") != "true" or node.get("enabled") != "true":
            continue
        names = []
        for descendant in node.iter("node"):
            text = (descendant.get("text") or descendant.get("content-desc") or "").strip()
            if text and text not in names:
                names.append(text)
        if not names or any("@" in part for part in names):
            continue
        bounds = node.get("bounds") or ""
        match = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", bounds)
        if not match:
            continue
        coords = tuple(map(int, match.groups()))
        if coords[2] <= coords[0] or coords[3] <= coords[1]:
            continue
        controls.append({"id": f"n{len(controls)}", "label": names[0][:100],
                         "description": " | ".join(names[:2])[:160],
                         "class": node.get("class") or "", "bounds": coords})
    return controls


def page_title(xml: str) -> str:
    for node in ET.fromstring(xml).iter("node"):
        if (node.get("resource-id") or "").endswith(":id/collapsing_toolbar"):
            return (node.get("content-desc") or "").strip()
    return ""


def choose(controls: list[dict], label: str) -> dict | None:
    # Reject duplicates. E.g. Dark theme can be both a switch and a navigation
    # row; a target name alone is insufficient to ground the action.
    found = [item for item in controls if item["label"] == label]
    return found[0] if len(found) == 1 else None


def merge_verified_rows(prior: list[dict], new: list[dict]) -> list[dict]:
    """Retain verified episodes across disconnected collection attempts."""
    by_episode = {}
    for row in prior:
        if row.get("observation", {}).get("screenSignature") and row.get("outcomeVerified"):
            by_episode.setdefault(row["episodeId"], []).append(row)
    current = {}
    for row in new:
        current.setdefault(row["episodeId"], []).append(row)
    by_episode.update(current)
    return [row for episode in sorted(by_episode)
            for row in sorted(by_episode[episode], key=lambda item: item["step"])]


def exported(snapshot, controls):
    # Stable layout identity for leakage auditing: screenshot/XML hashes change
    # with clock, radio state and scroll position. Labels and page title remain
    # the meaningful decision surface for these navigation tasks.
    identity = [SETTINGS, page_title(snapshot.ui_hierarchy_xml),
                [(item["label"], item["class"]) for item in controls]]
    return {"provider": "ARTEMIS_UIAUTOMATOR2", "packageName": SETTINGS,
            "hierarchySha256": hashlib.sha256(snapshot.ui_hierarchy_xml.encode()).hexdigest(),
            "imageSha256": hashlib.sha256(snapshot.screenshot_bytes).hexdigest(),
            "screenSignature": hashlib.sha256(json.dumps(identity, ensure_ascii=False).encode()).hexdigest(),
            "pageTitle": page_title(snapshot.ui_hierarchy_xml),
            "candidates": [{k: item[k] for k in ("id", "label", "description", "class")}
                           for item in controls]}


async def capture(driver, raw: Path, episode: str, phase: str):
    snap = await driver.get_screen_data()
    if not snap.ui_hierarchy_xml or not snap.screenshot_bytes:
        raise RuntimeError("Artemis did not capture both hierarchy and pixels")
    if await driver.get_current_package() != SETTINGS:
        raise RuntimeError("Settings lost foreground; refuse to label this transition")
    path = raw / episode
    path.mkdir(parents=True, exist_ok=True)
    (path / f"{phase}.xml").write_text(snap.ui_hierarchy_xml)
    (path / f"{phase}.jpg").write_bytes(snap.screenshot_bytes)
    controls = clickable_controls(snap.ui_hierarchy_xml)
    return snap, controls, exported(snap, controls)


async def verified_tap(driver, raw, episode, before, controls, target, phase):
    chosen = choose(controls, target)
    if chosen is None:
        raise RuntimeError(f"{target!r} is missing or ambiguous")
    x1, y1, x2, y2 = chosen["bounds"]
    if not await driver.tap((x1 + x2) // 2, (y1 + y2) // 2):
        raise RuntimeError("Artemis tap failed")
    after, controls_after, exported_after = await capture(driver, raw, episode, phase)
    if page_title(after.ui_hierarchy_xml) != target or exported_after["hierarchySha256"] == before["hierarchySha256"]:
        raise RuntimeError(f"Post-action page title failed verification: {page_title(after.ui_hierarchy_xml)!r}")
    return chosen, after, controls_after, exported_after


async def collect(args):
    preflight(args.serial)
    sys.path.insert(0, str(args.artemis_src.resolve()))
    from adbutils import AdbClient
    from artemis.clients.ui_automator_client import UIAutomatorClient
    from artemis.drivers.android.adb_driver import AndroidAdbDriver

    driver = AndroidAdbDriver(args.serial, AdbClient(), ui_adb_client=UIAutomatorClient(args.serial))
    raw = args.raw.resolve()
    raw.mkdir(parents=True, exist_ok=True)
    rows, failures = [], []
    prior = [json.loads(line) for line in args.output.read_text().splitlines() if line.strip()] if args.output.exists() else []
    already_verified = {row["episodeId"] for row in prior if row.get("step") == 2 and row.get("outcomeVerified")}
    await driver.connect()
    try:
        for category, targets in PATHS.items():
            for target in targets:
                episode = f"settings_{re.sub('[^a-z0-9]+', '_', category.lower()).strip('_')}__{re.sub('[^a-z0-9]+', '_', target.lower()).strip('_')}"
                if episode in already_verified:
                    continue
                goal = f"In Settings, open {target} under {category}."
                try:
                    # A previous route can end in a separate Settings-owned app
                    # such as Permission Controller. Home is a clean, reversible
                    # reset; never let a failed external transition poison the
                    # next independent episode.
                    if not await driver.press_key("home"):
                        raise RuntimeError("Could not return to the isolated launcher")
                    # Settings can delegate a page to Permission Controller,
                    # whose task survives a force-stop of com.android.settings.
                    # Explicitly stop that isolated-AVD overlay and launch the
                    # root Activity rather than resuming monkey's last task.
                    await asyncio.to_thread(driver.device.shell, "am force-stop com.google.android.permissioncontroller")
                    await asyncio.to_thread(driver.device.shell, "am force-stop com.google.android.as")
                    if not await driver.stop_app(SETTINGS):
                        raise RuntimeError("Could not open Settings")
                    await asyncio.to_thread(driver.device.shell, "am start -S -n com.android.settings/.Settings")
                    _, initial, root = await capture(driver, raw, episode, "root")
                    if len(initial) < 3 or choose(initial, category) is None:
                        raise RuntimeError("Root lacks three controls or requested category")
                    selected_root, _, sub_controls, sub = await verified_tap(
                        driver, raw, episode, root, initial, category, "category")
                    if len(sub_controls) < 3 or choose(sub_controls, target) is None:
                        raise RuntimeError("Subpage lacks three controls or unambiguous requested target")
                    selected_sub, _, final_controls, final = await verified_tap(
                        driver, raw, episode, sub, sub_controls, target, "final")
                    if choose(final_controls, "Navigate up") is None:
                        raise RuntimeError("Final page lacks a grounded return control")
                    base = {"version": 1, "episodeId": episode, "goal": goal,
                            "appFamily": "android-settings", "sourceRevision": args.revision,
                            "oracle": "scripted_target_plus_verified_post_page_title",
                            "device": "fresh_isolated_android_36_avd", "collectedUtcMs": int(time.time() * 1000)}
                    rows.extend([
                        {**base, "step": 0, "subgoal": f"Open {category}", "observation": root,
                         "expected": {"operation": "TAP", "candidateId": selected_root["id"]},
                         "postObservation": sub, "outcomeVerified": True},
                        {**base, "step": 1, "subgoal": f"Open {target}", "observation": sub,
                         "expected": {"operation": "TAP", "candidateId": selected_sub["id"]},
                         "postObservation": final, "outcomeVerified": True},
                        {**base, "step": 2, "subgoal": "Confirm the requested page is open", "observation": final,
                         "expected": {"operation": "DONE"}, "postObservation": final,
                         "outcomeVerified": True, "verification": {"observedPageTitle": target}},
                    ])
                    print(f"verified {episode}: root + subpage + DONE", flush=True)
                except (OSError, RuntimeError, ET.ParseError, ValueError) as error:
                    failures.append({"episodeId": episode, "reason": str(error)})
                    print(f"excluded {episode}: {error}", flush=True)
    finally:
        await driver.disconnect()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    # Preserve independently verified episodes across intermittent emulator
    # disconnects. Never let a failed retry erase an earlier good collection.
    combined = merge_verified_rows(prior, rows)
    pending = args.output.with_suffix(".tmp")
    pending.write_text("".join(json.dumps(row, ensure_ascii=False) + "\n" for row in combined))
    pending.replace(args.output)
    audit = {"source": "Artemis AndroidAdbDriver/UIAutomatorClient", "revision": args.revision,
             "avdName": AVD_NAME, "includedEpisodes": len(combined) // 3,
             "includedDecisions": len(combined), "newlyVerifiedEpisodes": len(rows) // 3,
             "excludedThisRun": failures,
             "taskerCompatible": False, "unobservedActionCount": 0,
             "limitations": "Navigation-only Settings data; no Tasker equivalence or unseen-app generalization."}
    args.output.with_suffix(".audit.json").write_text(json.dumps(audit, indent=2) + "\n")
    print(json.dumps({"episodes": audit["includedEpisodes"], "decisions": len(combined),
                      "newlyVerifiedEpisodes": len(rows) // 3, "excludedThisRun": len(failures)}, indent=2))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--artemis-src", type=Path, required=True)
    parser.add_argument("--raw", type=Path, default=Path("/tmp/opencode/artemis-phone-captures"))
    parser.add_argument("--output", type=Path, default=Path("android/CyanBridge/training/artemis_real_v1/settings.jsonl"))
    parser.add_argument("--revision", default="371aa6df56880643da57b30da936e9812fb0ec66")
    args = parser.parse_args()
    asyncio.run(collect(args))


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Static contract checks for CyanBridge Tasker integrations.

This intentionally uses only the Python standard library so it can run on the homelab
runner before Gradle or an Android device is available.
"""
from __future__ import annotations

import sys
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
TASKER = ROOT / "android" / "CyanBridge" / "tasker"
MANIFEST = ROOT / "android" / "CyanBridge" / "app" / "src" / "main" / "AndroidManifest.xml"
BUILD_GRADLE = ROOT / "android" / "CyanBridge" / "app" / "build.gradle"

errors: list[str] = []


def fail(message: str) -> None:
    errors.append(message)


def load_xml(name: str) -> str:
    path = TASKER / name
    if not path.exists():
        fail(f"missing Tasker profile: {path}")
        return ""
    text = path.read_text(encoding="utf-8")
    try:
        ET.fromstring(text)
    except ET.ParseError as exc:
        fail(f"invalid XML in {name}: {exc}")
    return text


def require(text: str, needle: str, where: str) -> None:
    if needle not in text:
        fail(f"{where}: missing required contract fragment {needle!r}")


def forbid(text: str, needle: str, where: str) -> None:
    if needle in text:
        fail(f"{where}: forbidden fragment still present {needle!r}")


ai = load_xml("Tasker_AI.xml")
for needle in (
    "CyanBridge Gemini v3",
    "CyanBridge ChatGPT v1",
    "gemini-v3",
    "chatgpt-v1",
    "com.fersaiyan.cyanbridge.AI_EVENT",
    "com.fersaiyan.cyanbridge.AI_IMAGE_PROFILE",
    "%assistant",
    "<rhs>Gemini</rhs>",
    "<rhs>ChatGPT</rhs>",
    "com.joaomgcd.autoinput",
):
    require(ai, needle, "Tasker_AI.xml")

local_agent = load_xml("CyanBridge_LocalAgent_Tasker.XML")
for needle in (
    "com.fersaiyan.cyanbridge.TASKER_AGENT_OBSERVE",
    "com.fersaiyan.cyanbridge.TASKER_AGENT_EXECUTE",
    "com.fersaiyan.cyanbridge.TASKER_AGENT_RESPONSE",
    "CB_LocalAgentBlocked",
    "com.joaomgcd.autoinput",
    "read_screen_aloud",
):
    require(local_agent, needle, "CyanBridge_LocalAgent_Tasker.XML")

auto_diary = load_xml("CyanBridge_AutoDiary_Tasker.XML")
for needle in (
    "CyanBridge AutoDiary Periodic Capture",
    "com.fersaiyan.cyanbridge.TASKER_AUTO_DIARY_CAPTURE",
    "com.fersaiyan.cyanbridge.TASKER_AUTO_DIARY_ENABLE",
    "com.fersaiyan.cyanbridge.TASKER_AUTO_DIARY_DISABLE",
    "CB_AutoDiaryExcluded",
    "<repval>10</repval>",
    "com.joaomgcd.autoinput",
):
    require(auto_diary, needle, "CyanBridge_AutoDiary_Tasker.XML")

visual_diary = load_xml("CyanBridge_VisualDiary_Tasker.XML")
for needle in (
    "CyanBridge VisualDiary Periodic Capture",
    "com.fersaiyan.cyanbridge.TASKER_VISUAL_DIARY_CAPTURE",
    "com.fersaiyan.cyanbridge.TASKER_VISUAL_DIARY_ENABLE",
    "com.fersaiyan.cyanbridge.TASKER_VISUAL_DIARY_DISABLE",
    "<repval>15</repval>",
):
    require(visual_diary, needle, "CyanBridge_VisualDiary_Tasker.XML")

manifest = MANIFEST.read_text(encoding="utf-8")
for needle in (
    "android.permission.BIND_ACCESSIBILITY_SERVICE",
    "LocalAgentAccessibilityService",
    "android.permission.QUERY_ALL_PACKAGES",
    "android.permission.MANAGE_EXTERNAL_STORAGE",
):
    forbid(manifest, needle, "AndroidManifest.xml")
for needle in (
    'android:name="net.dinglisch.android.taskerm"',
    'android:name="com.joaomgcd.autoinput"',
):
    require(manifest, needle, "AndroidManifest.xml")

build_gradle = BUILD_GRADLE.read_text(encoding="utf-8")
for needle in ("dev.rikka.shizuku", "rikka.shizuku"):
    forbid(build_gradle, needle, "app/build.gradle")

# The APK-embedded profiles used by the assistant setup must remain version-compatible
# with the downloadable combined profile.
for asset_name, target, version in (
    ("CyanBridge_Gemini.xml", "gemini", "gemini-v3"),
    ("CyanBridge_ChatGPT.xml", "chatgpt", "chatgpt-v1"),
):
    asset = ROOT / "android" / "CyanBridge" / "app" / "src" / "main" / "assets" / "tasker" / asset_name
    if not asset.exists():
        fail(f"missing embedded assistant profile: {asset}")
        continue
    text = asset.read_text(encoding="utf-8")
    try:
        ET.fromstring(text)
    except ET.ParseError as exc:
        fail(f"invalid embedded XML in {asset_name}: {exc}")
    require(text, f"profile_target:{target}", asset_name)
    require(text, f"profile_version:{version}", asset_name)

if errors:
    print("Tasker contract validation FAILED", file=sys.stderr)
    for error in errors:
        print(f" - {error}", file=sys.stderr)
    raise SystemExit(1)

print("Tasker contract validation passed")
print(" - assistant bundle: Gemini v3 + ChatGPT v1")
print(" - Local Agent: Tasker/AutoInput observer + executor")
print(" - AutoDiary: Tasker schedule + exclusions")
print(" - Visual Diary: Tasker schedule trigger")
print(" - CyanBridge manifest: no AccessibilityService / QUERY_ALL_PACKAGES / all-files access")

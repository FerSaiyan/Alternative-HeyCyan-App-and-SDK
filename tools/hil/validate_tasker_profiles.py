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


ai = load_xml("Tasker_AI.prj.xml")
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
    require(ai, needle, "Tasker_AI.prj.xml")

local_agent = load_xml("CyanBridge_LocalAgent_Tasker.prj.xml")
for needle in (
    "com.fersaiyan.cyanbridge.TASKER_AGENT_OBSERVE",
    "com.fersaiyan.cyanbridge.TASKER_AGENT_EXECUTE",
    "com.fersaiyan.cyanbridge.TASKER_AGENT_RESPONSE",
    "CB_LocalAgentBlocked",
    "com.joaomgcd.autoinput",
    "read_screen_aloud",
):
    require(local_agent, needle, "CyanBridge_LocalAgent_Tasker.prj.xml")

auto_diary = load_xml("CyanBridge_AutoDiary_Tasker.prj.xml")
for needle in (
    "CyanBridge AutoDiary Periodic Capture",
    "CyanBridge AutoDiary Periodic Handler",
    "com.fersaiyan.cyanbridge.TASKER_AUTO_DIARY_CAPTURE",
    "com.fersaiyan.cyanbridge.TASKER_AUTO_DIARY_ENABLE",
    "com.fersaiyan.cyanbridge.TASKER_AUTO_DIARY_DISABLE",
    "CB_AutoDiaryExcluded",
    "<repval>10</repval>",
    "com.joaomgcd.autoinput",
    "payload:%capture_payload",
):
    require(auto_diary, needle, "CyanBridge_AutoDiary_Tasker.prj.xml")

visual_diary = load_xml("CyanBridge_VisualDiary_Tasker.prj.xml")
for needle in (
    "CyanBridge VisualDiary Periodic Capture",
    "CyanBridge VisualDiary Periodic Handler",
    "com.fersaiyan.cyanbridge.TASKER_VISUAL_DIARY_CAPTURE",
    "com.fersaiyan.cyanbridge.TASKER_VISUAL_DIARY_ENABLE",
    "com.fersaiyan.cyanbridge.TASKER_VISUAL_DIARY_DISABLE",
    "<repval>15</repval>",
):
    require(visual_diary, needle, "CyanBridge_VisualDiary_Tasker.prj.xml")

hil = load_xml("CyanBridge_HIL_Tasker.prj.xml")
for needle in (
    "com.fersaiyan.cyanbridge.HIL_AUTODIARY_NOW",
    "com.fersaiyan.cyanbridge.HIL_VISUALDIARY_NOW",
    "com.fersaiyan.cyanbridge.HIL_SET_AUTODIARY_EXCLUDED",
    "com.fersaiyan.cyanbridge.HIL_SET_LOCALAGENT_BLOCKED",
    'performTask("CyanBridge AutoDiary Periodic Handler"',
    'performTask("CyanBridge VisualDiary Periodic Handler"',
    'setGlobal("CB_AutoDiaryExcluded"',
    'setGlobal("CB_LocalAgentBlocked"',
):
    require(hil, needle, "CyanBridge_HIL_Tasker.prj.xml")

for name, text in (
    ("Tasker_AI.prj.xml", ai),
    ("CyanBridge_LocalAgent_Tasker.prj.xml", local_agent),
    ("CyanBridge_AutoDiary_Tasker.prj.xml", auto_diary),
    ("CyanBridge_VisualDiary_Tasker.prj.xml", visual_diary),
    ("CyanBridge_HIL_Tasker.prj.xml", hil),
):
    require(text, '<Project sr="proj0"', name)
    require(text, "<cdate>", name)
    require(text, "<pids>", name)
    require(text, "<tids>", name)
    # Tasker 6.6.20 JavaScriptlets finish without delivering package-targeted
    # broadcasts via the JS sendIntent helper; every CyanBridge response/capture
    # must use Tasker's native Send Intent action instead.
    forbid(text, 'sendIntent("com.fersaiyan.cyanbridge', name)
    try:
        root = ET.fromstring(text)
    except ET.ParseError:
        continue
    project = root.find("Project")
    if project is None:
        continue
    profiles = {node.findtext("id", "") for node in root.findall("Profile")}
    tasks = {node.findtext("id", "") for node in root.findall("Task")}
    project_profiles = set(project.findtext("pids", "").split(","))
    project_tasks = set(project.findtext("tids", "").split(","))
    if project_profiles != profiles:
        fail(f"{name}: project profile IDs {project_profiles} do not match {profiles}")
    if project_tasks != tasks:
        fail(f"{name}: project task IDs {project_tasks} do not match {tasks}")
    for node in root.findall("Profile"):
        if node.get("sr") != f'prof{node.findtext("id", "")}':
            fail(f"{name}: profile sr does not match its id")
    for node in root.findall("Task"):
        if node.get("sr") != f'task{node.findtext("id", "")}':
            fail(f"{name}: task sr does not match its id")

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
print(" - HIL controller: real periodic-task triggers + blacklist setters")
print(" - CyanBridge manifest: no AccessibilityService / QUERY_ALL_PACKAGES / all-files access")

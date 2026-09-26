#!/usr/bin/env python3
"""Opt-in black-box UI check on an isolated emulator using an Artemis daemon.

Artemis controls the DEBUG fixture only. CyanBridge's own decision/approval
path is tested separately by Tasker HIL. Never point this at a provisioned AVD.
The dependency-free artemis-client comes from a pinned external checkout via
--client-src; the full Artemis daemon and its LLM credentials run separately.
"""

from __future__ import annotations

import argparse
import asyncio
import os
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

PACKAGE = "com.fersaiyan.cyanbridge"
ACTIVITY = f"{PACKAGE}/.hil.HilFixtureActivity"
TEXT = "CB_ARTEMIS_FIXTURE_72941"
XML_PATH = "/sdcard/cb_artemis_fixture.xml"
PAID_PACKAGES = {"net.dinglisch.android.taskerm", "com.joaomgcd.autoinput"}


def adb(serial: str, *args: str, timeout: int = 30) -> str:
    result = subprocess.run(
        ["adb", "-s", serial, *args], capture_output=True, text=True, timeout=timeout,
        check=True,
    )
    return result.stdout


def verify_fixture(xml: str) -> None:
    root = ET.fromstring(xml)
    nodes = root.iter("node")
    by_id = {node.get("resource-id", ""): node for node in nodes}
    status = by_id.get(f"{PACKAGE}:id/hil_status")
    typed = by_id.get(f"{PACKAGE}:id/hil_input")
    if status is None or status.get("text") != "HIL_CLICK_COUNT=1":
        raise AssertionError("Artemis did not click the fixture button exactly once")
    if typed is None or typed.get("text") != TEXT:
        raise AssertionError("Artemis did not type the exact literal into the fixture input")


def preflight(serial: str, *, require_app: bool = True) -> None:
    if not serial.startswith("emulator-"):
        raise RuntimeError("Only an explicitly selected, isolated Android emulator is supported")
    if adb(serial, "get-state").strip() != "device":
        raise RuntimeError(f"Device {serial} is not online")
    if adb(serial, "shell", "getprop", "sys.boot_completed").strip() != "1":
        raise RuntimeError(f"Device {serial} has not booted")
    packages = set(adb(serial, "shell", "pm", "list", "packages").replace("\r", "").splitlines())
    if any(f"package:{package}" in packages for package in PAID_PACKAGES):
        raise RuntimeError("This emulator has Tasker/AutoInput: use an isolated throwaway test AVD")
    if require_app and f"package:{PACKAGE}" not in packages:
        raise RuntimeError("Install CyanBridge debug APK on the selected emulator first")


async def run(serial: str, base_url: str, token: str | None) -> None:
    from artemis_client import ArtemisClient

    client = ArtemisClient(base_url, token=token, device_serial=serial, default_profile="flash")
    devices = await client.list_devices()
    if not any(device.serial == serial and device.state == "device" and not device.busy for device in devices):
        raise RuntimeError(f"Artemis daemon does not see idle device {serial}")
    adb(serial, "shell", "am", "force-stop", PACKAGE)
    adb(serial, "shell", "am", "start", "-n", ACTIVITY)
    # Artemis is an external tester, not CyanBridge's runtime policy engine.
    # The package lock prevents it from navigating into Gmail or other apps.
    result = await client.run(
        f"In the CyanBridge HIL Fixture, tap the button labeled HIL CLICK ME exactly once, "
        f"then type the exact text {TEXT} into the field labeled HIL INPUT. "
        "Do not tap anything else and remain on this screen.",
        profile="flash", locked_app_package=PACKAGE, device_serial=serial, timeout=180,
    )
    if not result.succeeded:
        raise AssertionError(f"Artemis task {result.trace_id} ended with status {result.status}: {result.error}")
    try:
        adb(serial, "shell", "uiautomator", "dump", XML_PATH, timeout=40)
        xml = adb(serial, "exec-out", "cat", XML_PATH)
        verify_fixture(xml)
    finally:
        adb(serial, "shell", "rm", "-f", XML_PATH)
    print(f"Artemis fixture passed on {serial}; trace_id={result.trace_id}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True, help="Dedicated emulator serial (never the licensed Tasker AVD)")
    parser.add_argument("--base-url", default="http://127.0.0.1:8000")
    parser.add_argument("--client-src", type=Path, help="Pinned Artemis checkout; uses its stdlib-only client")
    parser.add_argument("--preflight-only", action="store_true", help="Check the emulator before installing APKs")
    args = parser.parse_args()
    preflight(args.serial, require_app=not args.preflight_only)
    if args.preflight_only:
        print(f"Isolated Artemis target ready: {args.serial}")
        return
    if args.client_src is None:
        parser.error("--client-src is required unless --preflight-only is used")
    src = args.client_src / "packages/artemis-client/src"
    if not (src / "artemis_client/client.py").is_file():
        parser.error(f"No artemis-client in {src}")
    sys.path.insert(0, str(src.resolve()))
    asyncio.run(run(args.serial, args.base_url, os.environ.get("ARTEMIS_TOKEN")))


if __name__ == "__main__":
    main()

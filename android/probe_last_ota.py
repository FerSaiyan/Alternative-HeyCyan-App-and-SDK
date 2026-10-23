#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import re
import urllib.error
import urllib.request


API_URL = "https://www.qlifesnap.com/glasses/app-update/last-ota"


def dedupe(seq: list[str]) -> list[str]:
    seen: set[str] = set()
    out: list[str] = []
    for item in seq:
        if item and item not in seen:
            seen.add(item)
            out.append(item)
    return out


def build_version_variants(version: str) -> list[str]:
    raw = (version or "").strip()
    if not raw:
        return []
    variants = [raw]
    m = re.match(r"^(?:v)?(?P<base>\d+\.\d+\.\d+)(?:[_-](?P<stamp>\d{6,10}))?$", raw, re.IGNORECASE)
    if m:
        base = m.group("base")
        stamp = m.group("stamp")
        if stamp:
            variants.append(f"{base}_{stamp[:6]}")
            variants.append(base)
        else:
            variants.append(base)
    return dedupe(variants)


def build_hw_variants(hw: str) -> list[str]:
    raw = (hw or "").strip()
    if not raw:
        return []
    normalized = raw.upper()
    variants = [normalized]
    if normalized == "WIFIA03BV":
        variants.extend(["WIFIA03B", "WIFIA03"])
    elif normalized == "WIFIA03B":
        variants.append("WIFIA03")
    return dedupe(variants)


def post_json(url: str, payload: dict[str, object], token: str) -> tuple[int, str]:
    req = urllib.request.Request(
        url,
        data=json.dumps(payload).encode(),
        headers={
            "Content-Type": "application/json",
            "token": token,
        },
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=20) as resp:
            return resp.status, resp.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as exc:
        return exc.code, exc.read().decode("utf-8", "replace")


def main() -> int:
    ap = argparse.ArgumentParser(description="Probe HeyCyan last-ota API with a captured token.")
    ap.add_argument("--token", required=True, help="Official app token header value")
    ap.add_argument("--hardware-version", default="WIFIA03BV", help="Primary hardwareVersion to test")
    ap.add_argument("--rom-version", default="1.00.27_2512271030", help="Primary romVersion to test")
    ap.add_argument("--app-id", type=int, default=4, help="appId field (default: 4)")
    ap.add_argument("--uid", type=int, default=0, help="uid field (default: 0)")
    ap.add_argument("--os", type=int, default=1, help="os field (default: 1)")
    ap.add_argument("--mac", default="", help="mac field (default: empty)")
    ap.add_argument("--country", default="US", help="country field (default: US)")
    ap.add_argument("--dev", type=int, default=2, help="dev field (default: 2)")
    args = ap.parse_args()

    for hw in build_hw_variants(args.hardware_version):
        for rom in build_version_variants(args.rom_version):
            payload = {
                "appId": args.app_id,
                "uid": args.uid,
                "hardwareVersion": hw,
                "romVersion": rom,
                "os": args.os,
                "mac": args.mac,
                "country": args.country,
                "dev": args.dev,
            }
            status, body = post_json(API_URL, payload, args.token)
            print(json.dumps({"status": status, "payload": payload, "body": body}, ensure_ascii=True))
            if status == 200:
                try:
                    obj = json.loads(body)
                except json.JSONDecodeError:
                    continue
                if obj.get("retCode") == 0:
                    return 0
                if obj.get("retCode") == 401:
                    return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

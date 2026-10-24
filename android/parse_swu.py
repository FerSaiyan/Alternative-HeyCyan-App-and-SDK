#!/usr/bin/env python3
"""
Quick-and-dirty inspector for HeyCyan .swu OTA files.

Usage:
    python parse_swu.py WIFIAM01G1_V9.2.swu

This is intentionally conservative: it identifies obvious container
types, warns if the file is actually an XML error page, and dumps a
few basic facts plus any chip-related strings it can find.
"""

import sys
import os
import struct


def read_head(path: str, n: int = 64) -> bytes:
    with open(path, "rb") as f:
        return f.read(n)


def is_xml_error(head: bytes) -> bool:
    lower = head.lower()
    return lower.startswith(b"<?xml") or b"<error>" in lower or b"<html>" in lower


def detect_container(head: bytes) -> str:
    # Common signatures we might encounter.
    if head.startswith(b"\x1f\x8b"):
        return "gzip-compressed data"
    if head.startswith(b"PK\x03\x04"):
        return "zip archive"
    if head[257:262] == b"ustar":
        return "tar archive"
    if head.startswith(b"\x42\x5a\x68"):
        return "bzip2-compressed data"
    if head.startswith(b"\xfd7zXZ\x00".replace(b"7", b"\x37")):
        return "xz-compressed data"
    if head.startswith(b"\x7fELF"):
        return "ELF executable"
    # SquashFS commonly starts with "hsqs" (little-endian)
    if head.startswith(b"hsqs"):
        return "squashfs filesystem"
    return "unknown (no common magic found)"


def scan_for_chip_strings(path: str, max_bytes: int = 2_000_000) -> None:
    """
    Look for anything hinting at JL7018F / Allwinner V821L2 or related strings.
    This is a simple heuristic scanner, not a full strings(1) implementation.
    """
    interesting_tokens = [
        b"JL7018",
        b"JL70",
        b"JERRY",
        b"ALLWINNER",
        b"V821",
        b"V821L",
    ]

    try:
        with open(path, "rb") as f:
            data = f.read(max_bytes)
    except OSError as e:
        print(f"[!] Failed to read {path}: {e}")
        return

    print("\n[+] Chip-related string scan (first ~2MB):")
    found_any = False
    for token in interesting_tokens:
        idx = data.find(token)
        if idx != -1:
            found_any = True
            print(f"    - Found {token.decode(errors='ignore')} at offset 0x{idx:x}")
    if not found_any:
        print("    (no obvious JL/Allwinner markers in first chunk)")


def main(argv: list[str]) -> int:
    if len(argv) < 2:
        print("Usage: parse_swu.py <path_to_swu>")
        return 1

    path = argv[1]
    if not os.path.isfile(path):
        print(f"[!] File not found: {path}")
        return 1

    size = os.path.getsize(path)
    head = read_head(path, 512)

    print(f"[+] Inspecting: {path}")
    print(f"    Size: {size} bytes")

    if is_xml_error(head):
        print("\n[!] This .swu appears to be an XML error page, not firmware.")
        print("    The beginning of the file looks like:")
        snippet = head.splitlines()[0][:120]
        print(f"    {snippet!r}")
        print("    This usually means the OSS bucket returned AccessDenied; "
              "you'll need to capture the *real* OTA payload (e.g. from the app).")
        return 0

    container = detect_container(head)
    print(f"\n[+] Container guess: {container}")

    # Show first few bytes for manual inspection.
    print("\n[+] First 64 bytes (hex):")
    print("    " + " ".join(f"{b:02x}" for b in head[:64]))

    # Chip-related heuristic scan.
    scan_for_chip_strings(path)

    print("\n[+] Next steps (manual):")
    print("    - If this looks like gzip/zip/tar, try decompression manually.")
    print("    - For unknown containers, run binwalk/strings on the file and")
    print("      look for partitions or separate images (JL vs Allwinner).")

    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))


#!/usr/bin/env python3
"""Package matching patched Kotlin/JNI binaries with the stock 0.16 AAR metadata."""
import argparse
import hashlib
import json
import re
import subprocess
import zipfile
from pathlib import Path


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


ELF_MACHINES = {"arm64-v8a": 183, "x86_64": 62}
PLATFORM_LIBRARIES = {
    "libc.so", "libm.so", "libdl.so", "liblog.so", "libandroid.so", "libz.so",
    "libEGL.so", "libGLESv2.so", "libGLESv3.so", "libvulkan.so",
}


def check_elf(args, abi, path, allowed):
    with path.open("rb") as library:
        header = library.read(20)
    assert header[:4] == b"\x7fELF", "JNI library must be ELF"
    # Never package one ABI's binary under another ABI's directory.
    assert int.from_bytes(header[18:20], "little") == ELF_MACHINES[abi], f"JNI ELF machine does not match {abi}"
    dynamic = subprocess.check_output([args.readelf, "-dW", str(path)], text=True)
    needed = re.findall(r"\(NEEDED\).*\[([^\]]+)\]", dynamic)
    assert set(needed) <= allowed, f"Unbundled native dependencies: {set(needed) - allowed}"
    segments = subprocess.check_output([args.readelf, "-lW", str(path)], text=True)
    alignments = [int(line.split()[-1], 16) for line in segments.splitlines() if line.strip().startswith("LOAD ")]
    assert alignments and min(alignments) >= 0x4000, "JNI LOAD segments must support 16-KB pages"
    return {"native_dependencies": needed, "load_segment_alignments": alignments, "sha256": sha256(path)}


def check_jni(args, abi, path, bundled):
    symbols = subprocess.check_output([args.nm, "-D", "--defined-only", str(path)], text=True)
    assert "Java_com_google_ai_edge_litertlm_LiteRtLmJni_nativeScoreDecision" in symbols, f"Unpatched JNI binary: {abi}"
    return check_elf(args, abi, path, PLATFORM_LIBRARIES | {p.name for p in bundled})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-aar", type=Path, required=True)
    parser.add_argument("--classes", type=Path, required=True)
    parser.add_argument("--jni", action="append", required=True, metavar="ABI=PATH",
                        help="Patched JNI library per ABI (arm64-v8a, x86_64); repeat for each")
    parser.add_argument("--bundle", action="append", default=[], metavar="ABI=PATH",
                        help="Verified upstream prebuilt the JNI links dynamically (open-source Bazel build)")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--upstream-revision", default="924e79c91542761242244e4f1651851f822e4cbb")
    parser.add_argument("--patch", type=Path, default=Path(__file__).with_name("litertlm-0.16.0-shared-decision.patch"))
    parser.add_argument("--nm", default="nm")
    parser.add_argument("--readelf", default="readelf")
    args = parser.parse_args()
    with zipfile.ZipFile(args.classes) as classes:
        engine = classes.read("com/google/ai/edge/litertlm/Engine.class")
        jni = classes.read("com/google/ai/edge/litertlm/LiteRtLmJni.class")
        assert b"scoreDecision" in engine and b"nativeScoreDecision" in jni, "Unpatched Kotlin classes"
    libraries = {}
    for entry in args.jni:
        abi, _, path = entry.partition("=")
        assert abi in ELF_MACHINES and path, f"Expected ABI=PATH with ABI in {sorted(ELF_MACHINES)}: {entry}"
        assert abi not in libraries, f"Duplicate ABI {abi}"
        libraries[abi] = Path(path)
    bundles = {abi: [] for abi in libraries}
    for entry in args.bundle:
        abi, _, path = entry.partition("=")
        assert abi in libraries and path, f"Bundle needs ABI=PATH for a packaged ABI: {entry}"
        bundles[abi].append(Path(path))
    checks = {abi: dict(check_jni(args, abi, path, bundles[abi]),
                        bundled={p.name: check_elf(args, abi, p, PLATFORM_LIBRARIES) for p in bundles[abi]})
              for abi, path in libraries.items()}
    output = args.output.resolve()
    assert output not in [args.base_aar.resolve(), args.classes.resolve(), *(p.resolve() for p in libraries.values())]
    assert not output.exists(), "Choose a new output path"
    output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(args.base_aar) as base, zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as out:
        for item in base.infolist():
            # Only patched ABIs ship. No mixed stock JNI under other ABIs.
            if item.filename == "classes.jar" or item.filename.startswith("jni/"):
                continue
            out.writestr(item, base.read(item.filename))
        out.write(args.classes, "classes.jar")
        for abi, path in libraries.items():
            out.write(path, f"jni/{abi}/liblitertlm_jni.so")
            for extra in bundles[abi]:
                out.write(extra, f"jni/{abi}/{extra.name}")
    manifest = {
        "upstream_revision": args.upstream_revision,
        "abis": checks,
        "inputs": {str(p): sha256(p) for p in [args.base_aar, args.classes]},
        "output": {str(output): sha256(output)},
        "patch_sha256": sha256(args.patch),
    }
    output.with_suffix(".manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print(json.dumps(manifest, indent=2))


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Package one local AAR with everything CyanBridge needs to run Qualcomm NPU models.

Contents, all from a single QAIRT version (see docs/NPU_ON_DEVICE.md):
- LiteRT CompiledModel classes (com.google.ai.edge.litert, without the AI-pack
  ModelProvider/ModelSelector/deployment classes that need Guava and Play AI
  delivery) and their natives (liblitert_jni.so, libLiteRt.so). The Interpreter
  classes are left out so the app can keep litert:1.4.2 (NNAPI/GPU delegates).
- libLiteRtDispatch_Qualcomm.so from litert-npu-runtime-qualcomm (same LiteRT
  version; LiteRT-LM Backend.NPU loads it from nativeLibraryDir too).
- QNN runtime from the QAIRT SDK the dispatch library was built against:
  libQnnHtp.so, libQnnSystem.so, libQnnHtpV<arch>Stub.so and the Hexagon
  libQnnHtpV<arch>Skel.so per requested DSP architecture.
- A manifest declaring libcdsprpc.so (FastRPC to the DSP); apps targeting API 31+
  cannot open it otherwise.

Qualcomm binaries are not committed; this script rebuilds the AAR from the
downloaded SDK. --jit adds the compiler plugin and libQnnHtpPrepare.so (~86 MB)
for on-device compilation; AOT-compiled models do not need them.
"""
import argparse
import hashlib
import io
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


ELF_MACHINES = {"arm64-v8a": 183, "x86_64": 62, "hexagon": 164}
PLATFORM_LIBRARIES = {
    "libc.so", "libm.so", "libdl.so", "liblog.so", "libandroid.so", "libz.so",
    "libEGL.so", "libGLESv2.so", "libGLESv3.so", "libvulkan.so",
}
# Vendor library reached through <uses-native-library>, present on Qualcomm phones.
VENDOR_LIBRARIES = {"libcdsprpc.so"}
# Classes that pull in Guava, coroutines-guava, lifecycle and Play AI delivery.
EXCLUDED_CLASSES = re.compile(r"com/google/ai/edge/litert/(ModelProvider|ModelSelector|AiPackModelProvider|deployment/)")
# Hexagon architectures by Snapdragon SoC (Build.SOC_MODEL).
HEXAGON_BY_SOC = {"SM8450": "v69", "SM8550": "v73", "SM8650": "v75", "SM8750": "v79", "SM8850": "v81"}
MANIFEST = """<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.fersaiyan.cyanbridge.litert.npu">
    <uses-sdk android:minSdkVersion="24" />
    <application>
        <uses-native-library android:name="libcdsprpc.so" android:required="false" />
    </application>
</manifest>
"""
PROGUARD = """# JNI creates and calls these classes by name.
-keep class com.google.ai.edge.litert.** { *; }
"""


def check_elf(args, abi, path, allowed):
    with path.open("rb") as library:
        header = library.read(20)
    assert header[:4] == b"\x7fELF", f"{path} is not ELF"
    assert int.from_bytes(header[18:20], "little") == ELF_MACHINES[abi], f"{path.name}: ELF machine does not match {abi}"
    result = {"sha256": sha256(path), "bytes": path.stat().st_size}
    if abi == "hexagon":
        # Loaded by the DSP through FastRPC, not by the Android linker.
        return result
    dynamic = subprocess.check_output([args.readelf, "-dW", str(path)], text=True)
    needed = re.findall(r"\(NEEDED\).*\[([^\]]+)\]", dynamic)
    assert set(needed) <= allowed, f"{path.name}: unbundled native dependencies {set(needed) - allowed}"
    segments = subprocess.check_output([args.readelf, "-lW", str(path)], text=True)
    alignments = [int(line.split()[-1], 16) for line in segments.splitlines() if line.strip().startswith("LOAD ")]
    assert alignments and min(alignments) >= 0x4000, f"{path.name}: LOAD segments must support 16-KB pages"
    return result | {"native_dependencies": needed, "load_segment_alignments": alignments}


def read_member(aar, name):
    with zipfile.ZipFile(aar) as archive:
        return archive.read(name)


def qairt_version(qairt_root):
    """version.build_id from sdk.yaml, e.g. 2.47.0.260601114230."""
    fields = dict(re.findall(r"^(version|build_id): *(\S+)", (qairt_root / "sdk.yaml").read_text(), re.M))
    return f"{fields['version']}.{fields['build_id']}"


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--litert-aar", type=Path, required=True, help="com.google.ai.edge.litert:litert:<version>")
    parser.add_argument("--litert-api-aar", type=Path, required=True, help="com.google.ai.edge.litert:litert-api:<version>")
    parser.add_argument("--npu-runtime-aar", type=Path, required=True,
                        help="com.google.ai.edge.litert:litert-npu-runtime-qualcomm:<version>")
    parser.add_argument("--qairt-root", type=Path, required=True, help="QAIRT SDK root (contains lib/aarch64-android)")
    parser.add_argument("--expected-qairt", required=True, help="QAIRT version the dispatch library was built against")
    parser.add_argument("--hexagon", action="append", required=True, choices=sorted(set(HEXAGON_BY_SOC.values())),
                        help="DSP architecture to include; repeat per architecture (SM8650 = v75)")
    parser.add_argument("--jit", action="store_true", help="Also ship the compiler plugin and libQnnHtpPrepare.so")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--readelf", default="readelf")
    args = parser.parse_args()

    found = qairt_version(args.qairt_root)
    # Download names shorten the build id (2.50.0.260828 vs sdk.yaml's full timestamp).
    assert found.startswith(args.expected_qairt), f"QAIRT at {args.qairt_root} is {found}, expected {args.expected_qairt}"
    output = args.output.resolve()
    assert not output.exists(), "Choose a new output path"

    allowed = PLATFORM_LIBRARIES | VENDOR_LIBRARIES
    natives = {}  # (abi, name) -> bytes
    for abi in ("arm64-v8a", "x86_64"):
        natives[abi, "libLiteRt.so"] = read_member(args.litert_aar, f"jni/{abi}/libLiteRt.so")
        natives[abi, "liblitert_jni.so"] = read_member(args.litert_api_aar, f"jni/{abi}/liblitert_jni.so")
    plugin = ["libLiteRtCompilerPlugin_Qualcomm.so"] if args.jit else []
    for name in ["libLiteRtDispatch_Qualcomm.so", *plugin]:
        natives["arm64-v8a", name] = read_member(args.npu_runtime_aar, f"jni/arm64-v8a/{name}")
    android_lib = args.qairt_root / "lib/aarch64-android"
    qnn = ["libQnnHtp.so", "libQnnSystem.so", *(["libQnnHtpPrepare.so"] if args.jit else [])]
    qnn += [f"libQnnHtp{arch.upper()}Stub.so" for arch in args.hexagon]
    files = {("arm64-v8a", name): android_lib / name for name in qnn}
    files |= {("hexagon", f"libQnnHtp{arch.upper()}Skel.so"): args.qairt_root / f"lib/hexagon-{arch}/unsigned/libQnnHtp{arch.upper()}Skel.so"
              for arch in args.hexagon}

    staging = output.with_suffix(".staging")
    staging.mkdir(parents=True, exist_ok=False)
    checks = {}
    try:
        for (abi, name), data in natives.items():
            path = staging / abi / name
            path.parent.mkdir(exist_ok=True)
            path.write_bytes(data)
            files[abi, name] = path
        for (abi, name), path in files.items():
            checks[f"{abi}/{name}"] = check_elf(args, abi, path, allowed)
        classes = io.BytesIO()
        with zipfile.ZipFile(io.BytesIO(read_member(args.litert_api_aar, "classes.jar"))) as source, \
                zipfile.ZipFile(classes, "w", zipfile.ZIP_DEFLATED) as target:
            kept = [item for item in source.infolist()
                    if item.filename.startswith("com/google/ai/edge/litert/") and not EXCLUDED_CLASSES.match(item.filename)]
            assert any(item.filename.endswith("/CompiledModel.class") for item in kept)
            for item in kept:
                target.writestr(item, source.read(item.filename))
        with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as aar:
            aar.writestr("AndroidManifest.xml", MANIFEST)
            aar.writestr("classes.jar", classes.getvalue())
            aar.writestr("R.txt", "")
            aar.writestr("proguard.txt", PROGUARD)
            for (abi, name), path in sorted(files.items()):
                # The skel sits next to the arm64 libraries: the dispatch library points
                # ADSP_LIBRARY_PATH at its own directory (nativeLibraryDir).
                aar.write(path, f"jni/arm64-v8a/{name}" if abi == "hexagon" else f"jni/{abi}/{name}")
    finally:
        for path in sorted(staging.rglob("*"), reverse=True):
            path.unlink() if path.is_file() else path.rmdir()
        staging.rmdir()
    manifest = {
        "qairt": found,
        "hexagon": args.hexagon,
        "jit": args.jit,
        "classes_kept": len(kept),
        "inputs": {str(p): sha256(p) for p in [args.litert_aar, args.litert_api_aar, args.npu_runtime_aar]},
        "natives": checks,
        "output": {str(output): sha256(output)},
    }
    output.with_suffix(".manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print(json.dumps({k: manifest[k] for k in ("qairt", "hexagon", "jit", "classes_kept", "output")}, indent=2))


if __name__ == "__main__":
    main()

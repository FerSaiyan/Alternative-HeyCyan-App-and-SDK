#!/usr/bin/env bash
# Builds CyanBridge's trimmed LibVLC AAR from the official VideoLAN sources.
#
#   ANDROID_NDK=~/Android/Sdk/ndk/27.2.12479018 ./build.sh [abi ...]
#
# Default ABIs: arm64-v8a armeabi-v7a x86 x86_64. Work tree: $VLC_TRIM_WORK
# (default ~/build/vlc-trim; ~0.7 GB per ABI). The AAR is
# written to $VLC_TRIM_OUT (default ../../app/libs/libvlc-cyan-3.7.7.aar).
# See README.md for what is kept and why.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd -P)"
: "${ANDROID_NDK:?set ANDROID_NDK to an NDK r27-r29 directory}"
WORK="${VLC_TRIM_WORK:-$HOME/build/vlc-trim}"
OUT="${VLC_TRIM_OUT:-$HERE/../../app/libs/libvlc-cyan-3.7.7.aar}"
JOBS="${VLC_TRIM_JOBS:-12}"
ABIS=("$@")
[ ${#ABIS[@]} -gt 0 ] || ABIS=(arm64-v8a armeabi-v7a x86 x86_64)

# Source revisions. LIBVLCJNI_HASH is what vlc-android tag libvlc-3.7.7
# (7a7c8f14be6759f9bb1b9a934fd086259473e20e) pins in buildsystem/compile.sh;
# VLC_HASH is what that libvlcjni revision pins in buildsystem/get-vlc.sh.
LIBVLCJNI_REPO=https://code.videolan.org/videolan/libvlcjni.git
LIBVLCJNI_HASH=0b8dc65efb203c86a0476bc337ddd99ecf1c0ef6
VLC_REPO=https://code.videolan.org/videolan/vlc.git
VLC_HASH=84177b2273abc5c4300234e6e18b55a4d2dd5a03
# The Java half (classes.jar, res, R.txt) is taken unchanged from the official
# AAR, which VideoLAN built from the same libvlcjni revision.
OFFICIAL_AAR_URL=https://repo1.maven.org/maven2/org/videolan/android/libvlc-all/3.7.7/libvlc-all-3.7.7.aar
OFFICIAL_AAR_SHA256=b48dab96e0e90e34cce3861963500c144a5aadb1b249d501d6a4b104d849e61b

# ffmpeg: decoders and parsers for the codecs the glasses and Mentra send.
export AVLC_FFMPEG_TRIM_CONF="--disable-everything --disable-autodetect \
--disable-avformat --disable-swscale --disable-swresample --disable-avfilter \
--disable-avdevice --disable-network --disable-zlib --disable-bzlib --disable-lzma \
--disable-iconv --disable-hwaccels --disable-mediacodec --disable-jni --disable-vulkan \
--enable-decoder=h264,hevc,aac,aac_latm,mp2float,mp3float,opus,mjpeg,adpcm_g726,pcm_alaw,pcm_mulaw \
--enable-parser=h264,hevc,aac,aac_latm,mpegaudio,opus,mjpeg"
export AVLC_CONTRIB_TARGETS=".ffmpeg .live555 .dvbpsi"
export AVLC_MODULES_FILE="$HERE/modules.txt"
export AVLC_ANDROID_API=28
export ANDROID_NDK

git_am() { git -c user.name=libvlc-trim -c user.email=libvlc-trim@localhost am -q --message-id "$@"; }

mkdir -p "$WORK"
cd "$WORK"

if [ ! -d libvlcjni/.git ]; then
    git init -q libvlcjni
    git -C libvlcjni fetch -q --depth 1 "$LIBVLCJNI_REPO" "$LIBVLCJNI_HASH"
    git -C libvlcjni checkout -q FETCH_HEAD
    (cd libvlcjni && git_am "$HERE"/patches/libvlcjni-*.patch)
fi
if [ ! -d libvlcjni/vlc/.git ]; then
    git init -q libvlcjni/vlc
    git -C libvlcjni/vlc fetch -q --depth 1 "$VLC_REPO" "$VLC_HASH"
    git -C libvlcjni/vlc checkout -q FETCH_HEAD
    # VideoLAN's own Android patches, then ours.
    (cd libvlcjni/vlc && git_am ../libvlc/patches/*.patch && git_am "$HERE"/patches/vlc-*.patch)
fi

STAGE="$WORK/stage"
rm -rf "$STAGE"
mkdir -p "$STAGE/jni"

for abi in "${ABIS[@]}"; do
    echo "=== $abi ==="
    (cd libvlcjni && MAKEFLAGS="-j$JOBS" ./buildsystem/compile-libvlc.sh -a "$abi" --release --license l)
    mkdir -p "$STAGE/jni/$abi"
    cp libvlcjni/libvlc/jni/libs/"$abi"/{libvlc.so,libvlcjni.so,libc++_shared.so} "$STAGE/jni/$abi/"
done

# Java half from the official AAR (Gradle cache first, else Maven Central).
official="$(find "$HOME/.gradle/caches/modules-2/files-2.1/org.videolan.android/libvlc-all/3.7.7" \
    -name libvlc-all-3.7.7.aar 2>/dev/null | head -n1 || true)"
if [ -z "$official" ]; then
    official="$WORK/libvlc-all-3.7.7.aar"
    curl -fsSL -o "$official" "$OFFICIAL_AAR_URL"
fi
echo "$OFFICIAL_AAR_SHA256  $official" | sha256sum -c -
(cd "$STAGE" && unzip -q "$official" AndroidManifest.xml R.txt classes.jar 'res/*' \
    META-INF/com/android/build/gradle/aar-metadata.properties)

# 16 KB pages: every PT_LOAD of the 64-bit libraries must be 16 KB aligned.
READELF="$ANDROID_NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
for so in "$STAGE"/jni/*/*.so; do
    bad="$("$READELF" -lW "$so" | awk '$1=="LOAD" && strtonum($NF) < 16384 {print $NF}')"
    case "$so" in
        */arm64-v8a/*|*/x86_64/*) [ -z "$bad" ] || { echo "4 KB aligned: $so ($bad)"; exit 1; } ;;
    esac
done

mkdir -p "$(dirname "$OUT")"
rm -f "$OUT"
# Deterministic zip: fixed order and timestamps.
(cd "$STAGE" && export TZ=UTC LC_ALL=C && \
    find . -type f -exec touch -d 1980-02-01T00:00:00 {} + && \
    find . -type f | sed 's|^\./||' | sort | zip -q -X -D "$OUT" -@)
ls -l "$OUT"

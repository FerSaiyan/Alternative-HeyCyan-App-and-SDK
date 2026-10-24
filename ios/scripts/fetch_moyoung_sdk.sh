#!/usr/bin/env bash
# Fetches the MoYoung glasses iOS SDK (CRPSmartGlasses) for CyanBridge device builds.
#
# The SDK ships in the published moyoung_glasses_ble_plugin package (GPL-3.0, see
# android/CyanBridge/third_party/moyoung_glasses_sdk). CRPSmartGlasses.framework links
# @rpath/AFNetworking.framework and @rpath/SwiftProtobuf.framework, which the plugin gets
# from CocoaPods; this script builds both as dynamic frameworks instead.
# The frameworks are arm64 iPhone-only, so simulator builds leave MoYoung out.
#
# usage: ios/scripts/fetch_moyoung_sdk.sh
set -euo pipefail

PLUGIN_VERSION="1.3.6"
PLUGIN_SHA256="4d2aaf0360c64af6b67f959460fff6bda808de207c27cd775961841f9be22dc3"
SWIFT_PROTOBUF_TAG="1.29.0"
AFNETWORKING_TAG="4.0.1"
MIN_IOS="17.2"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$ROOT/Vendor/MoYoung/Frameworks"
WORK="${TMPDIR:-/tmp}/cyanbridge-moyoung"
mkdir -p "$WORK" "$DEST"

# 1. Vendor frameworks from the published plugin archive.
ARCHIVE="$WORK/moyoung_glasses_ble_plugin-$PLUGIN_VERSION.tar.gz"
if [[ ! -f "$ARCHIVE" ]]; then
    curl -fsSL -o "$ARCHIVE" "https://pub.dev/api/archives/moyoung_glasses_ble_plugin-$PLUGIN_VERSION.tar.gz"
fi
echo "$PLUGIN_SHA256  $ARCHIVE" | shasum -a 256 -c -
rm -rf "$WORK/plugin" && mkdir -p "$WORK/plugin"
tar -xzf "$ARCHIVE" -C "$WORK/plugin" ios/Frameworks
for framework in CRPSmartGlasses SpeexKit JL_HashPair JL_OTALib JLLogHelper MZEncryptSDK openssl; do
    rm -rf "$DEST/$framework.framework"
    cp -R "$WORK/plugin/ios/Frameworks/$framework.framework" "$DEST/"
done

# 2. SwiftProtobuf as a dynamic framework (the version the SDK was built against).
if [[ ! -d "$WORK/swift-protobuf/.git" ]]; then
    git clone --depth 1 --branch "$SWIFT_PROTOBUF_TAG" https://github.com/apple/swift-protobuf.git "$WORK/swift-protobuf"
fi
PB="$DEST/SwiftProtobuf.framework"
rm -rf "$PB" && mkdir -p "$PB/Modules/SwiftProtobuf.swiftmodule"
SDK_PATH="$(xcrun --sdk iphoneos --show-sdk-path)"
xcrun --sdk iphoneos swiftc \
    -target "arm64-apple-ios$MIN_IOS" -sdk "$SDK_PATH" \
    -module-name SwiftProtobuf -parse-as-library -O -whole-module-optimization \
    -enable-library-evolution -swift-version 5 \
    -emit-library -o "$PB/SwiftProtobuf" \
    -emit-module-path "$PB/Modules/SwiftProtobuf.swiftmodule/arm64-apple-ios.swiftmodule" \
    -emit-module-interface-path "$PB/Modules/SwiftProtobuf.swiftmodule/arm64-apple-ios.swiftinterface" \
    -Xlinker -install_name -Xlinker @rpath/SwiftProtobuf.framework/SwiftProtobuf \
    "$WORK"/swift-protobuf/Sources/SwiftProtobuf/*.swift
/usr/libexec/PlistBuddy -c "Clear dict" \
    -c "Add :CFBundleExecutable string SwiftProtobuf" \
    -c "Add :CFBundleIdentifier string org.swift.protobuf.SwiftProtobuf" \
    -c "Add :CFBundleName string SwiftProtobuf" \
    -c "Add :CFBundlePackageType string FMWK" \
    -c "Add :CFBundleShortVersionString string $SWIFT_PROTOBUF_TAG" \
    -c "Add :CFBundleVersion string 1" \
    -c "Add :MinimumOSVersion string $MIN_IOS" \
    -c "Add :CFBundleSupportedPlatforms array" \
    -c "Add :CFBundleSupportedPlatforms:0 string iPhoneOS" \
    "$PB/Info.plist" >/dev/null 2>&1 || true

# 3. AFNetworking from its own Xcode project (dynamic framework target).
if [[ ! -d "$WORK/AFNetworking/.git" ]]; then
    git clone --depth 1 --branch "$AFNETWORKING_TAG" https://github.com/AFNetworking/AFNetworking.git "$WORK/AFNetworking"
fi
# 4.0.1 imports a header recent iOS SDKs made private (removed upstream after the release).
grep -rl '#import <netinet6/in6.h>' "$WORK/AFNetworking/AFNetworking" | while read -r source; do
    sed -i '' '/#import <netinet6\/in6.h>/d' "$source"
done
xcodebuild -quiet -project "$WORK/AFNetworking/AFNetworking.xcodeproj" -scheme "AFNetworking iOS" \
    -destination "generic/platform=iOS" -configuration Release -derivedDataPath "$WORK/afn-build" \
    IPHONEOS_DEPLOYMENT_TARGET="$MIN_IOS" CODE_SIGNING_ALLOWED=NO SKIP_INSTALL=NO build
rm -rf "$DEST/AFNetworking.framework"
cp -R "$WORK/afn-build/Build/Products/Release-iphoneos/AFNetworking.framework" "$DEST/"

echo "$PLUGIN_VERSION" > "$DEST/../moyoung.version"
echo "MoYoung SDK $PLUGIN_VERSION -> $DEST"

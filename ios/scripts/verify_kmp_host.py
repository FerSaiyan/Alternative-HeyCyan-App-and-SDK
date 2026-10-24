#!/usr/bin/env python3
"""Static checks for the KMP iOS host that are safe to run without Xcode."""

from pathlib import Path
import sys


ROOT = Path(__file__).resolve().parents[2]
PROJECT = ROOT / "ios" / "QCSDKDemo.xcodeproj" / "project.pbxproj"
HOST = ROOT / "ios" / "CyanBridgeKMPHost" / "CyanBridgeKMPHostApp.swift"
VENDOR_BRIDGE = ROOT / "ios" / "CyanBridgeKMPHost" / "QCSDKGlassesBridge.swift"
HOST_ENTITLEMENTS = ROOT / "ios" / "CyanBridgeKMPHost" / "CyanBridgeKMPHost.entitlements"
DEMO_APP_DELEGATE = ROOT / "ios" / "QCSDKDemo" / "AppDelegate.m"
SCHEME = ROOT / "ios" / "QCSDKDemo.xcodeproj" / "xcshareddata" / "xcschemes" / "CyanBridgeKMPHost.xcscheme"
DEMO_SCHEME = ROOT / "ios" / "QCSDKDemo.xcodeproj" / "xcshareddata" / "xcschemes" / "QCSDKDemo.xcscheme"
MAIN_VIEW_CONTROLLER = ROOT / "android" / "CyanBridge" / "shared" / "src" / "iosMain" / "kotlin" / "com" / "fersaiyan" / "cyanbridge" / "shared" / "platform" / "MainViewController.kt"


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def block(contents: str, marker: str) -> str:
    start = contents.find(marker)
    require(start >= 0, f"Missing project section: {marker}")
    end = contents.find("\n\t\t};", start)
    require(end >= 0, f"Unterminated project section: {marker}")
    return contents[start:end]


def main() -> int:
    project = PROJECT.read_text(encoding="utf-8")
    host = HOST.read_text(encoding="utf-8")
    host_entitlements = HOST_ENTITLEMENTS.read_text(encoding="utf-8")
    demo_app_delegate = DEMO_APP_DELEGATE.read_text(encoding="utf-8")
    scheme = SCHEME.read_text(encoding="utf-8")
    demo_scheme = DEMO_SCHEME.read_text(encoding="utf-8")

    # CMP entry point checks
    require("import CyanBridgeShared" in host, "The KMP host must import CyanBridgeShared.")
    require(
        "MainViewControllerKt" in host,
        "The KMP host must call MainViewControllerKt.MainViewController() for CMP rendering.",
    )
    require(
        "UIViewControllerRepresentable" in host,
        "The KMP host must embed the CMP UIViewController via UIViewControllerRepresentable.",
    )
    require(
        'BlueprintIdentifier = "CB2000092F00000100CB0001"' in scheme,
        "The shared Xcode scheme must build CyanBridgeKMPHost.",
    )
    for forbidden in ("import QCSDK", "CoreBluetooth", "NetworkExtension"):
        require(forbidden not in host, f"The KMP host entry point must not import vendor transport: {forbidden}")

    # QCSDK is confined to the Swift bridge and only exists on device builds.
    vendor_bridge = VENDOR_BRIDGE.read_text(encoding="utf-8")
    require("#if canImport(QCSDK)" in vendor_bridge, "The QCSDK bridge must compile behind canImport(QCSDK).")
    require("VendorGlassesBridge" in vendor_bridge, "The QCSDK bridge must implement the shared VendorGlassesBridge.")
    require("VendorGlassesSetup.register()" in host, "The KMP host must register the vendor bridge at launch.")

    # Verify the iosMain entry point exists
    require(
        MAIN_VIEW_CONTROLLER.exists(),
        f"MainViewController.kt must exist at {MAIN_VIEW_CONTROLLER}",
    )
    main_vc = MAIN_VIEW_CONTROLLER.read_text(encoding="utf-8")
    require(
        "ComposeUIViewController" in main_vc,
        "MainViewController must use ComposeUIViewController for CMP rendering.",
    )
    require(
        "CyanBridgeApp" in main_vc,
        "MainViewController must render the shared CyanBridgeApp composable.",
    )
    require(
        "MainViewControllerForDestination" in main_vc,
        "The screenshot harness must expose a destination-specific CMP entry point.",
    )
    require(
        "CYANBRIDGE_SCREENSHOT_DESTINATION" in host,
        "The Swift host must honor the screenshot-harness destination environment variable.",
    )

    host_target = block(project, 'CB2000092F00000100CB0001 /* CyanBridgeKMPHost */ = {')
    require("Build CyanBridgeShared" in host_target, "The KMP host must build the shared framework.")
    host_debug = block(project, 'CB20000A2F00000100CB0001 /* Debug */ = {')
    host_release = block(project, 'CB20000B2F00000100CB0001 /* Release */ = {')
    for configuration in (host_debug, host_release):
        require("CyanBridgeShared" in configuration, "The KMP host must link the shared framework.")
        require("xcode-frameworks" in configuration, "The KMP host must search Gradle framework output.")
        require(
            "CODE_SIGN_ENTITLEMENTS = CyanBridgeKMPHost/CyanBridgeKMPHost.entitlements" in configuration,
            "The KMP host must enable hotspot configuration and Wi-Fi information entitlements.",
        )
        require("CoreBluetooth" in configuration, "The KMP host must link CoreBluetooth for the iOS BLE adapter.")
        require("NetworkExtension" in configuration, "The KMP host must link NetworkExtension for hotspot joining.")
        require(
            '"OTHER_LDFLAGS[sdk=iphoneos*]"' in configuration and "QCSDK," in configuration,
            "The KMP host must link QCSDK for device builds only.",
        )
        unconditional_ldflags = configuration.split('"OTHER_LDFLAGS[sdk=iphoneos*]"')[0]
        require("QCSDK," not in unconditional_ldflags, "QCSDK has no simulator slice and must stay device-only.")
    require(
        "com.apple.developer.networking.HotspotConfiguration" in host_entitlements,
        "The KMP host must enable the Hotspot Configuration entitlement.",
    )
    require(
        "com.apple.developer.networking.wifi-info" in host_entitlements,
        "The KMP host must enable the Wi-Fi information entitlement for readiness checks.",
    )

    demo_target = block(project, 'AA1313562E2F903500B03938 /* QCSDKDemo */ = {')
    demo_debug = block(project, 'AA1313702E2F903600B03938 /* Debug */ = {')
    demo_release = block(project, 'AA1313712E2F903600B03938 /* Release */ = {')
    for section in (demo_target, demo_debug, demo_release, demo_app_delegate):
        require("CyanBridgeShared" not in section, "QCSDKDemo must not reference CyanBridgeShared.")
    require(
        "Embed Frameworks" not in demo_target,
        "QCSDKDemo must link, not embed, the static QCSDK.framework archive.",
    )
    require(
        "CyanBridgeSharedIntegration" not in project,
        "The legacy QCSDKDemo KMP smoke wrapper must not remain in the project.",
    )
    require(
        'BlueprintIdentifier="AA1313562E2F903500B03938"' in demo_scheme,
        "The shared QCSDKDemo scheme must build the QCSDKDemo target.",
    )
    for configuration in (demo_debug, demo_release):
        require(
            "SUPPORTED_PLATFORMS = iphoneos;" in configuration,
            "QCSDKDemo must be device-only because the vendor framework has no simulator slice.",
        )
        require(
            "NetworkExtension" in configuration,
            "QCSDKDemo must link its NetworkExtension dependency explicitly.",
        )
        require(
            '"-ObjC"' in configuration,
            "QCSDKDemo must load Objective-C categories from the static vendor archive.",
        )

    print("KMP iOS host and device-only QCSDKDemo wiring are structurally valid.")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (AssertionError, OSError) as error:
        print(f"KMP iOS host verification failed: {error}", file=sys.stderr)
        raise SystemExit(1)

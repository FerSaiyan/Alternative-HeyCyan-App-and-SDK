import CyanBridgeShared
import Foundation

/// Registers the glasses SDK bridges this build links (see SdkGlassesBridge.kt) before the shared
/// Compose controller starts. MoYoung needs ios/scripts/fetch_moyoung_sdk.sh and a device build.
enum SdkGlassesSetup {
    static func register() {
        #if canImport(CRPSmartGlasses)
        SdkGlassesRegistry.shared.register(bridge: MoyoungGlassesBridge.shared)
        #endif
        #if canImport(MWDATCore)
        MetaGlassesBridge.configure()
        SdkGlassesRegistry.shared.register(bridge: MetaGlassesBridge.shared)
        #endif
        #if canImport(MentraBluetoothSDK)
        SdkGlassesRegistry.shared.register(bridge: MentraGlassesBridge.shared)
        #endif
    }

    /// The `cyanbridge://` callbacks Meta AI sends after registration and permission requests.
    static func handle(url: URL) {
        #if canImport(MWDATCore)
        MetaGlassesBridge.handle(url: url)
        #endif
    }
}

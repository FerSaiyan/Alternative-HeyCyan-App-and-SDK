import SwiftUI
import CyanBridgeShared

@main
struct CyanBridgeKMPHostApp: App {
    init() {
        VendorGlassesSetup.register()
        SdkGlassesSetup.register()
        IosSecurityBridgeImpl.register()
        LocalModelBridgeImpl.register()
        LiteRtModelBridgeImpl.register()
        // CYANBRIDGE_LITERT_SELFTEST=1: download the catalog's LiteRT models and run each once; the report goes to
        // Documents/litert-selftest.txt and cyanbridge-debug.log.
        if ProcessInfo.processInfo.environment["CYANBRIDGE_LITERT_SELFTEST"] == "1" {
            LiteRtModelBridgeImpl.runSelfTest { report in NSLog("LITERT_SELFTEST %@", report) }
        }
        if let selfTest = ProcessInfo.processInfo.environment["CYANBRIDGE_LOCAL_MODEL_SELFTEST"], !selfTest.isEmpty {
            IosLocalModels.shared.runSelfTest(modelName: selfTest)
        }
    }

    var body: some Scene {
        WindowGroup {
            // Compose handles the keyboard (imePadding) and safe areas (WindowInsets) itself.
            // Letting SwiftUI also shrink the view above the keyboard doubled the offset and
            // pushed the chat composer to the top; it also left a black band under the tab bar.
            ComposeView()
                .ignoresSafeArea()
                .onOpenURL { url in SdkGlassesSetup.handle(url: url) }
        }
    }
}

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        if let destination = ProcessInfo.processInfo.environment["CYANBRIDGE_SCREENSHOT_DESTINATION"],
           !destination.isEmpty {
            return MainViewControllerKt.MainViewControllerForDestination(destination: destination)
        }
        return MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

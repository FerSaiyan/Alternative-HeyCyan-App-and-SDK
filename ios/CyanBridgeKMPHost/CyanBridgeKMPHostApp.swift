import SwiftUI
import CyanBridgeShared

@main
struct CyanBridgeKMPHostApp: App {
    init() {
        VendorGlassesSetup.register()
        SdkGlassesSetup.register()
        IosSecurityBridgeImpl.register()
        LocalModelBridgeImpl.register()
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

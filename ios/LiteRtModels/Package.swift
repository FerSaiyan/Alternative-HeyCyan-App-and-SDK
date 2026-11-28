// swift-tools-version: 5.9
// LiteRT 2.4.0 CompiledModel runtime (Metal GPU / XNNPACK CPU) for the CyanBridge iOS host: opens and runs the small
// LiteRT models (YOLO11n, PP-OCRv6, WeSpeaker) that the shared Kotlin code (shared/.../litert) downloads.
//
// A DYNAMIC library on purpose: LiteRT-LM's CLiteRTLM.framework (ios/LocalModels) carries its own private copy of the
// LiteRT C API with the same exported symbol names as LiteRT 2.4.0's CLiteRT.framework. Linking both straight into the
// app would let the linker bind the 2.4.0 Swift wrapper's calls to whichever framework comes first. Inside this
// framework only CLiteRT is linked, so every call binds to LiteRT 2.4.0 (two-level namespace).
import PackageDescription

let package = Package(
    name: "CyanLiteRtModels",
    platforms: [.iOS(.v15)],
    products: [
        .library(name: "CyanLiteRtModels", type: .dynamic, targets: ["CyanLiteRtModels"]),
    ],
    dependencies: [
        .package(url: "https://github.com/google-ai-edge/LiteRT-Swift", exact: "2.4.0"),
    ],
    targets: [
        .target(
            name: "CyanLiteRtModels",
            dependencies: [
                .product(name: "LiteRT", package: "LiteRT-Swift"),
                // Loaded by LiteRT with dlopen("LiteRtMetalAccelerator") for GPU runs; must be embedded in the app.
                .product(name: "LiteRtMetalAccelerator", package: "LiteRT-Swift"),
            ]
        ),
    ]
)

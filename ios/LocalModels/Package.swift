// swift-tools-version: 5.9
// On-device LLM runtimes for the CyanBridge iOS host (Android: llamacpp-kotlin + litertlm-android).
//
// - llama: Frameworks/llama.xcframework, built by ios/scripts/build_llama_xcframework.sh (not committed,
//   because llama.cpp's release zip has no iOS-simulator slice).
// - LiteRTLM: Google's prebuilt C runtime plus its Apache-2.0 Swift wrapper (Sources/LiteRTLM, copied
//   from google-ai-edge/LiteRT-LM v0.20.0 swift/, tests/BUILD/apple_fm left out). Depending on that repo
//   directly makes SwiftPM clone its multi-GB history, so only the wrapper is vendored. The wrapper does
//   `import CLiteRTLM`; that module comes from the xcframework's own Modules/module.modulemap
//   (`framework module CLiteRTLM`, umbrella header CLiteRTLM.h), so no shim target is needed. Keep the
//   wrapper and the xcframework on the same LiteRT-LM tag: the C API changes between releases.
import PackageDescription

let package = Package(
    name: "CyanLocalModels",
    platforms: [.iOS("16.4")],
    products: [
        .library(name: "CyanLocalModels", targets: ["CyanLocalModels"]),
    ],
    targets: [
        .binaryTarget(name: "llama", path: "Frameworks/llama.xcframework"),
        .binaryTarget(
            name: "CLiteRTLM",
            url: "https://github.com/google-ai-edge/LiteRT-LM/releases/download/v0.20.0/CLiteRTLM.xcframework.zip",
            checksum: "ae6b2b2ea3e46964152110ad5bd64b754f39273add2eb4cc417bbbbd72c5e6e7"
        ),
        .target(
            name: "LiteRTLM",
            dependencies: ["CLiteRTLM"],
            exclude: ["LICENSE"]
        ),
        .target(
            name: "CyanLocalModels",
            dependencies: ["LiteRTLM", "llama"]
        ),
    ]
)

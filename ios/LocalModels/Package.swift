// swift-tools-version: 5.9
// On-device LLM runtimes for the CyanBridge iOS host (Android: llamacpp-kotlin + litertlm-android).
//
// - llama: Frameworks/llama.xcframework, built by ios/scripts/build_llama_xcframework.sh (not committed,
//   because llama.cpp's release zip has no iOS-simulator slice).
// - LiteRTLM: Google's prebuilt C runtime plus its Apache-2.0 Swift wrapper (Sources/LiteRTLM, copied
//   from google-ai-edge/LiteRT-LM v0.17.1 swift/). Depending on that repo directly makes SwiftPM clone
//   its multi-GB history, so only the wrapper is vendored.
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
            url: "https://github.com/google-ai-edge/LiteRT-LM/releases/download/v0.17.1/CLiteRTLM.xcframework.zip",
            checksum: "c94fc12aa0403cb47208e419cc3bfe258214ea17035f7a63c16de536869f2186"
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

import CyanBridgeShared
import CyanLiteRtModels
import Foundation

/// Runs the small LiteRT models (YOLO11n, PP-OCRv6, WeSpeaker) for the shared `LiteRtModelBridge` with LiteRT 2.4.0
/// (ios/LiteRtModels). The shared Kotlin code (IosLiteRtModels) picks, downloads and verifies the files.
final class LiteRtModelBridgeImpl: NSObject, LiteRtModelBridge {
    static let shared = LiteRtModelBridgeImpl()

    static func register() {
        LiteRtModelRegistry.shared.bridge = shared
    }

    /// Launch-time / test entry point: downloads every iOS model of the catalog and runs each once (CPU + its
    /// accelerator). [completion] gets the report (first line PASS or FAIL) on a background thread.
    static func runSelfTest(completion: @escaping (String) -> Void) {
        register()
        IosLiteRtModels.shared.runSelfTest { report in completion(report) }
    }

    private let runner = CyanLiteRtRunner.shared
    private let errorLock = NSLock()
    private var error: String?

    private func setError(_ message: String?) {
        errorLock.lock()
        error = message
        errorLock.unlock()
    }

    func gpuAvailable() -> Bool { runner.gpuAvailable }

    func open(path: String, accelerator: String, gpuPrecision: Int32, cacheDir: String?) -> Int64 {
        do {
            let result = try runner.open(
                path: path, accelerator: accelerator, gpuPrecision: Int(gpuPrecision), cacheDir: cacheDir)
            setError(result.gpuError.map { "GPU open failed, using the CPU: \($0)" })
            return result.handle
        } catch {
            setError("\(error)")
            return 0
        }
    }

    func acceleratorOf(handle: Int64) -> String { runner.accelerator(of: handle) ?? "cpu" }

    func run(handle: Int64, input: Data) -> [Data]? {
        do {
            return try runner.run(handle: handle, input: input)
        } catch {
            setError("\(error)")
            return nil
        }
    }

    func close(handle: Int64) { runner.close(handle: handle) }

    func lastError() -> String? {
        errorLock.lock()
        defer { errorLock.unlock() }
        return error
    }
}

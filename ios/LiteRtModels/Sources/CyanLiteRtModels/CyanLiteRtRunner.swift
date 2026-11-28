// Opens and runs LiteRT 2.4.0 models by handle. Kotlin (shared/src/iosMain/.../litert/IosLiteRtModels.kt) decides which
// file, accelerator and precision; this only talks to LiteRT. The settings are the ones measured on Google Device Run
// iPhones with ios/LiteRtGpuBench (2026-11-27): Metal accelerator found without extra environment options, GPU
// options passed as the "gpu_options" TOML payload (the Swift package has no GpuOptions type in 2.4.0).

import Foundation
import LiteRT
import Metal

public enum CyanLiteRtError: Error, CustomStringConvertible {
    case badHandle(Int64)
    case badInput(String)
    case unsupported(String)

    public var description: String {
        switch self {
        case .badHandle(let h): return "no open LiteRT model with handle \(h)"
        case .badInput(let s): return s
        case .unsupported(let s): return s
        }
    }
}

public struct CyanLiteRtOpenResult {
    public let handle: Int64
    /// "gpu" or "cpu": where the model really runs.
    public let accelerator: String
    /// Why a GPU open fell back to the CPU, if it did.
    public let gpuError: String?
}

public final class CyanLiteRtRunner {
    public static let shared = CyanLiteRtRunner()

    private let lock = NSLock()
    private var sessions: [Int64: Session] = [:]
    private var nextHandle: Int64 = 1

    public init() {}

    public var gpuAvailable: Bool { MTLCreateSystemDefaultDevice() != nil }

    /// Opens [path] on "gpu" or "cpu". [gpuPrecision]: LiteRtDelegatePrecision code (0 default, 1 FP16, 2 FP32,
    /// 3 FP16 with FP32 accumulation) or -1. [cacheDir]: GPU program cache directory or nil. A GPU open that fails falls
    /// back to the CPU. Throws when the CPU open fails too.
    public func open(path: String, accelerator: String, gpuPrecision: Int, cacheDir: String?) throws -> CyanLiteRtOpenResult {
        var gpuError: String?
        var session: Session?
        if accelerator == "gpu" {
            do {
                session = try Session(path: path, gpu: true, gpuPrecision: gpuPrecision, cacheDir: cacheDir)
            } catch {
                gpuError = "\(error)"
            }
        }
        if session == nil {
            session = try Session(path: path, gpu: false, gpuPrecision: -1, cacheDir: nil)
        }
        let opened = session!
        lock.lock()
        let handle = nextHandle
        nextHandle += 1
        sessions[handle] = opened
        lock.unlock()
        return CyanLiteRtOpenResult(handle: handle, accelerator: opened.accelerator, gpuError: gpuError)
    }

    public func accelerator(of handle: Int64) -> String? {
        lock.lock()
        defer { lock.unlock() }
        return sessions[handle]?.accelerator
    }

    /// One inference: float32 bytes of input 0 -> float32 bytes per output.
    public func run(handle: Int64, input: Data) throws -> [Data] {
        lock.lock()
        let session = sessions[handle]
        lock.unlock()
        guard let session else { throw CyanLiteRtError.badHandle(handle) }
        return try session.run(input)
    }

    public func close(handle: Int64) {
        lock.lock()
        let session = sessions.removeValue(forKey: handle)
        lock.unlock()
        session?.close()
    }
}

/// One compiled model with its buffers. Buffers are released before the model and the environment (Metal resources).
private final class Session {
    let accelerator: String
    private let runLock = NSLock()
    private var environment: Environment?
    private var model: CompiledModel?
    private var inputs: [TensorBuffer] = []
    private var outputs: [TensorBuffer] = []

    init(path: String, gpu: Bool, gpuPrecision: Int, cacheDir: String?) throws {
        let env = try Environment()
        let options = try Options()
        if gpu {
            try options.setHardwareAccelerators([.gpu])
            var toml: [String] = []
            if gpuPrecision >= 0 { toml.append("precision = \(gpuPrecision)") }
            if let cacheDir {
                try? FileManager.default.createDirectory(atPath: cacheDir, withIntermediateDirectories: true)
                let size = ((try? FileManager.default.attributesOfItem(atPath: path)[.size]) as? NSNumber)?.intValue ?? 0
                toml.append("serialization_dir = \"\(cacheDir)\"")
                toml.append("model_cache_key = \"\((path as NSString).lastPathComponent)-\(size)\"")
                toml.append("serialize_program_cache = true")
            }
            if !toml.isEmpty {
                guard let payload = strdup(toml.joined(separator: "\n")) else {
                    throw CyanLiteRtError.unsupported("strdup failed")
                }
                let opaque = try OpaqueOptions(
                    identifier: "gpu_options", payload: UnsafeMutableRawPointer(payload),
                    destructor: { pointer in free(pointer) })
                try options.addOpaqueOptions(opaque)
            }
        } else {
            try options.setHardwareAccelerators([.cpu])
        }
        let compiled = try CompiledModel(filePath: path, environment: env, options: options)
        guard try compiled.inputCount() == 1 else {
            throw CyanLiteRtError.unsupported("only single-input models are supported")
        }
        guard try compiled.inputTensorType(inputIndex: 0).elementType == .float32 else {
            throw CyanLiteRtError.unsupported("only float32 inputs are supported")
        }
        for o in 0..<(try compiled.outputCount()) {
            guard try compiled.outputTensorType(outputIndex: o).elementType == .float32 else {
                throw CyanLiteRtError.unsupported("only float32 outputs are supported")
            }
        }
        environment = env
        model = compiled
        inputs = try compiled.createInputBuffers()
        outputs = try compiled.createOutputBuffers()
        accelerator = gpu ? "gpu" : "cpu"
    }

    func run(_ input: Data) throws -> [Data] {
        runLock.lock()
        defer { runLock.unlock() }
        guard let model, let buffer = inputs.first else { throw CyanLiteRtError.unsupported("model is closed") }
        guard input.count == buffer.size else {
            throw CyanLiteRtError.badInput("input has \(input.count) bytes, the model expects \(buffer.size)")
        }
        try buffer.write(input)
        try model.run(inputs: inputs, outputs: outputs)
        return try outputs.map { buffer -> Data in
            let bytes: Data = try buffer.read()
            return bytes
        }
    }

    func close() {
        runLock.lock()
        defer { runLock.unlock() }
        inputs.removeAll()
        outputs.removeAll()
        model = nil
        environment = nil
    }

    deinit {
        inputs.removeAll()
        outputs.removeAll()
        model = nil
        environment = nil
    }
}

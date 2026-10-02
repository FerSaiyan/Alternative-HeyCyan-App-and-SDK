import Foundation

/// One chat turn passed to an on-device model. Roles: "system", "user", "assistant".
public struct LocalChatMessage: Sendable {
    public let role: String
    public let content: String

    public init(role: String, content: String) {
        self.role = role
        self.content = content
    }
}

/// Sampling settings (Android: LocalGenerationSettings).
public struct LocalGenerationOptions: Sendable {
    public let maxTokens: Int
    public let temperature: Float
    public let topK: Int
    public let topP: Float
    public let repetitionPenalty: Float
    /// Negative means random.
    public let seed: Int

    public init(maxTokens: Int, temperature: Float, topK: Int, topP: Float, repetitionPenalty: Float, seed: Int) {
        self.maxTokens = max(16, maxTokens)
        self.temperature = max(0, temperature)
        self.topK = max(1, topK)
        self.topP = min(1, max(0.01, topP))
        self.repetitionPenalty = max(0.5, repetitionPenalty)
        self.seed = seed
    }
}

public enum LocalModelRuntime: String, Sendable {
    /// GGUF files run by llama.cpp.
    case llamaCpp = "gguf"
    /// .litertlm files run by Google LiteRT-LM.
    case liteRtLm = "litertlm"

    public static func forFile(_ path: String) -> LocalModelRuntime? {
        switch (path as NSString).pathExtension.lowercased() {
        case "gguf": return .llamaCpp
        case "litertlm": return .liteRtLm
        default: return nil
        }
    }
}

public enum LocalModelError: LocalizedError {
    case unsupportedFile(String)
    case loadFailed(String)
    case generationFailed(String)

    public var errorDescription: String? {
        switch self {
        case .unsupportedFile(let name): return "Unsupported model file \(name). Use a .gguf or .litertlm file."
        case .loadFailed(let reason): return "Could not load the model: \(reason)"
        case .generationFailed(let reason): return "Generation failed: \(reason)"
        }
    }
}

/// A loaded on-device text model. Calls are serialized by the caller.
public protocol LocalTextModel: AnyObject {
    var runtime: LocalModelRuntime { get }

    /// True when the model was loaded with an image encoder (for example Gemma 4 on LiteRT-LM).
    var supportsImages: Bool { get }

    /// Generates a reply to the last user message, streaming text pieces to [onToken].
    /// [imagePath] attaches a JPEG/PNG to that message when the model supports images.
    func generate(
        messages: [LocalChatMessage],
        systemPrompt: String,
        options: LocalGenerationOptions,
        imagePath: String?,
        onToken: @escaping (String) -> Void
    ) async throws -> String

    func cancel()
}

public enum LocalModelLoader {
    /// The simulator has no usable GPU path for these runtimes, so it always runs on the CPU.
    public static var gpuAvailable: Bool {
        #if targetEnvironment(simulator)
        return false
        #else
        return true
        #endif
    }

    public static func load(path: String, useGpu: Bool, contextTokens: Int) async throws -> LocalTextModel {
        guard let runtime = LocalModelRuntime.forFile(path) else {
            throw LocalModelError.unsupportedFile((path as NSString).lastPathComponent)
        }
        let gpu = useGpu && gpuAvailable
        switch runtime {
        case .llamaCpp:
            return try await Task.detached(priority: .userInitiated) {
                try LlamaTextModel(path: path, useGpu: gpu, contextTokens: contextTokens)
            }.value
        case .liteRtLm:
            return try await LiteRtTextModel.load(path: path, useGpu: gpu, contextTokens: contextTokens)
        }
    }
}

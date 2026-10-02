import CyanBridgeShared
import CyanLocalModels
import Foundation

/// Runs on-device models for the shared `LocalModelBridge` (llama.cpp + LiteRT-LM via CyanLocalModels).
final class LocalModelBridgeImpl: NSObject, LocalModelBridge {
    static let shared = LocalModelBridgeImpl()

    static func register() {
        LocalModelRegistry.shared.bridge = shared
    }

    /// Serializes load/generate so a model is never swapped mid-reply.
    private let worker = LocalModelWorker()

    func gpuAvailable() -> Bool { LocalModelLoader.gpuAvailable }

    func load(path: String, useGpu: Bool, contextTokens: Int32, completion: LocalModelResultCallback) {
        Task {
            do {
                try await worker.load(path: path, useGpu: useGpu, contextTokens: Int(contextTokens))
                await MainActor.run { completion.onResult(success: true, message: "") }
            } catch {
                let message = error.localizedDescription
                await MainActor.run { completion.onResult(success: false, message: message) }
            }
        }
    }

    func generate(
        messagesJson: String,
        systemPrompt: String,
        maxTokens: Int32,
        temperature: Double,
        topK: Int32,
        topP: Double,
        repetitionPenalty: Double,
        seed: Int32,
        imagePath: String,
        onToken: LocalModelTokenCallback,
        completion: LocalModelResultCallback
    ) {
        let messages = Self.decode(messagesJson)
        let options = LocalGenerationOptions(
            maxTokens: Int(maxTokens),
            temperature: Float(temperature),
            topK: Int(topK),
            topP: Float(topP),
            repetitionPenalty: Float(repetitionPenalty),
            seed: Int(seed)
        )
        Task {
            do {
                let reply = try await worker.generate(
                    messages: messages,
                    systemPrompt: systemPrompt,
                    options: options,
                    imagePath: imagePath.isEmpty ? nil : imagePath
                ) { piece in
                    DispatchQueue.main.async { onToken.onToken(text: piece) }
                }
                await MainActor.run { completion.onResult(success: true, message: reply) }
            } catch {
                let message = error.localizedDescription
                await MainActor.run { completion.onResult(success: false, message: message) }
            }
        }
    }

    func cancel() {
        worker.cancelCurrent()
    }

    func unload() {
        Task { await worker.unload() }
    }

    private static func decode(_ json: String) -> [LocalChatMessage] {
        guard let data = json.data(using: .utf8),
              let items = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]] else { return [] }
        return items.compactMap { item in
            guard let role = item["role"] as? String, let content = item["content"] as? String else { return nil }
            return LocalChatMessage(role: role, content: content)
        }
    }
}

private actor LocalModelWorker {
    private var model: LocalTextModel?
    private var path: String?
    private var onGpu = false
    nonisolated(unsafe) private var active: LocalTextModel?

    func load(path: String, useGpu: Bool, contextTokens: Int) async throws {
        if self.path == path, onGpu == useGpu, model != nil { return }
        model = nil
        active = nil
        self.path = nil
        let loaded = try await LocalModelLoader.load(path: path, useGpu: useGpu, contextTokens: contextTokens)
        model = loaded
        self.path = path
        onGpu = useGpu
    }

    func generate(
        messages: [LocalChatMessage],
        systemPrompt: String,
        options: LocalGenerationOptions,
        imagePath: String?,
        onToken: @escaping (String) -> Void
    ) async throws -> String {
        guard let model else { throw LocalModelError.generationFailed("no model is loaded") }
        active = model
        defer { active = nil }
        return try await model.generate(
            messages: messages,
            systemPrompt: systemPrompt,
            options: options,
            imagePath: imagePath,
            onToken: onToken
        )
    }

    nonisolated func cancelCurrent() {
        active?.cancel()
    }

    func unload() {
        model = nil
        active = nil
        path = nil
    }
}

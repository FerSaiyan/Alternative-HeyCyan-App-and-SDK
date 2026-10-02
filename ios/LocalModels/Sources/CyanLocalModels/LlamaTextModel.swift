import Foundation
import llama

/// GGUF models through the llama.cpp C API (Android: LlamaCppLocalInferenceEngine).
final class LlamaTextModel: LocalTextModel, @unchecked Sendable {
    let runtime = LocalModelRuntime.llamaCpp
    /// CyanBridge's llama.cpp path is text-only (no mtmd projector yet).
    let supportsImages = false

    private static let backendInit: Void = llama_backend_init()

    private let model: OpaquePointer
    private let context: OpaquePointer
    private let vocab: OpaquePointer
    private let batchSize: Int32
    private let lock = NSLock()
    private var cancelled = false

    init(path: String, useGpu: Bool, contextTokens: Int) throws {
        _ = Self.backendInit
        var modelParams = llama_model_default_params()
        modelParams.n_gpu_layers = useGpu ? 99 : 0
        guard let model = llama_model_load_from_file(path, modelParams) else {
            throw LocalModelError.loadFailed("llama.cpp rejected \((path as NSString).lastPathComponent)")
        }
        var contextParams = llama_context_default_params()
        let batchSize = Int32(min(contextTokens, 512))
        contextParams.n_ctx = UInt32(contextTokens)
        contextParams.n_batch = UInt32(batchSize)
        let threads = Int32(max(1, min(4, ProcessInfo.processInfo.activeProcessorCount - 2)))
        contextParams.n_threads = threads
        contextParams.n_threads_batch = threads
        guard let context = llama_init_from_model(model, contextParams) else {
            llama_model_free(model)
            throw LocalModelError.loadFailed("not enough memory for a \(contextTokens)-token context")
        }
        guard let vocab = llama_model_get_vocab(model) else {
            llama_free(context)
            llama_model_free(model)
            throw LocalModelError.loadFailed("the model has no vocabulary")
        }
        self.model = model
        self.context = context
        self.vocab = vocab
        self.batchSize = batchSize
    }

    deinit {
        llama_free(context)
        llama_model_free(model)
    }

    func cancel() {
        lock.lock()
        cancelled = true
        lock.unlock()
    }

    private var isCancelled: Bool {
        lock.lock()
        defer { lock.unlock() }
        return cancelled
    }

    func generate(
        messages: [LocalChatMessage],
        systemPrompt: String,
        options: LocalGenerationOptions,
        imagePath: String?,
        onToken: @escaping (String) -> Void
    ) async throws -> String {
        if imagePath != nil {
            throw LocalModelError.generationFailed("llama.cpp models in CyanBridge are text-only. Use a LiteRT-LM vision model such as Gemma 4.")
        }
        return try await Task.detached(priority: .userInitiated) { [self] in
            try generateBlocking(messages: messages, systemPrompt: systemPrompt, options: options, onToken: onToken)
        }.value
    }

    private func generateBlocking(
        messages: [LocalChatMessage],
        systemPrompt: String,
        options: LocalGenerationOptions,
        onToken: (String) -> Void
    ) throws -> String {
        lock.lock()
        cancelled = false
        lock.unlock()

        let contextSize = Int(llama_n_ctx(context))
        let replyBudget = max(16, min(options.maxTokens, contextSize / 2))
        // Drop the oldest turns until the prompt leaves room for the reply.
        var turns = messages
        var tokens = tokenize(formatPrompt(turns, systemPrompt: systemPrompt))
        while tokens.count + replyBudget > contextSize && turns.count > 1 {
            turns.removeFirst()
            tokens = tokenize(formatPrompt(turns, systemPrompt: systemPrompt))
        }
        guard !tokens.isEmpty, tokens.count + replyBudget <= contextSize else {
            throw LocalModelError.generationFailed("the message is too long for this model's context")
        }

        llama_memory_clear(llama_get_memory(context), true)
        var offset = 0
        while offset < tokens.count {
            let count = min(Int(batchSize), tokens.count - offset)
            let status = tokens.withUnsafeMutableBufferPointer { buffer in
                llama_decode(context, llama_batch_get_one(buffer.baseAddress! + offset, Int32(count)))
            }
            guard status == 0 else { throw LocalModelError.generationFailed("prompt decode returned \(status)") }
            offset += count
        }

        let sampler = makeSampler(options)
        defer { llama_sampler_free(sampler) }
        var reply = ""
        var pendingBytes: [UInt8] = []
        for _ in 0..<replyBudget {
            if isCancelled { break }
            var token = llama_sampler_sample(sampler, context, -1)
            if llama_vocab_is_eog(vocab, token) { break }
            pendingBytes += piece(for: token)
            // Tokens can split a UTF-8 character; emit only complete text.
            if let text = String(bytes: pendingBytes, encoding: .utf8) {
                reply += text
                onToken(text)
                pendingBytes.removeAll()
            } else if pendingBytes.count > 8 {
                let text = String(decoding: pendingBytes, as: UTF8.self)
                reply += text
                onToken(text)
                pendingBytes.removeAll()
            }
            let status = llama_decode(context, llama_batch_get_one(&token, 1))
            guard status == 0 else { break }
        }
        return reply
    }

    private func makeSampler(_ options: LocalGenerationOptions) -> UnsafeMutablePointer<llama_sampler> {
        let chain = llama_sampler_chain_init(llama_sampler_chain_default_params())!
        if options.repetitionPenalty != 1 {
            llama_sampler_chain_add(
                chain,
                llama_sampler_init_penalties(llama_vocab_n_tokens(vocab), 64, options.repetitionPenalty, 0, 0)
            )
        }
        llama_sampler_chain_add(chain, llama_sampler_init_top_k(Int32(options.topK)))
        llama_sampler_chain_add(chain, llama_sampler_init_top_p(options.topP, 1))
        llama_sampler_chain_add(chain, llama_sampler_init_temp(options.temperature))
        let seed = options.seed >= 0 ? UInt32(truncatingIfNeeded: options.seed) : UInt32.random(in: 0...UInt32.max)
        llama_sampler_chain_add(chain, llama_sampler_init_dist(seed))
        return chain
    }

    /// Applies the model's own chat template (falls back to ChatML when it has none).
    private func formatPrompt(_ messages: [LocalChatMessage], systemPrompt: String) -> String {
        var turns = messages
        if !systemPrompt.isEmpty && turns.first?.role != "system" {
            turns.insert(LocalChatMessage(role: "system", content: systemPrompt), at: 0)
        }
        let roles = turns.map { strdup($0.role) }
        let contents = turns.map { strdup($0.content) }
        defer {
            roles.forEach { free($0) }
            contents.forEach { free($0) }
        }
        let chat = zip(roles, contents).map { llama_chat_message(role: $0, content: $1) }
        let template = llama_model_chat_template(model, nil)
        var capacity = max(1024, turns.reduce(0) { $0 + $1.content.utf8.count } * 2 + 512)
        for _ in 0..<2 {
            var buffer = [CChar](repeating: 0, count: capacity)
            let written = chat.withUnsafeBufferPointer { chatBuffer in
                llama_chat_apply_template(template, chatBuffer.baseAddress, chat.count, true, &buffer, Int32(capacity))
            }
            if written < 0 { break }
            if Int(written) <= capacity {
                return String(decoding: buffer.prefix(Int(written)).map { UInt8(bitPattern: $0) }, as: UTF8.self)
            }
            capacity = Int(written) + 1
        }
        // Unknown template: plain transcript.
        return turns.map { "\($0.role): \($0.content)" }.joined(separator: "\n") + "\nassistant:"
    }

    private func tokenize(_ text: String) -> [llama_token] {
        let utf8Count = Int32(text.utf8.count)
        var tokens = [llama_token](repeating: 0, count: Int(utf8Count) + 16)
        var count = llama_tokenize(vocab, text, utf8Count, &tokens, Int32(tokens.count), true, true)
        if count < 0 {
            tokens = [llama_token](repeating: 0, count: Int(-count))
            count = llama_tokenize(vocab, text, utf8Count, &tokens, Int32(tokens.count), true, true)
        }
        return count > 0 ? Array(tokens.prefix(Int(count))) : []
    }

    private func piece(for token: llama_token) -> [UInt8] {
        var buffer = [CChar](repeating: 0, count: 64)
        var length = llama_token_to_piece(vocab, token, &buffer, Int32(buffer.count), 0, false)
        if length < 0 {
            buffer = [CChar](repeating: 0, count: Int(-length))
            length = llama_token_to_piece(vocab, token, &buffer, Int32(buffer.count), 0, false)
        }
        return length > 0 ? buffer.prefix(Int(length)).map { UInt8(bitPattern: $0) } : []
    }
}

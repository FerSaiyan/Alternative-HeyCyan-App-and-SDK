package com.fersaiyan.cyanbridge.plugins.orallesion

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.fersaiyan.cyanbridge.localmodels.session.LocalChatSessionManager
import com.fersaiyan.cyanbridge.shared.plugins.orallesion.OralLesionTurn
import com.fersaiyan.cyanbridge.shared.plugins.orallesion.OralLesionTurnMetrics
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The bench's own LiteRT-LM engine, separate from the chat model so the prompts reach the model
 * exactly as trained: no app system prompt, thinking off, greedy decoding (top-k 1), and LiteRT-LM's
 * benchmark counters on for the per-turn prefill/decode rates. GPU first (text and vision); CPU only
 * when the GPU cannot start, and the backend used is recorded with each case. The chat model is
 * unloaded first: two Gemma 4 engines do not fit in a phone's memory.
 */
@OptIn(ExperimentalApi::class)
internal class OralLesionEngine(private val context: Context) {
    private val lock = Mutex()
    private var engine: Engine? = null
    private var enginePath: String? = null
    private var backend = ""
    @Volatile private var conversation: Conversation? = null

    /** A new conversation with [system], the photo and [user]; it stays open for [continueConversation]. */
    suspend fun start(modelPath: String, imagePath: String, system: String, user: String, onToken: (String) -> Unit): OralLesionTurn =
        lock.withLock {
            withContext(Dispatchers.IO) {
                val llm = ensureEngine(modelPath)
                closeConversation()
                val image = File(imagePath)
                require(image.isFile && image.length() > 0) { "Photo is missing: $imagePath" }
                val opened = llm.createConversation(
                    ConversationConfig(
                        systemInstruction = Contents.of(system),
                        samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 1.0, seed = 0),
                        automaticToolCalling = false,
                        thinkingConfig = ThinkingConfig(enableThinking = false),
                    ),
                )
                conversation = opened
                send(opened, Contents.of(listOf(Content.ImageFile(image.absolutePath), Content.Text(user))), onToken)
            }
        }

    /** The next user turn of the open conversation. */
    suspend fun continueConversation(user: String, onToken: (String) -> Unit): OralLesionTurn = lock.withLock {
        withContext(Dispatchers.IO) {
            val open = conversation ?: error("The consultation was closed; assess the photo again")
            send(open, Contents.of(Content.Text(user)), onToken)
        }
    }

    fun cancel() {
        runCatching { conversation?.cancelProcess() }
    }

    suspend fun close() = lock.withLock {
        withContext(Dispatchers.IO) {
            closeConversation()
            runCatching { engine?.close() }
            engine = null
            enginePath = null
        }
    }

    private suspend fun ensureEngine(modelPath: String): Engine {
        engine?.takeIf { enginePath == modelPath }?.let { return it }
        closeConversation()
        runCatching { engine?.close() }
        engine = null
        runCatching { LocalChatSessionManager.unload() }.onFailure { Log.w(TAG, "Could not unload the chat model", it) }
        val created = runCatching { create(modelPath, Backend.GPU(), Backend.GPU()).also { backend = "GPU" } }
            .recoverCatching { gpuError ->
                Log.w(TAG, "GPU engine failed, using CPU: ${gpuError.message}", gpuError)
                create(modelPath, Backend.CPU(CPU_THREADS), Backend.CPU(CPU_THREADS)).also { backend = "CPU (GPU failed: ${gpuError.message?.take(120)})" }
            }.getOrThrow()
        engine = created
        enginePath = modelPath
        Log.i(TAG, "Oral lesion engine ready backend=$backend model=${File(modelPath).name}")
        return created
    }

    private fun create(modelPath: String, backend: Backend, vision: Backend): Engine {
        val config = EngineConfig(
            modelPath = modelPath,
            backend = backend,
            visionBackend = vision,
            audioBackend = null,
            maxNumTokens = MAX_TOKENS,
            cacheDir = context.cacheDir.path,
        )
        synchronized(ExperimentalFlags) {
            val previous = ExperimentalFlags.enableBenchmark
            try {
                ExperimentalFlags.enableBenchmark = true
                return Engine(config).also { it.initialize() }
            } finally {
                ExperimentalFlags.enableBenchmark = previous
            }
        }
    }

    private suspend fun send(open: Conversation, contents: Contents, onToken: (String) -> Unit): OralLesionTurn {
        val started = SystemClock.elapsedRealtime()
        var firstTokenAt = -1L
        val text = StringBuilder()
        open.sendMessageAsync(contents, emptyMap<String, Any>(), maxOutputToken = MAX_OUTPUT_TOKENS).collect { message ->
            val current = extractText(message)
            if (current.isEmpty()) return@collect
            val delta = incrementalDelta(text.toString(), current)
            if (delta.isEmpty()) return@collect
            if (firstTokenAt < 0) firstTokenAt = SystemClock.elapsedRealtime()
            text.append(delta)
            onToken(delta)
        }
        val latency = SystemClock.elapsedRealtime() - started
        val bench = runCatching { open.getBenchmarkInfo() }.getOrNull()
        val metrics = OralLesionTurnMetrics(
            turn = "",
            latencyMs = latency,
            ttftMs = if (firstTokenAt >= 0) firstTokenAt - started else null,
            prefillTokens = bench?.lastPrefillTokenCount,
            decodeTokens = bench?.lastDecodeTokenCount,
            prefillTokensPerSecond = bench?.lastPrefillTokensPerSecond,
            decodeTokensPerSecond = bench?.lastDecodeTokensPerSecond,
        )
        Log.i(TAG, "turn done backend=$backend latencyMs=$latency ttftMs=${metrics.ttftMs} prefill=${metrics.prefillTokensPerSecond} decode=${metrics.decodeTokensPerSecond}")
        return OralLesionTurn(text.toString().trim(), metrics, backend)
    }

    private fun closeConversation() {
        runCatching { conversation?.close() }
        conversation = null
    }

    private fun extractText(message: Message): String =
        message.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }

    /** LiteRT-LM streams deltas; some versions repeat the text so far. Returns only the new part. */
    private fun incrementalDelta(previous: String, current: String): String = when {
        previous.isEmpty() -> current
        current.startsWith(previous) -> current.substring(previous.length)
        previous.startsWith(current) -> ""
        else -> current
    }

    companion object {
        private const val TAG = "OralLesionEngine"
        private const val MAX_TOKENS = 4096
        private const val MAX_OUTPUT_TOKENS = 768
        private const val CPU_THREADS = 4
    }
}

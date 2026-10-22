package com.fersaiyan.cyanbridge.hil

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ToolProvider
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Opt-in long-context probe for LiteRT-LM bundles (docs/NPU_ON_DEVICE.md): a multi-turn chat
 * that crosses the first KV bucket mid-conversation, as multi-bucket NPU packages (4k + 16k)
 * must switch buckets and copy the KV cache there.
 *
 * Turn 1 plants a code word; turn 2 sends a long filler document (~12 tokens per note) with a
 * second fact in the middle and asks for the code word; turn 3 asks for the mid-document fact.
 * Greedy decoding. Logs per-turn time to first token, total time and answers (LC_PROBE lines);
 * it does not assert answers, so it can also measure models that fail them.
 *
 * Arguments:
 *   -e lc_model   <device path to a .litertlm>
 *   -e lc_backend NPU | GPU | CPU (default NPU)
 *   -e lc_notes   <filler notes, default 400 ≈ 4.8k tokens>
 *   -e lc_max_tokens <engine max tokens, default 16384>
 *   -e lc_max_decode <stop each answer after this many streamed chunks, default 48>
 *   -e lc_needle_file <optional jsonl (id, tokens, depth, answer, prompt): one fresh conversation
 *                      per row, answers capped at 16 chunks, scored by whether they contain
 *                      `answer`; rows longer than lc_max_tokens are skipped. E2B misses some
 *                      needles even in FP32, so compare LC_NEEDLE lines with the recipe's
 *                      FP32 reference (needle_eval/host/hf_ref.log) rather than raw hits>
 */
@RunWith(AndroidJUnit4::class)
class LiteRtLongContextProbeTest {
    private val args = InstrumentationRegistry.getArguments()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val maxDecode get() = args.getString("lc_max_decode")?.toInt() ?: 48

    @Test
    fun multiTurnChatAcrossTheFirstKvBucket() = runBlocking {
        val model = args.getString("lc_model").orEmpty()
        assumeTrue("pass -e lc_model", model.isNotBlank() && File(model).isFile)
        assumeTrue("needle mode runs in needleEval", args.getString("lc_needle_file") == null)
        val backendName = args.getString("lc_backend") ?: "NPU"
        val notes = args.getString("lc_notes")?.toInt() ?: 400
        val backend = when (backendName) {
            "NPU" -> Backend.NPU(context.applicationInfo.nativeLibraryDir)
            "GPU" -> Backend.GPU()
            else -> Backend.CPU()
        }
        val initStart = SystemClock.elapsedRealtime()
        val engine = Engine(
            EngineConfig(
                modelPath = model,
                backend = backend,
                maxNumTokens = args.getString("lc_max_tokens")?.toInt() ?: 16384,
                cacheDir = context.cacheDir.path,
            ),
        ).also { it.initialize() }
        println("LC_PROBE init backend=$backendName model=${File(model).name} ms=${SystemClock.elapsedRealtime() - initStart}")
        engine.use {
            it.createConversation(
                ConversationConfig(
                    systemInstruction = Contents.of(""),
                    initialMessages = emptyList<Message>(),
                    tools = emptyList<ToolProvider>(),
                    samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0, seed = 0),
                    automaticToolCalling = false,
                ),
            ).use { conversation ->
                turn(conversation, 1, "Remember this code word for later: AMBER-7413. Reply only with OK.")
                val filler = buildString {
                    appendLine("Here is a long maintenance log. Read it, then answer the question at the end.")
                    for (n in 1..notes) {
                        if (n == notes / 2) appendLine("Note $n: the inspector wrote that the secret city is Lisbon.")
                        else appendLine("Note $n: station ${n % 17} logged routine readings and no faults were found.")
                    }
                    append("Question: what was the code word I asked you to remember at the very start? Reply with the code word only.")
                }
                turn(conversation, 2, filler)
                turn(conversation, 3, "According to the maintenance log, which city was the secret city? Reply with the city only.")
            }
        }
    }

    @Test
    fun needleEval() = runBlocking {
        val model = args.getString("lc_model").orEmpty()
        val needles = args.getString("lc_needle_file").orEmpty()
        assumeTrue("pass -e lc_model and -e lc_needle_file", File(model).isFile && File(needles).isFile)
        val backendName = args.getString("lc_backend") ?: "NPU"
        val maxTokens = args.getString("lc_max_tokens")?.toInt() ?: 16384
        val engine = Engine(
            EngineConfig(
                modelPath = model,
                backend = when (backendName) {
                    "NPU" -> Backend.NPU(context.applicationInfo.nativeLibraryDir)
                    "GPU" -> Backend.GPU()
                    else -> Backend.CPU()
                },
                maxNumTokens = maxTokens,
                cacheDir = context.cacheDir.path,
            ),
        ).also { it.initialize() }
        var hits = 0
        var total = 0
        engine.use {
            File(needles).forEachLine { line ->
                if (line.isBlank()) return@forEachLine
                val row = org.json.JSONObject(line)
                if (row.getInt("tokens") + 32 > maxTokens) return@forEachLine
                // Rows are chat-formatted; the conversation applies the template itself.
                val prompt = row.getString("prompt").removePrefix("<|turn>user\n").removeSuffix("<turn|>\n<|turn>model\n")
                val answer = runBlocking {
                    it.createConversation(
                        ConversationConfig(
                            systemInstruction = Contents.of(""),
                            initialMessages = emptyList<Message>(),
                            tools = emptyList<ToolProvider>(),
                            samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0, seed = 0),
                            automaticToolCalling = false,
                        ),
                    ).use { conversation -> turn(conversation, total + 1, prompt, limit = 16, label = row.getString("id")) }
                }
                val hit = answer.contains(row.getString("answer"))
                if (hit) hits++
                total++
                println("LC_NEEDLE id=${row.getString("id")} tokens=${row.getInt("tokens")} depth=${row.get("depth")} hit=$hit")
            }
        }
        println("LC_NEEDLE summary backend=$backendName model=${File(model).name} hits=$hits/$total")
    }

    private suspend fun turn(conversation: Conversation, index: Int, text: String, limit: Int = maxDecode, label: String = ""): String {
        val start = SystemClock.elapsedRealtime()
        var firstTokenMs = -1L
        val answer = StringBuilder()
        conversation.sendMessageAsync(Contents.of(Content.Text(text)), emptyMap<String, Any>()).take(limit).collect { message ->
            val chunk = message.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
            if (chunk.isNotEmpty() && firstTokenMs < 0) firstTokenMs = SystemClock.elapsedRealtime() - start
            // Streams may be cumulative or incremental; keep the longest consistent text.
            if (chunk.startsWith(answer)) { answer.setLength(0); answer.append(chunk) } else answer.append(chunk)
        }
        println("LC_PROBE turn=$index $label inputChars=${text.length} ttftMs=$firstTokenMs totalMs=${SystemClock.elapsedRealtime() - start} " +
            "answer=${answer.toString().trim().replace("\n", " ").take(120)}")
        return answer.toString()
    }
}

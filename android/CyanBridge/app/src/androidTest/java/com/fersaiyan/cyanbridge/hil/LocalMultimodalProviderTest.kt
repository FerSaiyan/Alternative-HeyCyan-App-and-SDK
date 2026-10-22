package com.fersaiyan.cyanbridge.hil

import android.content.Context
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fersaiyan.cyanbridge.localmodels.catalog.LocalModelCatalogRepository
import com.fersaiyan.cyanbridge.localmodels.provider.LocalModelsProvider
import com.fersaiyan.cyanbridge.localmodels.remote.RemoteOpenAiPrefs
import com.fersaiyan.cyanbridge.localmodels.session.LocalChatSessionManager
import com.fersaiyan.cyanbridge.localmodels.settings.LocalComputeBackend
import com.fersaiyan.cyanbridge.localmodels.settings.LocalGenerationSettings
import com.fersaiyan.cyanbridge.localmodels.settings.LocalModelRuntime
import com.fersaiyan.cyanbridge.localmodels.settings.LocalModelSettingsRepository
import com.fersaiyan.cyanbridge.localmodels.storage.InstalledLocalModel
import com.fersaiyan.cyanbridge.localmodels.storage.LocalModelProjectorStore
import com.fersaiyan.cyanbridge.localmodels.storage.LocalModelStorageRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Image and audio questions through the app's own path ([LocalModelsProvider.streamChat], as
 * the glasses image/voice flow calls it) for one local model: a llama.cpp GGUF with its
 * mmproj projector, a LiteRT-LM package on CPU/GPU, or an NPU-compiled LiteRT-LM package.
 *
 * The model under test is the only installed model while the test runs, so media routing
 * cannot pick another one. The model registry, projector and per-model settings preferences
 * are snapshotted and restored afterwards, and no model file is deleted, so it is safe on a
 * phone with real data.
 *
 * Arguments:
 *   -e mm_model     <device path to a .gguf / .litertlm>
 *   -e mm_runtime   LLAMA_CPP | LITERT (default: from the file extension)
 *   -e mm_projector <mmproj .gguf, llama.cpp only>
 *   -e mm_backend   CPU | GPU | NPU (default CPU)
 *   -e mm_catalog_id <catalog id to register under, e.g. gemma4-e2b-it-npu> (optional)
 *   -e mm_image     <jpg/png>      -e mm_image_expect <comma-separated words, any match; default bus>
 *   -e mm_audio     <16 kHz wav>   -e mm_audio_expect <comma-separated words, all must match; default like,red,flower>
 *   -e mm_context   <context tokens, default 4096>
 *   -e mm_order     image_first (default) | audio_first | audio_image_audio
 *   -e mm_system    <system prompt override; "none" for empty; default: the app's default>
 *   -e mm_audio_prompt <audio question, default DEFAULT_AUDIO_QUESTION>
 * Logs MM_PROBE lines with load/answer times and the answers.
 */
@RunWith(AndroidJUnit4::class)
class LocalMultimodalProviderTest {
    private val args = InstrumentationRegistry.getArguments()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun imageAndAudioQuestionsThroughTheProvider() = runBlocking {
        val modelPath = args.getString("mm_model").orEmpty()
        assumeTrue("pass -e mm_model", modelPath.isNotBlank() && File(modelPath).isFile)
        assumeFalse("a remote OpenAI server is active; local routing would be skipped", RemoteOpenAiPrefs.isActive(context))
        val image = args.getString("mm_image")?.takeIf { File(it).isFile }
        val audio = args.getString("mm_audio")?.takeIf { File(it).isFile }
        assumeTrue("pass -e mm_image and/or -e mm_audio", image != null || audio != null)
        val runtime = args.getString("mm_runtime")?.let(LocalModelRuntime::valueOf)
            ?: if (modelPath.endsWith(".gguf", true)) LocalModelRuntime.LLAMA_CPP else LocalModelRuntime.LITERT
        val backend = when (args.getString("mm_backend") ?: "CPU") {
            "NPU" -> LocalComputeBackend.NPU_EXPERIMENTAL
            "GPU" -> LocalComputeBackend.GPU
            else -> LocalComputeBackend.CPU
        }

        val saved = PREFS.associateWith { snapshot(it) }
        try {
            PREFS.forEach { context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
            val model = register(File(modelPath))
            args.getString("mm_projector")?.let { LocalModelProjectorStore.attach(context, model, File(it)) }
            val entry = LocalModelCatalogRepository.findById(model.catalogId)
            LocalModelSettingsRepository.saveForModel(
                context,
                model.id,
                LocalGenerationSettings.defaultsFor(entry).copy(
                    modelRuntime = runtime,
                    computeBackend = backend,
                    contextSize = args.getString("mm_context")?.toInt() ?: 4096,
                    temperature = 0.0,
                    topK = 1,
                    seed = 0,
                    maxTokens = 64,
                ).let { base ->
                    args.getString("mm_system")?.let { base.copy(systemPromptOverride = if (it == "none") "" else it) } ?: base
                },
            )
            println("MM_PROBE model=${model.fileName} runtime=$runtime backend=$backend projector=${args.getString("mm_projector")}")

            val provider = LocalModelsProvider()
            val loadStart = SystemClock.elapsedRealtime()
            val load = provider.prepareSelectedModel(context)
            println("MM_PROBE load active=${load?.activeBackend} fallback=${load?.fallbackReason} ms=${SystemClock.elapsedRealtime() - loadStart}")
            if (backend == LocalComputeBackend.NPU_EXPERIMENTAL) {
                assertEquals("NPU model must not fall back", LocalComputeBackend.NPU_EXPERIMENTAL, load?.activeBackend)
            }

            val failures = mutableListOf<String>()
            val askImage = suspend {
                image?.let {
                    val answer = ask(provider, "What kind of vehicle is in this picture? Answer in a few words.", listOf(it), null)
                    val expected = words("mm_image_expect", "bus")
                    if (expected.none { w -> answer.contains(w, ignoreCase = true) }) failures += "image answer lacks any of $expected: $answer"
                }
            }
            val askAudio = suspend {
                audio?.let {
                    val answer = ask(provider, args.getString("mm_audio_prompt") ?: DEFAULT_AUDIO_QUESTION, emptyList(), it)
                    val expected = words("mm_audio_expect", "like,red,flower")
                    if (!expected.all { w -> answer.contains(w, ignoreCase = true) }) failures += "audio answer lacks $expected: $answer"
                }
            }
            when (args.getString("mm_order")) {
                "audio_first" -> { askAudio(); askImage() }
                "audio_image_audio" -> { askAudio(); askImage(); askAudio() }
                else -> { askImage(); askAudio() }
            }
            assertEquals("the loaded model is the one under test", model.id, LocalChatSessionManager.snapshot().loadedModelId)
            assertTrue(failures.joinToString("\n"), failures.isEmpty())
        } finally {
            runCatching { LocalChatSessionManager.unload() }
            saved.forEach { (name, values) -> restore(name, values) }
        }
    }

    private suspend fun ask(provider: LocalModelsProvider, question: String, images: List<String>, audio: String?): String {
        val start = SystemClock.elapsedRealtime()
        var firstTokenMs = -1L
        val answer = provider.streamChat(
            context = context,
            messages = listOf(mapOf("role" to "user", "content" to question)),
            onToken = { if (firstTokenMs < 0) firstTokenMs = SystemClock.elapsedRealtime() - start },
            imagePaths = images,
            audioPath = audio,
            maxTokens = 64,
        )
        println(
            "MM_PROBE ${if (audio != null) "audio" else "image"} ttftMs=$firstTokenMs totalMs=${SystemClock.elapsedRealtime() - start} " +
                "answer=${answer.trim().replace("\n", " ").take(200)}",
        )
        return answer
    }

    /** Registers [file] in place (nothing is copied or deleted), under a catalog id when given. */
    private fun register(file: File): InstalledLocalModel {
        val catalogEntry = LocalModelCatalogRepository.findById(args.getString("mm_catalog_id"))
        return if (catalogEntry != null) LocalModelStorageRepository.registerCatalogModel(context, catalogEntry, file)
        else LocalModelStorageRepository.registerImportedModel(context, file.nameWithoutExtension, file)
    }

    private fun words(key: String, default: String) = (args.getString(key) ?: default).split(',').map { it.trim() }.filter { it.isNotEmpty() }

    private fun snapshot(name: String): Map<String, *> = context.getSharedPreferences(name, Context.MODE_PRIVATE).all.toMap()

    @Suppress("UNCHECKED_CAST")
    private fun restore(name: String, values: Map<String, *>) {
        val editor = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
        values.forEach { (key, value) ->
            when (value) {
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Set<*> -> editor.putStringSet(key, value as Set<String>)
            }
        }
        editor.commit()
    }

    private companion object {
        val PREFS = listOf("local_models_registry", "local_model_projectors", "local_model_settings")

        /** A natural voice-flow question; "transcribe exactly" plus the terse app system prompt makes E2B refuse. */
        const val DEFAULT_AUDIO_QUESTION = "What does the speaker say in this recording? Quote it."
    }
}

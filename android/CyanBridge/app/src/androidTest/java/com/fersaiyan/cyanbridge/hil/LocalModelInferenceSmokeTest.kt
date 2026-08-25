package com.fersaiyan.cyanbridge.hil

import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fersaiyan.cyanbridge.localmodels.catalog.LocalModelCatalogRepository
import com.fersaiyan.cyanbridge.localmodels.provider.LocalModelsProvider
import com.fersaiyan.cyanbridge.localmodels.settings.LocalComputeBackend
import com.fersaiyan.cyanbridge.localmodels.settings.LocalModelSettingsRepository
import com.fersaiyan.cyanbridge.localmodels.storage.LocalModelStorageRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class LocalModelInferenceSmokeTest {
    @Test
    fun qwenGeneratesOnDevice() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val entry = requireNotNull(LocalModelCatalogRepository.findById(MODEL_ID))
        val modelFile = File(LocalModelStorageRepository.modelsDir(context), entry.expectedFilename)
        assumeTrue("Pre-seed ${entry.expectedFilename} in app local_models storage", modelFile.isFile)

        val installed = LocalModelStorageRepository.registerCatalogModel(context, entry, modelFile)
        val settings = LocalModelSettingsRepository.getForModel(context, installed.id)
        assertEquals(LocalComputeBackend.GPU, settings.computeBackend)

        val statuses = mutableListOf<String>()
        val reply = LocalModelsProvider().streamChat(
            context = context,
            messages = listOf(mapOf("role" to "User", "content" to "Reply with one short sentence confirming local inference works.")),
            onStatus = { status ->
                statuses += status
                Log.i(TAG, "status=$status")
            },
            maxTokens = 48,
        ).trim()

        Log.i(TAG, "reply=$reply")
        assertTrue("Expected a non-empty local-model response; statuses=$statuses", reply.isNotEmpty())
    }

    private companion object {
        const val TAG = "LocalInferenceSmoke"
        const val MODEL_ID = "qwen2.5-0.5b-instruct-q4"
    }
}

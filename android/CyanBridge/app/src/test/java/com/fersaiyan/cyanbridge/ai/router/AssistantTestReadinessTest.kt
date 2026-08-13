package com.fersaiyan.cyanbridge.ai.router

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.fersaiyan.cyanbridge.localmodels.remote.RemoteOpenAiPrefs
import com.fersaiyan.cyanbridge.localmodels.storage.LocalModelStorageRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AssistantTestReadinessTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun resetLocalModels() {
        context.getSharedPreferences("local_models_registry", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("remote_openai_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        LocalModelStorageRepository.modelsDir(context).deleteRecursively()
    }

    @Test
    fun localVoiceWithoutModelOpensLocalModelSetup() {
        val issue = AssistantTestReadiness.blockingIssue(
            context,
            GlassesAssistantRoute.LOCAL,
            AssistantTestKind.VOICE,
        )

        assertEquals(AssistantSetupDestination.LOCAL_MODELS, issue?.destination)
        assertEquals("Open Local Models", issue?.actionLabel)
    }

    @Test
    fun configuredRemoteLocalBackendDoesNotRequireDownloadedModel() {
        RemoteOpenAiPrefs.setBaseUrl(context, "https://example.com/v1")
        RemoteOpenAiPrefs.setModel(context, "vision-model")
        RemoteOpenAiPrefs.setEnabled(context, true)

        assertNull(
            AssistantTestReadiness.blockingIssue(
                context,
                GlassesAssistantRoute.LOCAL,
                AssistantTestKind.IMAGE,
            ),
        )
    }

    @Test
    fun phoneAssistantReadinessIsHandledByAndroidAssistantFlow() {
        assertNull(
            AssistantTestReadiness.blockingIssue(
                context,
                GlassesAssistantRoute.PHONE_ASSISTANT,
                AssistantTestKind.VOICE,
            ),
        )
    }
}

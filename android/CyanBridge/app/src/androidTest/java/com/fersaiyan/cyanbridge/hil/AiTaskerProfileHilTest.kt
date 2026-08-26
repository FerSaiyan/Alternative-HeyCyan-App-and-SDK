package com.fersaiyan.cyanbridge.hil

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fersaiyan.cyanbridge.MainActivity
import com.fersaiyan.cyanbridge.ai.image.DefaultAssistantResolver
import com.fersaiyan.cyanbridge.ai.image.ExternalImageAutomationIntents
import com.fersaiyan.cyanbridge.ai.image.ImageAutomationTarget
import com.fersaiyan.cyanbridge.ai.image.ImageQuestionBroadcast
import com.fersaiyan.cyanbridge.ai.image.TaskerImageProfileCompatibility
import com.fersaiyan.cyanbridge.ai.image.TaskerImageProfileStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AiTaskerProfileHilTest {
    @Test
    fun geminiAndChatGptProfilesHandshakeWithoutCrossTalk() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // Profile verification itself is a Tasker protocol check. AutoInput is only required by
        // the image/composer branch and must not become an accidental voice dependency.
        HilTestSupport.requireTasker(context)

        verifyProfile(
            assistantLabel = "Gemini",
            expectedTarget = "gemini",
            expectedVersion = TaskerImageProfileCompatibility.GEMINI_PROFILE_VERSION,
        )
        verifyProfile(
            assistantLabel = "ChatGPT",
            expectedTarget = "chatgpt",
            expectedVersion = TaskerImageProfileCompatibility.CHATGPT_PROFILE_VERSION,
        )
    }

    @Test
    fun configuredDefaultAssistantActuallyLaunchesThroughTaskerVoiceEvent() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        HilTestSupport.requireTasker(context)

        val defaultPackage = DefaultAssistantResolver.packageName(context)
        val target = ImageAutomationTarget.forDefaultAssistant(defaultPackage)
        assumeTrue(
            "Persistent HIL target has no Gemini/ChatGPT default assistant configured; voice launch test skipped",
            target != ImageAutomationTarget.NONE,
        )

        returnHome(context)
        context.sendBroadcast(
            Intent(MainActivity.aiEventAction(context.packageName)).apply {
                setPackage(HilTestSupport.TASKER_PACKAGE)
                putExtra(ImageQuestionBroadcast.EXTRA_TYPE, "voice")
                putExtra(ImageQuestionBroadcast.EXTRA_ASSISTANT, target.label)
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            },
        )

        val observedPackage = waitForForegroundPackage(target.packageNames, VOICE_LAUNCH_TIMEOUT_MS)
        println(
            "CYANBRIDGE_AI_VOICE_HIL route=tasker target=${target.wireName} " +
                "defaultPackage=$defaultPackage foreground=$observedPackage",
        )
        assertTrue(
            "Tasker received the ${target.label} voice event but the selected assistant never reached the foreground; last=$observedPackage",
            observedPackage in target.packageNames,
        )
        returnHome(context)
    }

    @Test
    fun reportPackageTargetedVoiceCommandSupportForInstalledAssistants() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val packageManager = context.packageManager

        for (target in listOf(ImageAutomationTarget.GEMINI, ImageAutomationTarget.CHATGPT)) {
            for (packageName in target.packageNames.distinct()) {
                if (!HilTestSupport.packageInstalled(context, packageName)) continue

                val intent = Intent(Intent.ACTION_VOICE_COMMAND).apply {
                    setPackage(packageName)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                val resolved = intent.resolveActivity(packageManager)
                var launchObserved = false
                var launchError: String? = null

                if (resolved != null) {
                    returnHome(context)
                    runCatching {
                        context.startActivity(intent)
                        launchObserved = waitForForegroundPackage(
                            target.packageNames,
                            DIRECT_VOICE_PROBE_TIMEOUT_MS,
                        ) in target.packageNames
                    }.onFailure { error ->
                        launchError = "${error.javaClass.simpleName}:${error.message.orEmpty()}"
                    }
                    returnHome(context)
                }

                println(
                    "CYANBRIDGE_AI_VOICE_DIRECT_PROBE target=${target.wireName} package=$packageName " +
                        "resolved=${resolved?.flattenToShortString().orEmpty()} launchObserved=$launchObserved " +
                        "error=${launchError.orEmpty()}",
                )
            }
        }

        // This is intentionally a capability probe, not a requirement. Gemini/ChatGPT do not
        // document stable internal voice Activity class names. CI records what the Play-updated
        // apps on the persistent emulator actually export so we can adopt direct package targeting
        // only when the installed version proves it works.
        assertTrue(true)
    }

    private fun verifyProfile(
        assistantLabel: String,
        expectedTarget: String,
        expectedVersion: String,
    ) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val token = TaskerImageProfileStore.beginVerification(context)
        context.sendBroadcast(
            Intent("${context.packageName}.AI_EVENT").apply {
                setPackage(HilTestSupport.TASKER_PACKAGE)
                putExtra(ImageQuestionBroadcast.EXTRA_TYPE, "profile_check")
                putExtra(ImageQuestionBroadcast.EXTRA_ASSISTANT, assistantLabel)
                putExtra("profile_token", token)
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            },
        )

        val deadline = System.currentTimeMillis() + 5_000L
        while (System.currentTimeMillis() < deadline) {
            if (
                TaskerImageProfileStore.target(context) == expectedTarget &&
                TaskerImageProfileStore.version(context) == expectedVersion
            ) {
                break
            }
            Thread.sleep(100)
        }

        assertEquals("Wrong Tasker profile answered $assistantLabel handshake", expectedTarget, TaskerImageProfileStore.target(context))
        assertEquals("Wrong/outdated Tasker profile version for $assistantLabel", expectedVersion, TaskerImageProfileStore.version(context))
    }

    private fun waitForForegroundPackage(expectedPackages: List<String>, timeoutMs: Long): String? {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadline = System.currentTimeMillis() + timeoutMs
        var last: String? = null
        while (System.currentTimeMillis() < deadline) {
            last = instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()
            if (last in expectedPackages) return last
            Thread.sleep(200L)
        }
        return last
    }

    private fun returnHome(context: android.content.Context) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
        }
        Thread.sleep(500L)
    }

    companion object {
        private const val VOICE_LAUNCH_TIMEOUT_MS = 10_000L
        private const val DIRECT_VOICE_PROBE_TIMEOUT_MS = 5_000L
    }
}

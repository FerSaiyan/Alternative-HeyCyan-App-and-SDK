package com.fersaiyan.cyanbridge.hil

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.test.ext.junit.rules.ActivityScenarioRule
import com.fersaiyan.cyanbridge.MainActivity
import com.fersaiyan.cyanbridge.ui.nav.AppRoute
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fersaiyan.cyanbridge.localmodels.catalog.LocalModelCatalogRepository
import com.fersaiyan.cyanbridge.localmodels.download.ModelDownloadForegroundService
import com.fersaiyan.cyanbridge.localmodels.engine.NpuCatalogVariants
import com.fersaiyan.cyanbridge.localmodels.settings.LocalComputeBackend
import com.fersaiyan.cyanbridge.localmodels.settings.LocalModelRuntime
import com.fersaiyan.cyanbridge.localmodels.settings.LocalModelSettingsRepository
import com.fersaiyan.cyanbridge.localmodels.storage.LocalModelStorageRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Snapdragon NPU Gemma entry on the Local Models settings page (docs/NPU_ON_DEVICE.md).
 *
 * On any device the entry is listed with this phone's resolution: Download enabled when a
 * build is published for its SoC and the app has the NPU runtime, otherwise disabled with
 * the reason. With `-e npu_download true` on a supported phone it also taps Download, waits
 * for the per-SoC file, and checks that the model is installed, selected, and set to
 * LiteRT on the NPU. The downloaded model is kept.
 */
@RunWith(AndroidJUnit4::class)
class NpuCatalogSettingsTest {
    @get:Rule
    // The Local models screen is a route of MainActivity now.
    val composeRule = AndroidComposeTestRule(
        activityRule = ActivityScenarioRule<MainActivity>(
            AppRoute.LOCAL_MODELS.intent(InstrumentationRegistry.getInstrumentation().targetContext),
        ),
        activityProvider = { rule ->
            lateinit var activity: MainActivity
            rule.scenario.onActivity { activity = it }
            activity
        },
    )

    private val args = InstrumentationRegistry.getArguments()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val entry = LocalModelCatalogRepository.findById("gemma4-e2b-it-npu")!!

    @Test
    fun npuEntryShowsThisPhonesBuildOrWhyNot() {
        val resolution = NpuCatalogVariants.forDevice(context, entry)
        val installed = LocalModelStorageRepository.findByCatalogId(context, entry.id)
        println("NPU_CATALOG resolved=${resolution.entry?.expectedFilename} url=${resolution.entry?.sourceUrl} reason=${resolution.reason} installed=${installed?.fileName}")

        showEntry()
        val button = entryButton(downloadLabel(resolution.entry != null, installed != null))
        when {
            installed != null -> button.assertIsNotEnabled()
            resolution.entry == null -> {
                composeRule.onNodeWithText(resolution.reason!!).performScrollTo().assertIsDisplayed()
                button.assertIsNotEnabled()
            }
            else -> button.assertIsEnabled()
        }
    }

    @Test
    fun downloadsAndSelectsThePerSocBuild() {
        assumeTrue("pass -e npu_download true", args.getString("npu_download") == "true")
        val resolved = NpuCatalogVariants.forDevice(context, entry).entry
        assumeTrue("no NPU build for this phone", resolved != null)
        assumeTrue("already installed", LocalModelStorageRepository.findByCatalogId(context, entry.id) == null)

        val previous = ModelDownloadForegroundService.lastResult
        showEntry()
        entryButton("Download").performClick()
        // A device-warning dialog may ask for confirmation first.
        runCatching { composeRule.onNodeWithText("Continue").performClick() }

        val deadline = System.currentTimeMillis() + 45 * 60_000L
        while (LocalModelStorageRepository.findByCatalogId(context, entry.id) == null && System.currentTimeMillis() < deadline) {
            val result = ModelDownloadForegroundService.lastResult
            if (result !== previous && result != null && result.modelId == entry.id && !result.success) error("download failed: ${result.error}")
            Thread.sleep(5_000)
        }
        val model = LocalModelStorageRepository.findByCatalogId(context, entry.id)
        assertNotNull("download did not finish", model)
        model!!
        println("NPU_CATALOG downloaded=${model.fileName} bytes=${model.sizeBytes} sha=${model.sha256}")
        assertEquals(resolved!!.expectedFilename, model.fileName)
        assertEquals(resolved.sha256, model.sha256)
        assertEquals(entry.id, LocalModelStorageRepository.getSelectedModelId(context))
        val settings = LocalModelSettingsRepository.getForModel(context, model.id)
        assertEquals(LocalModelRuntime.LITERT, settings.modelRuntime)
        assertEquals(LocalComputeBackend.NPU_EXPERIMENTAL, settings.computeBackend)
        assertTrue(settings.contextSize <= 4096)
    }

    private fun showEntry() {
        val list = composeRule.onNodeWithTag("local_models_configure")
        list.performScrollToNode(hasText("Curated models"))
        if (composeRule.onAllNodesWithText(entry.displayName).fetchSemanticsNodes().isEmpty()) {
            composeRule.onNodeWithText("Curated models").performClick()
        }
        list.performScrollToNode(hasText(entry.displayName))
        composeRule.onNodeWithText(entry.displayName).assertIsDisplayed()
    }

    /** The NPU entry is the catalog's last, so its button is the last one with [label]. */
    private fun entryButton(label: String) =
        composeRule.onAllNodes(hasClickAction() and hasText(label)).onLast().also { it.performScrollTo() }

    private fun downloadLabel(resolved: Boolean, installed: Boolean) = when {
        installed -> "Installed"
        !resolved -> "Unavailable"
        else -> "Download"
    }
}

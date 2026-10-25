package com.fersaiyan.cyanbridge.shared.plugins

import com.fersaiyan.cyanbridge.shared.plugins.walkingaid.WalkingAidModelOption
import com.fersaiyan.cyanbridge.shared.plugins.walkingaid.WalkingAidReadinessResult
import com.fersaiyan.cyanbridge.shared.plugins.walkingaid.WalkingAidSettings
import com.fersaiyan.cyanbridge.shared.plugins.walkingaid.WalkingAidSettingsMessage
import com.fersaiyan.cyanbridge.shared.plugins.walkingaid.WalkingAidSettingsPlatform
import com.fersaiyan.cyanbridge.shared.plugins.walkingaid.WalkingAidSettingsViewModel
import com.fersaiyan.cyanbridge.shared.prefs.PluginPrefs
import com.fersaiyan.cyanbridge.shared.prefs.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class WalkingAidSettingsViewModelTest {
    private val store = PluginPrefs.WalkingAid.store()
    private val catalog = PluginPrefs.Community.store()
    private var ready = false

    private val platform = object : WalkingAidSettingsPlatform {
        override val hasCamera = true
        override val cameraUnavailableReason: String? = null
        override fun isServiceRunning() = false
        override fun checkReadiness() = WalkingAidReadinessResult(ready, ready, true, true, false, listOf("model missing"))
        override suspend fun installModel(entryId: String, onProgress: (Int) -> Unit) = Unit
        override suspend fun runLatencyBenchmark(depthEnabled: Boolean, depthSource: String, focusDescription: String) = ""
        override suspend fun runAcquisitionBenchmark() = ""
        override suspend fun translateFocusToEnglish(original: String, source: String) = "dogs"
        override suspend fun fetchCloudModels() = emptyList<WalkingAidModelOption>()
        override fun clearImageHistory() = Unit
        override fun showMessage(message: WalkingAidSettingsMessage, detail: String?) = Unit
    }

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        store.clear()
        catalog.clear()
    }

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun switchingOnWaitsForReadinessThenSavesBothFlagsAndStarts() {
        val viewModel = WalkingAidSettingsViewModel(store, catalog, platform)
        var started = 0

        viewModel.onEnabledChange(true, startService = { started++ }, stopService = {})
        assertEquals(listOf("model missing"), viewModel.readiness?.missingDetails)
        assertFalse(viewModel.enabled)
        assertEquals(0, started)

        ready = true
        viewModel.dismissReadiness()
        viewModel.onEnabledChange(true, startService = { started++ }, stopService = {})
        assertNull(viewModel.readiness)
        assertTrue(store[PluginPrefs.WalkingAid.enabled])
        assertTrue(catalog[PluginPrefs.Community.nativePluginEnabled[NativePluginIds.WALKING_AID]])
        assertEquals(1, started)
    }

    @Test
    fun savingTheFocusStoresItsTranslationUntilTheWordsChange() {
        val viewModel = WalkingAidSettingsViewModel(store, catalog, platform)

        viewModel.onFocusTextChange("  perros ")
        assertTrue(viewModel.focusUnsaved)
        viewModel.onTranslateAndSaveFocus()

        assertFalse(viewModel.focusUnsaved)
        assertEquals("dogs", viewModel.understoodFocusText)
        assertEquals("dogs", WalkingAidSettings.modelFocusDescription(store))

        viewModel.onFocusTextChange("gatos")
        assertEquals("", viewModel.understoodFocusText)
    }
}

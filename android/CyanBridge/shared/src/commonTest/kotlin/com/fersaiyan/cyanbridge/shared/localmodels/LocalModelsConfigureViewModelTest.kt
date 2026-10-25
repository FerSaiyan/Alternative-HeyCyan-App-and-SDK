package com.fersaiyan.cyanbridge.shared.localmodels

import com.fersaiyan.cyanbridge.shared.ui.UiMessage
import com.fersaiyan.cyanbridge.shared.viewmodel.CloseScreen
import com.fersaiyan.cyanbridge.shared.viewmodel.ScreenDialog
import com.fersaiyan.cyanbridge.shared.viewmodel.ScreenEffect
import com.fersaiyan.cyanbridge.shared.viewmodel.ShowMessage
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class LocalModelsConfigureViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val platform = FakeLocalModelsPlatform()
    private val effects = mutableListOf<ScreenEffect>()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.viewModel() = LocalModelsConfigureViewModel(platform, dispatcher).also { vm ->
        backgroundScope.launch(dispatcher) { vm.effectFlow.toList(effects) }
        vm.start()
    }

    @Test
    fun editsAreUnsavedUntilDiscardedOnBack() = runTest(dispatcher) {
        val vm = viewModel()
        vm.handle(LocalModelsAction.UpdateText(LocalModelTextField.TEMPERATURE, "1.3"))
        assertTrue(vm.state.value.hasUnsavedChanges)
        assertEquals("1.3", vm.state.value.generation.temperature)

        vm.handle(LocalModelsAction.Back)
        val dialog = effects.filterIsInstance<ScreenDialog>().single()
        assertEquals("Unsaved changes", dialog.title)
        dialog.positive!!.onClick!!()
        assertEquals(CloseScreen, effects.last())
    }

    @Test
    fun downloadStatesFollowTheService() = runTest(dispatcher) {
        val vm = viewModel()
        vm.handle(LocalModelsAction.DownloadCatalogModel("gemma"))
        assertEquals(listOf("gemma"), platform.started)
        assertEquals(LocalModelDownloadUiState(isInFlight = true, message = "Starting Gemma…"), vm.state.value.download)

        vm.onDownloadProgress(LocalModelDownloadUiState(isInFlight = true, message = "Downloading: 40%", progressPercent = 40))
        assertEquals(40, vm.state.value.download.progressPercent)

        vm.onDownloadFinished(success = false, error = "disk full")
        assertEquals(LocalModelDownloadUiState(message = "Download failed: disk full"), vm.state.value.download)

        vm.handle(LocalModelsAction.UpdateText(LocalModelTextField.SEED, "7"))
        vm.onDownloadFinished(success = true, error = null)
        assertEquals("Download complete", vm.state.value.download.message)
        assertFalse(vm.state.value.hasUnsavedChanges)
        assertEquals("-1", vm.state.value.generation.seed)
    }

    @Test
    fun savingWithoutAModelKeepsOnlyTheToken() = runTest(dispatcher) {
        val vm = viewModel()
        vm.handle(LocalModelsAction.UpdateText(LocalModelTextField.HUGGING_FACE_TOKEN, "hf_x"))
        vm.handle(LocalModelsAction.SaveGenerationSettings)

        assertEquals("hf_x", platform.savedToken)
        assertFalse(vm.state.value.hasUnsavedChanges)
        assertEquals(ShowMessage(UiMessage("Saved download token. Install a model to save model settings.")), effects.last())
    }
}

private class FakeLocalModelsPlatform : LocalModelsPlatform {
    val started = mutableListOf<String>()
    var savedToken = ""
    private val expanded = mutableSetOf<LocalModelsSection>()

    override fun reload() = Unit
    override fun overview() = LocalModelsOverview("Runtimes", "arm64", "No model selected", "", emptyList(), null, null)
    override fun catalog(huggingFaceToken: String) = emptyList<LocalModelCatalogUiItem>()
    override fun selectModel(id: String) = Unit
    override fun warmupResult() = ""
    override fun isSectionExpanded(section: LocalModelsSection) = section in expanded
    override fun setSectionExpanded(section: LocalModelsSection, expanded: Boolean) {
        if (expanded) this.expanded += section else this.expanded -= section
    }

    override fun loadGeneration() = LocalModelGenerationUiState(temperature = "0.7", seed = "-1", huggingFaceToken = savedToken)
    override fun describeGeneration(draft: LocalModelGenerationUiState) = draft
    override fun saveGeneration(draft: LocalModelGenerationUiState): Boolean {
        savedToken = draft.huggingFaceToken
        return false
    }

    override fun loadRemote() = RemoteInferenceUiState()
    override fun loadStudio() = StudioBridgeUiState()
    override fun saveRemote(draft: RemoteInferenceUiState): Refusal? = null
    override suspend fun checkRemoteHealth() = "ok"
    override fun studioBlocker(apiKey: String): Refusal? = null
    override fun studioPermissionsGranted() = true
    override fun applyStudio(enabled: Boolean, apiKey: String) = enabled
    override fun downloadInProgress(): LocalModelDownloadUiState? = null
    override fun checkDownload(catalogId: String, huggingFaceToken: String) = DownloadCheck.Allowed("Gemma", null, emptyList())
    override fun startDownload(catalogId: String, token: String?) {
        started += catalogId
    }

    override fun cancelDownload() = Unit
    override fun importModel(source: Any) = "Import complete: $source"
    override suspend fun unloadModel() = Unit
    override fun removeModel(id: String) = Unit
    override fun selectedModelInfo(runtimeIndex: Int): ModelInfo? = null
    override fun catalogInfo(catalogId: String): ModelInfo? = null
}

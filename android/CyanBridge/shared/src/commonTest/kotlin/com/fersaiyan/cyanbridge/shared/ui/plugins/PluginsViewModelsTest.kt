package com.fersaiyan.cyanbridge.shared.ui.plugins

import com.fersaiyan.cyanbridge.shared.plugins.CommunityPluginCardData
import com.fersaiyan.cyanbridge.shared.plugins.NativePluginCardData
import com.fersaiyan.cyanbridge.shared.ui.UiMessage
import kotlinx.coroutines.CompletableDeferred
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
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PluginsViewModelsTest {
    private val messages = mutableListOf<String>()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun catalogRefreshReportsTheServerAndKeepsLoadedPluginsOnFailure() {
        val responses = ArrayDeque(listOf<Result<List<CommunityPluginCardData>>>(Result.success(listOf(plugin)), Result.failure(Exception("down"))))
        val viewModel = CommunityPluginsViewModel(object : CommunityPluginsPlatform {
            override fun nativePlugins() = listOf(NativePluginCardData("walking_aid", "Walking Aid", "", "", enabled = false, hasSettings = true))
            override suspend fun fetchCommunityPlugins() = responses.removeFirst().getOrThrow()
            override fun showMessage(message: UiMessage) { messages += message.text }
        })

        viewModel.refresh()
        viewModel.onNativePluginToggled("walking_aid", true)

        assertEquals(listOf(plugin), viewModel.communityPlugins)
        assertFalse(viewModel.isRefreshing)
        assertTrue(viewModel.nativePlugins.single().enabled)
        assertEquals(
            listOf(
                "Fetching plugins from server...",
                "Plugins refreshed from server!",
                "Server unavailable. Official Tasker integrations are still available above.",
            ),
            messages,
        )
    }

    @Test
    fun publishValidatesFieldsAndFinishesOnlyOnceTheRelayAccepts() {
        val accepted = CompletableDeferred<Boolean>()
        val viewModel = PublishPluginViewModel({ _, _, _, _, _ -> accepted.await() }) { messages += it.text }

        viewModel.submit()
        assertEquals("Title is required", viewModel.state.titleError)
        assertEquals("TaskerNet link is required", viewModel.state.taskerNetLinkError)

        viewModel.onTitleChanged("Commute")
        viewModel.onAuthorChanged("Ana")
        viewModel.onDescriptionChanged("Reads my route")
        viewModel.onTaskerNetLinkChanged("https://taskernet.com/x")
        viewModel.submit()
        assertTrue(viewModel.state.isSubmitting)
        assertFalse(viewModel.submitted)

        accepted.complete(true)
        assertTrue(viewModel.submitted)
        assertEquals(listOf("Plugin submitted for review! It will appear in the list once approved."), messages)
    }

    private companion object {
        val plugin = CommunityPluginCardData("Commute", "Ana", "", "Other", 0, 0, 0, 0, 0, 0, 0, 0, 0)
    }
}

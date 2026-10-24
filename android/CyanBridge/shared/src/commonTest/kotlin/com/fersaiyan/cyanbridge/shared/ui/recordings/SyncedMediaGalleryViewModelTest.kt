package com.fersaiyan.cyanbridge.shared.ui.recordings

import com.fersaiyan.cyanbridge.shared.recordings.SyncedMediaItem
import com.fersaiyan.cyanbridge.shared.ui.UiMessage
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class SyncedMediaGalleryViewModelTest {
    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun deletingReportsTheCountAndReloads() = runTest {
        val files = mutableListOf(media(1), media(2), media(3))
        val vm = SyncedMediaGalleryViewModel(object : SyncedMediaSource {
            override suspend fun list() = files.toList()
            override suspend fun delete(items: List<SyncedMediaItem>) = items.filter { files.remove(it) }
        })
        vm.refresh()
        vm.delete(listOf(media(1), media(2)))

        assertEquals(UiMessage("Deleted 2 items"), vm.messages.first())
        assertEquals(listOf(media(3)), vm.items)
        assertFalse(vm.isLoading)

        vm.delete(listOf(media(1)))
        assertEquals(UiMessage("Failed to delete items"), vm.messages.first())
    }

    private fun media(id: Long) = SyncedMediaItem(id, "IMG_$id.jpg", "content://media/$id", isVideo = false)
}

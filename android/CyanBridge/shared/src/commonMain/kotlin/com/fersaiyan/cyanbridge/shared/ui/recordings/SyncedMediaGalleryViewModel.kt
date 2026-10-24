package com.fersaiyan.cyanbridge.shared.ui.recordings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fersaiyan.cyanbridge.shared.recordings.SyncedMediaItem
import com.fersaiyan.cyanbridge.shared.ui.UiMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/** The synced media folder: listing and deleting. Opening and sharing stay with the host. */
interface SyncedMediaSource {
    suspend fun list(): List<SyncedMediaItem>

    /** Deletes [items] and returns the ones that were removed. */
    suspend fun delete(items: List<SyncedMediaItem>): List<SyncedMediaItem>
}

class SyncedMediaGalleryViewModel(private val source: SyncedMediaSource) : ViewModel() {
    var items by mutableStateOf<List<SyncedMediaItem>>(emptyList())
        private set
    var isLoading by mutableStateOf(true)
        private set

    private val messageChannel = Channel<UiMessage>(Channel.BUFFERED)
    val messages = messageChannel.receiveAsFlow()
    private var loadJob: Job? = null

    fun refresh() {
        loadJob?.cancel()
        isLoading = true
        loadJob = viewModelScope.launch {
            items = source.list()
            isLoading = false
        }
    }

    /** Stops a running load (the host is no longer visible); [refresh] starts over. */
    fun stopLoading() {
        loadJob?.cancel()
        loadJob = null
    }

    fun delete(selected: List<SyncedMediaItem>) {
        if (selected.isEmpty()) return
        viewModelScope.launch {
            val deleted = source.delete(selected)
            if (deleted.isNotEmpty()) {
                messageChannel.send(UiMessage("Deleted ${deleted.size} item${if (deleted.size > 1) "s" else ""}"))
                refresh()
            } else {
                messageChannel.send(UiMessage("Failed to delete items"))
            }
        }
    }
}

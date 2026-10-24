package com.fersaiyan.cyanbridge.shared.ui.plugins

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fersaiyan.cyanbridge.shared.plugins.CommunityPluginCatalog
import com.fersaiyan.cyanbridge.shared.plugins.PublishPluginUiState
import kotlinx.coroutines.launch

/** Sends a plugin to the relay's review queue. */
fun interface PublishPluginPlatform {
    suspend fun submit(title: String, author: String, description: String, category: String, taskerNetLink: String): Boolean
}

/** Form state and submission of [PublishPluginScreen]; [submitted] turns true once the relay accepted it. */
class PublishPluginViewModel(
    private val platform: PublishPluginPlatform,
    private val showMessage: (String) -> Unit,
) : ViewModel() {
    val categories = CommunityPluginCatalog.categories
    var state by mutableStateOf(PublishPluginUiState())
        private set
    var submitted by mutableStateOf(false)
        private set

    fun onTitleChanged(title: String) {
        state = state.copy(title = title, titleError = null)
    }

    fun onAuthorChanged(author: String) {
        state = state.copy(author = author, authorError = null)
    }

    fun onDescriptionChanged(description: String) {
        state = state.copy(description = description, descriptionError = null)
    }

    fun onCategorySelected(category: String) {
        state = state.copy(category = category)
    }

    fun onTaskerNetLinkChanged(link: String) {
        state = state.copy(taskerNetLink = link, taskerNetLinkError = null)
    }

    fun submit() {
        if (state.isSubmitting) return
        val title = state.title.trim()
        val author = state.author.trim()
        val description = state.description.trim()
        val category = state.category.trim()
        val taskerNetLink = state.taskerNetLink.trim()
        val checked = state.copy(
            titleError = "Title is required".takeIf { title.isBlank() },
            authorError = "Author name is required".takeIf { author.isBlank() },
            descriptionError = "Description is required".takeIf { description.isBlank() },
            taskerNetLinkError = "TaskerNet link is required".takeIf { taskerNetLink.isBlank() },
        )
        if (listOf(checked.titleError, checked.authorError, checked.descriptionError, checked.taskerNetLinkError).any { it != null }) {
            state = checked
            return
        }
        state = state.copy(isSubmitting = true)
        viewModelScope.launch {
            if (platform.submit(title, author, description, category, taskerNetLink)) {
                showMessage("Plugin submitted for review! It will appear in the list once approved.")
                submitted = true
            } else {
                state = state.copy(isSubmitting = false)
                showMessage("Server unavailable. Please try again later.")
            }
        }
    }
}

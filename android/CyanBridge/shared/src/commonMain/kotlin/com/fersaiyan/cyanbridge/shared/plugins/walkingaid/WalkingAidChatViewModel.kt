package com.fersaiyan.cyanbridge.shared.plugins.walkingaid

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fersaiyan.cyanbridge.shared.platform.PlatformPreferences
import com.fersaiyan.cyanbridge.shared.prefs.PluginPrefs.WalkingAid
import com.fersaiyan.cyanbridge.shared.prefs.get
import kotlinx.coroutines.launch

/** Answers questions about the latest Walking Aid photo and reads answers aloud. */
interface WalkingAidChatPlatform {
    suspend fun answer(question: String, imagePath: String?): String
    fun speak(text: String)
    fun close()
}

/** The "Ask about surroundings" screen: one question at a time about the newest photo. */
class WalkingAidChatViewModel(
    private val store: PlatformPreferences,
    private val platform: WalkingAidChatPlatform,
) : ViewModel() {
    var question by mutableStateOf("")
        private set
    var response by mutableStateOf("")
        private set
    var isLoading by mutableStateOf(false)
        private set

    fun onQuestionChange(text: String) {
        question = text
    }

    fun ask(latestImagePath: String?) {
        if (question.isBlank() || isLoading) return
        isLoading = true
        val userQuestion = question.trim()
        question = ""
        viewModelScope.launch {
            val reply = platform.answer(userQuestion, latestImagePath)
            response = reply.trim()
            if (store[WalkingAid.ttsEnabled] && reply.isNotBlank()) platform.speak(reply)
            isLoading = false
        }
    }

    override fun onCleared() = platform.close()
}

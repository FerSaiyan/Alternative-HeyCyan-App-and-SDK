package com.fersaiyan.cyanbridge.bridge.runtimes.evenhub

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fersaiyan.cyanbridge.bridge.core.DisplayCommand
import kotlinx.coroutines.launch

/**
 * The EvenHub app runner around a web view: the app URL, the runtime log, and the display calls
 * the app's JavaScript bridge forwards to the glasses through [display].
 */
class EvenHubRuntimeViewModel(private val display: suspend (DisplayCommand) -> Unit) : ViewModel() {
    var url by mutableStateOf(DEFAULT_URL)
        private set
    var logs by mutableStateOf("")
        private set

    fun onUrlChange(value: String) {
        url = value
    }

    /** The URL to load; http:// is assumed when it names no scheme. */
    fun loadableUrl(): String = if (url.startsWith("http://") || url.startsWith("https://")) url else "http://$url"

    /** Safe from any thread (the JavaScript bridge calls in on its own); keeps the last 16,000 characters. */
    fun log(message: String) {
        viewModelScope.launch {
            logs = buildString {
                if (logs.isNotBlank()) append(logs).append('\n')
                append("[$TAG] ").append(message)
            }.takeLast(16_000)
        }
    }

    fun displayText(text: String) {
        log("Display text: ${text.take(80)}")
        send(DisplayCommand.Text(text = text))
    }

    fun displayLines(lines: List<String>, page: Int, totalPages: Int?) {
        log("Display lines: ${lines.size} items")
        send(DisplayCommand.Lines(lines, page, totalPages))
    }

    fun displayCard(title: String, body: String) {
        log("Display card: $title")
        send(DisplayCommand.Card(title, body))
    }

    fun clearDisplay() {
        log("Clear display")
        send(DisplayCommand.Clear)
    }

    private fun send(command: DisplayCommand) {
        viewModelScope.launch { display(command) }
    }

    companion object {
        const val TAG = "EvenHubRuntime"
        private const val DEFAULT_URL = "http://10.0.2.2:5173"
    }
}

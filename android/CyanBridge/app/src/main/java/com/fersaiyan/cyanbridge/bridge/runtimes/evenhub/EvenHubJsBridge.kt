package com.fersaiyan.cyanbridge.bridge.runtimes.evenhub

import android.util.Log
import android.webkit.JavascriptInterface
import org.json.JSONObject

/**
 * Receives JavaScript bridge calls from the EvenHub app running in the WebView
 * and routes display commands to [GlassesBridge] via callback functions.
 *
 * Registered on the WebView as `CyanBridgeEvenHubBridge`.
 */
class EvenHubJsBridge(
    private val onDisplayText: (String) -> Unit,
    private val onDisplayLines: (List<String>, Int, Int?) -> Unit,
    private val onDisplayCard: (String, String) -> Unit,
    private val onClearDisplay: () -> Unit,
    private val onExit: () -> Unit,
    private val onLog: (String) -> Unit,
) {
    companion object {
        private const val TAG = "EvenHubJsBridge"
    }

    @JavascriptInterface
    fun createPage(jsonParams: String) {
        Log.d(TAG, "createPage: $jsonParams")
        onLog("createPage: ${truncate(jsonParams, 120)}")
        try {
            val params = JSONObject(jsonParams)
            val text = extractFirstText(params)
            if (text != null) {
                onDisplayText(text)
            } else {
                val lines = extractLines(params)
                if (lines.isNotEmpty()) {
                    onDisplayLines(lines, 0, null)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse createPage params", e)
            onLog("createPage error: ${e.message}")
        }
    }

    @JavascriptInterface
    fun updateText(containerId: Int, containerName: String, content: String, offset: Int, length: Int) {
        Log.d(TAG, "updateText: id=$containerId name=$containerName content=$content")
        onLog("updateText: ${truncate(content, 80)}")
        onDisplayText(content)
    }

    @JavascriptInterface
    fun rebuildPage(jsonParams: String) {
        Log.d(TAG, "rebuildPage: $jsonParams")
        onLog("rebuildPage: ${truncate(jsonParams, 120)}")
        try {
            val params = JSONObject(jsonParams)
            // Rebuild works the same as create — map containers to display
            val text = extractFirstText(params)
            if (text != null) {
                onDisplayText(text)
            } else {
                val lines = extractLines(params)
                if (lines.isNotEmpty()) {
                    onDisplayLines(lines, 0, null)
                } else {
                    onClearDisplay()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse rebuildPage params", e)
            onLog("rebuildPage error: ${e.message}")
        }
    }

    @JavascriptInterface
    fun shutDown(exitMode: Int) {
        Log.d(TAG, "shutDown: exitMode=$exitMode")
        onLog("shutDown: exitMode=$exitMode")
        onClearDisplay()
        onExit()
    }

    @JavascriptInterface
    fun log(message: String) {
        Log.d(TAG, "JS log: $message")
        onLog(message)
    }

    // ------------------------------------------------------------------
    // JSON parsing helpers
    // ------------------------------------------------------------------

    /**
     * Extract the first text content from the JSON params.
     *
     * Expected structure from EvenHub:
     * ```json
     * {
     *   "textObject": [
     *     { "content": "Hello world" }
     *   ]
     * }
     * ```
     * or a flat
     * ```json
     * { "content": "Hello world" }
     * ```
     */
    private fun extractFirstText(params: JSONObject): String? {
        // Try textObject array first
        if (params.has("textObject")) {
            val arr = params.optJSONArray("textObject")
            if (arr != null && arr.length() > 0) {
                val first = arr.optJSONObject(0)
                if (first != null && first.has("content")) {
                    return first.optString("content", "")
                }
            }
        }
        // Try container array (EvenHub v2+)
        if (params.has("container")) {
            val arr = params.optJSONArray("container")
            if (arr != null && arr.length() > 0) {
                val first = arr.optJSONObject(0)
                if (first != null && first.has("text")) {
                    return first.optString("text", "")
                }
            }
        }
        // Try flat content
        if (params.has("content")) {
            return params.optString("content", "")
        }
        return null
    }

    /**
     * Extract lines from list containers.
     *
     * Expected structure:
     * ```json
     * {
     *   "container": [
     *     {
     *       "type": "list",
     *       "items": ["item1", "item2", ...]
     *     }
     *   ]
     * }
     * ```
     * or
     * ```json
     * {
     *   "listObject": [
     *     { "text": "item1" },
     *     { "text": "item2" }
     *   ]
     * }
     * ```
     */
    private fun extractLines(params: JSONObject): List<String> {
        val lines = mutableListOf<String>()

        // Try container array with list type
        if (params.has("container")) {
            val arr = params.optJSONArray("container")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val container = arr.optJSONObject(i) ?: continue
                    if (container.optString("type", "") == "list") {
                        val items = container.optJSONArray("items")
                        if (items != null) {
                            for (j in 0 until items.length()) {
                                val item = items.optString(j, "")
                                if (item.isNotEmpty()) lines.add(item)
                            }
                        }
                    }
                }
            }
        }

        // Try listObject array
        if (lines.isEmpty() && params.has("listObject")) {
            val arr = params.optJSONArray("listObject")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i)
                    if (item != null) {
                        val text = item.optString("text", "")
                        if (text.isNotEmpty()) lines.add(text)
                    }
                }
            }
        }

        // Try textObject array (treat each as a line)
        if (lines.isEmpty() && params.has("textObject")) {
            val arr = params.optJSONArray("textObject")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i)
                    if (item != null) {
                        val content = item.optString("content", "")
                        if (content.isNotEmpty()) lines.add(content)
                    }
                }
            }
        }

        return lines
    }

    private fun truncate(s: String, maxLen: Int): String =
        if (s.length <= maxLen) s else s.take(maxLen) + "..."
}

package com.fersaiyan.cyanbridge.plugins.mentra

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.ByteArrayInputStream

/**
 * The UI layer of a running miniapp: its `ui/` files in a WebView, sandboxed like the MemoMind
 * Web-plugin host.
 * - Served only from its own origin (`https://<key>.mentra-miniapp.androidplatform.net`), file by
 *   file from the installed package; no file://, content:// or universal access.
 * - The page talks to its background only through `window.mentra` (Mentra's UI shim, injected at
 *   document start with `window.MentraOS`), whose `ReactNativeWebView.postMessage` is an
 *   origin-scoped web message listener (`MentraHostBridge`), never `addJavascriptInterface`.
 *   Messages from other frames or origins are dropped.
 * - Top-level navigation stays on the origin; http(s) links open in the browser. Pop-ups,
 *   camera/microphone/geolocation prompts and third-party cookies are refused. Subresources may
 *   load from the network, as in the Mentra App (store UIs use web fonts and map tiles).
 */
@SuppressLint("SetJavaScriptEnabled")
class MiniappUiWebView(
    context: Context,
    private val running: RunningMiniapp,
    private val store: MiniappStore,
    darkTheme: Boolean,
    private val onRendererGone: () -> Unit,
) {
    val webView = WebView(context)
    private val app = running.app
    private val main = Handler(Looper.getMainLooper())
    private var destroyed = false

    init {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = false
            setGeolocationEnabled(false)
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_NO_CACHE
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false)
        webView.webViewClient = Client()
        webView.webChromeClient = Chrome()
        val origins = setOf(app.origin)
        WebViewCompat.addWebMessageListener(webView, BRIDGE_OBJECT, origins) { _, message, sourceOrigin, isMainFrame, _ ->
            val data = message.data
            if (destroyed || !isMainFrame || sourceOrigin.toString() != app.origin || data == null || data.length > MAX_FRAME_CHARS) {
                return@addWebMessageListener
            }
            running.session.onUiFrame(data)
        }
        val shim = context.assets.open(MentraMiniappHost.UI_SHIM_ASSET).bufferedReader().use { it.readText() }
        WebViewCompat.addDocumentStartJavaScript(webView, globalsScript(darkTheme) + "\n" + shim, origins)
        running.uiSink = { frame -> main.post { deliver(frame) } }
    }

    fun load() {
        app.uiUrl?.let(webView::loadUrl)
    }

    fun setForeground(visible: Boolean) {
        if (destroyed) return
        if (visible) webView.onResume() else webView.onPause()
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        running.uiSink = null
        running.session.onUiClosed()
        runCatching { WebViewCompat.removeWebMessageListener(webView, BRIDGE_OBJECT) }
        webView.stopLoading()
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
    }

    private fun deliver(frame: String) {
        if (destroyed) return
        // A JSON object is a JavaScript expression once the two line separators are escaped.
        val expression = frame.replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")
        webView.evaluateJavascript("if (window.__mentra && window.__mentra.recv) window.__mentra.recv($expression);", null)
    }

    /** `window.MentraOS` (theme, safe areas, host features) and the `ReactNativeWebView` the shim posts to. */
    private fun globalsScript(darkTheme: Boolean): String {
        val globals = buildJsonObject {
            put("packageName", app.manifest.packageName)
            put("platform", "android")
            putJsonArray("capabilities") { add(JsonPrimitive("open_url")) }
            putJsonObject("safeAreaInsets") { put("top", 0); put("bottom", 0); put("left", 0); put("right", 0) }
            put("colorScheme", if (darkTheme) "dark" else "light")
            put("miniappLocal", true)
        }
        return """
            (function () {
              if (window.MentraOS && window.ReactNativeWebView) return;
              window.MentraOS = $globals;
              window.ReactNativeWebView = {
                postMessage: function (data) { try { $BRIDGE_OBJECT.postMessage(String(data)); } catch (e) {} }
              };
            })();
        """.trimIndent()
    }

    private inner class Client : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val url = request.url
            if (url.scheme == "https" && url.host == app.host) return servePackage(request)
            if (url.scheme == "https" || url.scheme == "http") return null
            return response(403, ByteArray(0))
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url
            if (url.scheme == "https" && url.host == app.host) return false
            if (request.isForMainFrame && (url.scheme == "https" || url.scheme == "http")) {
                runCatching { view.context.startActivity(Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            return true
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail?): Boolean {
            Log.w(TAG, "Miniapp UI renderer gone (crash=${detail?.didCrash()})")
            destroy()
            onRendererGone()
            return true
        }
    }

    private class Chrome : WebChromeClient() {
        override fun onPermissionRequest(request: PermissionRequest) = request.deny()

        override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback) =
            callback.invoke(origin, false, false)
    }

    private fun servePackage(request: WebResourceRequest): WebResourceResponse {
        if (request.method != "GET" && request.method != "HEAD") return response(405, ByteArray(0))
        val path = Uri.decode(request.url.encodedPath.orEmpty()).removePrefix("/").ifEmpty { app.manifest.uiEntry.orEmpty() }
        val file = store.payloadFile(app, path) ?: return response(404, ByteArray(0))
        val mime = mimeType(path)
        val headers = mapOf("Cache-Control" to "no-store", "X-Content-Type-Options" to "nosniff", "Referrer-Policy" to "no-referrer")
        val textual = mime.startsWith("text/") || mime.endsWith("javascript") || mime.endsWith("json") || mime.endsWith("svg+xml")
        return WebResourceResponse(mime, if (textual) "utf-8" else null, 200, "OK", headers, file.inputStream())
    }

    private fun response(status: Int, body: ByteArray) =
        WebResourceResponse("text/plain", null, status, if (status == 404) "Not Found" else "Refused", mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(body))

    companion object {
        private const val TAG = "MentraMiniappUi"
        private const val BRIDGE_OBJECT = "MentraHostBridge"
        private const val MAX_FRAME_CHARS = 1024 * 1024

        /** The origin-scoped listener and document-start script this host needs. */
        val isSupported: Boolean
            get() = WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) &&
                WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)

        private fun mimeType(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
            "html", "htm" -> "text/html"
            "js", "mjs" -> "text/javascript"
            "css" -> "text/css"
            "json", "map" -> "application/json"
            "wasm" -> "application/wasm"
            "svg" -> "image/svg+xml"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "ico" -> "image/x-icon"
            "wav" -> "audio/wav"
            "mp3" -> "audio/mpeg"
            "ogg", "opus" -> "audio/ogg"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            "ttf" -> "font/ttf"
            "otf" -> "font/otf"
            "txt", "md" -> "text/plain"
            else -> "application/octet-stream"
        }
    }
}

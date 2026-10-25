package com.fersaiyan.cyanbridge.devices.memomind.webplugin

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
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
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebMessagePortCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.fersaiyan.cyanbridge.devices.memomind.MemoMindManager
import com.fersaiyan.cyanbridge.devices.memomind.MemoMindMicrophoneBusyException
import com.fersaiyan.cyanbridge.shared.audio.AudioRoutePreference
import com.fersaiyan.cyanbridge.shared.audio.AudioSessionRequest
import com.fersaiyan.cyanbridge.shared.devices.memomind.webplugin.MemoMindWebPluginDevice
import com.fersaiyan.cyanbridge.shared.devices.memomind.webplugin.MemoMindWebPluginHost
import com.fersaiyan.cyanbridge.shared.devices.memomind.webplugin.WebPluginApprovals
import com.fersaiyan.cyanbridge.shared.devices.memomind.webplugin.WebPluginBridgeException
import com.fersaiyan.cyanbridge.shared.devices.memomind.webplugin.WebPluginContract
import com.fersaiyan.cyanbridge.shared.devices.memomind.webplugin.WebPluginErrorCode
import com.fersaiyan.cyanbridge.shared.devices.memomind.webplugin.WebPluginMicrophone
import com.fersaiyan.cyanbridge.shared.devices.memomind.webplugin.WebPluginPage
import com.fersaiyan.cyanbridge.shared.devices.memomind.webplugin.WebPluginStreamPort
import kotlinx.coroutines.CoroutineScope
import java.io.ByteArrayInputStream
import java.security.SecureRandom

/**
 * The Android WebView around one [MemoMindWebPluginHost]. The page is untrusted code, so:
 * - it is served only from its own origin (`https://<key>.mmplugin.androidplatform.net`), and only
 *   the files its manifest lists; no file://, content:// or universal access;
 * - the Bridge object `MemoPluginBridge` exists only in main frames of that origin (an
 *   origin-scoped web message listener, not `addJavascriptInterface`), and messages from other
 *   frames or origins are dropped; responses carry the session token and runtime generation;
 * - without the `network` permission every other request is refused and a Content Security
 *   Policy limits connections to the package; without `audio.playback` media is blocked;
 * - navigation away from the origin, pop-up windows, camera/microphone/geolocation prompts,
 *   file choosers and third-party cookies are refused; a renderer crash closes the plugin.
 */
@SuppressLint("SetJavaScriptEnabled")
class WebPluginRuntime(
    context: Context,
    private val plugin: InstalledWebPlugin,
    approvals: WebPluginApprovals,
    private val store: WebPluginStore,
    manager: MemoMindManager,
    scope: CoroutineScope,
    private val onRendererGone: () -> Unit,
) : WebPluginPage {
    val webView = WebView(context)
    val pluginName: String get() = plugin.manifest.name
    private val network = approvals.has(WebPluginContract.Permission.NETWORK)
    private val playback = approvals.has(WebPluginContract.Permission.AUDIO_PLAYBACK)
    private val random = SecureRandom()
    private val portsSupported = PORT_FEATURES.all(WebViewFeature::isFeatureSupported)
    private var destroyed = false
    private var initialPageStarted = false

    val host = MemoMindWebPluginHost(
        manifest = plugin.manifest,
        approvals = approvals,
        device = MemoMindWebPluginDevice(manager.session, manager.ready) { options, listener ->
            val capture = try {
                manager.microphone.open(
                    request = AudioSessionRequest.ContinuousRecognition(OWNER, route = AudioRoutePreference.DEVICE_SDK),
                    onOpus = listener,
                    options = options,
                )
            } catch (error: MemoMindMicrophoneBusyException) {
                throw WebPluginBridgeException(WebPluginErrorCode.AUDIO_BUSY, "The glasses microphone is in use by ${error.owner}")
            }
            WebPluginMicrophone { capture.close() }
        },
        storage = store.storage(plugin),
        page = this,
        scope = scope,
        secureToken = ::token,
        clock = System::currentTimeMillis,
        audioPortsSupported = portsSupported,
    )

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
            mediaPlaybackRequiresUserGesture = !playback
            cacheMode = WebSettings.LOAD_NO_CACHE
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false)
        webView.webViewClient = Client()
        webView.webChromeClient = Chrome()
        WebViewCompat.addWebMessageListener(webView, BRIDGE_OBJECT, setOf(plugin.origin)) { _, message, sourceOrigin, isMainFrame, _ ->
            val data = message.data
            if (destroyed || !isMainFrame || sourceOrigin.toString() != plugin.origin || data == null) return@addWebMessageListener
            host.onMessage(data)
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(webView, BOOTSTRAP_HOOK, setOf(plugin.origin))
        }
        host.start()
    }

    /** Loads the entry page; the first runtime starts before any page script can ask for its bootstrap. */
    fun load() {
        host.onPageStarted()
        initialPageStarted = true
        webView.loadUrl(plugin.entryUrl)
    }

    fun setForeground(visible: Boolean) {
        if (destroyed) return
        if (visible) webView.onResume() else webView.onPause()
        host.setForeground(visible)
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        host.close()
        runCatching { WebViewCompat.removeWebMessageListener(webView, BRIDGE_OBJECT) }
        webView.stopLoading()
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
    }

    // region WebPluginPage

    override fun bootstrap(sessionToken: String, runtimeGeneration: Long) =
        evaluate("window.__memoPluginBootstrap && window.__memoPluginBootstrap(${jsString(sessionToken)}, $runtimeGeneration)")

    override fun resolve(responseJson: String) = evaluate("window.__memoPluginResolve && window.__memoPluginResolve(${jsJson(responseJson)})")

    override fun emit(eventJson: String) = evaluate("window.__memoPluginEmit && window.__memoPluginEmit(${jsJson(eventJson)})")

    override fun openStreamPort(announcementJson: String, onMessage: (String) -> Unit): WebPluginStreamPort? {
        if (destroyed || !portsSupported) return null
        val ports = WebViewCompat.createWebMessageChannel(webView)
        val hostPort = ports[0]
        hostPort.setWebMessageCallback(object : WebMessagePortCompat.WebMessageCallbackCompat() {
            override fun onMessage(port: WebMessagePortCompat, message: WebMessageCompat?) {
                if (message?.type == WebMessageCompat.TYPE_STRING) message.data?.takeIf { it.length <= 256 }?.let(onMessage)
            }
        })
        WebViewCompat.postWebMessage(webView, WebMessageCompat(announcementJson, arrayOf(ports[1])), Uri.parse(plugin.origin))
        return object : WebPluginStreamPort {
            override fun postBinary(bytes: ByteArray) {
                if (!destroyed) hostPort.postMessage(WebMessageCompat(bytes))
            }

            override fun postText(text: String) {
                if (!destroyed) hostPort.postMessage(WebMessageCompat(text))
            }

            override fun close() {
                runCatching { hostPort.close() }
            }
        }
    }

    // endregion

    private fun evaluate(script: String) {
        if (!destroyed) webView.evaluateJavascript(script, null)
    }

    private fun token(): String = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }

    private inner class Client : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val url = request.url
            if (url.scheme == "https" && url.host == plugin.host) return servePackage(request)
            if (network && (url.scheme == "https" || url.scheme == "http")) return null
            return refused()
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
            !(request.url.scheme == "https" && request.url.host == plugin.host)

        override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
            if (!isPluginUrl(url)) return
            // Reloads and in-plugin navigation start a new runtime generation.
            if (initialPageStarted) initialPageStarted = false else host.onPageStarted()
        }

        override fun onPageFinished(view: WebView, url: String?) {
            if (isPluginUrl(url)) host.onPageLoaded()
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail?): Boolean {
            Log.w(TAG, "Plugin renderer gone (crash=${detail?.didCrash()})")
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

    private fun isPluginUrl(url: String?) = url != null && Uri.parse(url).let { it.scheme == "https" && it.host == plugin.host }

    private fun servePackage(request: WebResourceRequest): WebResourceResponse {
        if (request.method != "GET") return refused()
        val path = request.url.path?.removePrefix("/").orEmpty().ifEmpty { plugin.manifest.entry }
        val file = store.payloadFile(plugin, path) ?: return response(404, "text/plain", ByteArray(0))
        val mime = mimeType(path)
        val headers = mutableMapOf(
            "Cache-Control" to "no-store",
            "X-Content-Type-Options" to "nosniff",
            "Referrer-Policy" to "no-referrer",
        )
        if (mime == "text/html") headers["Content-Security-Policy"] = contentSecurityPolicy()
        return WebResourceResponse(mime, if (mime.startsWith("text/") || mime.endsWith("javascript") || mime.endsWith("json")) "utf-8" else null,
            200, "OK", headers, file.inputStream())
    }

    private fun contentSecurityPolicy(): String {
        val media = when {
            !playback -> "'none'"
            network -> "'self' data: blob: https:"
            else -> "'self' data: blob:"
        }
        val base = "default-src 'self' data: blob: 'unsafe-inline' 'unsafe-eval' 'wasm-unsafe-eval'; " +
            "media-src $media; form-action 'none'; base-uri 'self'; frame-ancestors 'none'"
        return if (network) "$base; connect-src * data: blob:; img-src * data: blob:" else "$base; connect-src 'self' data: blob:"
    }

    private fun refused() = response(403, "text/plain", ByteArray(0))

    private fun response(status: Int, mime: String, body: ByteArray) =
        WebResourceResponse(mime, null, status, if (status == 404) "Not Found" else "Forbidden", mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(body))

    companion object {
        private const val TAG = "MemoMindWebPlugin"
        private const val OWNER = "MemoMind Web plugin"
        const val BRIDGE_OBJECT = "MemoPluginBridge"
        private val PORT_FEATURES = listOf(
            WebViewFeature.CREATE_WEB_MESSAGE_CHANNEL,
            WebViewFeature.POST_WEB_MESSAGE,
            WebViewFeature.WEB_MESSAGE_ARRAY_BUFFER,
            WebViewFeature.WEB_MESSAGE_PORT_POST_MESSAGE,
            WebViewFeature.WEB_MESSAGE_PORT_CLOSE,
            WebViewFeature.WEB_MESSAGE_PORT_SET_MESSAGE_CALLBACK,
        )

        /** Without the origin-scoped message listener the Bridge would need a global JavaScript interface; refuse instead. */
        val isSupported: Boolean get() = WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)

        /**
         * Asks the host for the bootstrap as soon as the SDK installs its handler, whenever the
         * plugin creates it (the SDK waits for `__memoPluginBootstrap` before its first call).
         */
        private val BOOTSTRAP_HOOK = """
            (function () {
              var handler;
              Object.defineProperty(window, '__memoPluginBootstrap', {
                configurable: true,
                get: function () { return handler; },
                set: function (value) {
                  handler = value;
                  try { MemoPluginBridge.postMessage('${MemoMindWebPluginHost.BOOTSTRAP_REQUEST}'); } catch (e) {}
                }
              });
            })();
        """.trimIndent()

        private fun jsString(value: String) = "\"" + value.filter { it.isLetterOrDigit() || it == '-' } + "\""

        /** JSON is a JavaScript expression except for U+2028/U+2029 in old engines; escape them. */
        private fun jsJson(value: String) = value.replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")

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

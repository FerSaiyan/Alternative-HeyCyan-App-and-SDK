package com.fersaiyan.cyanbridge.bridge.runtimes.evenhub

import com.fersaiyan.cyanbridge.ui.nav.NavScreen
import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import androidx.activity.addCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import com.fersaiyan.cyanbridge.ui.nav.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.fersaiyan.cyanbridge.bridge.core.DisplayCommand
import com.fersaiyan.cyanbridge.bridge.core.GlassesBridge
import java.io.BufferedReader
import java.io.InputStreamReader

/** Compose shell around the Android-only WebView and EvenHub JavaScript bridge. */
class EvenHubRuntimeDestination : NavScreen() {
    companion object {
        private const val TAG = EvenHubRuntimeViewModel.TAG
        private const val SHIM_ASSET_PATH = "evenhub-compat-shim.js"
    }

    private val viewModel by viewModels<EvenHubRuntimeViewModel> {
        viewModelFactory { initializer { EvenHubRuntimeViewModel(::showOnGlasses) } }
    }
    private lateinit var webView: WebView
    private var isLoaded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            EvenHubRuntimeScreen(
                url = viewModel.url,
                logs = viewModel.logs,
                onUrlChange = viewModel::onUrlChange,
                onLoad = ::loadUrl,
                onStop = ::shutdown,
                onWebViewCreated = ::configureWebView,
            )
        }
        // Back goes back in the web app first, as the Activity's back key did.
        onBackPressedDispatcher.addCallback(this) {
            if (::webView.isInitialized && webView.canGoBack()) webView.goBack() else finish()
        }
    }

    override fun onResume() {
        super.onResume()
        if (::webView.isInitialized) webView.onResume()
    }

    override fun onPause() {
        if (::webView.isInitialized) webView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        if (::webView.isInitialized) webView.destroy()
        super.onDestroy()
    }


    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView(createdWebView: WebView) {
        webView = createdWebView
        val jsBridge = createJsBridge()
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            builtInZoomControls = true
            displayZoomControls = false
            setSupportZoom(true)
            useWideViewPort = true
            loadWithOverviewMode = true
        }
        webView.addJavascriptInterface(jsBridge, "CyanBridgeEvenHubBridge")
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                viewModel.log("Loading: $url")
                injectShim(view)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                viewModel.log("Page loaded: $url")
                injectShim(view)
            }
        }
    }

    private fun createJsBridge(): EvenHubJsBridge = EvenHubJsBridge(
        onDisplayText = viewModel::displayText,
        onDisplayLines = viewModel::displayLines,
        onDisplayCard = viewModel::displayCard,
        onClearDisplay = viewModel::clearDisplay,
        onExit = {
            viewModel.log("Exit requested")
            finish()
        },
        onLog = viewModel::log,
    )

    private fun loadUrl() {
        if (!::webView.isInitialized) return
        isLoaded = true
        webView.loadUrl(viewModel.loadableUrl())
    }

    private fun shutdown() {
        if (::webView.isInitialized && isLoaded) {
            webView.evaluateJavascript(
                "if (window._evenAppBridgeInstance) { window._evenAppBridgeInstance.shutDownPageContainer(0); }",
                null,
            )
        }
        finish()
    }

    private fun injectShim(view: WebView?) {
        val target = view ?: return
        runCatching { target.evaluateJavascript(readAsset(SHIM_ASSET_PATH), null) }
            .onSuccess { Log.d(TAG, "Shim injected") }
            .onFailure { Log.e(TAG, "Failed to inject shim", it) }
    }

    private fun readAsset(path: String): String = assets.open(path).use { inputStream ->
        BufferedReader(InputStreamReader(inputStream)).use { it.readText() }
    }
}

private suspend fun showOnGlasses(command: DisplayCommand) {
    when (command) {
        is DisplayCommand.Text -> GlassesBridge.showText(command)
        is DisplayCommand.Lines -> GlassesBridge.showLines(command)
        is DisplayCommand.Card -> GlassesBridge.showCard(command)
        DisplayCommand.Clear -> GlassesBridge.clearDisplay()
    }
}

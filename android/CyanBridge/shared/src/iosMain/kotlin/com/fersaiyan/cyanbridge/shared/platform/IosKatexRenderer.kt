package com.fersaiyan.cyanbridge.shared.platform

import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import com.fersaiyan.cyanbridge.shared.generated.resources.Res
import com.fersaiyan.cyanbridge.shared.ui.chat.ChatMathRenderer
import com.fersaiyan.cyanbridge.shared.ui.chat.INLINE_MATH
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readValue
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.compose.resources.ExperimentalResourceApi
import platform.CoreGraphics.CGRectZero
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSNumber
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask
import platform.UIKit.UIColor
import platform.WebKit.WKScriptMessage
import platform.WebKit.WKScriptMessageHandlerProtocol
import platform.WebKit.WKUserContentController
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.darwin.NSObject

/**
 * Typesets chat math with KaTeX bundled in the app (iosMain/composeResources/files/katex,
 * MIT license), so equations render offline. Each formula or math paragraph is a small,
 * transparent, non-interactive WKWebView sized to its content.
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalResourceApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
object IosKatexRenderer : ChatMathRenderer {
    private val FONTS = listOf(
        "AMS-Regular", "Caligraphic-Bold", "Caligraphic-Regular", "Fraktur-Bold", "Fraktur-Regular",
        "Main-Bold", "Main-BoldItalic", "Main-Italic", "Main-Regular", "Math-BoldItalic", "Math-Italic",
        "SansSerif-Bold", "SansSerif-Italic", "SansSerif-Regular", "Script-Regular", "Size1-Regular",
        "Size2-Regular", "Size3-Regular", "Size4-Regular", "Typewriter-Regular",
    ).map { "fonts/KaTeX_$it.woff2" }
    private val ASSETS = listOf("katex.min.js", "katex.min.css") + FONTS

    private val assetsMutex = Mutex()
    private var assetsDirectory: String? = null

    /** Copies KaTeX out of the resources once, so WKWebView can read it as local files. */
    private suspend fun assets(): String = assetsMutex.withLock {
        assetsDirectory?.let { return it }
        val caches = NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true).first() as String
        val directory = "$caches/katex-0.19.0"
        val fileManager = NSFileManager.defaultManager
        fileManager.createDirectoryAtPath("$directory/fonts", true, null, null)
        ASSETS.forEach { asset ->
            val path = "$directory/$asset"
            if (!fileManager.fileExistsAtPath(path)) IosMediaPlatform.writeFile(path, Res.readBytes("files/katex/$asset"))
        }
        directory.also { assetsDirectory = it }
    }

    @Composable
    override fun Formula(latex: String, color: Color, background: Color, modifier: Modifier) {
        MathWebView("""<div data-tex="${escape(latex)}" data-display="1"></div>""", color, background, modifier)
    }

    @Composable
    override fun Paragraph(text: String, color: Color, background: Color, modifier: Modifier) {
        MathWebView(paragraphHtml(text), color, background, modifier)
    }

    /**
     * Compose draws UIKit views through a hole in its canvas, so a transparent page would show
     * the window behind it: the page paints the bubble [background] itself.
     */
    @Composable
    private fun MathWebView(body: String, color: Color, background: Color, modifier: Modifier) {
        var heightPoints by remember(body) { mutableStateOf(22.0) }
        var fileUrl by remember(body) { mutableStateOf<NSURL?>(null) }
        var directoryUrl by remember { mutableStateOf<NSURL?>(null) }
        val sizeHandler = remember { SizeMessageHandler { heightPoints = it } }
        LaunchedEffect(body, color, background) {
            val directory = assets()
            val name = "math-${(body + color.value + background.value).hashCode().toUInt()}.html"
            IosMediaPlatform.writeFile("$directory/$name", page(body, color, background).encodeToByteArray())
            directoryUrl = NSURL.fileURLWithPath(directory)
            fileUrl = NSURL.fileURLWithPath("$directory/$name")
        }
        UIKitView(
            factory = {
                val configuration = WKWebViewConfiguration()
                configuration.userContentController.addScriptMessageHandler(sizeHandler, name = "size")
                WKWebView(frame = CGRectZero.readValue(), configuration = configuration).apply {
                    opaque = false
                    backgroundColor = background.toUIColor()
                    scrollView.backgroundColor = background.toUIColor()
                    scrollView.scrollEnabled = false
                }
            },
            update = { webView ->
                val url = fileUrl
                val directory = directoryUrl
                if (url != null && directory != null && webView.URL?.absoluteString != url.absoluteString) {
                    webView.loadFileURL(url, allowingReadAccessToURL = directory)
                }
            },
            onRelease = { webView ->
                webView.configuration.userContentController.removeScriptMessageHandlerForName("size")
            },
            modifier = modifier.height(heightPoints.dp),
            // Touches go to Compose so the chat keeps scrolling over equations.
            properties = UIKitInteropProperties(interactionMode = null),
        )
    }

    private fun Color.toCss(): String =
        "rgba(${(red * 255).toInt()},${(green * 255).toInt()},${(blue * 255).toInt()},$alpha)"

    private fun Color.toUIColor(): UIColor =
        if (this == Color.Unspecified) UIColor.clearColor
        else UIColor.colorWithRed(red.toDouble(), green.toDouble(), blue.toDouble(), alpha.toDouble())

    private fun page(body: String, color: Color, background: Color): String {
        val css = color.toCss()
        val backgroundCss = if (background == Color.Unspecified) "transparent" else background.toCss()
        return """<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
<link rel="stylesheet" href="katex.min.css"><script src="katex.min.js"></script>
<style>html,body{margin:0;padding:0;background:$backgroundCss;color:$css;font:16px/1.45 -apple-system,sans-serif;-webkit-text-size-adjust:none}
#c{width:100%}.katex-display{margin:2px 0}code{font-family:Menlo,monospace;background:rgba(127,127,127,.15);border-radius:4px;padding:0 3px}</style>
</head><body><div id="c">$body</div><script>
document.querySelectorAll('[data-tex]').forEach(function(el){
  try{katex.render(el.getAttribute('data-tex'),el,{displayMode:el.getAttribute('data-display')==='1',throwOnError:false});}
  catch(e){el.textContent=el.getAttribute('data-tex');}
});
var c=document.getElementById('c');
if(c.scrollWidth>c.clientWidth+1){c.style.zoom=(c.clientWidth/c.scrollWidth);}
function report(){window.webkit.messageHandlers.size.postMessage(c.getBoundingClientRect().height);}
report();document.fonts.ready.then(report);window.addEventListener('load',report);
</script></body></html>"""
    }

    /** Prose with light Markdown (**bold**, *italic*, `code`) and inline math spans. */
    private fun paragraphHtml(text: String): String = buildString {
        var cursor = 0
        INLINE_MATH.findAll(text).forEach { match ->
            append(proseHtml(text.substring(cursor, match.range.first)))
            val raw = match.value
            val latex = if (raw.startsWith("$")) raw.substring(1, raw.length - 1) else raw.substring(2, raw.length - 2)
            append("""<span data-tex="${escape(latex)}"></span>""")
            cursor = match.range.last + 1
        }
        append(proseHtml(text.substring(cursor)))
    }

    private fun proseHtml(text: String): String = escape(text)
        .replace(Regex("\\*\\*(.+?)\\*\\*"), "<b>$1</b>")
        .replace(Regex("(?<![*\\w])\\*(?!\\s)(.+?)(?<!\\s)\\*(?![*\\w])"), "<i>$1</i>")
        .replace(Regex("`([^`]+)`"), "<code>$1</code>")

    private fun escape(text: String): String = text
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}

/** Receives the page height (after KaTeX fonts load) and resizes the Compose slot. */
private class SizeMessageHandler(private val onHeight: (Double) -> Unit) : NSObject(), WKScriptMessageHandlerProtocol {
    override fun userContentController(userContentController: WKUserContentController, didReceiveScriptMessage: WKScriptMessage) {
        val height = (didReceiveScriptMessage.body as? NSNumber)?.doubleValue ?: return
        if (height > 0) onHeight(height + 2)
    }
}

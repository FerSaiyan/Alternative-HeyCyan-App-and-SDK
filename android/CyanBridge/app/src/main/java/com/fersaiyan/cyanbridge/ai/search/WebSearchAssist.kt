package com.fersaiyan.cyanbridge.ai.search

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONTokener
import java.net.URLEncoder
import kotlin.coroutines.resume

/**
 * Web lookups for spoken `[search]` questions with no API key: a search engine's results page is loaded in
 * an off-screen WebView on the user's phone (their own IP, like a normal search) and read from the page.
 * Plain HTTP clients and headless browsers get bot challenges, so this needs the real WebView.
 *
 * Each engine's page script reports the same fields: the engine's AI answer (DuckDuckGo Search Assist,
 * Google AI Overview), whether one may still come, an instant-answer box (weather for the IP's city, a
 * converter, ...), result snippets, and whether the engine served its bot challenge.
 */
enum class WebSearchEngine {
    DUCKDUCKGO {
        // `assist=true` asks for Search Assist, which mostly answers English queries. No region (`kl`) is set:
        // a region localises the boxes but drops most AI answers.
        override fun url(query: String, languageTag: String?): String =
            "https://duckduckgo.com/?q=${encode(query)}&ia=web&assist=true"

        override val readPage = """
            (() => {
              const text = document.body ? document.body.innerText : '';
              const answer = document.querySelector('[data-testid="duckassist-answer-content"]');
              // Top organic results as "title: description" (ads are not data-testid=result).
              const snippets = [...document.querySelectorAll('[data-testid="result"]')].map(r => {
                const title = r.querySelector('[data-testid="result-title-a"]');
                const snippet = r.querySelector('[data-result="snippet"]');
                return [title && title.innerText.trim(), snippet && snippet.innerText.trim()].filter(Boolean).join(': ');
              }).filter(Boolean).slice(0, 3);
              // Instant answers (forecast, places, ...) sit above the first organic result; ads are skipped.
              let box = null;
              for (const e of document.querySelectorAll('ol.react-results--main > li[data-layout]')) {
                const layout = e.getAttribute('data-layout');
                if (layout === 'ad') continue;
                if (layout !== 'organic' && layout !== 'related_searches') box = e;
                break;
              }
              return JSON.stringify({
                // Paragraphs only: the answer box also holds the source chips.
                answer: answer ? ([...answer.querySelectorAll('p, li')].map(e => e.innerText.trim()).filter(Boolean)
                  .join(' ') || answer.innerText.trim()) : '',
                // The Search Assist tab shows right away on pages that can get an answer.
                pending: !!document.querySelector('[data-testid^="duckassist"], a[data-assist-icon-trigger]'),
                complete: false,
                snippets,
                infoBox: box ? box.innerText : '',
                loaded: document.readyState === 'complete' && !!document.querySelector('[data-testid="mainline"]'),
                blocked: /bots use DuckDuckGo|Unexpected error\. Please try again/.test(text),
              });
            })()
        """.trimIndent()
    },

    GOOGLE {
        // `hl` sets the interface (and AI Overview) language; the query language matters as well.
        override fun url(query: String, languageTag: String?): String =
            "https://www.google.com/search?q=${encode(query)}" + languageTag?.let { "&hl=${encode(it)}" }.orEmpty()

        override val readPage = """
            (() => {
              const text = document.body ? document.body.innerText : '';
              // The AI Overview; "People also ask" answers (under [data-initq]) use the same block and are skipped.
              const overview = [...document.querySelectorAll('[data-subtree="aimc"]')].find(e => !e.closest('[data-initq]'));
              const main = overview && overview.querySelector('[data-container-id="main-col"]');
              // Lead paragraph only: the overview goes on with headings, lists and sources.
              const lead = main ? ((main.innerText || '').split('\n').map(l => l.trim())
                  .find(l => l.length > 20 && !/^AI Mode replied|function\s*\(/.test(l)) || '')
                // Inline source chips trail the last sentence ("… nos Estados Unidos. InfoMoney +").
                .replace(/([.!?…])\s+[^.!?…]{1,40}$/, '$1') : '';
              // Top organic results as "title: description"; ads live outside #rso (#tads).
              const snippets = [...document.querySelectorAll('#rso .VwiC3b')].map(snippet => {
                const block = snippet.closest('#rso > div') || snippet.parentElement;
                const heading = block && block.querySelector('h3, [role="heading"]');
                return [heading && heading.innerText.trim(), snippet.innerText.trim()].filter(Boolean).join(': ');
              }).filter(Boolean).slice(0, 3);
              // Instant answers (weather, converters, ...) are the result blocks above the web results.
              const box = [];
              const rso = document.querySelector('#rso');
              if (rso) for (const c of rso.children) {
                if (c.querySelector('.VwiC3b, [data-subtree="aimc"], [data-initq]')) break;
                const t = (c.innerText || '').trim();
                if (t) box.push(t);
              }
              return JSON.stringify({
                answer: lead,
                pending: !!overview,
                complete: !!overview && overview.getAttribute('data-complete') === 'true',
                snippets,
                infoBox: box.join('\n'),
                // Rendered results page (an intermediate page can be complete with an empty body).
                loaded: document.readyState === 'complete' && !!document.querySelector('#rso, #botstuff'),
                blocked: location.pathname.startsWith('/sorry') || /unusual traffic|tráfego incomum|not a robot/i.test(text),
              });
            })()
        """.trimIndent()
    },
    ;

    abstract fun url(query: String, languageTag: String?): String
    abstract val readPage: String

    protected fun encode(value: String): String = URLEncoder.encode(value.trim(), "UTF-8")
}

object WebSearchAssist {
    data class Result(
        val engine: WebSearchEngine,
        /** The engine's AI answer (Search Assist, AI Overview lead paragraph), or null when it wrote none. */
        val answer: String?,
        /** Text of the engine's instant-answer box (weather forecast for the IP's city, converters, ...). */
        val infoBox: String?,
        /** Top organic results (ads skipped) as "title: description", at most 3. */
        val snippets: List<String>,
        /** The engine answered with its bot challenge or an error page. */
        val blocked: Boolean,
        val elapsedMs: Long,
        /** Why nothing could be read, or null when the page loaded (even if it had no results). */
        val failure: Failure? = null,
    ) {
        val foundSomething: Boolean get() = answer != null || infoBox != null || snippets.isNotEmpty()
    }

    enum class Failure {
        /** No WebView on the phone (Android System WebView missing, disabled or updating). */
        WEBVIEW_UNAVAILABLE,
        /** The results page didn't load (offline, DNS, TLS, HTTP error). */
        NETWORK,
        /** The engine served its bot challenge ("unusual traffic"). */
        BLOCKED,
        /** Nothing readable before the timeout. */
        TIMEOUT,
    }

    private const val TAG = "WebSearchAssist"
    private const val POLL_MS = 250L
    /** AI answers stream in; one is complete once unchanged for this long (or the page marks it complete). */
    private const val STABLE_MS = 750L
    /** With snippets but no sign of an AI answer for this long, none is coming. */
    private const val NO_ASSIST_MS = 1_000L
    private const val MAX_INFO_BOX_CHARS = 800

    /** AI replies that are not answers ("Sorry, no relevant information was found in our search."). */
    private val NOT_FOUND = Regex("""no relevant information|couldn't find|could not find""", RegexOption.IGNORE_CASE)

    /** [languageTag] (e.g. "pt-BR") sets Google's interface and AI Overview language; DuckDuckGo ignores it. */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun search(
        context: Context,
        query: String,
        engine: WebSearchEngine,
        languageTag: String? = null,
        timeoutMs: Long = 12_000L,
    ): Result = withContext(Dispatchers.Main) {
        val started = SystemClock.elapsedRealtime()
        val web = try {
            WebView(context.applicationContext)
        } catch (error: Exception) {
            // AndroidRuntimeException (MissingWebViewPackageException) when no WebView provider is installed or enabled.
            Log.w(TAG, "No WebView for the web search", error)
            return@withContext Result(engine, null, null, emptyList(), false, SystemClock.elapsedRealtime() - started, Failure.WEBVIEW_UNAVAILABLE)
        }
        var loadError: String? = null
        try {
            web.settings.javaScriptEnabled = true
            web.settings.domStorageEnabled = true
            // The "; wv" marker gets the reduced in-app page; present as the phone's Chrome.
            web.settings.userAgentString = web.settings.userAgentString.replace("; wv)", ")")
            web.webViewClient = object : WebViewClient() {
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) loadError = "${error.errorCode} ${error.description}"
                }

                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                    // Google's bot challenge (/sorry) answers 429; it is reported as BLOCKED from the page itself.
                    if (request.isForMainFrame && response.statusCode >= 400 && response.statusCode != 429) {
                        loadError = "HTTP ${response.statusCode}"
                    }
                }
            }
            // Off-screen but laid out at phone size, so the page renders as it would on screen.
            val width = (412 * context.resources.displayMetrics.density).toInt()
            val height = (900 * context.resources.displayMetrics.density).toInt()
            web.measure(
                android.view.View.MeasureSpec.makeMeasureSpec(width, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(height, android.view.View.MeasureSpec.EXACTLY),
            )
            web.layout(0, 0, width, height)
            web.loadUrl(engine.url(query, languageTag))

            var answer = ""
            var answerSince = 0L
            var snippetsSince = 0L
            var emptySince = 0L
            var page = PageState("", false, false, emptyList(), "", false, false)
            while (SystemClock.elapsedRealtime() - started < timeoutMs) {
                delay(POLL_MS)
                if (loadError != null) break
                page = readPage(web, engine.readPage) ?: continue
                val now = SystemClock.elapsedRealtime()
                if (page.blocked) break
                if (page.answer != answer) {
                    answer = page.answer
                    answerSince = now
                }
                if (answer.isNotEmpty() && (page.complete || now - answerSince >= STABLE_MS)) break
                if (page.snippets.isNotEmpty() && snippetsSince == 0L) snippetsSince = now
                if (!page.pending && snippetsSince != 0L && now - snippetsSince >= NO_ASSIST_MS) break
                // Loaded with nothing at all (no results for the query): stop after the same grace period.
                if (page.loaded && !page.pending && page.snippets.isEmpty() && page.infoBox.isBlank()) {
                    if (emptySince == 0L) emptySince = now else if (now - emptySince >= NO_ASSIST_MS) break
                }
            }
            Result(
                engine = engine,
                answer = answer.ifBlank { null }?.takeUnless { NOT_FOUND.containsMatchIn(it) },
                infoBox = page.infoBox.replace(Regex("""\n\s*\n"""), "\n").trim().take(MAX_INFO_BOX_CHARS).ifBlank { null },
                snippets = page.snippets,
                blocked = page.blocked,
                elapsedMs = SystemClock.elapsedRealtime() - started,
                failure = when {
                    page.blocked -> Failure.BLOCKED
                    loadError != null -> Failure.NETWORK
                    answer.isEmpty() && page.infoBox.isBlank() && page.snippets.isEmpty() && !page.loaded -> Failure.TIMEOUT
                    else -> null
                },
            ).also {
                Log.i(
                    TAG,
                    "engine=$engine query='${query.take(80)}' answer=${it.answer != null} infoBox=${it.infoBox != null} " +
                        "snippets=${it.snippets.size} failure=${it.failure} ${loadError.orEmpty()} ms=${it.elapsedMs}",
                )
                if (it.answer == null && it.infoBox == null) it.snippets.forEach { r -> Log.d(TAG, "result: ${r.take(300)}") }
                if (!it.foundSomething) {
                    // What the page showed instead (consent page, layout change, ...).
                    pageExcerpt(web)?.let { excerpt -> Log.i(TAG, "nothing read; url=${web.url} page='$excerpt'") }
                }
            }
        } finally {
            web.stopLoading()
            web.destroy()
        }
    }

    private data class PageState(
        val answer: String,
        val pending: Boolean,
        val complete: Boolean,
        val snippets: List<String>,
        val infoBox: String,
        val blocked: Boolean,
        /** The results page finished loading (an empty one means the search found nothing). */
        val loaded: Boolean,
    )

    private suspend fun pageExcerpt(web: WebView): String? = suspendCancellableCoroutine { done ->
        web.evaluateJavascript("(document.body ? document.body.innerText : '').slice(0, 400)") { raw ->
            val text = runCatching { JSONTokener(raw).nextValue() as String }.getOrNull()?.replace('\n', '/')
            if (done.isActive) done.resume(text)
        }
    }

    private suspend fun readPage(web: WebView, script: String): PageState? = suspendCancellableCoroutine { done ->
        web.evaluateJavascript(script) { raw ->
            // evaluateJavascript hands back the returned string JSON-encoded.
            val state = runCatching {
                val json = JSONObject(JSONTokener(raw).nextValue() as String)
                val snippets = json.getJSONArray("snippets")
                PageState(
                    answer = json.optString("answer"),
                    pending = json.optBoolean("pending"),
                    complete = json.optBoolean("complete"),
                    snippets = List(snippets.length()) { snippets.getString(it) },
                    infoBox = json.optString("infoBox"),
                    blocked = json.optBoolean("blocked"),
                    loaded = json.optBoolean("loaded"),
                )
            }.getOrNull()
            if (done.isActive) done.resume(state)
        }
    }
}

package com.fersaiyan.cyanbridge.plugins.mentra

import android.util.Log
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.function
import com.fersaiyan.cyanbridge.shared.plugins.mentra.MiniappDispatchException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * One miniapp's background layer in its own QuickJS context and thread, as Mentra's Android
 * host runs it (`crust/.../JSCRuntime.kt`): the six native globals the polyfill expects
 * (`__dispatch`, `__hostLog`, `__hostError`, `__hostUnhandledRejection`, `__nativeSetTimeout`,
 * `__nativeClearTimer`), then `startup.js`, then the miniapp's IIFE bundle. Host-to-JS traffic
 * goes through [deliver], evaluated in order on the context's thread after the current call
 * returns, so `__dispatch` never re-enters the engine.
 */
class QuickJsMiniappEngine(
    private val packageName: String,
    private val dispatch: (iface: String, method: String, argsJson: String) -> String?,
    private val onLog: (level: String, argsJson: String) -> Unit,
    private val onError: (kind: String, detailJson: String) -> Unit,
) {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "Miniapp-$packageName").apply { isDaemon = true }
    }
    private val thread: ExecutorCoroutineDispatcher = executor.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + thread)
    private val timers = ConcurrentHashMap<Int, Job>()
    private var quickJs: QuickJs? = null
    @Volatile private var closed = false

    /** Creates the context and evaluates [polyfill] and [bundle]; false when either fails. */
    suspend fun start(polyfill: String, bundle: String): Boolean = withContext(thread) {
        try {
            // Dispatchers.Unconfined: the engine's own async jobs (none: every global is
            // synchronous) resume on this thread, as in Mentra's host.
            val qjs = QuickJs.create(Dispatchers.Unconfined).apply {
                memoryLimit = MEMORY_LIMIT_BYTES
                maxStackSize = STACK_LIMIT_BYTES
            }
            quickJs = qjs
            installGlobals(qjs)
            evaluate(qjs, polyfill, "mentrajs:startup.js")
            evaluate(qjs, bundle, "mentrajs:miniapp.js")
            true
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Log.w(TAG, "$packageName failed to start: ${error.message}")
            onError("start", error.message ?: error.javaClass.simpleName)
            false
        }
    }

    /** Evaluates `__deliver(envelope)` on the context's thread, in call order. */
    fun deliver(envelopeJson: String) = run("globalThis.__deliver && globalThis.__deliver(${jsString(envelopeJson)})", "deliver")

    fun close() {
        if (closed) return
        closed = true
        timers.values.forEach { it.cancel() }
        timers.clear()
        scope.launch {
            runCatching { quickJs?.close() }
            quickJs = null
        }.invokeOnCompletion {
            scope.cancel()
            thread.close()
        }
    }

    private fun run(script: String, source: String) {
        if (closed) return
        scope.launch {
            val qjs = quickJs ?: return@launch
            try {
                evaluate(qjs, script, "mentrajs:$source")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                onError("exception", error.message ?: error.javaClass.simpleName)
            }
        }
    }

    /** Evaluates for effect only: the trailing `undefined` keeps result conversion out of the way. */
    private suspend fun evaluate(qjs: QuickJs, code: String, filename: String) {
        qjs.evaluate<Any?>("$code\n;undefined", filename, false)
    }

    private fun installGlobals(qjs: QuickJs) {
        qjs.function<String?>("__dispatch") { args ->
            if (closed) return@function null
            val iface = args.getOrNull(0) as? String ?: throw IllegalArgumentException("INVALID_ARGS: iface")
            val method = args.getOrNull(1) as? String ?: throw IllegalArgumentException("INVALID_ARGS: method")
            val argsJson = args.getOrNull(2) as? String ?: throw IllegalArgumentException("INVALID_ARGS: argsJson")
            try {
                dispatch(iface, method, argsJson)
            } catch (error: MiniappDispatchException) {
                throw IllegalStateException(error.message)
            }
        }
        qjs.function<Unit>("__hostLog") { args -> onLog(args.getOrNull(0) as? String ?: "log", args.getOrNull(1) as? String ?: "[]") }
        qjs.function<Unit>("__hostError") { args -> onError("uncaught", args.getOrNull(0) as? String ?: "{}") }
        qjs.function<Unit>("__hostUnhandledRejection") { args -> onError("unhandledRejection", args.getOrNull(0) as? String ?: "{}") }
        qjs.function<Unit>("__nativeSetTimeout") { args ->
            val token = (args.getOrNull(0) as? Number)?.toInt() ?: return@function
            val delayMs = (args.getOrNull(1) as? Number)?.toLong()?.coerceAtLeast(0L) ?: 0L
            timers.remove(token)?.cancel()
            timers[token] = scope.launch {
                delay(delayMs)
                timers.remove(token)
                val engine = quickJs ?: return@launch
                try {
                    evaluate(engine, "globalThis.__deliverTimer && globalThis.__deliverTimer($token)", "mentrajs:timer")
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    onError("exception", error.message ?: "timer failed")
                }
            }
        }
        qjs.function<Unit>("__nativeClearTimer") { args ->
            (args.getOrNull(0) as? Number)?.toInt()?.let { timers.remove(it)?.cancel() }
        }
    }

    companion object {
        private const val TAG = "MentraMiniappJs"

        /** Mentra's per-miniapp limits (`JSCRuntime.kt:263-268`). */
        const val MEMORY_LIMIT_BYTES = 32L * 1024 * 1024
        const val STACK_LIMIT_BYTES = 512L * 1024

        /** A JavaScript string literal for [value] (JSON quoting, plus the two line separators). */
        fun jsString(value: String): String {
            val out = StringBuilder(value.length + 16).append('"')
            value.forEach { c ->
                when (c) {
                    '"' -> out.append("\\\"")
                    '\\' -> out.append("\\\\")
                    '\n' -> out.append("\\n")
                    '\r' -> out.append("\\r")
                    '\t' -> out.append("\\t")
                    ' ' -> out.append("\\u2028")
                    ' ' -> out.append("\\u2029")
                    else -> if (c.code < 0x20) out.append("\\u").append(c.code.toString(16).padStart(4, '0')) else out.append(c)
                }
            }
            return out.append('"').toString()
        }
    }
}

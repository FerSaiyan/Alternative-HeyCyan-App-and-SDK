package com.fersaiyan.cyanbridge.media.rtsp

import android.content.Context
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.fersaiyan.cyanbridge.BuildConfig
import com.fersaiyan.cyanbridge.shared.diagnostics.DiagnosticEvents
import com.fersaiyan.cyanbridge.shared.diagnostics.DiagnosticSubsystem
import com.fersaiyan.cyanbridge.shared.prefs.MediaPrefs.VideoFontCache as Prefs
import com.fersaiyan.cyanbridge.shared.prefs.get
import com.fersaiyan.cyanbridge.shared.prefs.set
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import org.videolan.libvlc.LibVLC

/**
 * The full LibVLC 3.7 (VLC 3.0.25) scans the system fonts into a fontconfig cache on its first
 * video output. On the emulators that took 25-28 s, so the first live preview after an install or
 * an update missed its timeouts (HeyCyan: "No RTSP found"; EyeVue: ~38 s to the first frame).
 *
 * The trimmed LibVLC the app ships (third_party/libvlc-trim) has no text renderer and no
 * fontconfig: buildFontCache() returns true at once, so this is a one-time no-op per app version.
 * It stays so that a LibVLC build with fonts would need no other change.
 *
 * The cache is built once per app version in the background, and a preview that starts before it
 * is done waits for it before talking to the glasses, so none of the stream timeouts run during
 * the scan. When the cache is already valid the build returns quickly.
 */
object VlcFontCache {
    private const val TAG = "VlcFontCache"
    private const val START_DELAY_MS = 15_000L
    private const val MAX_WAIT_MS = 90_000L
    /** The order LibVLC.loadLibraries() uses. */
    private val NATIVE_LIBRARIES = listOf("c++_shared", "vlc", "vlcjni")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private var build: Deferred<Boolean>? = null
    private var startNow: CompletableDeferred<Unit>? = null
    @Volatile private var buildThreadId: Int = 0

    /** Called at app start: builds the cache a little later, at background priority. */
    fun prewarmInBackground(context: Context) {
        val app = context.applicationContext
        if (isCurrent(app)) return
        start(app)
    }

    /**
     * Called before a live preview opens LibVLC. Returns at once when the cache is current;
     * otherwise starts (or hurries) the build and waits for it, at most [MAX_WAIT_MS].
     */
    suspend fun awaitReady(context: Context) {
        val app = context.applicationContext
        if (isCurrent(app)) return
        val pending = start(app)
        synchronized(lock) { startNow?.complete(Unit) }
        buildThreadId.takeIf { it != 0 }?.let { tid ->
            runCatching { Process.setThreadPriority(tid, Process.THREAD_PRIORITY_DEFAULT) }
        }
        if (withTimeoutOrNull(MAX_WAIT_MS) { pending.await() } == null) {
            Log.w(TAG, "Font cache still building after ${MAX_WAIT_MS} ms; opening the preview anyway")
        }
    }

    private fun isCurrent(app: Context): Boolean =
        Prefs.builtForVersionCode[app] == BuildConfig.VERSION_CODE

    private fun start(app: Context): Deferred<Boolean> = synchronized(lock) {
        build?.takeIf { it.isActive }?.let { return it }
        val go = CompletableDeferred<Unit>().also { startNow = it }
        scope.async {
            withTimeoutOrNull(START_DELAY_MS) { go.await() }
            buildNow(app)
        }.also { build = it }
    }

    private fun buildNow(app: Context): Boolean {
        if (isCurrent(app)) return true
        val urgent = startNow?.isCompleted == true
        buildThreadId = Process.myTid()
        val previousPriority = Process.getThreadPriority(buildThreadId)
        if (!urgent) runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }
        val started = SystemClock.elapsedRealtime()
        return try {
            // LibVLC calls System.exit(1) when its native libraries do not load (JVM unit tests,
            // a broken install). Load them first so a failure here only skips the cache.
            NATIVE_LIBRARIES.forEach(System::loadLibrary)
            val vlc = LibVLC(app)
            val ok = try {
                vlc.buildFontCache()
            } finally {
                vlc.release()
            }
            val elapsed = SystemClock.elapsedRealtime() - started
            if (ok) Prefs.builtForVersionCode[app] = BuildConfig.VERSION_CODE
            Log.i(TAG, "buildFontCache ok=$ok in $elapsed ms")
            DiagnosticEvents.info(
                DiagnosticSubsystem.LIVE,
                "video font cache",
                mapOf("ok" to ok.toString(), "ms" to elapsed.toString(), "urgent" to urgent.toString()),
            )
            ok
        } catch (error: Throwable) {
            Log.w(TAG, "buildFontCache failed: ${error.javaClass.simpleName}")
            DiagnosticEvents.warn(DiagnosticSubsystem.LIVE, "video font cache failed", throwable = error)
            false
        } finally {
            buildThreadId = 0
            runCatching { Process.setThreadPriority(previousPriority) }
        }
    }
}

package com.fersaiyan.cyanbridge.plugins.walkingaid

import android.content.Context
import android.graphics.Bitmap
import com.fersaiyan.cyanbridge.devices.eyevue.EyevueLivePreviewManager
import com.fersaiyan.cyanbridge.devices.eyevue.EyevueManager
import com.fersaiyan.cyanbridge.devices.metarayban.MetaRaybanManager
import com.fersaiyan.cyanbridge.glasses.GlassesSession
import com.fersaiyan.cyanbridge.glasses.GlassesSessionCoordinator
import com.fersaiyan.cyanbridge.plugins.walkingaid.vision.VisionFrame
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

/** Owns a continuous camera session until cancellation; never takes an existing preview. */
internal class WalkingAidLiveVideoCapture(
    private val context: Context,
    private val eyevueFactory: () -> EyevueLivePreviewManager = {
        EyevueLivePreviewManager(context, EyevueManager.getInstance(context))
    },
) {
    suspend fun collect(mode: WalkingAidVideoMode, onFrame: (VisionFrame) -> Unit) = coroutineScope {
        val lastFrameAt = AtomicLong(System.currentTimeMillis())
        var index = 0
        val deliver: (Bitmap) -> Unit = { bitmap ->
            val now = System.currentTimeMillis()
            lastFrameAt.set(now)
            if (isActive) onFrame(VisionFrame(bitmap, timestampMs = now, captureIndex = index++))
            else bitmap.recycle()
        }
        val watchdog = launch {
            // Includes transport startup. A stale camera must not leave "active" warnings hanging.
            delay(60_000)
            while (isActive) {
                if (System.currentTimeMillis() - lastFrameAt.get() > 15_000) {
                    throw IOException("Glasses video stopped delivering frames. Reconnect and restart Walking Aid.")
                }
                delay(1_000)
            }
        }
        try {
            when (mode) {
                WalkingAidVideoMode.EYEVUE_VIDEO -> collectEyevue(deliver)
                WalkingAidVideoMode.META_VIDEO -> collectMeta(deliver)
                WalkingAidVideoMode.PERIODIC_PHOTOS -> error("Select a live video source")
            }
        } catch (timeout: TimeoutCancellationException) {
            throw IOException("Timed out starting or stopping glasses video. Reconnect and try again.", timeout)
        } finally { watchdog.cancel() }
    }

    private suspend fun collectEyevue(onFrame: (Bitmap) -> Unit): Unit = withContext(Dispatchers.Main.immediate) {
        val lease = GlassesSessionCoordinator.tryAcquireLease(GlassesSession.LIVE_PREVIEW)
            ?: throw IOException("Stop the current glasses transport before starting Walking Aid video.")
        var manager: EyevueLivePreviewManager? = null
        val finished = CompletableDeferred<Unit>()
        try {
            val activeManager = eyevueFactory().also { manager = it }
            activeManager.startFrames(onFrame) { finished.complete(Unit) }
            finished.await()
            throw IOException(activeManager.uiState.value.detail.ifBlank { "Eyevue video stopped" })
        } finally {
            withContext(NonCancellable) {
                try {
                    manager?.stopAndJoin()
                } finally {
                    manager?.release()
                    GlassesSessionCoordinator.release(lease)
                }
            }
        }
    }

    private suspend fun collectMeta(onFrame: (Bitmap) -> Unit): Unit = withContext(Dispatchers.Main.immediate) {
        val manager = MetaRaybanManager.getInstance(context)
        if (!manager.isInitialized.value) manager.initialize()
        if (!manager.awaitCameraReady()) throw IOException("Connect and register the glasses in Meta AI first.")
        val authorized = CompletableDeferred<Unit>()
        manager.checkCameraPermission(
            { authorized.complete(Unit) },
            { authorized.completeExceptionally(IOException("Authorize the glasses camera in Meta setup first.")) },
            { authorized.completeExceptionally(IOException(it)) },
        )
        withTimeout(20_000) { authorized.await() }
        check(GlassesSessionCoordinator.currentSession() == null &&
            manager.deviceSessionState.value == MetaRaybanManager.DeviceSessionState.IDLE) {
            "Stop the current Meta preview or glasses transport before starting Walking Aid video."
        }
        val started = CompletableDeferred<Unit>()
        val failure = CompletableDeferred<Unit>()
        try {
            manager.startSession(
                onSuccess = {
                    manager.startStreaming(
                        onFrame, { started.complete(Unit) },
                        { failure.completeExceptionally(IOException(it)); started.completeExceptionally(IOException(it)) },
                    )
                },
                onError = { failure.completeExceptionally(IOException(it)); started.completeExceptionally(IOException(it)) },
            )
            withTimeout(30_000) { started.await() }
            coroutineScope {
                launch {
                    manager.isStreaming.first { !it }
                    failure.completeExceptionally(IOException("Meta camera stream stopped. Reconnect in Meta AI."))
                }
                failure.await()
            }
        } finally {
            withContext(NonCancellable) {
                manager.stopSession()
                withTimeout(10_000) {
                    manager.deviceSessionState.first { it == MetaRaybanManager.DeviceSessionState.IDLE }
                }
            }
        }
    }
}

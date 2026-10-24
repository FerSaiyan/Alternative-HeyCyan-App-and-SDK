package com.fersaiyan.cyanbridge.media.sync

import com.fersaiyan.cyanbridge.ai.image.ImageQuestionSource
import com.fersaiyan.cyanbridge.devices.eyevue.EyevueManager
import com.fersaiyan.cyanbridge.devices.moyoung.MoyoungW620Manager
import com.fersaiyan.cyanbridge.devices.tunebuds.TuneBudsManager
import com.fersaiyan.cyanbridge.glasses.GlassesSession
import com.fersaiyan.cyanbridge.glasses.GlassesSessionLease
import com.fersaiyan.cyanbridge.shared.glasses.GlassesTransferUiState
import java.io.File

/** Exclusive glasses-session handling the media sync flows borrow from the dashboard Activity. */
internal interface GlassesMediaSessionHost {
    var mediaSessionLease: GlassesSessionLease?

    fun ensureGlassesTransportPermissions(feature: String, onGranted: () -> Unit)

    fun acquireExclusiveGlassesSession(session: GlassesSession): GlassesSessionLease?

    fun releaseExclusiveGlassesSession(lease: GlassesSessionLease?)
}

internal interface VendorMediaSyncHost : GlassesMediaSessionHost {
    fun getOrCreateEyevueManager(): EyevueManager

    fun getOrCreateTuneBudsManager(): TuneBudsManager

    fun getOrCreateMoyoungW620Manager(): MoyoungW620Manager

    fun isTuneBudsAiPhotoInProgress(): Boolean

    fun isImageQuestionWorkActive(): Boolean
}

internal interface GlassesMediaSyncHost : GlassesMediaSessionHost {
    val pendingImageCaptureStartedAtMs: Long
    var pendingImageQuestionSource: ImageQuestionSource

    fun rejectHeyCyanOnlyFeature(feature: String): Boolean

    fun dashboardTransferState(): GlassesTransferUiState

    fun isOtaActive(): Boolean

    fun onImageReadyForQuestion(imagePath: String, source: ImageQuestionSource, transferDurationMs: Long)

    fun cancelParallelAudioQuestion()

    fun requestImageThumbnailForQuestion(sourceTag: String)

    fun clearPendingVoiceImageQuestion(sourceTag: String)

    fun isDecodableImageFile(file: File): Boolean
}

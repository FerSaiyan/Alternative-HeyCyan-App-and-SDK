package com.fersaiyan.cyanbridge.devices.heycyan

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import org.videolan.libvlc.MediaPlayer

/** Headless LibVLC output used by Walking Aid for HeyCyan continuous video. */
internal class HeyCyanVideoFrameOutput(
    private val onFrame: (Bitmap) -> Unit,
    private val onError: (Throwable) -> Unit,
) {
    private val thread = HandlerThread("HeyCyanVisionFrames").apply { start() }
    private val reader = ImageReader.newInstance(640, 480, PixelFormat.RGBX_8888, 3)

    init {
        reader.setOnImageAvailableListener({ source ->
            try {
                source.acquireLatestImage()?.use { image ->
                    val plane = image.planes[0]
                    val paddedWidth = plane.rowStride / plane.pixelStride
                    val padded = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
                    padded.copyPixelsFromBuffer(plane.buffer)
                    padded.setHasAlpha(false)
                    val cropped = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
                    if (padded !== cropped) padded.recycle()
                    onFrame(cropped)
                }
            } catch (error: Exception) {
                onError(error)
            }
        }, Handler(thread.looper))
    }

    fun attach(player: MediaPlayer) {
        player.vlcVout.apply {
            setVideoSurface(reader.surface, null)
            setWindowSize(640, 480)
            attachViews()
        }
    }

    fun close(player: MediaPlayer?) {
        player?.takeUnless { it.isReleased }?.vlcVout?.detachViews()
        reader.setOnImageAvailableListener(null, null)
        reader.close()
        thread.quitSafely()
    }
}

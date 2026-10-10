package com.fersaiyan.cyanbridge.devices.eyevue

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import org.videolan.libvlc.MediaPlayer

/** Software VLC output without a window: the same RTSP decoder can feed local vision. */
internal class EyevueVideoFrameOutput(
    private val onFrame: (Bitmap) -> Unit,
    private val onError: (Throwable) -> Unit,
    private val rotationDegrees: Float = -90f,
) {
    private val thread = HandlerThread("EyevueVisionFrames").apply { start() }
    // VLC's RV32 Android surface uses opaque RGBX, not RGBA. ImageReader requires
    // an exact producer-format match even though both have four bytes per pixel.
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
                    val upright = Bitmap.createBitmap(
                        cropped, 0, 0, cropped.width, cropped.height,
                        Matrix().apply { postRotate(rotationDegrees) }, true,
                    )
                    if (padded !== cropped && padded !== upright) padded.recycle()
                    if (cropped !== upright) cropped.recycle()
                    // Ownership of this independent bitmap passes to the consumer.
                    onFrame(upright)
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

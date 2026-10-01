package com.fersaiyan.cyanbridge.devices.eyevue

import android.content.Context
import android.graphics.Color
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

/**
 * A portrait viewport containing a landscape VLC layout turned 90 degrees left.
 * LibVLC owns the TextureView and surface lifecycle, just as in the vendor app.
 * Swapping the inner layout's dimensions avoids scaling a portrait canvas (and
 * its black borders) to try to fit landscape video.
 */
class EyevueLiveVideoView(context: Context) : FrameLayout(context) {
    private val videoLayout = VLCVideoLayout(context).apply { rotation = -90f }
    private var player: MediaPlayer? = null

    init {
        setBackgroundColor(Color.BLACK)
        clipChildren = true
        clipToPadding = true
        addView(videoLayout)
    }

    fun bindPlayer(next: MediaPlayer?) {
        if (player === next) return
        player?.let { if (!it.isReleased) it.detachViews() }
        player = next
        if (next != null && !next.isReleased) {
            // Texture rendering supports rotation; SurfaceView layers do not.
            next.setUseOrientationFromBounds(true)
            next.attachViews(videoLayout, null, false, true)
            next.setVideoScale(MediaPlayer.ScaleType.SURFACE_BEST_FIT)
            Log.i("EyevueLive", "Inline LibVLC views attached")
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = View.MeasureSpec.getSize(widthMeasureSpec)
        val height = View.MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(width, height)
        videoLayout.measure(
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val width = right - left
        val height = bottom - top
        val x = (width - height) / 2
        val y = (height - width) / 2
        videoLayout.layout(x, y, x + height, y + width)
        videoLayout.pivotX = height / 2f
        videoLayout.pivotY = width / 2f
        if (changed) player?.let { if (!it.isReleased) it.updateVideoSurfaces() }
    }
}

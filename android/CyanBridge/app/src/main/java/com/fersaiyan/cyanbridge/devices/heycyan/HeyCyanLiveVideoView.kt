package com.fersaiyan.cyanbridge.devices.heycyan

import android.content.Context
import android.graphics.Color
import android.util.Log
import android.widget.FrameLayout
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

/** Inline LibVLC surface for the stock HeyCyan realtime RTSP feed. */
class HeyCyanLiveVideoView(context: Context) : FrameLayout(context) {
    private val videoLayout = VLCVideoLayout(context)
    private var player: MediaPlayer? = null

    init {
        setBackgroundColor(Color.BLACK)
        clipChildren = true
        clipToPadding = true
        addView(videoLayout, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun bindPlayer(next: MediaPlayer?) {
        if (player === next) return
        player?.let { if (!it.isReleased) it.detachViews() }
        player = next
        if (next != null && !next.isReleased) {
            next.setUseOrientationFromBounds(true)
            next.attachViews(videoLayout, null, false, true)
            next.setVideoScale(MediaPlayer.ScaleType.SURFACE_BEST_FIT)
            Log.i("LivePreview", "HeyCyan inline LibVLC views attached")
        }
    }
}

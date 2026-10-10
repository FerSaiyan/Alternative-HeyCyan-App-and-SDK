package com.fersaiyan.cyanbridge.devices.tunebuds

import android.content.Context
import android.graphics.Color
import android.widget.FrameLayout
import com.fersaiyan.cyanbridge.media.rtsp.LibVlcVideoView
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

/** TuneBuds uses the stream's orientation; EyeVue's fixed rotation does not apply. */
class TuneBudsLiveVideoView(context: Context) : FrameLayout(context), LibVlcVideoView {
    private val videoLayout = VLCVideoLayout(context)
    private var player: MediaPlayer? = null

    init {
        setBackgroundColor(Color.BLACK)
        addView(videoLayout, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    override fun bindPlayer(next: MediaPlayer?) {
        if (player === next) return
        player?.let { if (!it.isReleased) it.detachViews() }
        player = next
        if (next != null && !next.isReleased) {
            next.setUseOrientationFromBounds(true)
            next.attachViews(videoLayout, null, false, true)
            next.setVideoScale(MediaPlayer.ScaleType.SURFACE_BEST_FIT)
        }
    }
}

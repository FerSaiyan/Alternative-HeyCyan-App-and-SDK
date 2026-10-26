package com.fersaiyan.cyanbridge.devices.mentra

import android.content.Context
import android.graphics.Color
import android.widget.FrameLayout
import com.fersaiyan.cyanbridge.media.rtsp.LibVlcLivePreviewManager
import com.fersaiyan.cyanbridge.media.rtsp.LibVlcVideoView
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

/**
 * Mentra Live preview on the shared LibVLC lifecycle: [MentraLiveStreamConnection] receives the
 * glasses' RTMP stream and serves it as MPEG-TS on loopback. The stream is landscape 16:9, so
 * vision frames need no rotation. Short caching keeps the local hop low-latency.
 */
class MentraLivePreviewManager(context: Context) : LibVlcLivePreviewManager(
    context.applicationContext,
    MentraLiveStreamConnection(context.applicationContext),
    deviceName = "Mentra Live",
    tag = MentraRtmpIngest.TAG,
    networkCachingMs = 300,
    fileCachingMs = 300,
    liveCachingMs = 300,
    frameWidth = 640,
    frameHeight = 360,
    frameRotationDegrees = 0f,
) {
    companion object {
        @Volatile private var dashboard: MentraLivePreviewManager? = null

        /**
         * The preview the dashboard card shows. It outlives the main screen, so a preview keeps
         * its state when the activity is recreated. Walking Aid creates its own instance.
         */
        fun forDashboard(context: Context): MentraLivePreviewManager =
            dashboard ?: synchronized(this) {
                dashboard ?: MentraLivePreviewManager(context.applicationContext).also { dashboard = it }
            }

        fun dashboardOrNull(): MentraLivePreviewManager? = dashboard
    }
}

/** Inline view for the Mentra preview; the stream already has the right orientation. */
class MentraLiveVideoView(context: Context) : FrameLayout(context), LibVlcVideoView {
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
            next.attachViews(videoLayout, null, false, true)
            next.setVideoScale(MediaPlayer.ScaleType.SURFACE_BEST_FIT)
        }
    }
}

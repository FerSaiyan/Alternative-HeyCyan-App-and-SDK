package com.fersaiyan.cyanbridge.devices.tunebuds

import android.content.Context
import com.fersaiyan.cyanbridge.media.rtsp.LibVlcLivePreviewManager

/** AB Mate hotspot/RTSP flow with the official embedded player's 300 ms caching. */
class TuneBudsLivePreviewManager(context: Context, manager: TuneBudsManager) :
    LibVlcLivePreviewManager(
        context, TuneBudsLiveConnection(context, manager), deviceName = "TuneBuds", tag = "TuneBudsLive",
        networkCachingMs = 300, fileCachingMs = 300, liveCachingMs = 300,
    )

package com.fersaiyan.cyanbridge.devices.eyevue

import android.content.Context
import com.fersaiyan.cyanbridge.media.rtsp.LibVlcLivePreviewManager

/** Vendor EyeVue transport and player defaults, including the rotated inline view. */
class EyevueLivePreviewManager internal constructor(context: Context, connection: EyevueLiveConnection) :
    LibVlcLivePreviewManager(context, connection, deviceName = "Eyevue", tag = "EyevueLive") {
    constructor(context: Context, eyevueManager: EyevueManager) :
        this(context, EyevueGlassesLiveConnection(context, eyevueManager))
}

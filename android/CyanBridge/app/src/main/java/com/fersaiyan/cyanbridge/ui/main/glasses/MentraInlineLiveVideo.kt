package com.fersaiyan.cyanbridge.ui.main.glasses

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.fersaiyan.cyanbridge.devices.mentra.MentraLivePreviewManager
import com.fersaiyan.cyanbridge.devices.mentra.MentraLiveVideoView
import com.fersaiyan.cyanbridge.shared.glasses.InlineLiveVideo

/**
 * The Mentra Live preview inside the dashboard's live preview section. It binds the process-wide
 * preview's LibVLC player itself, so the main activity's video slot needs no Mentra branch.
 */
internal object MentraInlineLiveVideo : InlineLiveVideo {
    @Composable
    override fun Content() {
        val preview = MentraLivePreviewManager.forDashboard(LocalContext.current)
        Column(modifier = Modifier.fillMaxWidth()) {
            Spacer(Modifier.height(8.dp))
            AndroidView(
                factory = { context -> MentraLiveVideoView(context) },
                update = { view -> preview.attachVideoView(view) },
                onRelease = { view -> preview.detachVideoView(view) },
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
            )
        }
    }
}

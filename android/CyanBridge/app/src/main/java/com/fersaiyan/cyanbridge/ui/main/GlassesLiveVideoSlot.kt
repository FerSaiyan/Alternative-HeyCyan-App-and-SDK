package com.fersaiyan.cyanbridge.ui.main

import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.fersaiyan.cyanbridge.devices.eyevue.EyevueLivePreviewManager
import com.fersaiyan.cyanbridge.devices.tunebuds.TuneBudsLivePreviewManager
import com.fersaiyan.cyanbridge.ota.LivePreviewManager
import com.fersaiyan.cyanbridge.shared.glasses.GlassesDashboardUiState
import org.videolan.libvlc.MediaPlayer

/**
 * The glasses screen's inline live video (Eyevue, TuneBuds or HeyCyan) with its URL, mute,
 * copy and VLC buttons. Moved out of MainActivity unchanged; the Activity keeps the players and
 * the mute state.
 */
@Composable
internal fun GlassesLiveVideoSlot(
    dashboardState: GlassesDashboardUiState,
    eyevueLivePlayer: MediaPlayer?,
    tuneBudsLivePlayer: MediaPlayer?,
    heyCyanLivePlayer: MediaPlayer?,
    eyevueLivePreviewManager: EyevueLivePreviewManager?,
    tuneBudsLivePreviewManager: TuneBudsLivePreviewManager?,
    livePreviewManager: LivePreviewManager,
    eyevueMuted: Boolean,
    onEyevueMuted: (Boolean) -> Unit,
    tuneBudsMuted: Boolean,
    onTuneBudsMuted: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val live = dashboardState.livePreview
    val isEyevueLive = dashboardState.showEyevueControls
    val isHeyCyanLive = dashboardState.showHeyCyanControls
    val isTuneBudsLive = dashboardState.showTuneBudsControls
    val livePlayer = when {
        isEyevueLive -> eyevueLivePlayer
        isTuneBudsLive -> tuneBudsLivePlayer
        else -> heyCyanLivePlayer
    }
    if ((isEyevueLive || isHeyCyanLive || isTuneBudsLive) &&
        live.isPlaying &&
        live.streamUrl != null &&
        livePlayer != null
    ) {
        val liveUrl = live.streamUrl ?: ""
        androidx.compose.foundation.layout.Column(
            modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
        ) {
            androidx.compose.foundation.layout.Spacer(
                modifier = androidx.compose.ui.Modifier.height(8.dp),
            )
            if (isEyevueLive) {
                androidx.compose.ui.viewinterop.AndroidView(
                    factory = { ctx ->
                        com.fersaiyan.cyanbridge.devices.eyevue.EyevueLiveVideoView(ctx)
                    },
                    update = { view -> eyevueLivePreviewManager?.attachVideoView(view) },
                    onRelease = { view -> eyevueLivePreviewManager?.detachVideoView(view) },
                    modifier = androidx.compose.ui.Modifier
                        .fillMaxWidth()
                        .aspectRatio(0.75f),
                )
            } else if (isTuneBudsLive) {
                androidx.compose.ui.viewinterop.AndroidView(
                    factory = { ctx -> com.fersaiyan.cyanbridge.devices.tunebuds.TuneBudsLiveVideoView(ctx) },
                    update = { view -> tuneBudsLivePreviewManager?.attachVideoView(view) },
                    onRelease = { view -> tuneBudsLivePreviewManager?.detachVideoView(view) },
                    modifier = androidx.compose.ui.Modifier.fillMaxWidth().aspectRatio(4f / 3f),
                )
            } else {
                androidx.compose.ui.viewinterop.AndroidView(
                    factory = { ctx ->
                        com.fersaiyan.cyanbridge.devices.heycyan.HeyCyanLiveVideoView(ctx)
                    },
                    update = { view -> livePreviewManager.attachVideoView(view) },
                    onRelease = { view -> livePreviewManager.detachVideoView(view) },
                    modifier = androidx.compose.ui.Modifier
                        .fillMaxWidth()
                        .aspectRatio(4f / 3f),
                )
            }
            androidx.compose.foundation.layout.Spacer(
                modifier = androidx.compose.ui.Modifier.height(8.dp),
            )
            androidx.compose.material3.Text(
                text = liveUrl,
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
            )
            androidx.compose.material3.Text(
                text = "Shared local RTSP feed — keep Live preview running",
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (isEyevueLive) {
                androidx.compose.material3.TextButton(
                    onClick = {
                        onEyevueMuted(!eyevueMuted)
                    },
                ) {
                    androidx.compose.material3.Text(
                        if (eyevueMuted) "Listen to audio" else "Mute audio",
                    )
                }
            }
            if (isTuneBudsLive) {
                androidx.compose.material3.TextButton(onClick = {
                    onTuneBudsMuted(!tuneBudsMuted)
                }) {
                    androidx.compose.material3.Text(if (tuneBudsMuted) "Listen to audio" else "Mute audio")
                }
            }
            androidx.compose.foundation.layout.Row(
                modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
            ) {
                androidx.compose.material3.TextButton(
                    onClick = {
                        val clipboard =
                            context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                as android.content.ClipboardManager
                        clipboard.setPrimaryClip(
                            android.content.ClipData.newPlainText(
                                "Glasses live URL",
                                liveUrl,
                            ),
                        )
                        android.widget.Toast.makeText(
                            context,
                            "Stream URL copied — open it while live preview is running",
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    },
                ) {
                    androidx.compose.material3.Text("Copy URL")
                }
                androidx.compose.foundation.layout.Spacer(
                    modifier = androidx.compose.ui.Modifier.width(8.dp),
                )
                androidx.compose.material3.TextButton(
                    onClick = {
                        if (isEyevueLive) {
                            onEyevueMuted(true)
                        }
                        if (isTuneBudsLive) {
                            onTuneBudsMuted(true)
                        }
                        val uri = android.net.Uri.parse(liveUrl)
                        val vlcIntent = android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            uri,
                        ).setPackage("org.videolan.vlc")
                        val opened = runCatching {
                            context.startActivity(vlcIntent)
                            true
                        }.getOrDefault(false)
                        if (!opened) {
                            runCatching {
                                context.startActivity(
                                    android.content.Intent(
                                        android.content.Intent.ACTION_VIEW,
                                        uri,
                                    ),
                                )
                            }.onFailure {
                                android.widget.Toast.makeText(
                                    context,
                                    "No app found for RTSP",
                                    android.widget.Toast.LENGTH_SHORT,
                                ).show()
                            }
                        }
                    },
                ) {
                    androidx.compose.material3.Text("Open in VLC")
                }
            }
        }
    }

}

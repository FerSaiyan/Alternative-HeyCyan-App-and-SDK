package com.fersaiyan.cyanbridge.ui

import com.fersaiyan.cyanbridge.ui.nav.NavScreen
import com.fersaiyan.cyanbridge.ui.nav.AppRoute
import android.os.Bundle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.fersaiyan.cyanbridge.R
import com.fersaiyan.cyanbridge.devices.mentra.MentraBroadcastController
import com.fersaiyan.cyanbridge.devices.mentra.MentraBroadcastRefusal
import com.fersaiyan.cyanbridge.devices.mentra.MentraBroadcastState
import com.fersaiyan.cyanbridge.devices.mentra.MentraLiveManager
import com.fersaiyan.cyanbridge.shared.devices.mentra.MentraBroadcastPhase
import com.fersaiyan.cyanbridge.shared.devices.mentra.MentraBroadcastUrlProblem
import com.fersaiyan.cyanbridge.shared.prefs.MediaPrefs
import com.fersaiyan.cyanbridge.shared.prefs.get
import com.fersaiyan.cyanbridge.shared.prefs.set
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Mentra Live broadcast to the user's own RTMP/SRT server, opened from the dashboard's live
 * preview section. The broadcast lives in [MentraBroadcastController]; this screen edits the URL,
 * starts and stops it and shows the glasses' status events. The glasses' Wi-Fi is set up on the
 * Mentra firmware update screen, which this screen opens when the glasses have none.
 */
class MentraBroadcastDestination : NavScreen() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        keepScreenOnWhile(MentraBroadcastController.state.map { it.phase.isActive }.distinctUntilChanged())
        setContent { BroadcastScreen() }
    }

    @Composable
    private fun BroadcastScreen() {
        val state by MentraBroadcastController.state.collectAsState()
        var url by remember { mutableStateOf(MediaPrefs.MentraBroadcast.streamUrl[this]) }
        var refusal by remember { mutableStateOf<String?>(null) }
        var glassesWifi by remember { mutableStateOf(glassesWifiSsid()) }
        LaunchedEffect(Unit) {
            while (true) {
                glassesWifi = glassesWifiSsid()
                delay(2_000)
            }
        }
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.mentra_broadcast_title), style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(R.string.mentra_broadcast_intro), style = MaterialTheme.typography.bodyMedium)
                Text(
                    glassesWifi?.let { stringResource(R.string.mentra_broadcast_wifi, it) }
                        ?: stringResource(R.string.mentra_broadcast_wifi_none),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (glassesWifi == null && !state.phase.isActive) {
                    OutlinedButton(onClick = { startActivity(AppRoute.MENTRA_FIRMWARE_UPDATE.intent(this@MentraBroadcastDestination)) }) {
                        Text(stringResource(R.string.mentra_broadcast_wifi_setup))
                    }
                }
                OutlinedTextField(
                    value = url,
                    onValueChange = {
                        url = it
                        refusal = null
                    },
                    enabled = !state.phase.isActive,
                    singleLine = true,
                    label = { Text(stringResource(R.string.mentra_broadcast_url_label)) },
                    placeholder = { Text(stringResource(R.string.mentra_broadcast_url_hint)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth().testTag("mentra_broadcast_url"),
                )
                refusal?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        enabled = !state.phase.isActive,
                        onClick = {
                            MediaPrefs.MentraBroadcast.streamUrl[this@MentraBroadcastDestination] = url.trim()
                            refusal = MentraBroadcastController.start(this@MentraBroadcastDestination, url)?.let(::message)
                        },
                    ) { Text(stringResource(R.string.mentra_broadcast_start)) }
                    OutlinedButton(
                        enabled = state.phase.isActive && state.phase != MentraBroadcastPhase.STOPPING,
                        onClick = MentraBroadcastController::stop,
                    ) { Text(stringResource(R.string.mentra_broadcast_stop)) }
                }
                Status(state)
                OutlinedButton(onClick = ::finish) { Text(stringResource(R.string.mentra_broadcast_close)) }
            }
        }
    }

    @Composable
    private fun Status(state: MentraBroadcastState) {
        Text(
            stringResource(R.string.mentra_broadcast_status, stringResource(phaseLabel(state.phase))),
            style = MaterialTheme.typography.titleMedium,
        )
        state.destination?.let { Text(stringResource(R.string.mentra_broadcast_destination, it)) }
        if (state.phase != MentraBroadcastPhase.IDLE) {
            Text(
                stringResource(
                    if (state.includesAudio) R.string.mentra_broadcast_audio_on else R.string.mentra_broadcast_audio_off,
                ),
            )
        }
        state.video?.let { Text(stringResource(R.string.mentra_broadcast_video, it)) }
        state.stats?.let { Text(stringResource(R.string.mentra_broadcast_stats, it)) }
        state.detail?.let { detail ->
            Text(
                detail,
                color = if (state.phase == MentraBroadcastPhase.FAILED) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }

    private fun glassesWifiSsid(): String? =
        MentraLiveManager.instanceOrNull()?.takeIf { it.connected }?.wifi()?.ssid?.takeIf { it.isNotBlank() }

    private fun phaseLabel(phase: MentraBroadcastPhase): Int = when (phase) {
        MentraBroadcastPhase.IDLE -> R.string.mentra_broadcast_phase_idle
        MentraBroadcastPhase.STARTING -> R.string.mentra_broadcast_phase_starting
        MentraBroadcastPhase.LIVE -> R.string.mentra_broadcast_phase_live
        MentraBroadcastPhase.RECONNECTING -> R.string.mentra_broadcast_phase_reconnecting
        MentraBroadcastPhase.STOPPING -> R.string.mentra_broadcast_phase_stopping
        MentraBroadcastPhase.STOPPED -> R.string.mentra_broadcast_phase_stopped
        MentraBroadcastPhase.FAILED -> R.string.mentra_broadcast_phase_failed
    }

    private fun message(refusal: MentraBroadcastRefusal): String = when (refusal) {
        is MentraBroadcastRefusal.InvalidUrl -> getString(
            when (refusal.problem) {
                MentraBroadcastUrlProblem.EMPTY -> R.string.mentra_broadcast_url_empty
                MentraBroadcastUrlProblem.UNSUPPORTED_SCHEME -> R.string.mentra_broadcast_url_scheme
                MentraBroadcastUrlProblem.MISSING_HOST -> R.string.mentra_broadcast_url_host
                MentraBroadcastUrlProblem.LOCAL_HOST -> R.string.mentra_broadcast_url_local
                MentraBroadcastUrlProblem.MISSING_STREAM_PATH -> R.string.mentra_broadcast_url_path
                MentraBroadcastUrlProblem.MISSING_PORT -> R.string.mentra_broadcast_url_port
            },
        )
        MentraBroadcastRefusal.NotReady -> getString(R.string.mentra_broadcast_not_ready)
        MentraBroadcastRefusal.NoWifi -> getString(R.string.mentra_broadcast_no_wifi)
        MentraBroadcastRefusal.FirmwareUpdate -> getString(R.string.mentra_broadcast_firmware_update)
        is MentraBroadcastRefusal.Busy -> getString(R.string.mentra_broadcast_busy, refusal.session)
        MentraBroadcastRefusal.AlreadyActive -> getString(R.string.mentra_broadcast_already_active)
    }
}

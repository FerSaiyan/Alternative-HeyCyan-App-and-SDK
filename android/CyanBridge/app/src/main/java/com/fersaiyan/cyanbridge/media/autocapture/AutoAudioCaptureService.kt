package com.fersaiyan.cyanbridge.media.autocapture

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.fersaiyan.cyanbridge.MainActivity
import com.fersaiyan.cyanbridge.R
import com.fersaiyan.cyanbridge.devices.DeviceProfileStore
import com.fersaiyan.cyanbridge.glasses.GlassesSessionCoordinator
import com.fersaiyan.cyanbridge.plugins.PluginVoicePermissions
import com.fersaiyan.cyanbridge.ui.ensureBluetoothRuntimePermission
import com.fersaiyan.cyanbridge.ui.hasBluetooth
import com.fersaiyan.cyanbridge.ui.hasNotificationPermission
import com.fersaiyan.cyanbridge.ui.hasWifiP2pPermission
import com.oudmon.ble.base.bluetooth.BleOperateManager
import com.oudmon.ble.base.communication.LargeDataHandler
import com.fersaiyan.cyanbridge.audio.MeetingCapturePrefs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground service that continuously records audio on the glasses in 15-minute chunks.
 *
 * Protocol: same as MainActivity "Media controls → Audio":
 * - start: glassesControl(0x02, 0x01, 0x08)
 * - stop:  glassesControl(0x02, 0x01, 0x0c)
 */
class AutoAudioCaptureService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loopJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action

        when (action) {
            ACTION_START -> startLoop()
            ACTION_STOP -> stopLoop(reason = "user")
            null -> {
                // Process restart: respect pref.
                if (AutoAudioCapturePrefs.isEnabled(this)) startLoop() else stopSelf()
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        stopLoop(reason = "destroy")
        scope.cancel()
        super.onDestroy()
    }

    private fun canPostNotifications(): Boolean {
        return hasNotificationPermission(this)
    }

    private fun startForegroundSafely(content: String): Boolean {
        if (!canPostNotifications()) {
            Log.w(TAG, "Missing POST_NOTIFICATIONS; waiting for the next foreground permission request")
            AutoAudioCapturePrefs.setLastPauseReason(this, "missing_post_notifications")
            return false
        }

        return runCatching {
            val notif = notification(content)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                    this,
                    NOTIF_ID,
                    notif,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } else {
                startForeground(NOTIF_ID, notif)
            }
        }.onFailure {
            Log.e(TAG, "startForeground failed: ${it.message}")
            AutoAudioCapturePrefs.setLastPauseReason(this, "start_foreground_failed")
            AutoAudioCapturePrefs.setEnabled(this, false)
        }.isSuccess
    }

    private fun startLoop() {
        if (RUNNING.getAndSet(true)) {
            Log.i(TAG, "Already running")
            return
        }

        if (!PluginVoicePermissions.hasRequiredPermissions(this) || !hasBluetooth(this)) {
            Log.w(TAG, "Cannot start auto-audio: required microphone, notification, or Bluetooth permission is missing")
            AutoAudioCapturePrefs.setLastPauseReason(this, "missing_runtime_permission")
            RUNNING.set(false)
            stopSelf()
            return
        }

        if (DeviceProfileStore.isEyevueSelected(this)) {
            Log.w(TAG, "Eyevue uses its own media protocol; legacy auto-audio capture is disabled")
            AutoAudioCapturePrefs.setLastPauseReason(this, "eyevue_uses_native_media_protocol")
            AutoAudioCapturePrefs.setEnabled(this, false)
            RUNNING.set(false)
            stopSelf()
            return
        }

        if (!startForegroundSafely("Auto audio capture: starting")) {
            RUNNING.set(false)
            stopSelf()
            return
        }

        loopJob = scope.launch {
            var pauseStopSent = false
            var consecutiveExtensions = 0
            val maxExtensions = 4 // Safety cap: at most 4 extensions (1h extra)

            while (isActive) {
                if (!AutoAudioCapturePrefs.isEnabled(this@AutoAudioCaptureService)) {
                    Log.i(TAG, "Pref disabled, stopping")
                    break
                }
                if (DeviceProfileStore.isEyevueSelected(this@AutoAudioCaptureService)) {
                    Log.w(TAG, "Eyevue selected; stopping legacy auto-audio capture")
                    AutoAudioCapturePrefs.setLastPauseReason(
                        this@AutoAudioCaptureService,
                        "eyevue_uses_native_media_protocol",
                    )
                    AutoAudioCapturePrefs.setEnabled(this@AutoAudioCaptureService, false)
                    break
                }

                // Hard pause conditions (meeting capture or video recording), plus short user holdoffs.
                val pauseReason = computePauseReason()
                if (pauseReason != null) {
                    AutoAudioCapturePrefs.setLastPauseReason(this@AutoAudioCaptureService, pauseReason)
                    updateNotification("Paused: $pauseReason")
                    // Best-effort stop any ongoing glasses recording (only once per paused period).
                    if (!pauseStopSent) {
                        pauseStopSent = true
                        sendAudioCommandAwait(start = false)
                    }
                    delay(10_000)
                    continue
                } else {
                    AutoAudioCapturePrefs.clearLastPauseReason(this@AutoAudioCaptureService)
                    pauseStopSent = false
                }

                if (!BleOperateManager.getInstance().isConnected) {
                    GlassesSessionCoordinator.clearForDisconnectedDevice()
                    Log.w(TAG, "Waiting for glasses connection…")
                    delay(10_000)
                    continue
                }

                val loops = AutoAudioCapturePrefs.getSuccessfulLoops(this@AutoAudioCaptureService)
                val extensionLabel = if (consecutiveExtensions > 0) " (extended ${consecutiveExtensions}x)" else ""
                Log.i(TAG, "Starting recording loop (completed=$loops)$extensionLabel")

                // Best-effort start. Some firmware builds record successfully but don't ACK reliably,
                // so we don't gate the loop on acknowledgements.
                val startAck = sendAudioCommandAwait(start = true)
                if (startAck.responded && !startAck.ok) {
                    // Log-only; do not treat as failure.
                    Log.w(TAG, "Start not acknowledged (err=${startAck.errorCode}, workTypeIng=${startAck.workTypeIng}); continuing")
                }

                // Wait until the last minute of the loop, then check for speech.
                val speechExtendEnabled = AutoAudioCapturePrefs.isSpeechExtendEnabled(this@AutoAudioCaptureService)
                val shouldCheckSpeech = speechExtendEnabled && consecutiveExtensions < maxExtensions && hasRecordAudioPermission()

                if (shouldCheckSpeech) {
                    // Record for (CHUNK_MS - 60s), then check ambient speech for the last 60s.
                    val preCheckMs = CHUNK_MS - SPEECH_CHECK_WINDOW_MS
                    if (preCheckMs > 0) {
                        val finished = delayWhileEnabledOrPaused(preCheckMs)
                        if (!finished) {
                            val reason = computePauseReason() ?: if (!AutoAudioCapturePrefs.isEnabled(this@AutoAudioCaptureService)) "disabled" else "paused"
                            AutoAudioCapturePrefs.setLastPauseReason(this@AutoAudioCaptureService, reason)
                            Log.w(TAG, "Stopping early: $reason")
                            sendAudioCommandAwait(start = false)
                            delay(2_000)
                            continue
                        }
                    }

                    // Speech check runs silently — no notification update to avoid churn.
                    val speechDetected = withContext(Dispatchers.IO) {
                        AmbientSpeechDetector.detectSpeechFor(
                            context = this@AutoAudioCaptureService,
                            durationMs = SPEECH_CHECK_WINDOW_MS,
                        )
                    }

                    if (speechDetected) {
                        Log.i(TAG, "Speech detected in last minute; extending loop by ${CHUNK_MS / 60_000} min")
                        consecutiveExtensions++
                        // Continue the loop without stopping the glasses recording.
                        val finished = delayWhileEnabledOrPaused(CHUNK_MS)
                        if (!finished) {
                            val reason = computePauseReason() ?: if (!AutoAudioCapturePrefs.isEnabled(this@AutoAudioCaptureService)) "disabled" else "paused"
                            AutoAudioCapturePrefs.setLastPauseReason(this@AutoAudioCaptureService, reason)
                            Log.w(TAG, "Stopping early: $reason (during extension)")
                            sendAudioCommandAwait(start = false)
                            delay(2_000)
                            continue
                        }
                        // After extension, proceed to stop and finalize.
                        // Do NOT reset consecutiveExtensions — the next loop will start fresh.
                    } else {
                        Log.i(TAG, "No significant speech in last minute; proceeding to stop")
                        consecutiveExtensions = 0
                    }
                } else {
                    // Standard path: wait the full chunk duration.
                    val finished = delayWhileEnabledOrPaused(CHUNK_MS)
                    if (!finished) {
                        val reason = computePauseReason() ?: if (!AutoAudioCapturePrefs.isEnabled(this@AutoAudioCaptureService)) "disabled" else "paused"
                        AutoAudioCapturePrefs.setLastPauseReason(this@AutoAudioCaptureService, reason)
                        Log.w(TAG, "Stopping early: $reason")
                        sendAudioCommandAwait(start = false)
                        delay(2_000)
                        continue
                    }
                    consecutiveExtensions = 0
                }

                // Stop audio recording (notification stays at last state until next start/stop/sync).
                val stopAck = sendAudioCommandAwait(start = false)
                if (stopAck.responded && !stopAck.ok) {
                    Log.w(TAG, "Stop not acknowledged (err=${stopAck.errorCode}, workTypeIng=${stopAck.workTypeIng}); continuing")
                }

                // Consider the loop successful if we reached the end of the recording window.
                val newLoops = AutoAudioCapturePrefs.incrementSuccessfulLoops(this@AutoAudioCaptureService)

                // Suppress visual notes when the loop was extended (BLE commands could interrupt ongoing speech).
                val wasExtended = consecutiveExtensions > 0
                if (!wasExtended && AutoAudioCapturePrefs.isVisualNotesEnabled(this@AutoAudioCaptureService)) {
                    AutoLoopVisualNoteGenerator.enqueue(
                        context = this@AutoAudioCaptureService,
                        loopIndex = newLoops,
                    )
                    // Give the BLE thumbnail transfer time to complete before starting P2P sync.
                    // P2P sync triggers Wi-Fi Direct which interferes with BLE data transfer.
                    delay(VISUAL_NOTE_SETTLE_MS)
                } else if (wasExtended) {
                    Log.i(TAG, "Suppressing visual note for extended loop #$newLoops")
                }

                val loopsPerSync = AutoAudioCapturePrefs.getLoopsPerSync(this@AutoAudioCaptureService)
                val shouldSync = newLoops > 0 && newLoops % loopsPerSync == 0

                if (shouldSync) {
                    updateNotification("Loop #$newLoops done (sync every $loopsPerSync). Starting P2P sync…")
                    triggerP2pSyncViaMainActivity()
                    // Give the glasses a moment to finalize the file before starting the next recording.
                    delayWhileEnabledOrPaused(10_000)
                }

                // Small gap between recordings.
                delayWhileEnabledOrPaused(2_000)
            }

            stopLoop(reason = "loop_end")
        }
    }

    private fun stopLoop(reason: String) {
        if (!RUNNING.getAndSet(false)) {
            stopSelf()
            return
        }

        Log.i(TAG, "Stopping: $reason")
        loopJob?.cancel()
        loopJob = null

        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    private data class AudioCmdAck(
        val responded: Boolean,
        val ok: Boolean,
        val dataType: Int? = null,
        val errorCode: Int? = null,
        val workTypeIng: Int? = null,
    )

    private suspend fun sendAudioCommandAwait(start: Boolean): AudioCmdAck {
        val permit = GlassesSessionCoordinator.tryAcquireBackgroundCommand()
        if (permit == null) {
            val activeSession = GlassesSessionCoordinator.currentSession()
            val owner = activeSession?.label ?: "another glasses command"
            Log.i(TAG, "Skipping audio command while $owner owns the SDK BLE/P2P slots")
            return AudioCmdAck(responded = false, ok = false)
        }
        val value = if (start) 0x08 else 0x0c
        val done = CompletableDeferred<AudioCmdAck>()
        var commandSubmitted = false
        try {
            LargeDataHandler.getInstance().glassesControl(
                byteArrayOf(0x02, 0x01, value.toByte())
            ) { _, rsp ->
                val ok = (rsp != null && rsp.dataType == 1 && rsp.errorCode == 0)
                val ack = AudioCmdAck(
                    responded = (rsp != null),
                    ok = ok,
                    dataType = rsp?.dataType,
                    errorCode = rsp?.errorCode,
                    workTypeIng = rsp?.workTypeIng,
                )

                Log.i(
                    TAG,
                    "Audio cmd start=$start responded=${ack.responded} dataType=${ack.dataType} err=${ack.errorCode} workTypeIng=${ack.workTypeIng} ok=${ack.ok}"
                )

                if (!done.isCompleted) done.complete(ack)
                GlassesSessionCoordinator.releaseBackgroundCommand(permit)
            }
            commandSubmitted = true

            // Some firmware builds start recording but never ACK (or ACK late). Treat timeout as
            // "unknown", not as hard failure. Keep the permit until a late ACK or reconnect so
            // the SDK's single response slot cannot be overwritten by another workflow.
            val result = withTimeoutOrNull(6_000) { done.await() }
            if (result == null) {
                Log.w(TAG, "Audio command timed out; keeping the glasses SDK isolated until ACK or reconnect")
                return AudioCmdAck(responded = false, ok = false)
            }
            return result
        } catch (e: Exception) {
            if (!commandSubmitted) {
                GlassesSessionCoordinator.releaseBackgroundCommand(permit)
            }
            throw e
        }
    }

    private suspend fun delayWhileEnabledOrPaused(totalMs: Long, stepMs: Long = 2_000): Boolean {
        var remaining = totalMs
        while (remaining > 0) {
            if (!AutoAudioCapturePrefs.isEnabled(this@AutoAudioCaptureService)) {
                return false
            }
            if (computePauseReason() != null) {
                return false
            }
            val step = minOf(stepMs, remaining)
            delay(step)
            remaining -= step
        }
        return AutoAudioCapturePrefs.isEnabled(this@AutoAudioCaptureService)
    }

    private fun hasRecordAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Returns a short string reason if auto audio should pause now; otherwise null.
     */
    private fun computePauseReason(): String? {
        GlassesSessionCoordinator.currentSession()?.let { session ->
            return "glasses_${session.name.lowercase()}_active"
        }

        val meetingActive = MeetingCapturePrefs.getState(this@AutoAudioCaptureService).isRecording
        if (meetingActive) return "meeting_capture_active"

        if (AutoAudioCapturePrefs.isPausedForMeeting(this@AutoAudioCaptureService)) return "paused_for_meeting"
        if (AutoAudioCapturePrefs.isPausedForVideo(this@AutoAudioCaptureService)) return "paused_for_video"

        val until = AutoAudioCapturePrefs.getPauseUntilMs(this@AutoAudioCaptureService)
        val now = System.currentTimeMillis()
        if (until > now) {
            val sec = ((until - now) / 1000L).coerceAtLeast(1)
            return "manual_holdoff_${sec}s"
        }

        return null
    }

    private fun triggerP2pSyncViaMainActivity() {
        GlassesSessionCoordinator.currentSession()?.let { session ->
            Log.i(TAG, "Skipping P2P sync while ${session.label} owns the SDK BLE/P2P slots")
            return
        }

        // Requirements: Bluetooth connected + permission already granted (Android 13+).
        if (!BleOperateManager.getInstance().isConnected) {
            Log.w(TAG, "Skipping P2P sync: glasses not connected")
            return
        }

        if (!hasWifiP2pPermission(this)) {
            // MainActivity owns the user-facing permission request. Continue so it can
            // bring the UI forward and request the platform-appropriate permission.
            Log.i(TAG, "Starting MainActivity to request the missing Wi-Fi Direct permission")
        }

        // Use the same pathway as the UI button: MainActivity handles the tasker command
        // and triggers btnDataDownload -> startDataDownload.
        val intent = Intent(this, MainActivity::class.java).apply {
            action = MainActivity.actionTaskerCommand(packageName)
            putExtra(MainActivity.EXTRA_TASKER_COMMAND, "data_download")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        runCatching {
            startActivity(intent)
        }.onFailure {
            Log.e(TAG, "Failed to start MainActivity for P2P sync: ${it.message}")
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val ch = NotificationChannel(
            CHANNEL_ID,
            "Auto audio capture",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Keeps the auto audio capture loop running"
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
        nm.createNotificationChannel(ch)
    }

    private fun updateNotification(content: String) {
        if (!canPostNotifications()) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        runCatching {
            nm.notify(NOTIF_ID, notification(content))
        }.onFailure {
            Log.w(TAG, "notify failed: ${it.message}")
        }
    }

    private fun notification(content: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val openPi = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Auto audio capture")
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setOngoing(true)
            .setContentIntent(openPi)
            .build()
    }

    companion object {
        private const val TAG = "AutoAudioCapture"
        private const val CHANNEL_ID = "auto_audio_capture"
        private const val NOTIF_ID = 55231

        // 15 minutes
        private const val CHUNK_MS = 15L * 60L * 1000L

        // Last 60 seconds of the loop used for ambient speech detection.
        private const val SPEECH_CHECK_WINDOW_MS = 60L * 1000L

        // Delay after visual note capture to let BLE thumbnail transfer finish before P2P sync starts.
        private const val VISUAL_NOTE_SETTLE_MS = 20_000L

        private val RUNNING = AtomicBoolean(false)

        const val ACTION_START = "com.fersaiyan.cyanbridge.action.AUTO_AUDIO_CAPTURE_START"
        const val ACTION_STOP = "com.fersaiyan.cyanbridge.action.AUTO_AUDIO_CAPTURE_STOP"

        fun start(context: Context) {
            if (!hasBluetooth(context)) {
                if (context is FragmentActivity) {
                    ensureBluetoothRuntimePermission(context, "Auto Audio") {
                        start(context)
                    }
                } else {
                    Log.w(TAG, "Cannot request Auto Audio Bluetooth permission without an Activity context")
                }
                return
            }
            if (!PluginVoicePermissions.hasRequiredPermissions(context)) {
                AutoAudioCapturePrefs.setLastPauseReason(context, "missing_voice_permissions")
                if (context is FragmentActivity) {
                    PluginVoicePermissions.ensure(context) {
                        start(context)
                    }
                } else {
                    Log.w(TAG, "Cannot request auto-audio permissions without an Activity context")
                }
                return
            }

            AutoAudioCapturePrefs.setEnabled(context, true)
            val intent = Intent(context, AutoAudioCaptureService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            AutoAudioCapturePrefs.setEnabled(context, false)
            val intent = Intent(context, AutoAudioCaptureService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }

        fun isRunning(): Boolean = RUNNING.get()
    }
}

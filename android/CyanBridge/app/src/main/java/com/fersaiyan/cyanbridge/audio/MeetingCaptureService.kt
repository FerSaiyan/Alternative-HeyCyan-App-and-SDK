package com.fersaiyan.cyanbridge.audio
import com.fersaiyan.cyanbridge.shared.settings.CaptureSource

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.fragment.app.FragmentActivity
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.fersaiyan.cyanbridge.MainActivity
import com.fersaiyan.cyanbridge.R
import com.fersaiyan.cyanbridge.data.local.entity.CaptureSession
import com.fersaiyan.cyanbridge.media.autocapture.AutoAudioCapturePrefs
import com.fersaiyan.cyanbridge.plugins.PluginVoicePermissions
import com.fersaiyan.cyanbridge.ui.MyApplication
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Foreground service that records meeting audio and persists capture metadata.
 *
 * High-level flow:
 * 1. Start from explicit user action (`ACTION_START`).
 * 2. Best-effort route input to Bluetooth mic, fallback to phone mic.
 * 3. Record to app-private storage while showing persistent notification.
 * 4. Persist a [CaptureSession] row when recording stops.
 *
 * Privacy/runtime behavior:
 * - Recording is always foreground with visible notification.
 * - Auto-capture loop is paused while manual meeting capture runs.
 */
class MeetingCaptureService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var recorder: MediaRecorder? = null
    private var startedAtMs: Long? = null
    private var outputPath: String? = null
    private var captureSource: CaptureSource? = null
    private var deviceClass: String = "UNKNOWN"
    private var timerDurationSec: Long? = null
    private var autoStopJobActive: Boolean = false

    private var btRoute: BtInputRoute? = null

    private val notificationManager by lazy {
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val durationSec = intent.getLongExtra(EXTRA_TIMER_DURATION_SEC, -1L).takeIf { it > 0 }
                val dc = intent.getStringExtra(EXTRA_DEVICE_CLASS) ?: "UNKNOWN"
                startCapture(timerSec = durationSec, deviceClass = dc)
            }
            ACTION_STOP -> {
                stopCapture(stopReason = "user")
            }
            else -> {
                // no-op
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopCapture(stopReason = "service_destroy")
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startCapture(timerSec: Long?, deviceClass: String) {
        if (recorder != null) {
            Log.w(TAG, "startCapture called while already recording")
            return
        }
        if (!PluginVoicePermissions.hasRequiredPermissions(this)) {
            Log.w(TAG, "Cannot start meeting capture: microphone or notification permission is missing")
            broadcastState(isRecording = false, error = "Microphone and notification permissions are required")
            stopSelf()
            return
        }

        this.deviceClass = deviceClass
        this.timerDurationSec = timerSec
        this.startedAtMs = System.currentTimeMillis()

        // Pause the automatic glasses audio loop while meeting capture is running.
        AutoAudioCapturePrefs.setPausedForMeeting(applicationContext, true)

        serviceScope.launch {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

            // Prefer glasses (Bluetooth) mic when available.
            val route = startBluetoothInputRoutingBestEffort(audioManager)
            btRoute = route

            val source = if (route.isActive) CaptureSource.BLUETOOTH_MIC else CaptureSource.PHONE_MIC
            captureSource = source

            val outputFile = createOutputFile()
            outputPath = outputFile.absolutePath
            Log.i(TAG, "Preparing audio capture source=$source path=${outputFile.absolutePath}")

            val startOk = runCatching {
                val r = MediaRecorder()

                val audioSource = when (source) {
                    // VOICE_COMMUNICATION tends to honor BT comm routes better than MIC on many devices.
                    CaptureSource.BLUETOOTH_MIC -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
                    CaptureSource.PHONE_MIC -> MediaRecorder.AudioSource.MIC
                }

                r.setAudioSource(audioSource)

                // If we can identify the BT input device, explicitly prefer it.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    route.preferredInput?.let { r.setPreferredDevice(it) }
                }

                // Keep a widely supported output profile for MVP export/transcription pipeline.
                r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                r.setAudioEncodingBitRate(128_000)
                r.setAudioSamplingRate(44_100)
                r.setOutputFile(outputFile.absolutePath)
                r.prepare()
                r.start()

                recorder = r
                Log.i(TAG, "MediaRecorder started source=$source path=${outputFile.absolutePath}")
                true
            }.getOrElse { e ->
                Log.e(TAG, "Failed to start MediaRecorder", e)
                false
            }

            // If we failed to start recording, release any BT routing changes.
            if (!startOk) {
                stopBluetoothInputRoutingBestEffort(audioManager, btRoute)
            }

            if (!startOk) {
                btRoute = null
                // If meeting capture never started, undo the pause.
                AutoAudioCapturePrefs.setPausedForMeeting(applicationContext, false)
                broadcastState(isRecording = false, error = "Failed to start recording")
                stopSelf()
                return@launch
            }

            MeetingCapturePrefs.setRecording(
                context = applicationContext,
                isRecording = true,
                startAtMs = startedAtMs,
                source = source,
                audioPath = outputPath,
                deviceClass = deviceClass
            )

            startForeground(NOTIFICATION_ID, buildNotification(isRecording = true, source = source))
            broadcastState(isRecording = true, source = source)

            if (timerSec != null && !autoStopJobActive) {
                autoStopJobActive = true
                serviceScope.launch {
                    delay(timerSec * 1000L)
                    stopCapture(stopReason = "timer")
                }
            }
        }
    }

    private fun stopCapture(stopReason: String?) {
        val r = recorder ?: run {
            stopSelf()
            return
        }
        recorder = null

        serviceScope.launch {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

            val error: String? = runCatching {
                r.stop()
                null
            }.getOrElse { e ->
                Log.w(TAG, "MediaRecorder.stop() failed", e)
                e.message ?: "stop_failed"
            }

            runCatching { r.reset() }
            runCatching { r.release() }

            stopBluetoothInputRoutingBestEffort(audioManager, btRoute)
            btRoute = null

            val endedAtMs = System.currentTimeMillis()
            val started = startedAtMs ?: endedAtMs
            val durationSec = ((endedAtMs - started).coerceAtLeast(0L)) / 1000L
            val path = outputPath

            MeetingCapturePrefs.setRecording(
                context = applicationContext,
                isRecording = false
            )

            // Resume the automatic glasses audio loop (if enabled) once meeting capture ends.
            AutoAudioCapturePrefs.setPausedForMeeting(applicationContext, false)

            if (path != null) {
                val outputFile = File(path)
                Log.i(
                    TAG,
                    "Finalizing audio capture path=$path exists=${outputFile.exists()} bytes=${outputFile.length()} " +
                        "durationSec=$durationSec stopReason=$stopReason error=${error ?: "none"}",
                )
                runCatching {
                    val sessionId = MyApplication.repository.insertCaptureSession(
                        CaptureSession(
                            startedAt = started,
                            endedAt = endedAtMs,
                            durationSec = durationSec,
                            deviceClass = deviceClass,
                            captureSource = (captureSource ?: CaptureSource.PHONE_MIC).name,
                            audioPath = path,
                            timerDurationSec = timerDurationSec,
                            stopReason = stopReason,
                            error = error,
                        )
                    )
                    Log.i(TAG, "Persisted capture session id=$sessionId path=$path")
                }.onFailure { dbErr ->
                    Log.e(TAG, "Failed to persist capture session", dbErr)
                }
            }

            broadcastState(isRecording = false, source = captureSource, stopReason = stopReason, error = error)
            notificationManager.notify(NOTIFICATION_ID, buildNotification(isRecording = false, source = captureSource))
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun createOutputFile(): File {
        val dir = File(getExternalFilesDir(null), "recordings")
        if (!dir.exists()) dir.mkdirs()
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return File(dir, "meeting_${ts}.m4a")
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "Meeting capture",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows recording indicator while meeting capture is active"
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun buildNotification(isRecording: Boolean, source: CaptureSource?): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val openPi = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, MeetingCaptureService::class.java).apply { action = ACTION_STOP }
        val stopPi = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (isRecording) "Recording meeting" else "Meeting recording stopped"
        val src = when (source) {
            CaptureSource.BLUETOOTH_MIC -> "Bluetooth mic"
            CaptureSource.PHONE_MIC -> "Phone mic"
            null -> ""
        }
        val text = if (isRecording) {
            if (src.isNotBlank()) "Source: $src" else "Recording…"
        } else {
            "Tap to open"
        }

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openPi)
            .setOngoing(isRecording)
            .addAction(
                NotificationCompat.Action.Builder(
                    0,
                    "Stop",
                    stopPi
                ).build()
            )
            .build()
    }

    private fun broadcastState(
        isRecording: Boolean,
        source: CaptureSource? = null,
        stopReason: String? = null,
        error: String? = null,
    ) {
        val i = Intent(ACTION_STATE).apply {
            putExtra(EXTRA_IS_RECORDING, isRecording)
            putExtra(EXTRA_SOURCE, source?.name)
            putExtra(EXTRA_STOP_REASON, stopReason)
            putExtra(EXTRA_ERROR, error)
        }
        LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(i)
    }

    private data class BtInputRoute(
        val isActive: Boolean,
        val usedSco: Boolean,
        val usedCommunicationDevice: Boolean,
        val preferredInput: AudioDeviceInfo?,
    )

    private fun isBluetoothHeadsetLikelyConnected(audioManager: AudioManager): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
                if (inputs.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }) return true
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (inputs.any { it.type == AudioDeviceInfo.TYPE_BLE_HEADSET }) return true
                }

                val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                if (outputs.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }) return true
                if (outputs.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }) return true
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (outputs.any { it.type == AudioDeviceInfo.TYPE_BLE_HEADSET }) return true
                }
            }

            // Fallback hint: if SCO is available off-call we can attempt routing.
            audioManager.isBluetoothScoAvailableOffCall
        } catch (_: Throwable) {
            false
        }
    }

    private fun findBluetoothInputDevice(audioManager: AudioManager): AudioDeviceInfo? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
        val inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        return inputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
            ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                inputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLE_HEADSET }
            } else {
                null
            }
    }

    private fun findBluetoothCommunicationDevice(audioManager: AudioManager): AudioDeviceInfo? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        return runCatching {
            audioManager.availableCommunicationDevices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
            }
        }.getOrNull()
    }

    private suspend fun startBluetoothInputRoutingBestEffort(audioManager: AudioManager): BtInputRoute {
        if (!isBluetoothHeadsetLikelyConnected(audioManager)) {
            Log.d(TAG, "Bluetooth mic not detected; using phone mic")
            return BtInputRoute(false, usedSco = false, usedCommunicationDevice = false, preferredInput = null)
        }

        // Android 12+ has explicit communication-device routing; try that first.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val comm = findBluetoothCommunicationDevice(audioManager)
            if (comm != null) {
                val ok = runCatching {
                    audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                    audioManager.setCommunicationDevice(comm)
                    true
                }.getOrDefault(false)

                delay(250)
                val preferred = findBluetoothInputDevice(audioManager)
                if (ok) {
                    Log.i(TAG, "Using communication device routing for Bluetooth mic (type=${comm.type})")
                    return BtInputRoute(
                        isActive = true,
                        usedSco = false,
                        usedCommunicationDevice = true,
                        preferredInput = preferred,
                    )
                }
            }
        }

        // Legacy fallback: SCO routing.
        val scoConnected = startBluetoothScoBestEffort(audioManager)
        val preferred = findBluetoothInputDevice(audioManager)
        return BtInputRoute(
            isActive = scoConnected,
            usedSco = scoConnected,
            usedCommunicationDevice = false,
            preferredInput = preferred,
        )
    }

    private fun stopBluetoothInputRoutingBestEffort(audioManager: AudioManager, route: BtInputRoute? = null) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && (route?.usedCommunicationDevice == true || route == null)) {
            runCatching { audioManager.clearCommunicationDevice() }
        }

        // Always best-effort stop SCO + reset mode.
        stopBluetoothScoBestEffort(audioManager)
    }

    private suspend fun startBluetoothScoBestEffort(audioManager: AudioManager): Boolean {
        return runCatching {
            val deferred = CompletableDeferred<Boolean>()
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action != AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED) return
                    val state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)
                    when (state) {
                        AudioManager.SCO_AUDIO_STATE_CONNECTED -> deferred.complete(true)
                        AudioManager.SCO_AUDIO_STATE_DISCONNECTED,
                        AudioManager.SCO_AUDIO_STATE_ERROR -> deferred.complete(false)
                    }
                }
            }

            registerReceiver(receiver, IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED))
            try {
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                runCatching { audioManager.startBluetoothSco() }
                runCatching { audioManager.isBluetoothScoOn = true }

                // Some devices are slow to bring up SCO.
                withTimeoutOrNull(6_000) { deferred.await() } ?: false
            } finally {
                runCatching { unregisterReceiver(receiver) }
            }
        }.getOrElse { e ->
            Log.w(TAG, "Failed to start Bluetooth SCO", e)
            false
        }
    }

    private fun stopBluetoothScoBestEffort(audioManager: AudioManager) {
        runCatching { audioManager.isBluetoothScoOn = false }
        runCatching { audioManager.stopBluetoothSco() }
        runCatching { audioManager.mode = AudioManager.MODE_NORMAL }
    }

    companion object {
        private const val TAG = "MeetingCaptureService"

        const val ACTION_START = "com.fersaiyan.cyanbridge.action.MEETING_CAPTURE_START"
        const val ACTION_STOP = "com.fersaiyan.cyanbridge.action.MEETING_CAPTURE_STOP"

        const val ACTION_STATE = "com.fersaiyan.cyanbridge.action.MEETING_CAPTURE_STATE"
        const val EXTRA_IS_RECORDING = "extra_is_recording"
        const val EXTRA_SOURCE = "extra_source"
        const val EXTRA_STOP_REASON = "extra_stop_reason"
        const val EXTRA_ERROR = "extra_error"

        const val EXTRA_TIMER_DURATION_SEC = "extra_timer_duration_sec"
        const val EXTRA_DEVICE_CLASS = "extra_device_class"

        private const val NOTIFICATION_CHANNEL_ID = "meeting_capture"
        private const val NOTIFICATION_ID = 936

        fun start(context: Context, timerDurationSec: Long?, deviceClass: String) {
            if (!PluginVoicePermissions.hasRequiredPermissions(context)) {
                if (context is FragmentActivity) {
                    PluginVoicePermissions.ensure(context) {
                        start(context, timerDurationSec, deviceClass)
                    }
                } else {
                    Log.w(TAG, "Cannot request meeting capture permissions without an Activity context")
                }
                return
            }
            val intent = Intent(context, MeetingCaptureService::class.java).apply {
                action = ACTION_START
                if (timerDurationSec != null && timerDurationSec > 0) {
                    putExtra(EXTRA_TIMER_DURATION_SEC, timerDurationSec)
                }
                putExtra(EXTRA_DEVICE_CLASS, deviceClass)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, MeetingCaptureService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}

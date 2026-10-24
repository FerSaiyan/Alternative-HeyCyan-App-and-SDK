package com.fersaiyan.cyanbridge.plugins

import android.Manifest
import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.widget.Toast
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.fersaiyan.cyanbridge.audio.AudioSessionCoordinator
import com.fersaiyan.cyanbridge.audio.AudioSessionGrant
import com.fersaiyan.cyanbridge.audio.AudioSessionHandle
import com.fersaiyan.cyanbridge.audio.AudioSessionListener
import com.fersaiyan.cyanbridge.shared.audio.AudioSessionRequest
import com.hjq.permissions.OnPermissionCallback
import com.hjq.permissions.Permission
import com.hjq.permissions.XXPermissions

/** Shared microphone permission and foreground-service handling for voice plugins. */
object PluginVoicePermissions {
    fun hasRequiredPermissions(context: Context): Boolean {
        val hasAudio = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        val hasNotifications = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        return hasAudio && hasNotifications
    }

    fun request(activity: FragmentActivity, onResult: (Boolean) -> Unit) {
        val permissions = buildList {
            add(Permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Permission.POST_NOTIFICATIONS)
            }
        }
        XXPermissions.with(activity)
            .permission(permissions)
            .request(object : OnPermissionCallback {
                override fun onGranted(permissions: MutableList<String>, all: Boolean) {
                    onResult(all)
                }

                override fun onDenied(permissions: MutableList<String>, never: Boolean) {
                    super.onDenied(permissions, never)
                    onResult(false)
                    if (never) {
                        XXPermissions.startPermissionActivity(activity, permissions)
                    }
                }
            })
    }

    fun ensure(
        activity: FragmentActivity,
        onDenied: () -> Unit = {},
        onGranted: () -> Unit,
    ) {
        if (hasRequiredPermissions(activity)) {
            onGranted()
            return
        }
        request(activity) { granted ->
            if (granted) {
                onGranted()
            } else {
                Toast.makeText(
                    activity,
                    "Microphone and notification permissions are required for this plugin",
                    Toast.LENGTH_LONG,
                ).show()
                onDenied()
            }
        }
    }
}

internal fun startPluginVoiceForeground(
    service: Service,
    notificationId: Int,
    notification: Notification,
): Boolean {
    if (!PluginVoicePermissions.hasRequiredPermissions(service)) return false
    return runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                service,
                notificationId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            service.startForeground(notificationId, notification)
        }
    }.onFailure { error ->
        Log.e("PluginVoiceSupport", "Unable to start voice foreground service", error)
    }.isSuccess
}

internal fun startPluginVoiceService(context: Context, intent: Intent) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        ContextCompat.startForegroundService(context, intent)
    } else {
        context.startService(intent)
    }
}

/**
 * Continuous Android speech recognition with best-effort routing to a Bluetooth glasses mic.
 *
 * The vendor's raw audio protocol is not confirmed for real-time use. Android's supported SCO/
 * communication-device route is therefore the safe path for live plugins. The microphone is a
 * [AudioSessionRequest.ContinuousRecognition] session: only one plugin listens at a time, and
 * listening pauses while a spoken prompt or Gemini Live owns the microphone.
 */
internal class PluginVoiceRecognizer(
    context: Context,
    private val languageTag: String?,
    private val onPartialText: (String) -> Unit,
    private val onFinalText: (String) -> Unit,
    private val onError: (String) -> Unit,
) {
    private val appContext = context.applicationContext
    private val owner = context.javaClass.simpleName
    private val handler = Handler(Looper.getMainLooper())
    private var speechRecognizer: SpeechRecognizer? = null
    @Volatile private var running = false
    @Volatile private var session: AudioSessionHandle? = null

    private val restartRunnable = Runnable { startListening() }

    private val sessionListener = object : AudioSessionListener {
        override fun onSuspended() {
            handler.post {
                Log.i(TAG, "$owner pausing recognition while another session uses the microphone")
                handler.removeCallbacks(restartRunnable)
                runCatching { speechRecognizer?.cancel() }
            }
        }

        override fun onResumed() {
            handler.post { scheduleRestart(ROUTE_SETTLE_MS) }
        }
    }

    fun start(): Boolean {
        if (running) return true
        val handle = when (
            val grant = AudioSessionCoordinator.open(
                appContext,
                AudioSessionRequest.ContinuousRecognition(owner),
                sessionListener,
            )
        ) {
            is AudioSessionGrant.Granted -> grant.handle
            is AudioSessionGrant.Denied -> {
                onError("Another voice plugin is already listening")
                return false
            }
        }
        session = handle
        running = true
        handler.post {
            if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
                failAndStop("Speech recognition is unavailable on this phone")
                return@post
            }
            val route = handle.connectRouteNow()
            if (route.bluetoothActive) {
                Log.i(TAG, "Using Bluetooth microphone route for $owner")
            } else {
                Log.i(TAG, "Bluetooth mic route unavailable; Android will use its current input")
            }
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(appContext).also {
                it.setRecognitionListener(listener)
            }
            if (handle.isActive) handler.postDelayed(restartRunnable, ROUTE_SETTLE_MS)
        }
        return true
    }

    fun stop() {
        running = false
        val handle = session
        session = null
        handler.post {
            handler.removeCallbacks(restartRunnable)
            runCatching { speechRecognizer?.cancel() }
            runCatching { speechRecognizer?.destroy() }
            speechRecognizer = null
        }
        // Released at once so the next plugin can take the microphone immediately.
        handle?.close()
    }

    private fun startListening() {
        if (!running || session?.isActive != true) return
        val recognizer = speechRecognizer ?: run {
            failAndStop("Speech recognizer was not initialized")
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1_200L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1_200L)
            languageTag?.takeIf { it.isNotBlank() }?.let {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, it)
            }
        }
        runCatching { recognizer.startListening(intent) }
            .onFailure {
                onError("Could not start speech recognition: ${it.message ?: "unknown error"}")
                scheduleRestart(RETRY_DELAY_MS)
            }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: android.os.Bundle?) = Unit

        override fun onBeginningOfSpeech() = Unit

        override fun onRmsChanged(rmsdB: Float) = Unit

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() = Unit

        override fun onError(error: Int) {
            if (!running) return
            if (error !in TRANSIENT_ERRORS) {
                onError("Speech recognition error: $error")
            }
            scheduleRestart(if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) RETRY_DELAY_MS else RESTART_DELAY_MS)
        }

        override fun onResults(results: android.os.Bundle?) {
            results.bestRecognition()?.let(onFinalText)
            scheduleRestart(RESTART_DELAY_MS)
        }

        override fun onPartialResults(partialResults: android.os.Bundle?) {
            partialResults.bestRecognition()?.let(onPartialText)
        }

        override fun onEvent(eventType: Int, params: android.os.Bundle?) = Unit
    }

    private fun scheduleRestart(delayMs: Long) {
        if (!running || session?.isActive != true) return
        handler.removeCallbacks(restartRunnable)
        handler.postDelayed(restartRunnable, delayMs)
    }

    private fun failAndStop(message: String) {
        onError(message)
        stop()
    }

    private fun android.os.Bundle?.bestRecognition(): String? {
        return this
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private companion object {
        private const val TAG = "PluginVoiceRecognizer"
        private const val ROUTE_SETTLE_MS = 350L
        private const val RESTART_DELAY_MS = 250L
        private const val RETRY_DELAY_MS = 1_000L

        private val TRANSIENT_ERRORS = setOf(
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
            SpeechRecognizer.ERROR_CLIENT,
        )
    }
}

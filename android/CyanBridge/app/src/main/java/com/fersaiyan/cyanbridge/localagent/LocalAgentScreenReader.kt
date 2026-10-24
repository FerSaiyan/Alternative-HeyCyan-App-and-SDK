package com.fersaiyan.cyanbridge.localagent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The demo and read-screen-aloud commands of [LocalAgentService]. */
class LocalAgentScreenReader(
    private val session: LocalAgentSession,
    private val device: LocalAgentDevice,
    private val memory: LocalAgentTaskMemory,
    private val status: LocalAgentStatusReporter,
    private val speech: LocalAgentSpeechOutput,
    private val host: LocalAgentServiceHost,
    private val scope: CoroutineScope,
) {

    fun runDemo() {
        // Demo behavior:
        // - wait 5 seconds to let the user change screens
        // - snapshot screen text
        // - store it in Local Agent memory
        // - read it back via TTS (Bluetooth headset/glasses will receive audio if routed)

        device.availability()
            .takeIf { it != LocalAgentDeviceState.Availability.READY }
            ?.let { availability ->
                status.setStatus("Demo: unavailable")
                status.setLastError(availability.errorCode)
                status.emitStatus()
                host.stopSelf()
                return
            }

        if (!device.isAccessibilityConnected()) {
            val err = "accessibility_not_connected"
            status.setStatus("Demo: failed")
            status.setLastError(err)
            status.emitStatus()
            host.stopSelf()
            return
        }

        status.setStatus("Demo: reading in 5s…")
        status.clearLastError()
        status.emitStatus()

        scope.launch {
            delay(5_000)

            device.availability()
                .takeIf { it != LocalAgentDeviceState.Availability.READY }
                ?.let { availability ->
                    status.setStatus("Demo: stopped")
                    status.setLastError(availability.errorCode)
                    status.emitStatus()
                    host.stopSelf()
                    return@launch
                }

            val text = device.snapshotScreenText() ?: ""
            if (text.isBlank()) {
                status.setStatus("Demo: no text found")
                status.setLastError("empty_screen_text")
                status.emitStatus()
                speech.speakAndWaitBestEffort("I couldn't read any text on the screen.")
                host.stopSelf()
                return@launch
            }

            // Store snapshot (package name isn't directly available here; mark as unknown for demo).
            memory.appendScreenCapture(
                packageName = "(demo)",
                text = text,
            )

            val toSpeak = text
                .lineSequence()
                .filter { it.isNotBlank() }
                .take(14)
                .joinToString(". ")
                .take(550)

            speech.speakAndWaitBestEffort("Reading your screen. $toSpeak", maxLength = 600)

            status.setStatus("Demo: spoke (${toSpeak.length} chars)")
            status.clearLastError()
            status.emitStatus()
            host.stopSelf()
        }
    }

    /**
     * Reads only currently visible accessibility text. This is deliberately an explicit,
     * approval-gated action rather than a background notification or message reader.
     */
    fun readCurrentScreenAloud(): Boolean {
        val standaloneRequest = !session.running.get()
        device.availability()
            .takeIf { it != LocalAgentDeviceState.Availability.READY }
            ?.let { availability ->
                status.setStatus("Screen reading unavailable: ${availability.statusText}")
                status.setLastError(availability.errorCode)
                status.emitStatus()
                if (!session.running.get()) host.stopSelf()
                return false
            }
        if (!device.hasNotificationPermission()) {
            status.setLastError("missing_post_notifications")
            status.emitStatus()
            if (standaloneRequest) host.stopSelf()
            return false
        }
        if (!device.isAccessibilityConnected()) {
            status.setLastError("accessibility_not_connected")
            status.emitStatus()
            if (standaloneRequest) host.stopSelf()
            return false
        }
        val activePackage = device.activeWindowPackageName()
        if (activePackage.isNullOrBlank()) {
            status.setStatus("Unable to verify current app")
            status.setLastError("screen_package_unknown")
            status.emitStatus()
            if (standaloneRequest) host.stopSelf()
            return false
        }
        device.privacyBlockedReason(activePackage)?.let {
            status.setStatus("Screen reading blocked by privacy settings")
            status.setLastError("privacy_blacklisted_app")
            status.emitStatus()
            if (standaloneRequest) host.stopSelf()
            return false
        }

        if (standaloneRequest) {
            status.setStatus("Reading current screen")
            status.clearLastError()
            host.startForeground("Reading current screen")
            status.emitStatus()
        }

        val visibleText = device.snapshotScreenText()
            ?.lineSequence()
            ?.map(String::trim)
            ?.filter(String::isNotBlank)
            ?.take(20)
            ?.joinToString(". ")
            ?.take(MAX_SCREEN_READ_ALOUD_CHARS)
            .orEmpty()
        if (visibleText.isBlank()) {
            status.setLastError("empty_screen_text")
            status.emitStatus()
            session.screenReadInProgress.set(true)
            scope.launch {
                try {
                    speech.speakBestEffort("I couldn't read any text on the screen.")
                } finally {
                    session.screenReadInProgress.set(false)
                    if (standaloneRequest) {
                        host.stopForegroundAndSelf()
                    }
                }
            }
            return false
        }

        session.screenReadInProgress.set(true)
        scope.launch {
            try {
                device.availability()
                    .takeIf { it != LocalAgentDeviceState.Availability.READY }
                    ?.let { availability ->
                        status.setStatus("Screen reading stopped: ${availability.statusText}")
                        status.setLastError(availability.errorCode)
                        status.emitStatus()
                        return@launch
                    }
                speech.speakAndWaitBestEffort("Reading the visible screen. $visibleText")
            } finally {
                session.screenReadInProgress.set(false)
                if (standaloneRequest) {
                    status.setStatus("Read current screen")
                    status.clearLastError()
                    status.emitStatus()
                    host.stopForegroundAndSelf()
                }
            }
        }
        return true
    }

    internal companion object {
        const val MAX_SCREEN_READ_ALOUD_CHARS = 750
    }
}

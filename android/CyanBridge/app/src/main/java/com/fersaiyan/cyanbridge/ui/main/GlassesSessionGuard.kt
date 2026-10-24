package com.fersaiyan.cyanbridge.ui.main

import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.fersaiyan.cyanbridge.glasses.BackgroundGlassesCommandPermit
import com.fersaiyan.cyanbridge.glasses.GlassesSession
import com.fersaiyan.cyanbridge.glasses.GlassesSessionCoordinator
import com.fersaiyan.cyanbridge.glasses.GlassesSessionLease
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Main-screen access to [GlassesSessionCoordinator]: exclusive sessions and one-shot commands
 * that share the vendor SDK's BLE/P2P slots, with the user-facing messages for conflicts.
 */
internal class GlassesSessionGuard(
    private val activity: AppCompatActivity,
    private val devices: DeviceManagerRegistry,
    /** Must outlive the activity so timeout warnings still fire during teardown. */
    private val glassesTeardownScope: CoroutineScope,
) {
    fun acquireExclusiveGlassesSession(session: GlassesSession): GlassesSessionLease? {
        val lease = GlassesSessionCoordinator.tryAcquireLease(session)
        if (lease != null) {
            Log.i("GlassesSession", "Acquired ${session.label} session")
            return lease
        }

        val activeSession = GlassesSessionCoordinator.currentSession()
        if (activeSession == null) {
            Log.w("GlassesSession", "Could not acquire ${session.label} session; a one-shot glasses command is still active")
            Toast.makeText(
                activity,
                "Waiting for the current glasses command to finish.",
                Toast.LENGTH_SHORT,
            ).show()
            return null
        }

        Log.w(
            "GlassesSession",
            "Cannot start ${session.label}; ${activeSession.label} still owns BLE/P2P",
        )
        Toast.makeText(
            activity,
            "Stop ${activeSession.label} before starting ${session.label}.",
            Toast.LENGTH_LONG,
        ).show()
        return null
    }

    fun isGlassesCommandBlocked(source: String): Boolean {
        val activeSession = GlassesSessionCoordinator.currentSession() ?: return false
        if (activeSession == GlassesSession.META_CAMERA &&
            devices.selectedProfile().voiceQueriesBypassCameraSession &&
            source == "voice-query command"
        ) {
            // Meta voice queries use Android's audio route and do not contend for the
            // HeyCyan SDK response slot owned by the DAT camera session.
            return false
        }
        Log.w(
            "GlassesSession",
            "Skipping $source; ${activeSession.label} owns the SDK BLE/P2P slots",
        )
        return true
    }

    fun acquireBackgroundGlassesCommand(source: String): BackgroundGlassesCommandPermit? {
        val permit = GlassesSessionCoordinator.tryAcquireBackgroundCommand()
        if (permit == null) {
            val owner = GlassesSessionCoordinator.currentSession()?.label ?: "another glasses command"
            Log.w("GlassesSession", "Skipping $source; $owner owns the SDK BLE/P2P slots")
        }
        return permit
    }

    fun warnIfBackgroundGlassesCommandTimesOut(
        permit: BackgroundGlassesCommandPermit,
        timeoutMs: Long = ONE_SHOT_BLE_COMMAND_TIMEOUT_MS,
    ) {
        glassesTeardownScope.launch {
            delay(timeoutMs)
            if (GlassesSessionCoordinator.isBackgroundCommandActive(permit)) {
                Log.w(
                    "GlassesSession",
                    "Glasses command timed out; keeping the SDK response slot isolated until its response or Bluetooth reconnect",
                )
            }
        }
    }

    companion object {
        const val ONE_SHOT_BLE_COMMAND_TIMEOUT_MS = 6_000L
    }
}

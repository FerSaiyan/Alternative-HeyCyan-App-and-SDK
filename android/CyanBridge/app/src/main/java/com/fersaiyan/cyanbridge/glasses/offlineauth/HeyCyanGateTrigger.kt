package com.fersaiyan.cyanbridge.glasses.offlineauth

import android.app.Activity
import android.util.Log
import com.fersaiyan.cyanbridge.ui.dialog.ExpressiveDialogs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

/**
 * App-wide glue that drives [HeyCyanGateController] from the real connection/notify events and shows
 * the Material 3 prompts. Pure orchestration lives in the controller (unit tested); this object only
 * wires Android: the activity for dialogs, the raw-notify NAK listener, and the coroutine scope.
 */
object HeyCyanGateTrigger {

    private const val TAG = "HeyCyanGate"

    private val controller = HeyCyanGateController()
    private var activityRef: WeakReference<Activity> = WeakReference(null)
    @Volatile private var promptHandle: ExpressiveDialogs.Handle? = null
    @Volatile private var resultHandle: ExpressiveDialogs.Handle? = null

    /** The current gated-command retry (set by the media sync coordinator while a sync is running). */
    @Volatile private var syncRetry: (suspend () -> Unit)? = null

    var enabled: Boolean
        get() = controller.enabled
        set(value) { controller.enabled = value }

    /** Media sync registers the re-send of its gated command so a `03` NAK can retry it once. */
    fun registerGatedRetry(retry: suspend () -> Unit) {
        syncRetry = retry
        // Starting a sync is a user action: after a missed press it may prompt again.
        controller.allowRetryAfterMissedPress()
    }
    fun clearGatedRetry() { syncRetry = null }

    /**
     * Called once per HeyCyan (re)connect (from the connection coordinator). Registers the NAK listener
     * and runs the unlock if the unit requires it.
     */
    fun onHeyCyanConnected(activity: Activity, scope: CoroutineScope) {
        activityRef = WeakReference(activity)
        controller.onConnectionReset()
        HeyCyanAuthRawChannel.setNakListener { cmd -> onGatedNak(cmd, scope) }
        val connectedAt = android.os.SystemClock.uptimeMillis()
        scope.launch {
            awaitNotifications(connectedAt)
            val result = controller.onHeyCyanConnected(
                prompt = { showPrompt(activity) },
                onResult = { dismissPromptAndReport(activity, it) },
            )
            Log.i(TAG, "connect-time gate unlock result=$result")
        }
    }

    /**
     * The connect event arrives before the SDK has enabled notifications (on a test rig the first raw write went
     * out 3 ms before onServiceDiscovered and its reply was lost, ending in TRANSPORT_ERROR). Wait for the first
     * vendor notify of this connection (the SDK's own connect-time reads produce one), at most [NOTIFY_WAIT_MS].
     */
    private suspend fun awaitNotifications(connectedAt: Long) {
        val deadline = connectedAt + NOTIFY_WAIT_MS
        while (HeyCyanAuthRawChannel.lastNotifyUptimeMs <= connectedAt &&
            android.os.SystemClock.uptimeMillis() < deadline
        ) {
            kotlinx.coroutines.delay(200)
        }
    }

    private const val NOTIFY_WAIT_MS = 10_000L

    fun onHeyCyanDisconnected() {
        controller.onConnectionReset()
        HeyCyanAuthRawChannel.setNakListener(null)
        syncRetry = null
        dismissPrompt()
        activityRef = WeakReference(null)
    }

    private fun onGatedNak(cmdId: Int, scope: CoroutineScope) {
        val activity = activityRef.get()
        val retry = syncRetry
        scope.launch {
            controller.onGatedNak(
                cmdId = cmdId,
                prompt = { activity?.let { showPrompt(it) } },
                onResult = { activity?.let { a -> dismissPromptAndReport(a, it) } },
                retry = retry,
            )
        }
    }

    private fun showPrompt(activity: Activity) {
        activity.runOnUiThread {
            dismissPrompt()
            dismissResult() // an earlier result popup must not cover the new prompt
            promptHandle = HeyCyanGatePrompt.showFrontButtonPrompt(
                activity = activity,
                windowMs = HeyCyanAuthProtocol.BIND_BUTTON_WINDOW_MS,
                onCancel = { promptHandle = null },
            )
        }
    }

    private fun dismissPromptAndReport(activity: Activity, result: OfflineUnlockResult) {
        activity.runOnUiThread {
            dismissPrompt()
            dismissResult()
            resultHandle = HeyCyanGatePrompt.showResult(activity, result)
        }
    }

    private fun dismissPrompt() {
        promptHandle?.dismiss()
        promptHandle = null
    }

    private fun dismissResult() {
        resultHandle?.dismiss()
        resultHandle = null
    }
}

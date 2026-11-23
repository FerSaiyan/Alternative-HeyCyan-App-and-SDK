package com.fersaiyan.cyanbridge.media.sync

import android.app.Activity
import com.fersaiyan.cyanbridge.devices.DeviceProfileStore
import com.fersaiyan.cyanbridge.shared.devices.DeviceClass
import com.fersaiyan.cyanbridge.shared.diagnostics.DiagnosticEvents
import com.fersaiyan.cyanbridge.shared.diagnostics.DiagnosticSubsystem
import com.fersaiyan.cyanbridge.shared.glasses.GlassesConditionState
import com.fersaiyan.cyanbridge.shared.glasses.GlassesWifiStartHint
import com.fersaiyan.cyanbridge.ui.dialog.ExpressiveDialogs

/**
 * Before a HeyCyan media sync: if the glasses last reported a battery too low to start Wi-Fi (at or below 15 % and not
 * charging, [GlassesWifiStartHint]), say so instead of letting the sync run into "the glasses' Wi-Fi did not start".
 * The user can still sync anyway (the reading may be a few minutes old).
 */
internal object GlassesLowBatteryWarning {
    /** Battery readings older than this are not trusted (the same age the sync's failure hint uses). */
    private const val MAX_AGE_MS = 10 * 60_000L

    fun confirmOrContinue(activity: Activity, start: () -> Unit) {
        if (DeviceProfileStore.selectedClass(activity) != DeviceClass.HEY_CYAN) return start()
        val condition = GlassesConditionState.snapshot().freshAt(System.currentTimeMillis(), MAX_AGE_MS)
        val message = GlassesWifiStartHint.lowBatteryBeforeSync(condition) ?: return start()
        DiagnosticEvents.warn(
            DiagnosticSubsystem.SYNC,
            "low glasses battery before sync",
            mapOf("battery_pct" to condition.batteryPercent.toString(), "charging" to condition.charging.toString()),
        )
        val shown = ExpressiveDialogs.alert(
            activity = activity,
            title = "Glasses battery low",
            message = message,
            confirm = ExpressiveDialogs.Action("Sync anyway") { start() },
            dismiss = ExpressiveDialogs.Action("Cancel"),
        )
        if (shown == null) start()
    }
}

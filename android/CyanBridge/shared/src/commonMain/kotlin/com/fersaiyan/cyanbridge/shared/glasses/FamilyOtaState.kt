package com.fersaiyan.cyanbridge.shared.glasses

import com.fersaiyan.cyanbridge.shared.customization.CustomizationFamily
import com.fersaiyan.cyanbridge.shared.devices.DeviceClass
import com.fersaiyan.cyanbridge.shared.devices.firmware.FamilyFirmwareRules
import com.fersaiyan.cyanbridge.shared.devices.firmware.FamilyOtaBlocker

/*
 * The dashboard firmware section for the families whose update CyanBridge runs itself over
 * Bluetooth (EyeVue: JieLi RCSP; TuneBuds: AB Mate FOTA). HeyCyan keeps its own section
 * ([OtaSectionUiState]); the platform host drives this one through [FamilyOtaMachine].
 */

enum class FamilyOtaStage {
    IDLE,
    CHECKING,
    UP_TO_DATE,
    UPDATE_AVAILABLE,
    PREPARING,
    DOWNLOADING,
    VALIDATING,
    INSTALLING,
    RECONNECTING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
}

/** A newer official firmware the vendor server offers. */
data class FamilyOtaOffer(
    val version: String,
    /** The vendor's package also updates the camera (Wi-Fi) chip, which this update leaves alone. */
    val includesWifiPart: Boolean = false,
)

/** What the dashboard's family firmware buttons ask for (one action: [GlassesDashboardAction.FamilyFirmware]). */
enum class FamilyFirmwareCommand {
    CHECK_OFFICIAL,
    INSTALL_OFFICIAL,

    /** Opens the file picker; the screen sends it only after the risk acknowledgement. */
    INSTALL_FILE,

    /** Clears a finished result. */
    DISMISS,
}

data class FamilyOtaUiState(
    val family: CustomizationFamily,
    val stage: FamilyOtaStage = FamilyOtaStage.IDLE,
    val currentVersion: String? = null,
    val modelCode: String? = null,
    val batteryPercent: Int? = null,
    val offer: FamilyOtaOffer? = null,
    /** 0..100 while sending; null for indeterminate work. */
    val progress: Int? = null,
    /** The platform's status or result line (English, like the HeyCyan section). */
    val detail: String = "",
    val blockers: Set<FamilyOtaBlocker> = emptySet(),
    /** Whether a running install may still be cancelled (a single-bank EyeVue transfer may not). */
    val installCancelable: Boolean = true,
) {
    val officialUpdates: Boolean get() = FamilyFirmwareRules.hasOfficialUpdates(family)
    val minBatteryPercent: Int get() = FamilyFirmwareRules.minBatteryPercent(family)
    val isRunning: Boolean get() = stage in FamilyOtaMachine.RUNNING
    val canStart: Boolean get() = !isRunning && blockers.isEmpty()
    val canCancel: Boolean
        get() = when (stage) {
            FamilyOtaStage.CHECKING, FamilyOtaStage.PREPARING, FamilyOtaStage.DOWNLOADING, FamilyOtaStage.VALIDATING -> true
            FamilyOtaStage.INSTALLING -> installCancelable
            else -> false
        }

    companion object {
        /** The firmware family of a dashboard device class, or null when it has no family section. */
        fun familyOf(deviceClass: DeviceClass): CustomizationFamily? = when (deviceClass) {
            DeviceClass.EYEVUE -> CustomizationFamily.EYEVUE
            DeviceClass.TUNEBUDS -> CustomizationFamily.TUNEBUDS
            else -> null
        }
    }
}

sealed interface FamilyOtaEvent {
    /** Fresh glasses facts and blockers; never changes the stage. */
    data class Refreshed(
        val currentVersion: String? = null,
        val modelCode: String? = null,
        val batteryPercent: Int? = null,
        val blockers: Set<FamilyOtaBlocker>,
    ) : FamilyOtaEvent

    data object CheckStarted : FamilyOtaEvent
    data class CheckFinished(val offer: FamilyOtaOffer?, val detail: String = "") : FamilyOtaEvent
    data class Preparing(val detail: String) : FamilyOtaEvent
    data class Downloading(val progress: Int?) : FamilyOtaEvent
    data object Validating : FamilyOtaEvent
    data class Installing(val progress: Int?, val cancelable: Boolean, val detail: String = "") : FamilyOtaEvent
    data class Reconnecting(val detail: String) : FamilyOtaEvent
    data class Succeeded(val newVersion: String?, val detail: String) : FamilyOtaEvent
    data class Failed(val detail: String) : FamilyOtaEvent
    data class Cancelled(val detail: String) : FamilyOtaEvent
    data object Dismissed : FamilyOtaEvent
}

/**
 * The family firmware update's state machine. Start events only apply while nothing runs,
 * progress and end events only while something runs, so a late callback from a cancelled or
 * finished update cannot rewrite the section.
 */
object FamilyOtaMachine {
    val RUNNING = setOf(
        FamilyOtaStage.CHECKING,
        FamilyOtaStage.PREPARING,
        FamilyOtaStage.DOWNLOADING,
        FamilyOtaStage.VALIDATING,
        FamilyOtaStage.INSTALLING,
        FamilyOtaStage.RECONNECTING,
    )

    fun reduce(state: FamilyOtaUiState, event: FamilyOtaEvent): FamilyOtaUiState = when (event) {
        is FamilyOtaEvent.Refreshed -> state.copy(
            currentVersion = event.currentVersion ?: state.currentVersion,
            modelCode = event.modelCode ?: state.modelCode,
            batteryPercent = event.batteryPercent ?: state.batteryPercent,
            blockers = event.blockers,
        )
        FamilyOtaEvent.CheckStarted -> if (state.isRunning) state else {
            state.copy(stage = FamilyOtaStage.CHECKING, offer = null, progress = null, detail = "")
        }
        is FamilyOtaEvent.CheckFinished -> if (state.stage != FamilyOtaStage.CHECKING) state else {
            state.copy(
                stage = if (event.offer != null) FamilyOtaStage.UPDATE_AVAILABLE else FamilyOtaStage.UP_TO_DATE,
                offer = event.offer,
                detail = event.detail,
            )
        }
        is FamilyOtaEvent.Preparing -> if (state.isRunning) state else {
            state.copy(stage = FamilyOtaStage.PREPARING, progress = null, detail = event.detail, installCancelable = true)
        }
        is FamilyOtaEvent.Downloading -> running(state) { it.copy(stage = FamilyOtaStage.DOWNLOADING, progress = event.progress) }
        FamilyOtaEvent.Validating -> running(state) { it.copy(stage = FamilyOtaStage.VALIDATING, progress = null) }
        is FamilyOtaEvent.Installing -> running(state) {
            it.copy(
                stage = FamilyOtaStage.INSTALLING,
                progress = event.progress,
                installCancelable = event.cancelable,
                detail = event.detail.ifEmpty { it.detail },
            )
        }
        is FamilyOtaEvent.Reconnecting -> running(state) {
            it.copy(stage = FamilyOtaStage.RECONNECTING, progress = null, detail = event.detail)
        }
        is FamilyOtaEvent.Succeeded -> running(state) {
            it.copy(
                stage = FamilyOtaStage.SUCCEEDED,
                progress = null,
                offer = null,
                currentVersion = event.newVersion ?: it.currentVersion,
                detail = event.detail,
            )
        }
        is FamilyOtaEvent.Failed -> running(state) { it.copy(stage = FamilyOtaStage.FAILED, progress = null, detail = event.detail) }
        is FamilyOtaEvent.Cancelled -> running(state) {
            it.copy(stage = FamilyOtaStage.CANCELLED, progress = null, detail = event.detail)
        }
        FamilyOtaEvent.Dismissed -> if (state.isRunning) state else {
            state.copy(stage = FamilyOtaStage.IDLE, progress = null, detail = "", offer = null)
        }
    }

    private inline fun running(state: FamilyOtaUiState, change: (FamilyOtaUiState) -> FamilyOtaUiState) =
        if (state.isRunning) change(state) else state
}

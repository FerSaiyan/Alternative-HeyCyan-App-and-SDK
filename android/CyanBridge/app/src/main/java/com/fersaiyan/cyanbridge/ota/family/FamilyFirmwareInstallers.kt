package com.fersaiyan.cyanbridge.ota.family

import com.fersaiyan.cyanbridge.customization.AndroidCustomizationPlatform
import com.fersaiyan.cyanbridge.devices.eyevue.EyevueManager
import com.fersaiyan.cyanbridge.devices.eyevue.RcspInstallProgress
import com.fersaiyan.cyanbridge.devices.tunebuds.TuneBudsManager
import com.fersaiyan.cyanbridge.shared.customization.CustomizationFamily
import com.fersaiyan.cyanbridge.shared.devices.eyevue.JieliRcsp
import com.fersaiyan.cyanbridge.shared.devices.firmware.FamilyFirmwareRules
import com.fersaiyan.cyanbridge.shared.devices.firmware.FamilyGlassesFirmware
import com.fersaiyan.cyanbridge.shared.devices.tunebuds.TuneBudsFota
import com.fersaiyan.cyanbridge.shared.glasses.FamilyOtaEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.security.MessageDigest

/** How an install ended. */
internal sealed interface InstallOutcome {
    data class Installed(val newVersion: String?, val detail: String) : InstallOutcome
    data class Failed(val detail: String) : InstallOutcome
    data object Cancelled : InstallOutcome
}

/**
 * One glasses family's firmware path in the dashboard's firmware section: what the glasses
 * report and how an image is sent. HeyCyan keeps its own path ([com.fersaiyan.cyanbridge.ota.OtaCoordinator]).
 */
internal interface FamilyFirmwareInstaller {
    val family: CustomizationFamily
    fun isConnected(): Boolean

    /** The last battery level the manager knows, without asking the glasses. */
    fun knownBatteryPercent(): Int?

    /** What the manager already knows (no Bluetooth traffic); fields are null until read. */
    fun knownGlasses(): FamilyGlassesFirmware

    /** Reads model, firmware version and battery from the glasses; null when they do not answer. */
    suspend fun readGlasses(): FamilyGlassesFirmware?

    /**
     * Sends [image]. [cancelRequested] is polled; the installer stops only while stopping keeps
     * the installed firmware intact. Progress goes to [onEvent].
     */
    suspend fun install(
        image: ByteArray,
        targetVersion: String?,
        glasses: FamilyGlassesFirmware,
        cancelRequested: () -> Boolean,
        onEvent: (FamilyOtaEvent) -> Unit,
    ): InstallOutcome
}

/** EyeVue: JieLi RCSP over GATT AE00 ([EyevueManager.installRcspFirmware]). */
internal class EyevueFirmwareInstaller(private val manager: () -> EyevueManager) : FamilyFirmwareInstaller {
    override val family = CustomizationFamily.EYEVUE

    override fun isConnected(): Boolean = manager().isConnected()

    override fun knownBatteryPercent(): Int? = manager().state.value.batteryPercent

    override fun knownGlasses(): FamilyGlassesFirmware {
        val eyevue = manager()
        val state = eyevue.state.value
        return FamilyGlassesFirmware(
            family,
            modelCode(state.project, state.customer),
            eyevue.rcspInfo?.version?.let(AndroidCustomizationPlatform::shortVersion),
            state.batteryPercent,
        )
    }

    private fun modelCode(project: String?, customer: String?): String? =
        listOfNotNull(project, customer).joinToString("").filter { it.isLetterOrDigit() }.uppercase().ifEmpty { null }

    override suspend fun readGlasses(): FamilyGlassesFirmware? {
        val eyevue = manager()
        if (!eyevue.isConnected()) return null
        val info = eyevue.readRcspTargetInfo() ?: return null
        val version = info.version?.let(AndroidCustomizationPlatform::shortVersion)
        if (eyevue.state.value.project.isNullOrBlank()) runCatching { eyevue.awaitProject(timeoutMs = 8_000L) }
        val state = eyevue.state.value
        val model = modelCode(state.project, state.customer)
        val battery = runCatching { eyevue.awaitBatteryPercent(timeoutMs = 5_000L) }.getOrNull() ?: eyevue.state.value.batteryPercent
        return FamilyGlassesFirmware(family, model, version, battery)
    }

    override suspend fun install(
        image: ByteArray,
        targetVersion: String?,
        glasses: FamilyGlassesFirmware,
        cancelRequested: () -> Boolean,
        onEvent: (FamilyOtaEvent) -> Unit,
    ): InstallOutcome {
        onEvent(FamilyOtaEvent.Installing(null, cancelable = true, detail = "Starting the update on the glasses…"))
        val result = manager().installRcspFirmware(image, cancelRequested) { progress ->
            when (progress) {
                is RcspInstallProgress.Sending -> onEvent(
                    FamilyOtaEvent.Installing(progress.percent, progress.cancelable, "Sending the firmware to the glasses…"),
                )
                RcspInstallProgress.Restarting -> onEvent(
                    FamilyOtaEvent.Reconnecting("The glasses are restarting as part of the update; reconnecting…"),
                )
            }
        }
        return when {
            result.startsWith("ok") -> {
                val after = result.substringAfter("-> ", "").trim().takeIf { it.isNotEmpty() && it != "?" }
                    ?.let(AndroidCustomizationPlatform::shortVersion)
                InstallOutcome.Installed(after, "Installed (${result.removePrefix("ok: ")}). The glasses restarted with the new firmware.")
            }
            result == "error: cancelled" -> InstallOutcome.Cancelled
            else -> InstallOutcome.Failed(describeRcspError(result.removePrefix("error: ")))
        }
    }

    private fun describeRcspError(error: String): String {
        val e2 = Regex("E2 refused: (\\d+)").find(error)?.groupValues?.get(1)?.toIntOrNull()
        return when (e2) {
            1 -> "The glasses refused the update: their battery is too low. Charge them and try again."
            2 -> "The glasses refused the file as damaged or not for them."
            3 -> "The glasses already run this firmware."
            null -> "The update failed: $error"
            else -> "The glasses refused the update: ${JieliRcsp.E2_CODES[e2] ?: "code $e2"}."
        }
    }
}

/** TuneBuds: AB Mate FOTA over SPP ([TuneBudsManager.installFirmware]); the glasses verify the whole image first. */
internal class TuneBudsFirmwareInstaller(private val manager: () -> TuneBudsManager) : FamilyFirmwareInstaller {
    override val family = CustomizationFamily.TUNEBUDS

    override fun isConnected(): Boolean = manager().isConnected()

    override fun knownBatteryPercent(): Int? = manager().state.value.batteryPercent

    override fun knownGlasses(): FamilyGlassesFirmware = manager().state.value.let { state ->
        FamilyGlassesFirmware(family, state.model, state.firmwareVersion, state.batteryPercent)
    }

    override suspend fun readGlasses(): FamilyGlassesFirmware? {
        val tuneBuds = manager()
        if (!tuneBuds.isConnected()) return null
        val state = tuneBuds.refreshVersions()
        val battery = runCatching { tuneBuds.refreshBattery() }.getOrNull()
        return FamilyGlassesFirmware(family, state.model, state.firmwareVersion, battery ?: state.batteryPercent)
    }

    override suspend fun install(
        image: ByteArray,
        targetVersion: String?,
        glasses: FamilyGlassesFirmware,
        cancelRequested: () -> Boolean,
        onEvent: (FamilyOtaEvent) -> Unit,
    ): InstallOutcome = coroutineScope {
        val md5 = MessageDigest.getInstance("MD5").digest(image).copyOf(4)
        val version = TuneBudsFota.versionCode(targetVersion ?: glasses.firmwareVersion ?: "0")
        onEvent(FamilyOtaEvent.Installing(null, cancelable = true, detail = "Sending the firmware to the glasses…"))
        var lastPercent = -1
        val push = async {
            manager().installFirmware(image, md5, version) { sent, total ->
                val percent = (sent * 100 / total.coerceAtLeast(1)).toInt()
                if (percent != lastPercent) {
                    lastPercent = percent
                    onEvent(FamilyOtaEvent.Installing(percent, cancelable = true, detail = "Sending the firmware to the glasses…"))
                }
            }
        }
        // The glasses keep the received blocks and resume from them, so stopping is safe.
        val watcher = launch {
            while (!cancelRequested()) delay(CANCEL_POLL_MS)
            push.cancel()
        }
        try {
            when (val outcome = push.await()) {
                TuneBudsFota.Outcome.Finished -> {
                    onEvent(FamilyOtaEvent.Reconnecting("The glasses verified the firmware and restart with it…"))
                    InstallOutcome.Installed(targetVersion, awaitRestart(targetVersion))
                }
                is TuneBudsFota.Outcome.Failed -> InstallOutcome.Failed("The update failed: ${outcome.message}")
            }
        } catch (error: CancellationException) {
            if (!cancelRequested()) throw error
            InstallOutcome.Cancelled
        } finally {
            watcher.cancel()
        }
    }

    /** Waits briefly for the restarted glasses to report their version. */
    private suspend fun awaitRestart(targetVersion: String?): String {
        val reported = withTimeoutOrNull(RESTART_WAIT_MS) {
            delay(RESTART_SETTLE_MS)
            while (!manager().isConnected()) delay(1_000L)
            manager().refreshVersions().firmwareVersion
        }
        return if (reported != null && targetVersion != null && FamilyFirmwareRules.compareVersions(reported, targetVersion) == 0) {
            "Installed. The glasses restarted with firmware $reported."
        } else {
            "Installed${targetVersion?.let { " firmware $it" }.orEmpty()}. The glasses apply it and restart; reconnect them if they do not come back."
        }
    }

    private companion object {
        const val CANCEL_POLL_MS = 300L
        const val RESTART_SETTLE_MS = 4_000L
        const val RESTART_WAIT_MS = 45_000L
    }
}

package com.fersaiyan.cyanbridge.ota.family

import android.net.Uri
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.fersaiyan.cyanbridge.audio.AudioSessionCoordinator
import com.fersaiyan.cyanbridge.customization.AndroidCustomizationPlatform
import com.fersaiyan.cyanbridge.customization.CustomFirmwareBundleReader
import com.fersaiyan.cyanbridge.devices.eyevue.EyevueManager
import com.fersaiyan.cyanbridge.devices.tunebuds.TuneBudsManager
import com.fersaiyan.cyanbridge.glasses.GlassesSession
import com.fersaiyan.cyanbridge.glasses.GlassesSessionCoordinator
import com.fersaiyan.cyanbridge.glasses.GlassesSessionLease
import com.fersaiyan.cyanbridge.shared.audio.AudioSessionKind
import com.fersaiyan.cyanbridge.shared.audio.AudioSessionState
import com.fersaiyan.cyanbridge.shared.customization.CustomizationFamily
import com.fersaiyan.cyanbridge.shared.customization.GlassesFirmwareInfo
import com.fersaiyan.cyanbridge.shared.devices.firmware.EyevueOtaOffer
import com.fersaiyan.cyanbridge.shared.devices.firmware.FamilyFirmwareRules
import com.fersaiyan.cyanbridge.shared.devices.firmware.FamilyGlassesFirmware
import com.fersaiyan.cyanbridge.shared.devices.firmware.FamilyOtaBlocker
import com.fersaiyan.cyanbridge.shared.devices.firmware.FirmwareCandidate
import com.fersaiyan.cyanbridge.shared.devices.firmware.FirmwareCheck
import com.fersaiyan.cyanbridge.shared.devices.firmware.FirmwareOrigin
import com.fersaiyan.cyanbridge.shared.glasses.FamilyFirmwareCommand
import com.fersaiyan.cyanbridge.shared.glasses.FamilyOtaEvent
import com.fersaiyan.cyanbridge.shared.glasses.FamilyOtaMachine
import com.fersaiyan.cyanbridge.shared.glasses.FamilyOtaOffer
import com.fersaiyan.cyanbridge.shared.glasses.FamilyOtaStage
import com.fersaiyan.cyanbridge.shared.glasses.GlassesDashboardUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/**
 * The dashboard firmware section for EyeVue and TuneBuds ([com.fersaiyan.cyanbridge.shared.glasses.FamilyOtaUiState]):
 * "Update to the latest official firmware" (EyeVue's vendor server) and "Install a firmware file"
 * (a bare image or the Pro customization `.zip`). Every update holds the [GlassesSession.OTA]
 * lease, so media sync, live preview and other updates cannot start meanwhile, and refuses to
 * start while a voice question, Gemini Live or a recording uses the glasses audio.
 * The HeyCyan section stays with [com.fersaiyan.cyanbridge.ota.OtaCoordinator].
 */
internal class FamilyOtaCoordinator(
    private val activity: AppCompatActivity,
    private val host: Host,
    installers: List<FamilyFirmwareInstaller> = listOf(
        EyevueFirmwareInstaller { EyevueManager.getInstance(activity) },
        TuneBudsFirmwareInstaller { TuneBudsManager.getInstance(activity) },
    ),
) {
    interface Host {
        var dashboardState: GlassesDashboardUiState

        /** Outlives the activity, so an install is never cut off by a configuration change. */
        val glassesTeardownScope: CoroutineScope
        fun acquireExclusiveGlassesSession(session: GlassesSession): GlassesSessionLease?
        fun releaseExclusiveGlassesSession(lease: GlassesSessionLease?)
    }

    private val installers = installers.associateBy { it.family }
    private var job: Job? = null

    @Volatile
    private var cancelRequested = false
    private var lease: GlassesSessionLease? = null

    /** Held while the version is read quietly for display (no update running). */
    @Volatile
    private var readLease: GlassesSessionLease? = null
    private var lastOffer: EyevueOtaOffer? = null
    private var pendingPickFamily: CustomizationFamily? = null
    private var autoReadAttempts = 0
    private var nextAutoReadAtMs = 0L
    private var picker: ActivityResultLauncher<Array<String>>? = null

    val isActive: Boolean get() = job?.isActive == true

    /**
     * Registers the file picker (call from the activity's onCreate) and keeps the section's facts
     * and blockers current while the activity is visible.
     */
    fun start() {
        if (picker != null) return
        // Keyed registration: the glasses host may be created after the activity started, when
        // lifecycle-bound registerForActivityResult is no longer allowed.
        picker = activity.activityResultRegistry.register(PICKER_KEY, ActivityResultContracts.OpenDocument(), ::onFilePicked)
        activity.lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_DESTROY) picker?.unregister()
            },
        )
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    refresh()
                    delay(REFRESH_MS)
                }
            }
        }
    }

    fun onCommand(command: FamilyFirmwareCommand) {
        val ota = host.dashboardState.familyOta ?: return
        val installer = installers[ota.family] ?: return
        when (command) {
            FamilyFirmwareCommand.CHECK_OFFICIAL -> checkOfficial(installer)
            FamilyFirmwareCommand.INSTALL_OFFICIAL -> installOfficial(installer)
            FamilyFirmwareCommand.INSTALL_FILE -> pickFile(installer)
            FamilyFirmwareCommand.DISMISS -> dispatch(ota.family, FamilyOtaEvent.Dismissed)
        }
    }

    /**
     * The dashboard Cancel. Returns false when no family update runs (the HeyCyan section's
     * cancel then handles it). Before the install starts the work just stops; during the install
     * the installer stops only where that keeps the installed firmware.
     */
    fun cancel(): Boolean {
        if (!isActive) return false
        when (host.dashboardState.familyOta?.stage) {
            FamilyOtaStage.INSTALLING, FamilyOtaStage.RECONNECTING -> {
                cancelRequested = true
                if (host.dashboardState.familyOta?.installCancelable == false) {
                    toast("This update cannot be stopped safely now. Keep the glasses on until it finishes.")
                }
            }
            else -> job?.cancel()
        }
        return true
    }

    /** The file picker result. */
    private fun onFilePicked(uri: Uri?) {
        val family = pendingPickFamily ?: return
        pendingPickFamily = null
        if (uri == null) return
        val installer = installers[family] ?: return
        if (!begin(installer, forInstall = true)) return
        dispatch(family, FamilyOtaEvent.Preparing("Reading the firmware file…"))
        run(installer) {
            val document = AndroidCustomizationPlatform(activity).readDocument(uri.toString())
            val bundle = try {
                CustomFirmwareBundleReader.read(document.name, document.bytes)
            } catch (error: IllegalArgumentException) {
                throw UserFacing(error.message ?: "This is not a firmware file CyanBridge can install.")
            }
            val isBundle = document.name.endsWith(".zip", ignoreCase = true) ||
                (document.bytes.size > 2 && document.bytes[0] == 'P'.code.toByte() && document.bytes[1] == 'K'.code.toByte())
            dispatch(family, FamilyOtaEvent.Validating)
            val glasses = readGlassesForInstall(installer)
            CustomFirmwareBundleReader.mismatch(bundle, glasses.toFirmwareInfo())?.let { throw UserFacing(it) }
            val candidate = FirmwareCandidate(
                family = bundle.family,
                fileName = bundle.ble.name,
                image = bundle.ble.bytes,
                sha256 = sha256Hex(bundle.ble.bytes),
                origin = if (isBundle) FirmwareOrigin.BUNDLE else FirmwareOrigin.FILE,
                baseFirmwareVersion = bundle.baseBleFirmwareVersion,
                targetVersion = bundle.firmwareVersion,
            )
            installChecked(installer, candidate, glasses)
        }
    }

    private fun checkOfficial(installer: FamilyFirmwareInstaller) {
        if (!FamilyFirmwareRules.hasOfficialUpdates(installer.family)) return
        if (!begin(installer, forInstall = false)) return
        dispatch(installer.family, FamilyOtaEvent.CheckStarted)
        run(installer) {
            val glasses = installer.readGlasses()
                ?: throw UserFacing("The glasses did not report their firmware. Reconnect them and try again.")
            publish(installer, glasses)
            val model = glasses.modelCode ?: throw UserFacing("The glasses did not report their model. Reconnect them and try again.")
            val version = glasses.firmwareVersion ?: throw UserFacing("The glasses did not report their firmware version.")
            val offer = EyevueOfficialClient.check(model, version)
            lastOffer = offer
            dispatch(
                installer.family,
                FamilyOtaEvent.CheckFinished(
                    offer?.let { FamilyOtaOffer(it.version, it.includesWifiPart) },
                    detail = offer?.packageName?.let { "EyeVue package: $it" } ?: "Checked for $model, firmware $version.",
                ),
            )
        }
    }

    private fun installOfficial(installer: FamilyFirmwareInstaller) {
        val offer = lastOffer ?: return
        if (!begin(installer, forInstall = true)) return
        dispatch(installer.family, FamilyOtaEvent.Preparing("Reading the glasses…"))
        run(installer) {
            val glasses = readGlassesForInstall(installer)
            if (!offer.modelCode.equals(glasses.modelCode, ignoreCase = true)) {
                throw UserFacing("Other glasses are connected now. Check for updates again.")
            }
            dispatch(installer.family, FamilyOtaEvent.Downloading(0))
            val image = EyevueOfficialClient.download(offer) { percent ->
                dispatch(installer.family, FamilyOtaEvent.Downloading(percent))
            }
            dispatch(installer.family, FamilyOtaEvent.Validating)
            val candidate = FirmwareCandidate(
                family = installer.family,
                fileName = offer.bleUrl.substringBefore('?').substringAfterLast('/'),
                image = image,
                sha256 = sha256Hex(image),
                origin = FirmwareOrigin.OFFICIAL,
                targetVersion = offer.version,
            )
            installChecked(installer, candidate, glasses)
        }
    }

    private fun pickFile(installer: FamilyFirmwareInstaller) {
        if (isActive) return
        val blockers = blockersNow(installer)
        if (blockers.isNotEmpty()) {
            showBlocked(installer, blockers)
            return
        }
        pendingPickFamily = installer.family
        try {
            requireNotNull(picker) { "not started" }.launch(arrayOf("*/*"))
        } catch (error: Exception) {
            pendingPickFamily = null
            toast("Could not open the file picker: ${error.message}")
        }
    }

    private suspend fun readGlassesForInstall(installer: FamilyFirmwareInstaller): FamilyGlassesFirmware {
        val glasses = installer.readGlasses()
            ?: throw UserFacing("The glasses did not report their firmware. Reconnect them and try again.")
        publish(installer, glasses)
        val minimum = FamilyFirmwareRules.minBatteryPercent(installer.family)
        glasses.batteryPercent?.let { battery ->
            if (battery < minimum) throw UserFacing("Charge the glasses to at least $minimum% first (now $battery%).")
        }
        return glasses
    }

    private suspend fun installChecked(installer: FamilyFirmwareInstaller, candidate: FirmwareCandidate, glasses: FamilyGlassesFirmware) {
        when (val check = FamilyFirmwareRules.check(candidate, glasses)) {
            is FirmwareCheck.Refused -> throw UserFacing(check.reason)
            is FirmwareCheck.Accepted -> {
                Log.i(
                    TAG,
                    "Installing ${candidate.origin} ${candidate.fileName} (${candidate.image.size} B, sha256 ${candidate.sha256}) " +
                        "on ${glasses.modelCode} ${glasses.firmwareVersion} -> ${check.targetVersion ?: "?"}",
                )
                val outcome = installer.install(candidate.image, check.targetVersion, glasses, { cancelRequested }) { event ->
                    dispatch(installer.family, event)
                }
                when (outcome) {
                    is InstallOutcome.Installed -> dispatch(installer.family, FamilyOtaEvent.Succeeded(outcome.newVersion, outcome.detail))
                    is InstallOutcome.Failed -> dispatch(installer.family, FamilyOtaEvent.Failed(outcome.detail))
                    InstallOutcome.Cancelled -> dispatch(
                        installer.family,
                        FamilyOtaEvent.Cancelled("Stopped. The glasses keep the firmware they had."),
                    )
                }
            }
        }
    }

    /** Claims the glasses for an update; false (with the reason shown) when it cannot start. */
    private fun begin(installer: FamilyFirmwareInstaller, forInstall: Boolean): Boolean {
        if (isActive || readLease != null) return false
        var blockers = blockersNow(installer)
        if (!forInstall) blockers = blockers - FamilyOtaBlocker.BATTERY_LOW - FamilyOtaBlocker.AUDIO_BUSY
        if (blockers.isNotEmpty()) {
            showBlocked(installer, blockers)
            return false
        }
        lease = host.acquireExclusiveGlassesSession(GlassesSession.OTA) ?: return false
        cancelRequested = false
        return true
    }

    private fun run(installer: FamilyFirmwareInstaller, block: suspend () -> Unit) {
        val family = installer.family
        setKeepScreenOn(true)
        job = host.glassesTeardownScope.launch(Dispatchers.IO) {
            try {
                block()
            } catch (error: CancellationException) {
                dispatch(family, FamilyOtaEvent.Cancelled("Stopped before anything was sent to the glasses."))
                throw error
            } catch (error: UserFacing) {
                dispatch(family, FamilyOtaEvent.Failed(error.message.orEmpty()))
            } catch (error: Exception) {
                Log.w(TAG, "$family firmware update failed", error)
                dispatch(family, FamilyOtaEvent.Failed(error.message ?: "The update failed."))
            } finally {
                withContext(Dispatchers.Main + kotlinx.coroutines.NonCancellable) {
                    host.releaseExclusiveGlassesSession(lease)
                    lease = null
                    cancelRequested = false
                    setKeepScreenOn(false)
                    refresh()
                }
            }
        }
    }

    private fun refresh() {
        val ota = host.dashboardState.familyOta ?: return
        val installer = installers[ota.family] ?: return
        val known = installer.knownGlasses()
        val blockers = if (installer.isConnected() || !ota.isRunning) blockersNow(installer) else ota.blockers
        dispatch(
            ota.family,
            FamilyOtaEvent.Refreshed(known.firmwareVersion, known.modelCode, known.batteryPercent, blockers),
        )
        val now = android.os.SystemClock.elapsedRealtime()
        if (!installer.isConnected()) {
            autoReadAttempts = 0
            nextAutoReadAtMs = now + AUTO_READ_SETTLE_MS
        } else if (autoReadAttempts < AUTO_READ_ATTEMPTS && now >= nextAutoReadAtMs &&
            known.firmwareVersion == null && !isActive && readLease == null && blockers.isEmpty()
        ) {
            // EyeVue reports its version only over RCSP; read it quietly after connecting (a few tries).
            autoReadAttempts++
            nextAutoReadAtMs = now + AUTO_READ_SETTLE_MS
            val quietLease = GlassesSessionCoordinator.tryAcquireLease(GlassesSession.OTA) ?: return
            readLease = quietLease
            host.glassesTeardownScope.launch(Dispatchers.IO) {
                try {
                    installer.readGlasses()?.let { publish(installer, it) }
                } catch (error: Exception) {
                    Log.w(TAG, "Could not read the ${installer.family} firmware version", error)
                } finally {
                    withContext(Dispatchers.Main + kotlinx.coroutines.NonCancellable) {
                        host.releaseExclusiveGlassesSession(quietLease)
                        readLease = null
                    }
                }
            }
        }
    }

    private fun publish(installer: FamilyFirmwareInstaller, glasses: FamilyGlassesFirmware) {
        dispatch(
            installer.family,
            FamilyOtaEvent.Refreshed(glasses.firmwareVersion, glasses.modelCode, glasses.batteryPercent, blockersNow(installer)),
        )
    }

    private fun blockersNow(installer: FamilyFirmwareInstaller): Set<FamilyOtaBlocker> {
        val otherSession = GlassesSessionCoordinator.currentSession() != null && lease == null && readLease == null
        val audioBusy = AudioSessionCoordinator.snapshot().any {
            it.kind in AUDIO_BUSY_KINDS && it.state != AudioSessionState.ENDED
        }
        return FamilyFirmwareRules.blockers(
            connected = installer.isConnected(),
            batteryPercent = installer.knownBatteryPercent(),
            family = installer.family,
            glassesBusy = otherSession,
            audioBusy = audioBusy,
        )
    }

    private fun showBlocked(installer: FamilyFirmwareInstaller, blockers: Set<FamilyOtaBlocker>) {
        dispatch(installer.family, FamilyOtaEvent.Refreshed(blockers = blockersNow(installer)))
        toast(
            when {
                FamilyOtaBlocker.NOT_CONNECTED in blockers -> "Connect the glasses to update their firmware."
                FamilyOtaBlocker.BATTERY_LOW in blockers ->
                    "Charge the glasses to at least ${FamilyFirmwareRules.minBatteryPercent(installer.family)}% first."
                FamilyOtaBlocker.GLASSES_BUSY in blockers -> "Stop the media sync or live preview first."
                else -> "Wait until the voice question, Gemini Live or recording ends."
            },
        )
    }

    private fun dispatch(family: CustomizationFamily, event: FamilyOtaEvent) {
        activity.runOnUiThread {
            val state = host.dashboardState
            val ota = state.familyOta?.takeIf { it.family == family } ?: return@runOnUiThread
            val next = FamilyOtaMachine.reduce(ota, event)
            if (next != ota) host.dashboardState = state.copy(familyOta = next)
        }
    }

    private fun setKeepScreenOn(on: Boolean) {
        activity.runOnUiThread {
            if (on) {
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    private fun toast(message: String) {
        activity.runOnUiThread { Toast.makeText(activity, message, Toast.LENGTH_LONG).show() }
    }

    private fun FamilyGlassesFirmware.toFirmwareInfo() = GlassesFirmwareInfo(
        family = family,
        model = modelCode ?: family.label,
        bleHardwareVersion = modelCode ?: family.wire,
        bleFirmwareVersion = firmwareVersion ?: "unknown",
    )

    private fun sha256Hex(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** A failure whose message is meant for the user as is. */
    private class UserFacing(message: String) : Exception(message)

    private companion object {
        const val TAG = "FamilyOta"
        const val PICKER_KEY = "family_ota_firmware_file"
        const val REFRESH_MS = 2_000L
        const val AUTO_READ_ATTEMPTS = 3
        const val AUTO_READ_SETTLE_MS = 5_000L
        val AUDIO_BUSY_KINDS = setOf(AudioSessionKind.VOICE_COMMAND, AudioSessionKind.CONVERSATION, AudioSessionKind.RECORDING)
    }
}

package com.fersaiyan.cyanbridge.shared.devices.mentra

import com.fersaiyan.cyanbridge.shared.glasses.GlassesSession
import com.fersaiyan.cyanbridge.shared.glasses.GlassesSessionCoordinator
import com.fersaiyan.cyanbridge.shared.glasses.GlassesSessionLease
import com.fersaiyan.cyanbridge.shared.platform.PlatformLogger
import com.fersaiyan.cyanbridge.shared.platform.platformCurrentTimeMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Result of the SDK update check plus the manifest it used. */
data class MentraOtaCheckResult(val updateAvailable: Boolean, val manifest: MentraOtaManifest)

/** The check could not run; [unofficialClient] when the glasses run a sideloaded glasses app. */
class MentraOtaCheckException(message: String, val unofficialClient: Boolean = false, cause: Throwable? = null) :
    Exception(message, cause)

/** `ota_start` failed; [code] is the SDK error code (for example `battery_low`). */
class MentraOtaStartException(message: String, val code: String?, cause: Throwable? = null) : Exception(message, cause)

data class MentraWifiNetwork(val ssid: String, val secured: Boolean, val signal: Int)

enum class MentraWifiError { SCAN_FAILED, WRONG_PASSWORD, CONNECT_FAILED }

data class MentraWifiSetupState(
    val scanning: Boolean = false,
    val scanned: Boolean = false,
    val networks: List<MentraWifiNetwork> = emptyList(),
    val connectingSsid: String? = null,
    val error: MentraWifiError? = null,
)

/** The Mentra SDK calls the firmware update needs; Android implements it over the SDK. */
interface MentraOtaPort {
    val glasses: StateFlow<MentraOtaGlasses>
    val statusEvents: Flow<MentraOtaStatusEvent>

    /** An app feature streams the glasses microphone right now (read on demand, not a flow). */
    val microphoneInUse: Boolean get() = false

    /** Asks the glasses for their versions (system and BES are only in the version info). */
    suspend fun refreshVersions()

    /** Throws [MentraOtaCheckException]. */
    suspend fun checkForUpdate(): MentraOtaCheckResult

    /** Sends `ota_start` with the SDK's pinned manifest; returns on the glasses' ack. Throws [MentraOtaStartException]. */
    suspend fun startUpdate()

    suspend fun scanWifi(): List<MentraWifiNetwork>

    /** Returns null when the glasses joined [ssid], otherwise why not. */
    suspend fun connectWifi(ssid: String, password: String): MentraWifiError?

    /** While true, the platform refuses unrelated glasses commands (photos, microphone, button). */
    fun setUpdateActive(active: Boolean)
}

/**
 * Runs one Mentra Live firmware update over [port] with [MentraOtaMachine]. It outlives the
 * screen (the platform keeps one per process) so an update keeps its state when the screen is
 * recreated. While the update runs it holds the exclusive [GlassesSession.OTA] lease and tells
 * the platform to refuse other glasses commands.
 */
class MentraFirmwareUpdateSession(
    private val port: MentraOtaPort,
    private val scope: CoroutineScope,
    private val clock: () -> Long = ::platformCurrentTimeMillis,
) {
    private val _state = MutableStateFlow(MentraOtaState())
    val state: StateFlow<MentraOtaState> = _state.asStateFlow()
    val glasses: StateFlow<MentraOtaGlasses> get() = port.glasses

    private val _wifi = MutableStateFlow(MentraWifiSetupState())
    val wifi: StateFlow<MentraWifiSetupState> = _wifi.asStateFlow()

    private var lease: GlassesSessionLease? = null
    private var tickJob: Job? = null
    private var handledCheckToken = 0
    private var startJob: Job? = null
    private var lastStage: MentraOtaStage? = null
    private var screenOpened = false
    private var updateActive = false

    init {
        scope.launch {
            port.statusEvents.collect { event ->
                PlatformLogger.i(TAG, "ota_status ${event.status} ${event.stepType}/${event.phase} step ${event.currentStep}/${event.totalSteps} ${event.stepPercent}%/${event.overallPercent}% error=${event.error}")
                reduce { MentraOtaMachine.status(it, event, clock()) }
            }
        }
        scope.launch {
            port.glasses.map { it.ready }.distinctUntilChanged().collect { ready ->
                reduce { MentraOtaMachine.connectionChanged(it, ready, clock()) }
                // The screen opened before the glasses connected: check now.
                if (ready && screenOpened && state.value.stage == MentraOtaStage.IDLE) check()
            }
        }
        scope.launch {
            port.glasses.map { it.versions.buildNumber }.distinctUntilChanged().collect { build ->
                reduce { MentraOtaMachine.buildChanged(it, build, clock()) }
            }
        }
        scope.launch { state.collect(::onState) }
    }

    /** True when another workflow (media sync, live preview, HeyCyan OTA) holds the glasses. */
    fun otherSessionActive(): Boolean {
        val current = GlassesSessionCoordinator.currentSession() ?: return false
        val own = lease
        return own == null || !GlassesSessionCoordinator.isActive(own) || current != GlassesSession.OTA
    }

    fun blockers(nowMs: Long = clock()): Set<MentraOtaBlocker> {
        val current = state.value
        val glasses = port.glasses.value.let { if (port.microphoneInUse) it.copy(microphoneInUse = true) else it }
        return MentraOtaRules.blockers(glasses, current.offer, otherSessionActive(), current.cooldownUntilMs, nowMs)
    }

    /** Called when the screen opens: reads the versions and checks, unless an update runs. */
    fun onScreenOpened() {
        screenOpened = true
        val current = state.value
        if (current.isActive) return
        scope.launch { runCatching { port.refreshVersions() } }
        if (current.stage == MentraOtaStage.IDLE || current.stage == MentraOtaStage.UP_TO_DATE ||
            current.stage == MentraOtaStage.COMPLETE || current.stage == MentraOtaStage.CHECK_FAILED
        ) {
            check()
        }
    }

    fun check() {
        if (!port.glasses.value.ready) return
        reduce(MentraOtaMachine::checkStarted)
    }

    /** Sends `ota_start` after the user confirmed. Returns false when a blocker prevents it. */
    fun start(): Boolean {
        val current = state.value
        if (current.stage != MentraOtaStage.UPDATE_AVAILABLE || startJob?.isActive == true) return false
        val blockers = blockers()
        if (blockers.isNotEmpty()) {
            PlatformLogger.w(TAG, "Start refused: $blockers")
            return false
        }
        val acquired = GlassesSessionCoordinator.tryAcquireLease(GlassesSession.OTA)
        if (acquired == null) {
            PlatformLogger.w(TAG, "Start refused: ${GlassesSessionCoordinator.currentSession()} holds the glasses")
            return false
        }
        lease = acquired
        signalUpdateActive(true)
        val build = port.glasses.value.versions.buildNumber
        reduce { MentraOtaMachine.startRequested(it, clock(), build) }
        PlatformLogger.i(TAG, "Update pass ${state.value.passes} starting: ${current.offer?.components} -> ${current.offer?.manifest?.releaseVersion}")
        startJob = scope.launch {
            try {
                port.startUpdate()
                PlatformLogger.i(TAG, "ota_start acknowledged")
                reduce { MentraOtaMachine.startAcknowledged(it, clock()) }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: MentraOtaStartException) {
                PlatformLogger.w(TAG, "ota_start failed code=${error.code}: ${error.message}")
                reduce { MentraOtaMachine.startFailed(it, error.code, clock()) }
            } catch (error: Throwable) {
                PlatformLogger.w(TAG, "ota_start failed: ${error.message}", error)
                reduce { MentraOtaMachine.startFailed(it, null, clock()) }
            }
        }
        return true
    }

    fun scanWifi() {
        if (_wifi.value.scanning || state.value.isActive) return
        _wifi.update { it.copy(scanning = true, error = null) }
        scope.launch {
            val networks = runCatching { port.scanWifi() }
            _wifi.update { current ->
                networks.fold(
                    onSuccess = { list ->
                        current.copy(
                            scanning = false,
                            scanned = true,
                            networks = list.filter { it.ssid.isNotBlank() }.distinctBy { it.ssid }.sortedByDescending { it.signal },
                        )
                    },
                    onFailure = { current.copy(scanning = false, scanned = true, error = MentraWifiError.SCAN_FAILED) },
                )
            }
        }
    }

    fun connectWifi(ssid: String, password: String) {
        if (_wifi.value.connectingSsid != null || state.value.isActive) return
        _wifi.update { it.copy(connectingSsid = ssid, error = null) }
        scope.launch {
            val error = runCatching { port.connectWifi(ssid, password) }.getOrElse { MentraWifiError.CONNECT_FAILED }
            PlatformLogger.i(TAG, "Glasses Wi-Fi join result=${error ?: "connected"}")
            _wifi.update { it.copy(connectingSsid = null, error = error) }
        }
    }

    private fun reduce(transform: (MentraOtaState) -> MentraOtaState) {
        _state.update(transform)
    }

    private fun onState(current: MentraOtaState) {
        if (current.stage != lastStage) {
            lastStage = current.stage
            PlatformLogger.i(TAG, "stage=${current.stage} failure=${current.failure} code=${current.glassesErrorCode} restartRequired=${current.restartRequired}")
        }
        if (current.isActive) {
            // Progress can also arrive when the screen did not start the update (it reopened
            // during one, or a late status revived a failed attempt): hold the glasses anyway.
            signalUpdateActive(true)
            if (lease == null) lease = GlassesSessionCoordinator.tryAcquireLease(GlassesSession.OTA)
            if (tickJob?.isActive != true) {
                tickJob = scope.launch {
                    while (isActive) {
                        delay(TICK_MS)
                        reduce { MentraOtaMachine.tick(it, clock()) }
                    }
                }
            }
        } else {
            tickJob?.cancel()
            tickJob = null
            releaseLease()
            signalUpdateActive(false)
        }
        val checking = current.stage == MentraOtaStage.CHECKING || current.stage == MentraOtaStage.VERIFYING
        if (checking && current.checkToken != handledCheckToken) {
            handledCheckToken = current.checkToken
            runCheck(current.checkToken)
        }
    }

    private fun runCheck(token: Int) {
        scope.launch {
            var attempt = 1
            while (true) {
                try {
                    val result = port.checkForUpdate()
                    runCatching { port.refreshVersions() }
                    val offer = if (result.updateAvailable) MentraOtaRules.offer(result.manifest, port.glasses.value.versions) else null
                    PlatformLogger.i(TAG, "Check: updateAvailable=${result.updateAvailable} release=${result.manifest.releaseVersion} components=${offer?.components}")
                    reduce { MentraOtaMachine.checkSucceeded(it, token, result.manifest, offer) }
                    return@launch
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: Throwable) {
                    val unofficial = (error as? MentraOtaCheckException)?.unofficialClient == true
                    PlatformLogger.w(TAG, "Check attempt $attempt failed: ${error.message}", error)
                    // After an install the glasses app may still be restarting: retry the re-check.
                    val verifying = state.value.stage == MentraOtaStage.VERIFYING && state.value.checkToken == token
                    if (verifying && !unofficial && attempt < VERIFY_ATTEMPTS) {
                        attempt++
                        delay(VERIFY_RETRY_MS)
                        continue
                    }
                    reduce { MentraOtaMachine.checkFailed(it, token, unofficial) }
                    return@launch
                }
            }
        }
    }

    private fun releaseLease() {
        val held = lease ?: return
        lease = null
        GlassesSessionCoordinator.release(held)
        PlatformLogger.i(TAG, "Released the glasses")
    }

    private fun signalUpdateActive(active: Boolean) {
        if (updateActive == active) return
        updateActive = active
        port.setUpdateActive(active)
    }

    private companion object {
        const val TAG = "MentraOta"
        const val TICK_MS = 1_000L
        const val VERIFY_ATTEMPTS = 4
        const val VERIFY_RETRY_MS = 10_000L
    }
}

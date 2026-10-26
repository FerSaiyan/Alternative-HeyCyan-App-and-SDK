package com.fersaiyan.cyanbridge.shared.devices.mentra

/** One `ota_status` event from the glasses (fields as the Mentra SDK reports them). */
data class MentraOtaStatusEvent(
    val sessionId: String = "",
    /** idle, in_progress, step_complete, complete or failed. */
    val status: String,
    /** download or install. */
    val phase: String = "",
    /** apk, mtk or bes. */
    val stepType: String = "",
    val currentStep: Int = 0,
    val totalSteps: Int = 0,
    val stepPercent: Int = 0,
    val overallPercent: Int = 0,
    val error: String? = null,
)

enum class MentraOtaStage {
    IDLE,
    CHECKING,
    CHECK_FAILED,
    UP_TO_DATE,
    UPDATE_AVAILABLE,

    /** `ota_start` sent; waiting for the first progress. */
    STARTING,
    UPDATING,

    /** A step finished; waiting briefly because the glasses may restart before the re-check. */
    FINISHING,

    /** The glasses dropped the link during the update (a planned or unplanned restart). */
    RESTARTING,

    /** Re-checking the manifest after an install pass. */
    VERIFYING,
    COMPLETE,
    FAILED,
}

enum class MentraOtaPhase { DOWNLOAD, INSTALL, OTHER }

data class MentraOtaProgress(
    val component: MentraOtaComponent?,
    val phase: MentraOtaPhase,
    val currentStep: Int,
    val totalSteps: Int,
    val percent: Int,
)

enum class MentraOtaFailure {
    CHECK_FAILED,
    UNOFFICIAL_CLIENT,
    START_FAILED,
    BATTERY_LOW,
    GLASSES_ERROR,
    NO_PROGRESS,
    STALLED,
    RESTART_TIMEOUT,
    SESSION_TIMEOUT,
}

data class MentraOtaState(
    val stage: MentraOtaStage = MentraOtaStage.IDLE,
    val offer: MentraOtaOffer? = null,
    /** The last manifest the check read, also kept when the glasses are up to date. */
    val manifest: MentraOtaManifest? = null,
    val progress: MentraOtaProgress? = null,
    val failure: MentraOtaFailure? = null,
    val glassesErrorCode: String? = null,
    /**
     * A BES firmware install started and the update did not finish cleanly: the BES may be in
     * its raw update mode. Never offer a retry until the glasses restart (disconnect).
     */
    val restartRequired: Boolean = false,
    /** Install passes started on this screen; more than one when the manifest needs several. */
    val passes: Int = 0,
    /** At least one pass reported success, so a later "update available" means "continue". */
    val installedDuringSession: Boolean = false,
    /** Incremented for every check, so a stale result is dropped. */
    val checkToken: Int = 0,
    val passStartedAtMs: Long = 0,
    val lastEventAtMs: Long = 0,
    val disconnectedAtMs: Long? = null,
    val settleUntilMs: Long = 0,
    /** The glasses reported a BES install, which ends with a power cycle. */
    val besInstallStarted: Boolean = false,
    val awaitingBesReboot: Boolean = false,
    /** Start stays blocked until then: after a phone-side failure the glasses may still work. */
    val cooldownUntilMs: Long = 0,
    private val progressKey: String? = null,
) {
    /** The update runs: keep the screen on, refuse to leave and keep the glasses to ourselves. */
    val isActive: Boolean get() = stage in ACTIVE_STAGES

    internal val displayKey: String? get() = progressKey

    internal fun withProgressKey(key: String?) = copy(progressKey = key)

    companion object {
        private val ACTIVE_STAGES = setOf(
            MentraOtaStage.STARTING,
            MentraOtaStage.UPDATING,
            MentraOtaStage.FINISHING,
            MentraOtaStage.RESTARTING,
            MentraOtaStage.VERIFYING,
        )
    }
}

/**
 * The phone side of a Mentra Live update as a pure reducer: every function returns the next
 * state; the caller runs the SDK calls. Timings follow the official Mentra app's watchdogs,
 * padded by the 20 s it adds for older glasses builds, because declaring a failure too early is
 * worse than waiting: the glasses keep working whatever the phone shows.
 *
 * Safety rules it encodes:
 * - Every `ota_start` follows a successful check on the current link ([startRequested] only
 *   leaves [MentraOtaStage.UPDATE_AVAILABLE]); a failed attempt goes back through a check.
 * - A phone-side failure (no progress, stall, timeout, start not acknowledged) sets a cool-down,
 *   and any later progress from the glasses revives the update instead of allowing a restart.
 * - Once the BES firmware install has started, a failure requires a glasses restart before
 *   anything else ([MentraOtaState.restartRequired]); the official app treats it the same way.
 */
object MentraOtaMachine {
    const val START_NO_PROGRESS_MS = 90_000L
    const val PROGRESS_STALL_MS = 140_000L
    const val SYSTEM_INSTALL_STALL_MS = 320_000L
    const val BES_REBOOT_TIMEOUT_MS = 140_000L
    const val RECONNECT_TIMEOUT_MS = 300_000L
    const val SETTLE_MS = 10_000L
    const val PASS_TIMEOUT_MS = 20 * 60_000L + 20_000L
    const val COOLDOWN_MS = 90_000L

    fun checkStarted(state: MentraOtaState): MentraOtaState {
        if (state.isActive || state.restartRequired) return state
        return state.copy(
            stage = MentraOtaStage.CHECKING,
            failure = null,
            glassesErrorCode = null,
            checkToken = state.checkToken + 1,
        )
    }

    /** [offer] is null when the glasses already match the manifest. */
    fun checkSucceeded(
        state: MentraOtaState,
        token: Int,
        manifest: MentraOtaManifest,
        offer: MentraOtaOffer?,
    ): MentraOtaState {
        if (token != state.checkToken) return state
        return when (state.stage) {
            MentraOtaStage.CHECKING -> state.copy(
                stage = if (offer == null) MentraOtaStage.UP_TO_DATE else MentraOtaStage.UPDATE_AVAILABLE,
                manifest = manifest,
                offer = offer,
            )
            MentraOtaStage.VERIFYING -> state.copy(
                stage = if (offer == null) MentraOtaStage.COMPLETE else MentraOtaStage.UPDATE_AVAILABLE,
                manifest = manifest,
                offer = offer,
                progress = null,
                installedDuringSession = true,
            )
            else -> state
        }
    }

    fun checkFailed(state: MentraOtaState, token: Int, unofficialClient: Boolean): MentraOtaState {
        if (token != state.checkToken) return state
        if (state.stage != MentraOtaStage.CHECKING && state.stage != MentraOtaStage.VERIFYING) return state
        return state.copy(
            stage = MentraOtaStage.CHECK_FAILED,
            failure = if (unofficialClient) MentraOtaFailure.UNOFFICIAL_CLIENT else MentraOtaFailure.CHECK_FAILED,
            offer = null,
            installedDuringSession = state.installedDuringSession || state.stage == MentraOtaStage.VERIFYING,
        )
    }

    /** The user confirmed; the caller checked the blockers and then sends `ota_start`. */
    fun startRequested(state: MentraOtaState, nowMs: Long): MentraOtaState {
        if (state.stage != MentraOtaStage.UPDATE_AVAILABLE || state.offer == null) return state
        return state.copy(
            stage = MentraOtaStage.STARTING,
            passes = state.passes + 1,
            progress = null,
            failure = null,
            glassesErrorCode = null,
            passStartedAtMs = nowMs,
            lastEventAtMs = nowMs,
            disconnectedAtMs = null,
            besInstallStarted = false,
            awaitingBesReboot = false,
        ).withProgressKey(null)
    }

    fun startAcknowledged(state: MentraOtaState, nowMs: Long): MentraOtaState =
        if (state.stage == MentraOtaStage.STARTING) state.copy(lastEventAtMs = nowMs) else state

    /** `ota_start` failed. `battery_low` is the glasses refusing; anything else may hide a start. */
    fun startFailed(state: MentraOtaState, errorCode: String?, nowMs: Long): MentraOtaState {
        if (state.stage != MentraOtaStage.STARTING) return state
        val batteryLow = MentraOtaRules.errorKind(errorCode) == MentraOtaErrorKind.BATTERY
        return state.copy(
            stage = MentraOtaStage.FAILED,
            failure = if (batteryLow) MentraOtaFailure.BATTERY_LOW else MentraOtaFailure.START_FAILED,
            glassesErrorCode = errorCode,
            cooldownUntilMs = if (batteryLow) 0 else nowMs + COOLDOWN_MS,
        )
    }

    fun status(state: MentraOtaState, event: MentraOtaStatusEvent, nowMs: Long): MentraOtaState {
        val status = event.status.trim().lowercase()
        val component = component(event.stepType)
        val phase = phase(event.phase)
        val besInstall = component == MentraOtaComponent.BES && phase == MentraOtaPhase.INSTALL
        return when (status) {
            // No session on the glasses: nothing runs, so a cool-down is no longer needed.
            "idle" -> if (state.isActive && !event.error.isNullOrBlank()) {
                failed(state, event, nowMs)
            } else {
                state.copy(cooldownUntilMs = 0)
            }
            "in_progress", "step_complete" -> progressed(state, event, component, phase, besInstall, nowMs)
            "failed" -> failed(state, event, nowMs)
            "complete" -> when {
                // Older glasses builds report "complete" for a finished download; the install is ahead.
                event.sessionId.isEmpty() && phase == MentraOtaPhase.DOWNLOAD ->
                    progressed(state, event, component, phase, besInstall, nowMs)
                // The glasses power-cycle right after a BES install; wait for the reconnect.
                component == MentraOtaComponent.BES || state.besInstallStarted -> state.copy(
                    stage = MentraOtaStage.RESTARTING,
                    progress = state.progress?.copy(percent = 100),
                    awaitingBesReboot = true,
                    disconnectedAtMs = null,
                    lastEventAtMs = nowMs,
                    cooldownUntilMs = 0,
                )
                else -> state.copy(
                    stage = MentraOtaStage.FINISHING,
                    progress = state.progress?.copy(percent = 100),
                    settleUntilMs = nowMs + SETTLE_MS,
                    lastEventAtMs = nowMs,
                    cooldownUntilMs = 0,
                )
            }
            else -> state
        }
    }

    fun connectionChanged(state: MentraOtaState, ready: Boolean, nowMs: Long): MentraOtaState {
        if (!ready) {
            return when (state.stage) {
                MentraOtaStage.STARTING,
                MentraOtaStage.UPDATING,
                MentraOtaStage.FINISHING,
                MentraOtaStage.VERIFYING -> state.copy(stage = MentraOtaStage.RESTARTING, disconnectedAtMs = nowMs)
                MentraOtaStage.RESTARTING -> state.copy(disconnectedAtMs = state.disconnectedAtMs ?: nowMs)
                // The glasses went down: that is the restart a BES failure asks for.
                else -> if (state.restartRequired) state.copy(restartRequired = false) else state
            }
        }
        return when {
            state.stage == MentraOtaStage.RESTARTING && state.disconnectedAtMs != null ->
                state.copy(stage = MentraOtaStage.FINISHING, settleUntilMs = nowMs + SETTLE_MS, disconnectedAtMs = null)
            // The glasses came back after the restart watchdog gave up: that was the restart.
            state.stage == MentraOtaStage.FAILED && state.failure == MentraOtaFailure.RESTART_TIMEOUT ->
                state.copy(restartRequired = false)
            else -> state
        }
    }

    /** Watchdogs; call about once a second while [MentraOtaState.isActive]. */
    fun tick(state: MentraOtaState, nowMs: Long): MentraOtaState {
        if (!state.isActive) return state
        if (nowMs - state.passStartedAtMs > PASS_TIMEOUT_MS) {
            return phoneSideFailure(state, MentraOtaFailure.SESSION_TIMEOUT, nowMs)
        }
        return when (state.stage) {
            MentraOtaStage.STARTING ->
                if (nowMs - state.lastEventAtMs > START_NO_PROGRESS_MS) {
                    phoneSideFailure(state, MentraOtaFailure.NO_PROGRESS, nowMs)
                } else {
                    state
                }
            MentraOtaStage.UPDATING -> {
                val progress = state.progress
                val limit = if (progress?.component == MentraOtaComponent.SYSTEM && progress.phase == MentraOtaPhase.INSTALL) {
                    SYSTEM_INSTALL_STALL_MS
                } else {
                    PROGRESS_STALL_MS
                }
                if (nowMs - state.lastEventAtMs > limit) phoneSideFailure(state, MentraOtaFailure.STALLED, nowMs) else state
            }
            MentraOtaStage.RESTARTING -> {
                val since = state.disconnectedAtMs ?: state.lastEventAtMs
                val limit = if (state.awaitingBesReboot) BES_REBOOT_TIMEOUT_MS else RECONNECT_TIMEOUT_MS
                if (nowMs - since > limit) {
                    phoneSideFailure(state, MentraOtaFailure.RESTART_TIMEOUT, nowMs).copy(restartRequired = true)
                } else {
                    state
                }
            }
            MentraOtaStage.FINISHING ->
                if (nowMs >= state.settleUntilMs) {
                    state.copy(stage = MentraOtaStage.VERIFYING, checkToken = state.checkToken + 1)
                } else {
                    state
                }
            else -> state
        }
    }

    private fun progressed(
        state: MentraOtaState,
        event: MentraOtaStatusEvent,
        component: MentraOtaComponent?,
        phase: MentraOtaPhase,
        besInstall: Boolean,
        nowMs: Long,
    ): MentraOtaState {
        // As the official app: the download shows its own file, the install the whole pass.
        val raw = when {
            phase == MentraOtaPhase.DOWNLOAD -> event.stepPercent
            event.totalSteps >= 2 -> event.overallPercent
            else -> event.stepPercent
        }.coerceIn(0, 100)
        val key = "${event.sessionId}|${event.currentStep}|${event.stepType}|${event.phase}"
        val previous = state.progress?.percent?.takeIf { state.displayKey == key }
        val percent = maxOf(previous ?: raw, raw)
        val wasActive = state.isActive
        return state.copy(
            stage = MentraOtaStage.UPDATING,
            progress = MentraOtaProgress(
                component = component,
                phase = phase,
                currentStep = event.currentStep,
                totalSteps = event.totalSteps,
                percent = percent,
            ),
            failure = null,
            glassesErrorCode = null,
            lastEventAtMs = nowMs,
            passStartedAtMs = if (wasActive) state.passStartedAtMs else nowMs,
            disconnectedAtMs = null,
            besInstallStarted = state.besInstallStarted || besInstall,
            cooldownUntilMs = 0,
        ).withProgressKey(key)
    }

    private fun failed(state: MentraOtaState, event: MentraOtaStatusEvent, nowMs: Long): MentraOtaState {
        val besInstall = component(event.stepType) == MentraOtaComponent.BES &&
            phase(event.phase) == MentraOtaPhase.INSTALL
        val code = event.error?.takeIf { it.isNotBlank() }
        return state.copy(
            stage = MentraOtaStage.FAILED,
            failure = if (MentraOtaRules.errorKind(code) == MentraOtaErrorKind.BATTERY) {
                MentraOtaFailure.BATTERY_LOW
            } else {
                MentraOtaFailure.GLASSES_ERROR
            },
            glassesErrorCode = code,
            restartRequired = state.restartRequired || state.besInstallStarted || besInstall,
            lastEventAtMs = nowMs,
            // The glasses ended their session themselves.
            cooldownUntilMs = 0,
        )
    }

    private fun phoneSideFailure(state: MentraOtaState, failure: MentraOtaFailure, nowMs: Long) = state.copy(
        stage = MentraOtaStage.FAILED,
        failure = failure,
        restartRequired = state.restartRequired || state.besInstallStarted,
        cooldownUntilMs = nowMs + COOLDOWN_MS,
    )

    fun component(stepType: String): MentraOtaComponent? = when (stepType.trim().lowercase()) {
        "apk" -> MentraOtaComponent.APP
        "mtk" -> MentraOtaComponent.SYSTEM
        "bes" -> MentraOtaComponent.BES
        else -> null
    }

    private fun phase(phase: String): MentraOtaPhase = when (phase.trim().lowercase()) {
        "download" -> MentraOtaPhase.DOWNLOAD
        "install" -> MentraOtaPhase.INSTALL
        else -> MentraOtaPhase.OTHER
    }
}

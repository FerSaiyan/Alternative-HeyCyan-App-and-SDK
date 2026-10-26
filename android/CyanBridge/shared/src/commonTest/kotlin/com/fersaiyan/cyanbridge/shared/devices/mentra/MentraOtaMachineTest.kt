package com.fersaiyan.cyanbridge.shared.devices.mentra

import com.fersaiyan.cyanbridge.shared.devices.mentra.MentraOtaMachine as M
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MentraOtaMachineTest {
    private val manifest = MentraOtaManifest(
        releaseVersion = "3.2.0-dev.227",
        appVersionCode = 45,
        besVersion = "17.26.1.14",
        includesSystemPatch = true,
    )
    private val versions = MentraGlassesVersions(appVersion = "3.1.0", buildNumber = "41", besVersion = "17.26.1.10")
    private val glasses = MentraOtaGlasses(ready = true, batteryPercent = 80, wifiSsid = "Home", versions = versions)

    private fun available(): MentraOtaState {
        val checking = M.checkStarted(MentraOtaState())
        return M.checkSucceeded(checking, checking.checkToken, manifest, MentraOtaRules.offer(manifest, versions))
    }

    private fun status(status: String, step: String, phase: String, percent: Int = 0, error: String? = null) =
        MentraOtaStatusEvent("s1", status, phase, step, currentStep = 1, totalSteps = 2, stepPercent = percent, overallPercent = percent / 2, error = error)

    @Test
    fun blockersFollowTheOfficialPreconditions() {
        fun blockers(g: MentraOtaGlasses, busy: Boolean = false, cooldown: Long = 0) =
            MentraOtaRules.blockers(g, null, busy, cooldown, nowMs = 1_000)
        assertEquals(emptySet(), blockers(glasses.copy(batteryPercent = 25)))
        assertEquals(emptySet(), blockers(glasses.copy(batteryPercent = null)), "unknown battery fails open")
        assertEquals(setOf(MentraOtaBlocker.BATTERY_LOW), blockers(glasses.copy(batteryPercent = 24)))
        assertEquals(setOf(MentraOtaBlocker.WIFI_REQUIRED), blockers(glasses.copy(wifiSsid = null)))
        assertEquals(
            setOf(MentraOtaBlocker.NOT_CONNECTED, MentraOtaBlocker.MICROPHONE_IN_USE, MentraOtaBlocker.GLASSES_BUSY, MentraOtaBlocker.COOLDOWN),
            blockers(glasses.copy(ready = false, microphoneInUse = true), busy = true, cooldown = 2_000),
        )
    }

    @Test
    fun offerNamesTheChangedPartsAndFlagsADowngrade() {
        val offer = MentraOtaRules.offer(manifest, versions)
        assertEquals(setOf(MentraOtaComponent.APP, MentraOtaComponent.BES), offer.components)
        assertFalse(offer.downgrade)
        val newer = MentraOtaRules.offer(manifest, versions.copy(buildNumber = "50", besVersion = "17.26.1.14"))
        assertTrue(newer.downgrade)
        assertEquals(setOf(MentraOtaComponent.SYSTEM), newer.components)
        assertTrue(MentraOtaBlocker.DOWNGRADE in MentraOtaRules.blockers(glasses, newer, false, 0, 0))
    }

    @Test
    fun besUpdateFinishesOnlyAfterTheRebootAndARecheck() {
        var s = M.startRequested(available(), nowMs = 0)
        assertEquals(MentraOtaStage.STARTING, s.stage)
        s = M.status(s, status("in_progress", "apk", "download", percent = 60), 1_000)
        s = M.status(s, status("in_progress", "apk", "download", percent = 40), 2_000)
        assertEquals(60, s.progress?.percent, "progress never goes back within a step")
        s = M.status(s, status("in_progress", "bes", "install", percent = 10), 3_000)
        s = M.status(s, status("complete", "bes", "install", percent = 100), 4_000)
        assertEquals(MentraOtaStage.RESTARTING, s.stage)
        s = M.connectionChanged(s, ready = false, nowMs = 5_000)
        s = M.connectionChanged(s, ready = true, nowMs = 30_000)
        assertEquals(MentraOtaStage.FINISHING, s.stage)
        assertTrue(s.isActive)
        s = M.tick(s, 30_000 + M.SETTLE_MS)
        assertEquals(MentraOtaStage.VERIFYING, s.stage)
        s = M.checkSucceeded(s, s.checkToken, manifest, offer = null)
        assertEquals(MentraOtaStage.COMPLETE, s.stage)
        assertFalse(s.isActive)
    }

    @Test
    fun aNewGlassesAppBuildEndsTheAppStepAndAContinuationIsOffered() {
        var s = M.startRequested(available(), nowMs = 0, buildNumber = "41")
        s = M.status(s, status("in_progress", "apk", "install", percent = 50), 1_000)
        assertEquals(s, M.buildChanged(s, "41", 2_000))
        s = M.buildChanged(s, "45", 2_000)
        assertEquals(MentraOtaStage.FINISHING, s.stage)
        s = M.tick(s, 2_000 + M.SETTLE_MS)
        s = M.checkSucceeded(s, s.checkToken, manifest, MentraOtaRules.offer(manifest, versions.copy(buildNumber = "45")))
        assertEquals(MentraOtaStage.UPDATE_AVAILABLE, s.stage)
        assertTrue(s.installedDuringSession)
        assertEquals(setOf(MentraOtaComponent.BES), s.offer?.components)
    }

    @Test
    fun aBesInstallFailureRequiresAGlassesRestartBeforeAnything() {
        var s = M.startRequested(available(), nowMs = 0)
        s = M.status(s, status("in_progress", "bes", "install", percent = 30), 1_000)
        s = M.status(s, status("failed", "bes", "install", error = "install_failed"), 2_000)
        assertEquals(MentraOtaStage.FAILED, s.stage)
        assertTrue(s.restartRequired)
        assertEquals(s, M.checkStarted(s), "no check or retry until the glasses restart")
        s = M.connectionChanged(s, ready = false, nowMs = 3_000)
        assertFalse(s.restartRequired)
        assertEquals(MentraOtaStage.CHECKING, M.checkStarted(s).stage)
    }

    @Test
    fun aPhoneSideTimeoutCoolsDownAndLateProgressRevivesTheUpdate() {
        var s = M.startRequested(available(), nowMs = 0)
        s = M.startAcknowledged(s, 500)
        s = M.tick(s, 500 + M.START_NO_PROGRESS_MS + 1)
        assertEquals(MentraOtaFailure.NO_PROGRESS, s.failure)
        assertFalse(s.restartRequired)
        assertTrue(MentraOtaBlocker.COOLDOWN in MentraOtaRules.blockers(glasses, null, false, s.cooldownUntilMs, 100_000))
        s = M.status(s, status("in_progress", "apk", "download", percent = 5), 100_000)
        assertEquals(MentraOtaStage.UPDATING, s.stage)
        assertEquals(0, s.cooldownUntilMs)

        // The glasses refusing a low battery is final: no cool-down.
        val refused = M.startFailed(M.startRequested(available(), 0), "battery_low", 1_000)
        assertEquals(MentraOtaFailure.BATTERY_LOW, refused.failure)
        assertEquals(0, refused.cooldownUntilMs)
    }
}

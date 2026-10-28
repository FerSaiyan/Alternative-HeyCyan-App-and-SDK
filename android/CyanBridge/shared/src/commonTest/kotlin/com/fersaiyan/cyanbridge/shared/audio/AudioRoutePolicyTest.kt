package com.fersaiyan.cyanbridge.shared.audio

import com.fersaiyan.cyanbridge.shared.audio.AudioRouteCandidate.Type.BLE_HEADSET
import com.fersaiyan.cyanbridge.shared.audio.AudioRouteCandidate.Type.BLUETOOTH_SCO
import com.fersaiyan.cyanbridge.shared.devices.GlassesFamilyProfiles
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AudioRoutePolicyTest {
    private val glasses = AudioRouteCandidate(1, "HeyCyan Glasses", BLUETOOTH_SCO)
    private val earbuds = AudioRouteCandidate(2, "TuneBuds", BLE_HEADSET)

    private fun env(
        vararg candidates: AudioRouteCandidate,
        modern: Boolean = true,
        likelyConnected: Boolean = candidates.isNotEmpty(),
    ) = AudioRouteEnvironment(modern, candidates.toList(), likelyConnected)

    @Test
    fun noBluetoothDeviceFallsBackToPhoneRouting() {
        val noDevice = env(likelyConnected = false)

        val conversation = AudioRoutePolicy.plan(AudioRoutePreference.COMMUNICATION_BLUETOOTH_PREFERRED, noDevice, AudioRouteState.IDLE)
        assertEquals(AudioRoutePlan(enterCommunicationMode = true, communicationDevice = null, legacySco = false), conversation)

        val recording = AudioRoutePolicy.plan(AudioRoutePreference.BLUETOOTH_IF_CONNECTED, noDevice, AudioRouteState.IDLE)
        assertEquals(AudioRoutePlan.NONE, recording)
        assertFalse(recording.changesRoute)
    }

    @Test
    fun systemDefaultAndDeviceSdkNeverTouchTheRoute() {
        listOf(AudioRoutePreference.SYSTEM_DEFAULT, AudioRoutePreference.DEVICE_SDK).forEach {
            assertEquals(AudioRoutePlan.NONE, AudioRoutePolicy.plan(it, env(glasses), AudioRouteState.IDLE))
        }
    }

    @Test
    fun modernPlatformSelectsTheCommunicationDevice() {
        val plan = AudioRoutePolicy.plan(AudioRoutePreference.BLUETOOTH_IF_CONNECTED, env(glasses), AudioRouteState.IDLE)
        assertEquals(AudioRoutePlan(enterCommunicationMode = true, communicationDevice = glasses, legacySco = false), plan)
    }

    @Test
    fun bluetoothHeadsetWithoutCommunicationDeviceUsesLegacySco() {
        val a2dpOnly = env(likelyConnected = true)
        val plan = AudioRoutePolicy.plan(AudioRoutePreference.BLUETOOTH_IF_CONNECTED, a2dpOnly, AudioRouteState.IDLE)
        assertEquals(AudioRoutePlan(enterCommunicationMode = true, communicationDevice = null, legacySco = true), plan)
    }

    @Test
    fun legacyPlatformAlwaysTriesScoForConversationButOnlyWhenConnectedForRecording() {
        val legacyNoHeadset = env(modern = false, likelyConnected = false)
        assertTrue(AudioRoutePolicy.plan(AudioRoutePreference.COMMUNICATION_BLUETOOTH_PREFERRED, legacyNoHeadset, AudioRouteState.IDLE).legacySco)
        assertEquals(AudioRoutePlan.NONE, AudioRoutePolicy.plan(AudioRoutePreference.BLUETOOTH_IF_CONNECTED, legacyNoHeadset, AudioRouteState.IDLE))
        val legacyHeadset = env(modern = false, likelyConnected = true)
        assertTrue(AudioRoutePolicy.plan(AudioRoutePreference.BLUETOOTH_IF_CONNECTED, legacyHeadset, AudioRouteState.IDLE).legacySco)
    }

    @Test
    fun preferredNameWinsAndStrictSelectionRejectsAnUnknownPair() {
        assertEquals(
            AudioRouteSelection.Selected(earbuds),
            AudioRoutePolicy.select(listOf(glasses, earbuds), "tunebuds", requireUnambiguous = true),
        )
        assertEquals(
            AudioRouteSelection.Ambiguous(listOf(glasses, earbuds)),
            AudioRoutePolicy.select(listOf(glasses, earbuds), "Other", requireUnambiguous = true),
        )
        assertEquals(AudioRouteSelection.Selected(glasses), AudioRoutePolicy.select(listOf(glasses, earbuds), "Other", requireUnambiguous = false))
        assertEquals(AudioRouteSelection.Selected(earbuds), AudioRoutePolicy.select(listOf(earbuds), null, requireUnambiguous = true))
        assertEquals(AudioRouteSelection.NoCandidate, AudioRoutePolicy.select(emptyList(), "x", requireUnambiguous = true))

        val ambiguous = AudioRoutePolicy.plan(
            AudioRoutePreference.COMMUNICATION_BLUETOOTH_PREFERRED,
            env(glasses, earbuds),
            AudioRouteState.IDLE,
            preferredName = "Other",
            requireUnambiguous = true,
        )
        assertTrue(ambiguous.ambiguous)
        assertEquals(null, ambiguous.communicationDevice)
        assertFalse(ambiguous.legacySco)
    }

    @Test
    fun joiningAnEstablishedBluetoothRouteAddsNothing() {
        val current = AudioRouteState(communicationMode = true, communicationDevice = glasses)
        val plan = AudioRoutePolicy.plan(AudioRoutePreference.COMMUNICATION_BLUETOOTH_PREFERRED, env(glasses), current)
        assertEquals(AudioRoutePlan.NONE, plan)
    }

    @Test
    fun conversationJoiningAPhoneOnlyRecordingStillEntersCommunicationMode() {
        val plan = AudioRoutePolicy.plan(
            AudioRoutePreference.COMMUNICATION_BLUETOOTH_PREFERRED,
            env(likelyConnected = false),
            AudioRouteState.IDLE,
        )
        assertTrue(plan.enterCommunicationMode)
    }

    @Test
    fun ledgerRestoresOnlyWhenTheLastUserLeaves() {
        val ledger = AudioRouteLedger()
        ledger.join(1, AudioRouteState(communicationMode = true, communicationDevice = glasses))
        ledger.join(2, AudioRouteState.IDLE)
        assertEquals(2, ledger.userCount)

        assertEquals(AudioRouteState.IDLE, ledger.leave(1))
        assertTrue(ledger.applied.bluetooth)
        assertEquals(AudioRouteState(communicationMode = true, communicationDevice = glasses), ledger.leave(2))
        assertEquals(AudioRouteState.IDLE, ledger.applied)
        assertEquals(AudioRouteState.IDLE, ledger.leave(2))
    }

    @Test
    fun ledgerAccumulatesStepsAddedByLaterUsers() {
        val ledger = AudioRouteLedger()
        ledger.join(1, AudioRouteState.IDLE)
        ledger.join(2, AudioRouteState(communicationMode = true, legacySco = true))
        ledger.leave(2)
        assertEquals(AudioRouteState(communicationMode = true, legacySco = true), ledger.leave(1))
    }

    @Test
    fun deviceProfileDecidesVendorPcmAndPlaybackSignal() {
        val mentra = GlassesFamilyProfiles.mentraLive.audio
        val heyCyan = GlassesFamilyProfiles.heyCyan.audio
        val conversation = AudioRoutePreference.COMMUNICATION_BLUETOOTH_PREFERRED
        assertEquals(AudioRoutePreference.DEVICE_SDK, AudioRoutePolicy.forDevice(conversation, mentra))
        assertEquals(conversation, AudioRoutePolicy.forDevice(conversation, heyCyan))
        assertEquals(conversation, AudioRoutePolicy.forDevice(conversation, null))
        assertEquals(AudioRoutePreference.SYSTEM_DEFAULT, AudioRoutePolicy.forDevice(AudioRoutePreference.SYSTEM_DEFAULT, mentra))
        assertTrue(AudioRoutePolicy.needsPlaybackSignal(mentra))
        assertFalse(AudioRoutePolicy.needsPlaybackSignal(heyCyan))
        assertFalse(AudioRoutePolicy.needsPlaybackSignal(null))
    }

    @Test
    fun questionsUseTheSdkStreamOnlyWhereTheProfileOffersItAndItIsUp() {
        val memoMind = GlassesFamilyProfiles.memoMind.audio
        val conversation = AudioRoutePreference.COMMUNICATION_BLUETOOTH_PREFERRED
        assertEquals(AudioRoutePreference.DEVICE_SDK, AudioRoutePolicy.forQuestion(memoMind, sdkStreamAvailable = true))
        // HFP fallback: link down, firmware without it, iOS over BLE.
        assertEquals(conversation, AudioRoutePolicy.forQuestion(memoMind, sdkStreamAvailable = false))
        // Everything else MemoMind does keeps the headset route.
        assertEquals(conversation, AudioRoutePolicy.forDevice(conversation, memoMind))
        val mentra = GlassesFamilyProfiles.mentraLive.audio
        assertEquals(AudioRoutePreference.DEVICE_SDK, AudioRoutePolicy.forQuestion(mentra, sdkStreamAvailable = false))
        assertEquals(conversation, AudioRoutePolicy.forQuestion(GlassesFamilyProfiles.heyCyan.audio, sdkStreamAvailable = true))
    }
}

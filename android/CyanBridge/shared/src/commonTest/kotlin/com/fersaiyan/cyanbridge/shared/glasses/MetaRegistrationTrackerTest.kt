package com.fersaiyan.cyanbridge.shared.glasses

import com.fersaiyan.cyanbridge.shared.glasses.MetaRegistrationPhase.NOT_REGISTERED
import com.fersaiyan.cyanbridge.shared.glasses.MetaRegistrationPhase.REGISTERED
import com.fersaiyan.cyanbridge.shared.glasses.MetaRegistrationPhase.REGISTERING
import com.fersaiyan.cyanbridge.shared.glasses.MetaRegistrationPhase.UNREGISTERING
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MetaRegistrationTrackerTest {
    private class MemoryStore : MetaRegistrationStore {
        override var choice = MetaSetupChoice.NONE
        override var path = MetaRegistrationPath.UNKNOWN
    }

    private val store = MemoryStore()
    private val tracker = MetaRegistrationTracker(store, appIdConfigured = true)

    @Test
    fun registrationIsNeverStartedWhileRegisteredOrBetweenStates() {
        assertFalse(tracker.beginRegistration(REGISTERED))
        assertFalse(tracker.beginRegistration(REGISTERING))
        assertFalse(tracker.beginRegistration(UNREGISTERING))
        assertTrue(tracker.beginRegistration(NOT_REGISTERED))
    }

    @Test
    fun pathIsRecordedOnlyAfterMetaAiReturns() {
        var reads = 0
        tracker.beginRegistration(NOT_REGISTERED)
        // The SDK resets its dev-mode value when registration starts: do not read it yet.
        assertEquals(MetaRegistrationEvent.None, tracker.onPhase(REGISTERED) { reads++; false })
        assertEquals(0, reads)
        tracker.registrationUiClosed()
        assertEquals(
            MetaRegistrationEvent.PathRecorded(MetaRegistrationPath.DEVELOPER_MODE),
            tracker.onPhase(REGISTERED) { true },
        )
        assertEquals(MetaRegistrationPath.DEVELOPER_MODE, store.path)
        assertEquals(MetaRegistrationEvent.None, tracker.onPhase(REGISTERED) { true })
    }

    @Test
    fun releaseChannelAndUnknownReadings() {
        assertEquals(
            MetaRegistrationEvent.PathRecorded(MetaRegistrationPath.RELEASE_CHANNEL),
            tracker.onPhase(REGISTERED) { false },
        )
        // An SDK without the flag never erases what was recorded.
        assertEquals(MetaRegistrationEvent.None, tracker.onPhase(REGISTERED) { null })
        assertEquals(MetaRegistrationPath.RELEASE_CHANNEL, store.path)
        // A build without an app id can only have registered through Developer Mode.
        assertEquals(
            MetaRegistrationPath.DEVELOPER_MODE,
            MetaRegistrationPolicy.pathAfterRegistration(sdkIsDevMode = null, appIdConfigured = false),
        )
    }

    @Test
    fun developerModeRegistrationEndingOnItsOwnIsReported() {
        tracker.onPhase(REGISTERED) { true }
        assertEquals(MetaRegistrationEvent.DeveloperModeLost, tracker.onPhase(NOT_REGISTERED) { true })
        // Reported once, and the path stays so the setup screen can explain it.
        assertEquals(MetaRegistrationEvent.None, tracker.onPhase(NOT_REGISTERED) { true })
        assertEquals(MetaRegistrationPath.DEVELOPER_MODE, store.path)
    }

    @Test
    fun userUnregistrationClearsThePathWithoutANotice() {
        tracker.onPhase(REGISTERED) { true }
        assertTrue(tracker.beginUnregistration(REGISTERED))
        tracker.onPhase(UNREGISTERING) { true }
        assertEquals(MetaRegistrationEvent.UserUnregistered, tracker.onPhase(NOT_REGISTERED) { false })
        assertEquals(MetaRegistrationPath.UNKNOWN, store.path)
    }

    @Test
    fun releaseChannelLossIsNotADeveloperModeNotice() {
        tracker.onPhase(REGISTERED) { false }
        assertEquals(MetaRegistrationEvent.None, tracker.onPhase(NOT_REGISTERED) { false })
    }

    @Test
    fun presentationOffersChoicesBeforeRegistrationOnly() {
        val before = metaSetupChoicePresentation(NOT_REGISTERED, MetaSetupChoice.NONE, MetaRegistrationPath.UNKNOWN, true)
        assertTrue(before.showChoices)
        assertFalse(before.canRegister)
        val chosen = metaSetupChoicePresentation(NOT_REGISTERED, MetaSetupChoice.EMAIL_INVITE, MetaRegistrationPath.UNKNOWN, true)
        assertTrue(chosen.canRegister)
        val registered = metaSetupChoicePresentation(REGISTERED, MetaSetupChoice.EMAIL_INVITE, MetaRegistrationPath.DEVELOPER_MODE, true)
        assertFalse(registered.showChoices)
        assertFalse(registered.canRegister)
        assertTrue(registered.showInvitedOnDeveloperPath)
        // Without an app id (iOS today) the invite cannot work, so only Developer Mode is offered.
        val noAppId = metaSetupChoicePresentation(NOT_REGISTERED, MetaSetupChoice.EMAIL_INVITE, MetaRegistrationPath.DEVELOPER_MODE, false)
        assertFalse(noAppId.emailInviteAvailable)
        assertEquals(MetaSetupChoice.NONE, noAppId.choice)
        assertTrue(noAppId.showDeveloperModeLost)
    }
}

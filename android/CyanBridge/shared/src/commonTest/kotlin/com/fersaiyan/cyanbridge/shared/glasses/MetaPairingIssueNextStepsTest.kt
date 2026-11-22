package com.fersaiyan.cyanbridge.shared.glasses

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MetaPairingIssueNextStepsTest {
    private fun issue(
        installed: Boolean = true,
        error: String? = "Something unexpected",
        accessRequired: Boolean = false,
        invite: Boolean = false,
        registered: Boolean = false,
    ) = resolveMetaPairingIssue(installed, error, null, accessRequired, invite, registered)!!

    @Test
    fun missingMetaAiInInviteModeAlsoOffersTheInviteButNoLogs() {
        val i = issue(installed = false, error = "Meta AI app is not installed", invite = true)
        assertEquals(MetaPairingIssueAction.INSTALL_META_AI, i.action)
        assertEquals(listOf(MetaPairingIssueAction.REQUEST_ACCESS), i.secondaryActions)
        assertFalse(i.offerLogs)
    }

    @Test
    fun missingMetaAiOnDeveloperModeOffersOnlyTheInstall() {
        val i = issue(installed = false, error = "Meta AI app is not installed")
        assertEquals(emptyList(), i.secondaryActions)
        assertFalse(i.offerLogs)
    }

    @Test
    fun accessRequiredWithoutMetaAiAlsoOffersTheInstall() {
        val i = issue(installed = false, accessRequired = true)
        assertEquals(MetaPairingIssueAction.REQUEST_ACCESS, i.action)
        assertEquals(listOf(MetaPairingIssueAction.INSTALL_META_AI), i.secondaryActions)
        assertTrue(i.offerLogs)
    }

    @Test
    fun noGlassesPairedOffersRetryAndTheInviteWhileUnregistered() {
        val i = issue(error = "No Meta glasses are paired with this phone", invite = true)
        assertEquals(MetaPairingIssueAction.OPEN_META_AI, i.action)
        assertEquals(listOf(MetaPairingIssueAction.OPEN_PAIRING, MetaPairingIssueAction.REQUEST_ACCESS), i.secondaryActions)
        assertTrue(i.offerLogs)
    }

    @Test
    fun registeredUsersAreNotSentToTheInviteForm() {
        val i = issue(invite = true, registered = true)
        assertEquals(MetaPairingIssueAction.OPEN_PAIRING, i.action)
        assertEquals(listOf(MetaPairingIssueAction.OPEN_META_AI), i.secondaryActions)
    }

    @Test
    fun developerModeFailuresDoNotOfferTheInvite() {
        val i = issue()
        assertEquals(listOf(MetaPairingIssueAction.OPEN_META_AI), i.secondaryActions)
        assertTrue(i.offerLogs)
    }

    @Test
    fun theMainActionIsNeverRepeated() {
        val i = issue(error = "release channel not enabled", invite = true)
        assertEquals(MetaPairingIssueAction.REQUEST_ACCESS, i.action)
        assertFalse(MetaPairingIssueAction.REQUEST_ACCESS in i.secondaryActions)
    }

    @Test
    fun inviteModeFromChoicePathOrMetaAi() {
        assertTrue(metaInviteMode(MetaSetupChoice.EMAIL_INVITE, MetaRegistrationPath.UNKNOWN, false))
        assertTrue(metaInviteMode(MetaSetupChoice.NONE, MetaRegistrationPath.RELEASE_CHANNEL, false))
        assertTrue(metaInviteMode(MetaSetupChoice.DEVELOPER_MODE, MetaRegistrationPath.UNKNOWN, true))
        assertFalse(metaInviteMode(MetaSetupChoice.DEVELOPER_MODE, MetaRegistrationPath.DEVELOPER_MODE, false))
    }
}

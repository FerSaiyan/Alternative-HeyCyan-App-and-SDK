package com.fersaiyan.cyanbridge.localagent.actions

import com.fersaiyan.cyanbridge.localagent.LocalAgentAction
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalAgentApprovalCoordinatorTest {
    @Test
    fun yesRepliesApprove() {
        listOf("yes", "YES", "yes!", "go ahead", "confirm", "send").forEach { reply ->
            assertEquals(
                "Expected approval for '$reply'",
                LocalAgentApprovalCoordinator.ReplyKind.APPROVE,
                LocalAgentApprovalCoordinator.classifyReply(reply),
            )
        }
    }

    @Test
    fun noRepliesReject() {
        listOf("no", "NO", "nope", "cancel", "do not send", "don't send").forEach { reply ->
            assertEquals(
                "Expected rejection for '$reply'",
                LocalAgentApprovalCoordinator.ReplyKind.REJECT,
                LocalAgentApprovalCoordinator.classifyReply(reply),
            )
        }
    }

    @Test
    fun ambiguousReplyDoesNotAuthorizeAnything() {
        listOf("maybe", "later", "what is this?", "").forEach { reply ->
            assertEquals(
                "Expected unknown approval state for '$reply'",
                LocalAgentApprovalCoordinator.ReplyKind.UNKNOWN,
                LocalAgentApprovalCoordinator.classifyReply(reply),
            )
        }
    }

    @Test
    fun sendEmailRemainsHighRisk() {
        val action = LocalAgentAction.SendEmail(
            to = "self@example.com",
            subject = "HIL",
            body = "test",
        )
        assertEquals(
            LocalAgentActionManager.Risk.HIGH,
            LocalAgentActionManager.classifyRisk(action),
        )
    }
}

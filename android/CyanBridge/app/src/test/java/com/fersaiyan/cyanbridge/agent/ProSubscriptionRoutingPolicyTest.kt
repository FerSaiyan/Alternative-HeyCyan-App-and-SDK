package com.fersaiyan.cyanbridge.agent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.fersaiyan.cyanbridge.ai.router.AiProviderPrefs
import com.fersaiyan.cyanbridge.ai.router.AiProviderType
import com.fersaiyan.cyanbridge.shared.settings.AgentProviderType
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProSubscriptionRoutingPolicyTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun activationSwitchesFromLocalModelsToPro() {
        LocalAgentPrefs.setProviderType(context, AgentProviderType.LOCAL_AGENT)
        AiProviderPrefs.setProvider(context, AiProviderType.LOCAL_MODELS)

        val action = ProSubscriptionRoutingPolicy.actionAfterActivationTransition(context, wasActive = false)

        assertEquals(ProSubscriptionRoutingPolicy.Action.SWITCHED_TO_PRO, action)
        assertEquals(AgentProviderType.PRO_SUBSCRIPTION, LocalAgentPrefs.getProviderType(context))
        assertEquals(AiProviderType.CLI_RELAY, AiProviderPrefs.getProvider(context))
    }

    @Test
    fun activationSwitchesFromTaskerToPro() {
        LocalAgentPrefs.setProviderType(context, AgentProviderType.TASKER)
        AiProviderPrefs.setProvider(context, AiProviderType.MOCK)

        ProSubscriptionRoutingPolicy.actionAfterActivationTransition(context, wasActive = false)

        assertEquals(AgentProviderType.PRO_SUBSCRIPTION, LocalAgentPrefs.getProviderType(context))
        assertEquals(AiProviderType.CLI_RELAY, AiProviderPrefs.getProvider(context))
    }

    @Test
    fun routineVerificationLeavesAUsersLaterProviderChoiceAlone() {
        LocalAgentPrefs.setProviderType(context, AgentProviderType.LOCAL_AGENT)
        AiProviderPrefs.setProvider(context, AiProviderType.LOCAL_MODELS)

        val action = ProSubscriptionRoutingPolicy.actionAfterActivationTransition(context, wasActive = true)

        assertEquals(ProSubscriptionRoutingPolicy.Action.NO_CHANGE, action)
        assertEquals(AgentProviderType.LOCAL_AGENT, LocalAgentPrefs.getProviderType(context))
        assertEquals(AiProviderType.LOCAL_MODELS, AiProviderPrefs.getProvider(context))
    }

    @Test
    fun explicitActivationStillSwitchesAnAlreadyActiveTrialToPro() {
        LocalAgentPrefs.setProviderType(context, AgentProviderType.LOCAL_AGENT)
        AiProviderPrefs.setProvider(context, AiProviderType.LOCAL_MODELS)

        ProSubscriptionRoutingPolicy.applyAfterActivation(context)

        assertEquals(AgentProviderType.PRO_SUBSCRIPTION, LocalAgentPrefs.getProviderType(context))
        assertEquals(AiProviderType.CLI_RELAY, AiProviderPrefs.getProvider(context))
    }
}

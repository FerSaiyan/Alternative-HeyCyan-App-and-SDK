package com.fersaiyan.cyanbridge.lensmoo

import android.content.Context
import com.fersaiyan.cyanbridge.agent.LocalAgentPrefs
import com.fersaiyan.cyanbridge.localmodels.remote.RemoteOpenAiPrefs
import com.fersaiyan.cyanbridge.shared.settings.AgentProviderType
import java.net.URI

object LensMooAiGate {
    fun ready(context: Context): Boolean = runCatching {
        val base = RemoteOpenAiPrefs.getBaseUrl(context)
        val uri = URI(base)
        LocalAgentPrefs.getProviderType(context) == AgentProviderType.LOCAL_AGENT &&
            RemoteOpenAiPrefs.isActive(context) &&
            uri.host != null && uri.scheme in setOf("https", "http") &&
            RemoteOpenAiPrefs.isCredentialTransportAllowed(base) &&
            (!uri.host.equals("api.aitunnel.ru", true) || RemoteOpenAiPrefs.getApiKey(context).isNotBlank())
    }.getOrDefault(false)
}

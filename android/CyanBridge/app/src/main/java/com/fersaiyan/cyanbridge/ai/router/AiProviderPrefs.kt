package com.fersaiyan.cyanbridge.ai.router

import android.content.Context
import com.fersaiyan.cyanbridge.shared.ai.CliRelayBackend
import com.fersaiyan.cyanbridge.shared.ai.DEFAULT_RELAY_URL
import com.fersaiyan.cyanbridge.shared.prefs.AssistantPrefs.Provider
import com.fersaiyan.cyanbridge.shared.prefs.get
import com.fersaiyan.cyanbridge.shared.prefs.set

typealias AiProviderType = com.fersaiyan.cyanbridge.shared.ai.AiProviderType

object AiProviderPrefs {
    private const val LEGACY_PUBLIC_RELAY_URL = "https://carelens-wine.vercel.app"

    fun getProvider(context: Context): AiProviderType = AiProviderType.fromWire(Provider.provider[context])

    fun setProvider(context: Context, provider: AiProviderType) {
        Provider.provider[context] = provider.wire
    }

    fun getRelayBaseUrl(context: Context): String =
        Provider.relayBaseUrl[context].trim().let { current ->
            when {
                current.isBlank() -> DEFAULT_RELAY_URL
                current == LEGACY_PUBLIC_RELAY_URL -> DEFAULT_RELAY_URL
                else -> current
            }
        }

    fun getRelayBackend(context: Context): CliRelayBackend = CliRelayBackend.fromWire(Provider.relayBackend[context])
}

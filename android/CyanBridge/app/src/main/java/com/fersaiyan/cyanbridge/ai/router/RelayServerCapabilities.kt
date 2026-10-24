package com.fersaiyan.cyanbridge.ai.router

import android.content.Context
import com.fersaiyan.cyanbridge.shared.ai.RelayCapabilitiesCache
import com.fersaiyan.cyanbridge.shared.ai.RelayServerCapabilities
import kotlinx.coroutines.runBlocking

/** The app-wide relay capabilities cache (GET /capabilities, one minute per relay URL). */
object RelayServerCapabilitiesClient {
    internal val cache = RelayCapabilitiesCache(UrlConnectionHttpTransport)

    /** Blocking, as before (the HttpURLConnection transport never suspends). */
    fun get(context: Context, forceRefresh: Boolean = false): Result<RelayServerCapabilities> =
        runBlocking { cache.get(AiProviderPrefs.getRelayBaseUrl(context), forceRefresh) }
}

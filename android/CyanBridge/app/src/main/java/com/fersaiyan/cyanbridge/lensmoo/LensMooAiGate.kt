package com.fersaiyan.cyanbridge.lensmoo

import android.content.Context
import com.fersaiyan.cyanbridge.localmodels.remote.RemoteOpenAiPrefs
import java.net.URI
import com.fersaiyan.cyanbridge.localmodels.storage.LocalModelStorageRepository
import com.fersaiyan.cyanbridge.localmodels.settings.LocalModelSettingsRepository
import com.fersaiyan.cyanbridge.localmodels.settings.LocalModelRuntime

object LensMooAiGate {
    fun ready(context: Context): Boolean = runCatching {
        if (!RemoteOpenAiPrefs.isEnabled(context)) {
            val model = LocalModelStorageRepository.resolveSelectedModel(context)
            return@runCatching lensMooAiReady(false, false,
                model != null && java.io.File(model.absolutePath).isFile &&
                    LocalModelSettingsRepository.getForModel(context, model.id).modelRuntime == LocalModelRuntime.LITERT &&
                    (model.displayName + model.fileName).contains("gemma", ignoreCase = true))
        }
        val base = RemoteOpenAiPrefs.getBaseUrl(context)
        val uri = URI(base)
        lensMooAiReady(true, RemoteOpenAiPrefs.isActive(context) &&
            uri.host != null && uri.scheme in setOf("https", "http") &&
            RemoteOpenAiPrefs.isCredentialTransportAllowed(base) &&
            (!uri.host.equals("api.aitunnel.ru", true) || RemoteOpenAiPrefs.getApiKey(context).isNotBlank()), false)
    }.getOrDefault(false)
}

/** An explicitly enabled cloud endpoint must never fall back to an on-device model. */
internal fun lensMooAiReady(remoteEnabled: Boolean, remoteReady: Boolean, localVisionReady: Boolean): Boolean =
    if (remoteEnabled) remoteReady else localVisionReady

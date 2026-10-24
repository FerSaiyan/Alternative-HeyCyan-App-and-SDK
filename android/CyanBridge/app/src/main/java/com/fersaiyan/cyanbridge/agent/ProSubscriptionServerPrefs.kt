package com.fersaiyan.cyanbridge.agent

import android.content.Context
import android.util.Patterns
import com.fersaiyan.cyanbridge.shared.platform.PlatformPreferences
import com.fersaiyan.cyanbridge.shared.prefs.ProPrefs.LegacyCheckout
import com.fersaiyan.cyanbridge.shared.prefs.ProPrefs.Server
import com.fersaiyan.cyanbridge.shared.prefs.ProPrefs.ServerSecrets
import com.fersaiyan.cyanbridge.shared.prefs.clear
import com.fersaiyan.cyanbridge.shared.prefs.edit
import com.fersaiyan.cyanbridge.shared.prefs.get
import com.fersaiyan.cyanbridge.shared.prefs.remove
import com.fersaiyan.cyanbridge.shared.prefs.set
import com.fersaiyan.cyanbridge.shared.prefs.store
import java.util.Locale
import java.util.UUID

object ProSubscriptionServerPrefs {
    private const val WEB_CALLBACK_RESULT_TTL_MS = 15L * 60L * 1000L

    enum class WebCallbackPurpose {
        SUBSCRIPTION,
        DONATION,
    }

    fun getVerifyUrl(context: Context): String = Server.verifyUrl[context].trim()

    fun getApiToken(context: Context): String {
        clearLegacyPlaintextCredentials(context)
        val encrypted = ServerSecrets.apiToken[context].trim()
        if (encrypted.isNotBlank()) return encrypted

        // Migrate the legacy plaintext token once, then remove the old copy.
        val legacy = Server.legacyApiToken[context].trim()
        if (legacy.isBlank()) return ""

        val saved = ServerSecrets.edit(context, commit = true) { this[ServerSecrets.apiToken] = legacy }
        Server.edit(context, commit = true) { Server.legacyApiToken.remove(this) }
        return if (saved) legacy else ""
    }

    fun setApiToken(context: Context, token: String?) {
        clearLegacyPlaintextCredentials(context)
        val value = token?.trim().orEmpty()
        val saved = ServerSecrets.edit(context, commit = true) {
            if (value.isBlank()) ServerSecrets.apiToken.remove(this) else this[ServerSecrets.apiToken] = value
        }
        check(saved) { "Unable to securely store the server account token" }
        Server.edit(context, commit = true) { Server.legacyApiToken.remove(this) }
    }

    fun createWebCallbackResult(
        context: Context,
        purpose: WebCallbackPurpose,
        nowMs: Long = System.currentTimeMillis(),
    ): String {
        clearLegacyPlaintextCredentials(context)
        val result = UUID.randomUUID().toString()
        val saved = ServerSecrets.edit(context, commit = true) {
            this[ServerSecrets.webCallbackResult] = result
            this[ServerSecrets.webCallbackPurpose] = purpose.name
            this[ServerSecrets.webCallbackExpiresAtMs] = nowMs + WEB_CALLBACK_RESULT_TTL_MS
        }
        check(saved) { "Unable to securely prepare the checkout callback" }
        return result
    }

    fun consumeWebCallbackResult(
        context: Context,
        result: String?,
        nowMs: Long = System.currentTimeMillis(),
    ): WebCallbackPurpose? {
        clearLegacyPlaintextCredentials(context)
        val secrets = ServerSecrets.store(context)
        val expectedResult = secrets[ServerSecrets.webCallbackResult]
        val expiresAtMs = secrets[ServerSecrets.webCallbackExpiresAtMs]
        if (expectedResult.isBlank() || expiresAtMs <= nowMs) {
            clearWebCallback(secrets)
            return null
        }
        if (result.isNullOrBlank() || result != expectedResult) return null

        val purpose = secrets[ServerSecrets.webCallbackPurpose]
            .let { value -> WebCallbackPurpose.entries.firstOrNull { it.name == value } }
        clearWebCallback(secrets)
        return purpose
    }

    private fun clearWebCallback(secrets: PlatformPreferences) {
        secrets.edit {
            ServerSecrets.webCallbackResult.remove(this)
            ServerSecrets.webCallbackPurpose.remove(this)
            ServerSecrets.webCallbackExpiresAtMs.remove(this)
        }
    }

    fun getAccountEmail(context: Context): String = sanitizeAccountEmail(Server.accountEmail[context])

    fun setAccountEmail(context: Context, email: String?) {
        val value = sanitizeAccountEmail(email)
        val verified = getVerifiedAccountEmail(context)
        Server.edit(context) {
            if (value.isBlank()) Server.accountEmail.remove(this) else this[Server.accountEmail] = value
            if (value != verified) Server.verifiedAccountEmail.remove(this)
        }
    }

    fun getVerifiedAccountEmail(context: Context): String =
        sanitizeAccountEmail(Server.verifiedAccountEmail[context])

    fun isAccountEmailVerified(context: Context, email: String?): Boolean =
        normalizeAccountEmail(email) == getVerifiedAccountEmail(context)

    fun setVerifiedAccountEmail(context: Context, email: String?) {
        val value = sanitizeAccountEmail(email)
        Server.edit(context) {
            listOf(Server.verifiedAccountEmail, Server.accountEmail).forEach { pref ->
                if (value.isBlank()) pref.remove(this) else this[pref] = value
            }
        }
    }

    fun normalizeAccountEmail(email: String?): String =
        email?.trim()?.lowercase(Locale.US).orEmpty()

    fun isUsableAccountEmail(email: String?): Boolean {
        val normalized = normalizeAccountEmail(email)
        if (normalized.isBlank()) return false
        if (normalized.startsWith("relay_")) return false
        if (normalized.endsWith("@cyanbridge.placeholder")) return false
        return Patterns.EMAIL_ADDRESS.matcher(normalized).matches()
    }

    private fun sanitizeAccountEmail(email: String?): String {
        val normalized = normalizeAccountEmail(email)
        return if (isUsableAccountEmail(normalized)) normalized else ""
    }

    private fun clearLegacyPlaintextCredentials(context: Context) {
        if (Server.legacyCredentialsCleared[context]) return

        LegacyCheckout.clear(context)
        PendingDonationPrefs.removeLegacyCredentials(context)
        Server.legacyCredentialsCleared[context] = true
    }
}

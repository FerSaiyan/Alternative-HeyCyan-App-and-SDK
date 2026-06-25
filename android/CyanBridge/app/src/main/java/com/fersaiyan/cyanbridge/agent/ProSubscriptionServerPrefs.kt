package com.fersaiyan.cyanbridge.agent

import android.content.Context
import android.util.Patterns
import java.util.Locale

object ProSubscriptionServerPrefs {
    private const val PREFS_NAME = "pro_subscription_server_prefs"
    private const val KEY_VERIFY_URL = "verify_url"
    private const val KEY_API_TOKEN = "api_token"
    private const val KEY_ACCOUNT_EMAIL = "account_email"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getVerifyUrl(context: Context): String =
        prefs(context).getString(KEY_VERIFY_URL, "").orEmpty().trim()

    fun setVerifyUrl(context: Context, url: String?) {
        val value = url?.trim().orEmpty()
        prefs(context).edit().apply {
            if (value.isBlank()) remove(KEY_VERIFY_URL) else putString(KEY_VERIFY_URL, value)
        }.apply()
    }

    fun getApiToken(context: Context): String =
        prefs(context).getString(KEY_API_TOKEN, "").orEmpty().trim()

    fun setApiToken(context: Context, token: String?) {
        val value = token?.trim().orEmpty()
        prefs(context).edit().apply {
            if (value.isBlank()) remove(KEY_API_TOKEN) else putString(KEY_API_TOKEN, value)
        }.apply()
    }

    fun getAccountEmail(context: Context): String =
        sanitizeAccountEmail(prefs(context).getString(KEY_ACCOUNT_EMAIL, ""))

    fun setAccountEmail(context: Context, email: String?) {
        val value = sanitizeAccountEmail(email)
        prefs(context).edit().apply {
            if (value.isBlank()) remove(KEY_ACCOUNT_EMAIL) else putString(KEY_ACCOUNT_EMAIL, value)
        }.apply()
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
}

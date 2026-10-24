package com.fersaiyan.cyanbridge.ai.router

import android.content.Context
import com.fersaiyan.cyanbridge.R
import com.fersaiyan.cyanbridge.shared.ai.RelayErrorClassifier
import com.fersaiyan.cyanbridge.shared.ai.RelayErrorClassifier.Kind

/** Android text for [RelayErrorClassifier]'s verdict. */
object RelayErrorLocalizer {
    fun localizedMessage(context: Context, throwable: Throwable): String {
        val error = RelayErrorClassifier.classify(throwable)
        return when (error.kind) {
            Kind.QUOTA_EXHAUSTED -> context.getString(R.string.relay_quota_exhausted, error.argument)
            Kind.SUBSCRIPTION_REQUIRED -> context.getString(R.string.relay_subscription_required)
            Kind.AUTHENTICATION_REQUIRED -> context.getString(R.string.relay_authentication_required)
            Kind.GENERIC -> context.getString(R.string.relay_error_generic, error.argument)
        }
    }

    fun isQuotaError(throwable: Throwable): Boolean = RelayErrorClassifier.isQuotaError(throwable)
}

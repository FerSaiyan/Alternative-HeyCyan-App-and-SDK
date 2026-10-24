package com.fersaiyan.cyanbridge.agent

import android.content.Context
import com.fersaiyan.cyanbridge.shared.platform.PlatformPreferences
import com.fersaiyan.cyanbridge.shared.prefs.ProPrefs.Donation
import com.fersaiyan.cyanbridge.shared.prefs.ProPrefs.DonationSecrets
import com.fersaiyan.cyanbridge.shared.prefs.clear
import com.fersaiyan.cyanbridge.shared.prefs.edit
import com.fersaiyan.cyanbridge.shared.prefs.get
import com.fersaiyan.cyanbridge.shared.prefs.set
import com.fersaiyan.cyanbridge.shared.prefs.store

object PendingDonationPrefs {
    data class PendingDonation(
        val invoiceUrl: String,
        val statusUrl: String,
        val amount: String,
    )

    fun save(context: Context, pending: PendingDonation) {
        val saved = DonationSecrets.edit(context, commit = true) {
            this[DonationSecrets.invoiceUrl] = pending.invoiceUrl
            this[DonationSecrets.statusUrl] = pending.statusUrl
            this[DonationSecrets.amount] = pending.amount
        }
        check(saved) { "Unable to securely save donation checkout state" }
        // Remove the plaintext state and credentials written by the legacy flow.
        removePlaintextState(context)
    }

    fun get(context: Context): PendingDonation? {
        val encrypted = DonationSecrets.store(context)
        val securePending = PendingDonation(
            invoiceUrl = encrypted[DonationSecrets.invoiceUrl].trim(),
            statusUrl = encrypted[DonationSecrets.statusUrl].trim(),
            amount = encrypted[DonationSecrets.amount].trim(),
        )
        if (securePending.invoiceUrl.isNotBlank() && securePending.statusUrl.isNotBlank()) {
            removePlaintextState(context)
            return securePending
        }

        // Migrate a pending legacy donation once, removing its old credential copies.
        removeLegacyCredentials(context)
        val plain = Donation.store(context)
        val invoiceUrl = plain[Donation.invoiceUrl].trim()
        val statusUrl = plain[Donation.statusUrl].trim()
        val amount = plain[Donation.amount].trim()
        if (invoiceUrl.isBlank() || statusUrl.isBlank()) return null
        return PendingDonation(
            invoiceUrl = invoiceUrl,
            statusUrl = statusUrl,
            amount = amount,
        ).also { save(context, it) }
    }

    fun clear(context: Context) {
        Donation.clear(context)
        DonationSecrets.clear(context)
    }

    fun setAwaitingReturn(context: Context, awaiting: Boolean) {
        Donation.awaitingReturn[context] = awaiting
    }

    fun isAwaitingReturn(context: Context): Boolean = Donation.awaitingReturn[context]

    /** Removes the plaintext credentials an old checkout flow kept in this file. */
    fun removeLegacyCredentials(context: Context) {
        Donation.edit(context) { removeLegacyCredentials() }
    }

    private fun removePlaintextState(context: Context) {
        Donation.edit(context) {
            Donation.invoiceUrl.remove(this)
            Donation.statusUrl.remove(this)
            Donation.amount.remove(this)
            removeLegacyCredentials()
        }
    }

    private fun PlatformPreferences.removeLegacyCredentials() {
        Donation.legacyApiToken.remove(this)
        Donation.legacyEmail.remove(this)
        Donation.legacySubscriptionId.remove(this)
    }
}

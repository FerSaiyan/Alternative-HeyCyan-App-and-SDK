package com.fersaiyan.cyanbridge.ui

import android.app.Activity
import androidx.activity.ComponentDialog
import androidx.lifecycle.findViewTreeLifecycleOwner
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
class VersionUpdateCheckerTest {
    @After
    fun dismissDialog() {
        ShadowDialog.getLatestDialog()?.dismiss()
    }

    @Test
    fun updateDialogProvidesLifecycleOwnerForCompose() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()

        VersionUpdateChecker.showUpdateDialog(
            context = activity,
            latestVersion = "999.0.0",
            playStoreUrl = "https://play.google.com/store/apps/details?id=com.fersaiyan.cyanbridge",
        )

        val dialog = ShadowDialog.getLatestDialog()
        assertTrue(dialog is ComponentDialog)
        assertSame(dialog, dialog.window?.decorView?.findViewTreeLifecycleOwner())
    }

    @Test
    fun onlyAStrictlyNewerReleaseIsOffered() {
        assertTrue(VersionUpdateChecker.shouldOfferUpdate("2.5.1", "2.5.0", null))
        assertTrue(VersionUpdateChecker.shouldOfferUpdate("2.10.0", "2.9.3", null))
        // 2.5.0 installed while the server still reports 2.4.5 (seen on the emulator, 2026-11-22).
        assertFalse(VersionUpdateChecker.shouldOfferUpdate("2.4.5", "2.5.0", null))
        assertFalse(VersionUpdateChecker.shouldOfferUpdate("2.5.0", "2.5.0", null))
        assertFalse(VersionUpdateChecker.shouldOfferUpdate("2.5.0", "2.5.0-debug", null))
        assertTrue(VersionUpdateChecker.shouldOfferUpdate("2.5.1", "2.5.0-debug", null))
    }

    @Test
    fun postponedOrUnreadableVersionsAreNotOffered() {
        assertFalse(VersionUpdateChecker.shouldOfferUpdate("2.5.1", "2.5.0", "2.5.1"))
        assertFalse(VersionUpdateChecker.shouldOfferUpdate("", "2.5.0", null))
        assertFalse(VersionUpdateChecker.shouldOfferUpdate("2.5.1", "", null))
        assertFalse(VersionUpdateChecker.shouldOfferUpdate("latest", "2.5.0", null))
    }
}

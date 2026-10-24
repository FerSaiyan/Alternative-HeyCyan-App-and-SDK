package com.fersaiyan.cyanbridge.ui

import android.app.Activity
import androidx.activity.ComponentDialog
import androidx.lifecycle.findViewTreeLifecycleOwner
import org.junit.After
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
}

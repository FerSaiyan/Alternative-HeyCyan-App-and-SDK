package com.fersaiyan.cyanbridge.devices.moyoung

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MoyoungW620ManagerTest {
    @Test
    fun `core sdk adapter initializes without optional ota components`() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        assertNotNull(MoyoungW620Manager.getInstance(context))
    }
}

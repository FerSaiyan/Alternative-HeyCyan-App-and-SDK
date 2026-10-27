package com.fersaiyan.cyanbridge.devices.meizumyvu

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MeizuMyvuManagerTest {
    @Test
    fun `the manager can be created`() {
        // Its display adapter reads the manager's state while it is built.
        val manager = MeizuMyvuManager.getInstance(ApplicationProvider.getApplicationContext())

        assertNull(manager.state.value.lastError)
    }
}

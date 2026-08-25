package com.fersaiyan.cyanbridge.localagent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.fersaiyan.cyanbridge.agent.LocalAgentPrefs as AutomationPrefs
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalAgentSafetyPolicyTest {
    @Test
    fun `legacy cyanbridge blacklist is no longer a second blocking authority`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        AutomationPrefs.setCaptureBlacklistPackages(context, setOf("com.example.mobilebanking"))

        assertNull(
            LocalAgentSafetyPolicy.blockedReason(context, "com.example.mobilebanking"),
        )
    }
}

package com.fersaiyan.cyanbridge.diagnostics

import android.content.Context
import android.provider.Settings

/**
 * Firebase Test Lab devices, which also run Play's pre-launch report robots. Those robots tap every
 * button, so a release build there sends support reports and product analytics with no user behind
 * them (e.g. log_mwqrass7_5bb17d7f: a Meta "Send diagnostics" from a virtual "OnePlus8Pro").
 */
object TestLabEnvironment {
    private const val TEST_LAB_SETTING = "firebase.test.lab"

    fun isTestLab(context: Context): Boolean =
        runCatching { Settings.System.getString(context.contentResolver, TEST_LAB_SETTING) == "true" }
            .getOrDefault(false)
}

package com.fersaiyan.cyanbridge.hil

import android.content.Context
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue

object HilTestSupport {
    const val TASKER_PACKAGE = "net.dinglisch.android.taskerm"
    const val AUTOINPUT_PACKAGE = "com.joaomgcd.autoinput"

    private val args: Bundle
        get() = InstrumentationRegistry.getArguments()

    val mode: String
        get() = args.getString("hil_mode", "emulator") ?: "emulator"

    val hardwareRequired: Boolean
        get() = mode.equals("hardware", ignoreCase = true)

    val glassesRequired: Boolean
        get() = args.getString("hil_glasses", "false").toBoolean()

    val expectVisualFact: Boolean
        get() = args.getString("hil_expect_visual_fact", "false").toBoolean()

    fun packageInstalled(context: Context, packageName: String): Boolean = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(packageName, 0)
    }.isSuccess

    fun requireOrSkip(condition: Boolean, message: String) {
        if (hardwareRequired) {
            assertTrue(message, condition)
        } else {
            assumeTrue(message, condition)
        }
    }

    fun requireTaskerStack(context: Context) {
        requireOrSkip(packageInstalled(context, TASKER_PACKAGE), "Tasker is not installed on the HIL device")
        requireOrSkip(packageInstalled(context, AUTOINPUT_PACKAGE), "AutoInput is not installed on the HIL device")
    }
}

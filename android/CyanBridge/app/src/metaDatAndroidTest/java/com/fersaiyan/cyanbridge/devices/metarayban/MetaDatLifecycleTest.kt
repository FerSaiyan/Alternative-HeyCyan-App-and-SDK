package com.fersaiyan.cyanbridge.devices.metarayban

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.meta.wearable.dat.camera.Stream
import com.meta.wearable.dat.camera.types.StreamState as DatStreamState
import com.meta.wearable.dat.display.Display
import com.meta.wearable.dat.display.types.DisplayState
import java.lang.reflect.Proxy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the real manager observers with controlled DAT StateFlows, without glasses. */
@RunWith(AndroidJUnit4::class)
class MetaDatLifecycleTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val manager get() = MetaRaybanManager.getInstance(ApplicationProvider.getApplicationContext())

    @After
    fun tearDown() {
        instrumentation.runOnMainSync { manager.destroy() }
    }

    @Test
    fun streamIgnoresInitialStoppedButClosesAfterStreaming() {
        val states = MutableStateFlow(DatStreamState.STOPPED)
        var stopCalls = 0
        val stream = Proxy.newProxyInstance(Stream::class.java.classLoader, arrayOf(Stream::class.java)) { _, method, _ ->
            when (method.name) {
                "getState" -> states
                "getVideoStream", "getErrorStream" -> emptyFlow<Any>()
                "stop" -> { stopCalls++; null }
                else -> null
            }
        } as Stream
        instrumentation.runOnMainSync {
            manager.setField("stream", stream)
            manager.observe("observeStream", Stream::class.java, stream, { _: String -> })
            assertEquals(0, stopCalls)
            states.value = DatStreamState.STARTING
            states.value = DatStreamState.STREAMING
            assertTrue(manager.isStreaming.value)
            states.value = DatStreamState.STOPPED
            assertEquals(1, stopCalls)
            assertFalse(manager.isStreaming.value)
        }
    }

    @Test
    fun displayIgnoresInitialStoppedButClosesAfterStarting() {
        val states = MutableStateFlow(DisplayState.STOPPED)
        var stopCalls = 0
        val display = Proxy.newProxyInstance(Display::class.java.classLoader, arrayOf(Display::class.java)) { _, method, _ ->
            when (method.name) {
                "getState" -> states
                "stop" -> { stopCalls++; null }
                else -> null
            }
        } as Display
        instrumentation.runOnMainSync {
            manager.setField("display", display)
            manager.observe("observeDisplay", Display::class.java, display)
            assertEquals(0, stopCalls)
            states.value = DisplayState.STARTING
            states.value = DisplayState.STARTED
            assertTrue(manager.isDisplayActive.value)
            states.value = DisplayState.STOPPED
            assertEquals(1, stopCalls)
            assertFalse(manager.isDisplayActive.value)
        }
    }

    private fun MetaRaybanManager.setField(name: String, value: Any) {
        javaClass.getDeclaredField(name).apply { isAccessible = true }.set(this, value)
    }

    private fun MetaRaybanManager.observe(name: String, type: Class<*>, vararg args: Any) {
        val types = if (name == "observeStream") arrayOf(type, Function1::class.java) else arrayOf(type)
        javaClass.getDeclaredMethod(name, *types).apply { isAccessible = true }.invoke(this, *args)
    }
}

package com.fersaiyan.cyanbridge.lensmoo

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit opt-in radio probe: stops stale device recording, never takes a photo. */
@RunWith(AndroidJUnit4::class)
class LensMooDeviceControlTest {
    @Test fun longAnswerUsesClientAudioPlayer() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("lensmooAudio")=="true")
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val finished=java.util.concurrent.CountDownLatch(1)
        val failure=java.util.concurrent.atomic.AtomicReference<String?>(null)
        var voice:LensMooVoiceController?=null
        instrumentation.runOnMainSync {
            voice=LensMooVoiceController(context,{}, {},onIdle={finished.countDown()},onFailure={failure.set(it)})
            voice!!.speak(("Это проверка длинного голосового ответа через динамики очков. " +
                "Звук должен оставаться в очках на протяжении всей проверки. " +
                "Камера сейчас не используется, и запрос к серверу не отправляется. ").repeat(4))
        }
        try {
            assertTrue("Playback did not finish",finished.await(100,java.util.concurrent.TimeUnit.SECONDS))
            assertTrue("Audio playback failed",failure.get()==null)
        } finally { instrumentation.runOnMainSync { voice?.close() } }
    }
    @Test fun stopStaleAssistantRecording() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("lensmooRadio")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        context.stopService(Intent(context,LensMooHandsFreeService::class.java))
        val prefs=context.getSharedPreferences("lensmoo_private",0)
        val address=prefs.getString("address","").orEmpty()
        val owner=prefs.getString("owner","").orEmpty()
        check(address.isNotBlank() && owner.isNotBlank()) { "Device configuration missing" }
        LensMooSession(context).use { session ->
            session.connect(address,owner)
            session.stopAssistantRecording()
            assertTrue(session.checkConnection() in 0..100)
        }
    }
}

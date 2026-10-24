package com.fersaiyan.cyanbridge.studiobridge

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.URI

@RunWith(RobolectricTestRunner::class)
class StudioBridgeTest {

    @Test
    fun `websocket URL strips OpenAI endpoint paths`() {
        val cases = mapOf(
            "http://host.example:8000/v1" to "ws://host.example:8000/api/mobile/ws",
            "https://host.example/v1/" to "wss://host.example/api/mobile/ws",
            "http://host.example/v1/chat/completions" to "ws://host.example/api/mobile/ws",
            "https://host.example/proxy/v1/chat/completions" to "wss://host.example/proxy/api/mobile/ws",
        )

        cases.forEach { (base, expected) ->
            val actual = URI(StudioBridgeClient.buildWsUrl(base))
            val withoutQuery = URI(actual.scheme, actual.authority, actual.path, null, null).toString()
            assertEquals(expected, withoutQuery)
        }
    }

    @Test
    fun `classification parser handles structured and wrapped responses`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val handler = StudioApprovalHandler(context)

        assertEquals("allow", handler.parseClassification("{\"decision\":\"allow\"}"))
        assertEquals("deny", handler.parseClassification("```json\n{\"decision\":\"deny\"}\n```"))
        assertEquals(
            "allow",
            handler.parseClassification("<think>reasoning</think><answer>{\"decision\":\"allow\"}</answer>"),
        )
        assertEquals("clarify", handler.parseClassification("Maybe later, I am not sure."))
    }
}

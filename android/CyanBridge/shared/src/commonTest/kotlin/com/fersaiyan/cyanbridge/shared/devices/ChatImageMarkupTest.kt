package com.fersaiyan.cyanbridge.shared.devices

import com.fersaiyan.cyanbridge.shared.chat.ChatImageMarkup
import com.fersaiyan.cyanbridge.shared.ui.chat.hasInlineMath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatImageMarkupTest {
    @Test
    fun imageRoundTrips() {
        val content = ChatImageMarkup.attach("chat-image-42.jpg", "📷 O que é isso?")
        assertEquals("chat-image-42.jpg", ChatImageMarkup.imagePath(content))
        assertEquals("📷 O que é isso?", ChatImageMarkup.stripImage(content))
    }

    @Test
    fun plainMessagesAreUntouched() {
        val content = "Veja ![logo](https://example.com/a.png) aqui"
        assertNull(ChatImageMarkup.imagePath(content))
        assertEquals(content, ChatImageMarkup.stripImage(content))
    }

    @Test
    fun inlineMathIgnoresCurrency() {
        assertTrue(hasInlineMath("A área é \$\\pi r^2\$ metros"))
        assertTrue(hasInlineMath("Use \\(x = \\frac{-b}{2a}\\) aqui"))
        assertFalse(hasInlineMath("Custa R\$ 5,00 e R\$ 10,00"))
    }
}

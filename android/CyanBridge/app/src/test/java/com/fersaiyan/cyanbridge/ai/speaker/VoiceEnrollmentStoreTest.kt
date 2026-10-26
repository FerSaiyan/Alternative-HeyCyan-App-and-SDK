package com.fersaiyan.cyanbridge.ai.speaker

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class VoiceEnrollmentStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun info(count: Int = 3) = EnrollmentInfo("pt-BR", "phone_mic", count, consentAtMs = 1L, completedAtMs = 2L)

    @Test fun `pending phrases only become the profile on completion`() {
        val root = File(temporary.root, "voiceprint")
        val store = VoiceEnrollmentStore(root)
        repeat(3) { store.savePendingPhrase(it, shortArrayOf(it.toShort(), -1, Short.MAX_VALUE)) }
        assertFalse(store.isEnrolled())
        assertNull(store.loadPhrase(0))

        store.complete(info())

        assertTrue(store.isEnrolled())
        assertEquals(info(), store.info())
        assertArrayEquals(shortArrayOf(2, -1, Short.MAX_VALUE), store.loadPhrase(2))
        assertFalse(File(root, "pending").exists())
    }

    @Test fun `abandoned re-enrollment keeps the existing profile and templates`() {
        val store = VoiceEnrollmentStore(temporary.root)
        repeat(3) { store.savePendingPhrase(it, shortArrayOf(1, 2, 3)) }
        store.complete(info())
        val template = SpeakerTemplate("ecapa-spkrec-voxceleb-2s", floatArrayOf(0.6f, 0.8f), windows = 6, minLeaveOneOut = 0.7f, createdAtMs = 5L)
        store.saveTemplate(template)

        store.savePendingPhrase(0, shortArrayOf(9, 9))
        store.discardPending()

        assertTrue(store.isEnrolled())
        assertArrayEquals(shortArrayOf(1, 2, 3), store.loadPhrase(0))
        assertArrayEquals(template.vector, store.loadTemplate(template.modelId)!!.vector, 0f)
    }

    @Test fun `completing a new enrollment drops templates built from the old one`() {
        val store = VoiceEnrollmentStore(temporary.root)
        repeat(3) { store.savePendingPhrase(it, shortArrayOf(1)) }
        store.complete(info())
        store.saveTemplate(SpeakerTemplate("wespeaker-resnet34-lm-2s-int8w", floatArrayOf(1f), 1, Float.NaN, 1L))
        repeat(3) { store.savePendingPhrase(it, shortArrayOf(2)) }
        store.complete(info())
        assertNull(store.loadTemplate("wespeaker-resnet34-lm-2s-int8w"))
        assertArrayEquals(shortArrayOf(2), store.loadPhrase(1))
    }

    @Test fun `missing phrase files invalidate the enrollment and deleteAll removes everything`() {
        val root = File(temporary.root, "voiceprint")
        val store = VoiceEnrollmentStore(root)
        repeat(3) { store.savePendingPhrase(it, shortArrayOf(1)) }
        store.complete(info())
        File(root, "phrase-1.pcm").delete()
        assertFalse(store.isEnrolled())
        store.deleteAll()
        assertFalse(root.exists())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `template ids cannot escape the profile directory`() {
        VoiceEnrollmentStore(temporary.root).loadTemplate("../../secrets")
    }
}

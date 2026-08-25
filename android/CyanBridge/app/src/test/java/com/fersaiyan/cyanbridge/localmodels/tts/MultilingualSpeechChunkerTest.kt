package com.fersaiyan.cyanbridge.localmodels.tts

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MultilingualSpeechChunkerTest {

    private val producedChunks = mutableListOf<String>()
    private lateinit var chunker: MultilingualSpeechChunker

    @Before
    fun setUp() {
        producedChunks.clear()
        val config = SpeechChunkingConfig(
            firstChunkMinCodePoints = 5,
            normalChunkMinCodePoints = 5,
            firstChunkPreferredMaxCodePoints = 30,
            preferredChunkMaxCodePoints = 60,
            hardChunkMaxCodePoints = 100,
            candidateBoundaryDelayMs = 0L,
            firstChunkIdleFlushMs = 50L,
            normalChunkIdleFlushMs = 50L,
        )
        chunker = MultilingualSpeechChunker(
            config = config,
            scope = CoroutineScope(Dispatchers.Default),
            onChunkReady = { _, chunk -> producedChunks.add(chunk) },
        )
        chunker.startSession(1L)
    }

    @Test
    fun testEnglishSentencesAndDecimals() = runBlocking {
        chunker.append("Dr. Smith is here. He is waiting.")
        chunker.finish()

        val fullSpoken = producedChunks.joinToString(" ")
        assertTrue("No text lost in English stream", fullSpoken.contains("Smith is here"))
        assertTrue("Decimal/Abbreviation preserved", fullSpoken.contains("Dr. Smith"))
    }

    @Test
    fun testDecimalAndVersionSplits() = runBlocking {
        chunker.append("The value is 3.14 meters. Use version 2.1.0.")
        chunker.finish()

        val fullSpoken = producedChunks.joinToString(" ")
        assertTrue("Decimal 3.14 preserved", fullSpoken.contains("3.14"))
        assertTrue("Version 2.1.0 preserved", fullSpoken.contains("2.1.0"))
    }

    @Test
    fun testShortValidAnswers() = runBlocking {
        chunker.append("Yes.")
        chunker.finish()
        assertEquals(1, producedChunks.size)
        assertEquals("Yes.", producedChunks[0])

        producedChunks.clear()
        chunker.startSession(2L)
        chunker.append("Door ahead.")
        chunker.finish()
        assertEquals("Door ahead.", producedChunks.joinToString(" "))
    }

    @Test
    fun testFirstStableClauseFlushesAtCommaBeforeGenerationFinishes() {
        chunker.append("A person is ahead, standing by the doorway")

        assertTrue("First clause should be available immediately", producedChunks.isNotEmpty())
        assertEquals("A person is ahead,", producedChunks.first())
    }

    @Test
    fun testTerminalPeriodConfirmsBeforeIdleTimeout() = runBlocking {
        val fastChunks = mutableListOf<String>()
        val fastChunker = MultilingualSpeechChunker(
            config = SpeechChunkingConfig(
                firstChunkMinCodePoints = 5,
                normalChunkMinCodePoints = 5,
                candidateBoundaryDelayMs = 20L,
                firstChunkIdleFlushMs = 1_000L,
                normalChunkIdleFlushMs = 1_000L,
            ),
            scope = CoroutineScope(Dispatchers.Default),
            onChunkReady = { _, chunk -> fastChunks.add(chunk) },
        )
        fastChunker.startSession(90L)
        fastChunker.append("Door ahead.")

        delay(100L)

        assertEquals(listOf("Door ahead."), fastChunks)
        fastChunker.reset()
    }

    @Test
    fun testPortugueseSentences() = runBlocking {
        chunker.append("O Dr. Silva chegou. Ele está esperando. Há uma porta à frente.")
        chunker.finish()

        val fullSpoken = producedChunks.joinToString(" ")
        assertTrue(fullSpoken.contains("Dr. Silva"))
        assertTrue(fullSpoken.contains("esperando"))
    }

    @Test
    fun testGermanSentences() = runBlocking {
        chunker.append("Dr. Müller ist hier. Er wartet. Links befindet sich eine Tür.")
        chunker.finish()

        val fullSpoken = producedChunks.joinToString(" ")
        assertTrue(fullSpoken.contains("Dr. Müller"))
        assertTrue(fullSpoken.contains("eine Tür"))
    }

    @Test
    fun testFrenchSentences() = runBlocking {
        chunker.append("Le Dr Martin est arrivé. Il attend. Attention ! Une marche devant vous.")
        chunker.finish()

        val fullSpoken = producedChunks.joinToString(" ")
        assertTrue(fullSpoken.contains("Dr Martin"))
        assertTrue(fullSpoken.contains("Attention !"))
    }

    @Test
    fun testItalianSentences() = runBlocking {
        chunker.append("Il dott. Rossi è arrivato. Sta aspettando. C'è una porta davanti.")
        chunker.finish()

        val fullSpoken = producedChunks.joinToString(" ")
        assertTrue(fullSpoken.contains("dott. Rossi"))
        assertTrue(fullSpoken.contains("davanti"))
    }

    @Test
    fun testRussianSentences() = runBlocking {
        chunker.append("Доктор Иванов здесь. Он ждёт. Впереди дверь.")
        chunker.finish()

        val fullSpoken = producedChunks.joinToString(" ")
        assertTrue(fullSpoken.contains("Доктор Иванов"))
        assertTrue(fullSpoken.contains("дверь"))
    }

    @Test
    fun testChineseStreamingNoWhitespace() = runBlocking {
        chunker.append("前")
        chunker.append("方有")
        chunker.append("一扇门")
        chunker.append("。")
        chunker.append("请向左")
        chunker.append("移动")
        chunker.append("。")
        chunker.finish()

        val combined = producedChunks.joinToString("")
        assertTrue("Chinese text assembled cleanly", combined.contains("前方有一扇门。"))
        assertTrue("Second sentence assembled cleanly", combined.contains("请向左移动。"))
    }

    @Test
    fun testAwkwardDecimalStreamFragments() = runBlocking {
        chunker.append("3")
        chunker.append(".")
        chunker.append("14")
        chunker.append(" meters")
        chunker.append(".")
        chunker.finish()

        val combined = producedChunks.joinToString(" ")
        assertTrue("Decimal fragmented chunks combined without wrong split", combined.contains("3.14 meters."))
    }

    @Test
    fun testAccumulatedCallbacksDoNotDuplicateSpeech() {
        chunker.append("There")
        chunker.append("There is")
        chunker.append("There is a door.")
        chunker.finish()

        assertEquals(listOf("There is a door."), producedChunks)
    }

    @Test
    fun testUrlsAcronymsFilesAndNumberedListsRemainIntact() {
        chunker.append("Visit example.com. Read U.S. policy. Open model.bin. 1. First item.")
        chunker.finish()

        val spoken = producedChunks.joinToString(" ")
        assertTrue(spoken.contains("example.com"))
        assertTrue(spoken.contains("U.S."))
        assertTrue(spoken.contains("model.bin"))
        assertTrue(spoken.contains("1. First item."))
    }

    @Test
    fun testSurrogatePairsRemainWholeWhenForcedToSplit() {
        val chunks = mutableListOf<String>()
        val smallChunker = MultilingualSpeechChunker(
            config = SpeechChunkingConfig(
                firstChunkMinCodePoints = 1,
                normalChunkMinCodePoints = 1,
                firstChunkPreferredMaxCodePoints = 4,
                preferredChunkMaxCodePoints = 4,
                hardChunkMaxCodePoints = 5,
            ),
            onChunkReady = { _, chunk -> chunks.add(chunk) },
        )
        smallChunker.startSession(1L)
        smallChunker.append("😀😀😀😀😀😀")
        smallChunker.finish()

        assertEquals("😀😀😀😀😀😀", chunks.joinToString(""))
        assertTrue(chunks.none { it.last().isHighSurrogate() })
    }

    @Test
    fun testClosingPunctuationQuotesAndParens() = runBlocking {
        chunker.append("He said, \"Look out!\" And then he left.")
        chunker.finish()

        val combined = producedChunks.joinToString(" ")
        assertTrue("Quotes after exclamation mark preserved", combined.contains("\"Look out!\""))
    }

    @Test
    fun testTextNormalizerSpeechSanitization() {
        val markdown = "**Warning:** Please visit https://example.com for details.\n- Item 1\n- Item 2"
        val normalized = StreamingTextNormalizer.normalizeForSpeech(markdown, "en")

        assertTrue("Markdown bold stripped", !normalized.contains("**"))
        assertTrue("Bullet markers stripped", !normalized.contains("- "))
        assertTrue("URL transformed for speech", normalized.contains("a link is included"))
    }

    @Test
    fun testSessionIdCancellationProtection() = runBlocking {
        chunker.startSession(10L)
        chunker.append("Session 10 text.", 10L)

        // Session 10 cancelled when session 11 starts
        chunker.startSession(11L)
        chunker.append("Session 11 text.", 10L) // Stale session 10 append
        chunker.append("Session 11 valid text.", 11L)
        chunker.finish(11L)

        val fullSpoken = producedChunks.joinToString(" ")
        assertTrue("Stale session 10 text rejected", !fullSpoken.contains("Session 11 text."))
        assertTrue("Session 11 text accepted", fullSpoken.contains("Session 11 valid text."))
    }
}

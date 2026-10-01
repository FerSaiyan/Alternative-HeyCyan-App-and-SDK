package com.fersaiyan.cyanbridge.ai.transcription

import org.junit.Assert.*
import org.junit.Test

class OfflineQuestionTranscriberTest {
    @Test fun bareLanguageUsesAnInstalledRegionalPackInsteadOfFailingWithBareEn() {
        assertEquals("en-US", OfflineQuestionTranscriber.selectInstalledLocale("en", listOf("pt-BR", "en-US")))
    }
    @Test fun explicitDialectIsRespectedCaseInsensitively() {
        assertEquals("en-GB", OfflineQuestionTranscriber.selectInstalledLocale("EN-gb", listOf("en-US", "en-GB")))
        assertNull(OfflineQuestionTranscriber.selectInstalledLocale("en-GB", listOf("en-US")))
    }
    @Test fun missingLanguageFallsBackToAudioRatherThanUsingAnotherLanguage() {
        assertNull(OfflineQuestionTranscriber.selectInstalledLocale("ja", listOf("en-US", "pt-BR")))
        assertNull(OfflineQuestionTranscriber.selectInstalledLocale("en", emptyList()))
    }
}

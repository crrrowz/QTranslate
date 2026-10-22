package com.github.ahatem.qtranslate.plugins.ai

import com.github.ahatem.qtranslate.api.language.LanguageCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pins the translation between Whisper's conventions and the host's: Whisper takes ISO-639-1
 * codes but reports the language it heard by English name.
 */
class AISpeechRecognizerServiceTest {

    @Test
    fun `the spoken language is sent as its primary subtag`() {
        assertEquals("en", AISpeechRecognizerService.whisperLanguage(LanguageCode("en-US")))
        assertEquals("zh", AISpeechRecognizerService.whisperLanguage(LanguageCode("zh-Hans")))
        assertNull(AISpeechRecognizerService.whisperLanguage(LanguageCode.AUTO))
    }

    @Test
    fun `a reported language name becomes a code`() {
        assertEquals(LanguageCode("en"), AISpeechRecognizerService.languageFromName("english"))
        assertEquals(LanguageCode("ar"), AISpeechRecognizerService.languageFromName("Arabic"))
        assertEquals(LanguageCode("de"), AISpeechRecognizerService.languageFromName("de"))
        assertNull(AISpeechRecognizerService.languageFromName(""))
        assertNull(AISpeechRecognizerService.languageFromName("klingon"))
    }
}

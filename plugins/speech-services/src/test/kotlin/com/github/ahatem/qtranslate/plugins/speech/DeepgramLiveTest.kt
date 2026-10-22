package com.github.ahatem.qtranslate.plugins.speech

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.michaelbull.result.getError
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DeepgramLiveTest {

    @Test
    fun `parse interim and final deepgram messages`() {
        val partialJson = """
            {
              "type": "Results",
              "is_final": false,
              "channel": {
                "alternatives": [
                  {
                    "transcript": "good morning",
                    "confidence": 0.95
                  }
                ]
              }
            }
        """.trimIndent()
        val partial = parseDeepgramMessage(partialJson)
        assertTrue(partial is DeepgramParsedMessage.Partial)
        assertEquals("good morning", partial.text)

        val finalJson = """
            {
              "type": "Results",
              "is_final": true,
              "channel": {
                "alternatives": [
                  {
                    "transcript": "good morning everyone",
                    "words": [
                      {"word": "good", "start": 0.1, "end": 0.4},
                      {"word": "morning", "start": 0.5, "end": 0.9},
                      {"word": "everyone", "start": 1.0, "end": 1.4}
                    ]
                  }
                ]
              }
            }
        """.trimIndent()
        val finalResult = parseDeepgramMessage(finalJson)
        assertTrue(finalResult is DeepgramParsedMessage.Final)
        assertEquals("good morning everyone", finalResult.text)
        assertEquals(3, finalResult.words.size)
        assertEquals("good", finalResult.words[0].text)
        assertEquals(100L, finalResult.words[0].startMillis)
        assertEquals(400L, finalResult.words[0].endMillis)
        assertEquals(1400L, finalResult.words[2].endMillis)
    }

    @Test
    fun `parse deepgram error message`() {
        val errorJson = """{"type":"Error","description":"Invalid credentials"}"""
        val error = parseDeepgramMessage(errorJson)
        assertTrue(error is DeepgramParsedMessage.Error)
        assertEquals("Invalid credentials", error.message)
    }

    @Test
    fun `deepgram live service requires api key`() = runBlocking {
        val service = DeepgramLiveService {
            SpeechSettings(apiKey = "", liveApiKey = "")
        }
        val err = service.openSession(LanguageCode.ENGLISH).getError()
        assertNotNull(err)
        assertTrue(err.message.contains("API key"))

        val validateErr = service.validate().getError()
        assertNotNull(validateErr)
        assertTrue(validateErr.message.contains("API key"))
    }
}

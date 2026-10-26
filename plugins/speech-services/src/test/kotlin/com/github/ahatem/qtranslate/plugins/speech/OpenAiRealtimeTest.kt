package com.github.ahatem.qtranslate.plugins.speech

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.michaelbull.result.getError
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OpenAiRealtimeTest {

    @Test
    fun `parse openai realtime delta and final messages`() {
        val readyJson = """{"type":"session.created"}"""
        val ready = parseOpenAiRealtimeMessage(readyJson)
        assertEquals(OpenAiParsedMessage.Ready, ready)

        val deltaJson = """{"type":"conversation.item.input_audio_transcription.delta","delta":" hello"}"""
        val delta = parseOpenAiRealtimeMessage(deltaJson)
        assertTrue(delta is OpenAiParsedMessage.PartialDelta)
        assertEquals(" hello", delta.delta)

        val completedJson = """
            {
              "type": "conversation.item.input_audio_transcription.completed",
              "transcript": "Hello, how are you?"
            }
        """.trimIndent()
        val completed = parseOpenAiRealtimeMessage(completedJson)
        assertTrue(completed is OpenAiParsedMessage.Final)
        assertEquals("Hello, how are you?", completed.text)

        val errorJson = """{"type":"error","error":{"message":"Rate limit reached"}}"""
        val error = parseOpenAiRealtimeMessage(errorJson)
        assertTrue(error is OpenAiParsedMessage.Error)
        assertEquals("Rate limit reached", error.message)
    }

    @Test
    fun `openai realtime requires api key`() = runBlocking {
        val service = OpenAiRealtimeService {
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

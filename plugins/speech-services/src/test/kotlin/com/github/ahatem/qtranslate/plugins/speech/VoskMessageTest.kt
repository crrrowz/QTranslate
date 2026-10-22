package com.github.ahatem.qtranslate.plugins.speech

import kotlin.test.Test
import kotlin.test.assertEquals

/** Pins how the Vosk server's messages are read, word times included. */
class VoskMessageTest {

    @Test
    fun `status messages`() {
        assertEquals(VoskMessage.Ready, parseVoskMessage("""{"type":"status","status":"ready","msg":"Model loaded"}"""))
        assertEquals(VoskMessage.Stopped, parseVoskMessage("""{"type":"status","status":"stopped"}"""))
        assertEquals(VoskMessage.Error("No model for 'fr'"), parseVoskMessage("""{"type":"status","status":"error","msg":"No model for 'fr'"}"""))
        assertEquals(VoskMessage.Pong(listOf("ar", "en")), parseVoskMessage("""{"type":"status","status":"pong","models":["ar","en"],"connections":"1/5"}"""))
    }

    @Test
    fun `partial and final text`() {
        assertEquals(VoskMessage.Partial("see you"), parseVoskMessage("""{"type":"partial","text":" see you "}"""))
        val result = parseVoskMessage("""{"type":"result","text":"see you","result":[{"conf":1.0,"start":0.42,"end":0.6,"word":"see"},{"conf":0.9,"start":0.6,"end":0.9,"word":"you"}]}""")
        result as VoskMessage.Result
        assertEquals("see you", result.text)
        assertEquals(listOf(420L, 600L), result.words.map { it.startMillis })
        assertEquals(VoskMessage.Result("hi", emptyList()), parseVoskMessage("""{"type":"result","text":"hi"}"""), "word times are optional")
    }

    @Test
    fun `anything else is ignored`() {
        assertEquals(VoskMessage.Other, parseVoskMessage("not json"))
        assertEquals(VoskMessage.Other, parseVoskMessage("""{"type":"something-new"}"""))
    }

    @Test
    fun `vosk native library loads successfully`() {
        org.vosk.LibVosk.setLogLevel(org.vosk.LogLevel.WARNINGS)
    }
}

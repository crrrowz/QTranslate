package com.github.ahatem.qtranslate.plugins.speech

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.speech.LiveSpeechEvent
import com.github.michaelbull.result.get
import com.github.michaelbull.result.getError
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Streams a recorded WAV to a real Vosk server, in real time, and prints what comes back.
 * Skipped unless VOSK_URL (e.g. ws://192.168.1.20:8765) and VOSK_WAV (16 kHz mono 16-bit) are set.
 */
class VoskLiveIntegrationTest {

    @Test
    fun `streams speech to a real Vosk server and gets partial and final text`() = runBlocking {
        val url = System.getenv("VOSK_URL")
        val wav = System.getenv("VOSK_WAV")?.let(::File)
        assumeTrue(url != null && wav != null && wav.isFile, "set VOSK_URL and VOSK_WAV to run against a real server")

        val service = VoskLiveService { SpeechSettings(liveServerUrl = url!!) }
        println("ping: " + (service.validate().getError()?.message ?: "ok"))

        val opened = System.currentTimeMillis()
        val session = service.openSession(LanguageCode(System.getenv("VOSK_LANG") ?: "en"))
        session.getError()?.let { error("open failed: ${it.message}") }
        val live = session.get()!!
        println("ready after ${System.currentTimeMillis() - opened} ms")

        val started = System.currentTimeMillis()
        val events = async {
            live.events.toList().onEach { event ->
                println("+${System.currentTimeMillis() - started} ms  $event")
            }
        }
        // 100 ms chunks at real-time pace, as a microphone delivers them.
        val pcm = wav!!.readBytes().copyOfRange(44, wav.length().toInt())
        pcm.toList().chunked(3_200).forEach { chunk ->
            live.sendAudio(chunk.toByteArray())
            delay(100)
        }
        live.finish()
        val received = events.await()

        assertTrue(received.any { it is LiveSpeechEvent.Partial }, "partial results while speaking")
        assertTrue(received.any { it is LiveSpeechEvent.Final && it.text.isNotBlank() }, "at least one final result")
    }
}

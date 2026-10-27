package com.github.ahatem.qtranslate.plugins.speech

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.michaelbull.result.getError
import com.github.michaelbull.result.get
import kotlinx.coroutines.runBlocking
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.Base64
import kotlin.concurrent.thread
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

    @Test
    fun `session config is sent as the first frame after connecting`() = runBlocking {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        var firstFrame: String? = null
        val serverThread = thread(isDaemon = true) {
            server.accept().use { client ->
                client.soTimeout = 3_000
                val input = DataInputStream(client.getInputStream())
                val output = client.getOutputStream()
                val wsKey = generateSequence { readHttpLine(input) }.takeWhile { it.isNotEmpty() }.toList()
                    .first { it.startsWith("Sec-WebSocket-Key:", ignoreCase = true) }.substringAfter(':').trim()
                val accept = Base64.getEncoder().encodeToString(
                    MessageDigest.getInstance("SHA-1").digest((wsKey + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray())
                )
                output.write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n".toByteArray())
                output.flush()
                firstFrame = runCatching { readClientTextFrame(input) }.getOrNull()
                val ready = """{"type":"session.created"}""".toByteArray()
                output.write(byteArrayOf(0x81.toByte(), ready.size.toByte()) + ready)
                output.flush()
                runCatching { while (input.read() >= 0) Unit }
            }
        }

        val session = OpenAiRealtimeSession.open("k", "gpt-4o-realtime-preview", "ws://127.0.0.1:${server.localPort}/v1/realtime").get()
        session?.cancel()
        serverThread.join(5_000)
        server.close()

        assertNotNull(firstFrame, "no frame reached the server: session.update was dropped")
        assertTrue(firstFrame!!.contains("\"session.update\""), firstFrame)
    }

    private fun readHttpLine(input: DataInputStream): String {
        val line = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0 || c == 10) break
            if (c != 13) line.append(c.toChar())
        }
        return line.toString()
    }

    private fun readClientTextFrame(input: DataInputStream): String {
        input.readUnsignedByte()
        val lengthByte = input.readUnsignedByte() and 0x7f
        val length = when (lengthByte) {
            126 -> input.readUnsignedShort()
            127 -> input.readLong().toInt()
            else -> lengthByte
        }
        val mask = ByteArray(4).also { input.readFully(it) }
        val payload = ByteArray(length).also { input.readFully(it) }
        for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
        return payload.decodeToString()
    }
}

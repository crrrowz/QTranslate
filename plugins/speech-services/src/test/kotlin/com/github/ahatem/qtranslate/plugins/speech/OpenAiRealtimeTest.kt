package com.github.ahatem.qtranslate.plugins.speech

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.michaelbull.result.getError
import com.github.michaelbull.result.get
import kotlinx.coroutines.launch
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
    fun `session config is sent as transcription session with chosen model and language`() = runBlocking {
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
                runCatching { while (input.read() >= 0) { /* drain */ } }
            }
        }

        val session = OpenAiRealtimeSession.open(
            apiKey = "test-key",
            model = "gpt-live-transcribe",
            endpoint = "ws://127.0.0.1:${server.localPort}/v1/realtime",
            languageTag = "ar"
        ).get()
        session?.cancel()
        serverThread.join(5_000)
        server.close()

        assertNotNull(firstFrame, "no frame reached the server: session.update was dropped")
        assertTrue(firstFrame!!.contains("\"type\":\"session.update\""), firstFrame)
        assertTrue(firstFrame!!.contains("\"type\":\"transcription\""), firstFrame)
        assertTrue(firstFrame!!.contains("\"model\":\"gpt-live-transcribe\""), firstFrame)
        assertTrue(firstFrame!!.contains("\"languages\":[\"ar\"]"), firstFrame)
        // No conversation turn response creation
        assertTrue(!firstFrame!!.contains("\"create_response\""), firstFrame)
    }

    @Test
    fun `session config with auto language does not include languages array`() = runBlocking {
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
                runCatching { while (input.read() >= 0) { /* drain */ } }
            }
        }

        val session = OpenAiRealtimeSession.open(
            apiKey = "test-key",
            model = "gpt-live-transcribe",
            endpoint = "ws://127.0.0.1:${server.localPort}/v1/realtime",
            languageTag = null
        ).get()
        session?.cancel()
        serverThread.join(5_000)
        server.close()

        assertNotNull(firstFrame)
        assertTrue(firstFrame!!.contains("\"type\":\"transcription\""), firstFrame)
        assertTrue(!firstFrame!!.contains("\"languages\""), firstFrame)
    }

    @Test
    fun `partial deltas accumulate per item and reset on final completion`() = runBlocking {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
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
                runCatching { readClientTextFrame(input) }

                fun sendServerText(json: String) {
                    val bytes = json.toByteArray()
                    output.write(byteArrayOf(0x81.toByte(), bytes.size.toByte()) + bytes)
                    output.flush()
                }

                sendServerText("""{"type":"session.created"}""")
                sendServerText("""{"type":"conversation.item.input_audio_transcription.delta","delta":"Hello"}""")
                sendServerText("""{"type":"conversation.item.input_audio_transcription.delta","delta":" world"}""")
                sendServerText("""{"type":"conversation.item.input_audio_transcription.completed","transcript":"Hello world"}""")
                sendServerText("""{"type":"conversation.item.input_audio_transcription.delta","delta":"Second"}""")

                runCatching { while (input.read() >= 0) { /* drain */ } }
            }
        }

        val session = OpenAiRealtimeSession.open(
            apiKey = "test-key",
            model = "gpt-live-transcribe",
            endpoint = "ws://127.0.0.1:${server.localPort}/v1/realtime"
        ).get()
        assertNotNull(session)

        val collected = mutableListOf<com.github.ahatem.qtranslate.api.speech.LiveSpeechEvent>()
        val collectJob = this.launch {
            session.events.collect { collected.add(it) }
        }

        kotlinx.coroutines.delay(500)
        session.cancel()
        collectJob.cancel()
        serverThread.join(5_000)
        server.close()

        val partials = collected.filterIsInstance<com.github.ahatem.qtranslate.api.speech.LiveSpeechEvent.Partial>().map { it.text }
        val finals = collected.filterIsInstance<com.github.ahatem.qtranslate.api.speech.LiveSpeechEvent.Final>().map { it.text }

        assertEquals(listOf("Hello", "Hello world", "Second"), partials)
        assertEquals(listOf("Hello world"), finals)
    }

    @Test
    fun `error event before ready fails openSession`() = runBlocking {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
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
                runCatching { readClientTextFrame(input) }

                val err = """{"type":"error","error":{"message":"Invalid model name"}}""".toByteArray()
                output.write(byteArrayOf(0x81.toByte(), err.size.toByte()) + err)
                output.flush()

                runCatching { while (input.read() >= 0) { /* drain */ } }
            }
        }

        val res = OpenAiRealtimeSession.open(
            apiKey = "test-key",
            model = "bad-model",
            endpoint = "ws://127.0.0.1:${server.localPort}/v1/realtime"
        )
        serverThread.join(5_000)
        server.close()

        val err = res.getError()
        assertNotNull(err)
        assertTrue(err.message.contains("Invalid model name"), err.message)
    }

    @Test
    fun `error event after ready emits Failed event`() = runBlocking {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
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
                runCatching { readClientTextFrame(input) }

                fun sendServerText(json: String) {
                    val bytes = json.toByteArray()
                    output.write(byteArrayOf(0x81.toByte(), bytes.size.toByte()) + bytes)
                    output.flush()
                }

                sendServerText("""{"type":"session.created"}""")
                Thread.sleep(100)
                sendServerText("""{"type":"error","error":{"message":"Audio buffer overflow"}}""")

                runCatching { while (input.read() >= 0) { /* drain */ } }
            }
        }

        val session = OpenAiRealtimeSession.open(
            apiKey = "test-key",
            model = "gpt-live-transcribe",
            endpoint = "ws://127.0.0.1:${server.localPort}/v1/realtime"
        ).get()
        assertNotNull(session)

        val collected = mutableListOf<com.github.ahatem.qtranslate.api.speech.LiveSpeechEvent>()
        val collectJob = this.launch {
            session.events.collect { collected.add(it) }
        }

        kotlinx.coroutines.delay(500)
        session.cancel()
        collectJob.cancel()
        serverThread.join(5_000)
        server.close()

        val failed = collected.filterIsInstance<com.github.ahatem.qtranslate.api.speech.LiveSpeechEvent.Failed>().firstOrNull()
        assertNotNull(failed, "Expected LiveSpeechEvent.Failed event")
        assertTrue(failed.error.message.contains("Audio buffer overflow"), failed.error.message)
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

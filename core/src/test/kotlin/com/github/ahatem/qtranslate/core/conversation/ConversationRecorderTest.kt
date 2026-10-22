package com.github.ahatem.qtranslate.core.conversation

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.speech.RecognizedWord
import com.github.ahatem.qtranslate.api.speech.SpeechRecognitionRequest
import com.github.ahatem.qtranslate.api.speech.SpeechRecognitionResponse
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Drives the recorder with scripted devices in real time. Each test feeds a few seconds of
 * synthetic audio, so they are slower than most unit tests but exercise the real threads.
 */
class ConversationRecorderTest {

    private val logger = object : Logger {
        override fun debug(message: String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, error: Throwable?) = Unit
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val repository = SessionRepository(createTempDirectory("recorder").toFile())

    @AfterTest
    fun tearDown() {
        scope.cancel()
        repository.root.deleteRecursively()
    }

    /** A device whose audio is pushed by the test, delivered in 100 ms chunks. */
    private class ScriptedDevice {
        val chunks = LinkedBlockingQueue<ByteArray>()
        var failWith: Exception? = null

        fun push(pcm: ByteArray) = pcm.toList().chunked(3_200).forEach { chunks.put(it.toByteArray()) }

        fun stream() = object : PcmStream {
            @Volatile var closed = false
            override fun read(): ByteArray? {
                while (!closed) {
                    failWith?.let { throw it }
                    chunks.poll(20, TimeUnit.MILLISECONDS)?.let { return it }
                }
                return null
            }
            override fun close() { closed = true }
        }
    }

    private class ScriptedBackend(private val devices: Map<String, ScriptedDevice>) : AudioBackend {
        override fun inputDevices() = devices.keys.map { AudioDevice(it, it) }
        override fun open(device: AudioDevice): PcmStream =
            devices[device.id]?.stream() ?: throw AudioDeviceUnavailableException(device)
    }

    private fun recorder(
        mic: ScriptedDevice,
        system: ScriptedDevice,
        openLive: OpenLive = { null },
        recognize: Recognize
    ) = ConversationRecorder(
        scope = scope,
        repository = repository,
        microphoneBackend = ScriptedBackend(mapOf("mic" to mic)),
        systemBackend = ScriptedBackend(mapOf("out" to system)),
        recognize = recognize,
        logger = logger,
        openLive = openLive
    )

    /** A live session the test speaks for: it pushes the events a live server would send. */
    private class ScriptedLive : com.github.ahatem.qtranslate.api.speech.LiveSpeechSession {
        val channel = kotlinx.coroutines.channels.Channel<com.github.ahatem.qtranslate.api.speech.LiveSpeechEvent>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        val received = java.util.concurrent.atomic.AtomicLong()
        override val events = channel.let { c -> kotlinx.coroutines.flow.flow { for (e in c) emit(e) } }
        override fun sendAudio(pcm: ByteArray) { received.addAndGet(pcm.size.toLong()) }
        override suspend fun finish() { channel.close() }
        override fun cancel() { channel.close() }
    }

    private fun options(keepAudio: Boolean = true) = RecordingOptions(
        title = "Test call",
        microphone = AudioDevice("mic", "mic"),
        systemOutput = AudioDevice("out", "out"),
        keepAudio = keepAudio
    )

    private suspend fun waitUntil(condition: () -> Boolean) = withTimeout(10_000) {
        while (!condition()) delay(20)
    }

    @Test
    fun `both sources are recorded separately, transcribed and saved in spoken order`() = runBlocking {
        val mic = ScriptedDevice()
        val system = ScriptedDevice()
        val recorder = recorder(mic, system) { request ->
            // Tells the sources apart by clip length: the mic says a short line, the call a long one.
            val text = if (request.audio.durationMillis < 1_400) "maybe check the logs" else "the server keeps timing out"
            Ok(SpeechRecognitionResponse(text, words = listOf(RecognizedWord("the", 100, 300))))
        }

        recorder.start(options())
        waitUntil { recorder.state.value.streams.values.all { it.state == StreamState.LIVE } }
        system.push(silence(300) + tone(1_800) + silence(1_000))
        mic.push(silence(2_000) + tone(700) + silence(1_000))
        waitUntil { recorder.state.value.session?.segments?.count { !it.pending && it.text.isNotBlank() } == 2 }
        recorder.stop()

        val saved = repository.list().single()
        assertEquals(SessionStatus.COMPLETED, saved.status)
        assertEquals(listOf(AudioSource.SYSTEM, AudioSource.MICROPHONE), saved.segments.map { it.source })
        assertEquals("the server keeps timing out", saved.segments[0].text)
        val word = saved.segments[0].words.single()
        assertEquals(saved.segments[0].startMillis + 100, word.startMillis, "word times are on the session timeline")
        assertTrue(repository.hasAudio(saved.id, AudioSource.MICROPHONE))
        assertTrue(repository.hasAudio(saved.id, AudioSource.SYSTEM))
        assertEquals(RecorderPhase.IDLE, recorder.state.value.phase)
    }

    @Test
    fun `hybrid mode shows live words at once and replaces them with the accurate text`() = runBlocking {
        val mic = ScriptedDevice()
        val live = ScriptedLive()
        val clips = java.util.Collections.synchronizedList(mutableListOf<Long>())
        val recorder = recorder(mic, ScriptedDevice(), openLive = { Ok(live) }) { request ->
            clips += request.audio.durationMillis
            Ok(SpeechRecognitionResponse("See you tomorrow."))
        }
        recorder.start(options().copy(systemOutput = null))
        mic.push(silence(500))
        waitUntil { live.received.get() > 0 }

        mic.push(tone(1_500))
        waitUntil { live.received.get() >= 32_000L * 2 - 2 }
        live.channel.send(com.github.ahatem.qtranslate.api.speech.LiveSpeechEvent.Partial("see you"))
        waitUntil { recorder.state.value.live[AudioSource.MICROPHONE]?.text == "see you" }

        live.channel.send(com.github.ahatem.qtranslate.api.speech.LiveSpeechEvent.Final("see you to morrow"))
        waitUntil { recorder.state.value.session?.segments?.singleOrNull()?.text == "See you tomorrow." }
        val segment = recorder.state.value.session!!.segments.single()
        assertTrue(!segment.draft, "the accurate text is final")
        assertTrue(recorder.state.value.live.isEmpty(), "the live line clears once settled")
        assertTrue(clips.single() >= 1_400, "the accurate recognizer got the spoken stretch, was ${clips.single()} ms")
        recorder.stop()
    }

    @Test
    fun `live text stands on its own without an accurate recognizer`() = runBlocking {
        val mic = ScriptedDevice()
        val live = ScriptedLive()
        val recorder = recorder(mic, ScriptedDevice(), openLive = { Ok(live) }) { null }
        recorder.start(options().copy(systemOutput = null))
        mic.push(tone(800))
        waitUntil { live.received.get() > 0 }
        live.channel.send(com.github.ahatem.qtranslate.api.speech.LiveSpeechEvent.Final("hello there"))
        waitUntil { recorder.state.value.session?.segments?.singleOrNull()?.draft == false }
        assertEquals("hello there", recorder.state.value.session!!.segments.single().text)
        assertEquals(null, recorder.state.value.notice, "no accurate recognizer is not an error in live mode")
        recorder.stop()
    }

    @Test
    fun `an unconfigured live recognizer falls back to cutting at pauses, silently`() = runBlocking {
        val mic = ScriptedDevice()
        val recorder = recorder(mic, ScriptedDevice(),
            openLive = { Err(ServiceError.ConfigurationError("no live server set")) }) { Ok(SpeechRecognitionResponse("clip text")) }
        recorder.start(options().copy(systemOutput = null))
        waitUntil { recorder.state.value.streams[AudioSource.MICROPHONE]?.state == StreamState.LIVE }
        mic.push(tone(1_000) + silence(1_000))
        waitUntil { recorder.state.value.session?.segments?.any { it.text == "clip text" } == true }
        assertEquals(null, recorder.state.value.notice)
        recorder.stop()
    }

    @Test
    fun `each source is transcribed in its own language`() = runBlocking {
        val mic = ScriptedDevice()
        val system = ScriptedDevice()
        val languages = java.util.Collections.synchronizedList(mutableListOf<Pair<Long, String>>())
        val recorder = recorder(mic, system) { request ->
            languages += request.audio.durationMillis to request.language.tag
            Ok(SpeechRecognitionResponse("x"))
        }
        recorder.start(options().copy(
            microphoneLanguage = com.github.ahatem.qtranslate.api.language.LanguageCode("ar"),
            systemLanguage = com.github.ahatem.qtranslate.api.language.LanguageCode("en")
        ))
        waitUntil { recorder.state.value.streams.values.all { it.state == StreamState.LIVE } }
        mic.push(tone(700) + silence(1_000))
        system.push(tone(1_800) + silence(1_000))
        waitUntil { languages.size == 2 }
        recorder.stop()

        assertEquals("ar", languages.minByOrNull { it.first }!!.second, "the short clip came from the microphone")
        assertEquals("en", languages.maxByOrNull { it.first }!!.second)
        assertEquals("en", repository.list().single().callLanguage)
    }

    @Test
    fun `retry failed transcribes every failed segment once the service works`() = runBlocking {
        val mic = ScriptedDevice()
        var available = false
        val recorder = recorder(mic, ScriptedDevice()) {
            if (available) Ok(SpeechRecognitionResponse("recovered")) else null
        }
        recorder.start(options())
        waitUntil { recorder.state.value.streams[AudioSource.MICROPHONE]?.state == StreamState.LIVE }
        mic.push(tone(800) + silence(1_000) + tone(800) + silence(1_000))
        waitUntil { recorder.state.value.session?.segments?.count { it.failed } == 2 }
        recorder.stop()

        available = true
        val updated = recorder.retryFailed(repository.list().single())
        assertEquals(listOf("recovered", "recovered"), updated.segments.map { it.text })
        assertTrue(repository.list().single().segments.none { it.failed })
    }

    @Test
    fun `a failed transcription keeps the audio and can be retried`() = runBlocking {
        val mic = ScriptedDevice()
        var fail = true
        val recorder = recorder(mic, ScriptedDevice()) {
            if (fail) Err(ServiceError.NetworkError("offline"))
            else Ok(SpeechRecognitionResponse("hello there")) as Result<SpeechRecognitionResponse, ServiceError>
        }

        recorder.start(options())
        waitUntil { recorder.state.value.streams[AudioSource.MICROPHONE]?.state == StreamState.LIVE }
        mic.push(tone(1_000) + silence(1_000))
        waitUntil { recorder.state.value.session?.segments?.any { it.failed } == true }
        assertTrue(recorder.state.value.notice is RecorderNotice.TranscriptionFailed)
        recorder.stop()

        val session = repository.list().single()
        fail = false
        val retried = recorder.retry(session, session.segments.single())
        assertEquals("hello there", retried?.segments?.single()?.text)
        assertEquals("hello there", repository.load(session.id)?.segments?.single()?.text, "the retry is saved")
    }

    @Test
    fun `recording continues without a speech service and says so`() = runBlocking {
        val mic = ScriptedDevice()
        val recorder = recorder(mic, ScriptedDevice()) { null }
        recorder.start(options())
        waitUntil { recorder.state.value.streams[AudioSource.MICROPHONE]?.state == StreamState.LIVE }
        mic.push(tone(1_000) + silence(1_000))
        waitUntil { recorder.state.value.notice == RecorderNotice.NoSpeechService }
        recorder.stop()
        assertTrue(repository.hasAudio(repository.list().single().id, AudioSource.MICROPHONE))
    }

    @Test
    fun `audio spoken while paused is neither kept nor transcribed`() = runBlocking {
        val mic = ScriptedDevice()
        val clips = mutableListOf<SpeechRecognitionRequest>()
        val recorder = recorder(mic, ScriptedDevice()) { synchronized(clips) { clips += it }; Ok(SpeechRecognitionResponse("x")) }
        recorder.start(options())
        waitUntil { recorder.state.value.streams[AudioSource.MICROPHONE]?.state == StreamState.LIVE }
        recorder.pause()
        mic.push(tone(1_500) + silence(1_000))
        waitUntil { mic.chunks.isEmpty() }
        delay(300)
        recorder.resume()
        recorder.stop()

        assertTrue(clips.isEmpty())
        val session = repository.list().single()
        assertTrue(session.segments.isEmpty())
        assertTrue(WavFiles.durationMillis(repository.audioFile(session.id, AudioSource.MICROPHONE)) < 300)
    }

    @Test
    fun `a device that drops out stops only its own stream`() = runBlocking {
        val mic = ScriptedDevice()
        val system = ScriptedDevice()
        val recorder = recorder(mic, system) { Ok(SpeechRecognitionResponse("still here")) }
        recorder.start(options())
        waitUntil { recorder.state.value.streams.values.all { it.state == StreamState.LIVE } }

        system.failWith = IllegalStateException("device unplugged")
        waitUntil { recorder.state.value.streams[AudioSource.SYSTEM]?.state == StreamState.FAILED }
        assertTrue(recorder.state.value.notice is RecorderNotice.DeviceLost)

        mic.push(tone(1_000) + silence(1_000))
        waitUntil { recorder.state.value.session?.segments?.any { it.text == "still here" } == true }
        recorder.stop()
    }

    @Test
    fun `without keep audio the recordings are deleted when the session ends`() = runBlocking {
        val mic = ScriptedDevice()
        val recorder = recorder(mic, ScriptedDevice()) { Ok(SpeechRecognitionResponse("hi")) }
        recorder.start(options(keepAudio = false))
        waitUntil { recorder.state.value.streams[AudioSource.MICROPHONE]?.state == StreamState.LIVE }
        mic.push(tone(1_000) + silence(1_000))
        waitUntil { recorder.state.value.session?.segments?.any { it.text == "hi" } == true }
        recorder.stop()

        val session = repository.list().single()
        assertEquals("hi", session.segments.single().text, "the transcript is kept")
        assertTrue(!repository.hasAudio(session.id, AudioSource.MICROPHONE))
    }

    @Test
    fun `shutdown saves pending clips as failed instead of dropping them`() = runBlocking {
        val mic = ScriptedDevice()
        val recorder = recorder(mic, ScriptedDevice()) { delay(60_000); Ok(SpeechRecognitionResponse("late")) }
        recorder.start(options())
        waitUntil { recorder.state.value.streams[AudioSource.MICROPHONE]?.state == StreamState.LIVE }
        mic.push(tone(1_000) + silence(1_000))
        waitUntil { recorder.state.value.session?.segments?.any { it.pending } == true }

        recorder.shutdown()

        val session = repository.list().single()
        assertEquals(SessionStatus.COMPLETED, session.status)
        assertTrue(session.segments.single().failed)
    }
}

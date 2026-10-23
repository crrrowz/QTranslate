package com.github.ahatem.qtranslate.plugins.speech

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.speech.LiveSpeechEvent
import com.github.michaelbull.result.get
import com.github.michaelbull.result.getError
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VoskBuiltinServiceTest {

    @TempDir
    lateinit var tempDir: File

    open class FakeRecognizerHandle(
        private val onClosed: () -> Unit = {}
    ) : VoskRecognizerHandle {
        val closed = AtomicBoolean(false)
        val accepts = AtomicInteger(0)
        var returnEndpoint = false
        var partialJson = """{"partial":"hello world"}"""
        var resultJson = """{"text":"hello world","result":[{"conf":1.0,"start":0.2,"end":0.8,"word":"hello"}]}"""
        var finalJson = """{"text":"final speech","result":[{"conf":1.0,"start":1.0,"end":1.5,"word":"speech"}]}"""

        override fun setWords(words: Boolean) {}

        override fun acceptWaveForm(data: ByteArray, len: Int): Boolean {
            accepts.incrementAndGet()
            return returnEndpoint
        }

        override fun getResult(): String = resultJson
        override fun getPartialResult(): String = partialJson
        override fun getFinalResult(): String = finalJson
        override fun reset() {}

        override fun close() {
            closed.set(true)
            onClosed()
        }
    }

    class FakeModelHandle(
        private val onClose: () -> Unit = {}
    ) : VoskModelHandle {
        val recognizers = mutableListOf<FakeRecognizerHandle>()
        val closed = AtomicBoolean(false)

        override fun createRecognizer(sampleRate: Float): VoskRecognizerHandle {
            val r = FakeRecognizerHandle()
            recognizers.add(r)
            return r
        }

        override fun close() {
            closed.set(true)
            onClose()
        }
    }

    @Test
    fun `recognizer lifecycle emits partial then final on finish`() = runBlocking {
        val recognizer = FakeRecognizerHandle()
        val session = VoskBuiltinSession(recognizer, this)

        val eventsList = mutableListOf<LiveSpeechEvent>()
        val collectJob = launch {
            session.events.collect { eventsList.add(it) }
        }

        // Send PCM that doesn't trigger endpoint -> partial
        recognizer.returnEndpoint = false
        recognizer.partialJson = """{"partial":"testing"}"""
        session.sendAudio(ByteArray(320))

        // Wait a brief moment for background processor
        kotlinx.coroutines.delay(50)
        assertTrue(eventsList.any { it is LiveSpeechEvent.Partial && it.text == "testing" })

        // Send PCM that triggers endpoint -> final
        recognizer.returnEndpoint = true
        recognizer.resultJson = """{"text":"testing completed","result":[{"conf":1.0,"start":0.1,"end":0.5,"word":"testing"}]}"""
        session.sendAudio(ByteArray(320))

        kotlinx.coroutines.delay(50)
        val finalEvent = eventsList.filterIsInstance<LiveSpeechEvent.Final>().firstOrNull()
        assertNotNull(finalEvent)
        assertEquals("testing completed", finalEvent.text)
        assertEquals(1, finalEvent.words.size)
        assertEquals("testing", finalEvent.words[0].text)
        assertEquals(100L, finalEvent.words[0].startMillis)
        assertEquals(500L, finalEvent.words[0].endMillis)

        // Finish session flushes final result
        recognizer.finalJson = """{"text":"done"}"""
        session.finish()
        collectJob.join()

        assertTrue(recognizer.closed.get())
        assertTrue(eventsList.any { it is LiveSpeechEvent.Final && it.text == "done" })
    }

    @Test
    fun `builtin session runs recognition on a dedicated worker thread`() = runBlocking {
        val threadNames = java.util.Collections.synchronizedList(mutableListOf<String>())
        val recognizer = object : FakeRecognizerHandle() {
            override fun acceptWaveForm(data: ByteArray, len: Int): Boolean {
                threadNames.add(Thread.currentThread().name)
                return super.acceptWaveForm(data, len)
            }
        }
        val session = VoskBuiltinSession(recognizer)
        session.sendAudio(ByteArray(320))
        kotlinx.coroutines.delay(100)
        session.finish()

        assertTrue(threadNames.isNotEmpty())
        assertTrue(threadNames.all { it.startsWith("vosk-builtin-worker") })
    }

    @Test
    fun `builtin service caches models per language and closes them on shutdown`() = runBlocking {
        val manager = VoskModelManager({ tempDir })
        // Create model directory on disk
        val modelDir = File(manager.modelsDirectory, "vosk-model-small-en-us-0.15").apply { mkdirs() }
        File(modelDir, "conf.txt").writeText("test")

        val modelCreates = AtomicInteger(0)
        var lastCreatedModel: FakeModelHandle? = null

        val service = VoskBuiltinService(
            modelManager = manager,
            settings = {
                SpeechSettings(
                    liveEngine = LIVE_VOSK_BUILTIN,
                    voskBuiltinModel = "vosk-model-small-en-us-0.15"
                )
            },
            modelFactory = {
                modelCreates.incrementAndGet()
                FakeModelHandle().also { lastCreatedModel = it }
            }
        )

        // Validate succeeds because model is installed
        assertNull(service.validate().getError(), "Validate succeeds when model is installed")

        // Open first session
        val session1 = service.openSession(LanguageCode.ENGLISH).get()
        assertNotNull(session1)
        assertEquals(1, modelCreates.get(), "First session loads model")

        // Open second session for same language -> model is reused from cache
        val session2 = service.openSession(LanguageCode.ENGLISH).get()
        assertNotNull(session2)
        assertEquals(1, modelCreates.get(), "Second session reuses cached model")

        session1.cancel()
        session2.cancel()

        assertFalse(lastCreatedModel?.closed?.get() == true, "Model remains open after individual sessions end")

        service.close()
        assertTrue(lastCreatedModel?.closed?.get() == true, "Model closes when service is closed")
    }

    @Test
    fun `transcribeClip transcribes finished audio clip offline`() = runBlocking {
        val manager = VoskModelManager({ tempDir })
        File(manager.modelsDirectory, "vosk-model-small-en-us-0.15").apply {
            mkdirs()
            File(this, "README").writeText("fake model")
        }

        val fakeRecognizer = FakeRecognizerHandle().apply {
            finalJson = """{"text":"test offline speech","result":[{"conf":1.0,"start":0.1,"end":0.6,"word":"test"},{"conf":1.0,"start":0.7,"end":1.2,"word":"offline"}]}"""
        }

        val service = VoskBuiltinService(
            modelManager = manager,
            settings = {
                SpeechSettings(
                    mode = MODE_LOCAL,
                    liveEngine = LIVE_VOSK_BUILTIN,
                    voskBuiltinModel = "vosk-model-small-en-us-0.15"
                )
            },
            modelFactory = {
                object : VoskModelHandle {
                    override fun createRecognizer(sampleRate: Float): VoskRecognizerHandle = fakeRecognizer
                    override fun close() {}
                }
            }
        )

        val response = service.transcribeClip(ByteArray(1600), LanguageCode.ENGLISH).get()
        assertNotNull(response)
        assertEquals("test offline speech", response.text)
        assertEquals(LanguageCode.ENGLISH, response.detectedLanguage)
        assertEquals(2, response.words.size)
        assertEquals("test", response.words[0].text)
        assertTrue(fakeRecognizer.closed.get(), "Recognizer is closed after transcribeClip completes")
    }

    @Test
    fun `fixMojibake restores Windows-1252 corrupted UTF-8 Arabic text`() {
        val corrupted1 = "Ø¬Ø§ÙŠÙ‡ Ø³Ø§Ù„Ø¨ Ø¹Ø±Ø¨ÙŠ"
        assertEquals("جايه سالب عربي", fixMojibake(corrupted1))

        val plainEnglish = "hello world"
        assertEquals(plainEnglish, fixMojibake(plainEnglish))
    }
}

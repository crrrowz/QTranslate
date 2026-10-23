package com.github.ahatem.qtranslate.plugins.speech

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.plugin.ServiceMetadata
import com.github.ahatem.qtranslate.api.plugin.SupportedLanguages
import com.github.ahatem.qtranslate.api.speech.LiveSpeechEvent
import com.github.ahatem.qtranslate.api.speech.LiveSpeechRecognizer
import com.github.ahatem.qtranslate.api.speech.LiveSpeechSession
import com.github.ahatem.qtranslate.api.speech.RecognizedWord
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Interface abstraction for Vosk model handle to allow unit testing without loading native libraries.
 */
interface VoskModelHandle : AutoCloseable {
    fun createRecognizer(sampleRate: Float): VoskRecognizerHandle
}

/**
 * Interface abstraction for Vosk recognizer handle.
 */
interface VoskRecognizerHandle : AutoCloseable {
    fun setWords(words: Boolean)
    fun acceptWaveForm(data: ByteArray, len: Int): Boolean
    fun getResult(): String
    fun getPartialResult(): String
    fun getFinalResult(): String
    fun reset()
}

/** Real Vosk model backed by JNA native library. */
class RealVoskModelHandle(private val model: org.vosk.Model) : VoskModelHandle {
    override fun createRecognizer(sampleRate: Float): VoskRecognizerHandle {
        val recognizer = org.vosk.Recognizer(model, sampleRate)
        recognizer.setWords(true)
        return RealVoskRecognizerHandle(recognizer)
    }

    override fun close() {
        model.close()
    }
}

/** Real Vosk recognizer backed by JNA native library. */
class RealVoskRecognizerHandle(private val recognizer: org.vosk.Recognizer) : VoskRecognizerHandle {
    override fun setWords(words: Boolean) = recognizer.setWords(words)
    override fun acceptWaveForm(data: ByteArray, len: Int): Boolean = recognizer.acceptWaveForm(data, len)
    override fun getResult(): String = recognizer.result
    override fun getPartialResult(): String = recognizer.partialResult
    override fun getFinalResult(): String = recognizer.finalResult
    override fun reset() = recognizer.reset()
    override fun close() = recognizer.close()
}

/**
 * In-process live transcription powered by the bundled Vosk C++ engine.
 *
 * Runs locally without Python or Docker. Models are cached per language across sessions,
 * and individual recognizers are allocated per session and closed on finish/cancel.
 */
class VoskBuiltinService(
    private val modelManager: VoskModelManager,
    private val settings: () -> SpeechSettings,
    private val modelFactory: (File) -> VoskModelHandle = { dir ->
        org.vosk.LibVosk.setLogLevel(org.vosk.LogLevel.WARNINGS)
        RealVoskModelHandle(org.vosk.Model(dir.absolutePath))
    }
) : LiveSpeechRecognizer {
    override val metadata = ServiceMetadata(requiresConfiguration = false)
    override val key: String = "vosk-builtin"
    override val name: String = "Vosk (built-in)"
    override val version: String = "1.0.0"
    override val iconPath: String = "assets/speech.svg"
    override val supportedLanguages: SupportedLanguages
        get() {
            val installed = modelManager.listInstalledModels()
            val langs = installed.mapNotNull { dir ->
                VoskModelManager.MODELS_BY_NAME[dir.name]?.language
            }.distinct().map(::LanguageCode).toSet()
            return if (langs.isNotEmpty()) SupportedLanguages.Specific(langs)
            else SupportedLanguages.Specific(setOf(LanguageCode.ENGLISH))
        }

    private val modelCache = ConcurrentHashMap<String, VoskModelHandle>()

    override suspend fun openSession(language: LanguageCode): Result<LiveSpeechSession, ServiceError> {
        val current = settings()
        val preferredInfo = VoskModelManager.MODELS_BY_NAME[current.voskBuiltinModel]
        val lang = SpeechClient.primaryTag(language) ?: preferredInfo?.language ?: "en"

        val modelDir = modelManager.findModelForLanguage(lang, current.voskBuiltinModel)
            ?: return Err(
                ServiceError.ConfigurationError(
                    "No built-in Vosk model found for language '$lang' (${current.voskBuiltinModel}). " +
                        "Please download it in Settings > Plugins > Speech Recognition."
                )
            )

        val modelHandle = try {
            withContext(Dispatchers.IO) {
                modelCache.computeIfAbsent(modelDir.absolutePath) {
                    modelFactory(modelDir)
                }
            }
        } catch (t: Throwable) {
            return Err(ServiceError.ServiceUnavailableError("Failed to load Vosk model at ${modelDir.name}: ${t.message}", t))
        }

        val recognizer = try {
            modelHandle.createRecognizer(SAMPLE_RATE)
        } catch (t: Throwable) {
            return Err(ServiceError.ServiceUnavailableError("Failed to initialize Vosk recognizer: ${t.message}", t))
        }

        return Ok(VoskBuiltinSession(recognizer))
    }

    /**
     * Transcribes a finished audio clip locally using the in-process Vosk engine.
     * Used when running offline (Local only or Provider = None).
     */
    suspend fun transcribeClip(pcm: ByteArray, language: LanguageCode): Result<com.github.ahatem.qtranslate.api.speech.SpeechRecognitionResponse, ServiceError> {
        val current = settings()
        val preferredInfo = VoskModelManager.MODELS_BY_NAME[current.voskBuiltinModel]
        val lang = SpeechClient.primaryTag(language) ?: preferredInfo?.language ?: "en"

        val modelDir = modelManager.findModelForLanguage(lang, current.voskBuiltinModel)
            ?: return Err(
                ServiceError.ConfigurationError(
                    "No built-in Vosk model found for language '$lang' (${current.voskBuiltinModel}). " +
                        "Please download it in Settings > Plugins > Speech Recognition."
                )
            )

        val modelHandle = try {
            withContext(Dispatchers.IO) {
                modelCache.computeIfAbsent(modelDir.absolutePath) {
                    modelFactory(modelDir)
                }
            }
        } catch (t: Throwable) {
            return Err(ServiceError.ServiceUnavailableError("Failed to load Vosk model at ${modelDir.name}: ${t.message}", t))
        }

        val recognizer = try {
            modelHandle.createRecognizer(SAMPLE_RATE)
        } catch (t: Throwable) {
            return Err(ServiceError.ServiceUnavailableError("Failed to initialize Vosk recognizer: ${t.message}", t))
        }

        return try {
            withContext(Dispatchers.IO) {
                recognizer.acceptWaveForm(pcm, pcm.size)
                val finalJson = recognizer.getFinalResult()
                val parsed = parseVoskJson(finalJson)
                if (parsed is VoskParsedResult.Final) {
                    Ok(com.github.ahatem.qtranslate.api.speech.SpeechRecognitionResponse(text = parsed.text, detectedLanguage = LanguageCode(lang), words = parsed.words))
                } else if (parsed is VoskParsedResult.Partial) {
                    Ok(com.github.ahatem.qtranslate.api.speech.SpeechRecognitionResponse(text = parsed.text, detectedLanguage = LanguageCode(lang), words = emptyList()))
                } else {
                    Ok(com.github.ahatem.qtranslate.api.speech.SpeechRecognitionResponse(text = "", detectedLanguage = LanguageCode(lang), words = emptyList()))
                }
            }
        } finally {
            recognizer.close()
        }
    }

    fun preloadModel(modelName: String) {
        val model = VoskModelManager.MODELS_BY_NAME[modelName] ?: return
        val dir = File(modelManager.modelsDirectory, model.name)
        if (dir.isDirectory && (dir.list()?.isNotEmpty() == true)) {
            try {
                if (!modelCache.containsKey(dir.absolutePath)) {
                    modelCache.computeIfAbsent(dir.absolutePath) {
                        modelFactory(dir)
                    }
                }
            } catch (_: Throwable) {
                // Ignore background preload failures
            }
        }
    }

    override suspend fun validate(): Result<Unit, ServiceError> {
        val current = settings()
        val selectedModel = current.voskBuiltinModel
        if (!modelManager.isInstalled(selectedModel)) {
            val modelInfo = VoskModelManager.MODELS_BY_NAME[selectedModel]
            val desc = modelInfo?.label ?: selectedModel
            return Err(
                ServiceError.ConfigurationError(
                    "Built-in Vosk model '$desc' is not downloaded. Click 'Download Model' in Speech Recognition settings."
                )
            )
        }
        return Ok(Unit)
    }

    fun close() {
        modelCache.values.forEach { runCatching { it.close() } }
        modelCache.clear()
    }

    companion object {
        const val SAMPLE_RATE = 16_000.0f
    }
}

/**
 * One in-process live recognition session.
 *
 * Audio frames are queued through [audioQueue] and processed sequentially in background
 * on a dedicated single-threaded dispatcher to avoid blocking audio recording callbacks
 * or contending with shared dispatchers.
 */
class VoskBuiltinSession(
    private val recognizer: VoskRecognizerHandle,
    scope: CoroutineScope? = null
) : LiveSpeechSession {
    private val ownedExecutor: ExecutorService? = if (scope == null) {
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "vosk-builtin-worker").apply { isDaemon = true }
        }
    } else null

    private val sessionScope: CoroutineScope = scope ?: CoroutineScope(ownedExecutor!!.asCoroutineDispatcher())

    private val eventChannel = Channel<LiveSpeechEvent>(Channel.UNLIMITED)
    override val events: Flow<LiveSpeechEvent> = eventChannel.receiveAsFlow()

    private val audioQueue = Channel<ByteArray>(Channel.UNLIMITED)

    @Volatile
    private var ended = false

    private val processingJob: Job = sessionScope.launch {
        try {
            for (pcm in audioQueue) {
                if (ended) break
                val hasEndpoint = recognizer.acceptWaveForm(pcm, pcm.size)
                if (hasEndpoint) {
                    val resultJson = recognizer.getResult()
                    emitResult(resultJson)
                } else {
                    val partialJson = recognizer.getPartialResult()
                    emitPartial(partialJson)
                }
            }
        } catch (e: Exception) {
            if (!ended) {
                eventChannel.trySend(LiveSpeechEvent.Failed(ServiceError.UnknownError("Vosk error: ${e.message}", e)))
            }
        }
    }

    override fun sendAudio(pcm: ByteArray) {
        if (ended || pcm.isEmpty()) return
        audioQueue.trySend(pcm.copyOf())
    }

    override suspend fun finish() {
        if (ended) return
        ended = true
        audioQueue.close()
        processingJob.join()

        // Flush final result
        val finalJson = runCatching { recognizer.getFinalResult() }.getOrNull()
        if (!finalJson.isNullOrBlank()) {
            emitResult(finalJson)
        }

        cleanup()
    }

    override fun cancel() {
        if (ended) return
        ended = true
        audioQueue.close()
        processingJob.cancel()
        cleanup()
    }

    private fun cleanup() {
        eventChannel.close()
        runCatching { recognizer.close() }
        ownedExecutor?.shutdown()
    }

    private fun emitPartial(jsonString: String) {
        val parsed = parseVoskJson(jsonString)
        if (parsed is VoskParsedResult.Partial && parsed.text.isNotEmpty()) {
            eventChannel.trySend(LiveSpeechEvent.Partial(parsed.text))
        }
    }

    private fun emitResult(jsonString: String) {
        val parsed = parseVoskJson(jsonString)
        if (parsed is VoskParsedResult.Final && parsed.text.isNotEmpty()) {
            eventChannel.trySend(LiveSpeechEvent.Final(parsed.text, parsed.words))
        }
    }
}

internal sealed interface VoskParsedResult {
    data class Partial(val text: String) : VoskParsedResult
    data class Final(val text: String, val words: List<RecognizedWord>) : VoskParsedResult
    data object Empty : VoskParsedResult
}

private val jsonParser = Json { ignoreUnknownKeys = true; isLenient = true }

internal fun fixMojibake(raw: String): String {
    if (raw.isBlank()) return raw
    val looksLikeMojibake = raw.contains("Ø") || raw.contains("Ù") || raw.contains("Ã") || raw.contains("Â")
    if (!looksLikeMojibake) return raw
    return try {
        val bytes = raw.toByteArray(java.nio.charset.Charset.forName("Windows-1252"))
        val recovered = String(bytes, java.nio.charset.StandardCharsets.UTF_8)
        if (recovered.any { it in '\u0600'..'\u06FF' }) recovered else raw
    } catch (_: Exception) {
        raw
    }
}

/**
 * Parses either server-style (`{"type": "partial", "text": "..."}`) or native Vosk C library-style
 * (`{"partial": "..."}` and `{"text": "...", "result": [...]}`) messages.
 */
internal fun parseVoskJson(text: String): VoskParsedResult {
    val json = runCatching { jsonParser.parseToJsonElement(text).jsonObject }.getOrNull() ?: return VoskParsedResult.Empty
    fun JsonObject.string(key: String) = this[key]?.jsonPrimitive?.contentOrNull.orEmpty()

    // Partial: {"partial": "..."} or {"type": "partial", "text": "..."}
    if (json.containsKey("partial") || json.string("type") == "partial") {
        val partial = fixMojibake(json.string("partial").ifEmpty { json.string("text") }.trim())
        return if (partial.isNotEmpty()) VoskParsedResult.Partial(partial) else VoskParsedResult.Empty
    }

    // Final result: {"text": "...", "result": [...]} or {"type": "result", "text": "..."}
    if (json.containsKey("text") || json.string("type") == "result") {
        val finalText = fixMojibake(json.string("text").trim())
        val words = runCatching {
            json["result"]!!.jsonArray.mapNotNull { item ->
                val wordObj = item.jsonObject
                val start = wordObj["start"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
                val end = wordObj["end"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
                RecognizedWord(fixMojibake(wordObj.string("word")), (start * 1000).toLong(), (end * 1000).toLong())
            }
        }.getOrDefault(emptyList())
        return if (finalText.isNotEmpty()) VoskParsedResult.Final(finalText, words) else VoskParsedResult.Empty
    }

    return VoskParsedResult.Empty
}

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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Live speech recognition router service.
 *
 * Dispatches to the chosen engine: in-process built-in Vosk (offline),
 * Deepgram live streaming, or OpenAI Realtime streaming.
 */
class VoskLiveService(
    private val settings: () -> SpeechSettings,
    private val builtinService: LiveSpeechRecognizer? = null,
    private val deepgramService: LiveSpeechRecognizer? = null,
    private val openAiService: LiveSpeechRecognizer? = null
) : LiveSpeechRecognizer {
    constructor(settings: () -> SpeechSettings) : this(settings, null, null, null)

    override val metadata = ServiceMetadata()
    override val key: String = "vosk-live"
    override val name: String
        get() = when (settings().liveEngine) {
            LIVE_VOSK_BUILTIN -> "Vosk (built-in)"
            LIVE_DEEPGRAM -> "Deepgram (live)"
            LIVE_OPENAI -> "OpenAI Realtime"
            else -> "Vosk (live)"
        }
    override val version: String = "1.0.0"
    override val iconPath: String = "assets/speech.svg"
    override val supportedLanguages: SupportedLanguages = SupportedLanguages.Dynamic

    override suspend fun openSession(language: LanguageCode): Result<LiveSpeechSession, ServiceError> {
        val current = settings()
        if (current.mode == MODE_CLOUD) {
            return Err(ServiceError.ConfigurationError("Live text is off in Cloud only mode."))
        }
        if (current.mode == MODE_LOCAL && current.liveEngine != LIVE_VOSK_BUILTIN) {
            return Err(ServiceError.ConfigurationError("Only local engines are allowed in Local only mode."))
        }
        return when (current.liveEngine) {
            LIVE_VOSK_BUILTIN -> {
                val builtin = builtinService
                    ?: return Err(ServiceError.ConfigurationError("Built-in Vosk engine is not available."))
                builtin.openSession(language)
            }
            LIVE_DEEPGRAM -> {
                val deepgram = deepgramService
                    ?: return Err(ServiceError.ConfigurationError("Deepgram live engine is not available."))
                deepgram.openSession(language)
            }
            LIVE_OPENAI -> {
                val openAi = openAiService
                    ?: return Err(ServiceError.ConfigurationError("OpenAI Realtime engine is not available."))
                openAi.openSession(language)
            }
            else -> Err(ServiceError.ConfigurationError("Live text is off in Settings > Plugins > Speech Recognition."))
        }
    }

    override suspend fun validate(): Result<Unit, ServiceError> {
        val current = settings()
        if (current.mode == MODE_CLOUD) return Ok(Unit)
        if (current.mode == MODE_LOCAL && current.liveEngine != LIVE_VOSK_BUILTIN) {
            return Err(ServiceError.ConfigurationError("Only local engines are allowed in Local only mode."))
        }
        return when (current.liveEngine) {
            LIVE_VOSK_BUILTIN -> builtinService?.validate() ?: Ok(Unit)
            LIVE_DEEPGRAM -> deepgramService?.validate() ?: Ok(Unit)
            LIVE_OPENAI -> openAiService?.validate() ?: Ok(Unit)
            else -> Ok(Unit)
        }
    }
}

internal sealed interface VoskMessage {
    data object Ready : VoskMessage
    data class Error(val text: String) : VoskMessage
    data class Partial(val text: String) : VoskMessage
    data class Result(val text: String, val words: List<RecognizedWord>) : VoskMessage
    data object Stopped : VoskMessage
    data class Pong(val models: List<String>) : VoskMessage
    data object Other : VoskMessage
}

private val voskJson = Json { ignoreUnknownKeys = true; isLenient = true }

/**
 * Reads one message from a Vosk json payload.
 */
internal fun parseVoskMessage(text: String): VoskMessage {
    val json = runCatching { voskJson.parseToJsonElement(text).jsonObject }.getOrNull() ?: return VoskMessage.Other
    fun JsonObject.string(key: String) = this[key]?.jsonPrimitive?.contentOrNull.orEmpty()
    return when (json.string("type")) {
        "status" -> when (json.string("status")) {
            "ready" -> VoskMessage.Ready
            "error" -> VoskMessage.Error(json.string("msg"))
            "stopped" -> VoskMessage.Stopped
            "pong" -> VoskMessage.Pong(runCatching { json["models"]!!.jsonArray.map { it.jsonPrimitive.content } }.getOrDefault(emptyList()))
            else -> VoskMessage.Other
        }
        "partial" -> VoskMessage.Partial(json.string("text").trim())
        "result" -> VoskMessage.Result(
            json.string("text").trim(),
            runCatching {
                json["result"]!!.jsonArray.mapNotNull { item ->
                    val word = item.jsonObject
                    val start = word["start"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
                    val end = word["end"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
                    RecognizedWord(word.string("word"), (start * 1000).toLong(), (end * 1000).toLong())
                }
            }.getOrDefault(emptyList())
        )
        else -> VoskMessage.Other
    }
}

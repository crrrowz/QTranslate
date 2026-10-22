package com.github.ahatem.qtranslate.plugins.speech

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.HttpClient
import com.github.ahatem.qtranslate.api.plugin.MultipartHttpClient
import com.github.ahatem.qtranslate.api.plugin.MultipartPart
import com.github.ahatem.qtranslate.api.plugin.PluginContext
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.speech.RecognizedWord
import com.github.ahatem.qtranslate.api.speech.SpeechAudio
import com.github.ahatem.qtranslate.api.speech.SpeechRecognitionRequest
import com.github.ahatem.qtranslate.api.speech.SpeechRecognitionResponse
import com.github.ahatem.qtranslate.plugins.common.createJsonParser
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.andThen
import com.github.michaelbull.result.getError
import com.github.michaelbull.result.map
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.nio.ByteBuffer
import java.util.Base64
import java.nio.ByteOrder
import java.util.Locale

/** Talks to whichever provider the settings name. */
class SpeechClient(
    context: PluginContext,
    private val http: HttpClient,
    private val settings: () -> SpeechSettings
) {
    private val logger = context.logger
    private val openAiParser = createJsonParser<OpenAiTranscription>(context)
    private val deepgramParser = createJsonParser<DeepgramResponse>(context)
    private val chatParser = createJsonParser<ChatReply>(context)

    suspend fun transcribe(request: SpeechRecognitionRequest): Result<SpeechRecognitionResponse, ServiceError> {
        val current = settings()
        current.configurationError()?.let { return Err(it) }
        val uploads = http as? MultipartHttpClient
            ?: return Err(ServiceError.ConfigurationError("This version of QTranslate cannot upload audio. Update the app."))

        val language = primaryTag(request.language)
        return when (current.speechProvider.protocol) {
            SpeechProtocol.OPENAI_COMPATIBLE -> openAi(uploads, current, request, language)
            SpeechProtocol.DEEPGRAM -> deepgram(uploads, current, request.audio, language)
            SpeechProtocol.CHAT_AUDIO -> chatAudio(current, request, language)
        }.map { response ->
            response.copy(detectedLanguage = request.language.takeUnless { it == LanguageCode.AUTO } ?: response.detectedLanguage)
        }
    }

    /** One second of silence: answered with an empty transcript by any working provider. */
    suspend fun testConnection(): Result<Unit, ServiceError> =
        transcribe(SpeechRecognitionRequest(SpeechAudio(silentWav(1_000)))).map { }

    // -------------------------------------------------------------------------------------------
    // OpenAI-compatible
    // -------------------------------------------------------------------------------------------

    private suspend fun openAi(
        uploads: MultipartHttpClient,
        settings: SpeechSettings,
        request: SpeechRecognitionRequest,
        language: String?
    ): Result<SpeechRecognitionResponse, ServiceError> {
        // Word timings let the host replay a single word, but not every server offers the verbose
        // format (gpt-4o-transcribe and some local servers reject it). One retry in the plain
        // format keeps those working, just without timings.
        val verbose = openAiRequest(uploads, settings, request, language, verbose = true)
        val error = verbose.getError() ?: return verbose
        if (error is ServiceError.AuthenticationError || error is ServiceError.NetworkError ||
            error is ServiceError.RateLimitError || error is ServiceError.TimeoutError
        ) return verbose
        logger.debug("Verbose transcription refused (${error.message}); retrying in plain JSON")
        return openAiRequest(uploads, settings, request, language, verbose = false)
    }

    private suspend fun openAiRequest(
        uploads: MultipartHttpClient,
        settings: SpeechSettings,
        request: SpeechRecognitionRequest,
        language: String?,
        verbose: Boolean
    ): Result<SpeechRecognitionResponse, ServiceError> {
        val parts = buildList {
            add(MultipartPart.File("file", "speech.${request.audio.encoding.extension}", request.audio.encoding.mimeType, request.audio.bytes))
            add(MultipartPart.Text("model", settings.effectiveModel))
            add(MultipartPart.Text("temperature", "0"))
            if (verbose) {
                add(MultipartPart.Text("response_format", "verbose_json"))
                add(MultipartPart.Text("timestamp_granularities[]", "word"))
            } else {
                add(MultipartPart.Text("response_format", "json"))
            }
            language?.let { add(MultipartPart.Text("language", it)) }
            // Whisper reads only the last 224 tokens of a prompt; the tail is what matters.
            request.prompt?.takeIf { it.isNotBlank() }?.let { add(MultipartPart.Text("prompt", it.takeLast(MAX_PROMPT_CHARS))) }
        }
        return uploads.postMultipart(
            url = "${settings.effectiveBaseUrl}/audio/transcriptions",
            parts = parts,
            headers = authorization(settings, "Bearer")
        ).andThen { openAiParser.parse(it) }.andThen { body ->
            body.error?.let { return@andThen Err(ServiceError.InvalidResponseError(it.message)) }
            Ok(body.toResponse())
        }
    }

    // -------------------------------------------------------------------------------------------
    // Deepgram
    // -------------------------------------------------------------------------------------------

    private suspend fun deepgram(
        uploads: MultipartHttpClient,
        settings: SpeechSettings,
        audio: SpeechAudio,
        language: String?
    ): Result<SpeechRecognitionResponse, ServiceError> {
        val query = buildMap<String, Any?> {
            put("model", settings.effectiveModel)
            put("smart_format", "true")
            put("punctuate", "true")
            if (language != null) put("language", language) else put("detect_language", "true")
        }
        return uploads.postBytes(
            url = "${settings.effectiveBaseUrl}/listen",
            body = audio.bytes,
            contentType = audio.encoding.mimeType,
            headers = authorization(settings, "Token"),
            queryParams = query
        ).andThen { deepgramParser.parse(it) }.map { it.toResponse() }
    }

    // -------------------------------------------------------------------------------------------
    // Multimodal chat model
    // -------------------------------------------------------------------------------------------

    private suspend fun chatAudio(
        settings: SpeechSettings,
        request: SpeechRecognitionRequest,
        language: String?
    ): Result<SpeechRecognitionResponse, ServiceError> {
        val instruction = buildString {
            append("Transcribe the speech in this audio exactly as spoken, in the language it is spoken in. ")
            append("Do not translate, summarise or explain. Reply with the transcript only, without quotes. ")
            append("If there is no speech, reply with nothing.")
            language?.let { append(" The speaker is expected to speak language code '$it'.") }
            request.prompt?.takeIf { it.isNotBlank() }?.let {
                append(" For spelling of names and terms, the conversation so far was: ")
                append(it.takeLast(MAX_PROMPT_CHARS))
            }
        }
        val body = buildJsonObject {
            put("model", settings.effectiveModel)
            put("temperature", 0)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("content") {
                        addJsonObject { put("type", "text"); put("text", instruction) }
                        addJsonObject {
                            put("type", "input_audio")
                            putJsonObject("input_audio") {
                                put("data", Base64.getEncoder().encodeToString(request.audio.bytes))
                                put("format", request.audio.encoding.extension)
                            }
                        }
                    }
                }
            }
        }
        return http.post(
            url = "${settings.effectiveBaseUrl}/chat/completions",
            headers = authorization(settings, "Bearer") + ("Content-Type" to "application/json"),
            body = body.toString()
        ).andThen { chatParser.parse(it) }.andThen { reply ->
            reply.error?.let { return@andThen Err(ServiceError.InvalidResponseError(it.message)) }
            val text = reply.choices.firstOrNull()?.message?.content.orEmpty().trim().trim('"', '“', '”').trim()
            Ok(SpeechRecognitionResponse(text = text))
        }
    }

    private fun authorization(settings: SpeechSettings, scheme: String): Map<String, String> =
        settings.apiKey.trim().takeIf { it.isNotEmpty() }?.let { mapOf("Authorization" to "$scheme $it") }.orEmpty()

    internal companion object {
        const val MAX_PROMPT_CHARS = 600

        /** The ISO-639-1 code providers take, or null to let them detect the language. */
        fun primaryTag(language: LanguageCode): String? =
            language.takeUnless { it == LanguageCode.AUTO }
                ?.tag
                ?.substringBefore('-')
                ?.lowercase()
                ?.takeIf { it.length in 2..3 }

        /**
         * Whisper's verbose format names the language in English ("english", "arabic") rather
         * than coding it. Matched against the JDK's own English language names.
         */
        fun languageFromName(name: String?): LanguageCode? {
            val wanted = name?.trim()?.lowercase().orEmpty()
            if (wanted.isEmpty()) return null
            if (wanted.length in 2..3) return LanguageCode(wanted)
            return Locale.getISOLanguages()
                .firstOrNull { Locale(it).getDisplayLanguage(Locale.ENGLISH).lowercase() == wanted }
                ?.let(::LanguageCode)
        }

        fun silentWav(millis: Int, sampleRate: Int = 16_000): ByteArray {
            val dataBytes = sampleRate * millis / 1000 * 2
            return ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray()); putInt(36 + dataBytes); put("WAVE".toByteArray())
                put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
                putInt(sampleRate); putInt(sampleRate * 2); putShort(2); putShort(16)
                put("data".toByteArray()); putInt(dataBytes)
            }.array()
        }

        internal fun OpenAiTranscription.toResponse() = SpeechRecognitionResponse(
            text = text.orEmpty().trim(),
            detectedLanguage = languageFromName(language),
            words = words.orEmpty().toRecognized { Triple(it.word, it.start, it.end) }
        )

        internal fun DeepgramResponse.toResponse(): SpeechRecognitionResponse {
            val channel = results?.channels?.firstOrNull()
            val best = channel?.alternatives?.firstOrNull()
            return SpeechRecognitionResponse(
                text = best?.transcript.orEmpty().trim(),
                detectedLanguage = channel?.detectedLanguage?.takeIf { it.isNotBlank() }?.let(::LanguageCode),
                words = best?.words.orEmpty().toRecognized { Triple(it.punctuatedWord ?: it.word, it.start, it.end) }
            )
        }

        private fun <T> List<T>.toRecognized(fields: (T) -> Triple<String, Double, Double>): List<RecognizedWord> =
            mapNotNull { item ->
                val (word, start, end) = fields(item)
                if (word.isBlank()) return@mapNotNull null
                val startMillis = (start * 1000).toLong().coerceAtLeast(0)
                RecognizedWord(word.trim(), startMillis, (end * 1000).toLong().coerceAtLeast(startMillis))
            }
    }
}

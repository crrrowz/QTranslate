package com.github.ahatem.qtranslate.plugins.ai

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.plugin.ServiceMetadata
import com.github.ahatem.qtranslate.api.plugin.SupportedLanguages
import com.github.ahatem.qtranslate.api.speech.RecognizedWord
import com.github.ahatem.qtranslate.api.speech.SpeechRecognitionRequest
import com.github.ahatem.qtranslate.api.speech.SpeechRecognitionResponse
import com.github.ahatem.qtranslate.api.speech.SpeechRecognizer
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.map

/**
 * Speech-to-text through any OpenAI-compatible `/audio/transcriptions` endpoint.
 *
 * Whisper-family models recognise close to a hundred languages and detect which one is spoken,
 * so this declares [SupportedLanguages.All] and passes only the primary language subtag on —
 * the API takes ISO-639-1 codes, and `zh-Hans` or `pt-BR` would be rejected as written.
 */
class AISpeechRecognizerService(
    private val client: AIServiceClient
) : SpeechRecognizer {

    override val metadata = ServiceMetadata(requiresConfiguration = true)

    override val key: String = "ai-speech"
    override val name: String = "AI Speech Recognition"
    override val version: String = "1.0.0"
    override val iconPath: String = "assets/ai-icon.svg"
    override val supportedLanguages: SupportedLanguages = SupportedLanguages.All

    override suspend fun transcribe(
        request: SpeechRecognitionRequest
    ): Result<SpeechRecognitionResponse, ServiceError> =
        client.transcribe(
            audio    = request.audio,
            language = whisperLanguage(request.language),
            prompt   = request.prompt
        ).map { response ->
            SpeechRecognitionResponse(
                text = response.text.orEmpty(),
                detectedLanguage = request.language.takeUnless { it == LanguageCode.AUTO }
                    ?: response.language?.let(::languageFromName),
                words = response.words.orEmpty()
                    .filter { it.word.isNotBlank() }
                    .map { word ->
                        val start = (word.start * 1000).toLong().coerceAtLeast(0)
                        RecognizedWord(
                            text = word.word.trim(),
                            startMillis = start,
                            endMillis = (word.end * 1000).toLong().coerceAtLeast(start)
                        )
                    }
            )
        }

    internal companion object {
        /**
         * Whisper's verbose format names the language in English ("english", "arabic") rather
         * than coding it. Matched against the JDK's own English language names.
         */
        fun languageFromName(name: String): LanguageCode? {
            val wanted = name.trim().lowercase()
            if (wanted.isEmpty()) return null
            if (wanted.length in 2..3) return LanguageCode(wanted)
            return java.util.Locale.getISOLanguages()
                .firstOrNull { java.util.Locale(it).getDisplayLanguage(java.util.Locale.ENGLISH).lowercase() == wanted }
                ?.let(::LanguageCode)
        }

        /** The ISO-639-1 code Whisper expects, or null to let it detect the language. */
        fun whisperLanguage(language: LanguageCode): String? =
            language.takeUnless { it == LanguageCode.AUTO }
                ?.tag
                ?.substringBefore('-')
                ?.lowercase()
                ?.takeIf { it.length in 2..3 }
    }
}

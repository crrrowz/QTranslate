package com.github.ahatem.qtranslate.plugins.speech

/** The wire format a provider speaks. */
enum class SpeechProtocol {
    /** `POST {baseUrl}/audio/transcriptions` as multipart — OpenAI, Groq and every Whisper server. */
    OPENAI_COMPATIBLE,

    /** `POST {baseUrl}/listen` with the audio as the body — Deepgram. */
    DEEPGRAM,

    /**
     * `POST {baseUrl}/chat/completions` with the audio as an `input_audio` part, asking the model
     * to transcribe it. For multimodal chat models (Gemini, gpt-4o-audio) behind an
     * OpenAI-compatible gateway, which offer no transcription endpoint. No word timings.
     */
    CHAT_AUDIO
}

/**
 * A speech-to-text provider the user can pick, with the address and model it uses unless the
 * user overrides them. Leaving the URL or model blank in settings means "the provider's default",
 * so switching provider never leaves a stale address behind.
 */
enum class SpeechProvider(
    val label: String,
    val protocol: SpeechProtocol,
    val defaultBaseUrl: String,
    val defaultModel: String,
    /** Whether the provider refuses requests without a key. Local servers usually don't. */
    val requiresKey: Boolean,
    val keyHint: String,
    val supportsBatch: Boolean = true,
    val supportsRealtime: Boolean = false,
    val supportsWordTimings: Boolean = true,
    val supportsLanguageDetection: Boolean = true
) {
    /** No accurate recognizer: the live text is the transcript. Nothing is sent anywhere. */
    NONE(
        "None (live text only)", SpeechProtocol.OPENAI_COMPATIBLE,
        "", "", false,
        "not needed",
        supportsBatch = false, supportsRealtime = false, supportsWordTimings = false, supportsLanguageDetection = false
    ),
    GROQ(
        "Groq", SpeechProtocol.OPENAI_COMPATIBLE,
        "https://api.groq.com/openai/v1", "whisper-large-v3-turbo", true,
        "console.groq.com > API Keys",
        supportsBatch = true, supportsRealtime = false, supportsWordTimings = true, supportsLanguageDetection = true
    ),
    OPENAI(
        "OpenAI", SpeechProtocol.OPENAI_COMPATIBLE,
        "https://api.openai.com/v1", "whisper-1", true,
        "platform.openai.com > API keys",
        supportsBatch = true, supportsRealtime = true, supportsWordTimings = true, supportsLanguageDetection = true
    ),
    DEEPGRAM(
        "Deepgram", SpeechProtocol.DEEPGRAM,
        "https://api.deepgram.com/v1", "nova-3", true,
        "console.deepgram.com > API Keys",
        supportsBatch = true, supportsRealtime = true, supportsWordTimings = true, supportsLanguageDetection = true
    ),
    LOCAL(
        "Local Whisper server", SpeechProtocol.OPENAI_COMPATIBLE,
        "http://localhost:8000/v1", "Systran/faster-whisper-large-v3", false,
        "usually none",
        supportsBatch = true, supportsRealtime = false, supportsWordTimings = true, supportsLanguageDetection = true
    ),
    CUSTOM(
        "Custom (OpenAI-compatible)", SpeechProtocol.OPENAI_COMPATIBLE,
        "", "whisper-1", false,
        "whatever the server expects",
        supportsBatch = true, supportsRealtime = false, supportsWordTimings = true, supportsLanguageDetection = true
    ),
    CHAT_MODEL(
        "Audio chat model (OpenAI-compatible)", SpeechProtocol.CHAT_AUDIO,
        "", "", false,
        "whatever the gateway expects",
        supportsBatch = true, supportsRealtime = false, supportsWordTimings = false, supportsLanguageDetection = true
    );

    companion object {
        /** The `options` string of the provider dropdown, in display order. */
        const val DROPDOWN_OPTIONS =
            "None (live text only),Groq,OpenAI,Deepgram,Local Whisper server,Custom (OpenAI-compatible),Audio chat model (OpenAI-compatible)"

        fun fromLabel(label: String?): SpeechProvider = entries.firstOrNull { it.label == label } ?: GROQ
    }
}

/**
 * Realtime / live streaming speech engines available in the Live text group.
 */
enum class LiveEngine(
    val label: String,
    val requiresKey: Boolean,
    val supportsWordTimings: Boolean,
    val supportsLanguageDetection: Boolean
) {
    OFF(LIVE_OFF, requiresKey = false, supportsWordTimings = false, supportsLanguageDetection = false),
    VOSK_BUILTIN(LIVE_VOSK_BUILTIN, requiresKey = false, supportsWordTimings = true, supportsLanguageDetection = false),
    VOSK_SERVER(LIVE_VOSK_SERVER, requiresKey = false, supportsWordTimings = true, supportsLanguageDetection = false),
    DEEPGRAM(LIVE_DEEPGRAM, requiresKey = true, supportsWordTimings = true, supportsLanguageDetection = true),
    OPENAI_REALTIME(LIVE_OPENAI, requiresKey = true, supportsWordTimings = false, supportsLanguageDetection = true);

    companion object {
        fun fromLabel(label: String?): LiveEngine = entries.firstOrNull { it.label == label } ?: OFF
        val DROPDOWN_OPTIONS: String = entries.joinToString(",") { it.label }
    }
}

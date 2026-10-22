package com.github.ahatem.qtranslate.plugins.speech

/** The wire format a provider speaks. */
enum class SpeechProtocol {
    /** `POST {baseUrl}/audio/transcriptions` as multipart — OpenAI, Groq and every Whisper server. */
    OPENAI_COMPATIBLE,

    /** `POST {baseUrl}/listen` with the audio as the body — Deepgram. */
    DEEPGRAM
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
    val keyHint: String
) {
    GROQ(
        "Groq", SpeechProtocol.OPENAI_COMPATIBLE,
        "https://api.groq.com/openai/v1", "whisper-large-v3-turbo", true,
        "console.groq.com > API Keys"
    ),
    OPENAI(
        "OpenAI", SpeechProtocol.OPENAI_COMPATIBLE,
        "https://api.openai.com/v1", "whisper-1", true,
        "platform.openai.com > API keys"
    ),
    DEEPGRAM(
        "Deepgram", SpeechProtocol.DEEPGRAM,
        "https://api.deepgram.com/v1", "nova-3", true,
        "console.deepgram.com > API Keys"
    ),
    LOCAL(
        "Local Whisper server", SpeechProtocol.OPENAI_COMPATIBLE,
        "http://localhost:8000/v1", "Systran/faster-whisper-large-v3", false,
        "usually none"
    ),
    CUSTOM(
        "Custom (OpenAI-compatible)", SpeechProtocol.OPENAI_COMPATIBLE,
        "", "whisper-1", false,
        "whatever the server expects"
    );

    companion object {
        /** The `options` string of the provider dropdown, in display order. */
        const val DROPDOWN_OPTIONS = "Groq,OpenAI,Deepgram,Local Whisper server,Custom (OpenAI-compatible)"

        fun fromLabel(label: String?): SpeechProvider = entries.firstOrNull { it.label == label } ?: GROQ
    }
}

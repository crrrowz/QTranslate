package com.github.ahatem.qtranslate.plugins.speech

import com.github.ahatem.qtranslate.api.plugin.PluginSettings
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.settings.Setting
import com.github.ahatem.qtranslate.api.settings.SettingType
import java.net.URI

data class SpeechSettings(
    @field:Setting(
        label = "Provider",
        description = "Groq is fast and has a free tier. A local Whisper server keeps audio on your computer. " +
            "Custom works with any server that offers an OpenAI-style /audio/transcriptions endpoint. " +
            "Audio chat model sends the audio to a multimodal chat model (Gemini, gpt-4o-audio) through " +
            "an OpenAI-compatible gateway's /chat/completions.",
        type = SettingType.DROPDOWN,
        options = SpeechProvider.DROPDOWN_OPTIONS,
        defaultValue = "Groq",
        order = 10
    )
    var provider: String = SpeechProvider.GROQ.label,

    @field:Setting(
        label = "Server URL",
        description = "Leave blank for the provider's own address. Required for Custom and Audio chat model.",
        type = SettingType.TEXT,
        order = 20
    )
    var baseUrl: String = "",

    @field:Setting(
        label = "API Key",
        description = "Required by Groq, OpenAI and Deepgram. Usually left blank for a local server.",
        type = SettingType.PASSWORD,
        order = 30
    )
    var apiKey: String = "",

    @field:Setting(
        label = "Model",
        description = "Leave blank for the provider's default (Groq: whisper-large-v3-turbo, OpenAI: whisper-1, " +
            "Deepgram: nova-3). Required for Audio chat model.",
        type = SettingType.TEXT,
        order = 40
    )
    var model: String = "",

    @field:Setting(
        label = "Live server URL (Vosk)",
        description = "Optional. A Vosk WebSocket server such as ws://localhost:8765 shows text while people are " +
            "still talking. With a provider above too, its more accurate text replaces each live line.",
        type = SettingType.TEXT,
        order = 50
    )
    var liveServerUrl: String = "",

    @field:Setting(
        label = "Live language for Auto-Detect",
        description = "Vosk cannot detect the language, so this model is used when a language is set to " +
            "Auto-Detect in the conversation window (for example en or ar).",
        type = SettingType.TEXT,
        defaultValue = "en",
        order = 60
    )
    var liveAutoLanguage: String = "en"
) : PluginSettings.Configurable() {

    val speechProvider: SpeechProvider get() = SpeechProvider.fromLabel(provider)

    /** The address requests go to: the user's, or the provider's when left blank. */
    val effectiveBaseUrl: String get() = baseUrl.trim().trimEnd('/').ifEmpty { speechProvider.defaultBaseUrl }

    val effectiveModel: String get() = model.trim().ifEmpty { speechProvider.defaultModel }

    /** What is wrong with the address, or null when it is usable. */
    fun urlError(): ServiceError? {
        val url = effectiveBaseUrl
        if (url.isEmpty()) {
            return ServiceError.ConfigurationError(
                "Speech Recognition needs a Server URL for ${speechProvider.label}. Set it in Settings > Plugins > Speech Recognition."
            )
        }
        val uri = runCatching { URI(url) }.getOrNull()
        if (uri?.scheme !in setOf("http", "https") || uri?.host.isNullOrBlank()) {
            return ServiceError.ConfigurationError("Speech Recognition Server URL is not a valid http(s) address: $url")
        }
        return null
    }

    /** Why requests would fail before any is sent, or null when the settings are usable. */
    fun configurationError(): ServiceError? {
        urlError()?.let { return it }
        if (effectiveModel.isEmpty()) {
            return ServiceError.ConfigurationError(
                "Speech Recognition needs a Model for ${speechProvider.label}. Set it in Settings > Plugins > Speech Recognition."
            )
        }
        val host = URI(effectiveBaseUrl).host
        if (speechProvider.requiresKey && apiKey.isBlank() && !isLocal(host)) {
            return ServiceError.ConfigurationError(
                "Speech Recognition has no API key for ${speechProvider.label} (${speechProvider.keyHint}). " +
                    "Add it in Settings > Plugins > Speech Recognition."
            )
        }
        return null
    }

    private fun isLocal(host: String): Boolean =
        host == "localhost" || host == "127.0.0.1" || host == "::1" || host == "[::1]" ||
            host.endsWith(".local") || host.startsWith("192.168.") || host.startsWith("10.")
}

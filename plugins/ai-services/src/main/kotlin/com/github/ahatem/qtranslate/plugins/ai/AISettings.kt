package com.github.ahatem.qtranslate.plugins.ai

import com.github.ahatem.qtranslate.api.plugin.PluginSettings
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.settings.Setting
import com.github.ahatem.qtranslate.api.settings.SettingGroup
import com.github.ahatem.qtranslate.api.settings.SettingGroups
import com.github.ahatem.qtranslate.api.settings.SettingType

/**
 * User-configurable settings for the AI Plugin.
 *
 * The plugin targets any **OpenAI-compatible** chat completions endpoint, which covers:
 * - **OpenRouter** (default) — unified gateway to 300+ models via a single API key
 *   (`https://openrouter.ai/api/v1`). Recommended for most users.
 * - **OpenAI** — `https://api.openai.com/v1`
 * - **Mistral** — `https://api.mistral.ai/v1`
 * - **Gemini** — `https://generativelanguage.googleapis.com/v1beta/openai`
 * - **Local Ollama** — `http://localhost:11434/v1`
 * - **Azure OpenAI**, LM Studio, and any other OpenAI-compatible server.
 *
 * Anthropic's native `/v1/messages` API is no longer supported directly; use
 * OpenRouter (`anthropic/claude-3-5-sonnet`) to access Anthropic models.
 *
 * Settings are organized into three groups visible in the plugin settings dialog:
 * - **Endpoint** — base URL and API key
 * - **Model** — model identifier
 * - **Advanced** — temperature, max tokens, custom headers (collapsed by default)
 */
@SettingGroups(
    SettingGroup(key = "endpoint", title = "Endpoint",         order = 10),
    SettingGroup(key = "model",    title = "Model",            order = 20),
    SettingGroup(key = "speech",   title = "Speech Recognition", order = 25,
                 collapsible = true, defaultCollapsed = true),
    SettingGroup(key = "advanced", title = "Advanced",         order = 30,
                 collapsible = true, defaultCollapsed = true)
)
data class AISettings(

    @field:Setting(
        label       = "Base URL",
        description = "OpenAI-compatible endpoint. Default: OpenRouter — a single key gives access to 300+ models. " +
                "You can also point this at OpenAI (https://api.openai.com/v1), " +
                "Mistral (https://api.mistral.ai/v1), " +
                "Gemini (https://generativelanguage.googleapis.com/v1beta/openai), " +
                "local Ollama (http://localhost:11434/v1), or any compatible server.",
        type         = SettingType.TEXT,
        defaultValue = "https://openrouter.ai/api/v1",
        group        = "endpoint",
        order        = 10
    )
    var baseUrl: String = "https://openrouter.ai/api/v1",

    @field:Setting(
        label       = "API Key",
        description = "Your API key for the selected endpoint. " +
                "Get an OpenRouter key at openrouter.ai/keys. " +
                "Leave blank for a local endpoint such as Ollama or LM Studio, which need none.",
        type        = SettingType.PASSWORD,
        // Not marked required, though a hosted endpoint does need one. The dialog refuses to save
        // while a required field is blank, and that made a local endpoint impossible to configure
        // at all — the field said "leave blank for Ollama" and then would not let you. Whether a
        // key is needed depends on the endpoint, which an annotation cannot express, so the check
        // moved to where the endpoint is known: see [missingKeyError].
        isRequired  = false,
        group       = "endpoint",
        order       = 20
    )
    var apiKey: String = "",

    @field:Setting(
        label       = "Model",
        description = "Exact model slug for your provider. For OpenRouter, use openrouter/free to " +
                "automatically select an available free model, or copy a current slug from openrouter.ai/models. " +
                "A 404 usually means that the model slug was renamed or removed. " +
                "Direct provider examples: gpt-4o · gemini-2.5-flash · mistral-small-latest.",
        type         = SettingType.TEXT,
        defaultValue = "openrouter/free",
        isRequired   = true,
        group        = "model",
        order        = 10
    )
    var model: String = "openrouter/free",

    @field:Setting(
        label       = "Temperature",
        description = "Controls output randomness. 0.0 = deterministic, 2.0 = very creative. " +
                "Recommended: 0.2 for translation/spell-check, 0.7 for summarization/rewriting.",
        type         = SettingType.SLIDER,
        defaultValue = "0.3",
        minValue     = 0.0,
        maxValue     = 2.0,
        step         = 0.05,
        group        = "advanced",
        order        = 10
    )
    var temperature: Double = 0.3,

    @field:Setting(
        label       = "Max Tokens",
        description = "Maximum number of tokens the model may generate. " +
                "Increase for very long texts; decrease to reduce cost.",
        type         = SettingType.NUMBER,
        defaultValue = "4096",
        minValue     = 1.0,
        maxValue     = 131072.0,
        step         = 256.0,
        group        = "advanced",
        order        = 20
    )
    var maxTokens: Int = 4096,

    @field:Setting(
        label       = "Custom Headers (JSON)",
        description = "Optional extra HTTP headers sent with every request, as a JSON object. " +
                "The defaults below add OpenRouter site-attribution headers (harmless with other providers). " +
                "Leave blank to send no extra headers.",
        type         = SettingType.TEXTAREA,
        defaultValue = """{"HTTP-Referer": "https://github.com/ahatem/QTranslate", "X-Title": "QTranslate", "X-OpenRouter-Title": "QTranslate"}""",
        rows         = 4,
        group        = "advanced",
        order        = 30
    )
    var customHeaders: String = """{"HTTP-Referer": "https://github.com/ahatem/QTranslate", "X-Title": "QTranslate", "X-OpenRouter-Title": "QTranslate"}""",

    @field:Setting(
        label       = "Speech Base URL",
        description = "OpenAI-compatible endpoint that serves /audio/transcriptions, used by Live Conversation. " +
                "OpenRouter does not transcribe audio, so set one of: " +
                "Groq (https://api.groq.com/openai/v1, fast with a free tier), " +
                "OpenAI (https://api.openai.com/v1), " +
                "or a local Whisper server such as speaches or whisper.cpp (http://localhost:8000/v1). " +
                "Leave blank to use the Base URL above.",
        type        = SettingType.TEXT,
        isRequired  = false,
        group       = "speech",
        order       = 10
    )
    var speechBaseUrl: String = "",

    @field:Setting(
        label       = "Speech API Key",
        description = "Key for the speech endpoint. Leave blank to reuse the API Key above, " +
                "or for a local server that needs none.",
        type        = SettingType.PASSWORD,
        isRequired  = false,
        group       = "speech",
        order       = 20
    )
    var speechApiKey: String = "",

    @field:Setting(
        label       = "Speech Model",
        description = "Transcription model. Groq: whisper-large-v3-turbo · OpenAI: gpt-4o-mini-transcribe or whisper-1 · " +
                "local servers: whatever model they load.",
        type         = SettingType.TEXT,
        defaultValue = "whisper-large-v3-turbo",
        isRequired   = false,
        group        = "speech",
        order        = 30
    )
    var speechModel: String = "whisper-large-v3-turbo"

) : PluginSettings.Configurable() {

    /**
     * Whether [baseUrl] points at something running on this machine or network.
     *
     * A local server needs no API key, so requiring one would make Ollama and LM Studio
     * unusable. Matched on the host rather than on a list of known products, since anyone can
     * run an OpenAI-compatible server on any port.
     *
     * Deliberately generous: a private-network address counts, because a model served from
     * another machine on the same LAN is the same privacy story as one served from this one.
     */
    val isLocalEndpoint: Boolean
        get() = isLocalHost(baseUrl)

    private fun isLocalHost(url: String): Boolean =
        runCatching {
            val host = java.net.URI(url.trim()).host?.lowercase() ?: return@runCatching false
            host == "localhost" ||
                host == "::1" ||
                host.endsWith(".local") ||
                host.startsWith("127.") ||
                host.startsWith("10.") ||
                host.startsWith("192.168.") ||
                Regex("""^172\.(1[6-9]|2\d|3[01])\.""").containsMatchIn(host)
        }.getOrDefault(false)

    /**
     * The error to report when no key is set, or null when none is needed.
     *
     * A hosted endpoint without a key is "finish setting this up", which is a different problem
     * from a key the provider rejected, and the two deserve different messages. A local endpoint
     * without a key is not a problem at all.
     */
    /** Where speech is sent: [speechBaseUrl], or [baseUrl] when that is left blank. */
    val effectiveSpeechBaseUrl: String
        get() = speechBaseUrl.trim().ifBlank { baseUrl.trim() }

    /** The key sent with speech: [speechApiKey], or [apiKey] when that is left blank. */
    val effectiveSpeechApiKey: String
        get() = speechApiKey.ifBlank { apiKey }

    /** As [missingKeyError], for the speech endpoint. */
    fun missingSpeechKeyError(): ServiceError? =
        if (effectiveSpeechApiKey.isBlank() && !isLocalHost(effectiveSpeechBaseUrl)) {
            ServiceError.ConfigurationError(
                "No API key set for $effectiveSpeechBaseUrl. Add a Speech API Key in " +
                    "Settings → Plugins → AI Plugin → Speech Recognition, or use a local Whisper server."
            )
        } else {
            null
        }

    fun missingKeyError(): ServiceError? =
        if (apiKey.isBlank() && !isLocalEndpoint) {
            ServiceError.ConfigurationError(
                "No API key set for $baseUrl. Add one in Settings → Plugins → AI Plugin, " +
                    "or point the endpoint at a local server such as Ollama, which needs no key."
            )
        } else {
            null
        }
}

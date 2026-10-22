package com.github.ahatem.qtranslate.plugins.speech

import com.github.ahatem.qtranslate.api.plugin.PluginSettings
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.settings.PluginAction
import com.github.ahatem.qtranslate.api.settings.Setting
import com.github.ahatem.qtranslate.api.settings.SettingGroup
import com.github.ahatem.qtranslate.api.settings.SettingGroups
import com.github.ahatem.qtranslate.api.settings.SettingType
import java.net.URI

const val LIVE_OFF = "Off"
const val LIVE_VOSK_BUILTIN = "Vosk (built-in)"
const val LIVE_DEEPGRAM = "Deepgram (live)"
const val LIVE_OPENAI = "OpenAI Realtime"
const val LIVE_VOSK = LIVE_VOSK_BUILTIN
const val LIVE_ENGINE_OPTIONS = "$LIVE_OFF,$LIVE_VOSK_BUILTIN,$LIVE_DEEPGRAM,$LIVE_OPENAI"

const val MODE_LOCAL = "Local only"
const val MODE_CLOUD = "Cloud only"
const val MODE_HYBRID = "Hybrid"
const val MODE_OPTIONS = "$MODE_LOCAL,$MODE_CLOUD,$MODE_HYBRID"

/**
 * Two jobs, two groups. Live text appears while people talk (Vosk, Deepgram live, OpenAI Realtime);
 * accurate text replaces each finished line (a Whisper-style provider). Either can be off; with both on, the live
 * words show at once and the accurate ones take their place.
 */
@SettingGroups(
    SettingGroup(
        key = "live", title = "Live text", order = 10,
        description = "Words appear while people are still talking, locally via built-in Vosk or cloud streaming (Deepgram, OpenAI Realtime)."
    ),
    SettingGroup(
        key = "accurate", title = "Accurate text", order = 20,
        description = "Each finished line is transcribed again, more accurately, and replaces the live text."
    )
)
data class SpeechSettings(
    @field:Setting(
        label = "Mode",
        description = "Local only uses offline Vosk with zero data leaving your machine. " +
            "Cloud only transcribes through your speech provider. " +
            "Hybrid shows live text instantly then refines each finished line with the accurate provider.",
        type = SettingType.DROPDOWN,
        options = MODE_OPTIONS,
        defaultValue = MODE_HYBRID,
        order = 1
    )
    var mode: String = MODE_HYBRID,

    @field:Setting(
        label = "Provider",
        description = "None keeps only the live text, with nothing sent outside your network. " +
            "Groq is fast and has a free tier. A local Whisper server keeps audio on your computer. " +
            "Custom works with any server that offers an OpenAI-style /audio/transcriptions endpoint. " +
            "Audio chat model sends the audio to a multimodal chat model (Gemini, gpt-4o-audio) through " +
            "an OpenAI-compatible gateway's /chat/completions.",
        type = SettingType.DROPDOWN,
        options = SpeechProvider.DROPDOWN_OPTIONS,
        defaultValue = "Groq",
        group = "accurate",
        showIf = "mode=!Local only",
        order = 10
    )
    var provider: String = SpeechProvider.GROQ.label,

    @field:Setting(
        label = "Server URL",
        description = "Leave blank for the provider's own address. Required for Custom and Audio chat model.",
        type = SettingType.TEXT,
        group = "accurate",
        showIf = "provider=Custom (OpenAI-compatible),Audio chat model (OpenAI-compatible),Local Whisper server",
        order = 20
    )
    var baseUrl: String = "",

    @field:Setting(
        label = "API Key",
        description = "Required by Groq, OpenAI and Deepgram. Usually left blank for a local server.",
        type = SettingType.PASSWORD,
        group = "accurate",
        showIf = "provider=Groq,OpenAI,Deepgram,Custom (OpenAI-compatible),Audio chat model (OpenAI-compatible)",
        order = 30
    )
    var apiKey: String = "",

    @field:Setting(
        label = "Model",
        description = "Leave blank for the provider's default (Groq: whisper-large-v3-turbo, OpenAI: whisper-1, " +
            "Deepgram: nova-3). Required for Audio chat model.",
        type = SettingType.TEXT,
        group = "accurate",
        showIf = "provider=Groq,OpenAI,Deepgram,Custom (OpenAI-compatible),Audio chat model (OpenAI-compatible),Local Whisper server",
        order = 40
    )
    var model: String = "",

    @field:Setting(
        label = "Live engine",
        description = "Off records and transcribes line by line. Vosk runs locally. Deepgram live and OpenAI Realtime provide cloud streaming.",
        type = SettingType.DROPDOWN,
        options = LIVE_ENGINE_OPTIONS,
        defaultValue = LIVE_OFF,
        group = "live",
        showIf = "mode=!Cloud only",
        order = 5
    )
    var liveEngine: String = LIVE_OFF,

    @field:Setting(
        label = "Built-in model",
        description = "Model for built-in Vosk engine. Click 'Download Model' to fetch it from alphacephei.com.",
        type = SettingType.DROPDOWN,
        options = VoskModelManager.DROPDOWN_OPTIONS,
        defaultValue = VoskModelManager.DEFAULT_MODEL_NAME,
        group = "live",
        showIf = "liveEngine=$LIVE_VOSK_BUILTIN",
        order = 40
    )
    var voskBuiltinModel: String = VoskModelManager.DEFAULT_MODEL_NAME,

    @field:Setting(
        label = "Live API Key",
        description = "API key for Deepgram live or OpenAI Realtime. If left blank, uses the API Key from above.",
        type = SettingType.PASSWORD,
        group = "live",
        showIf = "liveEngine=$LIVE_DEEPGRAM,$LIVE_OPENAI",
        order = 70
    )
    var liveApiKey: String = ""
) : PluginSettings.Configurable() {

    @Transient
    var modelManager: VoskModelManager? = null

    @PluginAction(
        label = "Download Model",
        group = "live",
        order = 1,
        tooltip = "Downloads and installs the selected Vosk model from alphacephei.com"
    )
    fun downloadSelectedModel(): String? {
        val manager = modelManager ?: return "Model manager not initialized."
        return manager.downloadAction(voskBuiltinModel)
    }

    @PluginAction(
        label = "Check Models",
        group = "live",
        order = 2,
        tooltip = "Lists locally downloaded Vosk models"
    )
    fun checkModels(): String? {
        val manager = modelManager ?: return "Model manager not initialized."
        return manager.statusAction()
    }

    val speechProvider: SpeechProvider
        get() = if (mode == MODE_LOCAL) SpeechProvider.NONE else SpeechProvider.fromLabel(provider)

    val effectiveLiveApiKey: String get() = liveApiKey.trim().ifEmpty { apiKey.trim() }

    val liveEnabled: Boolean
        get() = when (mode) {
            MODE_CLOUD -> false
            MODE_LOCAL, MODE_HYBRID -> when (liveEngine) {
                LIVE_VOSK_BUILTIN -> true
                LIVE_DEEPGRAM -> effectiveLiveApiKey.isNotBlank()
                LIVE_OPENAI -> effectiveLiveApiKey.isNotBlank()
                else -> false
            }
            else -> false
        }

    /** The address requests go to: the user's, or the provider's when left blank. */
    val effectiveBaseUrl: String get() = baseUrl.trim().trimEnd('/').ifEmpty { speechProvider.defaultBaseUrl }

    val effectiveModel: String get() = model.trim().ifEmpty { speechProvider.defaultModel }

    /** What is wrong with the address, or null when it is usable. */
    fun urlError(): ServiceError? {
        if (mode == MODE_LOCAL || speechProvider == SpeechProvider.NONE) return null
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
        if (speechProvider == SpeechProvider.NONE) {
            return ServiceError.ConfigurationError(
                "No accurate speech provider is selected (live text only). Choose one in Settings > Plugins > Speech Recognition."
            )
        }
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

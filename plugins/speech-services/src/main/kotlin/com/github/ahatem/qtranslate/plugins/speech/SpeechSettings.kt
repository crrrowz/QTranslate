package com.github.ahatem.qtranslate.plugins.speech

import com.github.ahatem.qtranslate.api.plugin.HttpClient
import com.github.ahatem.qtranslate.api.plugin.PluginContext
import com.github.ahatem.qtranslate.api.plugin.PluginSettings
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.settings.PluginAction
import com.github.ahatem.qtranslate.api.settings.Setting
import com.github.ahatem.qtranslate.api.settings.SettingType
import com.github.michaelbull.result.fold
import kotlinx.coroutines.runBlocking
import java.net.URI

data class SpeechSettings(
    @field:Setting(
        label = "Provider",
        description = "Groq is fast and has a free tier. A local Whisper server keeps audio on your computer. " +
            "Custom works with any server that offers an OpenAI-style /audio/transcriptions endpoint.",
        type = SettingType.DROPDOWN,
        options = SpeechProvider.DROPDOWN_OPTIONS,
        defaultValue = "Groq",
        order = 10
    )
    var provider: String = SpeechProvider.GROQ.label,

    @field:Setting(
        label = "Server URL",
        description = "Leave blank for the provider's own address. Required for Custom.",
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
            "Deepgram: nova-3).",
        type = SettingType.TEXT,
        order = 40
    )
    var model: String = ""
) : PluginSettings.Configurable() {

    @Transient private var context: PluginContext? = null
    @Transient private var clientProvider: (() -> HttpClient)? = null

    val speechProvider: SpeechProvider get() = SpeechProvider.fromLabel(provider)

    /** The address requests go to: the user's, or the provider's when left blank. */
    val effectiveBaseUrl: String get() = baseUrl.trim().trimEnd('/').ifEmpty { speechProvider.defaultBaseUrl }

    val effectiveModel: String get() = model.trim().ifEmpty { speechProvider.defaultModel }

    /** What is wrong with the address, or null when it is usable. */
    fun urlError(): ServiceError? {
        val url = effectiveBaseUrl
        if (url.isEmpty()) {
            return ServiceError.ConfigurationError(
                "Speech Recognition needs a Server URL for a custom provider. Set it in Settings > Plugins > Speech Recognition."
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
        val host = URI(effectiveBaseUrl).host
        if (speechProvider.requiresKey && apiKey.isBlank() && !isLocal(host)) {
            return ServiceError.ConfigurationError(
                "Speech Recognition has no API key for ${speechProvider.label} (${speechProvider.keyHint}). " +
                    "Add it in Settings > Plugins > Speech Recognition."
            )
        }
        return null
    }

    internal fun attach(context: PluginContext, clientProvider: () -> HttpClient): SpeechSettings = apply {
        this.context = context
        this.clientProvider = clientProvider
    }

    @PluginAction(
        label = "Test Connection",
        order = 50,
        tooltip = "Sends one second of silence to the saved provider and reports whether it answered."
    )
    fun testConnection(): String = runBlocking {
        val context = context ?: return@runBlocking "Speech Recognition is not initialized."
        val client = clientProvider?.invoke() ?: return@runBlocking "Speech Recognition is not initialized."
        SpeechClient(context, client) { this@SpeechSettings }
            .testConnection()
            .fold(
                success = { "Connected to ${speechProvider.label} ($effectiveModel)." },
                failure = { "Connection failed: ${it.message}" }
            )
    }

    private fun isLocal(host: String): Boolean =
        host == "localhost" || host == "127.0.0.1" || host == "::1" || host == "[::1]" ||
            host.endsWith(".local") || host.startsWith("192.168.") || host.startsWith("10.")
}

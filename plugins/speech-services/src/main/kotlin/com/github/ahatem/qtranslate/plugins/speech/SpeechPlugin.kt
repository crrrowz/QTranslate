package com.github.ahatem.qtranslate.plugins.speech

import com.github.ahatem.qtranslate.api.plugin.HttpClient
import com.github.ahatem.qtranslate.api.plugin.Plugin
import com.github.ahatem.qtranslate.api.plugin.PluginContext
import com.github.ahatem.qtranslate.api.plugin.Service
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.plugin.ServiceMetadata
import com.github.ahatem.qtranslate.api.plugin.SupportedLanguages
import com.github.ahatem.qtranslate.api.speech.SpeechRecognitionRequest
import com.github.ahatem.qtranslate.api.speech.SpeechRecognitionResponse
import com.github.ahatem.qtranslate.api.speech.SpeechRecognizer
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result

/**
 * Speech-to-text for Conversation Sessions, through whichever provider the user picks: a cloud
 * service (Groq, OpenAI, Deepgram), a local Whisper server, or any OpenAI-compatible endpoint.
 *
 * Kept apart from the AI Services plugin on purpose. Transcription has its own providers, keys
 * and models, and someone who only wants speech-to-text should find it under its own name.
 */
class SpeechPlugin : Plugin<SpeechSettings> {
    private lateinit var context: PluginContext
    private val http: HttpClient get() = context.http
    private var settings = SpeechSettings()
    private var services: List<Service> = emptyList()
    private lateinit var modelManager: VoskModelManager
    private var builtinService: VoskBuiltinService? = null

    override suspend fun initialize(context: PluginContext): Result<Unit, ServiceError> {
        this.context = context
        modelManager = VoskModelManager({ context.getPluginDataDirectory() })
        builtinService = VoskBuiltinService(modelManager, { settings })
        settings = SpeechSettings(
            provider = context.settings.getString(KEY_PROVIDER) ?: SpeechProvider.GROQ.label,
            baseUrl = context.settings.getString(KEY_BASE_URL).orEmpty(),
            apiKey = context.secrets.get(KEY_API_KEY).orEmpty(),
            model = context.settings.getString(KEY_MODEL).orEmpty(),
            liveServerUrl = context.settings.getString(KEY_LIVE_URL).orEmpty(),
            // Saved before the engine choice existed: an address alone meant Vosk was wanted.
            liveEngine = context.settings.getString(KEY_LIVE_ENGINE)
                ?: if (context.settings.getString(KEY_LIVE_URL).isNullOrBlank()) LIVE_OFF else LIVE_VOSK_SERVER,
            liveAutoLanguage = context.settings.getString(KEY_LIVE_AUTO_LANGUAGE) ?: "en",
            voskBuiltinModel = context.settings.getString(KEY_VOSK_BUILTIN_MODEL) ?: VoskModelManager.DEFAULT_MODEL_NAME,
            liveApiKey = context.secrets.get(KEY_LIVE_API_KEY).orEmpty()
        ).apply {
            this.modelManager = this@SpeechPlugin.modelManager
        }
        return Ok(Unit)
    }

    override suspend fun onEnable(): Result<Unit, ServiceError> {
        val builtin = builtinService ?: VoskBuiltinService(modelManager, { settings }).also { builtinService = it }
        val deepgramLive = DeepgramLiveService { settings }
        val openAiRealtime = OpenAiRealtimeService { settings }
        services = listOf(
            SpeechRecognitionService(SpeechClient(context, http) { settings }) { settings },
            VoskLiveService({ settings }, builtin, deepgramLive, openAiRealtime),
            builtin,
            deepgramLive,
            openAiRealtime
        )
        return Ok(Unit)
    }

    override suspend fun onSettingsChanged(settings: SpeechSettings): Result<Unit, ServiceError> {
        val cleaned = settings.copy(
            provider = SpeechProvider.fromLabel(settings.provider).label,
            baseUrl = settings.baseUrl.trim().trimEnd('/'),
            apiKey = settings.apiKey.trim(),
            model = settings.model.trim(),
            liveServerUrl = settings.liveServerUrl.trim(),
            liveEngine = if (settings.liveEngine in listOf(LIVE_VOSK_BUILTIN, LIVE_VOSK_SERVER, LIVE_DEEPGRAM, LIVE_OPENAI)) settings.liveEngine else LIVE_OFF,
            liveAutoLanguage = settings.liveAutoLanguage.trim().ifEmpty { "en" },
            voskBuiltinModel = settings.voskBuiltinModel.trim().ifEmpty { VoskModelManager.DEFAULT_MODEL_NAME },
            liveApiKey = settings.liveApiKey.trim()
        ).apply {
            this.modelManager = this@SpeechPlugin.modelManager
        }
        // A missing key is fine to save (the user may add it later); an unusable URL is not.
        cleaned.urlError()?.let { return Err(ServiceError.ValidationError(it.message)) }

        context.settings.put(KEY_PROVIDER, cleaned.provider)
        context.settings.put(KEY_BASE_URL, cleaned.baseUrl)
        context.settings.put(KEY_MODEL, cleaned.model)
        context.settings.put(KEY_LIVE_URL, cleaned.liveServerUrl)
        context.settings.put(KEY_LIVE_ENGINE, cleaned.liveEngine)
        context.settings.put(KEY_LIVE_AUTO_LANGUAGE, cleaned.liveAutoLanguage)
        context.settings.put(KEY_VOSK_BUILTIN_MODEL, cleaned.voskBuiltinModel)
        context.secrets.put(KEY_API_KEY, cleaned.apiKey)
        context.secrets.put(KEY_LIVE_API_KEY, cleaned.liveApiKey)
        this.settings = cleaned
        return Ok(Unit)
    }

    override suspend fun onDisable() {
        builtinService?.close()
        services = emptyList()
    }

    override suspend fun shutdown() {
        builtinService?.close()
        services = emptyList()
    }

    override fun getServices(): List<Service> = services
    override fun getSettings(): SpeechSettings = settings

    private companion object {
        const val KEY_PROVIDER = "provider"
        const val KEY_BASE_URL = "baseUrl"
        const val KEY_API_KEY = "apiKey"
        const val KEY_MODEL = "model"
        const val KEY_LIVE_URL = "liveServerUrl"
        const val KEY_LIVE_ENGINE = "liveEngine"
        const val KEY_LIVE_AUTO_LANGUAGE = "liveAutoLanguage"
        const val KEY_VOSK_BUILTIN_MODEL = "voskBuiltinModel"
        const val KEY_LIVE_API_KEY = "liveApiKey"
    }
}

class SpeechRecognitionService(
    private val client: SpeechClient,
    private val settings: () -> SpeechSettings
) : SpeechRecognizer {
    override val metadata = ServiceMetadata(requiresConfiguration = true)
    override val key: String = "speech"
    override val version: String = "1.0.0"
    override val iconPath: String = "assets/speech.svg"
    override val supportedLanguages: SupportedLanguages = SupportedLanguages.All

    /** Names the provider, so the Services list and the conversation window say what is in use. */
    override val name: String get() = "Speech Recognition (${settings().speechProvider.label})"

    override suspend fun transcribe(request: SpeechRecognitionRequest): Result<SpeechRecognitionResponse, ServiceError> =
        client.transcribe(request)

    /** Backs the settings dialog's Test button, which applies what is on screen first. */
    override suspend fun validate(): Result<Unit, ServiceError> = client.testConnection()
}

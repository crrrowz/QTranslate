package com.github.ahatem.qtranslate.plugins.speech

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SpeechSettingsTest {

    @Test
    fun `private 172 16-31 hosts count as local and need no api key`() {
        fun error(url: String) = SpeechSettings(provider = "OpenAI", apiKey = "", baseUrl = url).configurationError()

        assertNull(error("http://172.16.0.5:8000/v1"))
        assertNull(error("http://172.31.255.1:8000/v1"))
        assertNotNull(error("http://172.32.0.1:8000/v1"))
        assertNotNull(error("http://172.15.0.1:8000/v1"))
    }

    @Test
    fun `voskBuiltinModelDisplayLabel formats label with installed marker`() {
        val isolatedDir = java.nio.file.Files.createTempDirectory("speech-settings-test").toFile()
        val manager = VoskModelManager(dataDirectory = { isolatedDir }, persistentDirectoryProvider = { isolatedDir })
        val settings = SpeechSettings().apply { modelManager = manager }

        val labelUninstalled = settings.voskBuiltinModelDisplayLabel("vosk-model-small-en-us-0.15")
        kotlin.test.assertEquals("English (US) small ~40MB", labelUninstalled)

        // Install model fake files
        val modelDir = java.io.File(manager.modelsDirectory, "vosk-model-small-en-us-0.15").apply { mkdirs() }
        java.io.File(modelDir, "am").mkdirs()
        java.io.File(modelDir, "am/final.mdl").writeText("fake")
        java.io.File(modelDir, "conf").mkdirs()
        java.io.File(modelDir, "conf/model.conf").writeText("fake")
        java.io.File(modelDir, "graph").mkdirs()
        java.io.File(modelDir, "graph/HCLG.fst").writeText("fake")

        val labelInstalled = settings.voskBuiltinModelDisplayLabel("vosk-model-small-en-us-0.15")
        kotlin.test.assertEquals("English (US) small ~40MB ✓ (installed)", labelInstalled)
    }

    @Test
    fun `effectiveLiveApiKey only falls back to accurate apiKey when vendors match`() {
        // OpenAI accurate + OpenAI Realtime live -> matches vendor -> fallback permitted
        val openAiSettings = SpeechSettings(
            provider = "OpenAI",
            apiKey = "openai-key",
            liveEngine = LIVE_OPENAI,
            liveApiKey = ""
        )
        kotlin.test.assertEquals("openai-key", openAiSettings.effectiveLiveApiKey)

        // Groq accurate + Deepgram live -> vendors differ -> NO fallback
        val mixedSettings = SpeechSettings(
            provider = "Groq",
            apiKey = "groq-key",
            liveEngine = LIVE_DEEPGRAM,
            liveApiKey = ""
        )
        kotlin.test.assertEquals("", mixedSettings.effectiveLiveApiKey)

        // Deepgram accurate + Deepgram live -> matches vendor -> fallback permitted
        val deepgramSettings = SpeechSettings(
            provider = "Deepgram",
            apiKey = "dg-key",
            liveEngine = LIVE_DEEPGRAM,
            liveApiKey = ""
        )
        kotlin.test.assertEquals("dg-key", deepgramSettings.effectiveLiveApiKey)

        // Explicit liveApiKey always takes precedence regardless of vendor
        val explicitSettings = SpeechSettings(
            provider = "Groq",
            apiKey = "groq-key",
            liveEngine = LIVE_DEEPGRAM,
            liveApiKey = "explicit-dg-key"
        )
        kotlin.test.assertEquals("explicit-dg-key", explicitSettings.effectiveLiveApiKey)
    }
}

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
}

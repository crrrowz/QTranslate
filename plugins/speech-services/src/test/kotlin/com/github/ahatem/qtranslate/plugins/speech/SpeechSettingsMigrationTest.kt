package com.github.ahatem.qtranslate.plugins.speech

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpeechSettingsMigrationTest {

    @Test
    fun `liveEnabled follows engine selection`() {
        val offSettings = SpeechSettings(liveEngine = LIVE_OFF)
        assertFalse(offSettings.liveEnabled)

        val serverNoUrl = SpeechSettings(liveEngine = LIVE_VOSK_SERVER, liveServerUrl = "")
        assertFalse(serverNoUrl.liveEnabled)

        val serverWithUrl = SpeechSettings(liveEngine = LIVE_VOSK_SERVER, liveServerUrl = "ws://localhost:8765")
        assertTrue(serverWithUrl.liveEnabled)

        val builtinSettings = SpeechSettings(liveEngine = LIVE_VOSK_BUILTIN, liveServerUrl = "")
        assertTrue(builtinSettings.liveEnabled, "Built-in engine is enabled without needing a server URL")
    }

    @Test
    fun `legacy LIVE_VOSK constant matches LIVE_VOSK_SERVER`() {
        assertEquals(LIVE_VOSK_SERVER, LIVE_VOSK)
    }
}

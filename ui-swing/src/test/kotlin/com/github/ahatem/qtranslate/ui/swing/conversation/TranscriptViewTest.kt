package com.github.ahatem.qtranslate.ui.swing.conversation

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.core.conversation.AudioSource
import com.github.ahatem.qtranslate.core.conversation.ConversationSession
import com.github.ahatem.qtranslate.core.conversation.TranscriptSegment
import com.github.ahatem.qtranslate.core.localization.LanguageTomlParser
import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import org.junit.jupiter.api.Test
import java.awt.Point
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TranscriptViewTest {

    private val silentLogger = object : Logger {
        override fun debug(message: String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, error: Throwable?) = Unit
    }

    private fun strings(): ConversationStrings {
        val tempDir = Files.createTempDirectory("transcript-view-test").toFile()
        val manager = LocalizationManager(tempDir, LanguageTomlParser(), silentLogger)
        return ConversationStrings(manager)
    }

    @Test
    fun `hit test distinguishes play button, retry button, delete button and text`() {
        val view = TranscriptView(strings())
        view.newestFirst = false
        view.setSize(600, 400)

        val segment = TranscriptSegment(
            id = "seg-1",
            source = AudioSource.MICROPHONE,
            startMillis = 1000,
            endMillis = 3000,
            text = "hello world",
            pending = false,
            failed = false
        )
        val session = ConversationSession(
            id = "session-1",
            title = "Test",
            createdAt = 1000L,
            segments = listOf(segment)
        )
        view.render(session)

        val span = view.spans.single()
        assertTrue(span.playStart < span.playEnd)
        assertTrue(span.retryStart < span.retryEnd)
        assertTrue(span.deleteStart < span.deleteEnd)
        assertTrue(span.textStart < span.textEnd)

        // Point over play button
        val playPos = view.modelToView2D(span.playStart + 1)
        assertNotNull(playPos)
        val playHit = view.hitTest(Point(playPos.x.toInt() + 1, playPos.y.toInt() + 1))
        assertTrue(playHit is TranscriptView.Hit.PlayButton, "Hit over play symbol should be Hit.PlayButton")

        // Point over retry button
        val retryPos = view.modelToView2D(span.retryStart + 1)
        assertNotNull(retryPos)
        val retryHit = view.hitTest(Point(retryPos.x.toInt() + 1, retryPos.y.toInt() + 1))
        assertTrue(retryHit is TranscriptView.Hit.RetryButton, "Hit over retry symbol should be Hit.RetryButton")

        // Point over delete button
        val deletePos = view.modelToView2D(span.deleteStart + 1)
        assertNotNull(deletePos)
        val deleteHit = view.hitTest(Point(deletePos.x.toInt() + 1, deletePos.y.toInt() + 1))
        assertTrue(deleteHit is TranscriptView.Hit.DeleteButton, "Hit over delete symbol should be Hit.DeleteButton")

        // Point over text
        val textPos = view.modelToView2D(span.textStart + 2)
        assertNotNull(textPos)
        val textHit = view.hitTest(Point(textPos.x.toInt() + 1, textPos.y.toInt() + 1))
        assertTrue(textHit is TranscriptView.Hit.Text, "Hit over text should be Hit.Text")
    }

    @Test
    fun `render newestFirst places latest segment at the top`() {
        val view = TranscriptView(strings())
        view.newestFirst = true

        val seg1 = TranscriptSegment(id = "1", source = AudioSource.MICROPHONE, startMillis = 0, endMillis = 1000, text = "first")
        val seg2 = TranscriptSegment(id = "2", source = AudioSource.MICROPHONE, startMillis = 2000, endMillis = 3000, text = "second")

        val session = ConversationSession(id = "s", title = "T", createdAt = 1000L, segments = listOf(seg1, seg2))
        view.render(session)

        assertEquals(2, view.spans.size)
        assertEquals("second", view.spans[0].segment.text, "Newest segment should be first in newestFirst mode")
        assertEquals("first", view.spans[1].segment.text)
    }

    @Test
    fun `updateCursor sets HAND_CURSOR for buttons, TEXT_CURSOR for text, and DEFAULT_CURSOR for header`() {
        val view = TranscriptView(strings())
        val span = TranscriptView.SegmentSpan(
            TranscriptSegment(id = "1", source = AudioSource.MICROPHONE, startMillis = 0, endMillis = 1000, text = "test"),
            0, 2, 2, 10, 10, 12, 12, 14, 15, 19, emptyList()
        )

        view.updateCursor(TranscriptView.Hit.PlayButton(span))
        assertEquals(java.awt.Cursor.HAND_CURSOR, view.cursor.type)

        view.updateCursor(TranscriptView.Hit.RetryButton(span))
        assertEquals(java.awt.Cursor.HAND_CURSOR, view.cursor.type)

        view.updateCursor(TranscriptView.Hit.DeleteButton(span))
        assertEquals(java.awt.Cursor.HAND_CURSOR, view.cursor.type)

        view.updateCursor(TranscriptView.Hit.Header(span))
        assertEquals(java.awt.Cursor.DEFAULT_CURSOR, view.cursor.type)

        view.updateCursor(TranscriptView.Hit.Text(span, 16))
        assertEquals(java.awt.Cursor.TEXT_CURSOR, view.cursor.type)

        view.updateCursor(null)
        assertEquals(java.awt.Cursor.DEFAULT_CURSOR, view.cursor.type)
    }
}

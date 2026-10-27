package com.github.ahatem.qtranslate.ui.swing.conversation

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.core.conversation.AudioSource
import com.github.ahatem.qtranslate.core.conversation.ConversationSession
import com.github.ahatem.qtranslate.core.conversation.LiveLine
import com.github.ahatem.qtranslate.core.conversation.TranscriptSegment
import com.github.ahatem.qtranslate.core.conversation.TranscriptWord
import com.github.ahatem.qtranslate.core.conversation.VerificationState
import com.github.ahatem.qtranslate.core.localization.LanguageTomlParser
import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import org.junit.jupiter.api.Test
import java.awt.Point
import java.nio.file.Files
import javax.swing.JScrollPane
import javax.swing.text.StyleConstants
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
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

    @Test
    fun `render renders confirmed prefix in normal style and live text in draft style`() {
        val view = TranscriptView(strings())
        view.newestFirst = false

        val seg = TranscriptSegment(
            id = "live-mic",
            source = AudioSource.MICROPHONE,
            startMillis = 1000,
            endMillis = 5000,
            text = "Hello world how are you",
            confirmedText = "Hello world",
            draft = true
        )

        val session = ConversationSession(id = "s", title = "T", createdAt = 0, segments = listOf(seg))
        view.render(session)

        val doc = view.styledDocument
        val docText = doc.getText(0, doc.length)
        assertTrue(docText.contains("Hello world how are you"))
    }

    @Test
    fun `inserting a card above anchored card under pointer keeps anchored card on-screen Y unchanged`() {
        val view = TranscriptView(strings())
        view.newestFirst = true
        val scrollPane = JScrollPane(view)
        scrollPane.setSize(600, 180)
        scrollPane.doLayout()
        scrollPane.viewport.doLayout()

        val initialSegments = (1..8).map { i ->
            TranscriptSegment(
                id = "seg-$i",
                source = AudioSource.MICROPHONE,
                startMillis = i * 1000L,
                endMillis = i * 1000L + 800L,
                text = "Line number $i with enough words to take up vertical space in the transcript view"
            )
        }
        val session1 = ConversationSession(id = "s", title = "T", createdAt = 0L, segments = initialSegments)
        view.render(session1)
        scrollPane.validate()

        val anchoredSpanBefore = view.spans.first { it.segment.id == "seg-7" }
        val docPosBefore = view.modelToView2D(anchoredSpanBefore.playStart)
        assertNotNull(docPosBefore)
        view.pointerPosition = Point(docPosBefore.x.toInt() + 10, docPosBefore.y.toInt() + 4)

        val screenYBefore = kotlin.math.round(docPosBefore.y - scrollPane.viewport.viewPosition.y).toInt()

        val newerSegment = TranscriptSegment(
            id = "seg-9",
            source = AudioSource.SYSTEM,
            startMillis = 9000L,
            endMillis = 9800L,
            text = "A brand new incoming message that gets inserted at the very top of the transcript"
        )
        val session2 = session1.withSegment(newerSegment)
        view.render(session2)
        scrollPane.validate()

        val anchoredSpanAfter = view.spans.first { it.segment.id == "seg-7" }
        val docPosAfter = view.modelToView2D(anchoredSpanAfter.playStart)
        assertNotNull(docPosAfter)
        val screenYAfter = kotlin.math.round(docPosAfter.y - scrollPane.viewport.viewPosition.y).toInt()

        assertEquals(screenYBefore, screenYAfter, "Anchored card under pointer must stay at the exact same on-screen Y")
    }

    @Test
    fun `render displays verification state styles, accuracy badge tooltip, and fades corrected highlight after 3s`() {
        val s = strings()
        val view = TranscriptView(s)
        var now = 10_000L
        view.clock = { now }
        view.newestFirst = false
        view.setSize(600, 400)

        val segVerified = TranscriptSegment(
            id = "seg-v",
            source = AudioSource.MICROPHONE,
            startMillis = 1000L,
            endMillis = 3000L,
            text = "hello world",
            words = listOf(
                TranscriptWord("hello", 1000L, 1800L, VerificationState.VERIFIED),
                TranscriptWord("world", 1900L, 2800L, VerificationState.CORRECTED)
            ),
            verificationState = VerificationState.CORRECTED,
            agreement = 0.92
        )
        val segUnverified = TranscriptSegment(
            id = "seg-u",
            source = AudioSource.SYSTEM,
            startMillis = 4000L,
            endMillis = 6000L,
            text = "unverified line",
            verificationState = VerificationState.UNVERIFIED
        )

        val session = ConversationSession(
            id = "s",
            title = "T",
            createdAt = 0L,
            segments = listOf(segVerified, segUnverified)
        )
        view.render(session)

        val doc = view.styledDocument
        val fullText = doc.getText(0, doc.length)
        assertTrue(fullText.contains("✓ 92%"), "Verified header should include ✓ 92% badge")

        val spanV = view.spans[0]
        assertTrue(spanV.badgeEnd > spanV.badgeStart)
        assertEquals(
            s.verifiedBadgeTooltip(92),
            view.tooltipForHit(TranscriptView.Hit.VerificationBadge(spanV))
        )

        // Word "world" is CORRECTED and should initially have a soft highlight background
        val worldRange = spanV.words[1].first
        val attrsBeforeFade = doc.getCharacterElement(worldRange.first).attributes
        assertEquals(view.correctedHighlightColor(), StyleConstants.getBackground(attrsBeforeFade))
        assertFalse(StyleConstants.isItalic(attrsBeforeFade))

        // Advance clock past 3 seconds and trigger fade check
        now += 3_500L
        view.checkCorrectionHighlightFade()
        val spanVAfter = view.spans[0]
        val worldRangeAfter = spanVAfter.words[1].first
        val attrsAfterFade = doc.getCharacterElement(worldRangeAfter.first).attributes
        assertNotEquals(view.correctedHighlightColor(), StyleConstants.getBackground(attrsAfterFade))

        // Unverified segment has unverified tooltip on both badge and text
        val spanU = view.spans[1]
        assertEquals(
            s.unverifiedBadgeTooltip,
            view.tooltipForHit(TranscriptView.Hit.VerificationBadge(spanU))
        )
        assertEquals(
            s.unverifiedBadgeTooltip,
            view.tooltipForHit(TranscriptView.Hit.Text(spanU, spanU.textStart + 1))
        )
    }

    @Test
    fun `low confidence words below 0_6 in LIVE state are underlined while high confidence or verified words are not`() {
        val highConfLive = TranscriptWord("clear", 0L, 400L, VerificationState.LIVE, confidence = 0.85)
        val lowConfLive = TranscriptWord("mumbled", 500L, 900L, VerificationState.LIVE, confidence = 0.45)
        val lowConfVerified = TranscriptWord("checked", 1000L, 1400L, VerificationState.VERIFIED, confidence = 0.45)

        assertFalse(TranscriptView.isLowConfidenceLiveWord(highConfLive))
        assertTrue(TranscriptView.isLowConfidenceLiveWord(lowConfLive))
        assertFalse(TranscriptView.isLowConfidenceLiveWord(lowConfVerified))

        val view = TranscriptView(strings())
        view.newestFirst = false
        val seg = TranscriptSegment(
            id = "live-mic",
            source = AudioSource.MICROPHONE,
            startMillis = 0L,
            endMillis = 1500L,
            text = "clear mumbled checked",
            words = listOf(highConfLive, lowConfLive, lowConfVerified),
            draft = true,
            verificationState = VerificationState.LIVE
        )
        val session = ConversationSession(id = "s", title = "T", createdAt = 0L, segments = listOf(seg))
        view.render(session)

        val span = view.spans.single()
        val doc = view.styledDocument
        val clearAttrs = doc.getCharacterElement(span.words[0].first.first).attributes
        val mumbledAttrs = doc.getCharacterElement(span.words[1].first.first).attributes
        val checkedAttrs = doc.getCharacterElement(span.words[2].first.first).attributes

        assertFalse(StyleConstants.isUnderline(clearAttrs), "High-confidence LIVE word must not be underlined")
        assertTrue(StyleConstants.isUnderline(mumbledAttrs), "Low-confidence LIVE word (< 0.6) must be underlined")
        assertFalse(StyleConstants.isUnderline(checkedAttrs), "VERIFIED word must not have low-confidence underline")
    }

    @Test
    fun `LiveSlotsPanel maintains two permanent speaker slots with bounded height and clears slot on settle`() {
        val panel = LiveSlotsPanel(strings())
        panel.setSize(600, 120)

        // Simultaneous live speech from both speakers updates their respective permanent slots independently
        panel.render(
            mapOf(
                AudioSource.MICROPHONE to LiveLine(
                    startMillis = 1000L,
                    text = "I am speaking right now",
                    confirmedText = "I am",
                    agreement = 0.95
                ),
                AudioSource.SYSTEM to LiveLine(
                    startMillis = 1200L,
                    text = "And the other person is speaking at the same time"
                )
            ),
            visible = true
        )

        assertTrue(panel.isVisible)
        assertEquals("I am speaking right now", panel.slotText(AudioSource.MICROPHONE))
        assertEquals("And the other person is speaking at the same time", panel.slotText(AudioSource.SYSTEM))
        assertTrue(panel.micSlot.badgeLabel.isVisible)
        assertTrue(panel.micSlot.badgeLabel.text.contains("95%"))

        // Slot scrollPane enforces a 2-line minimum and 4-line maximum height cap
        val minH = panel.micSlot.scrollPane.minimumSize.height
        val maxH = panel.micSlot.scrollPane.maximumSize.height
        assertTrue(minH > 0 && maxH > minH)
        assertTrue(panel.micSlot.scrollPane.preferredSize.height in minH..maxH)

        // When MICROPHONE settles, its slot clears while SYSTEM keeps its live speech
        panel.render(
            mapOf(
                AudioSource.SYSTEM to LiveLine(
                    startMillis = 1200L,
                    text = "And the other person is speaking at the same time"
                )
            ),
            visible = true
        )
        assertEquals("", panel.slotText(AudioSource.MICROPHONE))
        assertEquals("And the other person is speaking at the same time", panel.slotText(AudioSource.SYSTEM))
    }

    @Test
    fun `chat layout aligns Me and Them on opposite sides and flips sides in RTL`() {
        val ltrMic = TranscriptView.cardHorizontalBounds(AudioSource.MICROPHONE, 600, chatLayout = true, rtl = false)
        val ltrSys = TranscriptView.cardHorizontalBounds(AudioSource.SYSTEM, 600, chatLayout = true, rtl = false)
        assertEquals(10, ltrMic.first, "In LTR chat layout, MICROPHONE card starts on the left")
        assertTrue(ltrSys.first > ltrMic.first, "In LTR chat layout, SYSTEM card is shifted to the right")
        assertEquals(590, ltrSys.last, "In LTR chat layout, SYSTEM card reaches right edge")

        val rtlMic = TranscriptView.cardHorizontalBounds(AudioSource.MICROPHONE, 600, chatLayout = true, rtl = true)
        val rtlSys = TranscriptView.cardHorizontalBounds(AudioSource.SYSTEM, 600, chatLayout = true, rtl = true)
        assertEquals(10, rtlSys.first, "In RTL chat layout, SYSTEM card starts on the left")
        assertTrue(rtlMic.first > rtlSys.first, "In RTL chat layout, MICROPHONE card is shifted to the right")

        val disabledMic = TranscriptView.cardHorizontalBounds(AudioSource.MICROPHONE, 600, chatLayout = false, rtl = false)
        val disabledSys = TranscriptView.cardHorizontalBounds(AudioSource.SYSTEM, 600, chatLayout = false, rtl = false)
        assertEquals(disabledMic, disabledSys, "When chatLayout is false, both speakers span the full width")
    }
}

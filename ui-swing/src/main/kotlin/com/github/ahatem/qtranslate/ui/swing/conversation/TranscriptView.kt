package com.github.ahatem.qtranslate.ui.swing.conversation

import com.github.ahatem.qtranslate.core.conversation.AudioSource
import com.github.ahatem.qtranslate.core.conversation.ConversationSession
import com.github.ahatem.qtranslate.core.conversation.TranscriptSegment
import com.github.ahatem.qtranslate.core.conversation.TranscriptWord
import com.github.ahatem.qtranslate.core.conversation.formatTimestamp
import java.awt.Color
import java.awt.Cursor
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.Rectangle
import java.awt.RenderingHints
import java.text.BreakIterator
import javax.swing.JScrollPane
import javax.swing.JTextPane
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants
import javax.swing.text.StyledDocument

/**
 * The transcript, rendered as distinct card bubbles per message.
 *
 * Audio replay is explicitly triggered by clicking the dedicated "▶" play button in each card
 * rather than clicking anywhere on the speaker text.
 * Messages can be displayed newest-at-top or oldest-first.
 */
class TranscriptView(private val strings: ConversationStrings) : JTextPane() {

    /** Where a segment landed in the document. */
    class SegmentSpan(
        val segment: TranscriptSegment,
        val playStart: Int,
        val playEnd: Int,
        val headerStart: Int,
        val headerEnd: Int,
        val retryStart: Int,
        val retryEnd: Int,
        val deleteStart: Int,
        val deleteEnd: Int,
        val textStart: Int,
        val textEnd: Int,
        /** Recognised words with their character ranges, for replaying a single word. */
        val words: List<Pair<IntRange, TranscriptWord>>
    )

    sealed interface Hit {
        val span: SegmentSpan

        data class PlayButton(override val span: SegmentSpan) : Hit
        data class RetryButton(override val span: SegmentSpan) : Hit
        data class DeleteButton(override val span: SegmentSpan) : Hit
        data class Header(override val span: SegmentSpan) : Hit
        data class Text(override val span: SegmentSpan, val offset: Int) : Hit
    }

    var spans: List<SegmentSpan> = emptyList()
        private set

    /** Newest messages appear at the top by default so users see incoming speech without scrolling. */
    var newestFirst: Boolean = true
        set(value) {
            if (field != value) {
                field = value
                renderedKey = null
                render(currentSession)
            }
        }

    private var currentSession: ConversationSession? = null
    private var renderedKey: Any? = null

    init {
        isEditable = false
        caret.isVisible = false
        isOpaque = false // Allows custom card background rendering in paintComponent
        border = javax.swing.BorderFactory.createEmptyBorder(12, 16, 16, 16)
        font = font.deriveFont(font.size2D + 2f)

        // Clear any text selection that is dragged exclusively across the header or play button
        addCaretListener { e ->
            val dot = e.dot
            val mark = e.mark
            if (dot != mark) {
                val start = minOf(dot, mark)
                val end = maxOf(dot, mark)
                val span = spans.firstOrNull { it.textEnd > start && it.playStart <= end }
                if (span != null && end <= span.textStart) {
                    SwingUtilities.invokeLater { select(0, 0) }
                }
            }
        }
    }

    /**
     * Shows [session]. Skips the work when nothing visible changed, so the live session's frequent
     * state updates (levels, timer) never disturb a selection or the scroll position.
     */
    fun render(session: ConversationSession?) {
        currentSession = session
        val key = session?.let { (it.id to it.segments) to newestFirst }
        if (key == renderedKey) return
        renderedKey = key

        val scrollBar = (parent?.parent as? JScrollPane)?.verticalScrollBar
        val wasAtTop = scrollBar == null || scrollBar.value <= 24
        val wasAtBottom = scrollBar == null || scrollBar.value + scrollBar.visibleAmount >= scrollBar.maximum - 24

        val doc = styledDocument
        doc.remove(0, doc.length)
        val built = ArrayList<SegmentSpan>()

        val rawSegments = session?.segments.orEmpty()
        val segments = if (newestFirst) rawSegments.reversed() else rawSegments

        if (segments.isEmpty()) {
            doc.insertString(0, strings.emptyHint, muted(italic = true))
        }

        segments.forEach { segment ->
            if (doc.length > 0) doc.insertString(doc.length, "\n\n", null)

            val playStart = doc.length
            doc.insertString(doc.length, " ▶ ", playButton(segment.source))
            val playEnd = doc.length

            val headerStart = doc.length
            val speaker = if (segment.source == AudioSource.MICROPHONE) strings.speakerMe else strings.speakerCall
            doc.insertString(doc.length, " ${speaker.uppercase()} · ${formatTimestamp(segment.startMillis)}", header(segment.source))
            val headerEnd = doc.length

            // Action buttons on the opposite side of the header row
            doc.insertString(doc.length, "    ", null)
            val retryStart = doc.length
            doc.insertString(doc.length, " 🔄 ", actionButton(UIManager.getColor("Actions.Blue") ?: Color(0x389FD6)))
            val retryEnd = doc.length

            doc.insertString(doc.length, " ", null)
            val deleteStart = doc.length
            doc.insertString(doc.length, " 🗑 ", actionButton(UIManager.getColor("Actions.Red") ?: Color(0xE05555)))
            val deleteEnd = doc.length
            doc.insertString(doc.length, "\n", null)

            val textStart = doc.length
            val (text, style) = when {
                segment.pending -> strings.transcribing to muted(italic = true)
                segment.failed -> strings.failedRetry to link()
                // Live text awaiting the accurate recognizer: shown, but visibly provisional.
                segment.draft -> segment.text to body().apply { StyleConstants.setItalic(this, true) }
                else -> segment.text to body()
            }
            doc.insertString(doc.length, text, style)
            val textEnd = doc.length
            // Header and text together: a paragraph inserted after an Arabic line would otherwise
            // inherit its right alignment, header included.
            alignParagraph(doc, playStart, textEnd - playStart, text)

            built += SegmentSpan(
                segment, playStart, playEnd, headerStart, headerEnd,
                retryStart, retryEnd, deleteStart, deleteEnd, textStart, textEnd,
                if (segment.pending || segment.failed) emptyList() else locateWords(segment, textStart)
            )
        }
        spans = built

        SwingUtilities.invokeLater {
            if (newestFirst && wasAtTop) {
                scrollBar?.value = 0
            } else if (!newestFirst && wasAtBottom) {
                scrollBar?.value = scrollBar.maximum
            }
        }
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        // Fill background
        val bg = UIManager.getColor("TextPane.background") ?: background ?: Color(0x1e1e1e)
        g2.color = bg
        g2.fillRect(0, 0, width, height)

        val isDark = isDark(bg)
        val defaultCardBg = if (isDark) {
            Color((bg.red + 14).coerceAtMost(255), (bg.green + 14).coerceAtMost(255), (bg.blue + 16).coerceAtMost(255))
        } else {
            Color((bg.red - 8).coerceAtLeast(0), (bg.green - 8).coerceAtLeast(0), (bg.blue - 8).coerceAtLeast(0))
        }
        val defaultBorderColor = if (isDark) Color(255, 255, 255, 22) else Color(0, 0, 0, 20)

        // Highlight colors for the very first card (the latest message in newestFirst mode)
        val latestCardBg = if (isDark) {
            Color(46, 38, 18)
        } else {
            Color(255, 248, 205)
        }
        val latestBorderColor = if (isDark) Color(245, 190, 45, 220) else Color(215, 160, 20, 220)

        // Draw individual cards behind each segment span
        spans.forEachIndexed { index, span ->
            val topBox = runCatching { modelToView2D(span.playStart) }.getOrNull() ?: return@forEachIndexed
            val bottomBox = runCatching { modelToView2D(span.textEnd) }.getOrNull() ?: return@forEachIndexed

            val isLatest = index == 0
            val cardBg = if (isLatest) latestCardBg else defaultCardBg
            val borderColor = if (isLatest) latestBorderColor else defaultBorderColor

            val x = 10
            val y = topBox.y.toInt() - 4
            val w = (width - 20).coerceAtLeast(100)
            val h = (bottomBox.y + bottomBox.height - topBox.y).toInt() + 8

            // Rounded card body
            g2.color = cardBg
            g2.fillRoundRect(x, y, w, h, 10, 10)

            // Accent stripe on the left edge (amber for latest message, or blue for ME / green for CALL)
            g2.color = if (isLatest) Color(245, 175, 35) else sourceColor(span.segment.source)
            g2.fillRoundRect(x, y, 4, h, 4, 4)

            // Subtle card border
            g2.color = borderColor
            g2.drawRoundRect(x, y, w, h, 10, 10)
        }

        g2.dispose()
        super.paintComponent(g)
    }

    private fun isDark(color: Color): Boolean =
        (0.299 * color.red + 0.587 * color.green + 0.114 * color.blue) < 128

    /** What is under [point], if anything. */
    fun hitTest(point: Point): Hit? {
        val offset = viewToModel2D(point).takeIf { it >= 0 } ?: return null
        val box = runCatching { modelToView2D(offset) }.getOrNull() ?: return null
        val next = runCatching { modelToView2D((offset + 1).coerceAtMost(document.length)) }.getOrNull()
        val right = if (next != null && next.y == box.y) next.x else box.x + 12
        if (point.x < minOf(box.x, right) - 6 || point.x > maxOf(box.x, right) + 6) return null

        val span = spans.firstOrNull { offset in it.playStart until it.textEnd } ?: return null
        return when {
            offset in span.playStart until span.playEnd -> Hit.PlayButton(span)
            offset in span.retryStart until span.retryEnd -> Hit.RetryButton(span)
            offset in span.deleteStart until span.deleteEnd -> Hit.DeleteButton(span)
            offset < span.headerEnd -> Hit.Header(span)
            else -> Hit.Text(span, offset)
        }
    }

    /** The word around [offset] inside [span], as a document range, or null on spaces and punctuation. */
    fun wordRangeAt(span: SegmentSpan, offset: Int): IntRange? {
        val text = runCatching { document.getText(span.textStart, span.textEnd - span.textStart) }.getOrNull() ?: return null
        val local = offset - span.textStart
        if (local !in text.indices) return null
        val words = BreakIterator.getWordInstance()
        words.setText(text)
        val end = words.following(local)
        val start = words.previous()
        if (start == BreakIterator.DONE || end == BreakIterator.DONE) return null
        if (text.substring(start, end).none { it.isLetterOrDigit() }) return null
        return (span.textStart + start) until (span.textStart + end)
    }

    /** The recognised word overlapping [range], when the speech service reported timings. */
    fun timedWordAt(span: SegmentSpan, range: IntRange): TranscriptWord? =
        span.words.firstOrNull { (chars, _) -> chars.first <= range.last && range.first <= chars.last }?.second

    fun textOf(range: IntRange): String = document.getText(range.first, range.last - range.first + 1)

    fun boundsOf(range: IntRange): Rectangle? = runCatching {
        val start = modelToView2D(range.first).bounds
        val end = modelToView2D(range.last + 1).bounds
        if (start.y == end.y) Rectangle(start.x, start.y, end.x - start.x, start.height) else start
    }.getOrNull()

    fun setHandCursor(hand: Boolean) {
        cursor = if (hand) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) else Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR)
    }

    /**
     * Maps each recognised word to its characters in the segment text, in order. Speech services
     * return words with their own spacing and punctuation, so they are found rather than counted.
     */
    private fun locateWords(segment: TranscriptSegment, textStart: Int): List<Pair<IntRange, TranscriptWord>> {
        var cursor = 0
        return segment.words.mapNotNull { word ->
            val needle = word.text.trim { !it.isLetterOrDigit() }
            if (needle.isEmpty()) return@mapNotNull null
            val at = segment.text.indexOf(needle, cursor, ignoreCase = true)
            if (at < 0) return@mapNotNull null
            cursor = at + needle.length
            (textStart + at until textStart + at + needle.length) to word
        }
    }

    private fun alignParagraph(doc: StyledDocument, start: Int, length: Int, text: String) {
        val rtl = text.firstOrNull { Character.isLetter(it) }?.let {
            Character.getDirectionality(it) in setOf(Character.DIRECTIONALITY_RIGHT_TO_LEFT, Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC)
        } ?: false
        val attributes = SimpleAttributeSet().apply {
            StyleConstants.setAlignment(this, if (rtl) StyleConstants.ALIGN_RIGHT else StyleConstants.ALIGN_LEFT)
            StyleConstants.setSpaceBelow(this, 3f)
        }
        doc.setParagraphAttributes(start, length.coerceAtLeast(1), attributes, false)
    }

    private fun body() = SimpleAttributeSet().apply {
        StyleConstants.setForeground(this, UIManager.getColor("TextPane.foreground") ?: foreground)
    }

    private fun muted(italic: Boolean) = SimpleAttributeSet().apply {
        StyleConstants.setForeground(this, UIManager.getColor("Label.disabledForeground") ?: Color.GRAY)
        StyleConstants.setItalic(this, italic)
    }

    private fun link() = SimpleAttributeSet().apply {
        StyleConstants.setForeground(this, UIManager.getColor("Component.linkColor") ?: Color(0x2675BF))
        StyleConstants.setUnderline(this, true)
    }

    private fun playButton(source: AudioSource) = SimpleAttributeSet().apply {
        StyleConstants.setBold(this, true)
        StyleConstants.setFontSize(this, (font.size - 2).coerceAtLeast(11))
        StyleConstants.setForeground(this, sourceColor(source))
    }

    private fun actionButton(color: Color) = SimpleAttributeSet().apply {
        StyleConstants.setBold(this, true)
        StyleConstants.setFontSize(this, (font.size - 3).coerceAtLeast(10))
        StyleConstants.setForeground(this, color)
    }

    private fun header(source: AudioSource) = SimpleAttributeSet().apply {
        StyleConstants.setBold(this, true)
        StyleConstants.setFontSize(this, (font.size - 3).coerceAtLeast(10))
        StyleConstants.setForeground(this, sourceColor(source))
    }

    companion object {
        /** One colour per source, from the theme's action palette so it suits light and dark. */
        fun sourceColor(source: AudioSource): Color = when (source) {
            AudioSource.MICROPHONE -> UIManager.getColor("Actions.Blue") ?: Color(0x389FD6)
            AudioSource.SYSTEM -> UIManager.getColor("Actions.Green") ?: Color(0x59A869)
        }
    }
}

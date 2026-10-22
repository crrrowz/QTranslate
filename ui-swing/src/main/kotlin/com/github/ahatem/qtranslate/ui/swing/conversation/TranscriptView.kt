package com.github.ahatem.qtranslate.ui.swing.conversation

import com.github.ahatem.qtranslate.core.conversation.AudioSource
import com.github.ahatem.qtranslate.core.conversation.ConversationSession
import com.github.ahatem.qtranslate.core.conversation.TranscriptSegment
import com.github.ahatem.qtranslate.core.conversation.TranscriptWord
import com.github.ahatem.qtranslate.core.conversation.formatTimestamp
import java.awt.Color
import java.awt.Cursor
import java.awt.Point
import java.awt.Rectangle
import java.text.BreakIterator
import javax.swing.JTextPane
import javax.swing.UIManager
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants
import javax.swing.text.StyledDocument

/**
 * The transcript, rendered as read-only selectable text that knows which segment and which
 * recognised word sits under every character, so the window can translate and replay them.
 *
 * Each segment is a small header ("CALL · 1:05", clickable to replay the segment) over its text.
 * The original text is never altered: translations appear only in the hover popup.
 */
class TranscriptView(private val strings: ConversationStrings) : JTextPane() {

    /** Where a segment landed in the document. */
    class SegmentSpan(
        val segment: TranscriptSegment,
        val headerStart: Int,
        val headerEnd: Int,
        val textStart: Int,
        val textEnd: Int,
        /** Recognised words with their character ranges, for replaying a single word. */
        val words: List<Pair<IntRange, TranscriptWord>>
    )

    sealed interface Hit {
        val span: SegmentSpan

        data class Header(override val span: SegmentSpan) : Hit
        data class Text(override val span: SegmentSpan, val offset: Int) : Hit
    }

    var spans: List<SegmentSpan> = emptyList()
        private set

    private var renderedKey: Any? = null

    init {
        isEditable = false
        // Read-only but still selectable and navigable, so a phrase can be selected for translation.
        caret.isVisible = false
        border = javax.swing.BorderFactory.createEmptyBorder(12, 16, 12, 16)
        font = font.deriveFont(font.size2D + 2f)
    }

    /**
     * Shows [session]. Skips the work when nothing visible changed, so the live session's frequent
     * state updates (levels, timer) never disturb a selection or the scroll position.
     */
    fun render(session: ConversationSession?) {
        val key = session?.let { it.id to it.segments }
        if (key == renderedKey) return
        renderedKey = key

        val scrollBar = (parent?.parent as? javax.swing.JScrollPane)?.verticalScrollBar
        val wasAtBottom = scrollBar == null || scrollBar.value + scrollBar.visibleAmount >= scrollBar.maximum - 24

        val doc = styledDocument
        doc.remove(0, doc.length)
        val built = ArrayList<SegmentSpan>()

        val segments = session?.segments.orEmpty()
        if (segments.isEmpty()) {
            doc.insertString(0, strings.emptyHint, muted(italic = true))
        }
        segments.forEach { segment ->
            if (doc.length > 0) doc.insertString(doc.length, "\n\n", null)

            val headerStart = doc.length
            val speaker = if (segment.source == AudioSource.MICROPHONE) strings.speakerMe else strings.speakerCall
            doc.insertString(doc.length, "${speaker.uppercase()} · ${formatTimestamp(segment.startMillis)}", header(segment.source))
            val headerEnd = doc.length
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
            alignParagraph(doc, headerStart, textEnd - headerStart, text)

            built += SegmentSpan(
                segment, headerStart, headerEnd, textStart, textEnd,
                if (segment.pending || segment.failed) emptyList() else locateWords(segment, textStart)
            )
        }
        spans = built

        if (wasAtBottom) caretPosition = doc.length
    }

    /** What is under [point], if anything. */
    fun hitTest(point: Point): Hit? {
        val offset = viewToModel2D(point).takeIf { it >= 0 } ?: return null
        // viewToModel snaps to the nearest character even far to the right of a short line; only
        // count it when the character's box actually contains the point.
        val box = runCatching { modelToView2D(offset) }.getOrNull() ?: return null
        val next = runCatching { modelToView2D((offset + 1).coerceAtMost(document.length)) }.getOrNull()
        val right = if (next != null && next.y == box.y) next.x else box.x + 12
        if (point.x < minOf(box.x, right) - 6 || point.x > maxOf(box.x, right) + 6) return null

        val span = spans.firstOrNull { offset in it.headerStart until it.headerEnd || offset in it.textStart until it.textEnd }
            ?: return null
        return if (offset < span.headerEnd) Hit.Header(span) else Hit.Text(span, offset)
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
            StyleConstants.setSpaceBelow(this, 2f)
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

    private fun header(source: AudioSource) = SimpleAttributeSet().apply {
        StyleConstants.setBold(this, true)
        StyleConstants.setFontSize(this, (font.size - 4).coerceAtLeast(10))
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

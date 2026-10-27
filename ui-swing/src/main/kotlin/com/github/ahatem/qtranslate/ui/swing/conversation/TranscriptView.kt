package com.github.ahatem.qtranslate.ui.swing.conversation

import com.github.ahatem.qtranslate.core.conversation.AudioSource
import com.github.ahatem.qtranslate.core.conversation.ConversationSession
import com.github.ahatem.qtranslate.core.conversation.TranscriptSegment
import com.github.ahatem.qtranslate.core.conversation.TranscriptWord
import com.github.ahatem.qtranslate.core.conversation.VerificationState
import com.github.ahatem.qtranslate.core.conversation.formatTimestamp
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.MouseEvent
import java.text.BreakIterator
import javax.swing.Icon
import com.formdev.flatlaf.extras.FlatSVGIcon
import com.github.ahatem.qtranslate.ui.swing.shared.icon.Icons
import javax.swing.JScrollPane
import javax.swing.JTextPane
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.UIManager
import javax.swing.text.DefaultCaret
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
        val words: List<Pair<IntRange, TranscriptWord>>,
        val badgeStart: Int = headerEnd,
        val badgeEnd: Int = headerEnd
    )

    sealed interface Hit {
        val span: SegmentSpan

        data class PlayButton(override val span: SegmentSpan) : Hit
        data class RetryButton(override val span: SegmentSpan) : Hit
        data class DeleteButton(override val span: SegmentSpan) : Hit
        data class VerificationBadge(override val span: SegmentSpan) : Hit
        data class Header(override val span: SegmentSpan) : Hit
        data class Text(override val span: SegmentSpan, val offset: Int) : Hit
    }

    var spans: List<SegmentSpan> = emptyList()
        private set

    /** Injectable clock for deterministic testing of correction highlight fade-out. */
    var clock: () -> Long = System::currentTimeMillis

    private val correctedFirstSeenMillis = mutableMapOf<String, Long>()
    private val correctionFadeTimer = Timer(250) { checkCorrectionHighlightFade() }.apply {
        isRepeats = true
    }

    /** True while TranscriptView is adjusting scroll position programmatically (to prevent dismissing popups). */
    var isProgrammaticScroll: Boolean = false

    /** Current pointer position inside this transcript view, or null when outside. */
    var pointerPosition: Point? = null

    /** Newest messages appear at the top by default so users see incoming speech without scrolling. */
    var newestFirst: Boolean = true
        set(value) {
            if (field != value) {
                field = value
                renderedKey = null
                render(currentSession)
            }
        }

    /** Aligns "Me" and "Them" cards on opposite horizontal sides of the transcript view. */
    var chatLayout: Boolean = true
        set(value) {
            if (field != value) {
                field = value
                renderedKey = null
                render(currentSession)
            }
        }

    /** True when the surrounding UI is in right-to-left orientation (swaps left/right sides in chat layout). */
    var rtlLayout: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                renderedKey = null
                render(currentSession)
            }
        }

    var currentSession: ConversationSession? = null
        private set
    private var renderedKey: Any? = null

    inner class TranscriptCaret : DefaultCaret() {
        private var draggingFromHeader = false

        override fun mousePressed(e: MouseEvent) {
            val hit = hitTest(e.point)
            if (hit !is Hit.Text) {
                draggingFromHeader = true
                select(0, 0)
                return
            }
            draggingFromHeader = false
            super.mousePressed(e)
        }

        override fun mouseDragged(e: MouseEvent) {
            if (draggingFromHeader) return
            val offset = viewToModel2D(e.point)
            val span = spans.firstOrNull { it.textStart <= mark && mark <= it.textEnd }
            if (span != null) {
                val clamped = offset.coerceIn(span.textStart, span.textEnd)
                super.moveDot(clamped)
                return
            }
            super.mouseDragged(e)
        }

        override fun mouseReleased(e: MouseEvent) {
            if (draggingFromHeader) {
                draggingFromHeader = false
                select(0, 0)
                return
            }
            super.mouseReleased(e)
        }
    }

    init {
        isEditable = false
        caret = TranscriptCaret()
        caret.isVisible = false
        isOpaque = false // Allows custom card background rendering in paintComponent
        border = javax.swing.BorderFactory.createEmptyBorder(12, 16, 16, 16)
        font = font.deriveFont(font.size2D + 2f)

        addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) { pointerPosition = e.point }
            override fun mouseExited(e: MouseEvent) { pointerPosition = null }
        })
        addMouseMotionListener(object : java.awt.event.MouseMotionAdapter() {
            override fun mouseMoved(e: MouseEvent) { pointerPosition = e.point }
            override fun mouseDragged(e: MouseEvent) { pointerPosition = e.point }
        })

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

        val scrollPane = (parent?.parent as? JScrollPane)
        val viewport = scrollPane?.viewport
        val scrollBar = scrollPane?.verticalScrollBar
        val viewRect = viewport?.viewRect

        val wasAtTop = scrollBar == null || scrollBar.value <= 24
        val wasAtBottom = scrollBar == null || scrollBar.value + scrollBar.visibleAmount >= scrollBar.maximum - 24

        // Anchor card under the pointer first; otherwise fall back to the first visible card.
        val pointer = pointerPosition ?: runCatching { mousePosition }.getOrNull()
        val pointerSpan = pointer?.let(::spanAtPoint)
        val firstVisibleSpan = if (viewRect != null && spans.isNotEmpty()) {
            spans.firstOrNull { span ->
                val bounds = runCatching { modelToView2D(span.playStart) }.getOrNull()
                bounds != null && (bounds.y + 10) >= viewRect.y
            } ?: spans.firstOrNull()
        } else null
        val anchorSpan = pointerSpan ?: firstVisibleSpan

        val anchorSegmentId = anchorSpan?.segment?.id
        val currentScrollY = viewport?.viewPosition?.y ?: scrollBar?.value ?: 0
        val anchorOldBounds = anchorSpan?.let { runCatching { modelToView2D(it.playStart) }.getOrNull() }
        val anchorYOffset = if (anchorOldBounds != null) anchorOldBounds.y - currentScrollY else null

        // Selection preservation
        val selStart = selectionStart
        val selEnd = selectionEnd
        val hasSelection = selStart != selEnd
        val selSpan = if (hasSelection) spans.firstOrNull { it.textStart <= selStart && selStart <= it.textEnd } else null
        val selSegmentId = selSpan?.segment?.id
        val selRelStart = if (selSpan != null) selStart - selSpan.textStart else null
        val selRelEnd = if (selSpan != null) selEnd - selSpan.textStart else null

        val doc = styledDocument
        doc.remove(0, doc.length)
        val built = ArrayList<SegmentSpan>()

        val rawSegments = session?.segments.orEmpty()
        val segments = if (newestFirst) rawSegments.reversed() else rawSegments

        if (segments.isEmpty()) {
            doc.insertString(0, strings.emptyHint, muted(italic = true))
        }

        val now = clock()
        var anyActiveCorrectionHighlight = false

        segments.forEach { segment ->
            if (doc.length > 0) doc.insertString(doc.length, "\n\n", null)

            val playStart = doc.length
            val iconSize = (font.size - 2).coerceIn(12, 16)
            val playIcon = FlatSVGIcon(Icons.PLAY, iconSize, iconSize).apply {
                colorFilter = FlatSVGIcon.ColorFilter { sourceColor(segment.source) }
            }
            doc.insertString(doc.length, " ", SimpleAttributeSet().apply { StyleConstants.setIcon(this, playIcon) })
            doc.insertString(doc.length, " ", null)
            val playEnd = doc.length

            val headerStart = doc.length
            val speaker = if (segment.source == AudioSource.MICROPHONE) strings.speakerMe else strings.speakerCall
            doc.insertString(doc.length, "${speaker.uppercase()} · ${formatTimestamp(segment.startMillis)}", header(segment.source))
            val headerEnd = doc.length

            var badgeStart = doc.length
            var badgeEnd = badgeStart
            val agreementPct = segment.agreement?.let { kotlin.math.round(it * 100.0).toInt().coerceIn(0, 100) }
            if (agreementPct != null && (segment.verificationState == VerificationState.VERIFIED ||
                    segment.verificationState == VerificationState.CORRECTED ||
                    segment.confirmedText.isNotBlank())
            ) {
                doc.insertString(doc.length, "  ", null)
                badgeStart = doc.length
                doc.insertString(doc.length, strings.verifiedBadge(agreementPct), verifiedBadgeStyle())
                badgeEnd = doc.length
            } else if (!segment.draft && segment.verificationState == VerificationState.UNVERIFIED) {
                doc.insertString(doc.length, "  ", null)
                badgeStart = doc.length
                doc.insertString(doc.length, strings.unverifiedBadge, unverifiedBadgeStyle())
                badgeEnd = doc.length
            }

            val isLiveDraft = segment.id.startsWith("live-")
            var retryStart = doc.length
            var retryEnd = retryStart
            var deleteStart = doc.length
            var deleteEnd = deleteStart

            if (!isLiveDraft) {
                // Action buttons on the opposite side of the header row
                doc.insertString(doc.length, "    ", null)
                retryStart = doc.length
                val retryIcon = FlatSVGIcon(Icons.RETRY, iconSize, iconSize).apply {
                    colorFilter = FlatSVGIcon.ColorFilter { UIManager.getColor("Actions.Blue") ?: Color(0x389FD6) }
                }
                doc.insertString(doc.length, " ", SimpleAttributeSet().apply { StyleConstants.setIcon(this, retryIcon) })
                doc.insertString(doc.length, " ", null)
                retryEnd = doc.length

                deleteStart = doc.length
                val deleteIcon = FlatSVGIcon(Icons.DELETE, iconSize, iconSize).apply {
                    colorFilter = FlatSVGIcon.ColorFilter { UIManager.getColor("Actions.Red") ?: Color(0xE05555) }
                }
                doc.insertString(doc.length, " ", SimpleAttributeSet().apply { StyleConstants.setIcon(this, deleteIcon) })
                doc.insertString(doc.length, " ", null)
                deleteEnd = doc.length
            }
            doc.insertString(doc.length, "\n", null)

            val textStart = doc.length
            when {
                segment.pending -> {
                    doc.insertString(doc.length, strings.transcribing, muted(italic = true))
                }
                segment.failed -> {
                    doc.insertString(doc.length, strings.failedRetry, link())
                }
                segment.draft -> {
                    val confirmed = segment.confirmedText
                    val liveTail = when {
                        confirmed.isBlank() -> segment.text
                        segment.text.startsWith(confirmed, ignoreCase = true) -> segment.text.substring(confirmed.length).trimStart()
                        else -> segment.text
                    }
                    if (confirmed.isNotBlank()) {
                        val confirmedAttr = when (segment.verificationState) {
                            VerificationState.UNVERIFIED -> muted(italic = false)
                            else -> body()
                        }
                        doc.insertString(doc.length, confirmed, confirmedAttr)
                        if (liveTail.isNotBlank()) {
                            doc.insertString(doc.length, " ", null)
                        }
                    }
                    if (liveTail.isNotBlank()) {
                        doc.insertString(doc.length, liveTail, muted(italic = true))
                    }
                }
                else -> {
                    val baseAttr = when (segment.verificationState) {
                        VerificationState.LIVE -> muted(italic = true)
                        VerificationState.UNVERIFIED -> muted(italic = false)
                        VerificationState.VERIFIED, VerificationState.CORRECTED -> body()
                    }
                    doc.insertString(doc.length, segment.text, baseAttr)
                }
            }
            val textEnd = doc.length
            val wordSpans = if (segment.pending || segment.failed) emptyList() else locateWords(segment, textStart)
            wordSpans.forEach { (range, word) ->
                val len = range.last - range.first + 1
                if (len <= 0) return@forEach
                when (word.verificationState) {
                    VerificationState.CORRECTED -> {
                        val key = "${segment.id}:${range.first - textStart}:${word.text}"
                        val firstSeen = correctedFirstSeenMillis.getOrPut(key) { now }
                        if (now - firstSeen < CORRECTION_HIGHLIGHT_MS) {
                            doc.setCharacterAttributes(range.first, len, correctedStyle(), false)
                            anyActiveCorrectionHighlight = true
                        } else {
                            doc.setCharacterAttributes(range.first, len, body(), false)
                        }
                    }
                    VerificationState.UNVERIFIED -> {
                        doc.setCharacterAttributes(range.first, len, muted(italic = false), false)
                    }
                    VerificationState.LIVE -> {
                        val attr = muted(italic = true)
                        if (isLowConfidenceLiveWord(word)) {
                            StyleConstants.setUnderline(attr, true)
                        }
                        doc.setCharacterAttributes(range.first, len, attr, false)
                    }
                    VerificationState.VERIFIED -> {
                        doc.setCharacterAttributes(range.first, len, body(), false)
                    }
                }
            }
            if (wordSpans.isEmpty() && segment.verificationState == VerificationState.CORRECTED && textEnd > textStart) {
                val key = "${segment.id}:segment"
                val firstSeen = correctedFirstSeenMillis.getOrPut(key) { now }
                if (now - firstSeen < CORRECTION_HIGHLIGHT_MS) {
                    doc.setCharacterAttributes(textStart, textEnd - textStart, correctedStyle(), false)
                    anyActiveCorrectionHighlight = true
                }
            }
            // Header and text together: a paragraph inserted after an Arabic line would otherwise
            // inherit its right alignment, header included.
            alignParagraph(doc, playStart, textEnd - playStart, segment.text, segment.source)

            built += SegmentSpan(
                segment, playStart, playEnd, headerStart, headerEnd,
                retryStart, retryEnd, deleteStart, deleteEnd, textStart, textEnd,
                wordSpans, badgeStart, badgeEnd
            )
        }
        spans = built

        if (anyActiveCorrectionHighlight && isShowing) {
            if (!correctionFadeTimer.isRunning) correctionFadeTimer.start()
        } else {
            if (correctionFadeTimer.isRunning) correctionFadeTimer.stop()
        }

        fun applyScrollAndSelectionRestore() {
            isProgrammaticScroll = true
            try {
                if (scrollPane != null) {
                    val vpSize = viewport?.extentSize?.takeIf { it.width > 0 && it.height > 0 } ?: scrollPane.size
                    if (vpSize.width > 0) {
                        setSize(vpSize.width, Int.MAX_VALUE / 2)
                        val pref = preferredSize
                        setSize(vpSize.width, maxOf(vpSize.height, pref.height))
                    }
                    doLayout()
                    scrollPane.doLayout()
                    viewport?.doLayout()
                } else if (width > 0 && height > 0) {
                    doLayout()
                }
                val pointerInTranscript = pointer != null
                val pinnedToLiveEdge = if (newestFirst) wasAtTop else wasAtBottom
                val followLiveEdge = pinnedToLiveEdge && !pointerInTranscript

                if (followLiveEdge) {
                    if (newestFirst) {
                        if (viewport != null) viewport.viewPosition = Point(viewport.viewPosition.x, 0)
                        scrollBar?.value = 0
                    } else if (scrollBar != null) {
                        val bottomY = (scrollBar.maximum - scrollBar.visibleAmount).coerceAtLeast(0)
                        if (viewport != null) viewport.viewPosition = Point(viewport.viewPosition.x, bottomY)
                        scrollBar.value = scrollBar.maximum
                    }
                } else if (anchorSegmentId != null && anchorYOffset != null) {
                    val newSpan = built.firstOrNull { it.segment.id == anchorSegmentId }
                    if (newSpan != null) {
                        val newBounds = runCatching { modelToView2D(newSpan.playStart) }.getOrNull()
                        if (newBounds != null) {
                            val maxScroll = if (viewport != null && viewport.extentSize.height > 0) {
                                (height - viewport.extentSize.height).coerceAtLeast(0)
                            } else if (scrollBar != null && scrollBar.maximum > 0) {
                                (scrollBar.maximum - scrollBar.visibleAmount).coerceAtLeast(0)
                            } else Int.MAX_VALUE
                            val targetY = kotlin.math.round(newBounds.y - anchorYOffset).toInt().coerceIn(0, maxScroll)
                            if (viewport != null) {
                                viewport.viewPosition = Point(viewport.viewPosition.x, targetY)
                            }
                            scrollBar?.value = targetY
                        }
                    }
                }
            } finally {
                isProgrammaticScroll = false
            }

            if (selSegmentId != null && selRelStart != null && selRelEnd != null) {
                val newSpan = built.firstOrNull { it.segment.id == selSegmentId }
                if (newSpan != null) {
                    val restoredStart = (newSpan.textStart + selRelStart).coerceIn(newSpan.textStart, newSpan.textEnd)
                    val restoredEnd = (newSpan.textStart + selRelEnd).coerceIn(newSpan.textStart, newSpan.textEnd)
                    if (restoredStart != restoredEnd) {
                        select(restoredStart, restoredEnd)
                    }
                }
            }
        }

        applyScrollAndSelectionRestore()
        if (isShowing && SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater { applyScrollAndSelectionRestore() }
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

            val hBounds = cardHorizontalBounds(span.segment.source, width, chatLayout, rtlLayout)
            val x = hBounds.first
            val y = topBox.y.toInt() - 4
            val w = (hBounds.last - hBounds.first).coerceAtLeast(100)
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

        // Subtle dotted underline under UNVERIFIED ranges
        val gAfter = g.create() as Graphics2D
        try {
            gAfter.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            gAfter.color = UIManager.getColor("Label.disabledForeground") ?: Color.GRAY
            gAfter.stroke = BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1f, floatArrayOf(2f, 2f), 0f)
            spans.forEach { span ->
                if (span.words.isNotEmpty()) {
                    span.words.forEach { (range, word) ->
                        if (word.verificationState == VerificationState.UNVERIFIED) {
                            boundsOf(range)?.let { box ->
                                val yLine = box.y + box.height - 2
                                gAfter.drawLine(box.x, yLine, box.x + box.width, yLine)
                            }
                        }
                    }
                } else if (!span.segment.draft && span.segment.verificationState == VerificationState.UNVERIFIED && span.textEnd > span.textStart) {
                    boundsOf(span.textStart until span.textEnd)?.let { box ->
                        val yLine = box.y + box.height - 2
                        gAfter.drawLine(box.x, yLine, box.x + box.width, yLine)
                    }
                }
            }
        } finally {
            gAfter.dispose()
        }
    }

    /** Re-evaluates active correction highlights and triggers a re-render when any expires. */
    fun checkCorrectionHighlightFade() {
        val now = clock()
        val anyStillActive = correctedFirstSeenMillis.values.any { now - it < CORRECTION_HIGHLIGHT_MS }
        renderedKey = null
        render(currentSession)
        if (!anyStillActive && correctionFadeTimer.isRunning) {
            correctionFadeTimer.stop()
        }
    }

    private fun isDark(color: Color): Boolean =
        (0.299 * color.red + 0.587 * color.green + 0.114 * color.blue) < 128

    private fun Rectangle.grow(pad: Int): Rectangle = Rectangle(x - pad, y - pad, width + pad * 2, height + pad * 2)

    /** Finds the card span containing [point] vertically or by character offset. */
    fun spanAtPoint(point: Point): SegmentSpan? {
        val offset = viewToModel2D(point).takeIf { it >= 0 }
        if (offset != null) {
            spans.firstOrNull { offset in it.playStart..it.textEnd }?.let { return it }
        }
        return spans.firstOrNull { span ->
            val top = runCatching { modelToView2D(span.playStart) }.getOrNull() ?: return@firstOrNull false
            val bottom = runCatching { modelToView2D(span.textEnd) }.getOrNull() ?: top
            point.y.toDouble() in (top.y - 8.0)..(bottom.y + bottom.height + 8.0)
        }
    }

    /** What is under [point], if anything. */
    fun hitTest(point: Point): Hit? {
        val offset = viewToModel2D(point).takeIf { it >= 0 } ?: return null
        val span = spans.firstOrNull { offset in it.playStart until it.textEnd } ?: return null

        val playBox = boundsOf(span.playStart until span.playEnd)
        if (playBox != null && playBox.grow(4).contains(point)) return Hit.PlayButton(span)

        if (span.badgeEnd > span.badgeStart) {
            val badgeBox = boundsOf(span.badgeStart until span.badgeEnd)
            if (badgeBox != null && badgeBox.grow(4).contains(point)) return Hit.VerificationBadge(span)
        }

        val retryBox = boundsOf(span.retryStart until span.retryEnd)
        if (retryBox != null && retryBox.grow(4).contains(point)) return Hit.RetryButton(span)

        val deleteBox = boundsOf(span.deleteStart until span.deleteEnd)
        if (deleteBox != null && deleteBox.grow(4).contains(point)) return Hit.DeleteButton(span)

        val box = runCatching { modelToView2D(offset) }.getOrNull() ?: return null
        val next = runCatching { modelToView2D((offset + 1).coerceAtMost(document.length)) }.getOrNull()
        val right = if (next != null && next.y == box.y) next.x else box.x + 12
        if (point.x < minOf(box.x, right) - 6 || point.x > maxOf(box.x, right) + 6) return null

        return when {
            offset in span.playStart until span.playEnd -> Hit.PlayButton(span)
            offset in span.badgeStart until span.badgeEnd -> Hit.VerificationBadge(span)
            offset in span.retryStart until span.retryEnd -> Hit.RetryButton(span)
            offset in span.deleteStart until span.deleteEnd -> Hit.DeleteButton(span)
            offset < span.headerEnd -> Hit.Header(span)
            else -> Hit.Text(span, offset)
        }
    }

    /** Localized tooltip text for [hit], including verification badge and unverified segment explanations. */
    fun tooltipForHit(hit: Hit?): String? = when (hit) {
        is Hit.PlayButton -> strings.actionPlayAudio
        is Hit.RetryButton -> strings.actionRetrySegment
        is Hit.DeleteButton -> strings.actionDeleteMessage
        is Hit.VerificationBadge -> {
            val seg = hit.span.segment
            val pct = seg.agreement?.let { kotlin.math.round(it * 100.0).toInt().coerceIn(0, 100) }
            if (pct != null && seg.verificationState != VerificationState.UNVERIFIED) {
                strings.verifiedBadgeTooltip(pct)
            } else {
                strings.unverifiedBadgeTooltip
            }
        }
        is Hit.Text -> {
            val seg = hit.span.segment
            val word = wordRangeAt(hit.span, hit.offset)?.let { timedWordAt(hit.span, it) }
            if (seg.verificationState == VerificationState.UNVERIFIED || word?.verificationState == VerificationState.UNVERIFIED) {
                strings.unverifiedBadgeTooltip
            } else null
        }
        else -> null
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

    fun updateCursor(hit: Hit?) {
        cursor = when (hit) {
            is Hit.PlayButton, is Hit.RetryButton, is Hit.DeleteButton ->
                Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            is Hit.Text ->
                Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR)
            else ->
                Cursor.getDefaultCursor()
        }
    }

    fun setHandCursor(hand: Boolean) {
        cursor = if (hand) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) else Cursor.getDefaultCursor()
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

    private fun alignParagraph(doc: StyledDocument, start: Int, length: Int, text: String, source: AudioSource = AudioSource.MICROPHONE) {
        val rtl = text.firstOrNull { Character.isLetter(it) }?.let {
            Character.getDirectionality(it) in setOf(Character.DIRECTIONALITY_RIGHT_TO_LEFT, Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC)
        } ?: false
        val isLeftCard = if (!rtlLayout) source == AudioSource.MICROPHONE else source == AudioSource.SYSTEM
        val sideIndent = if (chatLayout) ((width.takeIf { it > 0 } ?: 600) * 0.14f).coerceAtLeast(24f) else 0f
        val attributes = SimpleAttributeSet().apply {
            StyleConstants.setAlignment(this, if (rtl) StyleConstants.ALIGN_RIGHT else StyleConstants.ALIGN_LEFT)
            StyleConstants.setLeftIndent(this, if (chatLayout && !isLeftCard) sideIndent else 0f)
            StyleConstants.setRightIndent(this, if (chatLayout && isLeftCard) sideIndent else 0f)
            StyleConstants.setSpaceBelow(this, 3f)
        }
        doc.setParagraphAttributes(start, length.coerceAtLeast(1), attributes, false)
    }

    private fun body() = SimpleAttributeSet().apply {
        StyleConstants.setForeground(this, UIManager.getColor("TextPane.foreground") ?: foreground)
        StyleConstants.setItalic(this, false)
    }

    private fun correctedStyle() = SimpleAttributeSet().apply {
        StyleConstants.setForeground(this, UIManager.getColor("TextPane.foreground") ?: foreground)
        StyleConstants.setItalic(this, false)
        StyleConstants.setBackground(this, correctedHighlightColor())
    }

    fun correctedHighlightColor(): Color {
        val bg = UIManager.getColor("TextPane.background") ?: background ?: Color(0x1e1e1e)
        return if (isDark(bg)) Color(85, 70, 22) else Color(255, 240, 175)
    }

    private fun verifiedBadgeStyle() = SimpleAttributeSet().apply {
        StyleConstants.setBold(this, true)
        StyleConstants.setFontSize(this, (font.size - 3).coerceAtLeast(10))
        StyleConstants.setForeground(this, UIManager.getColor("Actions.Green") ?: Color(0x59A869))
    }

    private fun unverifiedBadgeStyle() = SimpleAttributeSet().apply {
        StyleConstants.setItalic(this, false)
        StyleConstants.setFontSize(this, (font.size - 3).coerceAtLeast(10))
        StyleConstants.setForeground(this, UIManager.getColor("Label.disabledForeground") ?: Color.GRAY)
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
        const val CORRECTION_HIGHLIGHT_MS = 3_000L
        const val LOW_CONFIDENCE_THRESHOLD = 0.6

        /** True when [word] is in LIVE state and has a reported recognizer confidence below [LOW_CONFIDENCE_THRESHOLD]. */
        fun isLowConfidenceLiveWord(word: TranscriptWord): Boolean =
            word.verificationState == VerificationState.LIVE &&
                word.confidence != null &&
                word.confidence!! < LOW_CONFIDENCE_THRESHOLD

        /**
         * Horizontal pixel range (`x..(x + w)`) for a card bubble of [source] inside [totalWidth].
         * When [chatLayout] is true, "Me" ([AudioSource.MICROPHONE]) is placed on the left (or right in [rtl])
         * and "Them" ([AudioSource.SYSTEM]) on the opposite side.
         */
        fun cardHorizontalBounds(
            source: AudioSource,
            totalWidth: Int,
            chatLayout: Boolean,
            rtl: Boolean = false
        ): IntRange {
            val safeWidth = totalWidth.coerceAtLeast(140)
            if (!chatLayout) {
                val x = 10
                val w = (safeWidth - 20).coerceAtLeast(100)
                return x..(x + w)
            }
            val sideIndent = ((safeWidth - 20) * 0.14).toInt().coerceAtLeast(24)
            val w = (safeWidth - 20 - sideIndent).coerceAtLeast(100)
            val isLeft = if (!rtl) source == AudioSource.MICROPHONE else source == AudioSource.SYSTEM
            val x = if (isLeft) 10 else (safeWidth - 10 - w).coerceAtLeast(10)
            return x..(x + w)
        }

        /** One colour per source, from the theme's action palette so it suits light and dark. */
        fun sourceColor(source: AudioSource): Color = when (source) {
            AudioSource.MICROPHONE -> UIManager.getColor("Actions.Blue") ?: Color(0x389FD6)
            AudioSource.SYSTEM -> UIManager.getColor("Actions.Green") ?: Color(0x59A869)
        }
    }
}

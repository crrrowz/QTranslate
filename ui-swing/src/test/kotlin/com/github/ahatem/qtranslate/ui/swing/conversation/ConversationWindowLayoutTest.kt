package com.github.ahatem.qtranslate.ui.swing.conversation

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.core.conversation.AudioSource
import com.github.ahatem.qtranslate.core.conversation.ConversationSession
import com.github.ahatem.qtranslate.core.conversation.LiveLine
import com.github.ahatem.qtranslate.core.conversation.TranscriptSegment
import com.github.ahatem.qtranslate.core.localization.LanguageTomlParser
import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import org.junit.jupiter.api.Test
import java.awt.BorderLayout
import java.awt.Point
import java.nio.file.Files
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ConversationWindowLayoutTest {

    private val silentLogger = object : Logger {
        override fun debug(message: String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, error: Throwable?) = Unit
    }

    private fun strings(): ConversationStrings {
        val tempDir = Files.createTempDirectory("conversation-layout-test").toFile()
        val manager = LocalizationManager(tempDir, LanguageTomlParser(), silentLogger)
        return ConversationStrings(manager)
    }

    private fun layoutAll(innerPanel: JPanel, overlay: TranscriptOverlayPane, scrollPane: JScrollPane) {
        innerPanel.doLayout()
        overlay.doLayout()
        scrollPane.doLayout()
        scrollPane.viewport.doLayout()
    }

    private fun cardScreenY(
        view: TranscriptView,
        scrollPane: JScrollPane,
        innerPanel: JPanel,
        segmentId: String
    ): Double {
        val span = view.spans.firstOrNull { it.segment.id == segmentId }
        assertNotNull(span, "Span for $segmentId must exist")
        val cardYInView = view.modelToView2D(span.playStart).y
        return cardYInView - scrollPane.viewport.viewPosition.y +
            SwingUtilities.convertPoint(scrollPane.viewport, 0, 0, innerPanel).y
    }

    @Test
    fun `showing and hiding pause pill does not change transcript viewport bounds`() {
        SwingUtilities.invokeAndWait {
            val view = TranscriptView(strings())
            val scrollPane = JScrollPane(view)
            val pill = JButton("Pause Pill").apply { isVisible = false }
            val overlay = TranscriptOverlayPane(scrollPane, pill)

            val container = JPanel(BorderLayout()).apply {
                setBounds(0, 0, 600, 400)
                add(overlay, BorderLayout.CENTER)
            }
            layoutAll(container, overlay, scrollPane)

            val initialBounds = scrollPane.viewport.bounds
            assertTrue(initialBounds.width > 0 && initialBounds.height > 0)

            // Show pill
            pill.isVisible = true
            layoutAll(container, overlay, scrollPane)

            val boundsWithPill = scrollPane.viewport.bounds
            assertEquals(initialBounds, boundsWithPill, "Showing pill must not change viewport bounds")

            // Hide pill
            pill.isVisible = false
            layoutAll(container, overlay, scrollPane)

            val boundsWithoutPill = scrollPane.viewport.bounds
            assertEquals(initialBounds, boundsWithoutPill, "Hiding pill must not change viewport bounds")
        }
    }

    @Test
    fun `live slot text growing from 0 to 10 lines while recording does not change panel height or viewport bounds`() {
        SwingUtilities.invokeAndWait {
            val strings = strings()
            val livePanel = LiveSlotsPanel(strings)
            val view = TranscriptView(strings)
            val scrollPane = JScrollPane(view)
            val pill = JButton("Pill").apply { isVisible = false }
            val overlay = TranscriptOverlayPane(scrollPane, pill)

            val innerPanel = JPanel(BorderLayout()).apply {
                setBounds(0, 0, 600, 400)
                add(livePanel, BorderLayout.NORTH)
                add(overlay, BorderLayout.CENTER)
            }
            layoutAll(innerPanel, overlay, scrollPane)

            // Empty live speech while recording
            livePanel.render(emptyMap(), visible = true)
            layoutAll(innerPanel, overlay, scrollPane)

            val initialPanelHeight = livePanel.preferredSize.height
            val initialViewportBounds = scrollPane.viewport.bounds
            assertTrue(initialPanelHeight > 0)

            // Text growing to 10 lines
            val tenLinesText = (1..10).joinToString("\n") { "Live line $it" }
            livePanel.render(
                mapOf(
                    AudioSource.MICROPHONE to LiveLine(
                        startMillis = 1000L,
                        text = tenLinesText
                    )
                ),
                visible = true
            )
            layoutAll(innerPanel, overlay, scrollPane)

            assertEquals(
                initialPanelHeight,
                livePanel.preferredSize.height,
                "LiveSlotsPanel preferred height must remain constant when text grows"
            )
            assertEquals(
                initialViewportBounds,
                scrollPane.viewport.bounds,
                "Viewport bounds must remain constant when live slot text grows"
            )
        }
    }

    @Test
    fun `across recording start and stop anchored card on-screen Y is preserved`() {
        SwingUtilities.invokeAndWait {
            val strings = strings()
            val view = TranscriptView(strings)
            view.setSize(600, 400)
            val scrollPane = JScrollPane(view)
            scrollPane.setSize(600, 400)
            val pill = JButton("Pill").apply { isVisible = false }
            val overlay = TranscriptOverlayPane(scrollPane, pill)
            val livePanel = LiveSlotsPanel(strings)

            val innerPanel = JPanel(BorderLayout()).apply {
                setBounds(0, 0, 600, 400)
                add(livePanel, BorderLayout.NORTH)
                add(overlay, BorderLayout.CENTER)
            }
            layoutAll(innerPanel, overlay, scrollPane)

            // 30 settled segments in archive
            val segments = (1..30).map { i ->
                TranscriptSegment(
                    id = "seg-$i",
                    source = if (i % 2 == 1) AudioSource.MICROPHONE else AudioSource.SYSTEM,
                    startMillis = i * 2000L,
                    endMillis = i * 2000L + 1500L,
                    text = "Settled message $i from the speaker",
                    pending = false,
                    failed = false
                )
            }
            val session = ConversationSession(id = "s-1", title = "Session 1", createdAt = 0L, segments = segments)
            view.newestFirst = true
            view.render(session)
            layoutAll(innerPanel, overlay, scrollPane)

            // Rest mouse pointer over card seg-15 scrolled far enough down that viewPosition.y has room to increase
            val targetSpan = view.spans.firstOrNull { it.segment.id == "seg-15" }
            assertNotNull(targetSpan)
            val initialCardYInView = view.modelToView2D(targetSpan.playStart).y
            scrollPane.viewport.viewPosition = Point(0, (initialCardYInView - 140).toInt().coerceAtLeast(0))
            view.pointerPosition = Point(50, initialCardYInView.toInt() + 5)

            val anchorBefore = view.captureScrollAnchor()
            assertNotNull(anchorBefore)
            assertEquals("seg-15", anchorBefore.segmentId)
            val cardYBefore = cardScreenY(view, scrollPane, innerPanel, "seg-15")

            // Start recording: livePanel becomes visible at BorderLayout.NORTH
            livePanel.render(emptyMap(), visible = true)
            layoutAll(innerPanel, overlay, scrollPane)

            // Restore anchor
            view.restoreScrollAnchor(anchorBefore)

            val cardYAfterStart = cardScreenY(view, scrollPane, innerPanel, "seg-15")
            assertEquals(cardYBefore, cardYAfterStart, 1.0, "Card on-screen Y must be preserved (±1 px) on start")

            // Stop recording: livePanel becomes hidden
            val anchorBeforeStop = view.captureScrollAnchor()
            livePanel.render(emptyMap(), visible = false)
            layoutAll(innerPanel, overlay, scrollPane)

            // Restore anchor
            view.restoreScrollAnchor(anchorBeforeStop)

            val cardYAfterStop = cardScreenY(view, scrollPane, innerPanel, "seg-15")
            assertEquals(cardYBefore, cardYAfterStop, 1.0, "Card on-screen Y must be preserved (±1 px) on stop")
        }
    }

    @Test
    fun `anchored card on-screen Y is preserved in SOUTH mode and across order toggle`() {
        SwingUtilities.invokeAndWait {
            val strings = strings()
            val view = TranscriptView(strings)
            view.setSize(600, 400)
            val scrollPane = JScrollPane(view)
            scrollPane.setSize(600, 400)
            val pill = JButton("Pill").apply { isVisible = false }
            val overlay = TranscriptOverlayPane(scrollPane, pill)
            val livePanel = LiveSlotsPanel(strings)

            val innerPanel = JPanel(BorderLayout()).apply {
                setBounds(0, 0, 600, 400)
                add(livePanel, BorderLayout.SOUTH)
                add(overlay, BorderLayout.CENTER)
            }
            layoutAll(innerPanel, overlay, scrollPane)

            val segments = (1..30).map { i ->
                TranscriptSegment(
                    id = "seg-$i",
                    source = if (i % 2 == 1) AudioSource.MICROPHONE else AudioSource.SYSTEM,
                    startMillis = i * 2000L,
                    endMillis = i * 2000L + 1500L,
                    text = "Settled message $i from the speaker",
                    pending = false,
                    failed = false
                )
            }
            val session = ConversationSession(id = "s-1", title = "Session 1", createdAt = 0L, segments = segments)
            view.newestFirst = false
            view.render(session)
            layoutAll(innerPanel, overlay, scrollPane)

            val targetSpan = view.spans.firstOrNull { it.segment.id == "seg-15" }
            assertNotNull(targetSpan)
            val initialCardYInView = view.modelToView2D(targetSpan.playStart).y
            scrollPane.viewport.viewPosition = Point(0, (initialCardYInView - 140).toInt().coerceAtLeast(0))
            view.pointerPosition = Point(50, initialCardYInView.toInt() + 5)

            val cardYBeforeSouth = cardScreenY(view, scrollPane, innerPanel, "seg-15")
            val anchorSouthStart = view.captureScrollAnchor()

            // Start recording in SOUTH mode
            livePanel.render(emptyMap(), visible = true)
            layoutAll(innerPanel, overlay, scrollPane)
            view.restoreScrollAnchor(anchorSouthStart)

            val cardYAfterSouthStart = cardScreenY(view, scrollPane, innerPanel, "seg-15")
            assertEquals(cardYBeforeSouth, cardYAfterSouthStart, 1.0, "Card on-screen Y must be preserved (±1 px) in SOUTH mode on start")

            // Toggle order from oldest-first (SOUTH) to newest-first (NORTH) while recording is active
            val anchorBeforeToggleToNorth = view.captureScrollAnchor()
            view.newestFirst = true
            innerPanel.remove(livePanel)
            innerPanel.add(livePanel, BorderLayout.NORTH)
            layoutAll(innerPanel, overlay, scrollPane)
            view.restoreScrollAnchor(anchorBeforeToggleToNorth)

            val cardYAfterToggleToNorth = cardScreenY(view, scrollPane, innerPanel, "seg-15")
            assertEquals(cardYBeforeSouth, cardYAfterToggleToNorth, 1.0, "Card on-screen Y must be preserved (±1 px) when toggling SOUTH -> NORTH")

            // Toggle order back from newest-first (NORTH) to oldest-first (SOUTH)
            val anchorBeforeToggleToSouth = view.captureScrollAnchor()
            view.newestFirst = false
            innerPanel.remove(livePanel)
            innerPanel.add(livePanel, BorderLayout.SOUTH)
            layoutAll(innerPanel, overlay, scrollPane)
            view.restoreScrollAnchor(anchorBeforeToggleToSouth)

            val cardYAfterToggleToSouth = cardScreenY(view, scrollPane, innerPanel, "seg-15")
            assertEquals(cardYBeforeSouth, cardYAfterToggleToSouth, 1.0, "Card on-screen Y must be preserved (±1 px) when toggling NORTH -> SOUTH")
        }
    }
}

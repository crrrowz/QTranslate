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
import java.awt.Dimension
import java.awt.Point
import java.nio.file.Files
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JScrollPane
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

    @Test
    fun `showing and hiding pause pill does not change transcript viewport bounds`() {
        val view = TranscriptView(strings())
        val scrollPane = JScrollPane(view)
        val pill = JButton("Pause Pill").apply { isVisible = false }
        val overlay = TranscriptOverlayPane(scrollPane, pill)

        val container = JPanel(BorderLayout()).apply {
            setBounds(0, 0, 600, 400)
            add(overlay, BorderLayout.CENTER)
            doLayout()
        }
        overlay.doLayout()
        scrollPane.doLayout()

        val initialBounds = scrollPane.viewport.bounds
        assertTrue(initialBounds.width > 0 && initialBounds.height > 0)

        // Show pill
        pill.isVisible = true
        container.doLayout()
        overlay.doLayout()
        scrollPane.doLayout()

        val boundsWithPill = scrollPane.viewport.bounds
        assertEquals(initialBounds, boundsWithPill, "Showing pill must not change viewport bounds")

        // Hide pill
        pill.isVisible = false
        container.doLayout()
        overlay.doLayout()
        scrollPane.doLayout()

        val boundsWithoutPill = scrollPane.viewport.bounds
        assertEquals(initialBounds, boundsWithoutPill, "Hiding pill must not change viewport bounds")
    }

    @Test
    fun `live slot text growing from 0 to 10 lines while recording does not change panel height or viewport bounds`() {
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
            doLayout()
        }
        overlay.doLayout()
        scrollPane.doLayout()

        // Empty live speech while recording
        livePanel.render(emptyMap(), visible = true)
        innerPanel.doLayout()
        overlay.doLayout()
        scrollPane.doLayout()

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
        innerPanel.doLayout()
        overlay.doLayout()
        scrollPane.doLayout()

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

    @Test
    fun `across recording start and stop anchored card on-screen Y is preserved`() {
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
            doLayout()
        }
        overlay.doLayout()
        scrollPane.doLayout()

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
        view.newestFirst = false
        view.render(session)
        innerPanel.doLayout()
        overlay.doLayout()
        scrollPane.doLayout()

        // Rest mouse pointer over card seg-15
        val targetSpan = view.spans.firstOrNull { it.segment.id == "seg-15" }
        assertNotNull(targetSpan)
        val initialCardYInView = view.modelToView2D(targetSpan.playStart).y
        scrollPane.viewport.viewPosition = Point(0, (initialCardYInView - 40).toInt().coerceAtLeast(0))
        view.pointerPosition = Point(50, initialCardYInView.toInt() + 5)

        val anchorBefore = view.captureScrollAnchor()
        assertNotNull(anchorBefore)
        assertEquals("seg-15", anchorBefore.segmentId)
        val cardYBefore = view.modelToView2D(targetSpan.playStart).y - scrollPane.viewport.viewPosition.y

        // Start recording: livePanel becomes visible
        livePanel.render(emptyMap(), visible = true)
        innerPanel.doLayout()
        overlay.doLayout()
        scrollPane.doLayout()

        // Restore anchor
        view.restoreScrollAnchor(anchorBefore)

        val cardYAfterStart = view.modelToView2D(targetSpan.playStart).y - scrollPane.viewport.viewPosition.y
        assertEquals(cardYBefore, cardYAfterStart, 1.0, "Card on-screen Y must be preserved (±1 px) on start")

        // Stop recording: livePanel becomes hidden
        val anchorBeforeStop = view.captureScrollAnchor()
        livePanel.render(emptyMap(), visible = false)
        innerPanel.doLayout()
        overlay.doLayout()
        scrollPane.doLayout()

        // Restore anchor
        view.restoreScrollAnchor(anchorBeforeStop)

        val cardYAfterStop = view.modelToView2D(targetSpan.playStart).y - scrollPane.viewport.viewPosition.y
        assertEquals(cardYBefore, cardYAfterStop, 1.0, "Card on-screen Y must be preserved (±1 px) on stop")
    }
}

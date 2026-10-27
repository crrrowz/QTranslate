package com.github.ahatem.qtranslate.ui.swing.conversation

import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.LayoutManager
import javax.swing.JComponent
import javax.swing.JLayeredPane
import javax.swing.JScrollPane

/**
 * Hosts [transcriptScroll] and overlays [pill] centred horizontally near the top edge
 * of the transcript viewport without taking part in layout flow or displacing the transcript.
 */
class TranscriptOverlayPane(
    val transcriptScroll: JScrollPane,
    val pill: JComponent
) : JLayeredPane() {

    init {
        isOpaque = false
        // Layer 0: normal component layer for scrollpane
        add(transcriptScroll, DEFAULT_LAYER)
        // Layer 300: modal/popup layer so the pill sits clearly above the transcript contents
        add(pill, POPUP_LAYER)
        layout = OverlayLayout()

        pill.addComponentListener(object : java.awt.event.ComponentAdapter() {
            override fun componentShown(e: java.awt.event.ComponentEvent?) {
                doLayout()
                repaint()
            }

            override fun componentHidden(e: java.awt.event.ComponentEvent?) {
                doLayout()
                repaint()
            }
        })
        pill.addPropertyChangeListener("text") {
            doLayout()
            repaint()
        }
    }

    private inner class OverlayLayout : LayoutManager {
        override fun addLayoutComponent(name: String?, comp: Component?) = Unit
        override fun removeLayoutComponent(comp: Component?) = Unit

        override fun preferredLayoutSize(parent: Container): Dimension =
            transcriptScroll.preferredSize ?: Dimension(100, 100)

        override fun minimumLayoutSize(parent: Container): Dimension =
            transcriptScroll.minimumSize ?: Dimension(50, 50)

        override fun layoutContainer(parent: Container) {
            val insets = parent.insets
            val w = parent.width - insets.left - insets.right
            val h = parent.height - insets.top - insets.bottom

            // 1. Fill entire layered pane with the scrollpane
            transcriptScroll.setBounds(insets.left, insets.top, w, h)

            // 2. Position pill over top edge of viewport (avoid covering vertical scrollbar)
            val pillPref = pill.preferredSize
            val pillW = pillPref.width.coerceAtLeast(32)
            val pillH = pillPref.height.coerceAtLeast(20)

            // The viewport bounds within transcriptScroll
            val viewportBounds = transcriptScroll.viewport.bounds
            val scrollBarW = if (transcriptScroll.verticalScrollBar.isVisible) transcriptScroll.verticalScrollBar.width else 0

            val availW = (w - scrollBarW).coerceAtLeast(pillW)
            val pillX = insets.left + viewportBounds.x + (availW - pillW) / 2
            val pillY = insets.top + viewportBounds.y + 6 // 6px padding from top edge

            pill.setBounds(pillX, pillY, pillW, pillH)
        }
    }
}

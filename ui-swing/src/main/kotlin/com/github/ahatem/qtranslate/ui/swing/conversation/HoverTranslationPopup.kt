package com.github.ahatem.qtranslate.ui.swing.conversation

import net.miginfocom.swing.MigLayout
import java.awt.Color
import java.awt.Rectangle
import java.awt.Window
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JWindow
import javax.swing.SwingUtilities
import javax.swing.UIManager

/**
 * The small card that appears over a hovered word or a selected phrase: its translation, what the
 * whole sentence means, and buttons to replay it or look up its meanings.
 *
 * Never takes focus, so it can appear and vanish while the user keeps typing in Discord.
 */
class HoverTranslationPopup(owner: Window, private val strings: ConversationStrings) {

    data class Content(
        val text: String,
        val translation: String? = null,
        val error: String? = null,
        /** Translation of the sentence the word sits in; null for a phrase or while loading. */
        val context: String? = null,
        /** Null until requested; empty when the dictionary found nothing. */
        val meanings: List<String>? = null,
        val meaningsLoading: Boolean = false,
        val canPlayWord: Boolean = false,
        val canPlaySentence: Boolean = false
    )

    var onPlayWord: () -> Unit = {}
    var onPlaySentence: () -> Unit = {}
    var onMeanings: () -> Unit = {}
    var onCopy: () -> Unit = {}
    /** Called when the pointer enters (true) or leaves (false) the card. */
    var onHoverChanged: (Boolean) -> Unit = {}

    private val window = JWindow(owner).apply {
        focusableWindowState = false
        type = Window.Type.POPUP
    }

    private val source = JLabel()
    private val translation = JLabel()
    private val context = JLabel()
    private val meanings = JLabel()
    private val playWord = JButton("▶ ${strings.playWord}")
    private val playSentence = JButton("▶ ${strings.playSentence}")
    private val meaningsButton = JButton(strings.meanings)
    private val copyButton = JButton(strings.copy)

    private val panel = JPanel(MigLayout("wrap 1, insets 10 12 10 12, gap 4, wmax 420")).apply {
        border = BorderFactory.createLineBorder(UIManager.getColor("Component.borderColor") ?: Color.GRAY)
        add(source)
        add(translation)
        add(context)
        add(meanings)
        add(JPanel(MigLayout("insets 6 0 0 0, gap 4")).apply {
            isOpaque = false
            listOf(playWord, playSentence, meaningsButton, copyButton).forEach { button ->
                button.putClientProperty("JButton.buttonType", "toolBarButton")
                button.isFocusable = false
                add(button)
            }
        })
    }

    private var current: Content? = null

    init {
        window.contentPane = panel
        source.foreground = UIManager.getColor("Label.disabledForeground")
        context.foreground = UIManager.getColor("Label.disabledForeground")
        translation.font = translation.font.deriveFont(translation.font.size2D + 3f)
        playWord.addActionListener { onPlayWord() }
        playSentence.addActionListener { onPlaySentence() }
        meaningsButton.addActionListener { onMeanings() }
        copyButton.addActionListener { onCopy() }

        val hover = object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) = onHoverChanged(true)
            override fun mouseExited(e: MouseEvent) {
                val point = SwingUtilities.convertPoint(e.component, e.point, panel)
                if (!panel.contains(point)) onHoverChanged(false)
            }
        }
        (listOf(panel, source, translation, context, meanings, playWord, playSentence, meaningsButton, copyButton))
            .forEach { it.addMouseListener(hover) }
    }

    val isVisible: Boolean get() = window.isVisible

    /** The text the card is about, or null when hidden. */
    val text: String? get() = current?.text?.takeIf { window.isVisible }

    /** Shows the card just below [anchor] (screen coordinates), flipping above near the screen edge. */
    fun show(anchor: Rectangle, content: Content) {
        update(content)
        val screen = window.graphicsConfiguration?.bounds ?: owner().graphicsConfiguration.bounds
        val size = window.size
        var x = anchor.x
        var y = anchor.y + anchor.height + 6
        if (y + size.height > screen.y + screen.height) y = anchor.y - size.height - 6
        if (x + size.width > screen.x + screen.width) x = screen.x + screen.width - size.width - 4
        window.setLocation(x.coerceAtLeast(screen.x), y.coerceAtLeast(screen.y))
        window.isVisible = true
    }

    fun update(content: Content) {
        current = content
        source.text = html(content.text, bold = true)
        translation.text = html(
            when {
                content.error != null -> strings.failed(content.error)
                content.translation == null -> strings.loading
                else -> content.translation
            }
        )
        context.isVisible = content.context != null
        context.text = content.context?.let { html("${strings.inContext} $it", small = true) }
        meanings.isVisible = content.meanings != null || content.meaningsLoading
        meanings.text = when {
            content.meaningsLoading -> html(strings.loading, small = true)
            content.meanings.isNullOrEmpty() -> html(strings.noMeanings, small = true)
            else -> "<html><div style='width:360px'>" +
                content.meanings.joinToString("<br>") { "• ${escape(it)}" } + "</div></html>"
        }
        playWord.isVisible = content.canPlayWord
        playSentence.isVisible = content.canPlaySentence
        meaningsButton.isEnabled = content.meanings == null && !content.meaningsLoading
        copyButton.isEnabled = content.translation != null
        window.pack()
    }

    fun hide() {
        window.isVisible = false
        current = null
    }

    fun dispose() = window.dispose()

    private fun owner(): Window = window.owner

    private fun html(text: String, bold: Boolean = false, small: Boolean = false): String {
        val body = escape(text).let { if (bold) "<b>$it</b>" else it }
        val size = if (small) "font-size:95%;" else ""
        // A fixed width only once the text is long, so a one-word card stays compact.
        val width = if (text.length > 40) "width:360px;" else ""
        return "<html><div style='$width$size'>$body</div></html>"
    }

    private fun escape(text: String) =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>")
}

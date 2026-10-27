package com.github.ahatem.qtranslate.ui.swing.conversation

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.core.conversation.AudioApplication
import com.github.ahatem.qtranslate.core.conversation.AudioBackend
import com.github.ahatem.qtranslate.core.conversation.AudioDevice
import com.github.ahatem.qtranslate.core.conversation.AudioLevels
import com.github.ahatem.qtranslate.core.conversation.AudioSource
import com.github.ahatem.qtranslate.core.conversation.ConversationFeature
import com.github.ahatem.qtranslate.core.conversation.ConversationSession
import com.github.ahatem.qtranslate.core.conversation.DeviceCheckHeuristics
import com.github.ahatem.qtranslate.core.conversation.LiveRecognitionStatus
import com.github.ahatem.qtranslate.core.conversation.RecorderNotice
import com.github.ahatem.qtranslate.core.conversation.RecorderPhase
import com.github.ahatem.qtranslate.core.conversation.RecorderState
import com.github.ahatem.qtranslate.core.conversation.RecordingOptions
import com.github.ahatem.qtranslate.core.conversation.SessionStatus
import com.github.ahatem.qtranslate.core.conversation.StreamState
import com.github.ahatem.qtranslate.core.conversation.StreamStatus
import com.formdev.flatlaf.extras.FlatSVGIcon
import com.github.ahatem.qtranslate.ui.swing.shared.icon.Icons
import com.github.ahatem.qtranslate.core.conversation.TranscriptSegment
import com.github.ahatem.qtranslate.core.conversation.formatTimestamp
import com.github.ahatem.qtranslate.core.localization.getDisplayName
import com.github.michaelbull.result.fold
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import net.miginfocom.swing.MigLayout
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.ComponentOrientation
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Insets
import java.awt.Point
import java.awt.Rectangle
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import javax.swing.BorderFactory
import javax.swing.ButtonGroup
import javax.swing.DefaultComboBoxModel
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JFileChooser
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.JTextField
import javax.swing.JToggleButton
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.UIManager
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.filechooser.FileNameExtensionFilter
import javax.swing.text.DefaultHighlighter

/**
 * Conversation Sessions: records a call (your microphone and the call's audio as two streams),
 * shows the live transcript and translates any word you point at.
 *
 * Left: saved sessions with search. Right: device and language pickers, the record controls with
 * a status light per stream, and the transcript. Hovering a word shows its translation and the
 * sentence's; selecting a phrase translates the phrase; clicking a speaker label replays that
 * stretch of audio.
 */
class ConversationWindow(
    private val feature: ConversationFeature,
    private val strings: ConversationStrings,
    /** Languages the active translator offers, for the target picker. */
    private val languages: () -> List<LanguageCode>,
    /** The main window's target language, used until the user picks one here. */
    private val defaultTarget: () -> LanguageCode,
    private val isRtl: () -> Boolean,
    /** Opens Settings on the Plugins page, where Speech Recognition is configured. */
    private val onOpenSpeechSettings: () -> Unit = {}
) : JFrame(strings.title) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
    private val recorder get() = feature.recorder

    /** A device picker entry; [device] null is the "None" choice. */
    private data class DeviceItem(val device: AudioDevice?, val label: String, val app: AudioApplication? = null) {
        override fun toString() = label
    }

    private val microphoneCombo = JComboBox<DeviceItem>()
    private val systemCombo = JComboBox<DeviceItem>()
    private val deviceModeButton = JToggleButton(strings.captureModeDevice)
    private val appModeButton = JToggleButton(strings.captureModeApp)
    private val captureModeGroup = ButtonGroup().apply {
        add(deviceModeButton)
        add(appModeButton)
    }
    private var isAppMode = false
    private var cachedOutputs = emptyList<AudioDevice>()
    private var cachedApps = emptyList<AudioApplication>()

    private val refreshDevicesButton = JButton(FlatSVGIcon(Icons.RETRY, 14, 14)).apply {
        toolTipText = strings.refreshDevices
        putClientProperty("JButton.buttonType", "toolBarButton")
        margin = Insets(2, 6, 2, 6)
    }
    private val myLanguageCombo = JComboBox<LanguageCode>()
    private val callLanguageCombo = JComboBox<LanguageCode>()
    private val targetCombo = JComboBox<LanguageCode>()
    private val keepAudioCheck = JCheckBox(strings.keepAudio).apply { toolTipText = strings.keepAudioTooltip }
    private val chatLayoutCheck = JCheckBox(strings.chatLayout, true).apply { toolTipText = strings.chatLayoutTooltip }

    private val recordButton = JButton()
    private val stopButton = JButton("■ ${strings.stop}")
    private val timerLabel = JLabel("0:00")
    private val microphoneLight = StreamLight(strings.speakerMe, AudioSource.MICROPHONE)
    private val systemLight = StreamLight(strings.speakerCall, AudioSource.SYSTEM)
    private val pendingLabel = JLabel()
    private val retryFailedButton = JButton()
    private val speechLabel = JLabel()
    private val speechSettingsButton = JButton(strings.speechSettings)

    private var fillingLanguages = false

    private val testDevicesButton = JButton(strings.testDevices)
    private val deviceWarningText = wrappingText("")
    private val deviceWarningBar = banner(deviceWarningText, UIManager.getColor("Actions.Yellow")) {
        deviceWarningDismissed = true
    }.apply { isVisible = false }
    private var deviceWarningDismissed = false
    private var testingDevices = false

    private val noticeText = wrappingText("")
    private val noticeBar = banner(noticeText, UIManager.getColor("Actions.Red")) { recorder.clearNotice() }
    private val privacyBar = banner(wrappingText(strings.privacyNotice), UIManager.getColor("Actions.Yellow")) {
        feature.preferences = feature.preferences.copy(privacyNoticeDismissed = true)
    }

    private val sessionsModel = DefaultListModel<ConversationSession>()
    private val sessionsList = JList(sessionsModel)
    private val searchField = JTextField()
    private val newButton = JButton("+ ${strings.newSession}")
    private val renameButton = JButton(strings.rename)
    private val exportButton = JButton(strings.export)
    private val deleteButton = JButton(strings.delete)

    private val transcript = TranscriptView(strings)
    internal val liveSlotsPanel = LiveSlotsPanel(strings)
    private val transcriptInnerPanel = JPanel(BorderLayout())
    private val micMuteButton = JButton(FlatSVGIcon(Icons.MIC, 15, 15)).apply {
        toolTipText = strings.micUnmutedTooltip
        putClientProperty("JButton.buttonType", "toolBarButton")
        margin = Insets(2, 6, 2, 6)
        addActionListener {
            recorder.isMicrophoneMuted = !recorder.isMicrophoneMuted
            val muted = recorder.isMicrophoneMuted
            toolTipText = if (muted) strings.micMutedTooltip else strings.micUnmutedTooltip
            (icon as? FlatSVGIcon)?.colorFilter = if (muted) FlatSVGIcon.ColorFilter { Color(0xE05555) } else null
            repaint()
        }
    }
    private val callMuteButton = JButton(FlatSVGIcon(Icons.SPEAK, 15, 15)).apply {
        toolTipText = strings.callUnmutedTooltip
        putClientProperty("JButton.buttonType", "toolBarButton")
        margin = Insets(2, 6, 2, 6)
        addActionListener {
            recorder.isSystemMuted = !recorder.isSystemMuted
            val muted = recorder.isSystemMuted
            toolTipText = if (muted) strings.callMutedTooltip else strings.callUnmutedTooltip
            (icon as? FlatSVGIcon)?.colorFilter = if (muted) FlatSVGIcon.ColorFilter { Color(0xE05555) } else null
            repaint()
        }
    }
    private val transcriptScroll = JScrollPane(transcript)
    private val orderButton = JButton(strings.orderNewestAtTop).apply {
        putClientProperty("JButton.buttonType", "toolBarButton")
        toolTipText = strings.orderTooltip
    }
    internal val pausePill = JButton().apply {
        isFocusable = false
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        putClientProperty("JButton.buttonType", "roundRect")
        putClientProperty("FlatLaf.styleClass", "small")
        foreground = UIManager.getColor("Actions.Blue") ?: Color(0x389FD6)
        isVisible = false
        addActionListener { flushBufferedUpdates() }
        addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) {
                renderGate.onPointerEntered()
                freezeGraceTimer.stop()
            }
        })
    }
    internal val transcriptOverlayPane = TranscriptOverlayPane(transcriptScroll, pausePill)
    private val popup = HoverTranslationPopup(this, strings)

    /** Gate deciding whether transcript DOM updates are frozen while reading/hovering. */
    private val renderGate = RenderGate<ConversationSession?>()
    private val freezeGraceTimer = Timer(RenderGate.DEFAULT_GRACE_MILLIS.toInt()) { checkAndFlushIfIdle() }.apply { isRepeats = false }

    /** The saved session on screen when nothing is recording. */
    private var viewed: ConversationSession? = null
    private var lastPhase = RecorderPhase.IDLE
    private var loadingDevices = false

    // Hover state: which word the pointer rests on, and the timers that delay showing and hiding
    // the card so sweeping across text does not flash a card per word.
    private var hoverRange: IntRange? = null
    private var hoverSpan: TranscriptView.SegmentSpan? = null
    private var hoverCard = false
    private var popupHovered = false
    private var popupJob: Job? = null
    private var popupContent: HoverTranslationPopup.Content? = null
    private val showTimer = Timer(SHOW_DELAY_MILLIS) { showWordCard() }.apply { isRepeats = false }
    private val hideTimer = Timer(HIDE_DELAY_MILLIS) { if (!popupHovered) hideCard() }.apply { isRepeats = false }
    private val searchTimer = Timer(250) { reloadSessions() }.apply { isRepeats = false }

    init {
        com.github.ahatem.qtranslate.ui.swing.shared.util.AppIcons.applyTo(this)
        defaultCloseOperation = DO_NOTHING_ON_CLOSE
        minimumSize = Dimension(780, 480)
        // Never larger than the screen it opens on: a 1080p laptop at 150% has ~1280x700 to give.
        val screen = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
        size = Dimension(minOf(1120, screen.width - 40), minOf(740, screen.height - 40))
        setLocationRelativeTo(null)

        contentPane = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, buildSidebar(), buildMain()).apply {
            dividerLocation = 230
            resizeWeight = 0.0
            border = null
        }
        wireEvents()
        scope.launch { recorder.state.collect(::render) }
    }

    /** Brings the window up, refreshing devices and sessions. */
    fun open() {
        if (!isVisible) {
            val rtl = isRtl()
            applyComponentOrientation(if (rtl) ComponentOrientation.RIGHT_TO_LEFT else ComponentOrientation.LEFT_TO_RIGHT)
            // The transcript keeps its own direction per paragraph, whatever the UI language.
            transcript.componentOrientation = ComponentOrientation.LEFT_TO_RIGHT
            transcript.rtlLayout = rtl
            loadPreferences()
            loadDevices()
            reloadSessions()
        }
        isVisible = true
        state = NORMAL
        toFront()
    }

    override fun dispose() {
        popup.dispose()
        scope.cancel()
        super.dispose()
    }

    // ---------------------------------------------------------------------------------------
    // Layout
    // ---------------------------------------------------------------------------------------

    // Wide enough for its three buttons; the split takes any shortfall from the transcript side.
    private fun buildSidebar(): JPanel = shrinkable(MigLayout("fill, wrap 1, insets 10, gap 6", "[grow,fill]", "[][][grow,fill][]"), minWidth = 220).apply {
        searchField.putClientProperty("JTextField.placeholderText", strings.searchPlaceholder)
        searchField.putClientProperty("JTextField.showClearButton", true)
        sessionsList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        sessionsList.cellRenderer = SessionCellRenderer()
        add(newButton)
        add(searchField)
        add(JScrollPane(sessionsList))
        add(shrinkable(MigLayout("insets 0, gap 4, fillx", "[grow,fill][grow,fill][grow,fill]")).apply {
            listOf(renameButton, exportButton, deleteButton).forEach { button ->
                button.margin = java.awt.Insets(2, 6, 2, 6)
                add(button, "wmin 40")
            }
        })
    }

    private fun buildMain(): JPanel = JPanel(BorderLayout()).apply {
        // Device names run to 70 characters, and a combo box will not shrink below its widest item.
        // A typical-length prototype sizes them instead, and "wmin" lets each give way further, so
        // the pickers share whatever width the window has rather than overlapping or clipping.
        listOf(microphoneCombo, systemCombo).forEach { it.prototypeDisplayValue = DeviceItem(null, "SteelSeries Sonar - Chat (Virtual)") }
        val pickers = shrinkable(MigLayout("fillx, insets 8 12 6 12, gap 10 6", "[trailing][grow,fill][trailing][grow,fill][trailing][grow,fill]")).apply {
            // Row 1: Audio Devices with Mute, Test and Refresh buttons
            add(JLabel(strings.microphone))
            val micPanel = JPanel(BorderLayout(4, 0)).apply {
                isOpaque = false
                add(microphoneCombo, BorderLayout.CENTER)
                add(micMuteButton, BorderLayout.EAST)
            }
            add(micPanel, "wmin 100, growx")

            val callHeader = JPanel(FlowLayout(FlowLayout.TRAILING, 4, 0)).apply {
                isOpaque = false
                add(JLabel(strings.callAudio).apply { toolTipText = strings.callAudioTooltip })
                add(deviceModeButton.apply {
                    putClientProperty("JButton.buttonType", "toolBarButton")
                    toolTipText = strings.captureModeTooltip
                    margin = Insets(1, 4, 1, 4)
                    font = font.deriveFont((font.size2D - 2f).coerceAtLeast(10f))
                })
                add(appModeButton.apply {
                    putClientProperty("JButton.buttonType", "toolBarButton")
                    toolTipText = strings.captureModeTooltip
                    margin = Insets(1, 4, 1, 4)
                    font = font.deriveFont((font.size2D - 2f).coerceAtLeast(10f))
                })
            }
            add(callHeader)
            val callPanel = JPanel(BorderLayout(4, 0)).apply {
                isOpaque = false
                add(systemCombo.apply { toolTipText = strings.callAudioTooltip }, BorderLayout.CENTER)
                add(callMuteButton, BorderLayout.EAST)
            }
            add(callPanel, "wmin 100, growx")

            add(JLabel())
            val testPanel = JPanel(FlowLayout(FlowLayout.TRAILING, 4, 0)).apply {
                isOpaque = false
                add(testDevicesButton)
                add(refreshDevicesButton)
            }
            add(testPanel, "wrap")

            // Row 2: Languages
            add(JLabel(strings.myLanguage))
            add(myLanguageCombo, "wmin 90, growx")
            add(JLabel(strings.callLanguage))
            add(callLanguageCombo, "wmin 90, growx")
            add(JLabel(strings.translateTo))
            add(targetCombo, "wmin 90, growx, wrap")
        }
        val speechRow = shrinkable(MigLayout("fillx, insets 0 12 4 12, gap 8", "[grow,fill][]")).apply {
            add(speechLabel, "wmin 0")
            add(speechSettingsButton)
        }
        val recordRow = shrinkable(MigLayout("fillx, insets 4 12 2 12, gap 10", "[][][][][]push[][]")).apply {
            recordButton.putClientProperty("JButton.buttonType", "default")
            add(recordButton)
            add(stopButton)
            add(timerLabel.apply { font = font.deriveFont(font.size2D + 4f) })
            add(pendingLabel)
            add(retryFailedButton)
            add(microphoneLight, "wmin 0")
            add(systemLight, "wmin 0")
        }
        val keepAudioRow = shrinkable(MigLayout("fillx, insets 0 12 6 12", "[][grow,fill][]")).apply {
            add(keepAudioCheck, "wmin 0")
            add(chatLayoutCheck, "wmin 0")
            add(orderButton)
        }
        val top = JPanel(MigLayout("fillx, wrap 1, insets 0, gap 0", "[grow,fill]")).apply {
            add(pickers)
            add(speechRow)
            add(recordRow)
            add(keepAudioRow)
            add(privacyBar)
            add(deviceWarningBar)
            add(noticeBar)
        }
        add(top, BorderLayout.NORTH)
        transcriptInnerPanel.apply {
            add(liveSlotsPanel, BorderLayout.NORTH)
            add(transcriptOverlayPane, BorderLayout.CENTER)
        }
        transcriptScroll.border = BorderFactory.createMatteBorder(1, 0, 1, 0, UIManager.getColor("Component.borderColor"))
        transcriptScroll.verticalScrollBar.unitIncrement = 16
        add(transcriptInnerPanel, BorderLayout.CENTER)
        add(JPanel(BorderLayout()).apply {
            add(JLabel(strings.interactionHint).apply {
                foreground = UIManager.getColor("Label.disabledForeground")
                border = BorderFactory.createEmptyBorder(6, 12, 6, 12)
            }, BorderLayout.CENTER)
        }, BorderLayout.SOUTH)
    }

    /**
     * A panel that never asks for more width than it is given. MigLayout reports its preferred
     * width as the minimum, which made the parent clip the panel instead of shrinking it; the
     * children's own minimums and wmin constraints still apply inside.
     */
    private fun shrinkable(layout: MigLayout, minWidth: Int = 0): JPanel = object : JPanel(layout) {
        override fun getMinimumSize(): Dimension = Dimension(minWidth, super.getMinimumSize().height)
    }

    /** Read-only text that wraps to the window's width, which a JLabel does not do on its own. */
    private fun wrappingText(text: String) = object : javax.swing.JTextArea(text) {
        // A wrapping text area reports the height for the width it last had; sized to its current
        // width first, it reports the height it needs now, so the last line is never cut off.
        override fun getPreferredSize(): Dimension {
            if (width > 0) setSize(width, Short.MAX_VALUE.toInt())
            return super.getPreferredSize()
        }
    }.apply {
        // The height it needs is only known once it has its new width, so lay out once more then.
        addComponentListener(object : java.awt.event.ComponentAdapter() {
            private var lastWidth = -1
            override fun componentResized(e: java.awt.event.ComponentEvent) {
                if (width != lastWidth) {
                    lastWidth = width
                    SwingUtilities.invokeLater { revalidate() }
                }
            }
        })
        isEditable = false
        isFocusable = false
        lineWrap = true
        wrapStyleWord = true
        isOpaque = false
        border = null
        font = UIManager.getFont("Label.font")
    }

    private fun banner(text: javax.swing.JTextArea, accent: Color?, onDismiss: () -> Unit): JPanel =
        JPanel(MigLayout("fillx, insets 6 12 6 12", "[grow,fill][]")).apply {
            border = BorderFactory.createMatteBorder(0, 4, 0, 0, accent ?: Color.ORANGE)
            add(text, "wmin 100")
            add(JButton("✕").apply {
                putClientProperty("JButton.buttonType", "toolBarButton")
                toolTipText = strings.dismiss
                addActionListener { this@apply.parent.isVisible = false; onDismiss() }
            })
        }

    // ---------------------------------------------------------------------------------------
    // Events
    // ---------------------------------------------------------------------------------------

    private fun wireEvents() {
        addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent) = onClose()
            override fun windowDeactivated(e: WindowEvent) = hideCard()
            // Coming back from Settings: the speech service may have just been set up.
            override fun windowActivated(e: WindowEvent) = renderSpeechStatus()
        })
        recordButton.addActionListener {
            when (recorder.state.value.phase) {
                RecorderPhase.IDLE -> startRecording()
                RecorderPhase.RECORDING -> recorder.pause()
                RecorderPhase.PAUSED -> recorder.resume()
                RecorderPhase.STOPPING -> Unit
            }
        }
        stopButton.addActionListener {
            scope.launch {
                val current = recorder.state.value.session
                recorder.stop()
                if (current != null) {
                    val choice = JOptionPane.showConfirmDialog(
                        this@ConversationWindow,
                        strings.confirmSaveSession,
                        strings.confirmSaveTitle,
                        JOptionPane.YES_NO_OPTION,
                        JOptionPane.QUESTION_MESSAGE
                    )
                    if (choice == JOptionPane.NO_OPTION) {
                        feature.repository.delete(current.id)
                        viewed = null
                        transcript.render(null)
                        reloadSessions()
                    }
                }
            }
        }
        refreshDevicesButton.addActionListener { loadDevices() }
        testDevicesButton.addActionListener { testDevices() }
        deviceModeButton.addActionListener {
            if (isAppMode) {
                isAppMode = false
                deviceModeButton.isSelected = true
                appModeButton.isSelected = false
                feature.preferences = feature.preferences.copy(systemAudioMode = "device")
                populateSystemItems()
                deviceWarningDismissed = false
                checkDeviceWarnings()
            }
        }
        appModeButton.addActionListener {
            if (!isAppMode) {
                isAppMode = true
                appModeButton.isSelected = true
                deviceModeButton.isSelected = false
                feature.preferences = feature.preferences.copy(systemAudioMode = "app")
                populateSystemItems()
                deviceWarningDismissed = false
                checkDeviceWarnings()
            }
        }
        microphoneCombo.addActionListener {
            deviceWarningDismissed = false
            checkDeviceWarnings()
        }
        systemCombo.addActionListener {
            deviceWarningDismissed = false
            checkDeviceWarnings()
        }
        myLanguageCombo.addActionListener {
            if (fillingLanguages) return@addActionListener
            (myLanguageCombo.selectedItem as? LanguageCode)?.let { lang ->
                if (feature.preferences.spokenLanguage == lang.tag) return@let
                feature.preferences = feature.preferences.copy(spokenLanguage = lang.tag)
                if (recorder.state.value.isActive) {
                    recorder.updateSpokenLanguage(lang)
                }
            }
        }
        callLanguageCombo.addActionListener {
            if (fillingLanguages) return@addActionListener
            (callLanguageCombo.selectedItem as? LanguageCode)?.let { lang ->
                if (feature.preferences.callLanguage == lang.tag) return@let
                feature.preferences = feature.preferences.copy(callLanguage = lang.tag)
                if (recorder.state.value.isActive) {
                    recorder.updateCallLanguage(lang)
                }
            }
        }
        speechSettingsButton.addActionListener {
            onOpenSpeechSettings()
            loadPreferences()
        }
        addWindowListener(object : java.awt.event.WindowAdapter() {
            override fun windowActivated(e: java.awt.event.WindowEvent) = loadPreferences()
        })
        retryFailedButton.addActionListener { retryAllFailed() }
        chatLayoutCheck.addActionListener {
            val enabled = chatLayoutCheck.isSelected
            transcript.chatLayout = enabled
            feature.preferences = feature.preferences.copy(chatLayout = enabled)
        }
        orderButton.addActionListener {
            transcript.newestFirst = !transcript.newestFirst
            orderButton.text = if (transcript.newestFirst) strings.orderNewestAtTop else strings.orderOldestAtTop
            transcriptInnerPanel.remove(liveSlotsPanel)
            transcriptInnerPanel.add(liveSlotsPanel, if (transcript.newestFirst) BorderLayout.NORTH else BorderLayout.SOUTH)
            transcriptInnerPanel.revalidate()
            transcriptInnerPanel.repaint()
        }
        newButton.addActionListener {
            if (!recorder.state.value.isActive) {
                sessionsList.clearSelection()
                viewed = null
                recorder.show(null)
                transcript.render(null)
            }
        }
        targetCombo.addActionListener {
            (targetCombo.selectedItem as? LanguageCode)?.let { feature.preferences = feature.preferences.copy(targetLanguage = it.tag) }
            hideCard()
        }
        sessionsList.addListSelectionListener { e ->
            if (e.valueIsAdjusting || recorder.state.value.isActive) return@addListSelectionListener
            val selected = sessionsList.selectedValue ?: return@addListSelectionListener
            viewed = feature.repository.load(selected.id) ?: selected
            transcript.render(viewed)
            highlightSearch()
            updateSessionButtons()
            renderRetryButton()
        }
        sessionsList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) { if (e.clickCount == 2) renameSelected() }
        })
        renameButton.addActionListener { renameSelected() }
        exportButton.addActionListener { exportSelected() }
        deleteButton.addActionListener { deleteSelected() }
        searchField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = searchTimer.restart()
            override fun removeUpdate(e: DocumentEvent) = searchTimer.restart()
            override fun changedUpdate(e: DocumentEvent) = searchTimer.restart()
        })

        var pressPoint: Point? = null
        val mouse = object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) {
                renderGate.onPointerEntered()
                freezeGraceTimer.stop()
            }
            override fun mousePressed(e: MouseEvent) {
                pressPoint = e.point
            }
            override fun mouseMoved(e: MouseEvent) {
                renderGate.onPointerEntered()
                freezeGraceTimer.stop()
                onTranscriptHover(e)
            }
            override fun mouseExited(e: MouseEvent) {
                hoverCard = false
                val inViewport = transcriptScroll.viewport.mousePosition != null
                val inPill = pausePill.isVisible && pausePill.mousePosition != null
                if (!inViewport && !inPill) {
                    renderGate.onPointerExited()
                    freezeGraceTimer.restart()
                }
                showTimer.stop()
                hideTimer.restart()
                checkAndFlushIfIdle()
            }
            override fun mouseClicked(e: MouseEvent) = onTranscriptClick(e)
            override fun mouseReleased(e: MouseEvent) {
                val pressed = pressPoint
                pressPoint = null
                val didDrag = pressed != null && pressed.distance(e.point) > 5
                if (!didDrag && SwingUtilities.isLeftMouseButton(e)) {
                    val hit = transcript.hitTest(e.point)
                    if (hit is TranscriptView.Hit.PlayButton || hit is TranscriptView.Hit.RetryButton || hit is TranscriptView.Hit.DeleteButton) {
                        onTranscriptClick(e)
                        return
                    }
                }
                onTranscriptSelection()
            }
        }
        transcript.addMouseListener(mouse)
        transcript.addMouseMotionListener(mouse)
        transcriptScroll.viewport.addChangeListener {
            if (popup.isVisible && !transcript.isProgrammaticScroll) {
                hideCard()
            }
        }

        popup.onHoverChanged = { inside ->
            popupHovered = inside
            if (inside) hideTimer.stop() else hideTimer.restart()
            checkAndFlushIfIdle()
        }
    }

    private fun onClose() {
        if (!recorder.state.value.isActive) {
            hideCard()
            isVisible = false
            return
        }
        val options = arrayOf(strings.closeStop, strings.closeKeep, strings.cancel)
        when (JOptionPane.showOptionDialog(this, strings.closeWhileRecording, strings.title,
            JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE, null, options, options[0])) {
            0 -> scope.launch {
                val current = recorder.state.value.session
                recorder.stop()
                if (current != null) {
                    val saveChoice = JOptionPane.showConfirmDialog(
                        this@ConversationWindow,
                        strings.confirmSaveSession,
                        strings.confirmSaveTitle,
                        JOptionPane.YES_NO_OPTION,
                        JOptionPane.QUESTION_MESSAGE
                    )
                    if (saveChoice == JOptionPane.NO_OPTION) {
                        feature.repository.delete(current.id)
                        viewed = null
                        transcript.render(null)
                        reloadSessions()
                    }
                }
                isVisible = false
            }
            1 -> { hideCard(); isVisible = false } // keeps recording; the menu brings the window back
        }
    }

    // ---------------------------------------------------------------------------------------
    // Recording
    // ---------------------------------------------------------------------------------------

    private fun startRecording() {
        val microphone = (microphoneCombo.selectedItem as? DeviceItem)?.device
        val systemItem = systemCombo.selectedItem as? DeviceItem
        val system = systemItem?.device
        if (microphone == null && system == null) return
        if (!feature.hasSpeechService) {
            val answer = JOptionPane.showConfirmDialog(this, html(strings.noSpeechServiceConfirm), strings.title,
                JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE)
            if (answer != JOptionPane.YES_OPTION) return
        }
        val myLanguage = myLanguageCombo.selectedItem as? LanguageCode ?: LanguageCode.AUTO
        val callLanguage = callLanguageCombo.selectedItem as? LanguageCode ?: LanguageCode.AUTO
        feature.preferences = feature.preferences.copy(
            microphoneId = microphone?.id,
            systemOutputId = system?.id,
            systemAudioMode = if (isAppMode) "app" else "device",
            preferredProcessName = systemItem?.app?.processName ?: feature.preferences.preferredProcessName,
            spokenLanguage = myLanguage.tag,
            callLanguage = callLanguage.tag,
            keepAudio = keepAudioCheck.isSelected
        )
        sessionsList.clearSelection()
        viewed = null
        recorder.start(
            RecordingOptions(
                title = strings.defaultTitle(SimpleDateFormat("yyyy-MM-dd HH:mm").format(Date())),
                microphone = microphone,
                systemOutput = system,
                microphoneLanguage = myLanguage,
                systemLanguage = callLanguage,
                keepAudio = keepAudioCheck.isSelected
            )
        )
    }

    private fun render(state: RecorderState) {
        val active = state.isActive
        recordButton.text = when (state.phase) {
            RecorderPhase.RECORDING -> "❚❚ ${strings.pause}"
            RecorderPhase.PAUSED -> "▶ ${strings.resume}"
            else -> "● ${strings.record}"
        }
        recordButton.isEnabled = state.phase != RecorderPhase.STOPPING && !loadingDevices && !testingDevices
        stopButton.isEnabled = active
        listOf(refreshDevicesButton, testDevicesButton, newButton)
            .forEach { it.isEnabled = state.phase == RecorderPhase.IDLE && !loadingDevices && !testingDevices }
        listOf(deviceModeButton, appModeButton, keepAudioCheck)
            .forEach { it.isEnabled = !loadingDevices && !testingDevices }
        // During active recording, devices are locked to protect the streams, but languages remain freely switchable
        listOf(microphoneCombo, systemCombo)
            .forEach { it.isEnabled = state.phase == RecorderPhase.IDLE && !loadingDevices && !testingDevices }
        listOf(myLanguageCombo, callLanguageCombo, targetCombo)
            .forEach { it.isEnabled = !loadingDevices && !testingDevices }
        sessionsList.isEnabled = !active
        timerLabel.text = formatTimestamp(if (active || state.phase == RecorderPhase.STOPPING) state.elapsedMillis else viewed?.durationMillis ?: 0)
        microphoneLight.render(state.streams.getValue(AudioSource.MICROPHONE))
        systemLight.render(state.streams.getValue(AudioSource.SYSTEM))

        val pending = state.session?.segments?.count { it.pending } ?: 0
        pendingLabel.text = if (active && pending > 0) strings.pending(pending) else ""
        renderRetryButton()

        noticeBar.isVisible = state.notice != null
        noticeText.text = state.notice?.let(::noticeMessage).orEmpty()

        if (active || state.phase == RecorderPhase.STOPPING) {
            liveSlotsPanel.render(state.live, visible = active || state.live.values.any { it.text.isNotBlank() })
            val sessionToRender = state.session

            syncRenderGateState()
            if (renderGate.submit(sessionToRender)) {
                pausePill.isVisible = false
                transcript.render(sessionToRender)
            } else if (renderGate.hasPendingUpdates()) {
                pausePill.text = strings.updatesPaused(renderGate.pendingCount)
                pausePill.isVisible = true
            }
        } else {
            liveSlotsPanel.render(emptyMap(), visible = false)
        }
        renderSpeechStatus()
        checkDeviceWarnings()

        if (lastPhase != RecorderPhase.IDLE && state.phase == RecorderPhase.IDLE) {
            viewed = state.session
            reloadSessions(selectId = state.session?.id)
        }
        lastPhase = state.phase
        updateSessionButtons()
    }

    // Service messages usually end with their own full stop, and the templates add one.
    private fun noticeMessage(notice: RecorderNotice): String = when (notice) {
        RecorderNotice.NoSpeechService -> strings.noticeNoSpeechService
        is RecorderNotice.TranscriptionFailed -> strings.noticeTranscriptionFailed(notice.error.message.trimEnd('.'))
        is RecorderNotice.DeviceLost -> strings.noticeDeviceLost(sourceName(notice.source), notice.message.trimEnd('.'))
        is RecorderNotice.SaveFailed -> strings.noticeSaveFailed(notice.message.trimEnd('.'))
        is RecorderNotice.LiveFailed -> strings.noticeLiveFailed(sourceName(notice.source), notice.message.trimEnd('.'))
        RecorderNotice.EchoDetected -> strings.noticeEchoDetected
    }

    private fun sourceName(source: AudioSource) = if (source == AudioSource.MICROPHONE) strings.speakerMe else strings.speakerCall

    /** The session on screen: the live one while recording, otherwise the one picked in the list. */
    private fun displayedSession(): ConversationSession? =
        if (recorder.state.value.isActive) recorder.state.value.session else viewed

    // ---------------------------------------------------------------------------------------
    // Devices and preferences
    // ---------------------------------------------------------------------------------------

    private fun loadPreferences() {
        val preferences = feature.preferences
        privacyBar.isVisible = !preferences.privacyNoticeDismissed
        keepAudioCheck.isSelected = preferences.keepAudio
        chatLayoutCheck.isSelected = preferences.chatLayout
        transcript.chatLayout = preferences.chatLayout
        isAppMode = preferences.systemAudioMode == "app"
        deviceModeButton.isSelected = !isAppMode
        appModeButton.isSelected = isAppMode

        val translationAvailable = languages().filter { it != LanguageCode.AUTO }
        val speechAvailable = feature.speechLanguages()?.filter { it != LanguageCode.AUTO }
        val spokenAvailable = speechAvailable ?: translationAvailable
        val renderer = LanguageRenderer()
        val spokenChoices = (listOf(LanguageCode.AUTO) + spokenAvailable).toTypedArray()
        fun JComboBox<LanguageCode>.fill(tag: String) {
            model = DefaultComboBoxModel(spokenChoices)
            this.renderer = renderer
            selectedItem = LanguageCode(tag).takeIf { it == LanguageCode.AUTO || it in spokenAvailable } ?: LanguageCode.AUTO
        }
        fillingLanguages = true
        try {
            myLanguageCombo.fill(preferences.spokenLanguage)
            callLanguageCombo.fill(preferences.callLanguage)
            targetCombo.model = DefaultComboBoxModel(translationAvailable.toTypedArray())
            targetCombo.renderer = renderer
            targetCombo.selectedItem = preferences.targetLanguage?.let(::LanguageCode)?.takeIf { it in translationAvailable }
                ?: defaultTarget().takeIf { it in translationAvailable } ?: translationAvailable.firstOrNull()
        } finally {
            fillingLanguages = false
        }
        renderSpeechStatus()
    }

    private fun loadDevices() {
        loadingDevices = true
        val loading = arrayOf(DeviceItem(null, strings.loadingDevices))
        microphoneCombo.model = DefaultComboBoxModel(loading)
        systemCombo.model = DefaultComboBoxModel(loading)
        render(recorder.state.value)
        scope.launch {
            // Listing system outputs and apps starts PowerShell and takes a second or two.
            val (microphones, outputs, apps) = withContext(Dispatchers.IO) {
                Triple(feature.microphones(), feature.systemOutputs(), feature.systemApplications())
            }
            cachedOutputs = outputs
            cachedApps = apps

            val preferences = feature.preferences
            val none = DeviceItem(null, strings.none)

            val micItems = listOf(none) + microphones.filterNot { it.looksLikeSystemAudio }.map { DeviceItem(it, it.name) }
            microphoneCombo.model = DefaultComboBoxModel(micItems.toTypedArray())
            // Matched only when something was saved: a null id would otherwise match "None" itself.
            val micDevices = micItems.mapNotNull { it.device }
            val preferredMic = DeviceCheckHeuristics.pickDefaultMicrophone(micDevices)
            microphoneCombo.selectedItem = preferences.microphoneId?.let { id -> micItems.firstOrNull { it.device?.id == id } }
                ?: preferredMic?.let { dev -> micItems.firstOrNull { it.device?.id == dev.id } }
                ?: micItems.firstOrNull { it.device?.name?.contains("microphone", ignoreCase = true) == true }
                ?: micItems.getOrNull(1) ?: none

            populateSystemItems()

            loadingDevices = false
            render(recorder.state.value)
        }
    }

    private fun populateSystemItems() {
        val none = DeviceItem(null, strings.none)
        val preferences = feature.preferences

        if (isAppMode) {
            val appItems = if (cachedApps.isEmpty()) {
                listOf(none, DeviceItem(null, "(${strings.noActiveApps})"))
            } else {
                listOf(none) + cachedApps.map { app ->
                    DeviceItem(device = app.device, label = app.displayName, app = app)
                }
            }
            systemCombo.model = DefaultComboBoxModel(appItems.toTypedArray())

            // Try to match preferred process name first
            val preferredProc = preferences.preferredProcessName
            val selected = preferredProc?.let { name ->
                appItems.firstOrNull { it.app?.processName.equals(name, ignoreCase = true) }
            } ?: appItems.firstOrNull { it.app?.processName?.contains("discord", ignoreCase = true) == true }
              ?: appItems.firstOrNull { it.app != null } ?: none

            systemCombo.selectedItem = selected
        } else {
            val systemItems = listOf(none) + cachedOutputs.map { DeviceItem(it, it.name) }
            systemCombo.model = DefaultComboBoxModel(systemItems.toTypedArray())
            val systemDevices = systemItems.mapNotNull { it.device }
            val preferredSys = DeviceCheckHeuristics.pickDefaultCallOutput(systemDevices)
            systemCombo.selectedItem = preferences.systemOutputId?.let { id -> systemItems.firstOrNull { it.device?.id == id } }
                ?: preferredSys?.let { dev -> systemItems.firstOrNull { it.device?.id == dev.id } }
                ?: systemItems.firstOrNull { it.device?.name?.contains("chat", ignoreCase = true) == true }
                ?: systemItems.getOrNull(1) ?: none
        }
    }

    private fun checkDeviceWarnings() {
        if (deviceWarningDismissed || recorder.state.value.isActive) {
            deviceWarningBar.isVisible = false
            return
        }
        val micItem = microphoneCombo.selectedItem as? DeviceItem
        val micName = micItem?.device?.name
        val sysItem = systemCombo.selectedItem as? DeviceItem
        val sysName = sysItem?.device?.name
        val allOutputs = (0 until systemCombo.itemCount).mapNotNull { (systemCombo.getItemAt(it) as? DeviceItem)?.device?.name }

        val warnings = mutableListOf<String>()
        if (DeviceCheckHeuristics.isSuspiciousMicrophone(micName)) {
            warnings.add(strings.warningSuspiciousMic(micName ?: ""))
        }
        val suggestedChat = DeviceCheckHeuristics.findSuggestedChatOutput(sysName, allOutputs)
        if (suggestedChat != null) {
            warnings.add(strings.warningSuggestedChat(suggestedChat))
        }

        if (warnings.isNotEmpty()) {
            deviceWarningText.text = warnings.joinToString("\n")
            deviceWarningBar.isVisible = true
        } else {
            deviceWarningBar.isVisible = false
        }
    }

    private fun testDevices() {
        if (testingDevices || recorder.state.value.isActive) return
        val micDevice = (microphoneCombo.selectedItem as? DeviceItem)?.device
        val sysDevice = (systemCombo.selectedItem as? DeviceItem)?.device
        if (micDevice == null && sysDevice == null) return

        testingDevices = true
        testDevicesButton.isEnabled = false
        val originalText = strings.testDevices

        scope.launch {
            val startTime = System.currentTimeMillis()
            val durationMillis = 3_000L

            fun monitorDeviceLevel(backend: AudioBackend, dev: AudioDevice, light: StreamLight) = launch(Dispatchers.IO) {
                runCatching {
                    val stream = backend.open(dev)
                    try {
                        while (System.currentTimeMillis() - startTime < durationMillis && testingDevices) {
                            val chunk = stream.read() ?: break
                            val level = AudioLevels.rmsLevel(chunk)
                            withContext(Dispatchers.Swing) {
                                light.render(StreamStatus(state = StreamState.LIVE, level = level, deviceName = dev.name))
                            }
                        }
                    } finally {
                        runCatching { stream.close() }
                    }
                }
            }

            val micJob = micDevice?.let { monitorDeviceLevel(feature.microphoneBackend, it, microphoneLight) }
            val sysJob = sysDevice?.let { monitorDeviceLevel(feature.systemBackend, it, systemLight) }

            while (System.currentTimeMillis() - startTime < durationMillis && testingDevices) {
                val remainingSec = ((durationMillis - (System.currentTimeMillis() - startTime) + 999) / 1000).toInt()
                withContext(Dispatchers.Swing) {
                    testDevicesButton.text = strings.testingDevices(remainingSec)
                }
                kotlinx.coroutines.delay(200)
            }

            micJob?.join()
            sysJob?.join()

            withContext(Dispatchers.Swing) {
                testingDevices = false
                testDevicesButton.text = originalText
                testDevicesButton.isEnabled = !loadingDevices && recorder.state.value.phase == RecorderPhase.IDLE
                render(recorder.state.value)
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Sessions list
    // ---------------------------------------------------------------------------------------

    private fun reloadSessions(selectId: String? = sessionsList.selectedValue?.id) {
        val query = searchField.text
        scope.launch {
            val hits = withContext(Dispatchers.IO) { feature.repository.search(query) }
            sessionsModel.clear()
            hits.forEach { sessionsModel.addElement(it.session) }
            val index = (0 until sessionsModel.size).firstOrNull { sessionsModel[it].id == selectId } ?: -1
            if (index >= 0) sessionsList.selectedIndex = index
            highlightSearch()
            updateSessionButtons()
        }
    }

    private fun highlightSearch() {
        transcript.highlighter.removeAllHighlights()
        val query = searchField.text.trim()
        if (query.isEmpty()) return
        val painter = DefaultHighlighter.DefaultHighlightPainter(UIManager.getColor("Actions.Yellow")?.let {
            Color(it.red, it.green, it.blue, 90)
        } ?: Color(255, 220, 0, 90))
        transcript.spans.forEach { span ->
            val text = transcript.document.getText(span.textStart, span.textEnd - span.textStart)
            var at = text.indexOf(query, ignoreCase = true)
            while (at >= 0) {
                transcript.highlighter.addHighlight(span.textStart + at, span.textStart + at + query.length, painter)
                at = text.indexOf(query, at + query.length, ignoreCase = true)
            }
        }
    }

    private fun updateSessionButtons() {
        val selectable = !recorder.state.value.isActive && sessionsList.selectedValue != null
        renameButton.isEnabled = selectable
        exportButton.isEnabled = selectable
        deleteButton.isEnabled = selectable
    }

    private fun renameSelected() {
        val session = sessionsList.selectedValue ?: return
        if (recorder.state.value.isActive) return
        val title = JOptionPane.showInputDialog(this, strings.renamePrompt, session.title)?.trim()
        if (title.isNullOrEmpty() || title == session.title) return
        scope.launch {
            withContext(Dispatchers.IO) { feature.repository.load(session.id)?.let { feature.repository.save(it.copy(title = title)) } }
            reloadSessions(selectId = session.id)
        }
    }

    private fun exportSelected() {
        val session = viewed ?: return
        val chooser = JFileChooser().apply {
            selectedFile = File(session.title.replace(Regex("""[\\/:*?"<>|]"""), "_") + ".txt")
            addChoosableFileFilter(FileNameExtensionFilter("Text Document (*.txt)", "txt"))
            addChoosableFileFilter(FileNameExtensionFilter("SubRip Subtitle (*.srt)", "srt"))
            fileFilter = choosableFileFilters.first()
        }
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return
        val ext = chooser.fileFilter?.description?.let {
            if (it.contains(".srt", ignoreCase = true)) "srt" else "txt"
        } ?: chooser.selectedFile.extension.ifEmpty { "txt" }
        val target = chooser.selectedFile.let {
            if (it.extension.isEmpty()) File(it.path + ".$ext") else it
        }
        scope.launch {
            val content = withContext(Dispatchers.IO) {
                if (target.extension.equals("srt", ignoreCase = true)) {
                    feature.repository.exportSrt(session, ::sourceName)
                } else {
                    feature.repository.exportText(session, ::sourceName)
                }
            }
            withContext(Dispatchers.IO) { target.writeText(content, Charsets.UTF_8) }
            JOptionPane.showMessageDialog(this@ConversationWindow, strings.exported(target.absolutePath))
        }
    }

    private fun deleteSelected() {
        val session = sessionsList.selectedValue ?: return
        if (recorder.state.value.isActive) return
        if (JOptionPane.showConfirmDialog(this, strings.deleteConfirm(session.title), strings.delete,
                JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) return
        feature.player.stop()
        scope.launch {
            withContext(Dispatchers.IO) { feature.repository.delete(session.id) }
            if (viewed?.id == session.id) {
                viewed = null
                recorder.show(null)
                transcript.render(null)
            }
            reloadSessions(selectId = null)
        }
    }

    // ---------------------------------------------------------------------------------------
    // Transcript interaction
    // ---------------------------------------------------------------------------------------

    private fun onTranscriptHover(e: MouseEvent) {
        val hit = transcript.hitTest(e.point)
        hoverCard = hit != null
        transcript.updateCursor(hit)
        transcript.toolTipText = transcript.tooltipForHit(hit)
        // A selection owns the card until it is cleared.
        if (transcript.selectionStart != transcript.selectionEnd) return

        val text = hit as? TranscriptView.Hit.Text
        val range = text?.takeIf { !it.span.segment.pending && !it.span.segment.failed }
            ?.let { transcript.wordRangeAt(it.span, it.offset) }
        if (range == hoverRange) {
            if (range != null) hideTimer.stop() else checkAndFlushIfIdle()
            return
        }
        hoverRange = range
        hoverSpan = text?.span
        showTimer.stop()
        if (range == null) { hideTimer.restart(); checkAndFlushIfIdle() } else { hideTimer.stop(); showTimer.restart() }
    }

    private fun onTranscriptClick(e: MouseEvent) {
        if (e.clickCount != 1 || !SwingUtilities.isLeftMouseButton(e)) return
        when (val hit = transcript.hitTest(e.point)) {
            is TranscriptView.Hit.PlayButton -> playRange(hit.span.segment, hit.span.segment.startMillis, hit.span.segment.endMillis)
            is TranscriptView.Hit.RetryButton -> retry(hit.span.segment)
            is TranscriptView.Hit.DeleteButton -> deleteSingleSegment(hit.span.segment)
            is TranscriptView.Hit.VerificationBadge -> Unit
            is TranscriptView.Hit.Header -> Unit // Clicking speaker label or timestamp does not trigger playback
            is TranscriptView.Hit.Text -> if (hit.span.segment.failed) retry(hit.span.segment)
            null -> Unit
        }
    }

    private fun onTranscriptSelection() {
        val start = transcript.selectionStart
        val end = transcript.selectionEnd
        if (start == end) return

        // Must overlap actual spoken text, never the header row (play button, speaker, timestamp)
        val span = transcript.spans.firstOrNull { it.textEnd > start && it.textStart < end } ?: return
        if (end <= span.textStart || start >= span.textEnd) return

        val effectiveStart = maxOf(start, span.textStart)
        val effectiveEnd = minOf(end, span.textEnd)
        if (effectiveStart >= effectiveEnd) return

        val phrase = transcript.textOf(effectiveStart until effectiveEnd).trim()
        if (phrase.isEmpty() || phrase.length > MAX_PHRASE_CHARS || phrase.none { it.isLetterOrDigit() }) return

        val timed = span.words.filter { (chars, _) -> chars.first < effectiveEnd && chars.last >= effectiveStart }.map { it.second }
        val anchor = transcript.boundsOf(effectiveStart until effectiveEnd) ?: return
        val session = displayedSession() ?: return

        showCard(anchor, phrase, contextSentence = null, onPlay = timed.takeIf { it.isNotEmpty() }?.let {
            { playRange(span.segment, it.first().startMillis, it.last().endMillis) }
        }, segment = span.segment, session = session)
    }

    private fun showWordCard() {
        val range = hoverRange ?: return
        val span = hoverSpan ?: return
        val session = displayedSession() ?: return
        val word = transcript.textOf(range)
        val anchor = transcript.boundsOf(range) ?: return
        val timed = transcript.timedWordAt(span, range)
        val sentence = span.segment.text.takeIf { it.trim().contains(' ') }
        showCard(anchor, word, sentence, timed?.let { { playRange(span.segment, it.startMillis, it.endMillis) } }, span.segment, session)
    }

    private fun showCard(
        anchorInTranscript: Rectangle,
        text: String,
        contextSentence: String?,
        onPlay: (() -> Unit)?,
        segment: TranscriptSegment?,
        session: ConversationSession
    ) {
        val target = targetCombo.selectedItem as? LanguageCode ?: return
        val hasAudio = segment != null && feature.hasAudio(session, segment.source)
        val hasContext = contextSentence != null && contextSentence.trim() != text.trim()
        val content = HoverTranslationPopup.Content(
            text = text,
            translation = feature.translations.peek(text, target),
            context = if (!hasContext) contextSentence?.let { feature.translations.peek(it, target) } else null,
            hasContextSentence = hasContext,
            canPlayWord = onPlay != null && hasAudio,
            canPlaySentence = hasAudio
        )
        popup.onPlayWord = { onPlay?.invoke() }
        popup.onPlaySentence = { segment?.let { playRange(it, it.startMillis, it.endMillis) } }
        popup.onShowSentence = {
            if (contextSentence != null) {
                updateCard(text) { it.copy(contextLoading = true) }
                scope.launch {
                    feature.translations.translate(contextSentence, target).fold(
                        success = { sentence -> updateCard(text) { it.copy(context = sentence, contextLoading = false) } },
                        failure = { updateCard(text) { it.copy(contextLoading = false) } }
                    )
                }
            }
        }
        popup.onCopy = { popupContent?.translation?.let { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(it), null) } }
        popup.onMeanings = { loadMeanings(text, segment, session, target) }

        val screen = Rectangle(anchorInTranscript).apply {
            val origin = java.awt.Point(x, y)
            SwingUtilities.convertPointToScreen(origin, transcript)
            location = origin
        }
        popupContent = content
        popup.show(screen, content)

        popupJob?.cancel()
        popupJob = scope.launch {
            val translated = feature.translations.translate(text, target)
            updateCard(text) { current ->
                translated.fold(
                    success = { current.copy(translation = it, error = null) },
                    failure = { current.copy(error = it.message) }
                )
            }
        }
    }

    private fun loadMeanings(text: String, segment: TranscriptSegment?, session: ConversationSession, target: LanguageCode) {
        updateCard(text) { it.copy(meaningsLoading = true) }
        val source = segment?.language?.let(::LanguageCode)
            ?: LanguageCode(segment?.let { session.languageOf(it.source) } ?: session.spokenLanguage)
        scope.launch {
            val result = feature.meanings(text, source, target)
            updateCard(text) { current ->
                result.fold(
                    success = { entries ->
                        current.copy(meaningsLoading = false, meanings = entries.flatMap { entry ->
                            entry.definitions.take(2).map { definition ->
                                listOf(entry.partOfSpeech, definition.text).filter { it.isNotBlank() }.joinToString(" — ")
                            }
                        }.take(MAX_MEANINGS))
                    },
                    failure = { current.copy(meaningsLoading = false, meanings = listOf(strings.failed(it.message))) }
                )
            }
        }
    }

    /** Updates the card only if it still shows [text]; the user may have moved on. */
    private fun updateCard(text: String, transform: (HoverTranslationPopup.Content) -> HoverTranslationPopup.Content) {
        val current = popupContent ?: return
        if (current.text != text || !popup.isVisible) return
        val updated = transform(current)
        popupContent = updated
        popup.update(updated)
    }

    private fun hideCard() {
        showTimer.stop()
        hideTimer.stop()
        popupJob?.cancel()
        popup.hide()
        popupContent = null
        popupHovered = false
        hoverRange = null
        checkAndFlushIfIdle()
    }

    private fun syncRenderGateState() {
        if (transcriptScroll.viewport.mousePosition != null || transcript.mousePosition != null || (pausePill.isVisible && pausePill.mousePosition != null)) {
            renderGate.onPointerEntered()
            freezeGraceTimer.stop()
        }
        renderGate.popupVisible = popup.isVisible || popupHovered
        renderGate.hasSelection = transcript.selectionStart != transcript.selectionEnd
    }

    private fun checkAndFlushIfIdle() {
        syncRenderGateState()
        val session = renderGate.tryFlushIfUnfrozen() ?: return
        pausePill.isVisible = false
        transcript.render(session)
    }

    private fun flushBufferedUpdates() {
        val session = renderGate.forceFlush() ?: return
        pausePill.isVisible = false
        transcript.render(session)
    }

    private fun playRange(segment: TranscriptSegment, startMillis: Long, endMillis: Long) {
        val session = displayedSession() ?: return
        scope.launch {
            // A little padding either side: word timings are tight, and a clipped first consonant
            // makes a word harder to recognise.
            val played = feature.play(session, segment.source, (startMillis - 120).coerceAtLeast(0), endMillis + 120, segment.id)
            if (!played) JOptionPane.showMessageDialog(this@ConversationWindow, strings.noAudio)
        }
    }

    private fun renderSpeechStatus() {
        val name = feature.speechServiceName
        val live = feature.liveServiceName
        val baseText = when {
            name != null && live != null -> strings.speechHybrid(live, name)
            name != null -> strings.speechService(name)
            live != null -> strings.speechLiveOnly(live)
            else -> strings.speechNone
        }
        val statusText = if (recorder.state.value.isActive && live != null) {
            val status = when (recorder.state.value.liveStatus) {
                LiveRecognitionStatus.CONNECTED -> strings.liveStatusConnected
                LiveRecognitionStatus.RECONNECTING -> strings.liveStatusReconnecting
                LiveRecognitionStatus.OFF -> strings.liveStatusOff
            }
            strings.liveStatusTag(status)
        } else ""
        speechLabel.text = baseText + statusText
        speechLabel.foreground = when {
            name == null && live == null -> UIManager.getColor("Actions.Red") ?: Color.RED
            recorder.state.value.isActive && recorder.state.value.liveStatus == LiveRecognitionStatus.RECONNECTING ->
                UIManager.getColor("Actions.Yellow") ?: Color(200, 150, 0)
            else -> UIManager.getColor("Label.disabledForeground")
        }
    }

    private fun renderLive(state: RecorderState) {
        liveSlotsPanel.render(
            state.live,
            visible = state.isActive || state.live.values.any { it.text.isNotBlank() }
        )
    }

    private fun renderRetryButton() {
        val failed = displayedSession()?.segments?.count { it.failed } ?: 0
        retryFailedButton.isVisible = failed > 0
        retryFailedButton.text = strings.retryFailed(failed)
    }

    private fun retryAllFailed() {
        val session = displayedSession() ?: return
        retryFailedButton.isEnabled = false
        scope.launch {
            val updated = recorder.retryFailed(session)
            if (!recorder.state.value.isActive) {
                viewed = updated
                transcript.render(updated)
            }
            retryFailedButton.isEnabled = true
            renderRetryButton()
        }
    }

    private fun retry(segment: TranscriptSegment) {
        val session = displayedSession() ?: return
        val pendingSegment = segment.copy(pending = true, draft = false, failed = false, text = "")
        val interim = session.withSegment(pendingSegment)
        if (recorder.state.value.isActive) {
            recorder.updateLiveSession { it.withSegment(pendingSegment) }
        } else {
            viewed = interim
            transcript.render(interim)
        }
        scope.launch {
            val updated = recorder.retry(session, segment)
            if (updated != null) {
                if (recorder.state.value.isActive) {
                    val finalSeg = updated.segments.firstOrNull { s -> s.id == segment.id } ?: segment
                    recorder.updateLiveSession { it.withSegment(finalSeg) }
                } else {
                    viewed = updated
                    transcript.render(updated)
                }
            }
        }
    }

    private fun deleteSingleSegment(segment: TranscriptSegment) {
        val session = displayedSession() ?: return
        scope.launch {
            val updated = recorder.deleteSegment(session, segment.id)
            if (recorder.state.value.isActive) {
                recorder.updateLiveSession { it.withoutSegment(segment.id) }
            } else {
                viewed = updated
                transcript.render(updated)
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Small components
    // ---------------------------------------------------------------------------------------

    /** "● Call · Live" with a level bar: one per audio source. */
    private inner class StreamLight(private val name: String, private val source: AudioSource) :
        JPanel(MigLayout("insets 0, gap 4")) {
        private val label = JLabel()
        private val level = JProgressBar(0, 100).apply { preferredSize = Dimension(44, 6) }

        init {
            isOpaque = false
            add(label)
            add(level)
        }

        fun render(status: StreamStatus) {
            val (color, text) = when (status.state) {
                StreamState.OFF -> (UIManager.getColor("Label.disabledForeground") ?: Color.GRAY) to strings.statusOff
                StreamState.CONNECTING -> (UIManager.getColor("Actions.Yellow") ?: Color.ORANGE) to strings.statusConnecting
                StreamState.LIVE -> TranscriptView.sourceColor(source) to strings.statusLive
                StreamState.FAILED -> (UIManager.getColor("Actions.Red") ?: Color.RED) to strings.statusFailed
            }
            label.foreground = color
            label.text = "● $name · $text"
            toolTipText = listOfNotNull(status.deviceName.ifEmpty { null }, status.error).joinToString(" — ").ifEmpty { null }
            level.isVisible = status.state == StreamState.LIVE
            // Speech sits far below full scale; a square root makes normal talking fill the bar.
            level.value = (kotlin.math.sqrt(status.level.toDouble()) * 160).toInt().coerceIn(0, 100)
        }
    }

    private inner class SessionCellRenderer : DefaultListCellRenderer() {
        private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm")

        override fun getListCellRendererComponent(
            list: JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean
        ): Component {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
            val session = value as? ConversationSession ?: return this
            val status = when (session.status) {
                SessionStatus.RECORDING -> " · ${strings.statusRecording}"
                SessionStatus.INTERRUPTED -> " · ${strings.statusInterrupted}"
                SessionStatus.COMPLETED -> ""
            }
            val details = "${dateFormat.format(Date(session.createdAt))} · ${formatTimestamp(session.durationMillis)} · " +
                strings.lines(session.segments.count { it.text.isNotBlank() }) + status
            text = "<html><b>${escape(session.title)}</b><br><span style='font-size:90%'>${escape(details)}</span></html>"
            border = BorderFactory.createEmptyBorder(6, 8, 6, 8)
            return this
        }
    }

    private inner class LanguageRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean
        ): Component {
            val label = (value as? LanguageCode)?.getDisplayName(autoDetectLabel = strings.autoDetect) ?: value
            return super.getListCellRendererComponent(list, label, index, isSelected, cellHasFocus)
        }
    }

    private fun html(text: String) = "<html>${escape(text).replace("\n", "<br>")}</html>"

    private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private companion object {
        const val SHOW_DELAY_MILLIS = 320
        const val HIDE_DELAY_MILLIS = 450
        const val MAX_PHRASE_CHARS = 600
        const val MAX_MEANINGS = 6
    }
}

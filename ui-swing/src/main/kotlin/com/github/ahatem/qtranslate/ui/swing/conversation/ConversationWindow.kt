package com.github.ahatem.qtranslate.ui.swing.conversation

import com.github.ahatem.qtranslate.api.language.LanguageCode
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
import java.awt.Dimension
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
    private data class DeviceItem(val device: AudioDevice?, val label: String) {
        override fun toString() = label
    }

    private val microphoneCombo = JComboBox<DeviceItem>()
    private val systemCombo = JComboBox<DeviceItem>()
    private val refreshDevicesButton = JButton("⟳").apply { toolTipText = strings.refreshDevices }
    private val myLanguageCombo = JComboBox<LanguageCode>()
    private val callLanguageCombo = JComboBox<LanguageCode>()
    private val targetCombo = JComboBox<LanguageCode>()
    private val keepAudioCheck = JCheckBox(strings.keepAudio).apply { toolTipText = strings.keepAudioTooltip }

    private val recordButton = JButton()
    private val stopButton = JButton("■ ${strings.stop}")
    private val timerLabel = JLabel("0:00")
    private val microphoneLight = StreamLight(strings.speakerMe, AudioSource.MICROPHONE)
    private val systemLight = StreamLight(strings.speakerCall, AudioSource.SYSTEM)
    private val pendingLabel = JLabel()
    private val retryFailedButton = JButton()
    private val speechLabel = JLabel()
    private val speechSettingsButton = JButton(strings.speechSettings)

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

    /**
     * What is being said right now, one line per source, while a live recognizer is in use. Kept out
     * of the transcript document: it changes several times a second, and rebuilding the document
     * that often would break selection and hovering.
     */
    private val liveLines = AudioSource.entries.associateWith { source ->
        JLabel().apply {
            foreground = TranscriptView.sourceColor(source)
            border = BorderFactory.createEmptyBorder(2, 16, 2, 16)
            font = font.deriveFont(java.awt.Font.ITALIC, font.size2D + 1f)
        }
    }
    private val liveStrip = JPanel(MigLayout("fillx, wrap 1, insets 4 0 4 0, gap 0", "[grow,fill]")).apply {
        liveLines.values.forEach { add(it, "wmin 0") }
        isVisible = false
    }
    private val transcriptScroll = JScrollPane(transcript)
    private val popup = HoverTranslationPopup(this, strings)

    /** The saved session on screen when nothing is recording. */
    private var viewed: ConversationSession? = null
    private var lastPhase = RecorderPhase.IDLE
    private var loadingDevices = false

    // Hover state: which word the pointer rests on, and the timers that delay showing and hiding
    // the card so sweeping across text does not flash a card per word.
    private var hoverRange: IntRange? = null
    private var hoverSpan: TranscriptView.SegmentSpan? = null
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
            applyComponentOrientation(if (isRtl()) ComponentOrientation.RIGHT_TO_LEFT else ComponentOrientation.LEFT_TO_RIGHT)
            // The transcript keeps its own direction per paragraph, whatever the UI language.
            transcript.componentOrientation = ComponentOrientation.LEFT_TO_RIGHT
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
        listOf(microphoneCombo, systemCombo).forEach { it.prototypeDisplayValue = DeviceItem(null, "Headset Microphone (USB)") }
        // Only the pickers share a grid. The wider rows below are panels of their own: a row that
        // spans grid columns makes MigLayout widen the last column to its preferred width and
        // never give it back, which clipped the right-hand side of the window.
        val pickers = shrinkable(MigLayout("fillx, insets 10 12 4 12, gap 8 6", "[][60:200:,grow,fill][][60:200:,grow,fill]")).apply {
            add(JLabel(strings.microphone))
            add(microphoneCombo, "wmin 60")
            add(JLabel(strings.callAudio).apply { toolTipText = strings.callAudioTooltip })
            add(systemCombo.apply { toolTipText = strings.callAudioTooltip }, "split 3, growx, wmin 60")
            add(testDevicesButton)
            add(refreshDevicesButton, "wrap")
            add(JLabel(strings.myLanguage))
            add(myLanguageCombo, "wmin 60")
            add(JLabel(strings.callLanguage))
            add(callLanguageCombo, "wmin 60, wrap")
            add(JLabel(strings.translateTo))
            add(targetCombo, "wmin 60")
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
        val keepAudioRow = shrinkable(MigLayout("insets 0 12 6 12")).apply { add(keepAudioCheck, "wmin 0") }
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
        transcriptScroll.border = BorderFactory.createMatteBorder(1, 0, 1, 0, UIManager.getColor("Component.borderColor"))
        transcriptScroll.verticalScrollBar.unitIncrement = 16
        add(transcriptScroll, BorderLayout.CENTER)
        add(JPanel(BorderLayout()).apply {
            add(liveStrip, BorderLayout.NORTH)
            add(JLabel(strings.interactionHint).apply {
                foreground = UIManager.getColor("Label.disabledForeground")
                border = BorderFactory.createEmptyBorder(6, 12, 6, 12)
            }, BorderLayout.SOUTH)
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
        stopButton.addActionListener { scope.launch { recorder.stop() } }
        refreshDevicesButton.addActionListener { loadDevices() }
        testDevicesButton.addActionListener { testDevices() }
        microphoneCombo.addActionListener {
            deviceWarningDismissed = false
            checkDeviceWarnings()
        }
        systemCombo.addActionListener {
            deviceWarningDismissed = false
            checkDeviceWarnings()
        }
        speechSettingsButton.addActionListener { onOpenSpeechSettings() }
        retryFailedButton.addActionListener { retryAllFailed() }
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

        val mouse = object : MouseAdapter() {
            override fun mouseMoved(e: MouseEvent) = onTranscriptHover(e)
            override fun mouseExited(e: MouseEvent) {
                showTimer.stop()
                hideTimer.restart()
            }
            override fun mouseClicked(e: MouseEvent) = onTranscriptClick(e)
            override fun mouseReleased(e: MouseEvent) = onTranscriptSelection()
        }
        transcript.addMouseListener(mouse)
        transcript.addMouseMotionListener(mouse)
        transcriptScroll.viewport.addChangeListener { if (popup.isVisible) hideCard() }

        popup.onHoverChanged = { inside ->
            popupHovered = inside
            if (inside) hideTimer.stop() else hideTimer.restart()
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
            0 -> scope.launch { recorder.stop(); isVisible = false }
            1 -> { hideCard(); isVisible = false } // keeps recording; the menu brings the window back
        }
    }

    // ---------------------------------------------------------------------------------------
    // Recording
    // ---------------------------------------------------------------------------------------

    private fun startRecording() {
        val microphone = (microphoneCombo.selectedItem as? DeviceItem)?.device
        val system = (systemCombo.selectedItem as? DeviceItem)?.device
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
        listOf(microphoneCombo, systemCombo, refreshDevicesButton, testDevicesButton, myLanguageCombo, callLanguageCombo, keepAudioCheck, newButton)
            .forEach { it.isEnabled = state.phase == RecorderPhase.IDLE && !loadingDevices && !testingDevices }
        sessionsList.isEnabled = !active
        timerLabel.text = formatTimestamp(if (active || state.phase == RecorderPhase.STOPPING) state.elapsedMillis else viewed?.durationMillis ?: 0)
        microphoneLight.render(state.streams.getValue(AudioSource.MICROPHONE))
        systemLight.render(state.streams.getValue(AudioSource.SYSTEM))

        val pending = state.session?.segments?.count { it.pending } ?: 0
        pendingLabel.text = if (active && pending > 0) strings.pending(pending) else ""
        renderRetryButton()

        noticeBar.isVisible = state.notice != null
        noticeText.text = state.notice?.let(::noticeMessage).orEmpty()

        if (active || state.phase == RecorderPhase.STOPPING) transcript.render(state.session)
        renderLive(state)
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

        val available = languages().filter { it != LanguageCode.AUTO }
        val renderer = LanguageRenderer()
        val spokenChoices = (listOf(LanguageCode.AUTO) + available).toTypedArray()
        fun JComboBox<LanguageCode>.fill(tag: String) {
            model = DefaultComboBoxModel(spokenChoices)
            this.renderer = renderer
            selectedItem = LanguageCode(tag).takeIf { it == LanguageCode.AUTO || it in available } ?: LanguageCode.AUTO
        }
        myLanguageCombo.fill(preferences.spokenLanguage)
        callLanguageCombo.fill(preferences.callLanguage)
        renderSpeechStatus()
        targetCombo.model = DefaultComboBoxModel(available.toTypedArray())
        targetCombo.renderer = renderer
        targetCombo.selectedItem = preferences.targetLanguage?.let(::LanguageCode)?.takeIf { it in available }
            ?: defaultTarget().takeIf { it in available } ?: available.firstOrNull()
    }

    private fun loadDevices() {
        loadingDevices = true
        val loading = arrayOf(DeviceItem(null, strings.loadingDevices))
        microphoneCombo.model = DefaultComboBoxModel(loading)
        systemCombo.model = DefaultComboBoxModel(loading)
        render(recorder.state.value)
        scope.launch {
            // Listing system outputs starts PowerShell and takes a second or two.
            val (microphones, outputs) = withContext(Dispatchers.IO) { feature.microphones() to feature.systemOutputs() }
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

            val systemItems = listOf(none) + outputs.map { DeviceItem(it, it.name) }
            systemCombo.model = DefaultComboBoxModel(systemItems.toTypedArray())
            val systemDevices = systemItems.mapNotNull { it.device }
            val preferredSys = DeviceCheckHeuristics.pickDefaultCallOutput(systemDevices)
            systemCombo.selectedItem = preferences.systemOutputId?.let { id -> systemItems.firstOrNull { it.device?.id == id } }
                ?: preferredSys?.let { dev -> systemItems.firstOrNull { it.device?.id == dev.id } }
                ?: systemItems.firstOrNull { it.device?.name?.contains("chat", ignoreCase = true) == true }
                ?: systemItems.getOrNull(1) ?: none

            loadingDevices = false
            render(recorder.state.value)
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
            fileFilter = FileNameExtensionFilter("Text", "txt")
        }
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return
        val target = chooser.selectedFile.let { if (it.extension.isEmpty()) File(it.path + ".txt") else it }
        scope.launch {
            withContext(Dispatchers.IO) { target.writeText(feature.repository.exportText(session, ::sourceName), Charsets.UTF_8) }
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
        transcript.setHandCursor(hit is TranscriptView.Hit.Header || (hit is TranscriptView.Hit.Text && hit.span.segment.failed))
        // A selection owns the card until it is cleared.
        if (transcript.selectionStart != transcript.selectionEnd) return

        val text = hit as? TranscriptView.Hit.Text
        val range = text?.takeIf { !it.span.segment.pending && !it.span.segment.failed }
            ?.let { transcript.wordRangeAt(it.span, it.offset) }
        if (range == hoverRange) {
            if (range != null) hideTimer.stop()
            return
        }
        hoverRange = range
        hoverSpan = text?.span
        showTimer.stop()
        if (range == null) hideTimer.restart() else { hideTimer.stop(); showTimer.restart() }
    }

    private fun onTranscriptClick(e: MouseEvent) {
        if (e.clickCount != 1 || !SwingUtilities.isLeftMouseButton(e)) return
        when (val hit = transcript.hitTest(e.point)) {
            is TranscriptView.Hit.Header -> playRange(hit.span.segment, hit.span.segment.startMillis, hit.span.segment.endMillis)
            is TranscriptView.Hit.Text -> if (hit.span.segment.failed) retry(hit.span.segment)
            null -> Unit
        }
    }

    private fun onTranscriptSelection() {
        val start = transcript.selectionStart
        val end = transcript.selectionEnd
        if (start == end) return
        val phrase = transcript.selectedText?.trim().orEmpty()
        if (phrase.isEmpty() || phrase.length > MAX_PHRASE_CHARS) return

        val spans = transcript.spans.filter { it.textEnd > start && it.textStart < end }
        val span = spans.singleOrNull()
        val timed = span?.words?.filter { (chars, _) -> chars.first < end && chars.last >= start }?.map { it.second }.orEmpty()
        val anchor = transcript.boundsOf(start until end) ?: return
        val session = displayedSession() ?: return

        showCard(anchor, phrase, contextSentence = null, onPlay = timed.takeIf { it.isNotEmpty() && span != null }?.let {
            { playRange(span!!.segment, it.first().startMillis, it.last().endMillis) }
        }, segment = span?.segment, session = session)
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
        val content = HoverTranslationPopup.Content(
            text = text,
            translation = feature.translations.peek(text, target),
            canPlayWord = onPlay != null && hasAudio,
            canPlaySentence = hasAudio
        )
        popup.onPlayWord = { onPlay?.invoke() }
        popup.onPlaySentence = { segment?.let { playRange(it, it.startMillis, it.endMillis) } }
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
            if (contextSentence != null) {
                feature.translations.translate(contextSentence, target).fold(
                    success = { sentence -> updateCard(text) { it.copy(context = sentence) } },
                    failure = { }
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
            " · Live: $status"
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
        liveLines.forEach { (source, label) ->
            val line = state.live[source]
            label.isVisible = line != null
            label.text = line?.let { "${sourceName(source)} · ${formatTimestamp(it.startMillis)} — ${it.text}" }
            // Each line keeps its own direction: Arabic speech reads right to left.
            label.componentOrientation = if (line != null && line.text.firstOrNull { Character.isLetter(it) }
                    ?.let { Character.getDirectionality(it) == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC ||
                        Character.getDirectionality(it) == Character.DIRECTIONALITY_RIGHT_TO_LEFT } == true
            ) ComponentOrientation.RIGHT_TO_LEFT else ComponentOrientation.LEFT_TO_RIGHT
            label.horizontalAlignment = javax.swing.SwingConstants.LEADING
        }
        val visible = state.live.isNotEmpty()
        if (liveStrip.isVisible != visible) {
            liveStrip.isVisible = visible
            liveStrip.parent?.revalidate()
        }
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
        scope.launch {
            val updated = recorder.retry(session, segment)
            if (updated != null && !recorder.state.value.isActive) {
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

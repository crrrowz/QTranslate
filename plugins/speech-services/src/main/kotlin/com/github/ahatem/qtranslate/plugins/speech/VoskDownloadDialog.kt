package com.github.ahatem.qtranslate.plugins.speech

import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Window
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.SwingUtilities

/**
 * Thread-safe controller for managing active model downloads (pause, resume, cancel).
 */
class VoskDownloadController {
    val isPaused = AtomicBoolean(false)
    val isCancelled = AtomicBoolean(false)
    val isFinished = AtomicBoolean(false)

    fun pause() { isPaused.set(true) }
    fun resume() { isPaused.set(false) }
    fun cancel() { isCancelled.set(true) }
}

/**
 * Modern Swing dialog showing real-time model download progress, speed, ETA,
 * and controls for pausing/resuming or cancelling.
 */
class VoskDownloadDialog(
    parent: Window?,
    private val model: VoskModel,
    private val controller: VoskDownloadController
) : JDialog(parent, "Download Vosk Model", ModalityType.MODELESS) {

    private val titleLabel = JLabel("Model: ${model.label}").apply {
        font = font.deriveFont(Font.BOLD, 13f)
    }
    private val sourceLabel = JLabel("Source: alphacephei.com (local offline storage)").apply {
        font = font.deriveFont(Font.PLAIN, 11f)
    }
    private val progressBar = JProgressBar(0, 100).apply {
        isStringPainted = true
        preferredSize = Dimension(430, 24)
    }
    private val statsLabel = JLabel("Connecting to server...").apply {
        font = font.deriveFont(Font.PLAIN, 12f)
    }
    private val pauseResumeButton = JButton("Pause")
    private val cancelButton = JButton("Cancel")

    init {
        defaultCloseOperation = DO_NOTHING_ON_CLOSE
        addWindowListener(object : java.awt.event.WindowAdapter() {
            override fun windowClosing(e: java.awt.event.WindowEvent?) {
                handleCancel()
            }
        })

        val mainPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createEmptyBorder(16, 20, 12, 20)
            add(titleLabel)
            add(Box.createVerticalStrut(4))
            add(sourceLabel)
            add(Box.createVerticalStrut(14))
            add(progressBar)
            add(Box.createVerticalStrut(8))
            add(statsLabel)
            add(Box.createVerticalStrut(12))
        }

        pauseResumeButton.addActionListener {
            if (controller.isPaused.get()) {
                controller.resume()
                pauseResumeButton.text = "Pause"
                statsLabel.text = "Resuming download..."
            } else {
                controller.pause()
                pauseResumeButton.text = "Resume"
            }
        }

        cancelButton.addActionListener {
            handleCancel()
        }

        val buttonPanel = JPanel(FlowLayout(FlowLayout.TRAILING, 8, 0)).apply {
            border = BorderFactory.createEmptyBorder(0, 0, 12, 16)
            add(pauseResumeButton)
            add(cancelButton)
        }

        contentPane.layout = BorderLayout()
        contentPane.add(mainPanel, BorderLayout.CENTER)
        contentPane.add(buttonPanel, BorderLayout.SOUTH)

        pack()
        isResizable = false
        setLocationRelativeTo(parent)
    }

    private fun handleCancel() {
        if (controller.isFinished.get()) {
            dispose()
            return
        }
        controller.cancel()
        statsLabel.text = "Cancelling..."
        pauseResumeButton.isEnabled = false
        cancelButton.isEnabled = false
        dispose()
    }

    fun updateProgress(downloaded: Long, total: Long, speedBytesPerSec: Long, etaSeconds: Long) {
        SwingUtilities.invokeLater {
            if (controller.isCancelled.get() || !isVisible) return@invokeLater
            if (controller.isPaused.get()) {
                val pct = if (total > 0) ((downloaded * 100) / total).toInt().coerceIn(0, 100) else 0
                statsLabel.text = "Paused at ${formatBytes(downloaded)} / ${formatBytes(total)} ($pct%). Click Resume to continue."
                return@invokeLater
            }
            if (total > 0) {
                val pct = ((downloaded * 100) / total).toInt().coerceIn(0, 100)
                progressBar.value = pct
                progressBar.isIndeterminate = false
                val downloadedStr = formatBytes(downloaded)
                val totalStr = formatBytes(total)
                val speedStr = formatSpeed(speedBytesPerSec)
                val etaStr = formatEta(etaSeconds)
                statsLabel.text = "$downloadedStr / $totalStr ($pct%) • $speedStr • $etaStr"
            } else {
                progressBar.isIndeterminate = true
                statsLabel.text = "${formatBytes(downloaded)} downloaded • ${formatSpeed(speedBytesPerSec)}"
            }
        }
    }

    fun notifyExtracting() {
        SwingUtilities.invokeLater {
            progressBar.isIndeterminate = true
            statsLabel.text = "Extracting model files into local storage..."
            pauseResumeButton.isEnabled = false
            cancelButton.isEnabled = false
        }
    }

    fun notifyCompleted() {
        SwingUtilities.invokeLater {
            controller.isFinished.set(true)
            progressBar.isIndeterminate = false
            progressBar.value = 100
            statsLabel.text = "Done! Model installed successfully."
            pauseResumeButton.isEnabled = false
            cancelButton.text = "Close"
            cancelButton.isEnabled = true
            val timer = javax.swing.Timer(1200) { dispose() }
            timer.isRepeats = false
            timer.start()
        }
    }

    fun notifyError(msg: String) {
        SwingUtilities.invokeLater {
            progressBar.isIndeterminate = false
            statsLabel.text = "Error: $msg"
            pauseResumeButton.isEnabled = false
            cancelButton.text = "Close"
            cancelButton.isEnabled = true
        }
    }

    companion object {
        fun formatBytes(bytes: Long): String {
            if (bytes < 1024 * 1024) {
                return String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0)
            }
            return String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
        }

        fun formatSpeed(bytesPerSec: Long): String = "${formatBytes(bytesPerSec)}/s"

        fun formatEta(seconds: Long): String {
            if (seconds < 0) return "--"
            if (seconds < 60) return "${seconds}s remaining"
            val m = seconds / 60
            val s = seconds % 60
            return "${m}m ${s}s remaining"
        }
    }
}

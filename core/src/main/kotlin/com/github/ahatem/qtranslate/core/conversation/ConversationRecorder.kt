package com.github.ahatem.qtranslate.core.conversation

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

enum class RecorderPhase { IDLE, RECORDING, PAUSED, STOPPING }

enum class StreamState { OFF, CONNECTING, LIVE, FAILED }

/** How one audio source is doing, for the status lights in the window. */
data class StreamStatus(
    val state: StreamState = StreamState.OFF,
    val deviceName: String = "",
    /** Loudness of the latest audio, 0..1. */
    val level: Float = 0f,
    val error: String? = null
)

/** Something the user should hear about. Recording carries on through all of them. */
sealed interface RecorderNotice {
    data object NoSpeechService : RecorderNotice
    data class TranscriptionFailed(val error: ServiceError) : RecorderNotice
    data class DeviceLost(val source: AudioSource, val message: String) : RecorderNotice
    data class SaveFailed(val message: String) : RecorderNotice
}

data class RecorderState(
    val phase: RecorderPhase = RecorderPhase.IDLE,
    /** The session being recorded, or the last one recorded once stopped. */
    val session: ConversationSession? = null,
    val elapsedMillis: Long = 0,
    val streams: Map<AudioSource, StreamStatus> = AudioSource.entries.associateWith { StreamStatus() },
    val notice: RecorderNotice? = null
) {
    val isActive: Boolean get() = phase == RecorderPhase.RECORDING || phase == RecorderPhase.PAUSED
}

data class RecordingOptions(
    val title: String,
    val microphone: AudioDevice?,
    val systemOutput: AudioDevice?,
    val spokenLanguage: LanguageCode = LanguageCode.AUTO,
    val keepAudio: Boolean = true
)

/**
 * Records a conversation: the microphone and the computer's output as two separate streams, each
 * written to its own file, cut into utterances at pauses and transcribed as it goes.
 *
 * The layers fail independently. A device that drops out stops only its own stream; a speech
 * service that is missing or failing leaves the audio recording and marks the affected segments
 * for a retry; a transcript that cannot be saved never touches the recordings.
 *
 * Time on the session timeline is recorded time: it stands still while paused, so a segment's
 * timestamp is also its position in the stream's file (after the stream's start offset).
 */
class ConversationRecorder(
    private val scope: CoroutineScope,
    private val repository: SessionRepository,
    private val microphoneBackend: AudioBackend,
    private val systemBackend: AudioBackend,
    recognize: Recognize,
    private val logger: Logger,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val transcriber = SegmentTranscriber(recognize)

    private val _state = MutableStateFlow(RecorderState())
    val state: StateFlow<RecorderState> = _state.asStateFlow()

    private val sessionLock = Mutex()
    private var streams: List<StreamRecorder> = emptyList()
    private var ticker: Job? = null
    private var options: RecordingOptions? = null

    // Recorded-time clock: wall time since start, minus time spent paused.
    private var startedAt = 0L
    private var pausedAt = 0L
    private var pausedTotal = 0L

    @Volatile
    private var paused = false

    private fun recordedMillis(): Long {
        val now = if (paused) pausedAt else clock()
        return (now - startedAt - pausedTotal).coerceAtLeast(0)
    }

    /** Starts a new session. Ignored while one is already running. */
    fun start(options: RecordingOptions) {
        if (_state.value.isActive || _state.value.phase == RecorderPhase.STOPPING) return
        this.options = options

        val session = ConversationSession(
            id = newSessionId(),
            title = options.title,
            createdAt = clock(),
            spokenLanguage = options.spokenLanguage.tag,
            keepAudio = options.keepAudio
        )
        runCatching { repository.save(session) }
            .onFailure { e -> logger.error("Could not create conversation session", e) }

        startedAt = clock()
        pausedTotal = 0
        paused = false

        streams = listOfNotNull(
            options.microphone?.let { StreamRecorder(AudioSource.MICROPHONE, it, microphoneBackend, session.id) },
            options.systemOutput?.let { StreamRecorder(AudioSource.SYSTEM, it, systemBackend, session.id) }
        )
        _state.value = RecorderState(
            phase = RecorderPhase.RECORDING,
            session = session,
            streams = AudioSource.entries.associateWith { source ->
                val device = streams.firstOrNull { it.source == source }?.device
                if (device == null) StreamStatus() else StreamStatus(StreamState.CONNECTING, device.name)
            }
        )
        streams.forEach(StreamRecorder::start)
        ticker = scope.launch {
            while (isActive) {
                _state.update { state ->
                    state.copy(
                        elapsedMillis = recordedMillis(),
                        streams = state.streams.mapValues { (source, status) ->
                            streams.firstOrNull { it.source == source }?.let { status.copy(level = it.level) } ?: status
                        }
                    )
                }
                delay(TICK_MILLIS)
            }
        }
        logger.info("Conversation recording started (${streams.joinToString { "${it.source}: ${it.device.name}" }})")
    }

    fun pause() {
        if (_state.value.phase != RecorderPhase.RECORDING) return
        pausedAt = clock()
        paused = true
        _state.update { it.copy(phase = RecorderPhase.PAUSED, elapsedMillis = recordedMillis()) }
    }

    fun resume() {
        if (_state.value.phase != RecorderPhase.PAUSED) return
        pausedTotal += clock() - pausedAt
        paused = false
        _state.update { it.copy(phase = RecorderPhase.RECORDING) }
    }

    /**
     * Stops recording, waits up to [transcriptionGraceMillis] for clips still being transcribed
     * and saves the session. Clips that do not finish in time are kept as failed segments, to be
     * retried from the audio.
     */
    suspend fun stop(transcriptionGraceMillis: Long = 30_000) {
        if (!_state.value.isActive) return
        val duration = recordedMillis()
        paused = false
        _state.update { it.copy(phase = RecorderPhase.STOPPING, elapsedMillis = duration) }
        ticker?.cancel()

        streams.forEach(StreamRecorder::stopCapture)
        streams.map { it.captureJob }.filterNotNull().joinAll()
        withTimeoutOrNull(transcriptionGraceMillis) { streams.mapNotNull { it.transcriptionJob }.joinAll() }
        streams.forEach { it.transcriptionJob?.cancel() }

        val keepAudio = options?.keepAudio != false
        updateSession { session ->
            session.copy(
                status = SessionStatus.COMPLETED,
                durationMillis = duration,
                segments = session.segments.map { if (it.pending) it.copy(pending = false, failed = true) else it }
            )
        }
        val session = _state.value.session
        if (!keepAudio && session != null) {
            withContext(Dispatchers.IO) { repository.deleteAudio(session.id) }
        }
        streams = emptyList()
        _state.update { it.copy(phase = RecorderPhase.IDLE, streams = AudioSource.entries.associateWith { StreamStatus() }) }
        logger.info("Conversation recording stopped after ${formatTimestamp(duration)}")
    }

    /** For application exit: stops and saves without waiting for the speech service. */
    fun shutdown() {
        if (!_state.value.isActive) return
        runBlocking { withTimeoutOrNull(SHUTDOWN_MILLIS) { stop(transcriptionGraceMillis = 0) } }
    }

    fun clearNotice() = _state.update { it.copy(notice = null) }

    /** Shows [session] (a saved one) when nothing is recording. */
    fun show(session: ConversationSession?) {
        if (_state.value.isActive) return
        _state.update { it.copy(session = session) }
    }

    /**
     * Transcribes a failed segment again from its recording. Works on the live session and on
     * saved ones, as long as their audio was kept.
     */
    suspend fun retry(session: ConversationSession, segment: TranscriptSegment): ConversationSession? {
        val stream = session.streams.firstOrNull { it.source == segment.source } ?: return null
        val file = repository.audioFile(session.id, segment.source)
        val pcm = withContext(Dispatchers.IO) {
            WavFiles.readRange(file, segment.startMillis - stream.offsetMillis, segment.endMillis - stream.offsetMillis)
        }
        if (pcm.isEmpty()) return null
        val outcome = transcriber.transcribe(
            segment.copy(pending = true, failed = false), pcm,
            LanguageCode(session.spokenLanguage), promptBefore(session, segment)
        )
        report(outcome)
        val updated = session.withSegment(outcome.segment)
        if (_state.value.session?.id == session.id) {
            updateSession { it.withSegment(outcome.segment) }
        } else {
            withContext(Dispatchers.IO) { runCatching { repository.save(updated) } }
        }
        return updated
    }

    private suspend fun updateSession(transform: (ConversationSession) -> ConversationSession) {
        sessionLock.withLock {
            val current = _state.value.session ?: return
            val updated = transform(current)
            _state.update { it.copy(session = updated) }
            withContext(Dispatchers.IO) {
                runCatching { repository.save(updated) }.onFailure { e ->
                    logger.error("Could not save conversation transcript", e)
                    _state.update { it.copy(notice = RecorderNotice.SaveFailed(e.message ?: e.toString())) }
                }
            }
        }
    }

    private fun report(outcome: SegmentTranscriber.Outcome) {
        val notice = when (outcome) {
            is SegmentTranscriber.Outcome.Done -> return
            is SegmentTranscriber.Outcome.NoService -> RecorderNotice.NoSpeechService
            is SegmentTranscriber.Outcome.Failed -> {
                logger.warn("Transcription failed: ${outcome.error.message}")
                RecorderNotice.TranscriptionFailed(outcome.error)
            }
        }
        _state.update { it.copy(notice = notice) }
    }

    /** The tail of what the same source said before [segment], as context for the speech service. */
    private fun promptBefore(session: ConversationSession, segment: TranscriptSegment): String? =
        session.segments
            .filter { it.source == segment.source && it.startMillis < segment.startMillis && it.text.isNotBlank() }
            .takeLast(3)
            .joinToString(" ") { it.text }
            .takeIf { it.isNotBlank() }

    private fun setStream(source: AudioSource, transform: (StreamStatus) -> StreamStatus) =
        _state.update { state -> state.copy(streams = state.streams + (source to transform(state.streams.getValue(source)))) }

    private inner class StreamRecorder(
        val source: AudioSource,
        val device: AudioDevice,
        private val backend: AudioBackend,
        private val sessionId: String
    ) {
        private val segmenter = SpeechSegmenter()
        private val utterances = Channel<Pair<TranscriptSegment, ByteArray>>(Channel.UNLIMITED)
        private var writer: WavFileWriter? = null
        private var pcm: PcmStream? = null
        private var offsetMillis: Long? = null

        @Volatile
        private var stopping = false

        var captureJob: Job? = null
        var transcriptionJob: Job? = null
        val level: Float get() = if (paused) 0f else segmenter.level

        fun start() {
            captureJob = scope.launch(Dispatchers.IO) { capture() }
            transcriptionJob = scope.launch {
                for ((placeholder, audio) in utterances) {
                    val session = _state.value.session ?: continue
                    val outcome = transcriber.transcribe(
                        placeholder, audio, LanguageCode(session.spokenLanguage), promptBefore(session, placeholder)
                    )
                    report(outcome)
                    // A clip that held no speech (a cough, a door) comes back blank: drop it.
                    if (outcome is SegmentTranscriber.Outcome.Done && outcome.segment.text.isBlank()) {
                        updateSession { s -> s.copy(segments = s.segments.filterNot { it.id == placeholder.id }) }
                    } else {
                        updateSession { it.withSegment(outcome.segment) }
                    }
                }
            }
        }

        private suspend fun capture() {
            try {
                val stream = backend.open(device).also { pcm = it }
                writer = WavFileWriter(repository.audioFile(sessionId, source))
                setStream(source) { it.copy(state = StreamState.LIVE, error = null) }
                var flushedForPause = false
                while (!stopping) {
                    val chunk = stream.read() ?: break
                    // While paused the device keeps being read, so its buffer never overflows and
                    // nothing stale is replayed on resume, but nothing is kept. The utterance in
                    // progress ends at the pause.
                    if (paused) {
                        if (!flushedForPause) segmenter.flush()?.let(::announce)
                        flushedForPause = true
                        continue
                    }
                    flushedForPause = false
                    if (chunk.isEmpty()) continue
                    if (offsetMillis == null) {
                        val offset = recordedMillis()
                        offsetMillis = offset
                        updateSession { it.copy(streams = it.streams + SessionStream(source, device.name, offset)) }
                    }
                    writer?.write(chunk)
                    segmenter.accept(chunk).forEach(::announce)
                }
            } catch (e: Exception) {
                if (!stopping) {
                    val message = e.message ?: e.toString()
                    logger.error("Audio capture failed for $source (${device.name})", e)
                    _state.update { state ->
                        val status = state.streams.getValue(source).copy(state = StreamState.FAILED, error = message, level = 0f)
                        state.copy(streams = state.streams + (source to status), notice = RecorderNotice.DeviceLost(source, message))
                    }
                }
            } finally {
                segmenter.flush()?.let(::announce)
                utterances.close()
                runCatching { pcm?.close() }
                runCatching { writer?.close() }
            }
        }

        fun stopCapture() {
            stopping = true
            runCatching { pcm?.close() }
        }

        private fun announce(utterance: SpeechSegmenter.Utterance) {
            val offset = offsetMillis ?: 0
            val start = offset + utterance.startSample * 1000 / PcmStream.SAMPLE_RATE
            val placeholder = TranscriptSegment(
                id = UUID.randomUUID().toString(),
                source = source,
                startMillis = start,
                endMillis = start + utterance.sampleCount * 1000L / PcmStream.SAMPLE_RATE,
                pending = true
            )
            // Inserted asynchronously, so a fast transcription may land first; never overwrite it.
            scope.launch {
                updateSession { s -> if (s.segments.any { it.id == placeholder.id }) s else s.withSegment(placeholder) }
            }
            utterances.trySend(placeholder to utterance.pcm)
        }
    }

    private fun newSessionId(): String =
        java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.ROOT).format(java.util.Date(clock())) +
            "-" + UUID.randomUUID().toString().take(6)

    private companion object {
        const val TICK_MILLIS = 200L
        const val SHUTDOWN_MILLIS = 5_000L
    }
}

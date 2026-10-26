package com.github.ahatem.qtranslate.core.conversation

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.speech.LiveSpeechEvent
import com.github.ahatem.qtranslate.api.speech.LiveSpeechSession
import com.github.michaelbull.result.fold
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
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

    /** The live recognizer dropped out; recording carries on without live text while it reconnects. */
    data class LiveFailed(val source: AudioSource, val message: String) : RecorderNotice

    /** Microphone audio is bleeding into the call track (sidetone / loopback). */
    data object EchoDetected : RecorderNotice
}

/** What the live recognizer currently thinks is being said, not yet settled into a segment. */
data class LiveLine(val startMillis: Long, val text: String)

enum class LiveRecognitionStatus {
    OFF,
    CONNECTED,
    RECONNECTING
}

data class RecorderState(
    val phase: RecorderPhase = RecorderPhase.IDLE,
    /** The session being recorded, or the last one recorded once stopped. */
    val session: ConversationSession? = null,
    val elapsedMillis: Long = 0,
    val streams: Map<AudioSource, StreamStatus> = AudioSource.entries.associateWith { StreamStatus() },
    val notice: RecorderNotice? = null,
    /** Words being spoken right now, per source, while a live recognizer is in use. */
    val live: Map<AudioSource, LiveLine> = emptyMap(),
    val liveStatus: LiveRecognitionStatus = LiveRecognitionStatus.OFF
) {
    val isActive: Boolean get() = phase == RecorderPhase.RECORDING || phase == RecorderPhase.PAUSED
}

data class RecordingOptions(
    val title: String,
    val microphone: AudioDevice?,
    val systemOutput: AudioDevice?,
    /** What the user speaks. */
    val microphoneLanguage: LanguageCode = LanguageCode.AUTO,
    /** What the people on the call speak. */
    val systemLanguage: LanguageCode = LanguageCode.AUTO,
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
    private val clock: () -> Long = System::currentTimeMillis,
    /** The active live recognizer, if any. Without one, speech is cut at pauses and sent as clips. */
    private val openLive: OpenLive = { null },
    /** True while in-app playback is active, so recorded audio is muted to prevent acoustic/loopback feedback. */
    private val isPlaybackActive: () -> Boolean = { false }
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
            spokenLanguage = options.microphoneLanguage.tag,
            callLanguage = options.systemLanguage.tag,
            keepAudio = options.keepAudio
        )
        runCatching { repository.save(session) }
            .onFailure { e -> logger.error("Could not create conversation session", e) }

        startedAt = clock()
        pausedTotal = 0
        paused = false

        streams = listOfNotNull(
            options.microphone?.let { StreamRecorder(AudioSource.MICROPHONE, it, microphoneBackend, session.id, options.microphoneLanguage) },
            options.systemOutput?.let { StreamRecorder(AudioSource.SYSTEM, it, systemBackend, session.id, options.systemLanguage) }
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
        _state.update { it.copy(phase = RecorderPhase.IDLE, streams = AudioSource.entries.associateWith { StreamStatus() }, live = emptyMap(), liveStatus = LiveRecognitionStatus.OFF) }
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
        val pendingSegment = segment.copy(pending = true, draft = false, failed = false, text = "")
        if (_state.value.session?.id == session.id) {
            updateSession { it.withSegment(pendingSegment) }
        }
        val outcome = transcriber.transcribe(
            pendingSegment, pcm,
            LanguageCode(session.languageOf(segment.source)), promptBefore(session, segment)
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

    fun updateLiveSession(transform: (ConversationSession) -> ConversationSession) {
        if (_state.value.isActive) {
            scope.launch { updateSession(transform) }
        }
    }

    @Volatile
    var isMicrophoneMuted: Boolean = false

    @Volatile
    var isSystemMuted: Boolean = false

    fun updateSpokenLanguage(language: LanguageCode) {
        if (options?.microphoneLanguage == language) return
        options = options?.copy(microphoneLanguage = language)
        scope.launch { updateSession { it.copy(spokenLanguage = language.tag) } }
        streams.firstOrNull { it.source == AudioSource.MICROPHONE }?.switchLiveLanguage(language)
    }

    fun updateCallLanguage(language: LanguageCode) {
        if (options?.systemLanguage == language) return
        options = options?.copy(systemLanguage = language)
        scope.launch { updateSession { it.copy(callLanguage = language.tag) } }
        streams.firstOrNull { it.source == AudioSource.SYSTEM }?.switchLiveLanguage(language)
    }

    /**
     * Deletes a single segment from [session] and updates persistence.
     */
    suspend fun deleteSegment(session: ConversationSession, segmentId: String): ConversationSession {
        val updated = session.withoutSegment(segmentId)
        if (_state.value.session?.id == session.id) {
            updateSession { it.withoutSegment(segmentId) }
        } else {
            withContext(Dispatchers.IO) { runCatching { repository.save(updated) } }
        }
        return updated
    }

    /**
     * Retries every failed segment of [session], one after another so a provider's rate limit is
     * not hit all at once. Stops early if the speech service is still missing.
     */
    suspend fun retryFailed(session: ConversationSession): ConversationSession {
        var current = session
        // The notice left over from recording would otherwise end the loop after one segment.
        clearNotice()
        for (segment in session.segments.filter { it.failed }) {
            current = retry(current, segment) ?: continue
            if (_state.value.notice == RecorderNotice.NoSpeechService) break
        }
        return current
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

    private fun applySegment(current: ConversationSession, segment: TranscriptSegment): ConversationSession {
        // If it's on the SYSTEM stream and duplicates recent speech on the MICROPHONE stream, drop it
        if (segment.source == AudioSource.SYSTEM && segment.text.isNotBlank()) {
            if (isEcho(segment, current.segments)) {
                logger.warn("Echo detected: microphone audio bled into call stream ('${segment.text}')")
                _state.update { it.copy(notice = RecorderNotice.EchoDetected) }
                return current.copy(segments = current.segments.filterNot { it.id == segment.id })
            }
        }

        // Both MICROPHONE and SYSTEM streams operate as independent channels without cancelling each other.
        return current.withSegment(segment)
    }

    private fun isEcho(candidate: TranscriptSegment, allSegments: List<TranscriptSegment>): Boolean =
        allSegments.any { other -> isEchoPair(candidate, other) }

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

    private fun updateLiveStatus() {
        val overall = when {
            streams.any { it.liveStatus == LiveRecognitionStatus.RECONNECTING } -> LiveRecognitionStatus.RECONNECTING
            streams.any { it.liveStatus == LiveRecognitionStatus.CONNECTED } -> LiveRecognitionStatus.CONNECTED
            else -> LiveRecognitionStatus.OFF
        }
        _state.update { it.copy(liveStatus = overall) }
    }

    private inner class StreamRecorder(
        val source: AudioSource,
        val device: AudioDevice,
        private val backend: AudioBackend,
        private val sessionId: String,
        initialLanguage: LanguageCode = LanguageCode.AUTO
    ) {
        @Volatile
        var currentLanguage: LanguageCode = initialLanguage
            private set
        private val segmenter = SpeechSegmenter()
        private val utterances = Channel<Pair<TranscriptSegment, ByteArray>>(Channel.UNLIMITED)
        private var writer: WavFileWriter? = null
        private var pcm: PcmStream? = null
        private var offsetMillis: Long? = null

        @Volatile
        private var stopping = false

        // ---- live recognition ----
        @Volatile
        private var live: LiveSpeechSession? = null
        private var liveEvents: Job? = null
        private var liveConnecting: Job? = null
        /** No live recognizer is set up: never try again in this session, and say nothing. */
        @Volatile
        private var liveUnavailable = false
        private var liveFailures = 0
        var liveStatus = LiveRecognitionStatus.OFF
            private set
        private val liveLock = Any()
        private val rollingBuffer = java.util.ArrayDeque<ByteArray>()
        private var rollingBufferBytes = 0
        @Volatile
        private var samplesSentToLive = 0L
        /** Stream sample at which the current live session started; its event times count from here. */
        private var liveStartSample = 0L
        /** Stream sample where the last settled segment ended. */
        private var lastFinalSample = 0L
        /** Stream sample where the speech now being heard began, once a partial has arrived. */
        private var utteranceStartSample: Long? = null
        /** Where the voice detector heard speech begin since the last settled segment. */
        @Volatile
        private var voicedStartSample: Long? = null

        var captureJob: Job? = null
        var transcriptionJob: Job? = null
        val level: Float get() = if (paused) 0f else segmenter.level

        private val samplesWritten: Long get() = writer?.sampleCount ?: 0

        fun start() {
            captureJob = scope.launch(Dispatchers.IO) { capture() }
            transcriptionJob = scope.launch {
                // Several at once: a slow provider (a chat model takes 20 s or more) would otherwise
                // fall further behind with every line.
                val slots = Semaphore(MAX_CONCURRENT_TRANSCRIPTIONS)
                coroutineScope {
                    for ((placeholder, audio) in utterances) {
                        slots.acquire()
                        launch {
                            try {
                                transcribe(placeholder, audio)
                            } finally {
                                slots.release()
                            }
                        }
                    }
                }
            }
        }

        private suspend fun transcribe(placeholder: TranscriptSegment, audio: ByteArray) {
            val session = _state.value.session ?: return
            val outcome = transcriber.transcribe(
                placeholder, audio, LanguageCode(session.languageOf(source)), promptBefore(session, placeholder)
            )
            if (outcome is SegmentTranscriber.Outcome.Done) {
                updateDetectedLanguage(outcome.segment.language)
            }
            if (placeholder.draft) {
                // Refining a live line: the live text stands unless something better comes back.
                when (outcome) {
                    is SegmentTranscriber.Outcome.Done ->
                        if (outcome.segment.text.isBlank()) {
                            // The accurate recognizer heard no speech where the live one guessed words.
                            updateSession { s -> s.copy(segments = s.segments.filterNot { it.id == placeholder.id }) }
                        } else {
                            updateSession { applySegment(it, outcome.segment) }
                        }
                    // Live text alone is a legitimate setup, not an error.
                    is SegmentTranscriber.Outcome.NoService -> updateSession { applySegment(it, placeholder.copy(draft = false)) }
                    is SegmentTranscriber.Outcome.Failed -> {
                        // "No accurate provider chosen" is a setup, not a failure, when live text is on.
                        if (outcome.error !is ServiceError.ConfigurationError) report(outcome)
                        updateSession { applySegment(it, placeholder.copy(draft = false)) }
                    }
                }
                return
            }
            report(outcome)
            // A clip that held no speech (a cough, a door) comes back blank: drop it.
            if (outcome is SegmentTranscriber.Outcome.Done && outcome.segment.text.isBlank()) {
                updateSession { s -> s.copy(segments = s.segments.filterNot { it.id == placeholder.id }) }
            } else {
                updateSession { applySegment(it, outcome.segment) }
            }
        }

        private suspend fun capture() {
            try {
                val stream = backend.open(device).also { pcm = it }
                writer = WavFileWriter(repository.audioFile(sessionId, source))
                setStream(source) { it.copy(state = StreamState.LIVE, error = null) }
                connectLive()
                var flushedForPause = false
                while (!stopping) {
                    val chunk = stream.read() ?: break
                    // While paused the device keeps being read, so its buffer never overflows and
                    // nothing stale is replayed on resume, but nothing is kept. The utterance in
                    // progress ends at the pause, and a live session is closed (its server would
                    // otherwise wait mid-sentence) and reopened on resume.
                    if (paused) {
                        if (!flushedForPause) {
                            segmenter.flush()?.let(::announce)
                            closeLive()
                        }
                        flushedForPause = true
                        continue
                    }
                    if (flushedForPause) connectLive()
                    flushedForPause = false
                    if (chunk.isEmpty()) continue
                    val playing = isPlaybackActive()
                    val isMuted = (source == AudioSource.MICROPHONE && isMicrophoneMuted) ||
                        (source == AudioSource.SYSTEM && isSystemMuted)
                    val effectiveChunk = if (playing || isMuted) ByteArray(chunk.size) else chunk
                    if (playing || isMuted) {
                        segmenter.resetUtterance()
                    }
                    if (offsetMillis == null) {
                        val offset = recordedMillis()
                        offsetMillis = offset
                        updateSession { it.copy(streams = it.streams + SessionStream(source, device.name, offset)) }
                    }
                    val toWrite = if (isMuted) effectiveChunk else chunk
                    writer?.write(toWrite)
                    val chunkCopy = effectiveChunk.copyOf()
                    synchronized(liveLock) {
                        rollingBuffer.addLast(chunkCopy)
                        rollingBufferBytes += chunkCopy.size
                        while (rollingBufferBytes > ROLLING_BUFFER_MAX_BYTES && rollingBuffer.isNotEmpty()) {
                            rollingBufferBytes -= rollingBuffer.removeFirst().size
                        }

                        val session = live
                        if (session != null) {
                            session.sendAudio(effectiveChunk)
                            samplesSentToLive = samplesWritten
                            // The live recognizer decides where lines end; the voice detector still
                            // knows where speech began, which a late partial cannot tell.
                            val completed = segmenter.accept(effectiveChunk)
                            if (voicedStartSample == null) {
                                voicedStartSample = completed.firstOrNull()?.startSample ?: segmenter.activeStartSample
                            }
                        } else {
                            segmenter.accept(effectiveChunk).forEach(::announce)
                        }
                    }
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
                liveConnecting?.cancel()
                // The last words: the live recognizer settles them, or the segmenter cuts them.
                if (live != null) closeLive() else segmenter.flush()?.let(::announce)
                utterances.close()
                runCatching { pcm?.close() }
                runCatching { writer?.close() }
            }
        }

        private var detectedLanguage: String? = null

        private fun updateDetectedLanguage(detected: String?) {
            if (detected.isNullOrBlank() || detected == "auto") return
            val configured = _state.value.session?.languageOf(source) ?: "auto"
            if (configured == "auto" && detected != detectedLanguage) {
                val previous = detectedLanguage
                detectedLanguage = detected
                logger.info("Language detected as '$detected' for $source (was: ${previous ?: "auto"})")
                if (live != null && !stopping && !paused) {
                    scope.launch {
                        closeLive()
                        connectLive()
                    }
                }
            }
        }

        private fun extractLastBytes(bytesNeeded: Int): List<ByteArray> {
            if (bytesNeeded <= 0 || rollingBuffer.isEmpty()) return emptyList()
            val list = rollingBuffer.toList()
            var accumulated = 0
            var startIndex = list.size
            for (i in list.indices.reversed()) {
                accumulated += list[i].size
                startIndex = i
                if (accumulated >= bytesNeeded) break
            }
            val result = mutableListOf<ByteArray>()
            for (i in startIndex until list.size) {
                val chunk = list[i]
                if (i == startIndex && accumulated > bytesNeeded) {
                    val skip = accumulated - bytesNeeded
                    if (skip < chunk.size) {
                        result.add(chunk.copyOfRange(skip, chunk.size))
                    }
                } else {
                    result.add(chunk)
                }
            }
            return result
        }

        /** Opens a live session in the background; audio goes through the segmenter until it is ready. */
        private fun connectLive() {
            if (liveUnavailable || live != null || liveConnecting?.isActive == true) return
            liveStatus = LiveRecognitionStatus.RECONNECTING
            updateLiveStatus()
            liveConnecting = scope.launch {
                val configured = currentLanguage.tag
                val languageTag = if (configured == "auto") (detectedLanguage ?: "auto") else configured
                val language = LanguageCode(languageTag)
                val result = openLive(language)
                if (result == null) {
                    liveUnavailable = true
                    liveStatus = LiveRecognitionStatus.OFF
                    updateLiveStatus()
                    return@launch
                }
                result.fold(
                    success = { session ->
                        if (stopping || paused) {
                            session.cancel()
                            return@fold
                        }
                        // What the segmenter was in the middle of is finished the old way.
                        segmenter.flush()?.let(::announce)
                        liveFailures = 0
                        liveStatus = LiveRecognitionStatus.CONNECTED
                        updateLiveStatus()

                        val actualGapSamples = synchronized(liveLock) {
                            val gapSamples = (samplesWritten - samplesSentToLive).coerceAtLeast(0)
                            var replayed = 0L
                            if (gapSamples > 0) {
                                val gapBytes = (gapSamples * 2).coerceAtMost(rollingBufferBytes.toLong()).toInt()
                                val gapChunks = extractLastBytes(gapBytes)
                                for (gapChunk in gapChunks) {
                                    session.sendAudio(gapChunk)
                                }
                                samplesSentToLive = samplesWritten
                                replayed = (gapBytes / 2).toLong()
                            }
                            live = session
                            replayed
                        }
                        liveStartSample = samplesWritten - actualGapSamples
                        lastFinalSample = liveStartSample
                        utteranceStartSample = null
                        liveEvents = scope.launch { session.events.collect { onLiveEvent(session, it) } }
                        logger.info("Live recognition active for $source")
                    },
                    failure = { error ->
                        if (error is ServiceError.ConfigurationError) {
                            liveUnavailable = true
                            liveStatus = LiveRecognitionStatus.OFF
                            updateLiveStatus()
                            synchronized(liveLock) {
                                while (rollingBuffer.isNotEmpty()) {
                                    segmenter.accept(rollingBuffer.removeFirst()).forEach(::announce)
                                }
                                rollingBufferBytes = 0
                            }
                        } else {
                            liveFailed(error.message)
                        }
                    }
                )
            }
        }

        /** Settles and closes the live session, waiting for its last words. */
        private suspend fun closeLive() {
            val session = synchronized(liveLock) {
                val s = live
                live = null
                s
            } ?: return
            withTimeoutOrNull(LIVE_FINISH_MILLIS) {
                session.finish()
                liveEvents?.join()
            }
            liveEvents?.cancel()
            clearLiveLine()
        }

        fun switchLiveLanguage(language: LanguageCode) {
            if (currentLanguage == language && (live != null || liveConnecting?.isActive == true)) return
            currentLanguage = language
            scope.launch {
                closeLive()
                liveUnavailable = false
                connectLive()
            }
        }

        private suspend fun onLiveEvent(session: LiveSpeechSession, event: LiveSpeechEvent) {
            when (event) {
                is LiveSpeechEvent.Partial -> {
                    if (event.text.isBlank()) return
                    val startSample = utteranceStartSample
                        ?: (samplesWritten - LEAD_SAMPLES).coerceAtLeast(lastFinalSample).also { utteranceStartSample = it }
                    _state.update { it.copy(live = it.live + (source to LiveLine(toSessionMillis(startSample), event.text))) }
                }
                is LiveSpeechEvent.Final -> settle(event)
                is LiveSpeechEvent.Failed -> {
                    val s = synchronized(liveLock) {
                        if (live === session) {
                            live = null
                            session
                        } else null
                    }
                    if (s != null) {
                        clearLiveLine()
                        liveFailed(event.error.message)
                    }
                }
            }
        }

        /** Turns a live final into a segment, and hands its audio to the accurate recognizer. */
        private fun settle(event: LiveSpeechEvent.Final) {
            val endSample = samplesWritten
            var startSample = listOfNotNull(voicedStartSample, utteranceStartSample).minOrNull()
                ?.coerceIn(lastFinalSample, endSample) ?: lastFinalSample
            utteranceStartSample = null
            voicedStartSample = null
            lastFinalSample = endSample
            clearLiveLine()
            if (event.text.isBlank()) return

            val words = event.words.map { word ->
                TranscriptWord(
                    text = word.text,
                    startMillis = toSessionMillis(liveStartSample) + word.startMillis,
                    endMillis = toSessionMillis(liveStartSample) + word.endMillis
                )
            }
            words.firstOrNull()?.let { first ->
                startSample = ((first.startMillis - (offsetMillis ?: 0)) * PcmStream.SAMPLE_RATE / 1000 - LEAD_SAMPLES / 2)
                    .coerceIn(0, endSample)
            }
            val draft = TranscriptSegment(
                id = UUID.randomUUID().toString(),
                source = source,
                startMillis = toSessionMillis(startSample),
                endMillis = toSessionMillis(endSample),
                text = event.text,
                words = words,
                draft = true
            )
            scope.launch { updateSession { applySegment(it, draft) } }
            val audio = WavFiles.readRange(
                repository.audioFile(sessionId, source),
                startSample * 1000 / PcmStream.SAMPLE_RATE,
                endSample * 1000 / PcmStream.SAMPLE_RATE
            )
            if (audio.isEmpty()) {
                scope.launch { updateSession { applySegment(it, draft.copy(draft = false)) } }
            } else {
                utterances.trySend(draft to audio)
            }
        }

        private fun liveFailed(message: String) {
            logger.warn("Live recognition for $source failed: $message")
            _state.update { it.copy(notice = RecorderNotice.LiveFailed(source, message)) }
            if (stopping) return
            liveFailures++
            if (liveFailures > MAX_LIVE_RECONNECTS) {
                liveUnavailable = true
                liveStatus = LiveRecognitionStatus.OFF
                updateLiveStatus()
                synchronized(liveLock) {
                    while (rollingBuffer.isNotEmpty()) {
                        segmenter.accept(rollingBuffer.removeFirst()).forEach(::announce)
                    }
                    rollingBufferBytes = 0
                }
                return
            }
            liveStatus = LiveRecognitionStatus.RECONNECTING
            updateLiveStatus()
            // The same back-off as the Vosk extension: quick at first, never more than 5 s apart.
            val delayMillis = minOf((200 * Math.pow(1.5, (liveFailures - 1).toDouble())).toLong(), 5_000L)
            scope.launch {
                delay(delayMillis)
                if (!stopping && !paused) connectLive()
            }
        }

        private fun clearLiveLine() = _state.update { it.copy(live = it.live - source) }

        private fun toSessionMillis(streamSample: Long): Long =
            (offsetMillis ?: 0) + streamSample * 1000 / PcmStream.SAMPLE_RATE

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

    internal companion object {
        const val TICK_MILLIS = 200L
        const val SHUTDOWN_MILLIS = 5_000L
        const val MAX_CONCURRENT_TRANSCRIPTIONS = 3
        const val MAX_LIVE_RECONNECTS = 10
        const val LIVE_FINISH_MILLIS = 4_000L
        /** How far before the first partial a live segment's audio starts: words begin before they are recognised. */
        const val LEAD_SAMPLES = PcmStream.SAMPLE_RATE * 6 / 10
        const val ROLLING_BUFFER_SECONDS = 10
        const val ROLLING_BUFFER_MAX_BYTES = PcmStream.SAMPLE_RATE * 2 * ROLLING_BUFFER_SECONDS

        fun isEchoPair(systemSegment: TranscriptSegment, micSegment: TranscriptSegment): Boolean {
            val sys = if (systemSegment.source == AudioSource.SYSTEM) systemSegment else micSegment
            val mic = if (systemSegment.source == AudioSource.MICROPHONE) systemSegment else micSegment
            if (sys.source != AudioSource.SYSTEM || mic.source != AudioSource.MICROPHONE) return false
            if (sys.text.isBlank() || mic.text.isBlank()) return false
            if (kotlin.math.abs(sys.startMillis - mic.startMillis) > 12_000) return false

            val sysText = sys.text.trim().lowercase()
            val micText = mic.text.trim().lowercase()
            if (sysText.length < 5 || micText.length < 5) return false

            val sysWords = sysText.split(Regex("""[\s\p{Punct}]+""")).filter { it.isNotBlank() }
            val micWords = micText.split(Regex("""[\s\p{Punct}]+""")).filter { it.isNotBlank() }
            if (sysWords.size < 2 && micWords.size < 2 && sysText.length < 10) return false

            if (sysText == micText ||
                (sysWords.size >= 3 && micText.contains(sysText)) ||
                (micWords.size >= 3 && sysText.contains(micText))
            ) {
                return true
            }

            val set1 = sysWords.toSet()
            val set2 = micWords.toSet()
            val intersection = set1.intersect(set2).size
            val union = set1.union(set2).size
            if (union > 0) {
                val jaccard = intersection.toDouble() / union
                if (jaccard >= 0.6 && intersection >= 2) {
                    return true
                }
            }
            return false
        }
    }
}

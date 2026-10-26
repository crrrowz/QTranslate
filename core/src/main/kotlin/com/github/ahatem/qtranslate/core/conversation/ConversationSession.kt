package com.github.ahatem.qtranslate.core.conversation

import kotlinx.serialization.Serializable

/**
 * Where a stretch of speech was recorded.
 *
 * This is the audio source, not the speaker: [SYSTEM] holds everyone on the call mixed together,
 * and nothing here tells them apart.
 */
@Serializable
enum class AudioSource(val fileName: String) {
    /** The user's own microphone. */
    MICROPHONE("microphone.wav"),

    /** What the computer played — the other people on a Discord call. */
    SYSTEM("system.wav")
}

@Serializable
enum class SessionStatus {
    RECORDING,
    COMPLETED,

    /** The app stopped while recording. Audio up to the last write is kept and playable. */
    INTERRUPTED
}

/**
 * One recorded stream of a session.
 *
 * @param offsetMillis Where the stream's first sample falls on the session timeline. Devices
 *                     start at slightly different moments (system capture needs a second or two
 *                     to come up), so each stream remembers its own start.
 */
@Serializable
data class SessionStream(
    val source: AudioSource,
    val deviceName: String,
    val offsetMillis: Long = 0
)

/** A recognised word; times are on the session timeline. */
@Serializable
data class TranscriptWord(
    val text: String,
    val startMillis: Long,
    val endMillis: Long
)

/**
 * One utterance.
 *
 * @param startMillis On the session timeline (recorded time; pauses are not counted).
 * @param language    BCP-47 tag of the spoken language, when known.
 * @param pending     Speech was detected and is being transcribed; [text] is still empty.
 * @param draft       [text] came from the live recognizer and the accurate one is still working on it.
 * @param failed      Transcription failed; the audio is still there and can be retried.
 */
@Serializable
data class TranscriptSegment(
    val id: String,
    val source: AudioSource,
    val startMillis: Long,
    val endMillis: Long,
    val text: String = "",
    val language: String? = null,
    val words: List<TranscriptWord> = emptyList(),
    val pending: Boolean = false,
    val failed: Boolean = false,
    val draft: Boolean = false,
    val confirmedText: String = "",
    val confirmedWords: List<TranscriptWord> = emptyList()
)

const val COALESCE_PAUSE_THRESHOLD_MILLIS = 2_000L
const val COALESCE_MAX_DURATION_MILLIS = 15_000L

/**
 * A recorded conversation and its transcript.
 *
 * @param spokenLanguage BCP-47 tag of what the user speaks into the microphone, or "auto".
 * @param callLanguage   BCP-47 tag of what the people on the call speak, or "auto". Kept apart
 *                       because the two often differ, and naming the language helps recognition.
 * @param keepAudio      Whether the recordings are kept once the session ends. Without them a
 *                       segment cannot be replayed or re-transcribed, but nothing of anyone's
 *                       voice stays on disk.
 */
@Serializable
data class ConversationSession(
    val id: String,
    val title: String,
    val createdAt: Long,
    val durationMillis: Long = 0,
    val status: SessionStatus = SessionStatus.RECORDING,
    val spokenLanguage: String = "auto",
    val callLanguage: String = "auto",
    val keepAudio: Boolean = true,
    val streams: List<SessionStream> = emptyList(),
    val segments: List<TranscriptSegment> = emptyList()
) {
    /** The language the speech service is told for [source]. */
    fun languageOf(source: AudioSource): String =
        if (source == AudioSource.MICROPHONE) spokenLanguage else callLanguage

    /** Segments in timeline order, which is the order they were spoken. */
    fun withSegment(segment: TranscriptSegment): ConversationSession {
        val others = segments.filterNot { it.id == segment.id }
        val index = others.indexOfFirst {
            if (it.startMillis != segment.startMillis) {
                it.startMillis > segment.startMillis
            } else if (it.source != segment.source) {
                it.source.ordinal > segment.source.ordinal
            } else {
                it.id > segment.id
            }
        }.let { if (it < 0) others.size else it }
        return copy(segments = others.subList(0, index) + segment + others.subList(index, others.size))
    }

    /** Removes a segment by id. */
    fun withoutSegment(segmentId: String): ConversationSession =
        copy(segments = segments.filterNot { it.id == segmentId })
}

/** A search hit: the session and the segments in it that matched. */
data class SessionSearchHit(
    val session: ConversationSession,
    val segments: List<TranscriptSegment>
)

/**
 * Checks whether [candidate] can be coalesced with a new segment for [source].
 *
 * Rules:
 * - Same [source]
 * - Candidate is not failed
 * - Gap between candidate.endMillis and newStartMillis is <= 2_000 ms (and >= -500 ms)
 * - Combined duration <= 15_000 ms
 * - Language matches (or either is null/blank)
 * - No finalized segment from the other speaker occurred after candidate.startMillis
 */
fun ConversationSession.canCoalesce(
    candidate: TranscriptSegment,
    source: AudioSource,
    startMillis: Long,
    endMillis: Long,
    language: String? = null
): Boolean {
    if (candidate.source != source || candidate.failed) return false
    val gap = startMillis - candidate.endMillis
    if (gap !in -500..COALESCE_PAUSE_THRESHOLD_MILLIS) return false
    val combinedDuration = maxOf(candidate.endMillis, endMillis) - candidate.startMillis
    if (combinedDuration > COALESCE_MAX_DURATION_MILLIS) return false
    if (!candidate.language.isNullOrBlank() && !language.isNullOrBlank() && candidate.language != language) return false
    val otherFinalizedInBetween = segments.any {
        it.source != source && it.startMillis >= candidate.startMillis && !it.draft && !it.pending
    }
    if (otherFinalizedInBetween) return false
    return true
}

fun ConversationSession.findCoalesceCandidate(
    source: AudioSource,
    startMillis: Long,
    endMillis: Long,
    language: String? = null
): TranscriptSegment? {
    val candidate = segments.lastOrNull { it.source == source } ?: return null
    return if (canCoalesce(candidate, source, startMillis, endMillis, language)) candidate else null
}

fun coalesceSegments(first: TranscriptSegment, second: TranscriptSegment): TranscriptSegment {
    val combinedText = when {
        first.text.isBlank() -> second.text
        second.text.isBlank() -> first.text
        else -> "${first.text.trimEnd()} ${second.text.trimStart()}"
    }
    val normalizedSecondWords = second.words.map { word ->
        if (word.startMillis < second.startMillis) {
            word.copy(
                startMillis = word.startMillis + second.startMillis,
                endMillis = word.endMillis + second.startMillis
            )
        } else word
    }
    return first.copy(
        endMillis = maxOf(first.endMillis, second.endMillis),
        text = combinedText,
        words = first.words + normalizedSecondWords,
        draft = first.draft || second.draft,
        pending = false,
        failed = false
    )
}

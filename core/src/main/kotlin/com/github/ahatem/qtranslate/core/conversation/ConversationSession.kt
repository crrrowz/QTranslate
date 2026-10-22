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
    val draft: Boolean = false
)

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
        val index = others.indexOfFirst { it.startMillis > segment.startMillis }.let { if (it < 0) others.size else it }
        return copy(segments = others.subList(0, index) + segment + others.subList(index, others.size))
    }
}

/** A search hit: the session and the segments in it that matched. */
data class SessionSearchHit(
    val session: ConversationSession,
    val segments: List<TranscriptSegment>
)

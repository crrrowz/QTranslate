package com.github.ahatem.qtranslate.core.conversation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConversationSessionTest {

    @Test
    fun `withSegment maintains timeline ordering and deterministic tie-breaker for same startMillis`() {
        val s1 = TranscriptSegment(id = "mic-1", source = AudioSource.MICROPHONE, startMillis = 1000, endMillis = 2000, text = "mic first")
        val s2 = TranscriptSegment(id = "sys-1", source = AudioSource.SYSTEM, startMillis = 1000, endMillis = 2000, text = "sys first")
        val s3 = TranscriptSegment(id = "mic-2", source = AudioSource.MICROPHONE, startMillis = 3000, endMillis = 4000, text = "mic later")

        val session = ConversationSession(id = "test", title = "test", createdAt = 0)
            .withSegment(s3)
            .withSegment(s2)
            .withSegment(s1)

        assertEquals(listOf("mic-1", "sys-1", "mic-2"), session.segments.map { it.id })
    }

    @Test
    fun `coalescing merges consecutive segments from same speaker within pause threshold`() {
        val s1 = TranscriptSegment(
            id = "seg-1",
            source = AudioSource.MICROPHONE,
            startMillis = 1000,
            endMillis = 3000,
            text = "Hello world",
            words = listOf(TranscriptWord("Hello", 1000, 1500), TranscriptWord("world", 1600, 2000)),
            language = "en"
        )
        val session = ConversationSession(id = "test", title = "test", createdAt = 0).withSegment(s1)

        // Segment within 1000ms gap (< 2000ms threshold)
        val candidate = session.findCoalesceCandidate(
            source = AudioSource.MICROPHONE,
            startMillis = 4000,
            endMillis = 5500,
            language = "en"
        )
        assertNotNull(candidate)
        assertEquals("seg-1", candidate.id)

        val s2 = TranscriptSegment(
            id = "seg-2",
            source = AudioSource.MICROPHONE,
            startMillis = 4000,
            endMillis = 5500,
            text = "how are you",
            words = listOf(TranscriptWord("how", 4000, 4400), TranscriptWord("are", 4500, 4800), TranscriptWord("you", 4900, 5200)),
            language = "en"
        )
        val merged = coalesceSegments(candidate, s2)
        assertEquals("seg-1", merged.id)
        assertEquals(1000, merged.startMillis)
        assertEquals(5500, merged.endMillis)
        assertEquals("Hello world how are you", merged.text)
        assertEquals(5, merged.words.size)
    }

    @Test
    fun `coalescing rejects if other speaker spoke in between`() {
        val s1 = TranscriptSegment(id = "mic-1", source = AudioSource.MICROPHONE, startMillis = 1000, endMillis = 2000, text = "Are you there?")
        val s2 = TranscriptSegment(id = "sys-1", source = AudioSource.SYSTEM, startMillis = 2200, endMillis = 3000, text = "Yes I am")
        val session = ConversationSession(id = "test", title = "test", createdAt = 0)
            .withSegment(s1)
            .withSegment(s2)

        val candidate = session.findCoalesceCandidate(
            source = AudioSource.MICROPHONE,
            startMillis = 3500,
            endMillis = 5000,
            language = "en"
        )
        assertNull(candidate, "Should not coalesce when another speaker took a turn in between")
    }

    @Test
    fun `coalescing rejects if gap exceeds pause threshold`() {
        val s1 = TranscriptSegment(id = "mic-1", source = AudioSource.MICROPHONE, startMillis = 1000, endMillis = 2000, text = "First sentence")
        val session = ConversationSession(id = "test", title = "test", createdAt = 0).withSegment(s1)

        val candidate = session.findCoalesceCandidate(
            source = AudioSource.MICROPHONE,
            startMillis = 5000, // 3000ms gap > 2000ms
            endMillis = 7000
        )
        assertNull(candidate, "Should not coalesce when pause exceeds 2000ms")
    }

    @Test
    fun `coalescing rejects if combined duration exceeds maximum duration`() {
        val s1 = TranscriptSegment(id = "mic-1", source = AudioSource.MICROPHONE, startMillis = 1000, endMillis = 14000, text = "Very long sentence")
        val session = ConversationSession(id = "test", title = "test", createdAt = 0).withSegment(s1)

        val candidate = session.findCoalesceCandidate(
            source = AudioSource.MICROPHONE,
            startMillis = 14500,
            endMillis = 17000 // 17000 - 1000 = 16000ms > 15000ms
        )
        assertNull(candidate, "Should not coalesce when combined duration exceeds 15000ms")
    }
}

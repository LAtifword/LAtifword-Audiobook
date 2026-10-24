package com.latif.audiobook.offline

import java.security.MessageDigest

/** Pure local queue planner. Does not perform synthesis or network requests. */
internal data class NarrationSegment(
    val index: Int,
    val text: String,
    val voiceId: String,
    val cacheKey: String
)

internal class OfflineNarrationPlanner(
    private val maxChars: Int = 220,
    private val lookAhead: Int = 3
) {
    init {
        require(maxChars >= 40)
        require(lookAhead in 1..16)
    }

    fun plan(text: String, voiceId: String, revision: String): List<NarrationSegment> {
        require(voiceId.isNotBlank() && revision.isNotBlank())
        val normalized = text.replace(Regex("\\s+"), " ").trim()
        if (normalized.isEmpty()) return emptyList()
        val chunks = mutableListOf<String>()
        val current = StringBuilder()
        fun flush() {
            if (current.isNotEmpty()) {
                chunks.add(current.toString())
                current.setLength(0)
            }
        }
        for (sentence in normalized.split(Regex("(?<=[.!?؟؛。])\\s+"))) {
            for (word in sentence.split(' ')) {
                if (word.isEmpty()) continue
                var remaining = word
                while (remaining.length > maxChars) {
                    flush()
                    chunks.add(remaining.substring(0, maxChars))
                    remaining = remaining.substring(maxChars)
                }
                if (remaining.isEmpty()) continue
                if (current.isNotEmpty() && current.length + remaining.length + 1 > maxChars) flush()
                if (current.isNotEmpty()) current.append(' ')
                current.append(remaining)
            }
            flush()
        }
        return chunks.mapIndexed { index, chunk ->
            NarrationSegment(index, chunk, voiceId, hash(revision, voiceId, chunk))
        }
    }

    fun prefetchWindow(segments: List<NarrationSegment>, playingIndex: Int): List<NarrationSegment> =
        segments.drop((playingIndex + 1).coerceAtLeast(0)).take(lookAhead)

    fun switchVoice(
        segments: List<NarrationSegment>,
        fromIndex: Int,
        voiceId: String,
        revision: String
    ): List<NarrationSegment> {
        require(voiceId.isNotBlank() && revision.isNotBlank())
        return segments.map {
            if (it.index < fromIndex) it
            else it.copy(voiceId = voiceId, cacheKey = hash(revision, voiceId, it.text))
        }
    }

    private fun hash(revision: String, voiceId: String, text: String): String {
        val payload = listOf(revision, voiceId, text).joinToString("\u0000")
        return MessageDigest.getInstance("SHA-256")
            .digest(payload.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}

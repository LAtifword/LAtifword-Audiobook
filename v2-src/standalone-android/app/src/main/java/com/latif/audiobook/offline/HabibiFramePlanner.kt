package com.latif.audiobook.offline

/**
 * Splits already-local narration into requests that fit the pinned Habibi
 * F5_Preprocess ONNX graph's 4096 acoustic-frame ceiling.
 *
 * Uses the same duration estimate as SilmaF5Engine and reserves 32 frames
 * to avoid driving the model exactly to its observed boundary.
 * This is a safety planner, not a claim about speech quality or timing.
 */
internal object HabibiFramePlanner {
    const val MODEL_FRAME_LIMIT = 4096L
    private const val SAFETY_FRAMES = 32L
    private const val TAIL_FRAMES = 12

    fun estimateFrames(
        referenceSamples: Int,
        referenceTranscript: String,
        generationText: String,
        speed: Float = 1.0f
    ): Long {
        require(referenceSamples > 0) { "Reference recording is empty" }
        val refTextBytes = (referenceTranscript.trim() + " ").toByteArray(Charsets.UTF_8).size.coerceAtLeast(1)
        val genBytes = generationText.toByteArray(Charsets.UTF_8).size
        val refFrames = referenceSamples / 256 + 1
        val rate = if (speed <= 0f) 1.0 else speed.toDouble()
        return refFrames.toLong() +
            ((refFrames.toDouble() / refTextBytes.toDouble()) * genBytes.toDouble() / rate).toLong() +
            TAIL_FRAMES
    }

    fun plan(
        referenceSamples: Int,
        referenceTranscript: String,
        text: String,
        speed: Float = 1.0f
    ): List<String> {
        val cleaned = ArabicText.prepareForNarration(text)
        if (cleaned.isEmpty()) return emptyList()
        val safeLimit = MODEL_FRAME_LIMIT - SAFETY_FRAMES
        require(estimateFrames(referenceSamples, referenceTranscript, "", speed) < safeLimit) {
            "Reference audio exceeds the Habibi model's frame budget; shorten the reference recording."
        }
        val parts = mutableListOf<String>()
        var remaining = cleaned
        while (remaining.isNotEmpty()) {
            if (estimateFrames(referenceSamples, referenceTranscript, remaining, speed) <= safeLimit) {
                parts.add(remaining)
                break
            }

            // Walk Unicode code points: never split a surrogate pair.
            var index = 0
            var largestSafeEnd = 0
            while (index < remaining.length) {
                val next = index + Character.charCount(Character.codePointAt(remaining, index))
                if (estimateFrames(referenceSamples, referenceTranscript, remaining.substring(0, next), speed) > safeLimit) break
                largestSafeEnd = next
                index = next
            }
            require(largestSafeEnd > 0) {
                "Reference voice leaves no acoustic-frame budget for even one character."
            }
            // Prefer a word boundary, but guarantee progress for an oversized token.
            val lastSpace = remaining.lastIndexOf(' ', largestSafeEnd - 1)
            val cut = if (lastSpace >= largestSafeEnd / 2) lastSpace else largestSafeEnd
            val piece = remaining.substring(0, cut).trimEnd()
            require(piece.isNotEmpty()) { "Frame-aware segmentation produced an empty section" }
            parts.add(piece)
            remaining = remaining.substring(cut).trimStart()
        }
        check(parts.all { estimateFrames(referenceSamples, referenceTranscript, it, speed) <= safeLimit })
        return parts
    }
}

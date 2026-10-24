package com.latif.audiobook.offline

/** Generation tokens prevent stale audio from an old voice or seek reaching playback. */
internal data class PlaybackRequest(val generation: Long, val segment: NarrationSegment)

/** Thread-safe control plane for a local PCM renderer. No network or Android dependency. */
internal class OfflinePlaybackQueue(private val planner: OfflineNarrationPlanner = OfflineNarrationPlanner()) {
    private var segments = emptyList<NarrationSegment>()
    private var cursor = 0
    private var generation = 0L
    private val pending = LinkedHashMap<Int, PlaybackRequest>()

    @Synchronized fun open(text: String, voiceId: String, modelRevision: String): List<PlaybackRequest> {
        generation++
        segments = planner.plan(text, voiceId, modelRevision)
        cursor = 0
        pending.clear()
        return schedule()
    }

    @Synchronized fun seek(index: Int): List<PlaybackRequest> {
        require(index in 0..segments.size)
        generation++
        cursor = index
        pending.clear()
        return schedule()
    }

    @Synchronized fun switchVoice(voiceId: String, modelRevision: String): List<PlaybackRequest> {
        generation++
        segments = planner.switchVoice(segments, cursor, voiceId, modelRevision)
        pending.clear()
        return schedule()
    }

    /** Called after PCM for this segment has actually been queued to the audio sink. */
    @Synchronized fun acknowledge(request: PlaybackRequest): List<PlaybackRequest> {
        if (request.generation != generation || request.segment.index != cursor) return emptyList()
        if (pending[cursor] != request) return emptyList()
        pending.remove(cursor)
        cursor++
        return schedule()
    }

    /** Renderer must check immediately before emitting PCM, not only before synthesis. */
    @Synchronized fun accepts(request: PlaybackRequest): Boolean =
        request.generation == generation && request.segment.index >= cursor &&
            pending[request.segment.index] == request

    @Synchronized fun stop() {
        generation++
        pending.clear()
        cursor = segments.size
    }

    @Synchronized fun currentIndex(): Int = cursor

    /** Returns the request for the current playhead, or null at end-of-book. */
    @Synchronized fun currentRequest(): PlaybackRequest? = pending[cursor]

    /** Snapshot of the current prefetch window for a synthesis worker. */
    @Synchronized fun pendingRequests(): List<PlaybackRequest> = pending.values.toList()

    private fun schedule(): List<PlaybackRequest> {
        val added = mutableListOf<PlaybackRequest>()
        val end = (cursor + 4).coerceAtMost(segments.size)
        for (index in cursor until end) {
            if (index !in pending) {
                val request = PlaybackRequest(generation, segments[index])
                pending[index] = request
                added.add(request)
            }
        }
        return added
    }
}

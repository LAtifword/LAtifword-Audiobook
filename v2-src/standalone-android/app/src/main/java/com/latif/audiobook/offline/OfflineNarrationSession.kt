package com.latif.audiobook.offline

import android.content.Context
import android.net.Uri
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Connects the existing embedded F5 ONNX model to the local AudioTrack sink.
 * Runs on a dedicated background thread. The engine is retained for the session.
 * Do not share this engine with AudiobookService simultaneously.
 */
internal class OfflineNarrationSession(private val context: Context) : AutoCloseable {
    private val queue = OfflinePlaybackQueue()
    private val cancelled = AtomicBoolean(false)
    private val lock = Any()
    private var worker: Thread? = null
    private var sink: OfflinePcmSink? = null
    private var engine: SilmaF5Engine? = null

    @Volatile private var closed = false
    @Volatile private var reference: VoiceReference? = null

    fun start(text: String, voiceId: String = "built-in", revision: String = "habibi-msa") {
        synchronized(lock) {
            check(!closed)
            halt()
            queue.open(text, voiceId, revision)
            cancelled.set(false)
            worker = Thread({ render() }, "LATIF-offline-reader").apply { start() }
        }
    }

    fun seek(segmentIndex: Int) {
        synchronized(lock) {
            check(!closed)
            halt()
            queue.seek(segmentIndex)
            cancelled.set(false)
            worker = Thread({ render() }, "LATIF-offline-reader").apply { start() }
        }
    }

    /**
     * Voice selection must be paired with an authorized audio reference.
     * The new reference is loaded before the next segment is synthesized.
     */
    fun switchVoice(voiceId: String, revision: String, audioUri: Uri, transcript: String) {
        require(transcript.isNotBlank())
        synchronized(lock) {
            check(!closed)
            halt()
            val activeEngine = engine ?: SilmaF5Engine(context).also { engine = it }
            reference = activeEngine.referenceFromUri(audioUri, transcript)
            queue.switchVoice(voiceId, revision)
            cancelled.set(false)
            worker = Thread({ render() }, "LATIF-offline-reader").apply { start() }
        }
    }

    private fun render() {
        try {
            val activeEngine = synchronized(lock) {
                engine ?: SilmaF5Engine(context).also { engine = it }
            }
            activeEngine.load()
            val voice = reference ?: activeEngine.builtInReference().also { reference = it }
            val activeSink = synchronized(lock) {
                sink ?: OfflinePcmSink(activeEngine.sampleRate).also { sink = it }
            }
            activeSink.play()
            while (!cancelled.get()) {
                val request = queue.currentRequest() ?: break
                val pcm = activeEngine.synthesize(
                    reference = voice,
                    text = request.segment.text,
                    cancelled = cancelled
                )
                try {
                    if (!queue.accepts(request) || cancelled.get()) break
                    val delivered = activeSink.write(pcm) {
                        !cancelled.get() && queue.accepts(request)
                    }
                    if (!delivered) break
                    queue.acknowledge(request)
                } finally {
                    pcm.fill(0)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("OfflineNarrationSession", "Local playback failed", e)
        }
    }

    /** Interrupt worker before mutating or flushing the sink. */
    private fun halt() {
        cancelled.set(true)
        worker?.interrupt()
        sink?.flush()
        worker?.join(1_000)
        check(worker?.isAlive != true) { "Previous synthesis worker did not stop" }
        worker = null
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            halt()
            closed = true
            queue.stop()
            sink?.close()
            sink = null
            engine?.close()
            engine = null
            reference = null
        }
    }
}

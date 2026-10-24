package com.latif.audiobook.offline

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground-only, fully local reader. One worker owns the ONNX sessions and PCM sink.
 * Commands invalidate old requests immediately, without blocking the UI on ONNX inference.
 */
internal class OfflineNarrationSession(
    context: Context,
    private val onStatus: (String) -> Unit = {}
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val queue = OfflinePlaybackQueue()
    private val lock = Any()
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "LATIF-local-narrator").apply { isDaemon = true }
    }

    private data class VoiceChoice(val uri: Uri?, val transcript: String)
    private var choice = VoiceChoice(null, "")
    private var engine: SilmaF5Engine? = null // worker thread only
    @Volatile private var sink: OfflinePcmSink? = null
    private var activeCancellation = AtomicBoolean(false)
    private var activeJob: Future<*>? = null
    private var closed = false

    fun start(
        text: String,
        voiceId: String = "built-in",
        revision: String = "habibi-msa",
        referenceUri: Uri? = null,
        referenceTranscript: String = ""
    ) {
        require(text.isNotBlank()) { "A book or pasted text is required" }
        if (referenceUri != null) require(referenceTranscript.isNotBlank()) {
            "The reference recording requires its exact transcript"
        }
        synchronized(lock) {
            check(!closed)
            cancelCurrentLocked()
            choice = VoiceChoice(referenceUri, referenceTranscript)
            queue.open(text, voiceId, revision)
            submitLocked()
        }
    }

    fun seek(segmentIndex: Int) {
        synchronized(lock) {
            check(!closed)
            cancelCurrentLocked()
            queue.seek(segmentIndex)
            submitLocked()
        }
    }

    fun switchVoice(
        voiceId: String,
        revision: String,
        referenceUri: Uri? = null,
        referenceTranscript: String = ""
    ) {
        if (referenceUri != null) require(referenceTranscript.isNotBlank())
        synchronized(lock) {
            check(!closed)
            cancelCurrentLocked()
            choice = VoiceChoice(referenceUri, referenceTranscript)
            queue.switchVoice(voiceId, revision)
            submitLocked()
        }
    }

    fun stop() {
        synchronized(lock) {
            if (closed) return
            cancelCurrentLocked()
            queue.stop()
            onStatus("Playback stopped")
        }
    }

    private fun cancelCurrentLocked() {
        activeCancellation.set(true)
        activeJob?.cancel(true)
        // AudioTrack may still contain a short tail; discard it on any new command.
        runCatching { sink?.flush() }
    }

    private fun submitLocked() {
        val cancellation = AtomicBoolean(false)
        activeCancellation = cancellation
        val selectedVoice = choice
        activeJob = executor.submit { render(cancellation, selectedVoice) }
    }

    private fun render(cancelled: AtomicBoolean, voice: VoiceChoice) {
        try {
            if (cancelled.get()) return
            val activeEngine = engine ?: SilmaF5Engine(appContext).also { engine = it }
            onStatus("Loading local speech model…")
            activeEngine.load()
            if (cancelled.get()) return
            val reference = if (voice.uri == null) activeEngine.builtInReference()
                else activeEngine.referenceFromUri(voice.uri, voice.transcript)
            if (cancelled.get()) return
            val output = sink ?: OfflinePcmSink(activeEngine.sampleRate).also { sink = it }
            output.flush()
            output.play()
            while (!cancelled.get()) {
                val request = queue.currentRequest() ?: break
                val started = SystemClock.elapsedRealtime()
                onStatus("Synthesizing section ${request.segment.index + 1}…")
                val pcm = activeEngine.synthesize(
                    reference = reference,
                    text = request.segment.text,
                    cancelled = cancelled
                )
                try {
                    if (cancelled.get() || !queue.accepts(request)) break
                    onStatus("Section ${request.segment.index + 1} ready in " +
                        "${SystemClock.elapsedRealtime() - started} ms")
                    if (!output.write(pcm) { !cancelled.get() && queue.accepts(request) }) break
                    queue.acknowledge(request)
                } finally {
                    pcm.fill(0)
                }
            }
            if (!cancelled.get()) onStatus("End of available narration")
        } catch (t: Exception) {
            if (!cancelled.get()) {
                Log.e("OfflineNarrationSession", "Offline narration failed", t)
                onStatus("Offline playback error: ${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            cancelCurrentLocked()
            queue.stop()
            // Cleanup runs after the active inference task, never concurrently with ONNX.
            executor.execute {
                runCatching { sink?.close() }
                sink = null
                runCatching { engine?.close() }
                engine = null
            }
            executor.shutdown()
        }
    }
}

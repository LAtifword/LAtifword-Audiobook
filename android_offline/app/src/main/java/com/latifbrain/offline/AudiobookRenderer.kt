package com.latifbrain.offline

import android.content.Context
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class AudiobookRenderer(
    private val context: Context,
    private val tts: OfflineArabicTts,
) {
    private val cancelled = AtomicBoolean(false)

    data class Progress(val completed: Int, val total: Int, val message: String)

    fun cancel() = cancelled.set(true)

    fun render(text: String, speed: Float, onProgress: (Progress) -> Unit): File {
        cancelled.set(false)
        val chunks = BookChunker.split(text)
        require(chunks.isNotEmpty()) { "No text to synthesize" }

        val work = File(context.cacheDir, "audiobook_segments").apply { mkdirs() }
        val fingerprint = (text + "|" + speed).hashCode().toUInt().toString(16)
        val session = File(work, fingerprint).apply { mkdirs() }

        chunks.forEachIndexed { index, chunk ->
            if (cancelled.get()) throw InterruptedException("Synthesis cancelled")
            val pcm = File(session, "%05d.pcm".format(index))
            if (!pcm.exists() || pcm.length() == 0L) {
                onProgress(Progress(index, chunks.size, "Generating ${index + 1}/${chunks.size}"))
                WavWriter.floatToPcm16(tts.synthesize(chunk, speed), pcm)
            } else {
                onProgress(Progress(index, chunks.size, "Resuming ${index + 1}/${chunks.size}"))
            }
        }

        val segments = chunks.indices.map { File(session, "%05d.pcm".format(it)) }
        val outDir = context.getExternalFilesDir("Audiobooks") ?: File(context.filesDir, "Audiobooks")
        outDir.mkdirs()
        val output = File(outDir, "latif_brain_${fingerprint}.wav")
        onProgress(Progress(chunks.size, chunks.size, "Assembling WAV"))
        WavWriter.mergePcm16Mono(segments, output, tts.sampleRate())
        onProgress(Progress(chunks.size, chunks.size, "Done"))
        return output
    }
}

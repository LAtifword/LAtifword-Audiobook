package com.latif.audiobook.offline

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

/**
 * Local mono PCM16 sink. Caller owns a background audio thread.
 * Flush on seek/voice change; close on stop or activity teardown.
 */
internal class OfflinePcmSink(
    private val sampleRate: Int,
    private val bufferMs: Int = 400
) : AutoCloseable {
    init {
        require(sampleRate in 8_000..96_000)
        require(bufferMs in 100..2_000)
    }

    private val track: AudioTrack
    init {
        val format = AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val minimum = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        require(minimum > 0) { "Unsupported PCM format or output device" }
        val requested = sampleRate * 2 * bufferMs / 1000
        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(format)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(minimum, requested))
            .build()
        check(track.state == AudioTrack.STATE_INITIALIZED) { "AudioTrack initialization failed" }
    }

    @Synchronized fun play() = track.play()
    @Synchronized fun pause() = track.pause()

    /** Only call on the playback worker; a generation change must stop that worker first. */
    fun write(pcm: ShortArray, isCurrent: () -> Boolean): Boolean {
        var offset = 0
        while (offset < pcm.size) {
            if (!isCurrent()) return false
            val length = minOf(2048, pcm.size - offset)
            val written = track.write(pcm, offset, length, AudioTrack.WRITE_BLOCKING)
            if (written <= 0) throw IllegalStateException("AudioTrack write failed: $written")
            offset += written
        }
        return true
    }

    @Synchronized fun flush() {
        track.pause()
        track.flush()
    }

    @Synchronized override fun close() {
        track.pause()
        track.flush()
        track.release()
    }
}

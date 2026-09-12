package com.latif.audiobook.offline

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

class M4aWriter(
    private val context: Context,
    title: String,
    private val sampleRate: Int,
    private val bitrate: Int = 64_000,
) {
    private val codec: MediaCodec
    private val muxer: MediaMuxer
    private val outputUri: Uri
    private val pfd: android.os.ParcelFileDescriptor?

    private var trackIndex = -1
    private var muxerStarted = false
    private var totalSamples = 0L
    private var closed = false
    private val info = MediaCodec.BufferInfo()

    init {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, safeName(title) + ".m4a")
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
                put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/LATIF Audiobooks")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            outputUri = requireNotNull(context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values))
            pfd = context.contentResolver.openFileDescriptor(outputUri, "rw")
                ?: error("Unable to create audiobook output")
        } else {
            val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "LATIF Audiobooks")
            dir.mkdirs()
            val file = File(dir, safeName(title) + ".m4a")
            outputUri = Uri.fromFile(file)
            pfd = null
        }

        muxer = if (Build.VERSION.SDK_INT >= 29) {
            MediaMuxer(requireNotNull(pfd).fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        } else {
            MediaMuxer(requireNotNull(outputUri.path), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        }

        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }
        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
    }

    fun writeFloat(samples: FloatArray) {
        if (samples.isEmpty()) return
        val pcm = ByteArray(samples.size * 2)
        val bb = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in samples) {
            val s = (sample.coerceIn(-1f, 1f) * 32767f).roundToInt().toShort()
            bb.putShort(s)
        }
        feed(pcm)
    }

    fun writeSilence(milliseconds: Int) {
        val count = (sampleRate * milliseconds / 1000.0).roundToInt().coerceAtLeast(0)
        if (count > 0) feed(ByteArray(count * 2))
    }

    private fun feed(data: ByteArray) {
        var offset = 0
        while (offset < data.size) {
            val inputIndex = codec.dequeueInputBuffer(10_000)
            if (inputIndex >= 0) {
                val input = codec.getInputBuffer(inputIndex) ?: continue
                input.clear()
                val bytes = minOf(input.remaining(), data.size - offset)
                input.put(data, offset, bytes)
                val samplesInBuffer = bytes / 2
                val pts = totalSamples * 1_000_000L / sampleRate
                codec.queueInputBuffer(inputIndex, 0, bytes, pts, 0)
                totalSamples += samplesInBuffer
                offset += bytes
            }
            drain(false)
        }
        drain(false)
    }

    private fun drain(endOfStream: Boolean) {
        while (true) {
            val outIndex = codec.dequeueOutputBuffer(info, if (endOfStream) 10_000 else 0)
            when {
                outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) return
                }
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (muxerStarted) error("AAC output format changed twice")
                    trackIndex = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxerStarted = true
                }
                outIndex >= 0 -> {
                    val output = codec.getOutputBuffer(outIndex)
                    if (output != null && info.size > 0 && muxerStarted && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        output.position(info.offset)
                        output.limit(info.offset + info.size)
                        muxer.writeSampleData(trackIndex, output, info)
                    }
                    val eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    codec.releaseOutputBuffer(outIndex, false)
                    if (eos) return
                }
            }
        }
    }

    fun finish(): Uri {
        if (closed) return outputUri
        while (true) {
            val index = codec.dequeueInputBuffer(10_000)
            if (index >= 0) {
                val pts = totalSamples * 1_000_000L / sampleRate
                codec.queueInputBuffer(index, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                break
            }
            drain(false)
        }
        drain(true)
        codec.stop()
        codec.release()
        if (muxerStarted) muxer.stop()
        muxer.release()
        pfd?.close()
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }
            context.contentResolver.update(outputUri, values, null, null)
        }
        closed = true
        return outputUri
    }

    fun abort() {
        if (closed) return
        runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { if (muxerStarted) muxer.stop() }
        runCatching { muxer.release() }
        runCatching { pfd?.close() }
        if (Build.VERSION.SDK_INT >= 29) runCatching { context.contentResolver.delete(outputUri, null, null) }
        else outputUri.path?.let { runCatching { File(it).delete() } }
        closed = true
    }

    companion object {
        fun safeName(input: String): String {
            val base = input.trim().ifBlank { "LATIF_Audiobook" }
            return base.replace(Regex("[\\/:*?\"<>|\\p{Cntrl}]"), "_").take(96)
        }
    }
}

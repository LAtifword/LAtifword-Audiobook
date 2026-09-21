package com.latif.audiobook.offline

import android.content.ContentValues
import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Transactional AAC-LC/M4A writer.
 *
 * Audio is encoded into a private temporary file first. Only a successfully finalized,
 * non-empty M4A is published to MediaStore, so a crash never leaves a half-written
 * audiobook visible in the user's Music library.
 */
class M4aWriter(
    context: Context,
    title: String,
    private val sampleRate: Int,
    private val bitrate: Int = 128_000,
    private val channelCount: Int = 1,
    private val metadata: AudiobookMetadata? = null,
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val safeTitle = safeName(title)
    private val tempDirectory = File(appContext.cacheDir, "latif-m4a").apply { mkdirs() }
    private val temporaryFile = File(tempDirectory, "${safeTitle}_${System.nanoTime()}.m4a.tmp")

    private val codec: MediaCodec
    private val muxer: MediaMuxer
    private var trackIndex = -1
    private var muxerStarted = false
    private var submittedSamples = 0L
    private var finished = false
    private var aborted = false
    private val stopped = AtomicBoolean(false)

    init {
        require(sampleRate > 0) { "Invalid sample rate: $sampleRate" }
        require(channelCount == 1 || channelCount == 2) { "Only mono or stereo PCM is supported" }
        require(bitrate > 0) { "Invalid AAC bitrate: $bitrate" }

        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC,
            sampleRate,
            channelCount,
        ).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, INPUT_BUFFER_SIZE_BYTES)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            }
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        muxer = MediaMuxer(temporaryFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    }

    @Synchronized
    fun writePcm16(samples: ShortArray) {
        checkCanWrite()
        if (samples.isEmpty()) return

        var offset = 0
        while (offset < samples.size) {
            val inputIndex = dequeueInputBufferOrThrow()
            val inputBuffer = codec.getInputBuffer(inputIndex)
                ?: throw IOException("MediaCodec returned a null input buffer")
            inputBuffer.clear()
            inputBuffer.order(ByteOrder.LITTLE_ENDIAN)

            val capacitySamples = inputBuffer.remaining() / BYTES_PER_SAMPLE
            val samplesToWrite = minOf(capacitySamples, samples.size - offset)
            var index = 0
            while (index < samplesToWrite) {
                inputBuffer.putShort(samples[offset + index])
                index++
            }

            val frames = samplesToWrite / channelCount
            codec.queueInputBuffer(
                inputIndex,
                0,
                samplesToWrite * BYTES_PER_SAMPLE,
                samplesToPts(submittedSamples),
                0,
            )
            submittedSamples += frames.toLong()
            offset += samplesToWrite
            drainEncoder(endOfStream = false)
        }
    }

    /** Writes silence with a small fixed buffer instead of allocating the whole pause. */
    @Synchronized
    fun writeSilence(durationMs: Int) {
        checkCanWrite()
        if (durationMs <= 0) return

        var remainingFrames = sampleRate.toLong() * durationMs / 1_000L
        if (remainingFrames <= 0L) return
        val silence = ShortArray(SILENCE_BUFFER_FRAMES * channelCount)
        while (remainingFrames > 0L) {
            val frames = minOf(remainingFrames, SILENCE_BUFFER_FRAMES.toLong()).toInt()
            if (frames == SILENCE_BUFFER_FRAMES) {
                writePcm16(silence)
            } else {
                writePcm16(silence.copyOf(frames * channelCount))
            }
            remainingFrames -= frames
        }
    }

    @Synchronized
    fun currentDurationMs(): Long = submittedSamples * 1_000L / sampleRate.toLong()

    @Synchronized
    fun finish(): Uri {
        checkCanWrite()
        check(!finished) { "M4aWriter has already finished" }

        try {
            queueEndOfStream()
            drainEncoder(endOfStream = true)
            stopCodecAndMuxer()
            require(temporaryFile.isFile && temporaryFile.length() > 0L) {
                "M4A output is empty"
            }
            val uri = publishToMusicDirectory()
            finished = true
            return uri
        } catch (t: Throwable) {
            abort()
            throw t
        }
    }

    @Synchronized
    fun abort() {
        if (aborted || finished) return
        aborted = true
        stopCodecAndMuxer()
        runCatching { temporaryFile.delete() }
    }

    override fun close() {
        if (!finished && !aborted) abort()
    }

    private fun checkCanWrite() {
        check(!finished) { "M4aWriter is already finished" }
        check(!aborted) { "M4aWriter has been aborted" }
        check(!stopped.get()) { "M4aWriter is stopped" }
    }

    private fun dequeueInputBufferOrThrow(): Int {
        while (true) {
            val index = codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
            when {
                index >= 0 -> return index
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> drainEncoder(endOfStream = false)
                else -> throw IOException("Unable to obtain MediaCodec input buffer: $index")
            }
        }
    }

    private fun queueEndOfStream() {
        val inputIndex = dequeueInputBufferOrThrow()
        codec.queueInputBuffer(
            inputIndex,
            0,
            0,
            samplesToPts(submittedSamples),
            MediaCodec.BUFFER_FLAG_END_OF_STREAM,
        )
    }

    private fun drainEncoder(endOfStream: Boolean) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val outputIndex = codec.dequeueOutputBuffer(info, if (endOfStream) OUTPUT_TIMEOUT_US else 0L)
            when {
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) return
                }
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    check(!muxerStarted) { "AAC output format changed twice" }
                    trackIndex = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxerStarted = true
                }
                outputIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                outputIndex >= 0 -> {
                    val outputBuffer = codec.getOutputBuffer(outputIndex)
                        ?: throw IOException("MediaCodec returned a null output buffer")
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    try {
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            info.size = 0
                        }
                        if (info.size > 0) {
                            check(muxerStarted) { "AAC data arrived before MediaMuxer started" }
                            outputBuffer.position(info.offset)
                            outputBuffer.limit(info.offset + info.size)
                            muxer.writeSampleData(trackIndex, outputBuffer, info)
                        }
                    } finally {
                        codec.releaseOutputBuffer(outputIndex, false)
                    }
                    if (eos) return
                }
                else -> throw IOException("Unexpected MediaCodec output result: $outputIndex")
            }
        }
    }

    private fun samplesToPts(samples: Long): Long = samples * 1_000_000L / sampleRate.toLong()

    private fun stopCodecAndMuxer() {
        if (stopped.getAndSet(true)) return
        runCatching { codec.stop() }
        runCatching { codec.release() }
        if (muxerStarted) runCatching { muxer.stop() }
        runCatching { muxer.release() }
    }

    private fun publishToMusicDirectory(): Uri {
        val fileName = "$safeTitle.m4a"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            publishWithMediaStore(fileName)
        } else {
            publishPreQ(fileName)
        }
    }

    private fun publishWithMediaStore(fileName: String): Uri {
        val resolver = appContext.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
            put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/LATIF Audiobooks")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
            put(MediaStore.Audio.Media.TITLE, metadata?.title ?: safeTitle)
            metadata?.author?.let { put(MediaStore.Audio.Media.ARTIST, it) }
            metadata?.album?.let { put(MediaStore.Audio.Media.ALBUM, it) }
            metadata?.narrator?.let { put(MediaStore.Audio.Media.COMPOSER, it) }
            metadata?.year?.let { put(MediaStore.Audio.Media.YEAR, it) }
            metadata?.genre?.let { put("genre", it) }
            put(MediaStore.Audio.Media.DATE_ADDED, System.currentTimeMillis() / 1_000L)
        }
        val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Unable to create MediaStore audio item")
        try {
            resolver.openOutputStream(uri, "w")?.use { output ->
                FileInputStream(temporaryFile).use { input -> input.copyTo(output) }
            } ?: throw IOException("Unable to open MediaStore output stream")
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) },
                null,
                null,
            )
            temporaryFile.delete()
            return uri
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)
            throw t
        }
    }

    @Suppress("DEPRECATION")
    private fun publishPreQ(fileName: String): Uri {
        val parent = File(
            appContext.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: appContext.filesDir,
            "LATIF Audiobooks",
        ).apply { mkdirs() }
        val target = File(parent, fileName)
        val staging = File(parent, "$fileName.tmp")
        try {
            FileInputStream(temporaryFile).use { input ->
                FileOutputStream(staging).use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
            }
            if (target.exists() && !target.delete()) {
                throw IOException("Unable to replace ${target.absolutePath}")
            }
            if (!staging.renameTo(target)) {
                throw IOException("Unable to commit ${target.absolutePath}")
            }
            temporaryFile.delete()
            return Uri.fromFile(target)
        } catch (t: Throwable) {
            staging.delete()
            throw t
        }
    }

    companion object {
        private const val BYTES_PER_SAMPLE = 2
        private const val INPUT_BUFFER_SIZE_BYTES = 64 * 1024
        private const val INPUT_TIMEOUT_US = 10_000L
        private const val OUTPUT_TIMEOUT_US = 10_000L
        private const val SILENCE_BUFFER_FRAMES = 1024

        fun safeName(input: String): String {
            return input.trim()
                .ifBlank { "LATIF Audiobook" }
                .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
                .replace(Regex("\\s+"), " ")
                .trim()
                .take(120)
        }
    }
}

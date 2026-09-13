package com.latif.audiobook.offline

import android.content.Context
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor

/** Minimal WAV PCM16 reader/resampler used by the fully-offline F5 voice-cloning engine. */
object WavPcm {
    data class Audio(val samples: ShortArray, val sampleRate: Int)

    fun fromAsset(context: Context, assetPath: String, targetRate: Int = 24_000): ShortArray =
        context.assets.open(assetPath).use { readPcm16(it, targetRate).samples }

    fun fromUri(context: Context, uri: Uri, targetRate: Int = 24_000): ShortArray =
        context.contentResolver.openInputStream(uri)?.use { readPcm16(it, targetRate).samples }
            ?: error("Unable to open reference voice file")

    fun readPcm16(input: InputStream, targetRate: Int = 24_000): Audio {
        val bytes = input.readAllBytesCompat()
        require(bytes.size >= 44) { "Reference voice must be a PCM WAV file" }
        require(String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF") { "Reference voice must be WAV/RIFF" }
        require(String(bytes, 8, 4, Charsets.US_ASCII) == "WAVE") { "Reference voice must be WAVE" }

        var offset = 12
        var channels = 0
        var sampleRate = 0
        var bits = 0
        var format = 0
        var dataOffset = -1
        var dataSize = -1

        while (offset + 8 <= bytes.size) {
            val id = String(bytes, offset, 4, Charsets.US_ASCII)
            val size = leInt(bytes, offset + 4)
            val payload = offset + 8
            if (payload + size > bytes.size) break
            when (id) {
                "fmt " -> {
                    require(size >= 16) { "Invalid WAV format chunk" }
                    format = leShort(bytes, payload).toInt() and 0xffff
                    channels = leShort(bytes, payload + 2).toInt() and 0xffff
                    sampleRate = leInt(bytes, payload + 4)
                    bits = leShort(bytes, payload + 14).toInt() and 0xffff
                }
                "data" -> {
                    dataOffset = payload
                    dataSize = size
                    break
                }
            }
            offset = payload + size + (size and 1)
        }

        require(format == 1) { "Reference WAV must use uncompressed PCM" }
        require(channels == 1 || channels == 2) { "Reference WAV must be mono or stereo" }
        require(bits == 16) { "Reference WAV must be 16-bit PCM" }
        require(sampleRate in 8_000..192_000) { "Invalid reference WAV sample rate" }
        require(dataOffset >= 0 && dataSize > 0) { "Reference WAV contains no audio" }

        val frames = dataSize / (channels * 2)
        val mono = ShortArray(frames)
        var p = dataOffset
        for (i in 0 until frames) {
            if (channels == 1) {
                mono[i] = leShort(bytes, p)
                p += 2
            } else {
                val l = leShort(bytes, p).toInt()
                val r = leShort(bytes, p + 2).toInt()
                mono[i] = ((l + r) / 2).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                p += 4
            }
        }

        if (sampleRate == targetRate) return Audio(mono, sampleRate)
        return Audio(resampleLinear(mono, sampleRate, targetRate), targetRate)
    }

    private fun resampleLinear(input: ShortArray, fromRate: Int, toRate: Int): ShortArray {
        if (input.isEmpty() || fromRate == toRate) return input
        val outLength = ((input.size.toLong() * toRate) / fromRate).toInt().coerceAtLeast(1)
        val out = ShortArray(outLength)
        val scale = fromRate.toDouble() / toRate.toDouble()
        for (i in out.indices) {
            val pos = i * scale
            val left = floor(pos).toInt().coerceIn(0, input.lastIndex)
            val right = (left + 1).coerceAtMost(input.lastIndex)
            val t = pos - left
            val value = input[left] * (1.0 - t) + input[right] * t
            out[i] = value.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return out
    }

    private fun leShort(data: ByteArray, offset: Int): Short =
        ByteBuffer.wrap(data, offset, 2).order(ByteOrder.LITTLE_ENDIAN).short

    private fun leInt(data: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(data, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int

    private fun InputStream.readAllBytesCompat(): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(1024 * 128)
        while (true) {
            val n = read(buffer)
            if (n <= 0) break
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }
}

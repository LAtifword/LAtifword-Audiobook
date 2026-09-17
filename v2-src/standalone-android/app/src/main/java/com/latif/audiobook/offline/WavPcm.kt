package com.latif.audiobook.offline

import android.content.Context
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.InputStream

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
        // Q32 fixed-point interpolation avoids per-sample Double/floor work and
        // temporary objects on mid-range Android CPUs.
        val fractionBits = 32
        val fractionOne = 1L shl fractionBits
        val fractionMask = fractionOne - 1L
        val step = (fromRate.toLong() shl fractionBits) / toRate.toLong()
        var position = 0L
        for (i in out.indices) {
            val left = (position ushr fractionBits).toInt().coerceIn(0, input.lastIndex)
            val right = (left + 1).coerceAtMost(input.lastIndex)
            val fraction = position and fractionMask
            val value = (input[left].toLong() * (fractionOne - fraction) +
                input[right].toLong() * fraction) shr fractionBits
            out[i] = value.coerceIn(Short.MIN_VALUE.toLong(), Short.MAX_VALUE.toLong()).toShort()
            position += step
        }
        return out
    }

    private fun leShort(data: ByteArray, offset: Int): Short =
        ((data[offset].toInt() and 0xff) or (data[offset + 1].toInt() shl 8)).toShort()

    private fun leInt(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xff) or
            ((data[offset + 1].toInt() and 0xff) shl 8) or
            ((data[offset + 2].toInt() and 0xff) shl 16) or
            (data[offset + 3].toInt() shl 24)

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

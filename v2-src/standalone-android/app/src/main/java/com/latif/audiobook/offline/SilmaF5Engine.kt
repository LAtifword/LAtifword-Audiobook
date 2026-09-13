package com.latif.audiobook.offline

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxValue
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.nio.IntBuffer
import java.nio.LongBuffer
import java.nio.ShortBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/**
 * Fully local SILMA TTS v1 / F5-TTS ONNX runtime.
 *
 * The model is split into three ONNX graphs exported by DakeQQ/F5-TTS-ONNX:
 * preprocess -> iterative transformer denoising -> decoder/vocoder.
 * No network call, account, API key or remote inference is used at runtime.
 */
class SilmaF5Engine(private val context: Context) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private var preprocess: OrtSession? = null
    private var transformer: OrtSession? = null
    private var decoder: OrtSession? = null
    private var vocab: Map<String, Int> = emptyMap()

    val sampleRate: Int = 24_000

    @Synchronized
    fun load(progress: ((String) -> Unit)? = null) {
        if (preprocess != null && transformer != null && decoder != null) return

        progress?.invoke("Preparing SILMA voice-cloning engine…")
        val preFile = extractAsset("silma-f5/F5_Preprocess.onnx", progress)
        val transformerFile = extractAsset("silma-f5/model.onnx", progress)
        val decodeFile = extractAsset("silma-f5/F5_Decode.onnx", progress)

        vocab = context.assets.open("silma-f5/vocab.txt")
            .bufferedReader(Charsets.UTF_8)
            .useLines { lines ->
                val map = LinkedHashMap<String, Int>()
                lines.forEachIndexed { index, raw ->
                    val token = raw.removeSuffix("\r")
                    map[token] = index
                }
                map
            }

        require(vocab.isNotEmpty()) { "SILMA vocabulary is missing" }

        val options = OrtSession.SessionOptions()
        try {
            progress?.invoke("Loading SILMA preprocess graph…")
            preprocess = env.createSession(preFile.absolutePath, options)
            progress?.invoke("Loading SILMA transformer…")
            transformer = env.createSession(transformerFile.absolutePath, options)
            progress?.invoke("Loading SILMA decoder…")
            decoder = env.createSession(decodeFile.absolutePath, options)
        } catch (t: Throwable) {
            close()
            throw t
        } finally {
            options.close()
        }
    }

    fun builtInReference(): VoiceReference {
        val samples = WavPcm.fromAsset(context, DEFAULT_REFERENCE_ASSET, sampleRate)
        return VoiceReference(samples, DEFAULT_REFERENCE_TEXT)
    }

    fun referenceFromUri(uri: android.net.Uri, transcript: String): VoiceReference {
        require(transcript.isNotBlank()) { "Reference transcript is required for voice cloning" }
        val samples = WavPcm.fromUri(context, uri, sampleRate)
        val maxSamples = sampleRate * 15
        val trimmed = if (samples.size > maxSamples) samples.copyOf(maxSamples) else samples
        require(trimmed.size >= sampleRate * 2) { "Reference voice should be at least 2 seconds" }
        return VoiceReference(trimmed, transcript.trim())
    }

    /**
     * Synthesize one semantic chunk. The caller is responsible for long-book chunking and joins.
     */
    @Synchronized
    fun synthesize(
        reference: VoiceReference,
        text: String,
        speed: Float = 1.0f,
        nfeSteps: Int = 32,
        cancelled: AtomicBoolean? = null,
        onStep: ((Int, Int) -> Unit)? = null,
    ): ShortArray {
        load()
        require(text.isNotBlank()) { "Narration text is empty" }
        require(reference.samples.isNotEmpty()) { "Reference voice is empty" }

        val preSession = requireNotNull(preprocess)
        val transSession = requireNotNull(transformer)
        val decSession = requireNotNull(decoder)

        val refText = normalizeReferenceText(reference.transcript)
        val cleanText = ArabicText.prepareForNarration(text)
        val ids = encode(refText + cleanText)
        val maxDuration = computeMaxDuration(reference.samples.size, refText, cleanText, speed, 12)

        OnnxTensor.createTensor(
            env,
            ShortBuffer.wrap(reference.samples),
            longArrayOf(1, 1, reference.samples.size.toLong())
        ).use { audioTensor ->
            OnnxTensor.createTensor(
                env,
                IntBuffer.wrap(ids),
                longArrayOf(1, ids.size.toLong())
            ).use { textTensor ->
                OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(maxDuration)), longArrayOf(1)).use { durationTensor ->
                    val preResult = preSession.run(
                        mapOf(
                            "audio" to audioTensor,
                            "text_ids" to textTensor,
                            "max_duration" to durationTensor,
                        )
                    )
                    preResult.use { pre ->
                        val ropeCosQ = tensor(pre, "rope_cos_q")
                        val ropeSinQ = tensor(pre, "rope_sin_q")
                        val ropeCosK = tensor(pre, "rope_cos_k")
                        val ropeSinK = tensor(pre, "rope_sin_k")
                        val catMelText = tensor(pre, "cat_mel_text")
                        val catMelTextDrop = tensor(pre, "cat_mel_text_drop")
                        val refSignalLen = tensor(pre, "ref_signal_len")

                        var currentNoise = tensor(pre, "noise")
                        var currentTime: OnnxTensor = OnnxTensor.createTensor(
                            env,
                            IntBuffer.wrap(intArrayOf(0)),
                            longArrayOf(1)
                        )
                        var ownsInitialTime = true
                        var previousTransformerResult: OrtSession.Result? = null

                        try {
                            val steps = nfeSteps.coerceIn(8, 32)
                            for (i in 0 until steps - 1) {
                                if (cancelled?.get() == true) throw GenerationCancelled()

                                val next = transSession.run(
                                    mapOf(
                                        "noise" to currentNoise,
                                        "rope_cos_q" to ropeCosQ,
                                        "rope_sin_q" to ropeSinQ,
                                        "rope_cos_k" to ropeCosK,
                                        "rope_sin_k" to ropeSinK,
                                        "cat_mel_text" to catMelText,
                                        "cat_mel_text_drop" to catMelTextDrop,
                                        "time_step.1" to currentTime,
                                    )
                                )

                                if (ownsInitialTime) {
                                    currentTime.close()
                                    ownsInitialTime = false
                                }
                                previousTransformerResult?.close()
                                previousTransformerResult = next
                                currentNoise = tensor(next, "denoised")
                                currentTime = tensor(next, "time_step")
                                onStep?.invoke(i + 1, steps - 1)
                            }

                            if (cancelled?.get() == true) throw GenerationCancelled()
                            val decoded = decSession.run(
                                mapOf(
                                    "denoised" to currentNoise,
                                    "ref_signal_len" to refSignalLen,
                                )
                            )
                            decoded.use { out ->
                                return extractPcm16(tensor(out, "output_audio").value)
                            }
                        } finally {
                            if (ownsInitialTime) runCatching { currentTime.close() }
                            previousTransformerResult?.close()
                        }
                    }
                }
            }
        }
    }

    private fun encode(text: String): IntArray {
        val out = IntArray(text.length)
        for (i in text.indices) out[i] = vocab[text[i].toString()] ?: 0
        return out
    }

    private fun normalizeReferenceText(text: String): String {
        val trimmed = text.trim()
        return if (trimmed.endsWith(" ")) trimmed else "$trimmed "
    }

    private fun computeMaxDuration(
        referenceSampleCount: Int,
        referenceText: String,
        generationText: String,
        speed: Float,
        tailPaddingFrames: Int,
    ): Long {
        val refTextBytes = max(1, referenceText.toByteArray(Charsets.UTF_8).size)
        val genBytes = generationText.toByteArray(Charsets.UTF_8).size
        val refFrames = referenceSampleCount / 256 + 1
        val rate = if (speed <= 0f) 1.0 else speed.toDouble()
        return refFrames.toLong() +
            ((refFrames.toDouble() / refTextBytes.toDouble()) * genBytes.toDouble() / rate).toLong() +
            max(0, tailPaddingFrames)
    }

    private fun tensor(result: OrtSession.Result, name: String): OnnxTensor {
        val value: OnnxValue = result.get(name).orElseThrow {
            IllegalStateException("SILMA ONNX output '$name' is missing")
        }
        return value as? OnnxTensor
            ?: error("SILMA ONNX output '$name' is not a tensor")
    }

    private fun extractPcm16(value: Any): ShortArray {
        val shorts = ArrayList<Short>()
        val floats = ArrayList<Float>()
        fun walk(v: Any?) {
            when (v) {
                null -> Unit
                is Short -> shorts += v
                is ShortArray -> for (x in v) shorts += x
                is Float -> floats += v
                is FloatArray -> for (x in v) floats += x
                is Array<*> -> v.forEach(::walk)
                else -> error("Unsupported SILMA audio output type: ${v.javaClass.name}")
            }
        }
        walk(value)
        if (shorts.isNotEmpty()) return ShortArray(shorts.size) { shorts[it] }
        if (floats.isNotEmpty()) {
            return ShortArray(floats.size) { i ->
                (floats[i].coerceIn(-1f, 1f) * 32767f).toInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
        }
        error("SILMA decoder returned empty audio")
    }

    private fun extractAsset(assetPath: String, progress: ((String) -> Unit)?): File {
        val name = assetPath.substringAfterLast('/')
        val outDir = File(context.filesDir, "silma-f5-v3")
        val out = File(outDir, name)
        outDir.mkdirs()

        val expected = runCatching { context.assets.openFd(assetPath).use { it.length } }.getOrNull()
        if (out.exists() && out.length() > 1024L && (expected == null || out.length() == expected)) return out

        progress?.invoke("Installing local model: $name…")
        val temp = File(outDir, "$name.tmp")
        if (temp.exists()) temp.delete()
        context.assets.open(assetPath).use { input ->
            FileOutputStream(temp).use { output ->
                val buffer = ByteArray(4 * 1024 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n <= 0) break
                    output.write(buffer, 0, n)
                }
                output.fd.sync()
            }
        }
        require(temp.length() > 1024L) { "Failed to extract $name" }
        if (expected != null) require(temp.length() == expected) { "Incomplete model extraction for $name" }
        if (out.exists()) out.delete()
        require(temp.renameTo(out)) { "Unable to install local model $name" }
        return out
    }

    override fun close() {
        runCatching { preprocess?.close() }
        runCatching { transformer?.close() }
        runCatching { decoder?.close() }
        preprocess = null
        transformer = null
        decoder = null
    }

    data class VoiceReference(val samples: ShortArray, val transcript: String)
    class GenerationCancelled : RuntimeException()

    companion object {
        const val DEFAULT_REFERENCE_ASSET = "silma-f5/default_ref.wav"
        const val DEFAULT_REFERENCE_TEXT =
            "ويدقق النظر في القرآن الكريم وسائر الكتب السماوية ويتبع مسالك الرسل العظام عليهم الصلاة والسلام."
    }
}

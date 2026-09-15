package com.latif.audiobook.offline

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxValue
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.providers.NNAPIFlags
import android.content.Context
import android.content.res.AssetManager
import android.os.Build
import android.util.Log
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.nio.IntBuffer
import java.nio.LongBuffer
import java.nio.ShortBuffer
import java.security.MessageDigest
import java.util.EnumSet
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/** Fully local SILMA TTS v1 / F5-TTS ONNX runtime. */
class SilmaF5Engine(private val context: Context) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private var preprocess: OrtSession? = null
    private var transformer: OrtSession? = null
    private var decoder: OrtSession? = null
    private var sessionOptions: OrtSession.SessionOptions? = null
    private var vocab: Map<String, Int> = emptyMap()

    val sampleRate: Int = 24_000
    val workerThreads: Int = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
    val deviceName: String = buildString {
        append(Build.MANUFACTURER)
        append(' ')
        append(Build.MODEL)
        if (Build.VERSION.SDK_INT >= 31) {
            val soc = Build.SOC_MODEL
            if (soc.isNotBlank()) append(" · ").append(soc)
        } else if (Build.HARDWARE.isNotBlank()) {
            append(" · ").append(Build.HARDWARE)
        }
    }

    @Volatile
    var backendName: String = "not loaded"
        private set

    private enum class Backend {
        XNNPACK,
        NNAPI_FP16,
        NNAPI,
        CPU,
    }

    private data class AssetSpec(
        val path: String,
        val minimumBytes: Long,
        val exactBytes: Long,
        val sha256: String? = null,
        val extractToPrivateStorage: Boolean = false,
    )

    @Synchronized
    fun load(progress: ((String) -> Unit)? = null) {
        if (preprocess != null && transformer != null && decoder != null) {
            progress?.invoke("SILMA already ready · $backendName")
            return
        }

        val extracted = mutableMapOf<String, File>()
        try {
            progress?.invoke("Checking embedded SILMA assets…")
            MODEL_ASSETS.forEach { spec ->
                validatePackagedAsset(spec)
                if (spec.extractToPrivateStorage) {
                    extracted[spec.path] = extractAndValidateAsset(spec, progress)
                }
            }

            val preFile = requireNotNull(extracted[PREPROCESS_ASSET]) {
                "Validated preprocess model was not extracted"
            }
            val transformerFile = requireNotNull(extracted[TRANSFORMER_ASSET]) {
                "Validated transformer model was not extracted"
            }
            val decodeFile = requireNotNull(extracted[DECODER_ASSET]) {
                "Validated decoder model was not extracted"
            }

            vocab = loadVocabulary()
            progress?.invoke("SILMA assets verified. Initializing ONNX Runtime…")

            val backendFailures = mutableListOf<SilmaModelException>()
            for (backend in backendCandidates()) {
                closeSessionsOnly()
                val label = backendLabel(backend)
                progress?.invoke("Trying $label…")
                Log.i(TAG, "Attempting ONNX backend: $label")

                val options = createOptions(backend)
                var newPreprocess: OrtSession? = null
                var newTransformer: OrtSession? = null
                var newDecoder: OrtSession? = null
                try {
                    newPreprocess = env.createSession(preFile.absolutePath, options)
                    newTransformer = env.createSession(transformerFile.absolutePath, options)
                    newDecoder = env.createSession(decodeFile.absolutePath, options)

                    // Publish a backend only after the complete three-session pipeline succeeds.
                    preprocess = newPreprocess
                    transformer = newTransformer
                    decoder = newDecoder
                    sessionOptions = options
                    newPreprocess = null
                    newTransformer = null
                    newDecoder = null
                    backendName = label

                    Log.i(
                        TAG,
                        "SILMA initialized successfully: backend=$backendName threads=$workerThreads " +
                            "pre=${preFile.length()} transformer=${transformerFile.length()} decoder=${decodeFile.length()}",
                    )
                    progress?.invoke("SILMA ready · $backendName · $workerThreads threads")
                    return
                } catch (t: Throwable) {
                    runCatching { newPreprocess?.close() }
                    runCatching { newTransformer?.close() }
                    runCatching { newDecoder?.close() }
                    runCatching { options.close() }
                    val failure = SilmaModelException.OnnxInitializationFailed(label, t)
                    backendFailures += failure
                    Log.e(TAG, "ONNX backend failed: $label", t)
                    progress?.invoke("$label unavailable; trying fallback…")
                }
            }

            backendName = "failed"
            throw SilmaModelException.AllBackendsFailed(backendFailures)
        } catch (e: SilmaModelException) {
            closeSessionsOnly()
            Log.e(TAG, "SILMA model initialization failed", e)
            progress?.invoke("SILMA initialization failed: ${e.message}")
            throw e
        } catch (t: Throwable) {
            closeSessionsOnly()
            Log.e(TAG, "Unexpected SILMA initialization failure", t)
            throw SilmaModelException.OnnxInitializationFailed("unknown", t)
        }
    }

    private fun backendCandidates(): List<Backend> = buildList {
        add(Backend.XNNPACK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            add(Backend.NNAPI_FP16)
            add(Backend.NNAPI)
        }
        add(Backend.CPU)
    }

    private fun createOptions(backend: Backend): OrtSession.SessionOptions {
        val options = OrtSession.SessionOptions()
        options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
        options.setInterOpNumThreads(1)
        options.setMemoryPatternOptimization(true)
        options.setCPUArenaAllocator(true)

        when (backend) {
            Backend.XNNPACK -> {
                options.setIntraOpNumThreads(1)
                options.addConfigEntry("session.intra_op.allow_spinning", "0")
                options.addXnnpack(mapOf("intra_op_num_threads" to workerThreads.toString()))
            }
            Backend.NNAPI_FP16 -> {
                options.setIntraOpNumThreads(workerThreads.coerceAtMost(6))
                options.addConfigEntry("session.intra_op.allow_spinning", "0")
                options.addNnapi(EnumSet.of(NNAPIFlags.USE_FP16))
            }
            Backend.NNAPI -> {
                options.setIntraOpNumThreads(workerThreads.coerceAtMost(6))
                options.addConfigEntry("session.intra_op.allow_spinning", "0")
                options.addNnapi()
            }
            Backend.CPU -> {
                options.setIntraOpNumThreads(workerThreads.coerceAtMost(6))
                options.addConfigEntry("session.intra_op.allow_spinning", "1")
                options.addConfigEntry("session.intra_op.spin_duration_us", "1000")
                options.addConfigEntry("session.intra_op.spin_backoff_max", "8")
            }
        }
        return options
    }

    private fun backendLabel(backend: Backend): String = when (backend) {
        Backend.XNNPACK -> "XNNPACK ARM"
        Backend.NNAPI_FP16 -> "Android NNAPI FP16"
        Backend.NNAPI -> "Android NNAPI"
        Backend.CPU -> "ORT tuned CPU"
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

    @Synchronized
    fun synthesize(
        reference: VoiceReference,
        text: String,
        speed: Float = 1.0f,
        nfeSteps: Int = 12,
        cancelled: AtomicBoolean? = null,
        onStep: ((Int, Int) -> Unit)? = null,
    ): ShortArray {
        load()
        require(text.isNotBlank()) { "Narration text is empty" }
        require(reference.samples.isNotEmpty()) { "Reference voice is empty" }

        val preSession = requireNotNull(preprocess)
        val transSession = requireNotNull(transformer)
        val decSession = requireNotNull(decoder)
        val steps = nfeSteps.coerceIn(MIN_STEPS, MAX_STEPS)

        val refText = normalizeReferenceText(reference.transcript)
        val cleanText = ArabicText.prepareForNarration(text)
        val ids = encode(refText + cleanText)
        val maxDuration = computeMaxDuration(reference.samples.size, refText, cleanText, speed, 12)

        OnnxTensor.createTensor(
            env,
            ShortBuffer.wrap(reference.samples),
            longArrayOf(1, 1, reference.samples.size.toLong()),
        ).use { audioTensor ->
            OnnxTensor.createTensor(
                env,
                IntBuffer.wrap(ids),
                longArrayOf(1, ids.size.toLong()),
            ).use { textTensor ->
                OnnxTensor.createTensor(
                    env,
                    LongBuffer.wrap(longArrayOf(maxDuration)),
                    longArrayOf(1),
                ).use { durationTensor ->
                    preSession.run(
                        mapOf(
                            "audio" to audioTensor,
                            "text_ids" to textTensor,
                            "max_duration" to durationTensor,
                        ),
                    ).use { pre ->
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
                            longArrayOf(1),
                        )
                        var ownsInitialTime = true
                        var previousTransformerResult: OrtSession.Result? = null

                        try {
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
                                    ),
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
                            decSession.run(
                                mapOf(
                                    "denoised" to currentNoise,
                                    "ref_signal_len" to refSignalLen,
                                ),
                            ).use { out ->
                                return extractPcm16(tensor(out, "output_audio"))
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

    private fun extractPcm16(tensor: OnnxTensor): ShortArray {
        tensor.getFloatBuffer()?.let { buffer ->
            val out = ShortArray(buffer.remaining())
            var i = 0
            while (buffer.hasRemaining()) {
                out[i++] = (buffer.get().coerceIn(-1f, 1f) * 32767f)
                    .toInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    .toShort()
            }
            return out
        }
        tensor.getShortBuffer()?.let { buffer ->
            val out = ShortArray(buffer.remaining())
            buffer.get(out)
            return out
        }
        return extractPcm16Fallback(tensor.value)
    }

    private fun extractPcm16Fallback(value: Any): ShortArray {
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
                (floats[i].coerceIn(-1f, 1f) * 32767f)
                    .toInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    .toShort()
            }
        }
        error("SILMA decoder returned empty audio")
    }

    private fun validatePackagedAsset(spec: AssetSpec) {
        val actual = try {
            context.assets.openFd(spec.path).use { it.length }
        } catch (e: FileNotFoundException) {
            throw SilmaModelException.AssetMissing(spec.path, e)
        } catch (t: Throwable) {
            throw SilmaModelException.AssetExtractionFailed(
                spec.path,
                File(context.filesDir, spec.path.substringAfterLast('/')),
                IOException("Unable to inspect packaged asset. Verify androidResources.noCompress.", t),
            )
        }
        if (actual < spec.minimumBytes) {
            throw SilmaModelException.AssetTooSmall(spec.path, actual, spec.minimumBytes)
        }
        if (actual != spec.exactBytes) {
            throw SilmaModelException.AssetSizeMismatch(spec.path, actual, spec.exactBytes)
        }
        Log.i(TAG, "Validated packaged asset ${spec.path}: bytes=$actual")
    }

    private fun extractAndValidateAsset(
        spec: AssetSpec,
        progress: ((String) -> Unit)?,
    ): File {
        val name = spec.path.substringAfterLast('/')
        val outDir = File(context.filesDir, "silma-f5-v3")
        if (!outDir.exists() && !outDir.mkdirs()) {
            throw SilmaModelException.AssetExtractionFailed(
                spec.path,
                outDir,
                IOException("Unable to create ${outDir.absolutePath}"),
            )
        }

        val out = File(outDir, name)
        val packagedLength = spec.exactBytes
        if (out.exists()) {
            try {
                validateExtractedFile(out, spec, packagedLength)
                progress?.invoke("Validated cached asset: $name")
                return out
            } catch (t: Throwable) {
                Log.w(TAG, "Cached SILMA asset invalid; re-extracting ${spec.path}", t)
                if (!out.delete()) {
                    Log.w(TAG, "Unable to delete invalid cache ${out.absolutePath}")
                }
            }
        }

        val temp = File(outDir, "$name.tmp")
        if (temp.exists()) temp.delete()
        progress?.invoke("Installing local model: $name…")
        Log.i(TAG, "Extracting ${spec.path} -> ${temp.absolutePath}")

        try {
            context.assets.open(spec.path, AssetManager.ACCESS_STREAMING).use { input ->
                FileOutputStream(temp).use { output ->
                    val buffer = ByteArray(4 * 1024 * 1024)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        output.write(buffer, 0, read)
                        total += read
                    }
                    output.flush()
                    output.fd.sync()
                    Log.i(TAG, "Extracted ${spec.path}: bytes=$total")
                }
            }
            validateExtractedFile(temp, spec, packagedLength)
            if (out.exists() && !out.delete()) {
                throw IOException("Unable to replace ${out.absolutePath}")
            }
            if (!temp.renameTo(out)) {
                throw IOException("Unable to atomically commit ${out.absolutePath}")
            }
            Log.i(TAG, "Installed validated asset ${spec.path}: bytes=${out.length()}")
            return out
        } catch (e: SilmaModelException) {
            temp.delete()
            throw e
        } catch (e: FileNotFoundException) {
            temp.delete()
            throw SilmaModelException.AssetMissing(spec.path, e)
        } catch (t: Throwable) {
            temp.delete()
            throw SilmaModelException.AssetExtractionFailed(spec.path, out, t)
        }
    }

    private fun validateExtractedFile(
        file: File,
        spec: AssetSpec,
        packagedLength: Long,
    ) {
        if (!file.isFile) {
            throw SilmaModelException.AssetExtractionFailed(
                spec.path,
                file,
                IOException("Path is not a regular file"),
            )
        }
        val actual = file.length()
        if (actual < spec.minimumBytes) {
            throw SilmaModelException.AssetTooSmall(spec.path, actual, spec.minimumBytes)
        }
        if (actual != spec.exactBytes || actual != packagedLength) {
            throw SilmaModelException.AssetSizeMismatch(spec.path, actual, spec.exactBytes)
        }
        if (VERIFY_ASSET_HASHES && spec.sha256 != null) {
            val actualHash = sha256(file)
            if (!actualHash.equals(spec.sha256, ignoreCase = true)) {
                throw SilmaModelException.AssetHashMismatch(spec.path, actualHash, spec.sha256)
            }
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun loadVocabulary(): Map<String, Int> {
        return try {
            context.assets.open(VOCAB_ASSET)
                .bufferedReader(Charsets.UTF_8)
                .useLines { lines ->
                    val map = LinkedHashMap<String, Int>()
                    lines.forEachIndexed { index, raw ->
                        val token = raw.removeSuffix("\r")
                        if (token.isNotEmpty()) map[token] = index
                    }
                    map
                }
        } catch (e: FileNotFoundException) {
            throw SilmaModelException.AssetMissing(VOCAB_ASSET, e)
        } catch (t: Throwable) {
            throw SilmaModelException.AssetExtractionFailed(
                VOCAB_ASSET,
                File(context.filesDir, "silma-f5-v3/vocab.txt"),
                t,
            )
        }.also { loaded ->
            require(loaded.isNotEmpty()) { "SILMA vocabulary is empty" }
            Log.i(TAG, "Loaded SILMA vocabulary entries=${loaded.size}")
        }
    }

    private fun closeSessionsOnly() {
        runCatching { preprocess?.close() }.onFailure { Log.w(TAG, "Failed to close preprocess", it) }
        runCatching { transformer?.close() }.onFailure { Log.w(TAG, "Failed to close transformer", it) }
        runCatching { decoder?.close() }.onFailure { Log.w(TAG, "Failed to close decoder", it) }
        preprocess = null
        transformer = null
        decoder = null
        runCatching { sessionOptions?.close() }.onFailure { Log.w(TAG, "Failed to close session options", it) }
        sessionOptions = null
    }

    override fun close() {
        closeSessionsOnly()
        backendName = "closed"
    }

    data class VoiceReference(val samples: ShortArray, val transcript: String)
    class GenerationCancelled : RuntimeException()

    companion object {
        private const val TAG = "SilmaF5Engine"
        private const val VERIFY_ASSET_HASHES = false

        const val MIN_STEPS = 8
        const val MAX_STEPS = 32
        const val DEFAULT_REFERENCE_ASSET = "silma-f5/default_ref.wav"
        const val DEFAULT_REFERENCE_TEXT =
            "ويدقق النظر في القرآن الكريم وسائر الكتب السماوية ويتبع مسالك الرسل العظام عليهم الصلاة والسلام."

        private const val PREPROCESS_ASSET = "silma-f5/F5_Preprocess.onnx"
        private const val TRANSFORMER_ASSET = "silma-f5/model.onnx"
        private const val DECODER_ASSET = "silma-f5/F5_Decode.onnx"
        private const val VOCAB_ASSET = "silma-f5/vocab.txt"

        private val MODEL_ASSETS = listOf(
            AssetSpec(PREPROCESS_ASSET, 1_000_000L, 73_904_440L, extractToPrivateStorage = true),
            AssetSpec(TRANSFORMER_ASSET, 100_000_000L, 612_437_669L, extractToPrivateStorage = true),
            AssetSpec(DECODER_ASSET, 1_000_000L, 62_546_929L, extractToPrivateStorage = true),
            AssetSpec("silma-f5/config.json", 1_000L, 156_367L),
            AssetSpec(DEFAULT_REFERENCE_ASSET, 10_000L, 372_680L),
            AssetSpec(VOCAB_ASSET, 1_000L, 36_357L),
        )
    }
}

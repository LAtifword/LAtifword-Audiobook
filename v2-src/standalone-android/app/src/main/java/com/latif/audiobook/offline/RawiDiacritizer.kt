package com.latif.audiobook.offline

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.Normalizer

/**
 * Fully-local Arabic diacritization front-end for Nabra.
 *
 * Nabra's naturalness depends heavily on correct tashkeel because its Arabic
 * phonemizer must infer short vowels from the input. Rawi Ensemble restores
 * those marks before text reaches the TTS model. The model and vocab are
 * bundled in assets/rawi-diacritizer and never leave the device.
 */
class RawiDiacritizer(private val context: Context) : AutoCloseable {
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var session: OrtSession? = null
    private var charToIdx: Map<String, Long> = emptyMap()
    private var idxToDiac: Map<Long, String> = emptyMap()
    private var unknownId: Long = 0L

    @Synchronized
    private fun ensureLoaded() {
        if (session != null) return

        val model = copyAssetIfMissing(
            "rawi-diacritizer/rawi_ensemble.int8.onnx",
            File(context.filesDir, "rawi-diacritizer/rawi_ensemble.int8.onnx")
        )
        val vocabJson = context.assets.open("rawi-diacritizer/vocab.json")
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
        val root = JSONObject(vocabJson)
        val c2i = root.getJSONObject("char_to_idx")
        val d2i = root.getJSONObject("diac_to_idx")

        val chars = LinkedHashMap<String, Long>(c2i.length())
        val charKeys = c2i.keys()
        while (charKeys.hasNext()) {
            val key = charKeys.next()
            chars[key] = c2i.getLong(key)
        }
        charToIdx = chars
        unknownId = chars["<UNK>"] ?: chars["<unk>"] ?: chars["UNK"] ?: 0L

        val diacs = LinkedHashMap<Long, String>(d2i.length())
        val diacKeys = d2i.keys()
        while (diacKeys.hasNext()) {
            val mark = diacKeys.next()
            diacs[d2i.getLong(mark)] = mark
        }
        idxToDiac = diacs

        val options = OrtSession.SessionOptions()
        session = env.createSession(model.absolutePath, options)
    }

    @Synchronized
    fun diacritize(text: String): String {
        if (text.isBlank() || !ArabicText.containsArabic(text)) return text
        ensureLoaded()

        val stripped = stripToModelCharacters(text)
        if (stripped.isEmpty()) return text

        val ids = LongArray(stripped.size) { i -> charToIdx[stripped[i]] ?: unknownId }
        val input = arrayOf(ids)
        OnnxTensor.createTensor(env, input).use { tensor ->
            val result = requireNotNull(session).run(mapOf("input" to tensor))
            result.use {
                val classes = extractLongVector(it[0].value)
                val out = StringBuilder(text.length + text.length / 3)
                for (i in stripped.indices) {
                    val base = stripped[i]
                    out.append(base)
                    val cls = classes.getOrNull(i) ?: 0L
                    if (cls != 0L) {
                        val mark = idxToDiac[cls]
                        if (!mark.isNullOrEmpty() && mark != "<PAD>" && mark != "<pad>") {
                            out.append(mark)
                        }
                    }
                }
                return Normalizer.normalize(out.toString(), Normalizer.Form.NFC)
            }
        }
    }

    /**
     * Use existing user-provided tashkeel when the text is already heavily
     * diacritized; otherwise run Rawi. This avoids needlessly rewriting fully
     * vocalized Arabic editions.
     */
    fun prepare(text: String): String {
        val cleaned = ArabicText.prepareForNarration(text)
        return if (ArabicText.isAlreadyDiacritized(cleaned)) cleaned else diacritize(cleaned)
    }

    private fun stripToModelCharacters(input: String): List<String> {
        val nfd = Normalizer.normalize(input, Normalizer.Form.NFD)
        val out = ArrayList<String>(nfd.length)
        var i = 0
        while (i < nfd.length) {
            val cp = nfd.codePointAt(i)
            i += Character.charCount(cp)
            when (Character.getType(cp)) {
                Character.NON_SPACING_MARK.toInt(),
                Character.COMBINING_SPACING_MARK.toInt(),
                Character.ENCLOSING_MARK.toInt(),
                Character.OTHER_SYMBOL.toInt() -> continue
            }
            val s = String(Character.toChars(cp))
            out += if (s == "\n" || s == "\r" || s == "\t") " " else s
        }
        return out
    }

    private fun extractLongVector(value: Any): LongArray {
        return when (value) {
            is LongArray -> value
            is Array<*> -> {
                val first = value.firstOrNull()
                when (first) {
                    is LongArray -> first
                    is Array<*> -> LongArray(first.size) { idx -> (first[idx] as Number).toLong() }
                    else -> error("Unexpected Rawi output tensor shape")
                }
            }
            else -> error("Unexpected Rawi output tensor type: ${value.javaClass.name}")
        }
    }

    private fun copyAssetIfMissing(assetPath: String, destination: File): File {
        if (destination.exists() && destination.length() > 1_000_000L) return destination
        destination.parentFile?.mkdirs()
        context.assets.open(assetPath).use { input ->
            FileOutputStream(destination).use { output -> input.copyTo(output, 1024 * 256) }
        }
        return destination
    }

    override fun close() {
        runCatching { session?.close() }
        session = null
    }
}

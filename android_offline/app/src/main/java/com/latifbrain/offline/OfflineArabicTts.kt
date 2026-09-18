package com.latifbrain.offline

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig

class OfflineArabicTts(private val context: Context) : AutoCloseable {
    private var engine: OfflineTts? = null

    fun isModelBundled(): Boolean = try {
        context.assets.open("models/nabra/model.int8.onnx").close()
        context.assets.open("models/nabra/voices.bin").close()
        context.assets.open("models/nabra/tokens.txt").close()
        true
    } catch (_: Exception) {
        false
    }

    @Synchronized
    fun load() {
        if (engine != null) return
        check(isModelBundled()) { "Offline Arabic model missing from APK assets." }

        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                kokoro = OfflineTtsKokoroModelConfig(
                    model = "models/nabra/model.int8.onnx",
                    voices = "models/nabra/voices.bin",
                    tokens = "models/nabra/tokens.txt",
                    dataDir = "models/nabra/espeak-ng-data",
                    lang = "ar",
                    lengthScale = 1.0f,
                ),
                numThreads = threads,
                debug = false,
                provider = "cpu",
            ),
            maxNumSentences = 1,
            silenceScale = 0.12f,
        )
        engine = OfflineTts(context.assets, config)
    }

    fun synthesize(text: String, speed: Float): FloatArray {
        load()
        return requireNotNull(engine).generate(text, sid = 0, speed = speed).samples
    }

    fun sampleRate(): Int {
        load()
        return requireNotNull(engine).sampleRate()
    }

    override fun close() {
        engine?.release()
        engine = null
    }
}

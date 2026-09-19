package com.latifbrain.offline

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import java.io.File
import java.io.FileOutputStream

class OfflineArabicTts(private val context: Context) : AutoCloseable {
    private var engine: OfflineTts? = null

    fun isModelBundled(): Boolean = try {
        context.assets.open("models/nabra/model.int8.onnx").close()
        context.assets.open("models/nabra/voices.bin").close()
        context.assets.open("models/nabra/tokens.txt").close()
        val espeakRoot = context.assets.list("models/nabra/espeak-ng-data")
        !espeakRoot.isNullOrEmpty()
    } catch (_: Exception) {
        false
    }

    @Synchronized
    fun load() {
        if (engine != null) return
        check(isModelBundled()) { "Offline Arabic model missing or incomplete inside APK assets." }

        val dataDir = ensureEspeakDataDir()
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)

        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                kokoro = OfflineTtsKokoroModelConfig(
                    model = "models/nabra/model.int8.onnx",
                    voices = "models/nabra/voices.bin",
                    tokens = "models/nabra/tokens.txt",
                    dataDir = dataDir.absolutePath,
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

    private fun ensureEspeakDataDir(): File {
        val assetRoot = "models/nabra/espeak-ng-data"
        val modelRoot = File(context.filesDir, "runtime/nabra")
        val target = File(modelRoot, "espeak-ng-data")
        val marker = File(target, ".latif_complete")

        if (marker.exists() && target.isDirectory) {
            return target
        }

        if (target.exists()) {
            target.deleteRecursively()
        }
        target.mkdirs()

        copyAssetTree(assetRoot, target)

        check(target.listFiles()?.isNotEmpty() == true) {
            "Failed to prepare espeak-ng-data on local storage."
        }

        marker.writeText("ok")
        return target
    }

    private fun copyAssetTree(assetPath: String, destination: File) {
        val entries = context.assets.list(assetPath) ?: emptyArray()

        if (entries.isEmpty()) {
            destination.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                FileOutputStream(destination).use { output ->
                    input.copyTo(output, 64 * 1024)
                }
            }
            return
        }

        destination.mkdirs()
        for (name in entries) {
            copyAssetTree("$assetPath/$name", File(destination, name))
        }
    }

    fun synthesize(text: String, speed: Float): FloatArray {
        load()
        val audio = requireNotNull(engine).generate(text, sid = 0, speed = speed)
        check(audio.samples.isNotEmpty()) { "TTS engine returned no audio samples." }
        return audio.samples
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

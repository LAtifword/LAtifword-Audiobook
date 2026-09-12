package com.latif.audiobook.offline

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.os.PowerManager
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.getOfflineTtsConfig
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

class AudiobookService : Service() {
    private val cancelled = AtomicBoolean(false)
    @Volatile private var running = false
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            cancelled.set(true)
            return START_NOT_STICKY
        }
        if (running || intent == null) return START_NOT_STICKY

        val uri = intent.getStringExtra(EXTRA_URI)?.let(Uri::parse)
        val rawText = intent.getStringExtra(EXTRA_RAW_TEXT)
        if (uri == null && rawText.isNullOrBlank()) return START_NOT_STICKY

        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "LATIF Audiobook" }
        val displayName = intent.getStringExtra(EXTRA_DISPLAY_NAME)
        val profile = NarrationProfile.fromOrdinal(intent.getIntExtra(EXTRA_PROFILE, NarrationProfile.LITERARY.ordinal))
        val speed = intent.getFloatExtra(EXTRA_SPEED, profile.defaultSpeed).coerceIn(0.82f, 1.08f)

        running = true
        cancelled.set(false)
        startForeground(NOTIFICATION_ID, notification("Preparing high-quality Arabic narration…", 0, true))

        Thread { runJob(uri, rawText, title, displayName, profile, speed) }.start()
        return START_NOT_STICKY
    }

    private fun runJob(
        uri: Uri?,
        rawText: String?,
        title: String,
        displayName: String?,
        profile: NarrationProfile,
        speed: Float,
    ) {
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LatifAudiobook:render").apply {
            setReferenceCounted(false)
            acquire(12 * 60 * 60 * 1000L)
        }

        var tts: OfflineTts? = null
        var writer: M4aWriter? = null
        var diacritizer: RawiDiacritizer? = null
        try {
            sendProgress(1, "Reading book…")
            val text = rawText?.takeIf { it.isNotBlank() }
                ?: BookParser.readText(this, requireNotNull(uri), displayName)
            require(text.length >= 20) { "The selected book contains no readable text." }

            sendProgress(3, "Preparing literary Arabic…")
            // Nabra has a 510-phoneme style-row cap. Keep chunks sentence-aware and
            // moderately long for prosody, then split automatically if a hard case
            // still exceeds the model limit.
            val chunks = BookParser.splitForNarration(text, 320)
            require(chunks.isNotEmpty()) { "No narration sections were created." }

            val dataDir = ensureAssetTreeOnDisk("nabra-82m/espeak-ng-data")
            val config = getOfflineTtsConfig(
                modelDir = "nabra-82m",
                modelName = "model.fp16.onnx",
                acousticModelName = "",
                vocoder = "",
                voices = "voices.bin",
                lexicon = "",
                dataDir = dataDir.absolutePath,
                dictDir = "",
                ruleFsts = "",
                ruleFars = "",
                numThreads = 4,
            )

            // Load Nabra first. The previous build initialized the separate Java
            // ONNX Runtime bridge before sherpa-onnx and could fail at
            // ai.onnxruntime.OrtEnvironment before TTS ever started.
            sendProgress(5, "Loading Nabra voice…")
            tts = OfflineTts(assetManager = assets, config = config)
            val sampleRate = tts.sampleRate()
            writer = M4aWriter(this, title, sampleRate, bitrate = 96_000)

            // Rawi is a quality enhancement, not a hard dependency. Probe it once.
            // If the device/runtime rejects the Java ORT bridge, narration continues
            // with Nabra instead of aborting the entire audiobook.
            diacritizer = createRawiOrNull()

            chunks.forEachIndexed { index, chunk ->
                if (cancelled.get()) throw JobCancelledException()
                val p = 5 + (((index.toDouble() / chunks.size) * 92.0).toInt())
                sendProgress(p, "Narrating ${index + 1} / ${chunks.size} · ${profile.labelAr}")

                renderChunk(tts, diacritizer, writer, chunk, speed)

                val pauseMs = when {
                    chunk.endsWith("؟") || chunk.endsWith("!") -> profile.questionPauseMs
                    chunk.endsWith(".") || chunk.endsWith("…") || chunk.endsWith("؛") -> profile.sentencePauseMs
                    else -> profile.softPauseMs
                }
                writer.writeSilence(pauseMs)
            }

            sendProgress(98, "Mastering final audiobook…")
            val out = writer.finish()
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(KEY_LAST_OUTPUT, out.toString())
                .putString(KEY_LAST_TITLE, title)
                .putInt(KEY_LAST_PROFILE, profile.ordinal)
                .apply()
            sendComplete(out, title)
            updateNotification("Audiobook ready", 100, false)
        } catch (_: JobCancelledException) {
            writer?.abort()
            sendFailed("Cancelled")
            updateNotification("Generation cancelled", 0, false)
        } catch (t: Throwable) {
            writer?.abort()
            sendFailed(errorSummary(t))
            updateNotification("Generation failed", 0, false)
        } finally {
            runCatching { diacritizer?.close() }
            runCatching { tts?.release() }
            runCatching { wakeLock?.release() }
            running = false
            stopForeground(false)
            stopSelf()
        }
    }

    private fun createRawiOrNull(): RawiDiacritizer? {
        val candidate = runCatching { RawiDiacritizer(this) }.getOrNull() ?: return null
        return runCatching {
            // Force both the Java bridge and native session to initialize now.
            candidate.prepare("الصوت العربي الطبيعي يحتاج إلى نطق واضح.")
            candidate
        }.getOrElse {
            runCatching { candidate.close() }
            null
        }
    }

    private fun renderChunk(
        tts: OfflineTts,
        diacritizer: RawiDiacritizer?,
        writer: M4aWriter,
        text: String,
        speed: Float,
        depth: Int = 0,
    ) {
        if (cancelled.get()) throw JobCancelledException()
        val cleaned = ArabicText.prepareForNarration(text)
        if (cleaned.isBlank()) return

        try {
            // Nabra-82M has one actual speaker: af_msa / sid 0. Quality is
            // improved by context-aware tashkeel when Rawi is available, while
            // Nabra remains fully usable if Rawi cannot initialize on a device.
            val prepared = diacritizer?.let {
                runCatching { it.prepare(cleaned) }.getOrElse { cleaned }
            } ?: cleaned
            val audio = tts.generate(prepared, 0, speed)
            require(audio.samples.isNotEmpty()) { "Arabic voice engine returned empty audio." }
            writer.writeFloat(audio.samples)
        } catch (t: Throwable) {
            if (depth >= 3 || cleaned.length < 90) throw t
            val split = splitNearMiddle(cleaned)
            if (split.first.isBlank() || split.second.isBlank()) throw t
            renderChunk(tts, diacritizer, writer, split.first, speed, depth + 1)
            writer.writeSilence(35)
            renderChunk(tts, diacritizer, writer, split.second, speed, depth + 1)
        }
    }

    private fun splitNearMiddle(text: String): Pair<String, String> {
        val middle = text.length / 2
        val punctuation = listOf('،', '؛', '.', '؟', '!', '…')
        var best = -1
        var distance = Int.MAX_VALUE
        for (i in text.indices) {
            if (text[i].isWhitespace() || text[i] in punctuation) {
                val d = kotlin.math.abs(i - middle)
                if (d < distance) {
                    best = i
                    distance = d
                }
            }
        }
        if (best <= 20 || best >= text.length - 20) best = middle
        return text.substring(0, best).trim() to text.substring(best).trim()
    }

    private fun ensureAssetTreeOnDisk(assetPath: String): File {
        val outRoot = File(filesDir, "tts-runtime/$assetPath")
        val marker = File(outRoot, ".ready-v2")
        if (marker.exists()) return outRoot
        if (outRoot.exists()) outRoot.deleteRecursively()
        outRoot.mkdirs()
        copyAssetRecursive(assetPath, outRoot)
        marker.writeText("LATIF Audiobook AI v2")
        return outRoot
    }

    private fun copyAssetRecursive(assetPath: String, destination: File) {
        val children = assets.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            destination.parentFile?.mkdirs()
            assets.open(assetPath).use { input ->
                FileOutputStream(destination).use { output -> input.copyTo(output, 1024 * 128) }
            }
            return
        }
        destination.mkdirs()
        for (name in children) {
            copyAssetRecursive("$assetPath/$name", File(destination, name))
        }
    }

    private fun errorSummary(t: Throwable): String {
        var root = t
        while (root.cause != null && root.cause !== root) root = root.cause!!
        val rootMessage = root.message?.takeIf { it.isNotBlank() }
        return if (rootMessage != null) {
            "${root.javaClass.simpleName}: $rootMessage"
        } else {
            root.javaClass.name
        }
    }

    private fun sendProgress(percent: Int, message: String) {
        updateNotification(message, percent, true)
        sendBroadcast(Intent(ACTION_PROGRESS).setPackage(packageName).apply {
            putExtra(EXTRA_PROGRESS, percent)
            putExtra(EXTRA_MESSAGE, message)
        })
    }

    private fun sendComplete(uri: Uri, title: String) {
        sendBroadcast(Intent(ACTION_COMPLETE).setPackage(packageName).apply {
            putExtra(EXTRA_OUTPUT_URI, uri.toString())
            putExtra(EXTRA_TITLE, title)
        })
    }

    private fun sendFailed(message: String) {
        sendBroadcast(Intent(ACTION_FAILED).setPackage(packageName).apply {
            putExtra(EXTRA_MESSAGE, message)
        })
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Audiobook generation", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun notification(message: String, progress: Int, ongoing: Boolean): Notification {
        val pending = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("LATIF Audiobook AI · Nabra + Rawi")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pending)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setProgress(100, progress.coerceIn(0, 100), progress <= 0 && ongoing)
            .build()
    }

    private fun updateNotification(message: String, progress: Int, ongoing: Boolean) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification(message, progress, ongoing))
    }

    class JobCancelledException : RuntimeException()

    companion object {
        const val ACTION_PROGRESS = "com.latif.audiobook.offline.PROGRESS"
        const val ACTION_COMPLETE = "com.latif.audiobook.offline.COMPLETE"
        const val ACTION_FAILED = "com.latif.audiobook.offline.FAILED"
        const val ACTION_CANCEL = "com.latif.audiobook.offline.CANCEL"
        const val EXTRA_URI = "uri"
        const val EXTRA_RAW_TEXT = "rawText"
        const val EXTRA_TITLE = "title"
        const val EXTRA_DISPLAY_NAME = "displayName"
        const val EXTRA_SPEED = "speed"
        const val EXTRA_PROFILE = "profile"
        const val EXTRA_PROGRESS = "progress"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_OUTPUT_URI = "outputUri"
        const val PREFS = "latif_audiobook"
        const val KEY_LAST_OUTPUT = "lastOutput"
        const val KEY_LAST_TITLE = "lastTitle"
        const val KEY_LAST_PROFILE = "lastProfile"
        private const val CHANNEL_ID = "latif_audiobook_render"
        private const val NOTIFICATION_ID = 7002
    }
}

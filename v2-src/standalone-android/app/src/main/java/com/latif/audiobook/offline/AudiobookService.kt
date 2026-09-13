package com.latif.audiobook.offline

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.os.PowerManager
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/**
 * Foreground audiobook renderer for LATIF Voice Studio v3.
 * Primary engine: SILMA TTS v1 / F5-TTS ONNX, entirely on-device.
 */
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
        val refUri = intent.getStringExtra(EXTRA_REFERENCE_URI)?.takeIf { it.isNotBlank() }?.let(Uri::parse)
        val refText = intent.getStringExtra(EXTRA_REFERENCE_TEXT).orEmpty()

        running = true
        cancelled.set(false)
        startForeground(NOTIFICATION_ID, notification("Preparing SILMA local studio…", 0, true))
        Thread { runJob(uri, rawText, title, displayName, profile, speed, refUri, refText) }.start()
        return START_NOT_STICKY
    }

    private fun runJob(
        uri: Uri?,
        rawText: String?,
        title: String,
        displayName: String?,
        profile: NarrationProfile,
        speed: Float,
        referenceUri: Uri?,
        referenceText: String,
    ) {
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LatifVoiceStudio:render").apply {
            setReferenceCounted(false)
            acquire(12 * 60 * 60 * 1000L)
        }

        var engine: SilmaF5Engine? = null
        var writer: M4aWriter? = null
        try {
            sendProgress(1, "Reading manuscript…")
            val text = rawText?.takeIf { it.isNotBlank() }
                ?: BookParser.readText(this, requireNotNull(uri), displayName)
            require(text.length >= 20) { "The selected book contains no readable text." }

            sendProgress(2, "Building semantic narration sections…")
            val chunks = BookParser.splitForNarration(text, 180)
            require(chunks.isNotEmpty()) { "No narration sections were created." }

            engine = SilmaF5Engine(this)
            engine.load { message -> sendProgress(3, message) }

            val reference = if (referenceUri != null) {
                sendProgress(4, "Preparing your cloned narrator voice…")
                engine.referenceFromUri(referenceUri, referenceText)
            } else {
                sendProgress(4, "Loading built-in Arabic studio narrator…")
                engine.builtInReference()
            }

            writer = M4aWriter(this, title, engine.sampleRate, bitrate = 128_000)
            sendProgress(5, "SILMA F5 studio ready · ${chunks.size} sections")

            chunks.forEachIndexed { index, chunk ->
                if (cancelled.get()) throw JobCancelledException()
                val clean = ArabicText.prepareForNarration(chunk)
                if (clean.isBlank()) return@forEachIndexed

                val audio = engine.synthesize(
                    reference = reference,
                    text = clean,
                    speed = speed,
                    nfeSteps = 32,
                    cancelled = cancelled,
                ) { step, stepCount ->
                    val unit = (index.toDouble() + step.toDouble() / stepCount.coerceAtLeast(1)) / chunks.size.toDouble()
                    val percent = 5 + (unit * 92.0).roundToInt().coerceIn(0, 92)
                    sendProgress(percent, "SILMA narration ${index + 1}/${chunks.size} · quality pass $step/$stepCount")
                }

                writer.writePcm16(edgeFade(audio, engine.sampleRate, 7))
                val pauseMs = when {
                    clean.endsWith("؟") || clean.endsWith("!") -> profile.questionPauseMs.coerceIn(80, 220)
                    clean.endsWith(".") || clean.endsWith("…") || clean.endsWith("؛") -> profile.sentencePauseMs.coerceIn(65, 180)
                    else -> profile.softPauseMs.coerceIn(30, 100)
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
        } catch (_: SilmaF5Engine.GenerationCancelled) {
            writer?.abort()
            sendFailed("Cancelled")
            updateNotification("Generation cancelled", 0, false)
        } catch (_: JobCancelledException) {
            writer?.abort()
            sendFailed("Cancelled")
            updateNotification("Generation cancelled", 0, false)
        } catch (t: Throwable) {
            writer?.abort()
            sendFailed(errorSummary(t))
            updateNotification("Generation failed", 0, false)
        } finally {
            runCatching { engine?.close() }
            runCatching { wakeLock?.release() }
            running = false
            stopForeground(false)
            stopSelf()
        }
    }

    private fun edgeFade(samples: ShortArray, sampleRate: Int, milliseconds: Int): ShortArray {
        if (samples.isEmpty()) return samples
        val n = (sampleRate * milliseconds / 1000).coerceAtMost(samples.size / 3)
        if (n <= 1) return samples
        val out = samples.copyOf()
        for (i in 0 until n) {
            val gain = (i + 1).toDouble() / n.toDouble()
            out[i] = (out[i] * gain).toInt().toShort()
            val j = out.lastIndex - i
            out[j] = (out[j] * gain).toInt().toShort()
        }
        return out
    }

    private fun errorSummary(t: Throwable): String {
        var root = t
        while (root.cause != null && root.cause !== root) root = root.cause!!
        val message = root.message?.takeIf { it.isNotBlank() }
        return if (message != null) "${root.javaClass.simpleName}: $message" else root.javaClass.name
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
            .setContentTitle("LATIF Voice Studio · SILMA F5")
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
        const val EXTRA_REFERENCE_URI = "referenceUri"
        const val EXTRA_REFERENCE_TEXT = "referenceText"
        const val EXTRA_PROGRESS = "progress"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_OUTPUT_URI = "outputUri"
        const val PREFS = "latif_audiobook"
        const val KEY_LAST_OUTPUT = "lastOutput"
        const val KEY_LAST_TITLE = "lastTitle"
        const val KEY_LAST_PROFILE = "lastProfile"
        private const val CHANNEL_ID = "latif_audiobook_render"
        private const val NOTIFICATION_ID = 7003
    }
}

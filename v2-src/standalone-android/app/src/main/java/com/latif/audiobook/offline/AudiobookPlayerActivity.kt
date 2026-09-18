package com.latif.audiobook.offline

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import java.util.Locale

/** Audio-only Media3 player with exact section navigation from the generated sidecar. */
class AudiobookPlayerActivity : Activity() {
    private lateinit var player: ExoPlayer
    private lateinit var playPause: Button
    private lateinit var position: TextView
    private lateinit var progress: ProgressBar
    private lateinit var sectionContainer: LinearLayout
    private lateinit var status: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var chapters: List<AudiobookChapter> = emptyList()
    private var chapterButtons: List<Button> = emptyList()
    private var lastActiveIndex = -2

    private val backgroundColor = Color.rgb(8, 10, 16)
    private val panelColor = Color.rgb(18, 22, 34)
    private val ivory = Color.rgb(244, 239, 226)
    private val muted = Color.rgb(150, 154, 170)
    private val saffron = Color.rgb(232, 184, 106)

    private val ticker = object : Runnable {
        override fun run() {
            if (::player.isInitialized) updatePosition()
            handler.postDelayed(this, 250L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = backgroundColor
        window.navigationBarColor = backgroundColor
        setContentView(buildUi())

        val audioUri = intent.getStringExtra(EXTRA_AUDIO_URI)?.let(Uri::parse)
        if (audioUri == null) {
            status.text = "No audiobook URI was supplied."
            return
        }

        player = ExoPlayer.Builder(this).build().apply {
            addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    playPause.text = if (isPlaying) "إيقاف مؤقت" else "تشغيل"
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) {
                        status.text = "جاهز · Media3"
                        updatePosition()
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    status.text = "Playback failed: ${error.message ?: error.errorCodeName}"
                }
            })
            setMediaItem(MediaItem.fromUri(audioUri))
            prepare()
        }

        val sidecarUri = intent.getStringExtra(EXTRA_SIDECAR_URI)?.let(Uri::parse)
        if (sidecarUri != null) {
            Thread {
                runCatching { ChapterSidecarJson.read(this, sidecarUri) }
                    .onSuccess { sidecar ->
                        runOnUiThread {
                            chapters = sidecar.chapters
                            renderSections(sidecar)
                        }
                    }
                    .onFailure { error ->
                        runOnUiThread {
                            status.text = "Audio ready · navigation file unavailable: ${error.message}"
                        }
                    }
            }.start()
        } else {
            status.text = "Audio ready · no navigation sidecar"
        }
        handler.post(ticker)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (::player.isInitialized) player.release()
        super.onDestroy()
    }

    private fun buildUi(): View {
        val scroll = ScrollView(this).apply { setBackgroundColor(backgroundColor) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(20), dp(24), dp(20), dp(40))
        }
        scroll.addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        root.addView(TextView(this).apply {
            text = "LATIF AUDIOBOOK PLAYER"
            setTextColor(ivory)
            textSize = 25f
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "MEDIA3 · EXACT GENERATED SECTION TIMINGS"
            setTextColor(saffron)
            textSize = 11.5f
            setPadding(0, dp(4), 0, dp(16))
        })

        status = body("Preparing audiobook…")
        root.addView(status)
        position = body("00:00 / 00:00")
        root.addView(position)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 10_000
            progressTintList = android.content.res.ColorStateList.valueOf(saffron)
        }
        root.addView(progress, params(8))

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val back = button("−15s").apply {
            setOnClickListener { if (::player.isInitialized) player.seekTo((player.currentPosition - 15_000L).coerceAtLeast(0L)) }
        }
        playPause = button("تشغيل").apply {
            setOnClickListener {
                if (!::player.isInitialized) return@setOnClickListener
                if (player.isPlaying) player.pause() else player.play()
            }
        }
        val forward = button("+30s").apply {
            setOnClickListener {
                if (!::player.isInitialized) return@setOnClickListener
                val duration = player.duration.takeIf { it > 0L } ?: Long.MAX_VALUE
                player.seekTo((player.currentPosition + 30_000L).coerceAtMost(duration))
            }
        }
        controls.addView(back, LinearLayout.LayoutParams(0, dp(52), 1f))
        controls.addView(playPause, LinearLayout.LayoutParams(0, dp(52), 1.4f).apply { marginStart = dp(6); marginEnd = dp(6) })
        controls.addView(forward, LinearLayout.LayoutParams(0, dp(52), 1f))
        root.addView(controls, params(14))

        root.addView(TextView(this).apply {
            text = "التنقل"
            setTextColor(saffron)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(24), 0, dp(8))
        })
        sectionContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(sectionContainer)
        return scroll
    }

    private fun renderSections(sidecar: ChapterSidecar) {
        sectionContainer.removeAllViews()
        val buttons = ArrayList<Button>(sidecar.chapters.size)
        sidecar.chapters.forEachIndexed { index, chapter ->
            val button = button("${index + 1}. ${chapter.title}  ·  ${formatTime(chapter.startMs)}").apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setOnClickListener {
                    if (::player.isInitialized) {
                        player.seekTo(chapter.startMs)
                        player.play()
                    }
                }
            }
            sectionContainer.addView(button, params(if (index == 0) 0 else 6))
            buttons += button
        }
        chapterButtons = buttons
        status.text = "${sidecar.bookTitle} · ${sidecar.chapters.size} navigation sections"
        updatePosition()
    }

    private fun updatePosition() {
        val current = player.currentPosition.coerceAtLeast(0L)
        val duration = player.duration.takeIf { it > 0L } ?: 0L
        position.text = "${formatTime(current)} / ${formatTime(duration)}"
        progress.progress = if (duration > 0L) ((current.toDouble() / duration.toDouble()) * 10_000.0).toInt().coerceIn(0, 10_000) else 0

        var active = -1
        chapters.forEachIndexed { index, chapter ->
            if (current >= chapter.startMs) active = index
        }
        if (active != lastActiveIndex) {
            lastActiveIndex = active
            chapterButtons.forEachIndexed { index, button ->
                val selected = index == active
                button.setTextColor(if (selected) Color.rgb(22, 17, 10) else ivory)
                button.background = GradientDrawable().apply {
                    cornerRadius = dp(12).toFloat()
                    setColor(if (selected) saffron else panelColor)
                    if (!selected) setStroke(dp(1), Color.rgb(62, 67, 82))
                }
            }
        }
    }

    private fun formatTime(ms: Long): String {
        val total = (ms.coerceAtLeast(0L) / 1_000L)
        val hours = total / 3_600L
        val minutes = (total % 3_600L) / 60L
        val seconds = total % 60L
        return if (hours > 0) String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        else String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }

    private fun body(value: String) = TextView(this).apply {
        text = value
        setTextColor(muted)
        textSize = 13f
        setPadding(0, dp(5), 0, dp(3))
    }

    private fun button(label: String) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 12.5f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(ivory)
        background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(panelColor)
            setStroke(dp(1), Color.rgb(62, 67, 82))
        }
    }

    private fun params(top: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(top) }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_AUDIO_URI = "audioUri"
        const val EXTRA_SIDECAR_URI = "sidecarUri"
    }
}

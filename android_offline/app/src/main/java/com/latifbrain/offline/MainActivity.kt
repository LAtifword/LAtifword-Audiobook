package com.latifbrain.offline

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.media.MediaPlayer
import android.os.Bundle
import android.os.PowerManager
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*
import java.io.File
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var tts: OfflineArabicTts
    private lateinit var renderer: AudiobookRenderer
    private lateinit var textBox: EditText
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var speedBar: SeekBar
    private var outputFile: File? = null
    private var player: MediaPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = OfflineArabicTts(this)
        renderer = AudiobookRenderer(this, tts)
        setContentView(buildUi())
        textBox.setText("مرحباً بك في LATIF BRAIN. هذا التطبيق يولد الصوت محلياً على جهازك دون API أو خادم خارجي.")
        updateModelStatus()
    }

    private fun buildUi(): ScrollView {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.rgb(248, 248, 248))
        }

        root.addView(TextView(this).apply {
            text = "LATIF BRAIN — OFFLINE ARABIC STUDIO"
            textSize = 22f
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 12, 0, 16)
        })

        status = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.DKGRAY)
            setPadding(0, 0, 0, 12)
        }
        root.addView(status)

        textBox = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            gravity = Gravity.TOP or Gravity.START
            minLines = 12
            maxLines = 22
            textDirection = android.view.View.TEXT_DIRECTION_RTL
            setTextColor(Color.BLACK)
            setBackgroundColor(Color.WHITE)
            setPadding(pad, pad, pad, pad)
        }
        root.addView(
            textBox,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        val importButton = Button(this).apply {
            text = "Import TXT manuscript"
            setOnClickListener {
                startActivityForResult(
                    Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "text/plain"
                    },
                    REQUEST_TEXT
                )
            }
        }
        root.addView(importButton)

        val speedLabel = TextView(this).apply {
            text = "Speed: 1.00×"
            setTextColor(Color.BLACK)
            setPadding(0, 16, 0, 0)
        }
        root.addView(speedLabel)

        speedBar = SeekBar(this).apply {
            max = 50
            progress = 25
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    speedLabel.text = "Speed: %.2f×".format(0.75f + p / 100f)
                }
                override fun onStartTrackingTouch(s: SeekBar?) = Unit
                override fun onStopTrackingTouch(s: SeekBar?) = Unit
            })
        }
        root.addView(speedBar)

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
        }
        root.addView(
            progress,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        root.addView(Button(this).apply {
            text = "Generate audiobook offline"
            setOnClickListener {
                renderBook(0.75f + speedBar.progress / 100f)
            }
        })

        root.addView(Button(this).apply {
            text = "Stop"
            setOnClickListener {
                renderer.cancel()
                status.text = "Stopping after current segment…"
            }
        })

        root.addView(Button(this).apply {
            text = "Play / Pause"
            setOnClickListener { togglePlayback() }
        })

        return ScrollView(this).apply { addView(root) }
    }

    private fun updateModelStatus() {
        status.text = if (tts.isModelBundled()) {
            "✓ Offline Arabic neural model bundled. No Internet permission."
        } else {
            "Offline model is missing from this build."
        }
    }

    private fun renderBook(speed: Float) {
        if (!tts.isModelBundled()) {
            Toast.makeText(this, "Offline model is missing from APK assets.", Toast.LENGTH_LONG).show()
            return
        }
        val text = textBox.text.toString()
        if (text.isBlank()) return

        progress.progress = 0
        status.text = "Starting local inference…"

        executor.submit {
            val wake = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LatifBrain::Render")
            try {
                wake.acquire(30 * 60 * 1000L)
                outputFile = renderer.render(text, speed) { p ->
                    runOnUiThread {
                        progress.progress = if (p.total == 0) 0 else (100 * p.completed / p.total)
                        status.text = p.message
                    }
                }
                runOnUiThread {
                    progress.progress = 100
                    status.text = "Saved locally: ${outputFile?.absolutePath}"
                }
            } catch (_: InterruptedException) {
                runOnUiThread {
                    status.text = "Generation stopped. Existing segments remain available for resume."
                }
            } catch (e: Throwable) {
                runOnUiThread { status.text = "Error: ${e.message}" }
            } finally {
                if (wake.isHeld) wake.release()
            }
        }
    }

    private fun togglePlayback() {
        val f = outputFile
        if (f == null || !f.exists()) {
            Toast.makeText(this, "Generate an audiobook first.", Toast.LENGTH_SHORT).show()
            return
        }
        player?.let {
            if (it.isPlaying) it.pause() else it.start()
            return
        }
        player = MediaPlayer().apply {
            setDataSource(f.absolutePath)
            prepare()
            start()
            setOnCompletionListener {
                it.release()
                player = null
            }
        }
    }

    @Deprecated("Deprecated in Android API; retained for a dependency-free file picker.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_TEXT && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            try {
                contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8).use { reader ->
                    val value = reader?.readText().orEmpty()
                    if (value.isNotBlank()) textBox.setText(value)
                }
                status.text = "Manuscript loaded."
            } catch (e: Throwable) {
                status.text = "Could not read manuscript: ${e.message}"
            }
        }
    }

    override fun onDestroy() {
        renderer.cancel()
        executor.shutdownNow()
        player?.release()
        tts.close()
        super.onDestroy()
    }

    companion object {
        private const val REQUEST_TEXT = 1001
    }
}

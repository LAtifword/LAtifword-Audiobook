package com.latifbrain.offline

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.os.Bundle
import android.os.PowerManager
import android.text.Editable
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.sin

class MainActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var tts: OfflineArabicTts
    private lateinit var renderer: AudiobookRenderer

    private lateinit var textBox: EditText
    private lateinit var status: TextView
    private lateinit var manuscriptMeta: TextView
    private lateinit var renderMeta: TextView
    private lateinit var speedLabel: TextView
    private lateinit var speedBar: SeekBar
    private lateinit var progress: ProgressBar
    private lateinit var previewButton: Button
    private lateinit var previewPlayButton: Button
    private lateinit var renderButton: Button
    private lateinit var fullPlayButton: Button

    private var outputFile: File? = null
    private var previewFile: File? = null
    private var player: MediaPlayer? = null

    private val espresso = Color.rgb(31, 25, 21)
    private val cocoa = Color.rgb(62, 49, 40)
    private val copper = Color.rgb(191, 107, 69)
    private val copperSoft = Color.rgb(238, 220, 208)
    private val ivory = Color.rgb(247, 243, 236)
    private val paper = Color.rgb(255, 253, 249)
    private val sage = Color.rgb(224, 238, 229)
    private val sageText = Color.rgb(55, 102, 72)
    private val muted = Color.rgb(115, 105, 96)
    private val line = Color.rgb(226, 218, 207)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = espresso
        window.navigationBarColor = espresso

        tts = OfflineArabicTts(this)
        renderer = AudiobookRenderer(this, tts)
        setContentView(buildUi())

        textBox.setText(
            "مرحباً بك في LATIF Audio Studio. اكتب نصاً هنا أو استورد مخطوطاً، ثم أنشئ معاينة قصيرة قبل تحويل الكتاب كاملاً إلى صوت محلياً على جهازك."
        )
        refreshManuscriptStats()
        verifyRuntime()
    }

    private fun buildUi(): ScrollView {
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(ivory)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(40))
            setBackgroundColor(ivory)
        }

        root.addView(buildBrandBar())
        root.addView(space(22))
        root.addView(buildHero())
        root.addView(space(18))
        root.addView(buildModeStrip())
        root.addView(space(14))
        root.addView(buildSourceCard())
        root.addView(space(14))
        root.addView(buildNarratorCard())
        root.addView(space(14))
        root.addView(buildPreviewCard())
        root.addView(space(14))
        root.addView(buildRenderCard())
        root.addView(space(18))
        root.addView(buildPrivacyFooter())

        scroll.addView(root)
        return scroll
    }

    private fun buildBrandBar(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val mark = TextView(this).apply {
            text = "◉"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(copper)
            background = rounded(espresso, 16f)
        }
        row.addView(mark, LinearLayout.LayoutParams(dp(42), dp(42)))

        val brand = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        brand.addView(label("LATIF"))
        brand.addView(TextView(this).apply {
            text = "AUDIO STUDIO"
            textSize = 10f
            letterSpacing = 0.20f
            setTextColor(muted)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        row.addView(brand, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val privacy = TextView(this).apply {
            text = "●  ON DEVICE"
            textSize = 10f
            setTextColor(sageText)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = rounded(sage, 18f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        row.addView(privacy)

        return row
    }

    private fun buildHero(): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        box.addView(TextView(this).apply {
            text = "EXPRESSIVE NARRATION"
            textSize = 10f
            letterSpacing = 0.16f
            setTextColor(copper)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })

        val hero = SpannableString("Make the page sound alive.")
        val start = hero.toString().indexOf("sound alive.")
        hero.setSpan(ForegroundColorSpan(copper), start, hero.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        hero.setSpan(StyleSpan(Typeface.ITALIC), start, hero.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

        box.addView(TextView(this).apply {
            text = hero
            textSize = 34f
            setTextColor(espresso)
            typeface = Typeface.create("serif", Typeface.NORMAL)
            setLineSpacing(0f, 0.94f)
            setPadding(0, dp(6), 0, dp(8))
        })

        box.addView(TextView(this).apply {
            text = "Build the voice first. Hear a short scene. Render the book only when the narration feels right."
            textSize = 14f
            setTextColor(muted)
            setLineSpacing(dp(2).toFloat(), 1.08f)
        })

        return box
    }

    private fun buildModeStrip(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = cardBackground()
            elevation = dp(2).toFloat()
        }

        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        left.addView(TextView(this).apply {
            text = "PRODUCTION MODE"
            textSize = 9f
            letterSpacing = 0.14f
            setTextColor(copper)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        left.addView(TextView(this).apply {
            text = "Offline HQ"
            textSize = 16f
            setTextColor(espresso)
            typeface = Typeface.create("serif", Typeface.BOLD)
        })
        card.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        card.addView(TextView(this).apply {
            text = "NO API  •  PRIVATE"
            textSize = 10f
            setTextColor(cocoa)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = rounded(copperSoft, 16f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })

        return card
    }

    private fun buildSourceCard(): View {
        val card = sectionCard(
            "01 / SOURCE MATERIAL",
            "Bring your story in.",
            "Paste Arabic text or import a UTF-8 manuscript."
        )

        manuscriptMeta = TextView(this).apply {
            textSize = 12f
            setTextColor(sageText)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = rounded(sage, 14f)
        }
        card.addView(manuscriptMeta)

        card.addView(space(10))

        textBox = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            gravity = Gravity.TOP or Gravity.START
            minLines = 7
            maxLines = 14
            textDirection = View.TEXT_DIRECTION_RTL
            textSize = 18f
            setTextColor(espresso)
            hint = "اكتب النص هنا…"
            setHintTextColor(Color.rgb(165, 155, 145))
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = rounded(Color.WHITE, 16f, line, 1)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = refreshManuscriptStats()
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        card.addView(textBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        card.addView(space(10))
        card.addView(actionButton("Import TXT manuscript", false) {
            startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "text/plain"
                },
                REQUEST_TEXT
            )
        })

        return card
    }

    private fun buildNarratorCard(): View {
        val card = sectionCard(
            "02 / NARRATOR PROFILE",
            "Give it a human pulse.",
            "Arabic MSA voice, generated entirely on this device."
        )

        val voice = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = rounded(Color.WHITE, 16f, line, 1)
        }

        voice.addView(TextView(this).apply {
            text = "◌"
            textSize = 28f
            gravity = Gravity.CENTER
            setTextColor(copper)
            background = rounded(copperSoft, 24f)
        }, LinearLayout.LayoutParams(dp(48), dp(48)))

        val voiceText = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        voiceText.addView(TextView(this).apply {
            text = "Nabra · Arabic MSA"
            textSize = 17f
            setTextColor(espresso)
            typeface = Typeface.create("serif", Typeface.BOLD)
        })
        voiceText.addView(TextView(this).apply {
            text = "24 kHz neural voice  •  local inference"
            textSize = 12f
            setTextColor(muted)
        })
        voice.addView(voiceText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        card.addView(voice)
        card.addView(space(14))

        speedLabel = TextView(this).apply {
            text = "Narration pace  ·  1.00×"
            textSize = 13f
            setTextColor(cocoa)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        card.addView(speedLabel)

        speedBar = SeekBar(this).apply {
            max = 50
            progress = 25
            setPadding(0, dp(4), 0, 0)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    speedLabel.text = "Narration pace  ·  %.2f×".format(Locale.US, 0.75f + p / 100f)
                }
                override fun onStartTrackingTouch(s: SeekBar?) = Unit
                override fun onStopTrackingTouch(s: SeekBar?) = Unit
            })
        }
        card.addView(speedBar)

        status = TextView(this).apply {
            textSize = 12f
            setTextColor(sageText)
            setPadding(dp(12), dp(9), dp(12), dp(9))
            background = rounded(sage, 14f)
        }
        card.addView(status)

        return card
    }

    private fun buildPreviewCard(): View {
        val card = sectionCard(
            "03 / VOICE QUALITY GATE",
            "Hear one scene before the book.",
            "Preview the opening passage first. Nothing leaves your phone."
        )

        val playerBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = rounded(espresso, 18f)
        }
        playerBox.addView(TextView(this).apply {
            text = "LOCAL PREVIEW"
            textSize = 9f
            letterSpacing = 0.16f
            setTextColor(Color.rgb(218, 181, 157))
        })
        playerBox.addView(WaveformView(this), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58)))
        playerBox.addView(TextView(this).apply {
            text = "Arabic clarity  •  rhythm  •  pacing"
            textSize = 11f
            setTextColor(Color.rgb(219, 207, 198))
        })
        card.addView(playerBox)

        card.addView(space(10))

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        previewButton = actionButton("Create preview", true) {
            renderPreview(currentSpeed())
        }
        controls.addView(previewButton, LinearLayout.LayoutParams(0, dp(48), 1f))

        controls.addView(spaceHorizontal(8))

        previewPlayButton = actionButton("Play preview", false) {
            playFile(previewFile, "Create a preview first.")
        }.apply { isEnabled = false }
        controls.addView(previewPlayButton, LinearLayout.LayoutParams(0, dp(48), 1f))

        card.addView(controls)
        return card
    }

    private fun buildRenderCard(): View {
        val card = sectionCard(
            "04 / RENDER PLAN",
            "Ready when the voice feels right.",
            "Segmented rendering keeps long books stable and resumable."
        )

        renderMeta = TextView(this).apply {
            textSize = 13f
            setTextColor(cocoa)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(Color.rgb(250, 239, 232), 14f)
        }
        card.addView(renderMeta)

        card.addView(space(10))

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
        }
        card.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(10)))

        card.addView(space(10))

        renderButton = actionButton("Render full audiobook", true) {
            renderBook(currentSpeed())
        }
        card.addView(renderButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))

        card.addView(space(8))

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(actionButton("Stop", false) {
            renderer.cancel()
            status.text = "Stopping after the current segment…"
        }, LinearLayout.LayoutParams(0, dp(46), 1f))
        row.addView(spaceHorizontal(8))
        fullPlayButton = actionButton("Play / Pause", false) {
            playFile(outputFile, "Render an audiobook first.")
        }.apply { isEnabled = false }
        row.addView(fullPlayButton, LinearLayout.LayoutParams(0, dp(46), 1f))
        card.addView(row)

        return card
    }

    private fun buildPrivacyFooter(): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = rounded(Color.rgb(236, 241, 237), 16f)
        }
        box.addView(TextView(this).apply {
            text = "PRIVATE BY DEFAULT"
            textSize = 10f
            letterSpacing = 0.14f
            setTextColor(sageText)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        box.addView(TextView(this).apply {
            text = "Your manuscript and generated audio stay on this device. This build requests no Internet permission."
            textSize = 12f
            setTextColor(cocoa)
            setPadding(0, dp(5), 0, 0)
        })
        return box
    }

    private fun sectionCard(kicker: String, title: String, subtitle: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = cardBackground()
            elevation = dp(3).toFloat()

            addView(TextView(this@MainActivity).apply {
                text = kicker
                textSize = 9f
                letterSpacing = 0.14f
                setTextColor(copper)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            })
            addView(TextView(this@MainActivity).apply {
                text = title
                textSize = 25f
                setTextColor(espresso)
                typeface = Typeface.create("serif", Typeface.NORMAL)
                setPadding(0, dp(4), 0, dp(4))
            })
            addView(TextView(this@MainActivity).apply {
                text = subtitle
                textSize = 12f
                setTextColor(muted)
                setPadding(0, 0, 0, dp(14))
            })
        }
    }

    private fun actionButton(title: String, primary: Boolean, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = title
            isAllCaps = false
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(if (primary) Color.WHITE else cocoa)
            background = rounded(if (primary) copper else Color.rgb(238, 233, 226), 14f)
            stateListAnimator = null
            setOnClickListener { onClick() }
        }
    }

    private fun label(value: String): TextView = TextView(this).apply {
        text = value
        textSize = 17f
        setTextColor(espresso)
        typeface = Typeface.create("serif", Typeface.BOLD)
    }

    private fun cardBackground(): GradientDrawable = rounded(paper, 20f, line, 1)

    private fun rounded(fill: Int, radiusDp: Float, stroke: Int? = null, strokeWidthDp: Int = 0): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fill)
            cornerRadius = dp(radiusDp).toFloat()
            if (stroke != null && strokeWidthDp > 0) setStroke(dp(strokeWidthDp), stroke)
        }
    }

    private fun currentSpeed(): Float = 0.75f + speedBar.progress / 100f

    private fun refreshManuscriptStats() {
        if (!::textBox.isInitialized || !::manuscriptMeta.isInitialized || !::renderMeta.isInitialized) return
        val text = textBox.text?.toString().orEmpty()
        val chunks = if (text.isBlank()) emptyList() else BookChunker.split(text)
        val words = Regex("\\S+").findAll(text).count()
        manuscriptMeta.text = if (text.isBlank()) {
            "No manuscript loaded"
        } else {
            "✓  ${words} words  •  ${chunks.size} render segments"
        }
        renderMeta.text = if (chunks.isEmpty()) {
            "Add text to create a render plan."
        } else {
            "${chunks.size} segments  •  resumable local WAV  •  no upload"
        }
    }

    private fun verifyRuntime() {
        previewButton.isEnabled = false
        renderButton.isEnabled = false
        previewPlayButton.isEnabled = false
        fullPlayButton.isEnabled = false

        status.text = if (tts.isModelBundled()) {
            "Preparing offline Arabic engine… first launch may take a moment."
        } else {
            "Offline model package is incomplete."
        }

        if (!tts.isModelBundled()) return

        executor.submit {
            try {
                tts.load()
                val probe = tts.synthesize("مرحبا", 1.0f)
                check(probe.isNotEmpty()) { "Native TTS self-test produced no audio." }

                runOnUiThread {
                    status.text = "✓ Offline engine verified. Arabic synthesis is ready."
                    previewButton.isEnabled = true
                    renderButton.isEnabled = true
                }
            } catch (e: Throwable) {
                runOnUiThread {
                    status.text = "Engine startup failed: ${e.javaClass.simpleName}: ${e.message}"
                    previewButton.isEnabled = false
                    renderButton.isEnabled = false
                }
            }
        }
    }

    private fun renderPreview(speed: Float) {
        if (!tts.isModelBundled()) {
            Toast.makeText(this, "Offline model is missing from APK assets.", Toast.LENGTH_LONG).show()
            return
        }
        val chunks = BookChunker.split(textBox.text.toString())
        if (chunks.isEmpty()) {
            Toast.makeText(this, "Add some text first.", Toast.LENGTH_SHORT).show()
            return
        }

        previewButton.isEnabled = false
        previewPlayButton.isEnabled = false
        status.text = "Creating local preview…"

        executor.submit {
            try {
                val samples = tts.synthesize(chunks.first(), speed)
                val pcm = File(cacheDir, "latif_preview.pcm")
                val wav = File(cacheDir, "latif_preview.wav")
                WavWriter.floatToPcm16(samples, pcm)
                WavWriter.mergePcm16Mono(listOf(pcm), wav, tts.sampleRate())
                previewFile = wav
                runOnUiThread {
                    status.text = "✓ Preview ready. Listen before rendering the full book."
                    previewButton.isEnabled = true
                    previewPlayButton.isEnabled = true
                }
            } catch (e: Throwable) {
                runOnUiThread {
                    status.text = "Preview error: ${e.message}"
                    previewButton.isEnabled = true
                }
            }
        }
    }

    private fun renderBook(speed: Float) {
        if (!tts.isModelBundled()) {
            Toast.makeText(this, "Offline model is missing from APK assets.", Toast.LENGTH_LONG).show()
            return
        }
        val text = textBox.text.toString()
        if (text.isBlank()) {
            Toast.makeText(this, "Add a manuscript first.", Toast.LENGTH_SHORT).show()
            return
        }

        progress.progress = 0
        renderButton.isEnabled = false
        fullPlayButton.isEnabled = false
        status.text = "Starting local audiobook render…"

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
                    status.text = "✓ Audiobook ready: ${outputFile?.name}"
                    renderButton.isEnabled = true
                    fullPlayButton.isEnabled = true
                }
            } catch (_: InterruptedException) {
                runOnUiThread {
                    status.text = "Generation stopped. Existing segments remain available for resume."
                    renderButton.isEnabled = true
                }
            } catch (e: Throwable) {
                runOnUiThread {
                    status.text = "Render error: ${e.message}"
                    renderButton.isEnabled = true
                }
            } finally {
                if (wake.isHeld) wake.release()
            }
        }
    }

    private fun playFile(file: File?, missingMessage: String) {
        if (file == null || !file.exists()) {
            Toast.makeText(this, missingMessage, Toast.LENGTH_SHORT).show()
            return
        }

        player?.let {
            if (it.isPlaying) it.pause() else it.start()
            return
        }

        player = MediaPlayer().apply {
            setDataSource(file.absolutePath)
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
                status.text = "✓ Manuscript loaded."
                refreshManuscriptStats()
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

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).toInt()
    private fun space(heightDp: Int): Space = Space(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(heightDp))
    }
    private fun spaceHorizontal(widthDp: Int): Space = Space(this).apply {
        layoutParams = LinearLayout.LayoutParams(dp(widthDp), 1)
    }

    inner class WaveformView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(218, 139, 96)
            strokeWidth = dp(3).toFloat()
            strokeCap = Paint.Cap.ROUND
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val bars = 36
            val mid = height / 2f
            val gap = width.toFloat() / bars
            for (i in 0 until bars) {
                val phase = i * 0.73
                val energy = 0.18f + 0.82f * abs(sin(phase)).toFloat()
                val h = (height * 0.72f * energy) / 2f
                val x = gap * i + gap / 2f
                canvas.drawLine(x, mid - h, x, mid + h, paint)
            }
        }
    }

    companion object {
        private const val REQUEST_TEXT = 1001
    }
}

package com.latif.audiobook.offline

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sin

class MainActivity : Activity() {
    private var selectedUri: Uri? = null
    private var selectedDisplayName: String? = null
    private var mediaPlayer: MediaPlayer? = null
    private var selectedProfile: NarrationProfile = NarrationProfile.LITERARY

    private lateinit var titleInput: EditText
    private lateinit var textInput: EditText
    private lateinit var bookMeta: TextView
    private lateinit var statusText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var progressLabel: TextView
    private lateinit var speedLabel: TextView
    private lateinit var speedSeek: SeekBar
    private lateinit var generateButton: Button
    private lateinit var cancelButton: Button
    private lateinit var playButton: Button
    private lateinit var shareButton: Button
    private lateinit var waveform: WaveformView

    private val profileCards = linkedMapOf<NarrationProfile, LinearLayout>()

    private val ink = Color.rgb(9, 11, 18)
    private val ink2 = Color.rgb(13, 17, 29)
    private val panel = Color.rgb(18, 22, 35)
    private val panel2 = Color.rgb(23, 28, 43)
    private val ivory = Color.rgb(244, 239, 226)
    private val muted = Color.rgb(148, 151, 166)
    private val line = Color.rgb(48, 55, 72)
    private val saffron = Color.rgb(232, 184, 106)
    private val saffronDim = Color.rgb(145, 111, 59)

    private val progressReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                AudiobookService.ACTION_PROGRESS -> {
                    val p = intent.getIntExtra(AudiobookService.EXTRA_PROGRESS, 0)
                    val message = intent.getStringExtra(AudiobookService.EXTRA_MESSAGE).orEmpty()
                    progress.progress = p
                    progress.visibility = View.VISIBLE
                    progressLabel.visibility = View.VISIBLE
                    progressLabel.text = String.format(Locale.US, "%02d%%", p)
                    statusText.text = message
                    generateButton.isEnabled = false
                    generateButton.alpha = 0.55f
                    cancelButton.visibility = View.VISIBLE
                    waveform.active = true
                }
                AudiobookService.ACTION_COMPLETE -> {
                    progress.progress = 100
                    progressLabel.text = "100%"
                    statusText.text = "اكتمل الكتاب الصوتي وحُفظ في Music/LATIF Audiobooks"
                    generateButton.isEnabled = true
                    generateButton.alpha = 1f
                    cancelButton.visibility = View.GONE
                    playButton.visibility = View.VISIBLE
                    shareButton.visibility = View.VISIBLE
                    waveform.active = false
                }
                AudiobookService.ACTION_FAILED -> {
                    val msg = intent.getStringExtra(AudiobookService.EXTRA_MESSAGE).orEmpty()
                    progress.visibility = View.GONE
                    progressLabel.visibility = View.GONE
                    statusText.text = if (msg == "Cancelled") "تم إلغاء التوليد" else "توقف التوليد: $msg"
                    generateButton.isEnabled = true
                    generateButton.alpha = 1f
                    cancelButton.visibility = View.GONE
                    waveform.active = false
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = ink
        window.navigationBarColor = ink
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 88)
        }
        setContentView(buildUi())
        restorePreferences()
        restoreLastOutput()
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(AudiobookService.ACTION_PROGRESS)
            addAction(AudiobookService.ACTION_COMPLETE)
            addAction(AudiobookService.ACTION_FAILED)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(progressReceiver, filter, RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(progressReceiver, filter)
    }

    override fun onStop() {
        runCatching { unregisterReceiver(progressReceiver) }
        super.onStop()
    }

    override fun onDestroy() {
        mediaPlayer?.release()
        super.onDestroy()
    }

    private fun buildUi(): View {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(ink)
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(18), dp(22), dp(46))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        scroll.addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        root.addView(brandHeader())
        root.addView(divider())
        root.addView(heroBlock())
        root.addView(statsStrip())

        root.addView(kicker("01 / الكتاب"))
        root.addView(sectionHeadline("أدخل كتابك.\nواترك الصفحة تتكلّم."))
        root.addView(bodyText("اختر ملفاً كاملاً أو الصق نصاً عربياً. كل المعالجة والتوليد يبقيان داخل الهاتف."))

        val choose = primaryButton("اختيار كتاب  PDF · EPUB · DOCX · TXT").apply {
            setOnClickListener { chooseBook() }
        }
        root.addView(choose, fullWidth(dp(58), top = 18))

        bookMeta = metaText("لم يتم اختيار كتاب بعد — يمكنك لصق النص أدناه.")
        root.addView(bookMeta, fullWidthWrap(top = 10))

        titleInput = studioField("عنوان الكتاب الصوتي", singleLine = true)
        root.addView(titleInput, fullWidthWrap(top = 12))

        textInput = studioField("أو الصق النص العربي هنا…", singleLine = false).apply {
            minLines = 6
            maxLines = 12
            gravity = Gravity.TOP or Gravity.START
            textDirection = View.TEXT_DIRECTION_RTL
        }
        root.addView(textInput, fullWidthWrap(top = 10))

        root.addView(kicker("02 / طبقات الصوت"), fullWidthWrap(top = 34))
        root.addView(sectionHeadline("اختر النبرة.\nواترك الباقي للصفحة."))
        root.addView(bodyText("ثلاث شخصيات أداء مبنية فوق صوت Nabra الحقيقي. لا أصوات وهمية؛ الاختلاف في الإيقاع، الوقفات، وقرب السرد."))

        NarrationProfile.entries.forEachIndexed { index, profile ->
            val card = profileCard(profile, index + 1)
            profileCards[profile] = card
            root.addView(card, fullWidthWrap(top = if (index == 0) 18 else 10))
        }

        val pacePanel = studioPanel().apply {
            addView(metaText("سرعة السرد"))
            speedLabel = TextView(this@MainActivity).apply {
                setTextColor(ivory)
                textSize = 22f
                typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
            }
            addView(speedLabel, fullWidthWrap(top = 6))
            speedSeek = SeekBar(this@MainActivity).apply {
                max = 26
                progressTintList = android.content.res.ColorStateList.valueOf(saffron)
                thumbTintList = android.content.res.ColorStateList.valueOf(saffron)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        speedLabel.text = String.format(Locale.US, "%.2f×", speedValue())
                        if (fromUser) persistUiPreferences()
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                    override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
                })
            }
            addView(speedSeek, fullWidth(dp(46), top = 6))
        }
        root.addView(pacePanel, fullWidthWrap(top = 14))

        root.addView(kicker("03 / الاستوديو"), fullWidthWrap(top = 34))
        root.addView(sectionHeadline("تقنية هادئة،\nلا تقف بينك وبين النص."))

        val console = studioPanel().apply {
            val topRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutDirection = View.LAYOUT_DIRECTION_LTR
            }
            topRow.addView(metaText("NOW NARRATING").apply { textDirection = View.TEXT_DIRECTION_LTR }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            topRow.addView(dot())
            addView(topRow)

            waveform = WaveformView(this@MainActivity).apply {
                setBackgroundColor(Color.TRANSPARENT)
            }
            addView(waveform, fullWidth(dp(132), top = 18))

            val tech = TextView(this@MainActivity).apply {
                text = "NABRA 82M FP16   ·   RAWI TASHKEEL   ·   24 kHz   ·   AAC 96"
                setTextColor(Color.rgb(114, 117, 132))
                textSize = 10.5f
                typeface = Typeface.MONOSPACE
                letterSpacing = 0.08f
                gravity = Gravity.CENTER
                textDirection = View.TEXT_DIRECTION_LTR
            }
            addView(tech, fullWidthWrap(top = 10))
        }
        root.addView(console, fullWidthWrap(top = 16))

        generateButton = primaryButton("إنشاء الكتاب الصوتي كاملاً").apply { setOnClickListener { startGeneration() } }
        root.addView(generateButton, fullWidth(dp(64), top = 16))

        cancelButton = secondaryButton("إلغاء التوليد").apply {
            visibility = View.GONE
            setOnClickListener {
                startService(Intent(this@MainActivity, AudiobookService::class.java).setAction(AudiobookService.ACTION_CANCEL))
            }
        }
        root.addView(cancelButton, fullWidth(dp(54), top = 10))

        val progressRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            visibility = View.GONE
            progressTintList = android.content.res.ColorStateList.valueOf(saffron)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(37, 42, 55))
        }
        progressLabel = TextView(this).apply {
            text = "00%"
            setTextColor(saffron)
            textSize = 12f
            typeface = Typeface.MONOSPACE
            visibility = View.GONE
            gravity = Gravity.END
        }
        progressRow.addView(progress, LinearLayout.LayoutParams(0, dp(8), 1f))
        progressRow.addView(progressLabel, LinearLayout.LayoutParams(dp(54), ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(10) })
        root.addView(progressRow, fullWidthWrap(top = 14))

        statusText = metaText("جاهز. المحرك المحلي مثبت داخل التطبيق.").apply {
            textDirection = View.TEXT_DIRECTION_RTL
            gravity = Gravity.START
        }
        root.addView(statusText, fullWidthWrap(top = 10))

        val outRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        playButton = secondaryButton("تشغيل آخر ملف").apply {
            visibility = View.GONE
            setOnClickListener { playLastOutput() }
        }
        shareButton = secondaryButton("مشاركة / تصدير").apply {
            visibility = View.GONE
            setOnClickListener { shareLastOutput() }
        }
        outRow.addView(playButton, LinearLayout.LayoutParams(0, dp(54), 1f))
        outRow.addView(shareButton, LinearLayout.LayoutParams(0, dp(54), 1f).apply { marginStart = dp(10) })
        root.addView(outRow, fullWidthWrap(top = 10))

        root.addView(divider(), fullWidthWrap(top = 34))
        root.addView(TextView(this).apply {
            text = "PRIVATE BY DESIGN  ·  LOCAL ENGINE  ·  LATIF AUDIOBOOK AI 2.2.0"
            setTextColor(Color.rgb(93, 98, 114))
            textSize = 10f
            typeface = Typeface.MONOSPACE
            letterSpacing = 0.07f
            gravity = Gravity.CENTER
            textDirection = View.TEXT_DIRECTION_LTR
            setPadding(0, dp(18), 0, dp(8))
        })
        root.addView(bodyText("لا حساب · لا API · لا خادم · كتابك يبقى معك").apply {
            gravity = Gravity.CENTER
            textSize = 13f
        })

        return scroll
    }

    private fun brandHeader(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            setPadding(0, dp(10), 0, dp(14))
        }
        val mark = FrameLayout(this).apply {
            background = circleStroke(saffronDim, Color.TRANSPARENT)
            addView(TextView(this@MainActivity).apply {
                text = "◖│◗"
                setTextColor(saffron)
                textSize = 15f
                gravity = Gravity.CENTER
                typeface = Typeface.MONOSPACE
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        row.addView(mark, LinearLayout.LayoutParams(dp(46), dp(46)))

        val wordmark = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        wordmark.addView(TextView(this).apply {
            text = "LATIF"
            setTextColor(ivory)
            textSize = 21f
            typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
            letterSpacing = 0.14f
            textDirection = View.TEXT_DIRECTION_LTR
        })
        wordmark.addView(TextView(this).apply {
            text = "AUDIOBOOK AI"
            setTextColor(Color.rgb(139, 142, 157))
            textSize = 10f
            typeface = Typeface.MONOSPACE
            letterSpacing = 0.13f
            textDirection = View.TEXT_DIRECTION_LTR
        })
        row.addView(wordmark, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        row.addView(TextView(this).apply {
            text = "2.2.0"
            setTextColor(saffron)
            textSize = 12f
            typeface = Typeface.MONOSPACE
            letterSpacing = 0.06f
            gravity = Gravity.CENTER_VERTICAL
            textDirection = View.TEXT_DIRECTION_LTR
        })
        return row
    }

    private fun heroBlock(): View {
        val block = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(28), 0, dp(16))
        }
        block.addView(metaText("استوديو عربي محلي  •  الإصدار 2.2.0").apply {
            setTextColor(saffron)
            gravity = Gravity.START
            textDirection = View.TEXT_DIRECTION_RTL
        })
        block.addView(TextView(this).apply {
            text = "دع الصفحة\nتصبح صوتاً."
            setTextColor(ivory)
            textSize = 49f
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            gravity = Gravity.START
            textDirection = View.TEXT_DIRECTION_RTL
            setLineSpacing(0f, 0.92f)
        }, fullWidthWrap(top = 14))
        block.addView(TextView(this).apply {
            text = "حوّل كتابك العربي إلى تجربة استماع عميقة بنبرة تختارها، وخصوصية لا تغادر هاتفك."
            setTextColor(Color.rgb(177, 179, 191))
            textSize = 18f
            gravity = Gravity.START
            textDirection = View.TEXT_DIRECTION_RTL
            setLineSpacing(dp(4).toFloat(), 1.08f)
        }, fullWidthWrap(top = 20))
        block.addView(TextView(this).apply {
            text = "لا حساب   ·   لا API   ·   لا خادم"
            setTextColor(Color.rgb(123, 126, 141))
            textSize = 11f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.START
            textDirection = View.TEXT_DIRECTION_RTL
            setPadding(0, dp(18), 0, 0)
        })
        return block
    }

    private fun statsStrip(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            setPadding(0, dp(18), 0, dp(26))
        }
        row.addView(statCell("24", "kHz SOURCE"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(statCell("96", "kbps AAC"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(statCell("03", "PROFILES"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    private fun statCell(value: String, label: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.START
        addView(TextView(this@MainActivity).apply {
            text = value
            setTextColor(saffron)
            textSize = 32f
            typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
            textDirection = View.TEXT_DIRECTION_LTR
        })
        addView(TextView(this@MainActivity).apply {
            text = label
            setTextColor(Color.rgb(106, 110, 126))
            textSize = 9.5f
            typeface = Typeface.MONOSPACE
            letterSpacing = 0.06f
            textDirection = View.TEXT_DIRECTION_LTR
        })
    }

    private fun profileCard(profile: NarrationProfile, number: Int): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(17), dp(18), dp(17))
            isClickable = true
            isFocusable = true
            setOnClickListener { selectProfile(profile, true) }
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        top.addView(TextView(this).apply {
            text = String.format(Locale.US, "%02d", number)
            setTextColor(Color.rgb(105, 108, 124))
            textSize = 11f
            typeface = Typeface.MONOSPACE
            textDirection = View.TEXT_DIRECTION_LTR
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(MiniWaveView(this).apply { color = if (profile == NarrationProfile.LITERARY) saffron else Color.rgb(113, 133, 160) }, LinearLayout.LayoutParams(dp(74), dp(32)))
        card.addView(top)

        card.addView(TextView(this).apply {
            text = profile.labelAr
            setTextColor(ivory)
            textSize = 28f
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            gravity = Gravity.START
            textDirection = View.TEXT_DIRECTION_RTL
            setPadding(0, dp(12), 0, 0)
        })
        card.addView(TextView(this).apply {
            text = when (profile) {
                NarrationProfile.LITERARY -> "Deep Calm"
                NarrationProfile.WARM -> "Warm Intimate"
                NarrationProfile.CLEAR -> "Clear Narrator"
            }
            setTextColor(Color.rgb(108, 111, 126))
            textSize = 10.5f
            typeface = Typeface.MONOSPACE
            letterSpacing = 0.05f
            textDirection = View.TEXT_DIRECTION_LTR
        })
        card.addView(TextView(this).apply {
            text = profile.descriptionAr
            setTextColor(Color.rgb(154, 157, 172))
            textSize = 14f
            gravity = Gravity.START
            textDirection = View.TEXT_DIRECTION_RTL
            setPadding(0, dp(12), 0, 0)
        })
        return card
    }

    private fun selectProfile(profile: NarrationProfile, applyDefaultSpeed: Boolean) {
        selectedProfile = profile
        profileCards.forEach { (candidate, view) ->
            val selected = candidate == selectedProfile
            view.background = roundedPanel(if (selected) saffronDim else line, if (selected) Color.rgb(24, 27, 39) else panel)
            view.alpha = if (selected) 1f else 0.83f
        }
        if (applyDefaultSpeed && ::speedSeek.isInitialized) setSpeedValue(profile.defaultSpeed)
        persistUiPreferences()
    }

    private fun chooseBook() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                "application/pdf", "application/epub+zip",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "text/plain"
            ))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, PICK_BOOK)
    }

    @Deprecated("Deprecated in Android SDK; retained for API 26 compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PICK_BOOK || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        selectedUri = uri
        selectedDisplayName = queryDisplayName(uri)
        val base = selectedDisplayName?.substringBeforeLast('.')?.trim().orEmpty()
        if (titleInput.text.isBlank()) titleInput.setText(base)
        bookMeta.text = "جارٍ قراءة ${selectedDisplayName ?: "الكتاب"}…"
        statusText.text = "يتم فحص الكتاب محلياً…"
        Thread {
            runCatching { BookParser.readText(this, uri, selectedDisplayName) }
                .onSuccess { text ->
                    val words = text.split(Regex("\\s+")).count { it.isNotBlank() }
                    runOnUiThread {
                        bookMeta.text = "${selectedDisplayName ?: "الكتاب"}  ·  ${"%,d".format(words)} كلمة  ·  ${text.length} حرف"
                        textInput.setText(text.take(3500))
                        statusText.text = "الكتاب جاهز. سيُستخدم النص الكامل في التوليد."
                    }
                }
                .onFailure { e -> runOnUiThread {
                    bookMeta.text = "تعذر قراءة الكتاب: ${e.message}"
                    statusText.text = "اختر ملفاً آخر أو الصق النص مباشرة."
                } }
        }.start()
    }

    private fun startGeneration() {
        val uri = selectedUri
        val pasted = if (uri == null) textInput.text.toString().trim() else ""
        if (uri == null && pasted.length < 20) {
            Toast.makeText(this, "اختر كتاباً أو الصق نصاً عربياً أولاً.", Toast.LENGTH_SHORT).show()
            return
        }
        persistUiPreferences()
        val title = titleInput.text.toString().trim().ifBlank {
            selectedDisplayName?.substringBeforeLast('.') ?: "LATIF Audiobook"
        }
        val intent = Intent(this, AudiobookService::class.java).apply {
            if (uri != null) putExtra(AudiobookService.EXTRA_URI, uri.toString()) else putExtra(AudiobookService.EXTRA_RAW_TEXT, pasted)
            putExtra(AudiobookService.EXTRA_TITLE, title)
            putExtra(AudiobookService.EXTRA_DISPLAY_NAME, selectedDisplayName)
            putExtra(AudiobookService.EXTRA_PROFILE, selectedProfile.ordinal)
            putExtra(AudiobookService.EXTRA_SPEED, speedValue())
        }
        progress.progress = 0
        progress.visibility = View.VISIBLE
        progressLabel.visibility = View.VISIBLE
        progressLabel.text = "00%"
        statusText.text = "يبدأ محرك Nabra المحلي…"
        generateButton.isEnabled = false
        generateButton.alpha = 0.55f
        cancelButton.visibility = View.VISIBLE
        waveform.active = true
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
    }

    private fun speedValue(): Float = 0.82f + speedSeek.progress / 100f

    private fun setSpeedValue(value: Float) {
        if (!::speedSeek.isInitialized) return
        speedSeek.progress = (((value.coerceIn(0.82f, 1.08f) - 0.82f) * 100f).toInt()).coerceIn(0, speedSeek.max)
        speedLabel.text = String.format(Locale.US, "%.2f×", speedValue())
    }

    private fun persistUiPreferences() {
        if (!::speedSeek.isInitialized) return
        getSharedPreferences(AudiobookService.PREFS, MODE_PRIVATE).edit()
            .putInt(KEY_UI_PROFILE, selectedProfile.ordinal)
            .putFloat(KEY_UI_SPEED, speedValue())
            .apply()
    }

    private fun restorePreferences() {
        val prefs = getSharedPreferences(AudiobookService.PREFS, MODE_PRIVATE)
        selectedProfile = NarrationProfile.fromOrdinal(
            prefs.getInt(KEY_UI_PROFILE, prefs.getInt(AudiobookService.KEY_LAST_PROFILE, NarrationProfile.LITERARY.ordinal))
        )
        selectProfile(selectedProfile, false)
        setSpeedValue(prefs.getFloat(KEY_UI_SPEED, selectedProfile.defaultSpeed))
    }

    private fun restoreLastOutput() {
        val raw = getSharedPreferences(AudiobookService.PREFS, MODE_PRIVATE).getString(AudiobookService.KEY_LAST_OUTPUT, null)
        if (!raw.isNullOrBlank()) {
            playButton.visibility = View.VISIBLE
            shareButton.visibility = View.VISIBLE
        }
    }

    private fun playLastOutput() {
        val raw = getSharedPreferences(AudiobookService.PREFS, MODE_PRIVATE).getString(AudiobookService.KEY_LAST_OUTPUT, null) ?: return
        runCatching {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setDataSource(this@MainActivity, Uri.parse(raw))
                prepare()
                start()
            }
            statusText.text = "يتم تشغيل آخر كتاب صوتي."
        }.onFailure {
            Toast.makeText(this, "تعذر تشغيل الملف الصوتي.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareLastOutput() {
        val raw = getSharedPreferences(AudiobookService.PREFS, MODE_PRIVATE).getString(AudiobookService.KEY_LAST_OUTPUT, null) ?: return
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "audio/mp4"
            putExtra(Intent.EXTRA_STREAM, Uri.parse(raw))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "مشاركة الكتاب الصوتي"))
    }

    private fun queryDisplayName(uri: Uri): String? {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getString(0)
        }
        return uri.lastPathSegment
    }

    private fun studioPanel(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(18), dp(18), dp(18))
        background = roundedPanel(line, panel)
    }

    private fun studioField(hintText: String, singleLine: Boolean): EditText = EditText(this).apply {
        hint = hintText
        setTextColor(ivory)
        setHintTextColor(Color.rgb(96, 100, 117))
        background = roundedPanel(line, Color.rgb(15, 18, 29))
        setPadding(dp(15), dp(14), dp(15), dp(14))
        setSingleLine(singleLine)
        textSize = 15f
        textDirection = View.TEXT_DIRECTION_RTL
        layoutDirection = View.LAYOUT_DIRECTION_RTL
    }

    private fun kicker(textValue: String): TextView = TextView(this).apply {
        text = textValue
        setTextColor(saffron)
        textSize = 11f
        typeface = Typeface.MONOSPACE
        letterSpacing = 0.06f
        gravity = Gravity.START
        textDirection = View.TEXT_DIRECTION_RTL
    }

    private fun sectionHeadline(textValue: String): TextView = TextView(this).apply {
        text = textValue
        setTextColor(ivory)
        textSize = 39f
        typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        gravity = Gravity.START
        textDirection = View.TEXT_DIRECTION_RTL
        setLineSpacing(0f, 0.95f)
        setPadding(0, dp(10), 0, 0)
    }

    private fun bodyText(textValue: String): TextView = TextView(this).apply {
        text = textValue
        setTextColor(Color.rgb(161, 164, 178))
        textSize = 16f
        gravity = Gravity.START
        textDirection = View.TEXT_DIRECTION_RTL
        setLineSpacing(dp(3).toFloat(), 1.08f)
        setPadding(0, dp(12), 0, 0)
    }

    private fun metaText(textValue: String): TextView = TextView(this).apply {
        text = textValue
        setTextColor(muted)
        textSize = 11.5f
        typeface = Typeface.MONOSPACE
        letterSpacing = 0.035f
        gravity = Gravity.START
    }

    private fun primaryButton(label: String): Button = Button(this).apply {
        text = label
        setTextColor(Color.rgb(21, 21, 23))
        textSize = 15f
        typeface = Typeface.DEFAULT_BOLD
        isAllCaps = false
        background = GradientDrawable().apply {
            cornerRadius = dp(5).toFloat()
            setColor(saffron)
        }
        setPadding(dp(16), 0, dp(16), 0)
    }

    private fun secondaryButton(label: String): Button = Button(this).apply {
        text = label
        setTextColor(ivory)
        textSize = 13f
        isAllCaps = false
        background = roundedPanel(line, panel2)
        setPadding(dp(12), 0, dp(12), 0)
    }

    private fun divider(): View = View(this).apply {
        setBackgroundColor(line)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
    }

    private fun dot(): View = View(this).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(saffron)
        }
        layoutParams = LinearLayout.LayoutParams(dp(7), dp(7))
    }

    private fun roundedPanel(strokeColor: Int, fillColor: Int): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(7).toFloat()
        setColor(fillColor)
        setStroke(dp(1), strokeColor)
    }

    private fun circleStroke(strokeColor: Int, fillColor: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(fillColor)
        setStroke(dp(1), strokeColor)
    }

    private fun fullWidth(height: Int, top: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height).apply { topMargin = dp(top) }

    private fun fullWidthWrap(top: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private inner class WaveformView(context: Context) : View(context) {
        var active: Boolean = false
            set(value) {
                field = value
                invalidate()
            }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeCap = Paint.Cap.ROUND
            strokeWidth = dp(3).toFloat()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val count = 27
            val gap = width.toFloat() / (count + 1)
            val center = height / 2f
            val maxH = height * 0.58f
            for (i in 0 until count) {
                val x = gap * (i + 1)
                val envelope = 1f - abs((i - count / 2f) / (count / 2f)) * 0.52f
                val wave = (0.34f + abs(sin(i * 1.18)) * 0.66f) * envelope
                val h = maxH * wave
                paint.color = if (active || i in 8..18) saffron else Color.rgb(117, 95, 60)
                paint.alpha = if (active) 255 else if (i in 8..18) 230 else 115
                canvas.drawLine(x, center - h / 2f, x, center + h / 2f, paint)
            }
        }
    }

    private inner class MiniWaveView(context: Context) : View(context) {
        var color: Int = saffron
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeCap = Paint.Cap.ROUND
            strokeWidth = dp(2).toFloat()
        }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            p.color = color
            val count = 9
            val step = width.toFloat() / (count + 1)
            val center = height / 2f
            for (i in 0 until count) {
                val h = height * (0.22f + 0.55f * abs(sin((i + 1) * 0.9)))
                val x = step * (i + 1)
                canvas.drawLine(x, center - h / 2f, x, center + h / 2f, p)
            }
        }
    }

    companion object {
        const val PICK_BOOK = 501
        private const val KEY_UI_PROFILE = "ui_profile"
        private const val KEY_UI_SPEED = "ui_speed"
    }
}

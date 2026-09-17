package com.latif.audiobook.offline

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
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
import android.widget.*
import java.util.Locale

/** Native UI for LATIF Voice Studio v3.2 adaptive mobile renderer. */
class MainActivityV3 : Activity() {
    private var bookUri: Uri? = null
    private var bookName: String? = null
    private var referenceUri: Uri? = null
    private var referenceName: String? = null
    private var mediaPlayer: MediaPlayer? = null

    private lateinit var titleInput: EditText
    private lateinit var textInput: EditText
    private lateinit var bookInfo: TextView
    private lateinit var voiceInfo: TextView
    private lateinit var referenceText: EditText
    private lateinit var speedLabel: TextView
    private lateinit var speedSeek: SeekBar
    private lateinit var qualitySpinner: Spinner
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var preview: Button
    private lateinit var generate: Button
    private lateinit var cancel: Button
    private lateinit var play: Button
    private lateinit var share: Button

    private val bg = Color.rgb(8, 10, 16)
    private val panel = Color.rgb(18, 22, 34)
    private val ivory = Color.rgb(244, 239, 226)
    private val muted = Color.rgb(150, 154, 170)
    private val saffron = Color.rgb(232, 184, 106)

    private val modeLabels = listOf(
        "Turbo · 8 خطوات — أسرع اختبار/كتاب",
        "Balanced · 12 خطوة — الافتراضي",
        "Studio · 16 خطوة — تفاصيل أكثر",
        "High · 24 خطوة — بطيء",
        "Max · 32 خطوة — المسار الكامل",
    )
    private val modeSteps = intArrayOf(8, 12, 16, 24, 32)

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                AudiobookService.ACTION_PROGRESS -> {
                    progress.visibility = View.VISIBLE
                    progress.progress = intent.getIntExtra(AudiobookService.EXTRA_PROGRESS, 0)
                    status.text = intent.getStringExtra(AudiobookService.EXTRA_MESSAGE).orEmpty()
                    preview.isEnabled = false
                    generate.isEnabled = false
                    cancel.visibility = View.VISIBLE
                }
                AudiobookService.ACTION_COMPLETE -> {
                    progress.visibility = View.VISIBLE
                    progress.progress = 100
                    status.text = "اكتمل الملف الصوتي — Music/LATIF Audiobooks"
                    preview.isEnabled = true
                    generate.isEnabled = true
                    cancel.visibility = View.GONE
                    play.visibility = View.VISIBLE
                    share.visibility = View.VISIBLE
                }
                AudiobookService.ACTION_FAILED -> {
                    val message = intent.getStringExtra(AudiobookService.EXTRA_MESSAGE).orEmpty()
                    status.text = if (message == "Cancelled") "تم إلغاء التوليد" else "توقف التوليد: $message"
                    preview.isEnabled = true
                    generate.isEnabled = true
                    cancel.visibility = View.GONE
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        if (Build.VERSION.SDK_INT >= 24) runCatching { window.setSustainedPerformanceMode(true) }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 88)
        }
        setContentView(buildUi())
        restoreLastOutput()
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(AudiobookService.ACTION_PROGRESS)
            addAction(AudiobookService.ACTION_COMPLETE)
            addAction(AudiobookService.ACTION_FAILED)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(receiver, filter)
    }

    override fun onStop() {
        runCatching { unregisterReceiver(receiver) }
        super.onStop()
    }

    override fun onDestroy() {
        mediaPlayer?.release()
        super.onDestroy()
    }

    private fun buildUi(): View {
        val scroll = ScrollView(this).apply { setBackgroundColor(bg) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(20), dp(24), dp(20), dp(42))
        }
        scroll.addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        root.addView(TextView(this).apply {
            text = "LATIF VOICE STUDIO"
            setTextColor(ivory)
            textSize = 27f
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            gravity = Gravity.START
        })
        root.addView(TextView(this).apply {
            text = "3.2 · ADAPTIVE SILMA F5 · XNNPACK / NNAPI FP16 / CPU"
            setTextColor(saffron)
            textSize = 12.5f
            setPadding(0, dp(4), 0, dp(18))
        })

        root.addView(panel().apply {
            addView(title("السرعة أصبحت خياراً حقيقياً"))
            addView(body("بدلاً من إجبار الهاتف على 32 خطوة لكل مقطع، اختر 8 / 12 / 16 / 24 / 32. التطبيق يجرب XNNPACK ثم NNAPI FP16 ثم NNAPI ثم CPU، ويعرض ETA و RTF فعليين بعد بدء التوليد."))
            addView(body("الجهاز: ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}"))
        })

        root.addView(kicker("01 / الكتاب"))
        root.addView(button("اختيار PDF · EPUB · DOCX · TXT", true).apply { setOnClickListener { chooseBook() } })
        bookInfo = body("لم يتم اختيار كتاب. يمكنك لصق النص مباشرة.")
        root.addView(bookInfo)
        titleInput = field("عنوان الكتاب الصوتي", true)
        root.addView(titleInput, params(top = 10))
        textInput = field("أو الصق النص العربي هنا…", false).apply {
            minLines = 6
            maxLines = 12
            gravity = Gravity.TOP or Gravity.START
            textDirection = View.TEXT_DIRECTION_RTL
        }
        root.addView(textInput, params(top = 10))

        root.addView(kicker("02 / الصوت"))
        root.addView(panel().apply {
            addView(title("الراوي المدمج + استنساخ WAV"))
            addView(body("الراوي العربي المدمج يعمل بلا إنترنت. ويمكنك اختيار WAV واضح مع النص المنطوق نفسه لاستنساخ صوت تملك الإذن باستخدامه."))
        })
        root.addView(button("اختيار WAV لاستنساخ صوت آخر — اختياري", false).apply { setOnClickListener { chooseReference() } }, params(top = 10))
        voiceInfo = body("حالياً: الراوي العربي المدمج")
        root.addView(voiceInfo)
        referenceText = field("إذا اخترت WAV: اكتب النص المنطوق في المرجع بدقة", false).apply {
            minLines = 2
            maxLines = 4
            textDirection = View.TEXT_DIRECTION_RTL
        }
        root.addView(referenceText, params(top = 8))

        root.addView(kicker("03 / الأداء"))
        val pace = panel()
        speedLabel = title("سرعة السرد  0.94×")
        pace.addView(speedLabel)
        speedSeek = SeekBar(this).apply {
            max = 20
            progress = 12
            progressTintList = android.content.res.ColorStateList.valueOf(saffron)
            thumbTintList = android.content.res.ColorStateList.valueOf(saffron)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    speedLabel.text = String.format(Locale.US, "سرعة السرد  %.2f×", speedValue())
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        pace.addView(speedSeek, params(top = 8))
        pace.addView(title("وضع التوليد"), params(top = 10))
        qualitySpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivityV3, android.R.layout.simple_spinner_dropdown_item, modeLabels)
            setSelection(1)
        }
        pace.addView(qualitySpinner, params(top = 6))
        pace.addView(body("ابدأ بـ Balanced 12. إذا أعجبك الصوت وما زال بطيئاً، جرّب Turbo 8. استخدم Max 32 فقط بعد اختبار مقطع، لأنه الأعلى تكلفة حسابية."))
        root.addView(pace)

        root.addView(kicker("04 / الاختبار ثم التوليد"))
        preview = button("اختبار مقطع واحد أولاً", false).apply { setOnClickListener { startGeneration(previewOnly = true) } }
        root.addView(preview)
        generate = button("إنشاء الكتاب الصوتي بالكامل", true).apply { setOnClickListener { startGeneration(previewOnly = false) } }
        root.addView(generate, params(top = 8))
        cancel = button("إلغاء", false).apply {
            visibility = View.GONE
            setOnClickListener { startService(Intent(this@MainActivityV3, AudiobookService::class.java).setAction(AudiobookService.ACTION_CANCEL)) }
        }
        root.addView(cancel, params(top = 8))
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
            progressTintList = android.content.res.ColorStateList.valueOf(saffron)
        }
        root.addView(progress, params(top = 12))
        status = body("جاهز. اختبر مقطعاً واحداً قبل الكتاب الكامل. بعد أول مقطع كامل سيظهر معدل RTF ووقت الإنجاز المتوقع.")
        root.addView(status)

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        play = button("تشغيل آخر ملف", false).apply { visibility = View.GONE; setOnClickListener { playLast() } }
        share = button("مشاركة", false).apply { visibility = View.GONE; setOnClickListener { shareLast() } }
        row.addView(play, LinearLayout.LayoutParams(0, dp(54), 1f))
        row.addView(share, LinearLayout.LayoutParams(0, dp(54), 1f).apply { marginStart = dp(8) })
        root.addView(row, params(top = 10))

        root.addView(TextView(this).apply {
            text = "OFFLINE · SILMA F5 ONNX · ADAPTIVE 8–32 NFE · XNNPACK / NNAPI · ARM64 · v3.2"
            setTextColor(Color.rgb(90, 94, 110))
            textSize = 9.5f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            textDirection = View.TEXT_DIRECTION_LTR
            setPadding(0, dp(30), 0, 0)
        })
        return scroll
    }

    private fun chooseBook() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                "application/pdf", "application/epub+zip",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "text/plain"
            ))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }, PICK_BOOK)
    }

    private fun chooseReference() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/wav"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("audio/wav", "audio/x-wav", "audio/wave"))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }, PICK_REFERENCE)
    }

    @Deprecated("Deprecated Android callback retained for API 26 compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        when (requestCode) {
            PICK_BOOK -> {
                bookUri = uri
                bookName = queryName(uri)
                val base = bookName?.substringBeforeLast('.').orEmpty()
                if (titleInput.text.isBlank()) titleInput.setText(base)
                bookInfo.text = "الكتاب: ${bookName ?: "ملف مختار"}"
                status.text = "تم اختيار الكتاب. اختبر مقطعاً واحداً قبل التوليد الكامل."
            }
            PICK_REFERENCE -> {
                referenceUri = uri
                referenceName = queryName(uri)
                voiceInfo.text = "صوت مستنسخ من: ${referenceName ?: "WAV"}"
                Toast.makeText(this, "أدخل الآن النص المنطوق في ملف المرجع.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startGeneration(previewOnly: Boolean) {
        val pasted = if (bookUri == null) textInput.text.toString().trim() else ""
        if (bookUri == null && pasted.length < 20) {
            Toast.makeText(this, "اختر كتاباً أو الصق نصاً أولاً.", Toast.LENGTH_SHORT).show()
            return
        }
        if (referenceUri != null && referenceText.text.toString().trim().length < 5) {
            Toast.makeText(this, "اكتب النص المنطوق في مرجع الصوت بدقة.", Toast.LENGTH_LONG).show()
            return
        }

        val bookTitle = titleInput.text.toString().trim().ifBlank {
            bookName?.substringBeforeLast('.') ?: "LATIF Audiobook"
        }
        val steps = selectedNfeSteps()
        val work = Intent(this, AudiobookService::class.java).apply {
            bookUri?.let { putExtra(AudiobookService.EXTRA_URI, it.toString()) }
                ?: putExtra(AudiobookService.EXTRA_RAW_TEXT, pasted)
            putExtra(AudiobookService.EXTRA_TITLE, bookTitle)
            putExtra(AudiobookService.EXTRA_DISPLAY_NAME, bookName)
            putExtra(AudiobookService.EXTRA_SPEED, speedValue())
            putExtra(AudiobookService.EXTRA_PROFILE, NarrationProfile.LITERARY.ordinal)
            putExtra(AudiobookService.EXTRA_PREVIEW_ONLY, previewOnly)
            putExtra(AudiobookService.EXTRA_NFE_STEPS, steps)
            referenceUri?.let { putExtra(AudiobookService.EXTRA_REFERENCE_URI, it.toString()) }
            putExtra(AudiobookService.EXTRA_REFERENCE_TEXT, referenceText.text.toString().trim())
        }
        progress.visibility = View.VISIBLE
        progress.progress = 0
        status.text = if (previewOnly) "بدء اختبار $steps خطوات…" else "بدء الكتاب بوضع $steps خطوات…"
        preview.isEnabled = false
        generate.isEnabled = false
        cancel.visibility = View.VISIBLE
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(work) else startService(work)
    }

    private fun selectedNfeSteps(): Int = modeSteps[qualitySpinner.selectedItemPosition.coerceIn(0, modeSteps.lastIndex)]
    private fun speedValue(): Float = 0.82f + speedSeek.progress / 100f

    private fun restoreLastOutput() {
        val prefs = getSharedPreferences(AudiobookService.PREFS, MODE_PRIVATE)
        val raw = prefs.getString(AudiobookService.KEY_LAST_OUTPUT, null)
        val lastSteps = prefs.getInt(AudiobookService.KEY_LAST_NFE_STEPS, AudiobookService.DEFAULT_NFE_STEPS)
        val index = modeSteps.indexOf(lastSteps)
        if (index >= 0) qualitySpinner.setSelection(index)
        if (!raw.isNullOrBlank()) {
            play.visibility = View.VISIBLE
            share.visibility = View.VISIBLE
        }
    }

    private fun playLast() {
        val raw = getSharedPreferences(AudiobookService.PREFS, MODE_PRIVATE)
            .getString(AudiobookService.KEY_LAST_OUTPUT, null) ?: return
        runCatching {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setDataSource(this@MainActivityV3, Uri.parse(raw))
                prepare()
                start()
            }
        }.onFailure { Toast.makeText(this, "تعذر تشغيل الملف.", Toast.LENGTH_SHORT).show() }
    }

    private fun shareLast() {
        val raw = getSharedPreferences(AudiobookService.PREFS, MODE_PRIVATE)
            .getString(AudiobookService.KEY_LAST_OUTPUT, null) ?: return
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "audio/mp4"
            putExtra(Intent.EXTRA_STREAM, Uri.parse(raw))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "مشاركة الكتاب الصوتي"))
    }

    private fun queryName(uri: Uri): String? {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) return c.getString(0)
        }
        return uri.lastPathSegment
    }

    private fun panel() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = GradientDrawable().apply {
            setColor(panel)
            cornerRadius = dp(16).toFloat()
            setStroke(dp(1), Color.rgb(48, 54, 69))
        }
        layoutParams = params(top = 10)
    }

    private fun kicker(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(saffron)
        textSize = 12f
        typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(26), 0, dp(8))
    }

    private fun title(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(ivory)
        textSize = 17f
        typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
    }

    private fun body(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(muted)
        textSize = 13f
        setLineSpacing(0f, 1.18f)
        setPadding(0, dp(7), 0, dp(4))
    }

    private fun field(hint: String, singleLine: Boolean) = EditText(this).apply {
        this.hint = hint
        setTextColor(ivory)
        setHintTextColor(Color.rgb(105, 110, 126))
        setSingleLine(singleLine)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = GradientDrawable().apply {
            setColor(Color.rgb(14, 17, 27))
            cornerRadius = dp(13).toFloat()
            setStroke(dp(1), Color.rgb(47, 53, 68))
        }
    }

    private fun button(label: String, primary: Boolean) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(if (primary) Color.rgb(22, 17, 10) else ivory)
        background = GradientDrawable().apply {
            cornerRadius = dp(13).toFloat()
            setColor(if (primary) saffron else Color.rgb(24, 28, 40))
            if (!primary) setStroke(dp(1), Color.rgb(62, 67, 82))
        }
    }

    private fun params(top: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(top) }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val PICK_BOOK = 501
        private const val PICK_REFERENCE = 502
    }
}

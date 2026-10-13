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
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** Native UI for the deterministic v3.3 Author Narrator pipeline. */
class MainActivityV3 : Activity() {
    private var bookUri: Uri? = null
    private var bookName: String? = null
    private var referenceUri: Uri? = null
    private var referenceName: String? = null

    private lateinit var titleInput: EditText
    private lateinit var textInput: EditText
    private lateinit var bookInfo: TextView
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var preview: Button
    private lateinit var generate: Button
    private lateinit var cancel: Button
    private lateinit var play: Button
    private lateinit var share: Button
    private lateinit var devPanel: LinearLayout
    private lateinit var devLog: TextView
    private lateinit var modelLine: TextView
    private lateinit var chunkLine: TextView
    private lateinit var referenceInfo: TextView
    private lateinit var referenceTranscript: EditText

    private val bg = Color.rgb(8, 10, 16)
    private val panelColor = Color.rgb(18, 22, 34)
    private val ivory = Color.rgb(244, 239, 226)
    private val muted = Color.rgb(150, 154, 170)
    private val saffron = Color.rgb(232, 184, 106)

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                AudiobookService.ACTION_PROGRESS -> {
                    progress.visibility = View.VISIBLE
                    progress.progress = intent.getIntExtra(AudiobookService.EXTRA_PROGRESS, 0)
                    status.text = intent.getStringExtra(AudiobookService.EXTRA_MESSAGE).orEmpty()
                    updateDeveloperMonitor(status.text.toString(), progress.progress)
                    preview.isEnabled = false
                    generate.isEnabled = false
                    cancel.visibility = View.VISIBLE
                }
                AudiobookService.ACTION_COMPLETE -> {
                    progress.visibility = View.VISIBLE
                    progress.progress = 100
                    val sidecar = intent.getStringExtra(AudiobookService.EXTRA_SIDECAR_URI)
                    status.text = if (sidecar.isNullOrBlank()) {
                        "اكتمل الملف الصوتي — Music/LATIF Audiobooks"
                    } else {
                        "اكتمل الصوت + ملف التنقل — Music/LATIF Audiobooks"
                    }
                    updateDeveloperMonitor("اكتمل التوليد · تم حفظ الصوت وملف الفصول", 100)
                    preview.isEnabled = true
                    generate.isEnabled = true
                    cancel.visibility = View.GONE
                    play.visibility = View.VISIBLE
                    share.visibility = View.VISIBLE
                }
                AudiobookService.ACTION_FAILED -> {
                    val message = intent.getStringExtra(AudiobookService.EXTRA_MESSAGE).orEmpty()
                    status.text = if (message == "Cancelled") "تم إلغاء التوليد" else "توقف التوليد: $message"
                    updateDeveloperMonitor("فشل/إلغاء: $message", progress.progress)
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
        if (
            Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
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
            text = "3.3 · AUTHOR NARRATOR · VERIFIED LOCAL SILMA PIPELINE"
            setTextColor(saffron)
            textSize = 12f
            setPadding(0, dp(4), 0, dp(18))
        })

        root.addView(panel().apply {
            addView(title("إعداد الراوي ثابت ومقصود"))
            addView(body("LATIF Author Narrator · Literary · سرعة 0.90× · المسار المتوازن 24 خطوة F5. لا توجد إعدادات وهمية أو استنساخ صوت مخفي في هذا الإصدار."))
            addView(body("المحرك يجرب XNNPACK ثم NNAPI FP16 ثم NNAPI ثم ORT CPU، ويعرض الـ backend الفعلي و ETA / RTF أثناء العمل."))
            addView(body("الجهاز: ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}"))
        })

        root.addView(button("إعدادات التطوير ومراقبة النموذج  ▸", false).apply {
            setOnClickListener {
                devPanel.visibility = if (devPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            }
        }, params(top = 10))
        devPanel = panel().apply {
            visibility = View.GONE
            addView(title("لوحة التطوير · مراقبة حية"))
            addView(body("الصوت ثابت لكل الكتب: ${VoiceReferenceConfig.LABEL_AR} · ${VoiceReferenceConfig.SPEAKER} · مرجع مضمّن. لا يوجد تدريب داخل الهاتف؛ ملفات الصوت المرجعية الخارجية لا تغيّر الهوية إلا إذا دُمجت صراحة في إعدادات البناء."))
            addView(Switch(this@MainActivityV3).apply {
                text = "وضع التفاصيل العالية"
                setTextColor(ivory)
                isChecked = true
                setOnCheckedChangeListener { _, enabled ->
                    devLog.visibility = if (enabled) View.VISIBLE else View.GONE
                }
            })
            modelLine = body("النموذج: في وضع الاستعداد")
            chunkLine = body("المقطع: —")
            addView(modelLine)
            addView(chunkLine)
            devLog = TextView(this@MainActivityV3).apply {
                text = "سجل التنفيذ جاهز…"
                setTextColor(Color.rgb(172, 180, 198))
                textSize = 11f
                typeface = Typeface.MONOSPACE
                setPadding(0, dp(8), 0, 0)
                setLineSpacing(0f, 1.15f)
            }
            addView(devLog)
        }
        root.addView(devPanel, params(top = 6))

        root.addView(kicker("01 / الكتاب"))
        root.addView(button("اختيار PDF · EPUB · DOCX · TXT", true).apply {
            setOnClickListener { chooseBook() }
        })
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

        root.addView(kicker("02 / الصوت المرجعي"))
        root.addView(panel().apply {
            addView(title("مرجع الصوت للـ voice conditioning"))
            addView(body("استخدم المرجع المضمّن، أو اختر WAV نظيفاً بصوت تملك حق استخدامه مع النص الحرفي المنطوق. يفضّل 3–15 ثانية، صوت واحد، دون موسيقى أو صدى."))
            addView(button("استخدام المرجع المضمّن", false).apply {
                setOnClickListener {
                    referenceUri = null
                    referenceName = null
                    referenceInfo.text = "المرجع: SILMA Nabra-82M · ${VoiceReferenceConfig.SPEAKER} · مضمّن"
                }
            })
            addView(button("اختيار WAV لمرجع صوتي مخصص", false).apply {
                setOnClickListener { chooseReference() }
            }, params(top = 8))
            referenceInfo = body("المرجع: SILMA Nabra-82M · ${VoiceReferenceConfig.SPEAKER} · مضمّن")
            addView(referenceInfo)
            referenceTranscript = field("النص الحرفي للمرجع الصوتي", false).apply {
                minLines = 2
                maxLines = 5
                gravity = Gravity.TOP or Gravity.START
                textDirection = View.TEXT_DIRECTION_RTL
            }
            addView(referenceTranscript, params(top = 8))
        })

        root.addView(kicker("03 / خط الإنتاج"))
        root.addView(panel().apply {
            addView(title("Offline author-grade path"))
            addView(body("SILMA F5 ONNX · 24 kHz · AAC-LC 128 kbps M4A · كتابة مؤقتة آمنة ثم نشر نهائي."))
            addView(body("بعد كل مقطع، يحفظ التطبيق توقيت البداية والنهاية الحقيقيين وينشئ ملف .chapters.json للتنقل، بدون تقدير زمني من طول النص."))
            addView(body("ملفات الموديل تتحقق من أحجامها قبل تشغيل ONNX، وأي backend يفشل يُغلق بالكامل قبل تجربة البديل."))
        })

        root.addView(kicker("04 / الاختبار ثم التوليد"))
        preview = button("اختبار مقطع واحد أولاً", false).apply {
            setOnClickListener { startGeneration(previewOnly = true) }
        }
        root.addView(preview)
        generate = button("إنشاء الكتاب الصوتي بالكامل", true).apply {
            setOnClickListener { startGeneration(previewOnly = false) }
        }
        root.addView(generate, params(top = 8))
        cancel = button("إلغاء", false).apply {
            visibility = View.GONE
            setOnClickListener {
                startService(
                    Intent(this@MainActivityV3, AudiobookService::class.java)
                        .setAction(AudiobookService.ACTION_CANCEL),
                )
            }
        }
        root.addView(cancel, params(top = 8))

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
            progressTintList = android.content.res.ColorStateList.valueOf(saffron)
        }
        root.addView(progress, params(top = 12))
        status = body("جاهز. اختبر مقطعاً واحداً أولاً. أثناء التوليد سيظهر backend الفعلي، refinement، RTF ووقت الإنجاز المتوقع.")
        root.addView(status)

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        play = button("فتح المشغل", false).apply {
            visibility = View.GONE
            setOnClickListener { openLastInPlayer() }
        }
        share = button("مشاركة", false).apply {
            visibility = View.GONE
            setOnClickListener { shareLast() }
        }
        row.addView(play, LinearLayout.LayoutParams(0, dp(54), 1f))
        row.addView(share, LinearLayout.LayoutParams(0, dp(54), 1f).apply { marginStart = dp(8) })
        root.addView(row, params(top = 10))

        root.addView(TextView(this).apply {
            text = "OFFLINE · SILMA F5 · AUTHOR 0.90x · 24 NFE · XNNPACK / NNAPI / CPU · MEDIA3 · v3.3"
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
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(
                    Intent.EXTRA_MIME_TYPES,
                    arrayOf(
                        "application/pdf",
                        "application/epub+zip",
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                        "text/plain",
                    ),
                )
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            },
            PICK_BOOK,
        )
    }

    private fun chooseReference() {
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "audio/wav"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("audio/wav", "audio/x-wav", "audio/*"))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            },
            PICK_REFERENCE,
        )
    }

    @Deprecated("Deprecated Android callback retained for API 26 compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || (requestCode != PICK_BOOK && requestCode != PICK_REFERENCE)) return
        val uri = data?.data ?: return
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (requestCode == PICK_REFERENCE) {
            referenceUri = uri
            referenceName = queryName(uri)
            referenceInfo.text = "المرجع المخصص: ${referenceName ?: "WAV"} · أدخل النص الحرفي قبل التوليد"
            status.text = "تم اختيار مرجع صوتي مخصص. أدخل نصه الحرفي ثم اختبر مقطعاً."
        } else {
            bookUri = uri
            bookName = queryName(uri)
            val base = bookName?.substringBeforeLast('.').orEmpty()
            if (titleInput.text.isBlank()) titleInput.setText(base)
            bookInfo.text = "الكتاب: ${bookName ?: "ملف مختار"}"
            status.text = "تم اختيار الكتاب. اختبر مقطعاً واحداً قبل التوليد الكامل."
        }
    }

    private fun startGeneration(previewOnly: Boolean) {
        val pasted = if (bookUri == null) textInput.text.toString().trim() else ""
        if (bookUri == null && pasted.length < 20) {
            Toast.makeText(this, "اختر كتاباً أو الصق نصاً أولاً.", Toast.LENGTH_SHORT).show()
            return
        }

        val bookTitle = titleInput.text.toString().trim().ifBlank {
            bookName?.substringBeforeLast('.') ?: "LATIF Audiobook"
        }
        val work = Intent(this, AudiobookService::class.java).apply {
            bookUri?.let { putExtra(AudiobookService.EXTRA_URI, it.toString()) }
                ?: putExtra(AudiobookService.EXTRA_RAW_TEXT, pasted)
            putExtra(AudiobookService.EXTRA_TITLE, bookTitle)
            putExtra(AudiobookService.EXTRA_DISPLAY_NAME, bookName)
            putExtra(AudiobookService.EXTRA_PREVIEW_ONLY, previewOnly)
            referenceUri?.let { putExtra(AudiobookService.EXTRA_REFERENCE_URI, it.toString()) }
            if (referenceUri != null) {
                putExtra(AudiobookService.EXTRA_REFERENCE_TEXT, referenceTranscript.text.toString().trim())
            }
        }
        progress.visibility = View.VISIBLE
        progress.progress = 0
        status.text = if (previewOnly) {
            "بدء اختبار Author Narrator · 0.90× · 24 خطوة…"
        } else {
            "بدء الكتاب الكامل · Author Narrator · 0.90× · 24 خطوة…"
        }
        modelLine.text = "النموذج: بدء التشغيل · تحميل SILMA والتحقق من المرجع الصوتي"
        chunkLine.text = "المقطع: تجهيز قائمة المقاطع…"
        devLog.text = "[start] ${if (previewOnly) "preview" else "full audiobook"} · voice=${VoiceReferenceConfig.ID}"
        preview.isEnabled = false
        generate.isEnabled = false
        cancel.visibility = View.VISIBLE
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(work) else startService(work)
    }

    private fun restoreLastOutput() {
        val raw = getSharedPreferences(AudiobookService.PREFS, MODE_PRIVATE)
            .getString(AudiobookService.KEY_LAST_OUTPUT, null)
        if (!raw.isNullOrBlank()) {
            play.visibility = View.VISIBLE
            share.visibility = View.VISIBLE
        }
    }

    private fun openLastInPlayer() {
        val prefs = getSharedPreferences(AudiobookService.PREFS, MODE_PRIVATE)
        val audio = prefs.getString(AudiobookService.KEY_LAST_OUTPUT, null) ?: return
        val sidecar = prefs.getString(AudiobookService.KEY_LAST_SIDECAR, null)
        startActivity(Intent(this, AudiobookPlayerActivity::class.java).apply {
            putExtra(AudiobookPlayerActivity.EXTRA_AUDIO_URI, audio)
            sidecar?.let { putExtra(AudiobookPlayerActivity.EXTRA_SIDECAR_URI, it) }
        })
    }

    private fun shareLast() {
        val raw = getSharedPreferences(AudiobookService.PREFS, MODE_PRIVATE)
            .getString(AudiobookService.KEY_LAST_OUTPUT, null) ?: return
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "audio/mp4"
                    putExtra(Intent.EXTRA_STREAM, Uri.parse(raw))
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
                "مشاركة الكتاب الصوتي",
            ),
        )
    }

    private fun queryName(uri: Uri): String? {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getString(0)
        }
        return uri.lastPathSegment
    }

    private fun updateDeveloperMonitor(message: String, percent: Int) {
        if (!::devPanel.isInitialized) return
        val chunk = Regex("SILMA\\s+(\\d+)/(\\d+)").find(message)
        val refinement = Regex("refinement\\s+(\\d+)/(\\d+)").find(message)
        chunkLine.text = if (chunk != null) {
            val refinementText = refinement?.let { " · refinement ${it.groupValues[1]}/${it.groupValues[2]}" }.orEmpty()
            "المقطع: ${chunk.groupValues[1]}/${chunk.groupValues[2]}$refinementText · إجمالي $percent%"
        } else {
            "المقطع: $message · إجمالي $percent%"
        }
        modelLine.text = "النموذج: ${if (message.contains("SILMA")) message else "SILMA F5 · المرجع الثابت جاهز"}"
        val previous = devLog.text.toString().split('\n').takeLast(7)
        devLog.text = (previous + "[$percent%] $message").takeLast(8).joinToString("\n")
    }

    private fun panel() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = GradientDrawable().apply {
            setColor(panelColor)
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
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(top) }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val PICK_BOOK = 501
        private const val PICK_REFERENCE = 502
    }
}

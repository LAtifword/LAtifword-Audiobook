package com.latif.audiobook.offline

import android.Manifest
import android.app.Activity
import android.content.*
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
import android.widget.*
import java.util.Locale

class MainActivity : Activity() {
    private var selectedUri: Uri? = null
    private var selectedDisplayName: String? = null
    private var mediaPlayer: MediaPlayer? = null

    private lateinit var titleInput: EditText
    private lateinit var textInput: EditText
    private lateinit var bookMeta: TextView
    private lateinit var statusText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var profileSpinner: Spinner
    private lateinit var profileDescription: TextView
    private lateinit var speedLabel: TextView
    private lateinit var speedSeek: SeekBar
    private lateinit var generateButton: Button
    private lateinit var cancelButton: Button
    private lateinit var playButton: Button
    private lateinit var shareButton: Button

    private val progressReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                AudiobookService.ACTION_PROGRESS -> {
                    val p = intent.getIntExtra(AudiobookService.EXTRA_PROGRESS, 0)
                    progress.progress = p
                    progress.visibility = View.VISIBLE
                    statusText.text = intent.getStringExtra(AudiobookService.EXTRA_MESSAGE).orEmpty()
                    generateButton.isEnabled = false
                    cancelButton.visibility = View.VISIBLE
                }
                AudiobookService.ACTION_COMPLETE -> {
                    progress.progress = 100
                    statusText.text = "تم إنشاء الكتاب الصوتي وحفظه في Music/LATIF Audiobooks"
                    generateButton.isEnabled = true
                    cancelButton.visibility = View.GONE
                    playButton.visibility = View.VISIBLE
                    shareButton.visibility = View.VISIBLE
                }
                AudiobookService.ACTION_FAILED -> {
                    val msg = intent.getStringExtra(AudiobookService.EXTRA_MESSAGE).orEmpty()
                    progress.visibility = View.GONE
                    statusText.text = if (msg == "Cancelled") "تم إلغاء التوليد" else "توقف التوليد: $msg"
                    generateButton.isEnabled = true
                    cancelButton.visibility = View.GONE
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.rgb(8, 8, 14)) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(36))
        }
        scroll.addView(root)

        root.addView(TextView(this).apply {
            text = "LATIF AUDIOBOOK AI"
            setTextColor(Color.WHITE)
            textSize = 27f
            typeface = Typeface.DEFAULT_BOLD
        })
        root.addView(TextView(this).apply {
            text = "Arabic Studio · Nabra + Rawi Neural Tashkeel"
            setTextColor(Color.rgb(175, 168, 205))
            textSize = 14f
            setPadding(0, dp(4), 0, dp(18))
        })

        root.addView(card().apply {
            addView(TextView(this@MainActivity).apply {
                text = "HIGH QUALITY ARABIC PIPELINE\nRawi neural diacritization → Nabra-82M FP16 → 24 kHz → AAC 96 kbps"
                setTextColor(Color.rgb(213, 202, 255))
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
            })
            addView(TextView(this@MainActivity).apply {
                text = "التشكيل يتم محلياً قبل النطق لتحسين الحركات ومخارج الكلمات. لا API ولا خادم ولا Termux."
                setTextColor(Color.rgb(145, 146, 165))
                textSize = 12f
                textDirection = View.TEXT_DIRECTION_RTL
                setPadding(0, dp(8), 0, 0)
            })
        })

        root.addView(sectionTitle("1  BOOK"))
        root.addView(primaryButton("Choose PDF / EPUB / DOCX / TXT").apply { setOnClickListener { chooseBook() } })

        bookMeta = TextView(this).apply {
            text = "No book selected. You can paste Arabic text below."
            setTextColor(Color.rgb(158, 160, 176))
            textSize = 13f
            setPadding(dp(2), dp(10), dp(2), dp(10))
        }
        root.addView(bookMeta)

        titleInput = EditText(this).apply {
            hint = "Audiobook title"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(110, 112, 130))
            background = fieldBg()
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setSingleLine(true)
        }
        root.addView(titleInput, marginParams())

        textInput = EditText(this).apply {
            hint = "Or paste Arabic text here…"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(110, 112, 130))
            background = fieldBg()
            gravity = Gravity.TOP or Gravity.START
            minLines = 7
            maxLines = 14
            setPadding(dp(14), dp(12), dp(14), dp(12))
            textDirection = View.TEXT_DIRECTION_RTL
        }
        root.addView(textInput, marginParams())

        root.addView(sectionTitle("2  NARRATION"))
        root.addView(card().apply {
            addView(TextView(this@MainActivity).apply {
                text = "Nabra af_msa · Natural Arabic narrator"
                setTextColor(Color.WHITE)
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
            })
            addView(TextView(this@MainActivity).apply {
                text = "صوت Nabra الحقيقي الوحيد مع تشكيل Rawi العصبي. الأوضاع التالية تغيّر أداء القراءة والإيقاع، لا تستعمل أصواتاً وهمية."
                setTextColor(Color.rgb(150, 151, 170))
                textSize = 12f
                textDirection = View.TEXT_DIRECTION_RTL
                setPadding(0, dp(6), 0, dp(10))
            })

            profileSpinner = Spinner(this@MainActivity).apply {
                adapter = ArrayAdapter(
                    this@MainActivity,
                    android.R.layout.simple_spinner_dropdown_item,
                    NarrationProfile.entries.map { it.labelAr }
                )
            }
            addView(profileSpinner, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(54)))

            profileDescription = TextView(this@MainActivity).apply {
                setTextColor(Color.rgb(174, 176, 194))
                textSize = 12f
                textDirection = View.TEXT_DIRECTION_RTL
                setPadding(0, dp(8), 0, 0)
            }
            addView(profileDescription)
        })

        speedLabel = TextView(this).apply {
            setTextColor(Color.rgb(205, 205, 218))
            textSize = 14f
            setPadding(0, dp(10), 0, 0)
        }
        root.addView(speedLabel)
        speedSeek = SeekBar(this).apply {
            max = 26
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    speedLabel.text = String.format(Locale.US, "Narration pace  %.2f×", speedValue())
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        root.addView(speedSeek)

        profileSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val profile = NarrationProfile.fromOrdinal(position)
                profileDescription.text = profile.descriptionAr
                setSpeedValue(profile.defaultSpeed)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        profileSpinner.setSelection(NarrationProfile.LITERARY.ordinal)
        profileDescription.text = NarrationProfile.LITERARY.descriptionAr
        setSpeedValue(NarrationProfile.LITERARY.defaultSpeed)

        root.addView(sectionTitle("3  CREATE"))
        generateButton = primaryButton("CREATE COMPLETE AUDIOBOOK").apply { setOnClickListener { startGeneration() } }
        root.addView(generateButton, marginParams())

        cancelButton = secondaryButton("Cancel generation").apply {
            visibility = View.GONE
            setOnClickListener { startService(Intent(this@MainActivity, AudiobookService::class.java).setAction(AudiobookService.ACTION_CANCEL)) }
        }
        root.addView(cancelButton, marginParams())

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; progress = 0; visibility = View.GONE
        }
        root.addView(progress, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(8)).apply { topMargin = dp(8) })

        statusText = TextView(this).apply {
            text = "Ready. Rawi + Nabra high-quality Arabic pipeline is local."
            setTextColor(Color.rgb(174, 176, 194))
            textSize = 13f
            setPadding(0, dp(10), 0, dp(12))
        }
        root.addView(statusText)

        val outRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        playButton = secondaryButton("Play last").apply { visibility = View.GONE; setOnClickListener { playLastOutput() } }
        shareButton = secondaryButton("Share / Export").apply { visibility = View.GONE; setOnClickListener { shareLastOutput() } }
        outRow.addView(playButton, LinearLayout.LayoutParams(0, dp(50), 1f))
        outRow.addView(shareButton, LinearLayout.LayoutParams(0, dp(50), 1f).apply { marginStart = dp(10) })
        root.addView(outRow)

        root.addView(TextView(this).apply {
            text = "Output: Music/LATIF Audiobooks · M4A · 24 kHz source · AAC 96 kbps"
            setTextColor(Color.rgb(105, 108, 125))
            textSize = 12f
            setPadding(0, dp(24), 0, 0)
        })
        return scroll
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
        bookMeta.text = "Reading ${selectedDisplayName ?: "book"}…"
        statusText.text = "Inspecting book locally…"
        Thread {
            runCatching { BookParser.readText(this, uri, selectedDisplayName) }
                .onSuccess { text ->
                    val words = text.split(Regex("\\s+")).count { it.isNotBlank() }
                    runOnUiThread {
                        bookMeta.text = "${selectedDisplayName ?: "Book"} • ${"%,d".format(words)} words • ${text.length} characters"
                        textInput.setText(text.take(3500))
                        statusText.text = "Book loaded. Full source will be diacritized and narrated locally."
                    }
                }
                .onFailure { e -> runOnUiThread {
                    bookMeta.text = "Could not read book: ${e.message}"
                    statusText.text = "Choose another file or paste text directly."
                } }
        }.start()
    }

    private fun startGeneration() {
        val uri = selectedUri
        val pasted = if (uri == null) textInput.text.toString().trim() else ""
        if (uri == null && pasted.length < 20) {
            Toast.makeText(this, "Choose a book or paste Arabic text first.", Toast.LENGTH_SHORT).show(); return
        }
        val title = titleInput.text.toString().trim().ifBlank { selectedDisplayName?.substringBeforeLast('.') ?: "LATIF Audiobook" }
        val profile = NarrationProfile.fromOrdinal(profileSpinner.selectedItemPosition)
        val intent = Intent(this, AudiobookService::class.java).apply {
            if (uri != null) putExtra(AudiobookService.EXTRA_URI, uri.toString()) else putExtra(AudiobookService.EXTRA_RAW_TEXT, pasted)
            putExtra(AudiobookService.EXTRA_TITLE, title)
            putExtra(AudiobookService.EXTRA_DISPLAY_NAME, selectedDisplayName)
            putExtra(AudiobookService.EXTRA_PROFILE, profile.ordinal)
            putExtra(AudiobookService.EXTRA_SPEED, speedValue())
        }
        progress.progress = 0; progress.visibility = View.VISIBLE
        statusText.text = "Starting Rawi tashkeel + Nabra Arabic Studio…"
        generateButton.isEnabled = false; cancelButton.visibility = View.VISIBLE
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
    }

    private fun speedValue(): Float = 0.82f + speedSeek.progress / 100f

    private fun setSpeedValue(value: Float) {
        if (!::speedSeek.isInitialized) return
        speedSeek.progress = (((value.coerceIn(0.82f, 1.08f) - 0.82f) * 100f).toInt()).coerceIn(0, speedSeek.max)
        speedLabel.text = String.format(Locale.US, "Narration pace  %.2f×", speedValue())
    }

    private fun restoreLastOutput() {
        val prefs = getSharedPreferences(AudiobookService.PREFS, MODE_PRIVATE)
        val raw = prefs.getString(AudiobookService.KEY_LAST_OUTPUT, null)
        val profile = prefs.getInt(AudiobookService.KEY_LAST_PROFILE, NarrationProfile.LITERARY.ordinal)
        if (::profileSpinner.isInitialized) profileSpinner.setSelection(profile.coerceIn(0, NarrationProfile.entries.lastIndex))
        if (!raw.isNullOrBlank()) { playButton.visibility = View.VISIBLE; shareButton.visibility = View.VISIBLE }
    }

    private fun playLastOutput() {
        val raw = getSharedPreferences(AudiobookService.PREFS, MODE_PRIVATE).getString(AudiobookService.KEY_LAST_OUTPUT, null) ?: return
        runCatching {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply { setDataSource(this@MainActivity, Uri.parse(raw)); prepare(); start() }
            statusText.text = "Playing completed audiobook"
        }.onFailure { Toast.makeText(this, "Unable to play output", Toast.LENGTH_SHORT).show() }
    }

    private fun shareLastOutput() {
        val raw = getSharedPreferences(AudiobookService.PREFS, MODE_PRIVATE).getString(AudiobookService.KEY_LAST_OUTPUT, null) ?: return
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "audio/mp4"; putExtra(Intent.EXTRA_STREAM, Uri.parse(raw)); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "Share audiobook"))
    }

    private fun queryDisplayName(uri: Uri): String? {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) return c.getString(0) }
        return uri.lastPathSegment
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(16))
        background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(Color.rgb(20, 18, 35)); setStroke(dp(1), Color.rgb(62, 47, 108)) }
        layoutParams = marginParams()
    }
    private fun sectionTitle(text: String) = TextView(this).apply {
        this.text = text; setTextColor(Color.rgb(145, 113, 255)); textSize = 13f; typeface = Typeface.DEFAULT_BOLD; letterSpacing = 0.08f; setPadding(0, dp(22), 0, dp(8))
    }
    private fun primaryButton(label: String) = Button(this).apply {
        text = label; setTextColor(Color.WHITE); textSize = 14f; typeface = Typeface.DEFAULT_BOLD; isAllCaps = false
        background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Color.rgb(93, 50, 210), Color.rgb(147, 45, 232))).apply { cornerRadius = dp(14).toFloat() }
    }
    private fun secondaryButton(label: String) = Button(this).apply {
        text = label; setTextColor(Color.rgb(225, 222, 240)); textSize = 13f; isAllCaps = false
        background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(Color.rgb(25, 25, 37)); setStroke(dp(1), Color.rgb(66, 65, 85)) }
    }
    private fun fieldBg() = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(Color.rgb(18, 19, 29)); setStroke(dp(1), Color.rgb(48, 49, 66)) }
    private fun marginParams() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8); bottomMargin = dp(4) }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object { const val PICK_BOOK = 501 }
}

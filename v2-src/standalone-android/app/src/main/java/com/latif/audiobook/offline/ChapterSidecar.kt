package com.latif.audiobook.offline

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.Locale

data class AudiobookChapter(
    val title: String,
    val startMs: Long,
    val endMs: Long? = null,
)

data class ChapterSidecar(
    val bookTitle: String,
    val author: String? = null,
    val narrator: String? = null,
    val sampleRate: Int? = null,
    val durationMs: Long? = null,
    val chapters: List<AudiobookChapter>,
)

data class PublishedChapterSidecar(
    val uri: Uri,
    val fileName: String,
)

object ChapterSidecarJson {
    private const val SCHEMA_VERSION = 1

    fun toJson(sidecar: ChapterSidecar, pretty: Boolean = true): String {
        validate(sidecar)
        val root = JSONObject().apply {
            put("schema", "latif-audiobook-chapters")
            put("version", SCHEMA_VERSION)
            put("bookTitle", sidecar.bookTitle)
            sidecar.author?.let { put("author", it) }
            sidecar.narrator?.let { put("narrator", it) }
            sidecar.sampleRate?.let { put("sampleRate", it) }
            sidecar.durationMs?.let { put("durationMs", it) }
            put(
                "chapters",
                JSONArray().apply {
                    sidecar.chapters.forEachIndexed { index, chapter ->
                        put(
                            JSONObject().apply {
                                put("index", index + 1)
                                put("title", chapter.title)
                                put("startMs", chapter.startMs)
                                chapter.endMs?.let { put("endMs", it) }
                                put("start", formatTimestamp(chapter.startMs))
                                chapter.endMs?.let { put("end", formatTimestamp(it)) }
                            },
                        )
                    }
                },
            )
        }
        return if (pretty) root.toString(2) else root.toString()
    }

    fun fromJson(json: String): ChapterSidecar {
        require(json.isNotBlank()) { "Chapter JSON is empty" }
        val trimmed = json.trim()
        val result = if (trimmed.startsWith("[")) {
            ChapterSidecar(
                bookTitle = "LATIF Audiobook",
                chapters = parseChapterArray(JSONArray(trimmed)),
            )
        } else {
            val root = JSONObject(trimmed)
            ChapterSidecar(
                bookTitle = root.optString("bookTitle", "LATIF Audiobook"),
                author = root.optNullableString("author"),
                narrator = root.optNullableString("narrator"),
                sampleRate = root.optNullableInt("sampleRate"),
                durationMs = root.optNullableLong("durationMs"),
                chapters = parseChapterArray(
                    root.optJSONArray("chapters")
                        ?: throw IllegalArgumentException("Chapter JSON does not contain a 'chapters' array"),
                ),
            )
        }
        validate(result)
        return result
    }

    fun read(context: Context, uri: Uri): ChapterSidecar {
        val json = context.contentResolver.openInputStream(uri)
            ?.bufferedReader(StandardCharsets.UTF_8)
            ?.use { it.readText() }
            ?: throw IOException("Unable to open chapter sidecar: $uri")
        return fromJson(json)
    }

    fun writeAtomically(
        sidecar: ChapterSidecar,
        destination: File,
        pretty: Boolean = true,
    ) {
        destination.parentFile?.mkdirs()
        val temp = File(destination.parentFile ?: destination.absoluteFile.parentFile, "${destination.name}.tmp")
        if (temp.exists()) temp.delete()
        try {
            temp.outputStream().bufferedWriter(StandardCharsets.UTF_8).use { writer ->
                writer.write(toJson(sidecar, pretty))
                writer.flush()
            }
            if (destination.exists() && !destination.delete()) {
                throw IOException("Unable to replace ${destination.absolutePath}")
            }
            if (!temp.renameTo(destination)) {
                throw IOException("Unable to commit ${destination.absolutePath}")
            }
        } catch (t: Throwable) {
            temp.delete()
            throw t
        }
    }

    fun publishAlongsideAudio(
        context: Context,
        audioUri: Uri,
        sidecar: ChapterSidecar,
        audioDisplayName: String? = null,
        pretty: Boolean = true,
    ): PublishedChapterSidecar {
        validate(sidecar)
        val appContext = context.applicationContext
        val displayName = audioDisplayName
            ?: queryDisplayName(appContext.contentResolver, audioUri)
            ?: sidecar.bookTitle
        val base = displayName.substringBeforeLast('.', displayName)
        val fileName = "${sanitizeFileName(base)}.chapters.json"
        val json = toJson(sidecar, pretty)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            publishQPlus(appContext.contentResolver, fileName, json)
        } else {
            publishPreQ(appContext, fileName, json)
        }
    }

    private fun publishQPlus(
        resolver: ContentResolver,
        fileName: String,
        json: String,
    ): PublishedChapterSidecar {
        val values = ContentValues().apply {
            put(MediaStore.Files.FileColumns.DISPLAY_NAME, fileName)
            put(MediaStore.Files.FileColumns.MIME_TYPE, "application/json")
            put(MediaStore.Files.FileColumns.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/LATIF Audiobooks")
            put(MediaStore.Files.FileColumns.IS_PENDING, 1)
            put(MediaStore.Files.FileColumns.DATE_ADDED, System.currentTimeMillis() / 1_000L)
        }
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values)
            ?: throw IOException("Unable to create MediaStore chapter sidecar")
        try {
            resolver.openOutputStream(uri, "w")?.use { output ->
                output.write(json.toByteArray(StandardCharsets.UTF_8))
                output.flush()
            } ?: throw IOException("Unable to open chapter sidecar output")
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Files.FileColumns.IS_PENDING, 0) },
                null,
                null,
            )
            return PublishedChapterSidecar(uri, fileName)
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)
            throw t
        }
    }

    @Suppress("DEPRECATION")
    private fun publishPreQ(
        context: Context,
        fileName: String,
        json: String,
    ): PublishedChapterSidecar {
        val directory = File(
            context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir,
            "LATIF Audiobooks",
        ).apply { mkdirs() }
        val target = File(directory, fileName)
        val temp = File(directory, "$fileName.tmp")
        try {
            temp.outputStream().buffered().use { output ->
                output.write(json.toByteArray(StandardCharsets.UTF_8))
                output.flush()
            }
            if (target.exists() && !target.delete()) {
                throw IOException("Unable to replace ${target.absolutePath}")
            }
            if (!temp.renameTo(target)) {
                throw IOException("Unable to commit ${target.absolutePath}")
            }
            return PublishedChapterSidecar(Uri.fromFile(target), fileName)
        } catch (t: Throwable) {
            temp.delete()
            throw t
        }
    }

    private fun parseChapterArray(array: JSONArray): List<AudiobookChapter> {
        val result = ArrayList<AudiobookChapter>(array.length())
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index)
                ?: throw IllegalArgumentException("Chapter at index $index is not a JSON object")
            val title = item.optString("title", "Section ${index + 1}").trim()
            require(title.isNotBlank()) { "Chapter $index has an empty title" }
            val startMs = when {
                item.has("startMs") -> item.getLong("startMs")
                item.has("start") -> parseTimestamp(item.getString("start"))
                else -> throw IllegalArgumentException("Chapter $index has no start time")
            }
            val endMs = when {
                item.has("endMs") && !item.isNull("endMs") -> item.getLong("endMs")
                item.has("end") && !item.isNull("end") -> parseTimestamp(item.getString("end"))
                else -> null
            }
            result += AudiobookChapter(title, startMs, endMs)
        }
        return result
    }

    private fun validate(sidecar: ChapterSidecar) {
        require(sidecar.bookTitle.isNotBlank()) { "Book title must not be blank" }
        require(sidecar.chapters.isNotEmpty()) { "At least one navigation section is required" }
        var previousStart = -1L
        sidecar.chapters.forEachIndexed { index, chapter ->
            require(chapter.title.isNotBlank()) { "Section ${index + 1} has an empty title" }
            require(chapter.startMs >= 0L) { "Section ${index + 1} has a negative start time" }
            require(chapter.startMs >= previousStart) { "Section ${index + 1} is out of chronological order" }
            chapter.endMs?.let { end ->
                require(end > chapter.startMs) { "Section ${index + 1} must end after it starts" }
                sidecar.durationMs?.let { duration ->
                    require(end <= duration) { "Section ${index + 1} exceeds audiobook duration" }
                }
            }
            previousStart = chapter.startMs
        }
        sidecar.durationMs?.let { require(it >= 0L) { "Audiobook duration cannot be negative" } }
        sidecar.sampleRate?.let { require(it > 0) { "Sample rate must be positive" } }
    }

    private fun queryDisplayName(resolver: ContentResolver, uri: Uri): String? {
        return resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }

    private fun sanitizeFileName(value: String): String = value.trim()
        .replace(Regex("[\\\\/:*?\"<>|\\r\\n]+"), "_")
        .replace(Regex("\\s+"), " ")
        .trim()
        .ifBlank { "LATIF Audiobook" }
        .take(120)

    private fun formatTimestamp(milliseconds: Long): String {
        val totalSeconds = milliseconds / 1_000L
        val ms = milliseconds % 1_000L
        val hours = totalSeconds / 3_600L
        val minutes = (totalSeconds % 3_600L) / 60L
        val seconds = totalSeconds % 60L
        return String.format(Locale.US, "%02d:%02d:%02d.%03d", hours, minutes, seconds, ms)
    }

    private fun parseTimestamp(value: String): Long {
        val text = value.trim()
        if (text.matches(Regex("\\d+"))) return text.toLong()
        val match = Regex("""^(?:(\d+):)?(\d{1,2}):(\d{2})(?:\.(\d{1,3}))?$""")
            .matchEntire(text)
            ?: throw IllegalArgumentException("Invalid chapter timestamp: $value")
        val hours = match.groupValues[1].takeIf { it.isNotBlank() }?.toLong() ?: 0L
        val minutes = match.groupValues[2].toLong()
        val seconds = match.groupValues[3].toLong()
        require(minutes < 60L && seconds < 60L) { "Invalid chapter timestamp: $value" }
        val millis = match.groupValues[4]
            .takeIf { it.isNotBlank() }
            ?.padEnd(3, '0')
            ?.take(3)
            ?.toLong()
            ?: 0L
        return hours * 3_600_000L + minutes * 60_000L + seconds * 1_000L + millis
    }

    private fun JSONObject.optNullableString(key: String): String? =
        if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotBlank() } else null

    private fun JSONObject.optNullableInt(key: String): Int? =
        if (has(key) && !isNull(key)) getInt(key) else null

    private fun JSONObject.optNullableLong(key: String): Long? =
        if (has(key) && !isNull(key)) getLong(key) else null
}

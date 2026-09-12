package com.latif.audiobook.offline

import android.content.Context
import android.net.Uri
import android.text.Html
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.zip.ZipInputStream

object BookParser {
    fun readText(context: Context, uri: Uri, displayName: String? = null): String {
        val name = (displayName ?: uri.lastPathSegment ?: "book").lowercase(Locale.ROOT)
        val mime = context.contentResolver.getType(uri)?.lowercase(Locale.ROOT).orEmpty()
        return when {
            name.endsWith(".pdf") || mime == "application/pdf" -> readPdf(context, uri)
            name.endsWith(".docx") || mime.contains("wordprocessingml") -> readDocx(context, uri)
            name.endsWith(".epub") || mime == "application/epub+zip" -> readEpub(context, uri)
            else -> context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                ?: error("Unable to open the selected book")
        }.let(::cleanText)
    }

    private fun readPdf(context: Context, uri: Uri): String {
        PDFBoxResourceLoader.init(context.applicationContext)
        context.contentResolver.openInputStream(uri)?.use { input ->
            PDDocument.load(input).use { doc ->
                return PDFTextStripper().getText(doc)
            }
        }
        error("Unable to read PDF")
    }

    private fun readDocx(context: Context, uri: Uri): String {
        context.contentResolver.openInputStream(uri)?.use { raw ->
            ZipInputStream(BufferedInputStream(raw)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.name == "word/document.xml") {
                        val xml = readEntry(zip).toString(Charsets.UTF_8)
                        val htmlish = xml
                            .replace("</w:p>", "</p>")
                            .replace("<w:br/>", "<br/>")
                            .replace(Regex("<w:tab[^>]*/>"), " ")
                            .replace(Regex("<[^>]+>"), "")
                        return Html.fromHtml(htmlish, Html.FROM_HTML_MODE_LEGACY).toString()
                    }
                }
            }
        }
        error("DOCX document text was not found")
    }

    private fun readEpub(context: Context, uri: Uri): String {
        val parts = mutableListOf<Pair<String, String>>()
        context.contentResolver.openInputStream(uri)?.use { raw ->
            ZipInputStream(BufferedInputStream(raw)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val lower = entry.name.lowercase(Locale.ROOT)
                    if (!entry.isDirectory && (lower.endsWith(".xhtml") || lower.endsWith(".html") || lower.endsWith(".htm"))) {
                        val html = readEntry(zip).toString(Charsets.UTF_8)
                        val text = Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY).toString().trim()
                        if (text.length > 40) parts += entry.name to text
                    }
                }
            }
        }
        if (parts.isEmpty()) error("No readable chapters were found in EPUB")
        return parts.sortedBy { it.first }.joinToString("\n\n") { it.second }
    }

    private fun readEntry(zip: ZipInputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val n = zip.read(buffer)
            if (n <= 0) break
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    private fun cleanText(input: String): String {
        return input
            .replace('\u0000', ' ')
            .replace(Regex("[\\t\\x0B\\f\\r ]+"), " ")
            .replace(Regex(" *\\n *"), "\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    fun splitForNarration(text: String, maxChars: Int = 245): List<String> {
        val normalized = cleanText(text)
        if (normalized.isBlank()) return emptyList()

        val sentenceBreak = Regex("(?<=[.!?؟؛…])\\s+|\\n+")
        val sentences = normalized.split(sentenceBreak).map { it.trim() }.filter { it.isNotBlank() }
        val out = mutableListOf<String>()
        val current = StringBuilder()

        fun flush() {
            if (current.isNotBlank()) {
                out += current.toString().trim()
                current.clear()
            }
        }

        for (sentence in sentences) {
            if (sentence.length > maxChars) {
                flush()
                var start = 0
                while (start < sentence.length) {
                    var end = (start + maxChars).coerceAtMost(sentence.length)
                    if (end < sentence.length) {
                        val space = sentence.lastIndexOf(' ', end)
                        if (space > start + maxChars / 2) end = space
                    }
                    out += sentence.substring(start, end).trim()
                    start = end
                    while (start < sentence.length && sentence[start].isWhitespace()) start++
                }
            } else if (current.isEmpty()) {
                current.append(sentence)
            } else if (current.length + 1 + sentence.length <= maxChars) {
                current.append(' ').append(sentence)
            } else {
                flush()
                current.append(sentence)
            }
        }
        flush()
        return out
    }
}

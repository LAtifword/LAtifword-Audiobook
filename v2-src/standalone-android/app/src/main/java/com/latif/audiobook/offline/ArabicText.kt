package com.latif.audiobook.offline

object ArabicText {
    private val arabicDiacritics = Regex("[\\u064B-\\u065F\\u0670]")

    fun prepareForNarration(input: String): String {
        return input
            .replace('\u00A0', ' ')
            .replace('ـ', ' ')
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex(" *([،؛:,.!?؟…]) *"), "$1 ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    fun stripDiacritics(input: String): String = input.replace(arabicDiacritics, "")
}

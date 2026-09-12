package com.latif.audiobook.offline

object ArabicText {
    private val arabicDiacritics = Regex("[\\u0610-\\u061A\\u064B-\\u065F\\u0670\\u06D6-\\u06ED]")
    private val arabicLetters = Regex("[\\u0621-\\u064A\\u066E-\\u06D3]")

    /**
     * Conservative cleanup only. Do not replace hamza forms, ta marbuta, alef
     * maqsura, or existing tashkeel: those distinctions are important to Nabra.
     */
    fun prepareForNarration(input: String): String {
        return input
            .replace('\uFEFF', ' ')
            .replace('\u00A0', ' ')
            .replace('ـ', ' ')
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex(" *([،؛:,.!?؟…]) *"), "$1 ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    fun containsArabic(input: String): Boolean = arabicLetters.containsMatchIn(input)

    fun isAlreadyDiacritized(input: String): Boolean {
        val letters = arabicLetters.findAll(input).count()
        if (letters < 4) return false
        val marks = arabicDiacritics.findAll(input).count()
        return marks.toDouble() / letters.toDouble() >= 0.18
    }

    fun stripDiacritics(input: String): String = input.replace(arabicDiacritics, "")
}

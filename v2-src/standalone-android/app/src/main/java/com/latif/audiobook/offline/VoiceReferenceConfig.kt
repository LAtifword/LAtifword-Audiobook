package com.latif.audiobook.offline

/**
 * One bundled reference is used consistently across books.
 * Habibi-TTS performs in-context voice conditioning; this is not on-device training.
 */
object VoiceReferenceConfig {
    const val ID = "habibi-msa-latif-author-narrator"
    const val LABEL_AR = "الراوي المرجعي الثابت · Habibi MSA"
    const val SPEAKER = "LATIF Author Narrator / MSA"
    const val SAMPLE_RATE = 24_000
    const val MAX_REFERENCE_SECONDS = 15
    const val TRANSCRIPT = "ويدقق النظر في القرآن الكريم وسائر الكتب السماوية ويتبع مسالك الرسل العظام عليهم الصلاة والسلام."
}

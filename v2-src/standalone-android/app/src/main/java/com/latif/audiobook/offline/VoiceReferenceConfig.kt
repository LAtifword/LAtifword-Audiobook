package com.latif.audiobook.offline

/**
 * The app deliberately uses one bundled reference for every book.
 * This is deterministic voice conditioning, not on-device model training.
 */
object VoiceReferenceConfig {
    const val ID = "silma-nabra-82m-af_msa-sid0-bundled"
    const val LABEL_AR = "الراوي المرجعي الثابت · SILMA Nabra-82M"
    const val SPEAKER = "af_msa / sid=0"
    const val SAMPLE_RATE = 24_000
    const val MAX_REFERENCE_SECONDS = 15
    const val TRANSCRIPT = "ويدقق النظر في القرآن الكريم وسائر الكتب السماوية ويتبع مسالك الرسل العظام عليهم الصلاة والسلام."
}

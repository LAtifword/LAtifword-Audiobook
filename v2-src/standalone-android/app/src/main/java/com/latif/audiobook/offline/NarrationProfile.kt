package com.latif.audiobook.offline

/**
 * Performance presets for the single natural Nabra MSA voice.
 *
 * Nabra-82M ships one real speaker (af_msa, sid=0). These presets therefore
 * shape pacing and pauses without pretending that non-existent speaker IDs are
 * additional voices.
 */
enum class NarrationProfile(
    val labelAr: String,
    val descriptionAr: String,
    val defaultSpeed: Float,
    val sentencePauseMs: Int,
    val questionPauseMs: Int,
    val softPauseMs: Int,
) {
    LITERARY(
        labelAr = "أدبي طبيعي",
        descriptionAr = "إيقاع هادئ ومتزن للروايات والكتب الطويلة",
        defaultSpeed = 0.90f,
        sentencePauseMs = 125,
        questionPauseMs = 150,
        softPauseMs = 70,
    ),
    WARM(
        labelAr = "دافئ قريب",
        descriptionAr = "إيقاع أكثر قرباً للمقاطع الوجدانية والحوار الداخلي",
        defaultSpeed = 0.94f,
        sentencePauseMs = 110,
        questionPauseMs = 140,
        softPauseMs = 60,
    ),
    CLEAR(
        labelAr = "واضح مباشر",
        descriptionAr = "وضوح أعلى للمقالات والفكر والفصول المعلوماتية",
        defaultSpeed = 0.98f,
        sentencePauseMs = 95,
        questionPauseMs = 120,
        softPauseMs = 50,
    );

    companion object {
        fun fromOrdinal(value: Int): NarrationProfile = entries.getOrElse(value) { LITERARY }
    }
}

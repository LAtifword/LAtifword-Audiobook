package com.latif.audiobook.offline

/**
 * Compile-time compatibility for the pre-v3 launcher source that is still retained in the
 * project tree. The v3.3 service deliberately ignores these values and always uses the fixed
 * Author Narrator preset (Literary, 0.90x, 32 F5 steps).
 */
val AudiobookService.Companion.EXTRA_PROFILE: String
    get() = "profile"

val AudiobookService.Companion.EXTRA_SPEED: String
    get() = "speed"

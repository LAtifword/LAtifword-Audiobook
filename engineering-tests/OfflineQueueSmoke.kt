package com.latif.audiobook.offline

/** Executable JVM smoke checks; no Android emulator or speech model required. */
fun main() {
    val planner = OfflineNarrationPlanner(maxChars = 50)
    val text = "Hello world. Another sentence! ثالث جملة عربية؟ Final paragraph."
    val initial = planner.plan(text, "voice-a", "rev1")
    check(initial.size >= 3)
    check(initial.all { it.text.length <= 50 })
    check(initial.map { it.index } == initial.indices.toList())
    check(initial.all { it.cacheKey.length == 64 })
    check(planner.plan(text, "voice-a", "rev1") == initial)
    check(planner.plan(text, "voice-b", "rev1").first().cacheKey != initial.first().cacheKey)

    val queue = OfflinePlaybackQueue(planner)
    val first = queue.open(text, "voice-a", "rev1")
    check(first.isNotEmpty())
    check(first.first().segment.index == 0)
    check(queue.accepts(first.first()))
    val next = queue.acknowledge(first.first())
    check(queue.currentIndex() == 1)
    check(!queue.accepts(first.first()))
    check(next.none { it.segment.index < 1 })

    val beforeSwitch = first.drop(1).first()
    val afterSwitch = queue.switchVoice("voice-b", "rev1")
    check(!queue.accepts(beforeSwitch))
    check(afterSwitch.first().segment.voiceId == "voice-b")
    check(queue.acknowledge(beforeSwitch).isEmpty())
    val afterSeek = queue.seek(0)
    check(!queue.accepts(afterSwitch.first()))
    check(afterSeek.first().segment.index == 0)
    queue.stop()
    check(!queue.accepts(afterSeek.first()))
    // A short custom transcript with a long reference consumes acoustic frames
    // quickly; splitting must respect the actual model estimate.
    val referenceSamples = 24_000 * 15
    val transcript = "هذا صوت مرجعي قصير"
    val longArabic = ("هذه فقرة عربية متصلة تختبر تقسيم القراءة إلى أجزاء آمنة. ").repeat(12)
    val pieces = HabibiFramePlanner.plan(referenceSamples, transcript, longArabic)
    check(pieces.size > 1)
    check(pieces.all {
        HabibiFramePlanner.estimateFrames(referenceSamples, transcript, it) <= 4064L
    })
    check(pieces.joinToString(" ").replace(" ", "") ==
        ArabicText.prepareForNarration(longArabic).replace(" ", ""))

    // Unbroken words must still advance without splitting surrogate pairs.
    val oversizedWord = "ب".repeat(1000)
    val wordPieces = HabibiFramePlanner.plan(referenceSamples, transcript, oversizedWord)
    check(wordPieces.size > 1)
    check(wordPieces.joinToString("") == oversizedWord)
    check(wordPieces.all {
        HabibiFramePlanner.estimateFrames(referenceSamples, transcript, it) <= 4064L
    })
    check(HabibiFramePlanner.plan(24_000 * 2, transcript, "مرحبا بالعالم").size == 1)
    check(runCatching {
        HabibiFramePlanner.plan(24_000 * 60, transcript, "مرحبا")
    }.isFailure)
    println("PASS: planner, queue, voice invalidation, seek, stop, Habibi frame splitting")
}

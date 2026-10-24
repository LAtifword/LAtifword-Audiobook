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
    println("PASS: planner, queue, voice invalidation, seek, stop")
}

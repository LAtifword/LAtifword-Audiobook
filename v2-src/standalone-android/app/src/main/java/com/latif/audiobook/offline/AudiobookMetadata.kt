package com.latif.audiobook.offline

data class AudiobookMetadata(
    val title: String,
    val author: String? = null,
    val album: String? = null,
    val narrator: String? = null,
    val year: Int? = null,
    val genre: String? = "Audiobook",
    val comment: String? = null,
    val coverArt: ByteArray? = null,
    val coverMimeType: String = "image/jpeg",
    val chapters: List<AudiobookChapter> = emptyList(),
)

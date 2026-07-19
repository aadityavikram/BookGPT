package com.bookgpt.android.domain.chunking

data class TextSegment(
    val text: String,
    val chapter: String = "",
    val page: Int? = null,
)

data class BookChunk(
    val text: String,
    val chapter: String,
    val page: Int,
    val chunkIndex: Int,
)

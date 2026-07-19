package com.bookgpt.android.domain.loader

import com.bookgpt.android.domain.chunking.TextSegment

data class BookDocument(
    val title: String,
    val filename: String,
    val relativePath: String,
    val author: String? = null,
    val isbn: String? = null,
    val segments: List<TextSegment> = emptyList(),
) {
    val text: String
        get() = segments.joinToString("\n\n") { it.text }
}

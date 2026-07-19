package com.bookgpt.android.domain.retrieve

data class RetrievedChunk(
    val text: String,
    val title: String,
    val filename: String,
    val chunkIndex: Int,
    val chapter: String,
    val page: Int,
    val distance: Float,
)

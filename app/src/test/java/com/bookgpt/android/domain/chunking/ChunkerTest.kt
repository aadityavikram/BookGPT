package com.bookgpt.android.domain.chunking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChunkerTest {
    @Test
    fun splitTextIntoSegments_detectsChapterHeadings() {
        val text = """
            Prologue text here.

            Chapter 1: Beginnings
            Once upon a time there was a library.

            CHAPTER 2 The End
            And then it closed.
        """.trimIndent()

        val segments = Chunker.splitTextIntoSegments(text)
        assertTrue(segments.size >= 2)
        assertTrue(segments.any { it.chapter.contains("Chapter 1", ignoreCase = true) })
        assertTrue(segments.any { it.chapter.contains("CHAPTER 2", ignoreCase = true) })
    }

    @Test
    fun chunkSegments_respectsChunkSizeAndAssignsIndexes() {
        val longParagraph = (1..80).joinToString(" ") { "Sentence number $it is here." }
        val segments = listOf(TextSegment(text = longParagraph, chapter = "Chapter 1", page = 3))
        val chunks = Chunker.chunkSegments(segments, chunkSize = 120, overlap = 20)

        assertTrue(chunks.isNotEmpty())
        assertEquals(chunks.indices.toList(), chunks.map { it.chunkIndex })
        assertTrue(chunks.all { it.chapter == "Chapter 1" })
        assertTrue(chunks.all { it.page == 3 })
        assertTrue(chunks.any { it.text.length <= 200 })
    }
}

package com.bookgpt.android.domain.agent

import com.bookgpt.android.data.web.WebResult
import com.bookgpt.android.domain.retrieve.RetrievedChunk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatSourcesTest {
    @Test
    fun formatSourcesText_includesTitleChapterAndPage() {
        val sources = ChatSources()
            .addLibraryChunk(
                RetrievedChunk(
                    text = "Once upon a time",
                    title = "Fairy Tales",
                    filename = "fairy.txt",
                    chunkIndex = 0,
                    chapter = "Chapter 1",
                    page = 12,
                    distance = 0.2f,
                ),
            )

        val formatted = formatSourcesText(sources)
        assertTrue(formatted.contains("Sources"))
        assertTrue(formatted.contains("Fairy Tales"))
        assertTrue(formatted.contains("Chapter 1"))
        assertTrue(formatted.contains("p. 12"))
    }

    @Test
    fun detectBookInText_matchesExactTitle() {
        val detected = BookAgent.detectBookInText(
            "Tell me about Pride and Prejudice themes",
            listOf("Pride and Prejudice", "Moby Dick"),
        )
        assertTrue(detected == "Pride and Prejudice")
    }

    @Test
    fun formatSourcesText_emptyWhenNoSources() {
        assertFalse(formatSourcesText(ChatSources()).contains("Sources"))
    }

    @Test
    fun formatSourcesText_includesWebResultLink() {
        val sources = ChatSources(
            webResults = listOf(
                WebResult("Book review", "https://example.com/review", "A useful review"),
            ),
        )

        val formatted = formatSourcesText(sources)
        assertTrue(formatted.contains("From web search"))
        assertTrue(formatted.contains("https://example.com/review"))
    }
}

package com.bookgpt.android.domain.agent

import com.bookgpt.android.domain.retrieve.RetrievedChunk
import kotlinx.serialization.Serializable

@Serializable
data class LibraryCitation(
    val chunkIndex: Int,
    val chapter: String = "",
    val page: Int = 0,
)

@Serializable
data class ChatSources(
    val libraryChunks: Map<String, List<LibraryCitation>> = emptyMap(),
) {
    fun addLibraryChunk(chunk: RetrievedChunk): ChatSources {
        val citation = LibraryCitation(
            chunkIndex = chunk.chunkIndex,
            chapter = chunk.chapter,
            page = chunk.page,
        )
        val existing = libraryChunks[chunk.title].orEmpty()
        return copy(libraryChunks = libraryChunks + (chunk.title to (existing + citation)))
    }

    val hasSources: Boolean get() = libraryChunks.isNotEmpty()
}

fun formatSourcesText(sources: ChatSources): String {
    if (!sources.hasSources) return ""
    val lines = mutableListOf("", "---", "Sources")
    for (bookTitle in sources.libraryChunks.keys.sorted()) {
        val labels = sources.libraryChunks[bookTitle]
            .orEmpty()
            .map { citationLabel(it) }
            .toSortedSet()
        lines += "- Based on $bookTitle (library): ${labels.joinToString(", ")}"
    }
    return lines.joinToString("\n")
}

private fun citationLabel(citation: LibraryCitation): String {
    val parts = mutableListOf<String>()
    if (citation.chapter.isNotBlank()) parts += citation.chapter
    if (citation.page > 0) parts += "p. ${citation.page}"
    if (parts.isNotEmpty()) return parts.joinToString(", ")
    return "section ${citation.chunkIndex + 1}"
}

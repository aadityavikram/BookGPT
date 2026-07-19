package com.bookgpt.android.domain.chunking

import com.bookgpt.android.domain.Config
import java.util.regex.Pattern

object Chunker {
    private val chapterLine = Regex(
        """^(?:chapter|ch\.?|part|book|section)\s+[\divxlc]+(?:[:.\-\s]+(.+))?$""",
        RegexOption.IGNORE_CASE,
    )
    private val sentenceBoundary = Pattern.compile("""(?<=[.!?])\s+(?=[A-Z"'“])""")

    fun splitTextIntoSegments(text: String): List<TextSegment> {
        val lines = text.split('\n')
        val segments = mutableListOf<TextSegment>()
        var currentChapter = ""
        val currentLines = mutableListOf<String>()

        fun flush() {
            val body = currentLines.joinToString("\n").trim()
            if (body.isNotEmpty()) {
                segments += TextSegment(text = body, chapter = currentChapter)
            }
        }

        for (line in lines) {
            val stripped = line.trim()
            if (stripped.isNotEmpty() && isChapterHeading(stripped)) {
                flush()
                currentChapter = stripped
                currentLines.clear()
                continue
            }
            currentLines += line
        }

        flush()
        if (segments.isEmpty() && text.trim().isNotEmpty()) {
            segments += TextSegment(text = text.trim())
        }
        return segments
    }

    fun chunkSegments(
        segments: List<TextSegment>,
        chunkSize: Int = Config.CHUNK_SIZE,
        overlap: Int = Config.CHUNK_OVERLAP,
    ): List<BookChunk> {
        val chunks = mutableListOf<BookChunk>()
        var chunkIndex = 0
        for (segment in segments) {
            for (text in subChunkSegment(segment, chunkSize, overlap)) {
                if (text.isBlank()) continue
                chunks += BookChunk(
                    text = text,
                    chapter = segment.chapter,
                    page = segment.page ?: 0,
                    chunkIndex = chunkIndex,
                )
                chunkIndex += 1
            }
        }
        return chunks
    }

    private fun isChapterHeading(line: String): Boolean {
        if (line.length > 120) return false
        if (chapterLine.matches(line)) return true
        val words = line.split(Regex("""\s+"""))
        return line == line.uppercase() && words.size in 3..8
    }

    private fun splitParagraphs(text: String): List<String> =
        text.split(Regex("""\n\s*\n""")).map { it.trim() }.filter { it.isNotEmpty() }

    private fun splitSentences(text: String): List<String> {
        val parts = sentenceBoundary.split(text.trim())
        return parts.map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun tailForOverlap(text: String, overlap: Int): String {
        if (overlap <= 0 || text.length <= overlap) return text
        val tail = text.takeLast(overlap)
        for (boundary in listOf(Regex("""\n\s*\n"""), Regex("""(?<=[.!?])\s+"""))) {
            val parts = tail.split(boundary, limit = 2)
            if (parts.size > 1 && parts.last().isNotBlank()) {
                return parts.last().trim()
            }
        }
        return tail.trim()
    }

    private fun subChunkSegment(
        segment: TextSegment,
        chunkSize: Int,
        overlap: Int,
    ): List<String> {
        val paragraphs = splitParagraphs(segment.text)
        if (paragraphs.isEmpty()) return emptyList()

        val rawChunks = mutableListOf<String>()
        val currentParts = mutableListOf<String>()
        var currentLen = 0

        fun flushCurrent() {
            if (currentParts.isNotEmpty()) {
                rawChunks += currentParts.joinToString("\n\n")
                currentParts.clear()
                currentLen = 0
            }
        }

        for (paragraph in paragraphs) {
            if (paragraph.length > chunkSize) {
                flushCurrent()
                rawChunks += splitLongParagraph(paragraph, chunkSize, overlap)
                continue
            }
            val addedLen = paragraph.length + if (currentParts.isNotEmpty()) 2 else 0
            if (currentParts.isNotEmpty() && currentLen + addedLen > chunkSize) {
                flushCurrent()
            }
            currentParts += paragraph
            currentLen += addedLen
        }
        flushCurrent()
        return applyOverlap(rawChunks, overlap)
    }

    private fun splitLongParagraph(paragraph: String, chunkSize: Int, overlap: Int): List<String> {
        val sentences = splitSentences(paragraph)
        if (sentences.isEmpty()) {
            return if (paragraph.isNotBlank()) listOf(paragraph.take(chunkSize).trim()) else emptyList()
        }

        val chunks = mutableListOf<String>()
        val currentParts = mutableListOf<String>()
        var currentLen = 0

        fun flush() {
            if (currentParts.isNotEmpty()) {
                chunks += currentParts.joinToString(" ")
                currentParts.clear()
                currentLen = 0
            }
        }

        for (sentence in sentences) {
            if (sentence.length > chunkSize) {
                flush()
                var start = 0
                while (start < sentence.length) {
                    val end = minOf(start + chunkSize, sentence.length)
                    val piece = sentence.substring(start, end).trim()
                    if (piece.isNotEmpty()) chunks += piece
                    if (end >= sentence.length) break
                    start = if (overlap > 0) end - overlap else end
                }
                continue
            }
            val addedLen = sentence.length + if (currentParts.isNotEmpty()) 1 else 0
            if (currentParts.isNotEmpty() && currentLen + addedLen > chunkSize) {
                flush()
            }
            currentParts += sentence
            currentLen += addedLen
        }
        flush()
        return applyOverlap(chunks, overlap)
    }

    private fun applyOverlap(chunks: List<String>, overlap: Int): List<String> {
        if (overlap <= 0 || chunks.size <= 1) return chunks
        val merged = mutableListOf(chunks.first())
        for (chunk in chunks.drop(1)) {
            val prefix = tailForOverlap(merged.last(), overlap)
            if (prefix.isNotEmpty() && !chunk.startsWith(prefix)) {
                merged += if ("\n" in prefix) "$prefix\n\n$chunk" else "$prefix $chunk"
            } else {
                merged += chunk
            }
        }
        return merged
    }
}

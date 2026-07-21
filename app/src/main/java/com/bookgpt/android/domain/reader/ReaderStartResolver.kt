package com.bookgpt.android.domain.reader

import com.bookgpt.android.data.db.ChunkEntity

/**
 * Picks a sensible playback start when indexed text still includes TOC stubs
 * or chapters stored out of reading order from older EPUB imports.
 */
object ReaderStartResolver {
    private val chapterOne = Regex(
        """^(chapter|ch\.?|part|book|section)\s+(0*1|i|one)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val intro = Regex(
        """^(prologue|introduction|preface|foreword)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val numberedChapter = Regex(
        """^(chapter|ch\.?|part|book|section)\s+([\divxlc]+|\d+)\b""",
        RegexOption.IGNORE_CASE,
    )

    fun contentStartIndex(chunks: List<ChunkEntity>): Int {
        if (chunks.isEmpty()) return 0

        val introIdx = chunks.indexOfFirst {
            intro.containsMatchIn(it.chapter) && it.text.trim().length >= 40
        }
        if (introIdx >= 0) return introIdx

        val chapterOneIdx = chunks.indexOfFirst {
            chapterOne.containsMatchIn(it.chapter) && it.text.trim().length >= 40
        }
        if (chapterOneIdx >= 0) return chapterOneIdx

        val afterToc = skipLeadingToc(chunks)
        val earliestNumbered = earliestNumberedChapterIndex(chunks)
        return when {
            earliestNumbered == null -> afterToc
            earliestNumbered < afterToc -> afterToc
            else -> earliestNumbered
        }
    }

    private fun skipLeadingToc(chunks: List<ChunkEntity>): Int {
        var index = 0
        while (index < chunks.size && isLikelyTocStub(chunks[index])) {
            index += 1
        }
        return index.coerceAtMost(chunks.lastIndex.coerceAtLeast(0))
    }

    private fun isLikelyTocStub(chunk: ChunkEntity): Boolean {
        val text = chunk.text.trim()
        if (text.length >= 180) return false
        val lower = text.lowercase()
        if (lower == "contents" || lower == "table of contents" || lower == "toc") return true
        if (text.length < 50 && numberedChapter.matches(text)) return true
        if (text.length < 30 && text.all { it.isDigit() || it.isWhitespace() || it in ".-–—" }) {
            return true
        }
        // Short stub whose chapter label keeps changing through a TOC scan.
        return text.length < 80 && numberedChapter.containsMatchIn(chunk.chapter)
    }

    private fun earliestNumberedChapterIndex(chunks: List<ChunkEntity>): Int? {
        var bestIndex: Int? = null
        var bestNumber: Int? = null
        for ((index, chunk) in chunks.withIndex()) {
            if (chunk.text.trim().length < 40) continue
            val match = numberedChapter.find(chunk.chapter) ?: continue
            val number = parseChapterNumber(match.groupValues[2]) ?: continue
            if (bestNumber == null || number < bestNumber) {
                bestNumber = number
                bestIndex = index
            }
        }
        // Only jump when the earliest numbered chapter is not already near the start,
        // which usually means the spine was previously sorted wrong.
        return bestIndex?.takeIf { bestNumber != null && bestNumber <= 2 && it > 0 }
    }

    private fun parseChapterNumber(raw: String): Int? {
        raw.toIntOrNull()?.let { return it }
        return when (raw.lowercase()) {
            "i", "one" -> 1
            "ii", "two" -> 2
            "iii", "three" -> 3
            "iv", "four" -> 4
            "v", "five" -> 5
            "vi", "six" -> 6
            "vii", "seven" -> 7
            "viii", "eight" -> 8
            "ix", "nine" -> 9
            "x", "ten" -> 10
            else -> null
        }
    }
}

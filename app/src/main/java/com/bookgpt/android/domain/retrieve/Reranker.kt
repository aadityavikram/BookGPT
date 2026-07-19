package com.bookgpt.android.domain.retrieve

import com.bookgpt.android.data.openai.ChatMessageDto
import com.bookgpt.android.data.openai.OpenAiClient
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class Reranker @Inject constructor(
    private val openAi: OpenAiClient,
    private val json: Json,
) {
    suspend fun rerank(
        query: String,
        candidates: List<RetrievedChunk>,
        topK: Int,
    ): List<RetrievedChunk> {
        if (candidates.size <= topK) return candidates
        val passages = candidates.mapIndexed { index, chunk ->
            """{"index":$index,"title":${json.encodeToString(chunk.title)},"text":${json.encodeToString(chunk.text.take(1600))}}"""
        }.joinToString(",")
        val prompt = """
            Rank the passages by relevance to the query.
            Return JSON only in this form: {"ranking":[{"index":0,"score":10.0}]}.
            Include every passage index once. Scores range from 0 to 10.
            Query: $query
            Passages: [$passages]
        """.trimIndent()
        return runCatching {
            val raw = openAi.chat(
                listOf(
                    ChatMessageDto("system", "You are a precise passage relevance ranker. Output valid JSON only."),
                    ChatMessageDto("user", prompt),
                ),
                temperature = 0.0,
            )
            val cleaned = raw.substring(raw.indexOf('{'), raw.lastIndexOf('}') + 1)
            val ranking = json.decodeFromString<RankingResponse>(cleaned).ranking
            ranking.sortedByDescending { it.score }
                .mapNotNull { candidates.getOrNull(it.index) }
                .distinct()
                .take(topK)
                .ifEmpty { candidates.take(topK) }
        }.getOrElse { candidates.take(topK) }
    }

    @Serializable
    private data class RankingResponse(val ranking: List<RankedIndex> = emptyList())

    @Serializable
    private data class RankedIndex(val index: Int, val score: Double)
}

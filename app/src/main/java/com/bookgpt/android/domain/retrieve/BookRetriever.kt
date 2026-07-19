package com.bookgpt.android.domain.retrieve

import com.bookgpt.android.data.db.BookDao
import com.bookgpt.android.data.db.EmbeddingDao
import com.bookgpt.android.data.db.EmbeddingScoreRow
import com.bookgpt.android.data.openai.OpenAiClient
import com.bookgpt.android.data.settings.SettingsRepository
import com.bookgpt.android.domain.Config
import java.util.PriorityQueue
import javax.inject.Inject
import javax.inject.Singleton

class ReindexRequiredException : IllegalStateException(
    "The selected embedding model does not match the existing book index.",
)

@Singleton
class BookRetriever @Inject constructor(
    private val embeddingDao: EmbeddingDao,
    private val bookDao: BookDao,
    private val openAiClient: OpenAiClient,
    private val reranker: Reranker,
    private val settingsRepository: SettingsRepository,
) {
    suspend fun search(
        query: String,
        bookTitle: String? = null,
        topK: Int = Config.TOP_K,
    ): List<RetrievedChunk> {
        val bookId = bookTitle?.let { bookDao.getByDisplayTitle(it)?.id }
        val model = settingsRepository.getEmbeddingModel()
        if (embeddingDao.hasModelMismatch(bookId, model)) {
            throw ReindexRequiredException()
        }
        val queryEmbedding = openAiClient.embed(listOf(query)).first()
        val queryNorm = VectorQuantizer.l2Norm(queryEmbedding)
        if (queryNorm == 0f) return emptyList()

        val candidateLimit = Config.RERANK_CANDIDATES
        // Max-heap of the worst distance among current top candidates.
        val best = PriorityQueue<ScoredCandidate>(compareByDescending { it.distance })
        var offset = 0
        while (true) {
            val page = embeddingDao.getScorePage(
                bookId = bookId,
                model = model,
                limit = Config.EMBEDDING_SCORE_PAGE_SIZE,
                offset = offset,
            )
            if (page.isEmpty()) break
            for (row in page) {
                consider(row, queryEmbedding, queryNorm, candidateLimit, best)
            }
            if (page.size < Config.EMBEDDING_SCORE_PAGE_SIZE) break
            offset += page.size
        }
        if (best.isEmpty()) return emptyList()

        val ranked = best.sortedBy { it.distance }
        val texts = embeddingDao.getChunkTexts(ranked.map { it.chunkId })
            .associateBy { it.chunkId }
        val candidates = ranked.mapNotNull { scored ->
            val text = texts[scored.chunkId]?.text ?: return@mapNotNull null
            RetrievedChunk(
                text = text,
                title = scored.displayTitle,
                filename = scored.filename,
                chunkIndex = scored.chunkIndex,
                chapter = scored.chapter,
                page = scored.page,
                distance = scored.distance,
            )
        }
        return reranker.rerank(query, candidates, topK)
    }

    suspend fun hasRelevantLocalContent(query: String, bookTitle: String? = null): Boolean =
        search(query, bookTitle = bookTitle, topK = 3).isNotEmpty()

    private fun consider(
        row: EmbeddingScoreRow,
        queryEmbedding: FloatArray,
        queryNorm: Float,
        candidateLimit: Int,
        best: PriorityQueue<ScoredCandidate>,
    ) {
        if (row.quantized.size != queryEmbedding.size) return
        val similarity = Cosine.similarityToQuantized(
            query = queryEmbedding,
            queryNorm = queryNorm,
            quantized = row.quantized,
            scale = row.scale,
            documentNorm = row.norm,
        )
        val distance = Cosine.distanceFromSimilarity(similarity)
        if (distance > Config.MIN_RELEVANCE_DISTANCE) return

        val candidate = ScoredCandidate(
            chunkId = row.chunkId,
            chunkIndex = row.chunkIndex,
            chapter = row.chapter,
            page = row.page,
            displayTitle = row.displayTitle,
            filename = row.filename,
            distance = distance,
        )
        if (best.size < candidateLimit) {
            best.add(candidate)
        } else {
            val worst = best.peek() ?: return
            if (distance < worst.distance) {
                best.poll()
                best.add(candidate)
            }
        }
    }

    private data class ScoredCandidate(
        val chunkId: Long,
        val chunkIndex: Int,
        val chapter: String,
        val page: Int,
        val displayTitle: String,
        val filename: String,
        val distance: Float,
    )
}

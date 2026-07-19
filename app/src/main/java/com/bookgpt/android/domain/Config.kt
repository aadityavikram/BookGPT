package com.bookgpt.android.domain

object Config {
    const val DEFAULT_OPENAI_MODEL = "gpt-4o-mini"
    const val DEFAULT_EMBEDDING_MODEL = "text-embedding-3-small"
    val CHAT_MODELS = listOf("gpt-4o-mini", "gpt-4o", "gpt-4.1-mini", "gpt-4.1")
    val EMBEDDING_MODELS = listOf(
        "text-embedding-3-small",
        "text-embedding-3-large",
        "text-embedding-ada-002",
    )
    const val CHUNK_SIZE = 1200
    const val CHUNK_OVERLAP = 200
    const val TOP_K = 6
    const val RERANK_CANDIDATES = 20
    const val MIN_RELEVANCE_DISTANCE = 0.85f
    /** How many embedding rows to score per SQLite page during local retrieval. */
    const val EMBEDDING_SCORE_PAGE_SIZE = 256
    const val EMBEDDING_MAX_TOKENS_PER_REQUEST = 250_000
    /**
     * Verbatim chat turns kept after the rolling summary.
     * Older messages are compacted into [ConversationEntity.summary].
     */
    const val CHAT_RECENT_MESSAGE_COUNT = 12
    /** Soft character cap for the stored conversation summary. */
    const val CHAT_SUMMARY_MAX_CHARS = 2500
    val SUPPORTED_EXTENSIONS = setOf("txt", "pdf", "epub")
}

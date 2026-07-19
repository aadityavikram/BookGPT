package com.bookgpt.android.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {
    @Query("SELECT * FROM books ORDER BY displayTitle COLLATE NOCASE ASC")
    fun observeBooks(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books ORDER BY displayTitle COLLATE NOCASE ASC")
    suspend fun getBooks(): List<BookEntity>

    @Query("SELECT * FROM books WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): BookEntity?

    @Query("SELECT * FROM books WHERE relativePath = :relativePath LIMIT 1")
    suspend fun getByRelativePath(relativePath: String): BookEntity?

    @Query("SELECT * FROM books WHERE displayTitle = :displayTitle LIMIT 1")
    suspend fun getByDisplayTitle(displayTitle: String): BookEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(book: BookEntity): Long

    @Query(
        """
        UPDATE books
        SET status = :status,
            errorMessage = :errorMessage,
            indexedAt = :indexedAt,
            fileHash = COALESCE(:fileHash, fileHash),
            displayTitle = COALESCE(:displayTitle, displayTitle),
            title = COALESCE(:title, title),
            author = COALESCE(:author, author),
            isbn = COALESCE(:isbn, isbn)
        WHERE id = :bookId
        """,
    )
    suspend fun updateStatus(
        bookId: Long,
        status: BookStatus,
        errorMessage: String? = null,
        indexedAt: Long? = null,
        fileHash: String? = null,
        displayTitle: String? = null,
        title: String? = null,
        author: String? = null,
        isbn: String? = null,
    )

    @Query("DELETE FROM books WHERE id = :bookId")
    suspend fun deleteById(bookId: Long)

    @Query("SELECT COUNT(*) FROM books WHERE title = :title")
    suspend fun countByTitle(title: String): Int

    @Query("UPDATE books SET status = 'PENDING', errorMessage = NULL, indexedAt = NULL, fileHash = ''")
    suspend fun markAllPending()
}

@Dao
interface ChunkDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(chunks: List<ChunkEntity>): List<Long>

    @Query("DELETE FROM chunks WHERE bookId = :bookId")
    suspend fun deleteByBookId(bookId: Long)

    @Query("SELECT * FROM chunks WHERE bookId = :bookId ORDER BY chunkIndex ASC")
    suspend fun getByBookId(bookId: Long): List<ChunkEntity>

    @Query("DELETE FROM chunks")
    suspend fun deleteAll()
}

@Dao
interface EmbeddingDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(embeddings: List<EmbeddingEntity>)

    @Query(
        """
        SELECT EXISTS(
            SELECT 1
            FROM embeddings e
            INNER JOIN chunks c ON c.id = e.chunkId
            INNER JOIN books b ON b.id = c.bookId
            WHERE b.status = 'INDEXED'
              AND e.model != :model
              AND (:bookId IS NULL OR b.id = :bookId)
        )
        """,
    )
    suspend fun hasModelMismatch(bookId: Long?, model: String): Boolean

    @Query(
        """
        SELECT e.id AS embeddingId, e.chunkId AS chunkId, e.quantized AS quantized,
               e.scale AS scale, e.norm AS norm,
               c.chunkIndex AS chunkIndex, c.chapter AS chapter, c.page AS page,
               b.displayTitle AS displayTitle, b.filename AS filename
        FROM embeddings e
        INNER JOIN chunks c ON c.id = e.chunkId
        INNER JOIN books b ON b.id = c.bookId
        WHERE b.status = 'INDEXED'
          AND e.model = :model
          AND (:bookId IS NULL OR b.id = :bookId)
        ORDER BY e.id ASC
        LIMIT :limit OFFSET :offset
        """,
    )
    suspend fun getScorePage(
        bookId: Long?,
        model: String,
        limit: Int,
        offset: Int,
    ): List<EmbeddingScoreRow>

    @Query(
        """
        SELECT c.id AS chunkId, c.text AS text
        FROM chunks c
        WHERE c.id IN (:chunkIds)
        """,
    )
    suspend fun getChunkTexts(chunkIds: List<Long>): List<ChunkTextRow>

    @Query(
        """
        DELETE FROM embeddings
        WHERE chunkId IN (SELECT id FROM chunks WHERE bookId = :bookId)
        """,
    )
    suspend fun deleteByBookId(bookId: Long)

    @Query("DELETE FROM embeddings")
    suspend fun deleteAll()
}

data class EmbeddingScoreRow(
    val embeddingId: Long,
    val chunkId: Long,
    val quantized: ByteArray,
    val scale: Float,
    val norm: Float,
    val chunkIndex: Int,
    val chapter: String,
    val page: Int,
    val displayTitle: String,
    val filename: String,
)

data class ChunkTextRow(
    val chunkId: Long,
    val text: String,
)

@Dao
interface ChatDao {
    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC")
    fun observeConversations(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC")
    suspend fun getConversations(): List<ConversationEntity>

    @Query("SELECT * FROM chat_messages WHERE conversationId = :conversationId ORDER BY createdAt ASC, id ASC")
    fun observeMessages(conversationId: Long): Flow<List<ChatMessageEntity>>

    @Query("SELECT * FROM chat_messages WHERE conversationId = :conversationId ORDER BY createdAt ASC, id ASC")
    suspend fun getMessages(conversationId: Long): List<ChatMessageEntity>

    @Query("SELECT * FROM chat_messages ORDER BY conversationId, createdAt ASC, id ASC")
    suspend fun getAllMessages(): List<ChatMessageEntity>

    @Insert
    suspend fun insertConversation(conversation: ConversationEntity): Long

    @Insert
    suspend fun insert(message: ChatMessageEntity): Long

    @Query("UPDATE conversations SET title = :title, updatedAt = :updatedAt WHERE id = :conversationId")
    suspend fun updateConversation(conversationId: Long, title: String, updatedAt: Long)

    @Query("UPDATE conversations SET updatedAt = :updatedAt WHERE id = :conversationId")
    suspend fun touchConversation(conversationId: Long, updatedAt: Long)

    @Query("SELECT * FROM conversations WHERE id = :conversationId LIMIT 1")
    suspend fun getConversation(conversationId: Long): ConversationEntity?

    @Query(
        """
        UPDATE conversations
        SET summary = :summary,
            summarizedThroughMessageId = :summarizedThroughMessageId
        WHERE id = :conversationId
        """,
    )
    suspend fun updateConversationSummary(
        conversationId: Long,
        summary: String?,
        summarizedThroughMessageId: Long?,
    )

    @Query(
        """
        UPDATE conversations
        SET summary = NULL,
            summarizedThroughMessageId = NULL
        WHERE id = :conversationId
        """,
    )
    suspend fun clearConversationSummary(conversationId: Long)

    @Query("DELETE FROM chat_messages WHERE conversationId = :conversationId")
    suspend fun clearMessages(conversationId: Long)

    @Transaction
    suspend fun clearConversationMessages(conversationId: Long) {
        clearMessages(conversationId)
        clearConversationSummary(conversationId)
    }

    @Query("DELETE FROM conversations WHERE id = :conversationId")
    suspend fun deleteConversation(conversationId: Long)
}

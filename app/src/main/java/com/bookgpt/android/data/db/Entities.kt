package com.bookgpt.android.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class BookStatus {
    PENDING,
    INDEXING,
    INDEXED,
    FAILED,
}

@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val displayTitle: String,
    val author: String?,
    val isbn: String?,
    val filename: String,
    val relativePath: String,
    val fileHash: String,
    val format: String,
    val status: BookStatus,
    val errorMessage: String? = null,
    val indexedAt: Long? = null,
)

@Entity(
    tableName = "chunks",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("bookId"), Index(value = ["bookId", "chunkIndex"], unique = true)],
)
data class ChunkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val chunkIndex: Int,
    val chapter: String,
    val page: Int,
    val text: String,
)

@Entity(
    tableName = "embeddings",
    foreignKeys = [
        ForeignKey(
            entity = ChunkEntity::class,
            parentColumns = ["id"],
            childColumns = ["chunkId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["chunkId"], unique = true)],
)
data class EmbeddingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val chunkId: Long,
    val model: String,
    /** Int8 quantized embedding components. */
    val quantized: ByteArray,
    /** Multiplier that reconstructs approximate floats: quantized[i] * scale. */
    val scale: Float,
    /** L2 norm of the original float embedding, used for cosine scoring. */
    val norm: Float,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EmbeddingEntity) return false
        return id == other.id &&
            chunkId == other.chunkId &&
            model == other.model &&
            scale == other.scale &&
            norm == other.norm &&
            quantized.contentEquals(other.quantized)
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + chunkId.hashCode()
        result = 31 * result + model.hashCode()
        result = 31 * result + quantized.contentHashCode()
        result = 31 * result + scale.hashCode()
        result = 31 * result + norm.hashCode()
        return result
    }
}

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String = "New chat",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    /** Rolling summary of turns older than the recent sliding window. */
    val summary: String? = null,
    /** Last chat_messages.id covered by [summary]; null when no summary yet. */
    val summarizedThroughMessageId: Long? = null,
)

@Entity(
    tableName = "chat_messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("conversationId")],
)
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: Long,
    val role: String,
    val content: String,
    val sourcesJson: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

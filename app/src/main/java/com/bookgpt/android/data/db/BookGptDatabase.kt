package com.bookgpt.android.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        FolderEntity::class,
        BookEntity::class,
        ChunkEntity::class,
        EmbeddingEntity::class,
        ConversationEntity::class,
        ChatMessageEntity::class,
    ],
    version = 5,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class BookGptDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun folderDao(): FolderDao
    abstract fun chunkDao(): ChunkDao
    abstract fun embeddingDao(): EmbeddingDao
    abstract fun chatDao(): ChatDao
}

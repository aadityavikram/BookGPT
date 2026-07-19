package com.bookgpt.android.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.bookgpt.android.data.db.BookGptDatabase
import com.bookgpt.android.data.openai.OpenAiApi
import com.bookgpt.android.data.settings.SettingsRepository
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): BookGptDatabase =
        Room.databaseBuilder(context, BookGptDatabase::class.java, "bookgpt.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideBookDao(db: BookGptDatabase) = db.bookDao()

    @Provides
    fun provideChunkDao(db: BookGptDatabase) = db.chunkDao()

    @Provides
    fun provideEmbeddingDao(db: BookGptDatabase) = db.embeddingDao()

    @Provides
    fun provideChatDao(db: BookGptDatabase) = db.chatDao()

    @Provides
    @Singleton
    @Named("booksDir")
    fun provideBooksDir(@ApplicationContext context: Context): File =
        File(context.filesDir, "books").also { it.mkdirs() }

    @Provides
    @Singleton
    fun provideOkHttp(settingsRepository: SettingsRepository): OkHttpClient {
        val authInterceptor = Interceptor { chain ->
            val apiKey = settingsRepository.getApiKey()
            val request = chain.request().newBuilder()
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .build()
            chain.proceed(request)
        }
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        return OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .addInterceptor(logging)
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    fun provideOpenAiApi(okHttpClient: OkHttpClient, json: Json): OpenAiApi {
        val contentType = "application/json".toMediaType()
        return Retrofit.Builder()
            .baseUrl("https://api.openai.com/v1/")
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory(contentType))
            .build()
            .create(OpenAiApi::class.java)
    }

    private val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `conversations` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `title` TEXT NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    `updatedAt` INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO `conversations` (`id`, `title`, `createdAt`, `updatedAt`)
                SELECT 1, 'Previous chat',
                       COALESCE(MIN(`createdAt`), CAST(strftime('%s','now') AS INTEGER) * 1000),
                       COALESCE(MAX(`createdAt`), CAST(strftime('%s','now') AS INTEGER) * 1000)
                FROM `chat_messages`
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE `chat_messages_new` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `conversationId` INTEGER NOT NULL,
                    `role` TEXT NOT NULL,
                    `content` TEXT NOT NULL,
                    `sourcesJson` TEXT,
                    `createdAt` INTEGER NOT NULL,
                    FOREIGN KEY(`conversationId`) REFERENCES `conversations`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO `chat_messages_new` (`id`, `conversationId`, `role`, `content`, `sourcesJson`, `createdAt`)
                SELECT `id`, 1, `role`, `content`, `sourcesJson`, `createdAt` FROM `chat_messages`
                """.trimIndent(),
            )
            db.execSQL("DROP TABLE `chat_messages`")
            db.execSQL("ALTER TABLE `chat_messages_new` RENAME TO `chat_messages`")
            db.execSQL("CREATE INDEX `index_chat_messages_conversationId` ON `chat_messages` (`conversationId`)")
        }
    }

    private val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `embeddings_new` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `chunkId` INTEGER NOT NULL,
                    `model` TEXT NOT NULL,
                    `quantized` BLOB NOT NULL,
                    `scale` REAL NOT NULL,
                    `norm` REAL NOT NULL,
                    FOREIGN KEY(`chunkId`) REFERENCES `chunks`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL("DROP TABLE IF EXISTS `embeddings`")
            db.execSQL("ALTER TABLE `embeddings_new` RENAME TO `embeddings`")
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_embeddings_chunkId` ON `embeddings` (`chunkId`)",
            )
            // Old float32 embeddings cannot be reused; force a re-index.
            db.execSQL("DELETE FROM `chunks`")
            db.execSQL(
                """
                UPDATE `books`
                SET status = 'PENDING',
                    errorMessage = NULL,
                    indexedAt = NULL,
                    fileHash = ''
                """,
            )
        }
    }

    private val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `conversations` ADD COLUMN `summary` TEXT")
            db.execSQL(
                "ALTER TABLE `conversations` ADD COLUMN `summarizedThroughMessageId` INTEGER",
            )
        }
    }
}

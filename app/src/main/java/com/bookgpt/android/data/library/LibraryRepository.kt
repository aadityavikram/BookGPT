package com.bookgpt.android.data.library

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.bookgpt.android.data.db.BookDao
import com.bookgpt.android.data.db.BookEntity
import com.bookgpt.android.data.db.BookGptDatabase
import com.bookgpt.android.data.db.BookStatus
import com.bookgpt.android.data.db.ChunkDao
import com.bookgpt.android.data.db.ChunkEntity
import com.bookgpt.android.data.db.EmbeddingDao
import com.bookgpt.android.data.db.EmbeddingEntity
import com.bookgpt.android.data.openai.OpenAiClient
import com.bookgpt.android.data.openai.FailureCategory
import com.bookgpt.android.data.openai.OperationFailure
import com.bookgpt.android.data.settings.SettingsRepository
import com.bookgpt.android.data.storage.BookStorage
import com.bookgpt.android.domain.Config
import com.bookgpt.android.domain.chunking.Chunker
import com.bookgpt.android.domain.loader.BookLoader
import com.bookgpt.android.domain.retrieve.VectorQuantizer
import com.bookgpt.android.worker.IndexingWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

data class IndexProgress(
    val bookTitle: String,
    val stage: String,
    val percent: Int? = null,
)

@Singleton
class LibraryRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: BookGptDatabase,
    private val bookDao: BookDao,
    private val chunkDao: ChunkDao,
    private val embeddingDao: EmbeddingDao,
    private val bookLoader: BookLoader,
    private val openAiClient: OpenAiClient,
    private val settingsRepository: SettingsRepository,
    private val bookStorage: BookStorage,
    @Named("booksDir") private val booksDir: File,
) {
    fun observeBooks(): Flow<List<BookEntity>> = bookDao.observeBooks()

    suspend fun importUris(uris: List<Uri>): List<Long> = withContext(Dispatchers.IO) {
        val ids = mutableListOf<Long>()
        for (uri in uris) {
            val name = queryDisplayName(uri) ?: continue
            val ext = name.substringAfterLast('.', "").lowercase()
            if (ext !in Config.SUPPORTED_EXTENSIONS) continue

            val mimeType = context.contentResolver.getType(uri) ?: mimeTypeFor(ext)
            val stored = bookStorage.import(uri, name, mimeType)
            val relativePath = stored.uri.toString()
            val existing = bookDao.getByRelativePath(relativePath)
            val book = BookEntity(
                id = existing?.id ?: 0,
                title = stored.filename.substringBeforeLast('.'),
                displayTitle = stored.filename.substringBeforeLast('.'),
                author = null,
                isbn = null,
                filename = stored.filename,
                relativePath = relativePath,
                fileHash = "",
                format = ext,
                status = BookStatus.PENDING,
            )
            val id = bookDao.upsert(book)
            ids += id
            enqueueIndexing(id)
        }
        ids
    }

    fun enqueueIndexing(bookId: Long) {
        val request = OneTimeWorkRequestBuilder<IndexingWorker>()
            .setInputData(workDataOf(IndexingWorker.KEY_BOOK_ID to bookId))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "index-book-$bookId",
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    suspend fun reindexBook(bookId: Long) {
        val book = bookDao.getById(bookId) ?: return
        bookDao.upsert(
            book.copy(
                status = BookStatus.PENDING,
                errorMessage = null,
                indexedAt = null,
                fileHash = "",
            ),
        )
        enqueueIndexing(bookId)
    }

    suspend fun replaceBook(bookId: Long, source: Uri) = withContext(Dispatchers.IO) {
        val existing = bookDao.getById(bookId) ?: error("Book not found")
        val name = queryDisplayName(source) ?: error("Could not read the selected filename")
        val extension = name.substringAfterLast('.', "").lowercase()
        require(extension in Config.SUPPORTED_EXTENSIONS) {
            "Choose a TXT, PDF, or EPUB file"
        }
        val mimeType = context.contentResolver.getType(source) ?: mimeTypeFor(extension)
        val stored = bookStorage.import(source, name, mimeType)
        try {
            bookDao.upsert(
                existing.copy(
                    title = stored.filename.substringBeforeLast('.'),
                    displayTitle = stored.filename.substringBeforeLast('.'),
                    author = null,
                    isbn = null,
                    filename = stored.filename,
                    relativePath = stored.uri.toString(),
                    fileHash = "",
                    format = extension,
                    status = BookStatus.PENDING,
                    errorMessage = null,
                    indexedAt = null,
                ),
            )
        } catch (error: Exception) {
            bookStorage.delete(stored.uri.toString())
            throw error
        }

        if (existing.relativePath.startsWith("content://")) {
            bookStorage.delete(existing.relativePath)
        } else {
            File(booksDir, existing.relativePath).takeIf { it.exists() }?.delete()
        }
        enqueueIndexing(bookId)
    }

    suspend fun indexBook(
        bookId: Long,
        onProgress: suspend (IndexProgress) -> Unit = {},
    ) = withContext(Dispatchers.IO) {
        val book = bookDao.getById(bookId) ?: return@withContext
        val isDocumentUri = book.relativePath.startsWith("content://")
        var materializedFile: File? = null

        suspend fun report(stage: String, percent: Int? = null) {
            bookDao.updateStatus(bookId, BookStatus.INDEXING, errorMessage = stage)
            onProgress(IndexProgress(book.displayTitle, stage, percent))
        }

        try {
            report("Preparing book…", 0)
            val file = if (isDocumentUri) {
                bookStorage.materialize(book.relativePath, book.filename).also {
                    materializedFile = it
                }
            } else {
                File(booksDir, book.relativePath)
            }
            if (!file.exists()) {
                throw OperationFailure(
                    category = FailureCategory.BOOK_FILE,
                    message = "The book file is missing. Replace the file and try again.",
                    retryable = false,
                )
            }

            val fileHash = md5(file)
            if (book.status == BookStatus.INDEXED && book.fileHash == fileHash) {
                bookDao.updateStatus(bookId, BookStatus.INDEXED, indexedAt = book.indexedAt ?: System.currentTimeMillis())
                return@withContext
            }

            report("Extracting text…", 10)
            val document = bookLoader.loadBook(file, file.parentFile ?: booksDir).copy(
                filename = book.filename,
                relativePath = book.relativePath,
            )
            val ambiguous = bookDao.countByTitle(document.title) > 1 ||
                bookDao.getBooks().count { it.id != bookId && it.title == document.title } > 0
            val displayTitle = bookLoader.makeDisplayTitle(
                title = document.title,
                author = document.author,
                isbn = document.isbn,
                relativePath = document.relativePath,
                ambiguous = ambiguous,
            )
            report("Splitting text into passages…", 25)
            val chunks = Chunker.chunkSegments(document.segments)
            if (chunks.isEmpty()) {
                throw IllegalArgumentException("No chunks produced for ${document.filename}")
            }

            val embeddings = openAiClient.embed(chunks.map { it.text }) { completed, total ->
                val percent = if (total == 0) 35 else 35 + (completed * 55 / total)
                report("Creating embeddings ($completed/$total batches)…", percent)
            }
            if (embeddings.size != chunks.size) {
                throw IllegalStateException("Embedding count mismatch")
            }

            report("Saving index…", 95)
            database.withTransaction {
                embeddingDao.deleteByBookId(bookId)
                chunkDao.deleteByBookId(bookId)
                val chunkEntities = chunks.map {
                    ChunkEntity(
                        bookId = bookId,
                        chunkIndex = it.chunkIndex,
                        chapter = it.chapter,
                        page = it.page,
                        text = it.text,
                    )
                }
                val chunkIds = chunkDao.insertAll(chunkEntities)
                val embeddingEntities = chunkIds.mapIndexed { index, chunkId ->
                    val quantized = VectorQuantizer.quantize(embeddings[index])
                    EmbeddingEntity(
                        chunkId = chunkId,
                        model = settingsRepository.getEmbeddingModel(),
                        quantized = quantized.values,
                        scale = quantized.scale,
                        norm = quantized.norm,
                    )
                }
                embeddingDao.insertAll(embeddingEntities)
                bookDao.updateStatus(
                    bookId = bookId,
                    status = BookStatus.INDEXED,
                    errorMessage = null,
                    indexedAt = System.currentTimeMillis(),
                    fileHash = fileHash,
                    displayTitle = displayTitle,
                    title = document.title,
                    author = document.author,
                    isbn = document.isbn,
                )
            }
            onProgress(IndexProgress(book.displayTitle, "Indexing complete", 100))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val failure = indexingFailure(error)
            bookDao.updateStatus(
                bookId = bookId,
                status = BookStatus.FAILED,
                errorMessage = failure.message,
            )
            throw failure
        } finally {
            materializedFile?.delete()
        }
    }

    suspend fun markIndexingRetry(bookId: Long, message: String) {
        bookDao.updateStatus(bookId, BookStatus.PENDING, errorMessage = message)
    }

    suspend fun markIndexingFailed(bookId: Long, message: String) {
        bookDao.updateStatus(bookId, BookStatus.FAILED, errorMessage = message)
    }

    suspend fun deleteBook(bookId: Long) = withContext(Dispatchers.IO) {
        val book = bookDao.getById(bookId) ?: return@withContext
        if (book.relativePath.startsWith("content://")) {
            bookStorage.delete(book.relativePath)
        } else {
            File(booksDir, book.relativePath).takeIf { it.exists() }?.delete()
        }
        bookDao.deleteById(bookId)
        if (settingsRepository.activeBookTitle.value == book.displayTitle) {
            settingsRepository.setActiveBook(null)
        }
    }

    suspend fun reindexAll() {
        val books = bookDao.getBooks()
        for (book in books) {
            bookDao.updateStatus(book.id, BookStatus.PENDING, errorMessage = null)
            enqueueIndexing(book.id)
        }
    }

    suspend fun clearIndex() = withContext(Dispatchers.IO) {
        database.withTransaction {
            embeddingDao.deleteAll()
            chunkDao.deleteAll()
            bookDao.markAllPending()
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) return cursor.getString(index)
                }
            }
        return uri.lastPathSegment?.substringAfterLast('/')
    }

    private fun mimeTypeFor(extension: String): String = when (extension) {
        "txt" -> "text/plain"
        "pdf" -> "application/pdf"
        "epub" -> "application/epub+zip"
        else -> "application/octet-stream"
    }

    private fun indexingFailure(error: Exception): OperationFailure {
        if (error is OperationFailure) return error
        return when (error) {
            is IllegalArgumentException -> OperationFailure(
                category = FailureCategory.BOOK_FILE,
                message = error.message ?: "The book contains no readable text.",
                retryable = false,
                cause = error,
            )
            is IOException, is SecurityException -> OperationFailure(
                category = FailureCategory.BOOK_FILE,
                message = "BookGPT could not read the book file. Replace it and try again.",
                retryable = false,
                cause = error,
            )
            else -> OperationFailure(
                category = FailureCategory.UNKNOWN,
                message = "BookGPT could not process this book.",
                retryable = false,
                cause = error,
            )
        }
    }

    private fun md5(file: File): String {
        val digest = MessageDigest.getInstance("MD5")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

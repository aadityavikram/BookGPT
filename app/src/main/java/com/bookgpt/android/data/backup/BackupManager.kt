package com.bookgpt.android.data.backup

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Process
import android.os.SystemClock
import com.bookgpt.android.data.db.BookDao
import com.bookgpt.android.data.db.BookEntity
import com.bookgpt.android.data.db.BookGptDatabase
import com.bookgpt.android.data.db.ChatDao
import com.bookgpt.android.data.db.ChatMessageEntity
import com.bookgpt.android.data.db.ConversationEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BackupManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: BookGptDatabase,
    private val bookDao: BookDao,
    private val chatDao: ChatDao,
    private val json: Json,
) {
    suspend fun export(destination: Uri) = withContext(Dispatchers.IO) {
        val snapshot = File(context.cacheDir, "bookgpt-backup.db")
        snapshot.delete()
        val escapedPath = snapshot.absolutePath.replace("'", "''")
        val sqlite = database.openHelper.writableDatabase
        sqlite.query("PRAGMA wal_checkpoint(FULL)").close()
        runCatching {
            sqlite.execSQL("VACUUM INTO '$escapedPath'")
        }.getOrElse {
            // VACUUM INTO is unavailable on some older Android SQLite builds.
            context.getDatabasePath("bookgpt.db").copyTo(snapshot, overwrite = true)
        }

        val books = bookDao.getBooks().map(BackupBook::from)
        val chats = BackupChats(
            conversations = chatDao.getConversations().map(BackupConversation::from),
            messages = chatDao.getAllMessages().map(BackupMessage::from),
        )
        val manifest = BackupManifest(
            createdAt = System.currentTimeMillis(),
            databaseVersion = DATABASE_VERSION,
            bookCount = books.size,
            conversationCount = chats.conversations.size,
            includesApiKey = false,
        )

        try {
            val output = context.contentResolver.openOutputStream(destination, "w")
                ?: error("Could not open the backup destination")
            ZipOutputStream(output.buffered()).use { zip ->
                zip.putNextEntry(ZipEntry(DATABASE_ENTRY))
                snapshot.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
                zip.writeJson("manifest.json", json.encodeToString(manifest))
                zip.writeJson("books.json", json.encodeToString(books))
                zip.writeJson("chats.json", json.encodeToString(chats))
            }
        } finally {
            snapshot.delete()
        }
    }

    suspend fun restore(source: Uri) = withContext(Dispatchers.IO) {
        val restored = File(context.cacheDir, "bookgpt-restore.db")
        restored.delete()
        try {
            val input = context.contentResolver.openInputStream(source)
                ?: error("Could not open the selected backup")
            ZipInputStream(input.buffered()).use { zip ->
                var foundDatabase = false
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.name == DATABASE_ENTRY) {
                        restored.outputStream().use { output ->
                            copyLimited(zip, output, MAX_DATABASE_BYTES)
                        }
                        foundDatabase = true
                    }
                    zip.closeEntry()
                }
                if (!foundDatabase) error("This is not a BookGPT backup")
            }

            validateDatabase(restored)
            replaceDatabaseContents(restored)
            scheduleRestart()
        } finally {
            restored.delete()
        }
    }

    private fun validateDatabase(file: File) {
        val restored = SQLiteDatabase.openDatabase(
            file.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY,
        )
        restored.use { db ->
            val version = db.rawQuery("PRAGMA user_version", null).use { cursor ->
                check(cursor.moveToFirst())
                cursor.getInt(0)
            }
            require(version == DATABASE_VERSION) {
                "Unsupported BookGPT database version: $version"
            }
            val integrity = db.rawQuery("PRAGMA integrity_check", null).use { cursor ->
                cursor.moveToFirst()
                cursor.getString(0)
            }
            require(integrity.equals("ok", ignoreCase = true)) {
                "The backup database is damaged"
            }
            for (table in REQUIRED_TABLES) {
                val exists = db.rawQuery(
                    "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?",
                    arrayOf(table),
                ).use { it.moveToFirst() }
                require(exists) { "Backup is missing $table" }
            }
        }
    }

    private fun replaceDatabaseContents(restored: File) {
        val sqlite = database.openHelper.writableDatabase
        val escapedPath = restored.absolutePath.replace("'", "''")
        sqlite.execSQL("ATTACH DATABASE '$escapedPath' AS restored")
        try {
            sqlite.beginTransaction()
            try {
                sqlite.execSQL("DELETE FROM chat_messages")
                sqlite.execSQL("DELETE FROM conversations")
                sqlite.execSQL("DELETE FROM embeddings")
                sqlite.execSQL("DELETE FROM chunks")
                sqlite.execSQL("DELETE FROM books")
                sqlite.execSQL("INSERT INTO books SELECT * FROM restored.books")
                sqlite.execSQL("INSERT INTO chunks SELECT * FROM restored.chunks")
                sqlite.execSQL("INSERT INTO embeddings SELECT * FROM restored.embeddings")
                sqlite.execSQL("INSERT INTO conversations SELECT * FROM restored.conversations")
                sqlite.execSQL("INSERT INTO chat_messages SELECT * FROM restored.chat_messages")
                sqlite.setTransactionSuccessful()
            } finally {
                sqlite.endTransaction()
            }
        } finally {
            sqlite.execSQL("DETACH DATABASE restored")
        }
    }

    private fun scheduleRestart() {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: return
        launchIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pendingIntent = PendingIntent.getActivity(
            context,
            42,
            launchIntent,
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarm.set(
            AlarmManager.ELAPSED_REALTIME,
            SystemClock.elapsedRealtime() + 500,
            pendingIntent,
        )
        Process.killProcess(Process.myPid())
    }

    private fun ZipOutputStream.writeJson(name: String, value: String) {
        putNextEntry(ZipEntry(name))
        write(value.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun copyLimited(
        input: java.io.InputStream,
        output: java.io.OutputStream,
        maxBytes: Long,
    ) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= maxBytes) { "Backup database is too large" }
            output.write(buffer, 0, count)
        }
    }

    @Serializable
    private data class BackupManifest(
        val format: String = "BookGPT Backup",
        val formatVersion: Int = 1,
        val createdAt: Long,
        val databaseVersion: Int,
        val bookCount: Int,
        val conversationCount: Int,
        val includesApiKey: Boolean,
    )

    @Serializable
    private data class BackupBook(
        val id: Long,
        val title: String,
        val displayTitle: String,
        val author: String?,
        val filename: String,
        val sourceUri: String,
        val format: String,
        val status: String,
    ) {
        companion object {
            fun from(book: BookEntity) = BackupBook(
                book.id,
                book.title,
                book.displayTitle,
                book.author,
                book.filename,
                book.relativePath,
                book.format,
                book.status.name,
            )
        }
    }

    @Serializable
    private data class BackupChats(
        val conversations: List<BackupConversation>,
        val messages: List<BackupMessage>,
    )

    @Serializable
    private data class BackupConversation(
        val id: Long,
        val title: String,
        val createdAt: Long,
        val updatedAt: Long,
        val summary: String? = null,
        val summarizedThroughMessageId: Long? = null,
    ) {
        companion object {
            fun from(value: ConversationEntity) =
                BackupConversation(
                    value.id,
                    value.title,
                    value.createdAt,
                    value.updatedAt,
                    value.summary,
                    value.summarizedThroughMessageId,
                )
        }
    }

    @Serializable
    private data class BackupMessage(
        val id: Long,
        val conversationId: Long,
        val role: String,
        val content: String,
        val sourcesJson: String?,
        val createdAt: Long,
    ) {
        companion object {
            fun from(value: ChatMessageEntity) = BackupMessage(
                value.id,
                value.conversationId,
                value.role,
                value.content,
                value.sourcesJson,
                value.createdAt,
            )
        }
    }

    companion object {
        private const val DATABASE_ENTRY = "bookgpt.db"
        private const val DATABASE_VERSION = 4
        private const val MAX_DATABASE_BYTES = 2L * 1024 * 1024 * 1024
        private val REQUIRED_TABLES = listOf(
            "books",
            "chunks",
            "embeddings",
            "conversations",
            "chat_messages",
        )
    }
}

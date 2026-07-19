package com.bookgpt.android.data.storage

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.bookgpt.android.data.settings.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BookStorage @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
) {
    fun setRoot(treeUri: Uri) {
        settings.setLibraryTreeUri(treeUri)
    }

    fun displayPath(): String? {
        val tree = settings.libraryTreeUri.value ?: return null
        val root = DocumentFile.fromTreeUri(context, tree) ?: return null
        return if (root.name.equals(FOLDER_NAME, ignoreCase = true)) {
            root.name
        } else {
            "${root.name ?: "Selected folder"}/$FOLDER_NAME"
        }
    }

    suspend fun import(source: Uri, requestedName: String, mimeType: String): StoredBook =
        withContext(Dispatchers.IO) {
            val folder = bookFolder()
            val name = uniqueName(folder, sanitize(requestedName))
            val destination = folder.createFile(mimeType, name)
                ?: error("Could not create $name in the BookGPT folder")
            try {
                context.contentResolver.openInputStream(source)?.use { input ->
                    context.contentResolver.openOutputStream(destination.uri, "w")?.use { output ->
                        input.copyTo(output)
                    } ?: error("Could not write $name")
                } ?: error("Could not read $requestedName")
            } catch (error: Exception) {
                destination.delete()
                throw error
            }
            StoredBook(destination.uri, destination.name ?: name)
        }

    suspend fun materialize(uriString: String, filename: String): File =
        withContext(Dispatchers.IO) {
            val uri = Uri.parse(uriString)
            val suffix = filename.substringAfterLast('.', "").let { if (it.isBlank()) "" else ".$it" }
            val cache = File.createTempFile("bookgpt_", suffix, context.cacheDir)
            context.contentResolver.openInputStream(uri)?.use { input ->
                cache.outputStream().use { output -> input.copyTo(output) }
            } ?: error("The selected BookGPT file is no longer available")
            cache
        }

    suspend fun delete(uriString: String): Boolean = withContext(Dispatchers.IO) {
        DocumentFile.fromSingleUri(context, Uri.parse(uriString))?.delete() ?: false
    }

    private fun bookFolder(): DocumentFile {
        val treeUri = settings.libraryTreeUri.value
            ?: error("Choose a storage folder in Settings first")
        val selected = DocumentFile.fromTreeUri(context, treeUri)
            ?: error("The selected storage folder is unavailable")
        if (selected.name.equals(FOLDER_NAME, ignoreCase = true)) return selected
        return selected.findFile(FOLDER_NAME)
            ?: selected.createDirectory(FOLDER_NAME)
            ?: error("Could not create the BookGPT folder")
    }

    private fun uniqueName(folder: DocumentFile, requestedName: String): String {
        if (folder.findFile(requestedName) == null) return requestedName
        val stem = requestedName.substringBeforeLast('.', requestedName)
        val extension = requestedName.substringAfterLast('.', "")
        var counter = 1
        while (true) {
            val candidate = if (extension.isBlank()) "$stem ($counter)" else "$stem ($counter).$extension"
            if (folder.findFile(candidate) == null) return candidate
            counter += 1
        }
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("""[\\/:*?"<>|]+"""), "_").ifBlank { "book" }

    data class StoredBook(val uri: Uri, val filename: String)

    companion object {
        const val FOLDER_NAME = "BookGPT"
    }
}

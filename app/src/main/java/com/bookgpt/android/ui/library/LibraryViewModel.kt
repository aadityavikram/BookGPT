package com.bookgpt.android.ui.library

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bookgpt.android.data.library.LibraryRepository
import com.bookgpt.android.data.db.BookEntity
import com.bookgpt.android.data.db.FolderEntity
import com.bookgpt.android.data.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LibraryUiState(
    val books: List<BookEntity> = emptyList(),
    val folders: List<FolderEntity> = emptyList(),
    val collapsedFolderIds: Set<Long> = emptySet(),
    val hasApiKey: Boolean = false,
    val message: String? = null,
    val isImporting: Boolean = false,
    val hasLibraryFolder: Boolean = false,
)

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val message = MutableStateFlow<String?>(null)
    private val importing = MutableStateFlow(false)
    private val collapsedFolderIds = MutableStateFlow<Set<Long>>(emptySet())
    private val libraryContent = combine(
        libraryRepository.observeBooks(),
        libraryRepository.observeFolders(),
        collapsedFolderIds,
    ) { books, folders, collapsed -> Triple(books, folders, collapsed) }

    val uiState: StateFlow<LibraryUiState> = combine(
        libraryContent,
        settingsRepository.hasApiKey,
        message,
        importing,
        settingsRepository.libraryTreeUri,
    ) { (books, folders, collapsed), hasKey, msg, isImporting, treeUri ->
        LibraryUiState(
            books = books,
            folders = folders,
            collapsedFolderIds = collapsed,
            hasApiKey = hasKey,
            message = msg,
            isImporting = isImporting,
            hasLibraryFolder = treeUri != null,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    fun toggleFolderCollapsed(folderId: Long) {
        collapsedFolderIds.value =
            if (folderId in collapsedFolderIds.value) {
                collapsedFolderIds.value - folderId
            } else {
                collapsedFolderIds.value + folderId
            }
    }

    fun importBooks(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            importing.value = true
            try {
                val ids = libraryRepository.importUris(uris)
                message.value = if (ids.isEmpty()) {
                    "No supported books found (.txt, .pdf, .epub)."
                } else {
                    "Imported ${ids.size} book(s). Indexing started."
                }
            } catch (error: Exception) {
                message.value = error.message ?: "Import failed"
            } finally {
                importing.value = false
            }
        }
    }

    fun deleteBook(bookId: Long) {
        viewModelScope.launch {
            libraryRepository.deleteBook(bookId)
            message.value = "Book deleted"
        }
    }

    fun deleteBooks(bookIds: Set<Long>) {
        if (bookIds.isEmpty()) return
        viewModelScope.launch {
            bookIds.forEach { libraryRepository.deleteBook(it) }
            message.value = "Deleted ${bookIds.size} book(s)"
        }
    }

    fun retryIndex(bookId: Long) {
        viewModelScope.launch {
            libraryRepository.reindexBook(bookId)
            message.value = "Re-indexing queued"
        }
    }

    fun replaceBook(bookId: Long, uri: Uri) {
        viewModelScope.launch {
            importing.value = true
            try {
                libraryRepository.replaceBook(bookId, uri)
                message.value = "Replacement uploaded. Re-indexing started."
            } catch (error: Exception) {
                message.value = error.message ?: "Could not replace book"
            } finally {
                importing.value = false
            }
        }
    }

    fun reindexAll() {
        viewModelScope.launch {
            libraryRepository.reindexAll()
            message.value = "Re-indexing all books"
        }
    }

    fun clearIndex() {
        viewModelScope.launch {
            libraryRepository.clearIndex()
            message.value = "Index cleared. Source books remain in the BookGPT folder."
        }
    }

    fun createFolder(name: String) {
        viewModelScope.launch {
            try {
                libraryRepository.createFolder(name)
                message.value = "Folder created"
            } catch (error: Exception) {
                message.value = error.message ?: "Could not create folder"
            }
        }
    }

    fun deleteFolder(folderId: Long) {
        viewModelScope.launch {
            libraryRepository.deleteFolder(folderId)
            message.value = "Folder deleted. Its books are now unfiled."
        }
    }

    fun moveBook(bookId: Long, folderId: Long?) {
        viewModelScope.launch {
            try {
                libraryRepository.moveIndexedBook(bookId, folderId)
                message.value = if (folderId == null) "Book moved to Unfiled" else "Book moved"
            } catch (error: Exception) {
                message.value = error.message ?: "Could not move book"
            }
        }
    }

    fun moveBooks(bookIds: Set<Long>, folderId: Long?) {
        if (bookIds.isEmpty()) return
        viewModelScope.launch {
            try {
                bookIds.forEach { libraryRepository.moveIndexedBook(it, folderId) }
                message.value = "Moved ${bookIds.size} book(s)"
            } catch (error: Exception) {
                message.value = error.message ?: "Could not move books"
            }
        }
    }

    fun clearMessage() {
        message.value = null
    }
}

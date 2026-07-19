package com.bookgpt.android.ui.library

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bookgpt.android.data.library.LibraryRepository
import com.bookgpt.android.data.db.BookEntity
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

    val uiState: StateFlow<LibraryUiState> = combine(
        libraryRepository.observeBooks(),
        settingsRepository.hasApiKey,
        message,
        importing,
        settingsRepository.libraryTreeUri,
    ) { books, hasKey, msg, isImporting, treeUri ->
        LibraryUiState(
            books = books,
            hasApiKey = hasKey,
            message = msg,
            isImporting = isImporting,
            hasLibraryFolder = treeUri != null,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

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

    fun clearMessage() {
        message.value = null
    }
}

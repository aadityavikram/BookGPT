package com.bookgpt.android.ui.library

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bookgpt.android.data.db.BookEntity
import com.bookgpt.android.data.db.BookStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var replacingBookId by remember { mutableStateOf<Long?>(null) }
    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris: List<Uri> ->
        viewModel.importBooks(uris)
    }
    val replacementPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        val bookId = replacingBookId
        replacingBookId = null
        if (uri != null && bookId != null) {
            viewModel.replaceBook(bookId, uri)
        }
    }

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbar.showSnackbar(message)
        viewModel.clearMessage()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("BookGPT", fontWeight = FontWeight.Bold)
                        Text(
                            "Your library",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = viewModel::reindexAll,
                        enabled = state.books.isNotEmpty(),
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Re-index all")
                    }
                    IconButton(
                        onClick = viewModel::clearIndex,
                        enabled = state.books.isNotEmpty(),
                    ) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = "Clear index")
                    }
                },
            )
        },
        floatingActionButton = {
            if (state.hasLibraryFolder) {
                FloatingActionButton(
                    onClick = {
                        picker.launch(
                            arrayOf(
                                "text/plain",
                                "application/pdf",
                                "application/epub+zip",
                                "*/*",
                            ),
                        )
                    },
                ) {
                    Icon(Icons.Default.UploadFile, contentDescription = "Import books")
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            if (!state.hasApiKey) {
                Text(
                    "Add your OpenAI API key in Settings before indexing or chatting.",
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
            if (!state.hasLibraryFolder) {
                Text(
                    "Choose a phone folder in Settings. A BookGPT folder will be created inside it.",
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }

            if (state.isImporting) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(vertical = 8.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    Text("Importing...")
                }
            }

            if (state.books.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = 48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("No books yet", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Import TXT, PDF, or EPUB files to start chatting with your library.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(state.books, key = { it.id }) { book ->
                        BookRow(
                            book = book,
                            onDelete = { viewModel.deleteBook(book.id) },
                            onRetry = { viewModel.retryIndex(book.id) },
                            onReplace = {
                                replacingBookId = book.id
                                replacementPicker.launch(
                                    arrayOf(
                                        "text/plain",
                                        "application/pdf",
                                        "application/epub+zip",
                                        "*/*",
                                    ),
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BookRow(
    book: BookEntity,
    onDelete: () -> Unit,
    onRetry: () -> Unit,
    onReplace: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(book.displayTitle, fontWeight = FontWeight.SemiBold)
                val author = book.author?.takeIf { it.isNotBlank() }
                Text(
                    buildString {
                        append(book.format.uppercase())
                        append(" · ")
                        append(statusLabel(book.status))
                        if (author != null) {
                            append(" · ")
                            append(author)
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                book.errorMessage?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            if (book.status != BookStatus.INDEXING) {
                IconButton(onClick = onRetry) {
                    Icon(Icons.Default.Refresh, contentDescription = "Re-index book")
                }
                IconButton(onClick = onReplace) {
                    Icon(Icons.Default.UploadFile, contentDescription = "Replace book file")
                }
            }
            if (book.status == BookStatus.INDEXING) {
                CircularProgressIndicator(modifier = Modifier.size(22.dp))
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete book")
            }
        }
    }
}

private fun statusLabel(status: BookStatus): String = when (status) {
    BookStatus.PENDING -> "Pending"
    BookStatus.INDEXING -> "Indexing"
    BookStatus.INDEXED -> "Indexed"
    BookStatus.FAILED -> "Failed"
}

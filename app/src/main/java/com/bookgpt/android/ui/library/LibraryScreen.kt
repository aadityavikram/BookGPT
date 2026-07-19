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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.bookgpt.android.data.db.FolderEntity

private enum class LibraryConfirmation {
    REINDEX_ALL,
    CLEAR_INDEX,
    DELETE_SELECTED,
}

private const val UNFILED_FOLDER_ID = -1L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var replacingBookId by remember { mutableStateOf<Long?>(null) }
    var showCreateFolder by remember { mutableStateOf(false) }
    var folderName by remember { mutableStateOf("") }
    var movingBook by remember { mutableStateOf<BookEntity?>(null) }
    var confirmation by remember { mutableStateOf<LibraryConfirmation?>(null) }
    var selectedBookIds by remember { mutableStateOf(emptySet<Long>()) }
    var showBulkMove by remember { mutableStateOf(false) }
    var folderToDelete by remember { mutableStateOf<FolderEntity?>(null) }
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
    LaunchedEffect(state.books) {
        selectedBookIds = selectedBookIds.intersect(state.books.mapTo(mutableSetOf()) { it.id })
    }

    if (showCreateFolder) {
        AlertDialog(
            onDismissRequest = {
                showCreateFolder = false
                folderName = ""
            },
            title = { Text("New folder") },
            text = {
                OutlinedTextField(
                    value = folderName,
                    onValueChange = { folderName = it },
                    label = { Text("Folder name") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.createFolder(folderName)
                        showCreateFolder = false
                        folderName = ""
                    },
                    enabled = folderName.isNotBlank(),
                ) {
                    Text("Create")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showCreateFolder = false
                        folderName = ""
                    },
                ) {
                    Text("Cancel")
                }
            },
        )
    }

    folderToDelete?.let { folder ->
        val bookCount = state.books.count { it.folderId == folder.id }
        AlertDialog(
            onDismissRequest = { folderToDelete = null },
            title = { Text("Delete ${folder.name}?") },
            text = {
                Text(
                    if (bookCount == 0) {
                        "This folder will be permanently deleted."
                    } else {
                        "This folder will be deleted. Its $bookCount book(s) will be moved to Unfiled."
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteFolder(folder.id)
                        folderToDelete = null
                    },
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { folderToDelete = null }) {
                    Text("Cancel")
                }
            },
        )
    }

    movingBook?.let { book ->
        AlertDialog(
            onDismissRequest = { movingBook = null },
            title = { Text("Move ${book.displayTitle}") },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                    item(key = "unfiled") {
                        TextButton(
                            onClick = {
                                viewModel.moveBook(book.id, null)
                                movingBook = null
                            },
                            enabled = book.folderId != null,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Unfiled", modifier = Modifier.fillMaxWidth())
                        }
                    }
                    items(state.folders, key = { it.id }) { folder ->
                        TextButton(
                            onClick = {
                                viewModel.moveBook(book.id, folder.id)
                                movingBook = null
                            },
                            enabled = book.folderId != folder.id,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(folder.name, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { movingBook = null }) {
                    Text("Cancel")
                }
            },
        )
    }

    if (showBulkMove) {
        AlertDialog(
            onDismissRequest = { showBulkMove = false },
            title = { Text("Move ${selectedBookIds.size} books") },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                    item(key = "bulk-unfiled") {
                        TextButton(
                            onClick = {
                                viewModel.moveBooks(selectedBookIds, null)
                                selectedBookIds = emptySet()
                                showBulkMove = false
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Unfiled", modifier = Modifier.fillMaxWidth())
                        }
                    }
                    items(state.folders, key = { "bulk-${it.id}" }) { folder ->
                        TextButton(
                            onClick = {
                                viewModel.moveBooks(selectedBookIds, folder.id)
                                selectedBookIds = emptySet()
                                showBulkMove = false
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(folder.name, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showBulkMove = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    confirmation?.let { action ->
        val title = when (action) {
            LibraryConfirmation.REINDEX_ALL -> "Re-index all books?"
            LibraryConfirmation.CLEAR_INDEX -> "Delete all indexes?"
            LibraryConfirmation.DELETE_SELECTED -> "Delete ${selectedBookIds.size} books?"
        }
        val message = when (action) {
            LibraryConfirmation.REINDEX_ALL ->
                "Every book will be indexed again. This uses the OpenAI API and may incur charges."
            LibraryConfirmation.CLEAR_INDEX ->
                "All generated indexes will be deleted. Your source books and folders will remain."
            LibraryConfirmation.DELETE_SELECTED ->
                "The selected books, their files, and their indexes will be permanently deleted."
        }
        val confirmLabel = when (action) {
            LibraryConfirmation.REINDEX_ALL -> "Re-index all"
            LibraryConfirmation.CLEAR_INDEX -> "Delete indexes"
            LibraryConfirmation.DELETE_SELECTED -> "Delete books"
        }
        AlertDialog(
            onDismissRequest = { confirmation = null },
            title = { Text(title) },
            text = { Text(message) },
            confirmButton = {
                TextButton(
                    onClick = {
                        when (action) {
                            LibraryConfirmation.REINDEX_ALL -> viewModel.reindexAll()
                            LibraryConfirmation.CLEAR_INDEX -> viewModel.clearIndex()
                            LibraryConfirmation.DELETE_SELECTED -> {
                                viewModel.deleteBooks(selectedBookIds)
                                selectedBookIds = emptySet()
                            }
                        }
                        confirmation = null
                    },
                ) {
                    Text(confirmLabel)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmation = null }) {
                    Text("Cancel")
                }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (selectedBookIds.isNotEmpty()) {
                        Text("${selectedBookIds.size} selected", fontWeight = FontWeight.Bold)
                    } else {
                        Column {
                            Text("BookGPT", fontWeight = FontWeight.Bold)
                            Text(
                                "Your library",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                actions = {
                    if (selectedBookIds.isNotEmpty()) {
                        val selectedBooks = state.books.filter { it.id in selectedBookIds }
                        IconButton(
                            onClick = { showBulkMove = true },
                            enabled = selectedBooks.all { it.status == BookStatus.INDEXED },
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.DriveFileMove,
                                contentDescription = "Move selected books",
                            )
                        }
                        IconButton(
                            onClick = { confirmation = LibraryConfirmation.DELETE_SELECTED },
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete selected books")
                        }
                        IconButton(onClick = { selectedBookIds = emptySet() }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear selection")
                        }
                    } else {
                        IconButton(onClick = { showCreateFolder = true }) {
                            Icon(Icons.Default.CreateNewFolder, contentDescription = "Create folder")
                        }
                        IconButton(
                            onClick = { confirmation = LibraryConfirmation.REINDEX_ALL },
                            enabled = state.books.isNotEmpty(),
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = "Re-index all")
                        }
                        IconButton(
                            onClick = { confirmation = LibraryConfirmation.CLEAR_INDEX },
                            enabled = state.books.isNotEmpty(),
                        ) {
                            Icon(Icons.Default.DeleteSweep, contentDescription = "Clear index")
                        }
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

            if (state.books.isEmpty() && state.folders.isEmpty()) {
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
                    val unfiledBooks = state.books.filter { it.folderId == null }
                    if (unfiledBooks.isNotEmpty()) {
                        item(key = "header-unfiled") {
                            FolderHeader(
                                name = "Unfiled",
                                bookCount = unfiledBooks.size,
                                collapsed = UNFILED_FOLDER_ID in state.collapsedFolderIds,
                                onToggle = {
                                    viewModel.toggleFolderCollapsed(UNFILED_FOLDER_ID)
                                },
                            )
                        }
                    }
                    if (UNFILED_FOLDER_ID !in state.collapsedFolderIds) {
                        items(unfiledBooks, key = { "book-${it.id}" }) { book ->
                            BookRow(
                                book = book,
                                selected = book.id in selectedBookIds,
                                onSelectionChange = { selected ->
                                    selectedBookIds = selectedBookIds.withSelection(book.id, selected)
                                },
                                onDelete = { viewModel.deleteBook(book.id) },
                                onRetry = { viewModel.retryIndex(book.id) },
                                onMove = { movingBook = book },
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
                    state.folders.forEach { folder ->
                        val folderBooks = state.books.filter { it.folderId == folder.id }
                        val collapsed = folder.id in state.collapsedFolderIds
                        item(key = "folder-${folder.id}") {
                            FolderHeader(
                                name = folder.name,
                                bookCount = folderBooks.size,
                                collapsed = collapsed,
                                onToggle = {
                                    viewModel.toggleFolderCollapsed(folder.id)
                                },
                                onDelete = { folderToDelete = folder },
                            )
                        }
                        if (folderBooks.isEmpty() && !collapsed) {
                            item(key = "empty-${folder.id}") {
                                Text(
                                    "Empty folder",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                                )
                            }
                        }
                        if (!collapsed) {
                            items(folderBooks, key = { "book-${it.id}" }) { book ->
                                BookRow(
                                    book = book,
                                    selected = book.id in selectedBookIds,
                                    onSelectionChange = { selected ->
                                        selectedBookIds = selectedBookIds.withSelection(book.id, selected)
                                    },
                                    onDelete = { viewModel.deleteBook(book.id) },
                                    onRetry = { viewModel.retryIndex(book.id) },
                                    onMove = { movingBook = book },
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
    }
}

@Composable
private fun FolderHeader(
    name: String,
    bookCount: Int,
    collapsed: Boolean,
    onToggle: () -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onToggle) {
            Icon(
                if (collapsed) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
                contentDescription = if (collapsed) "Expand $name" else "Collapse $name",
            )
        }
        Icon(
            Icons.Default.Folder,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            "$name ($bookCount)",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp),
        )
        if (onDelete != null) {
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete $name folder")
            }
        }
    }
}

@Composable
private fun BookRow(
    book: BookEntity,
    selected: Boolean,
    onSelectionChange: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onRetry: () -> Unit,
    onMove: () -> Unit,
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
            Checkbox(
                checked = selected,
                onCheckedChange = onSelectionChange,
            )
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
            if (book.status == BookStatus.INDEXED) {
                IconButton(onClick = onMove) {
                    Icon(Icons.AutoMirrored.Filled.DriveFileMove, contentDescription = "Move book")
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

private fun Set<Long>.withSelection(value: Long, selected: Boolean): Set<Long> =
    if (selected) this + value else this - value

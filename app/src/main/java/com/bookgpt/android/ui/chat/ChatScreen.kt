package com.bookgpt.android.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bookgpt.android.data.db.ConversationEntity
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    onOpenLibrary: () -> Unit = {},
    viewModel: ChatViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val snackbar = remember { SnackbarHostState() }
    var showHistory by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<ConversationEntity?>(null) }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(state.messages.size, state.streamingResponse.length, state.isSending) {
        val targetIndex = when {
            state.isSending -> state.messages.size
            state.messages.isNotEmpty() -> state.messages.lastIndex
            else -> null
        }
        if (targetIndex != null) {
            listState.scrollToItem(targetIndex, scrollOffset = Int.MAX_VALUE)
        }
    }

    LaunchedEffect(state.error) {
        val error = state.error ?: return@LaunchedEffect
        snackbar.showSnackbar(error)
        viewModel.clearError()
    }

    LaunchedEffect(Unit) {
        viewModel.refreshBooks()
    }

    fun submit() {
        keyboardController?.hide()
        focusManager.clearFocus()
        viewModel.send()
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(state.activeConversationTitle, fontWeight = FontWeight.Bold)
                        Text(
                            when {
                                !state.hasApiKey -> "API key required"
                                state.isSending -> "Thinking…"
                                else -> "Ask about your books"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showHistory = true }) {
                        Icon(Icons.Default.History, contentDescription = "Chat history")
                    }
                    IconButton(onClick = viewModel::newChat) {
                        Icon(Icons.Default.Add, contentDescription = "New chat")
                    }
                    IconButton(onClick = viewModel::clearChat) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = "Clear chat")
                    }
                },
            )
        },
        bottomBar = {
            ComposerBar(
                draft = state.draft,
                isSending = state.isSending,
                onDraftChange = viewModel::onDraftChange,
                onSend = ::submit,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            FocusSelector(
                books = state.books,
                activeBook = state.activeBook,
                onSelect = viewModel::setActiveBook,
            )

            if (state.messages.isEmpty() && !state.isSending) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        when {
                            !state.hasApiKey -> "Add your OpenAI API key in Settings first."
                            state.books.isEmpty() -> "Index a book in Library, then ask a question here."
                            else -> "Ask anything about your library."
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(state.messages, key = { it.id }) { message ->
                        MessageBubble(message)
                    }
                    if (state.isSending) {
                        item(key = "sending") {
                            MessageBubble(
                                ChatUiMessage(
                                    id = -1,
                                    role = "assistant",
                                    content = state.streamingResponse.ifBlank { "BookGPT is writing…" },
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    if (showHistory) {
        HistoryDialog(
            conversations = state.conversations,
            activeConversationId = state.activeConversationId,
            onSelect = {
                viewModel.selectConversation(it)
                showHistory = false
            },
            onDelete = viewModel::deleteConversation,
            onRename = {
                renameTarget = it
                showHistory = false
            },
            onNew = {
                viewModel.newChat()
                showHistory = false
            },
            onDismiss = { showHistory = false },
        )
    }
    renameTarget?.let { conversation ->
        RenameConversationDialog(
            conversation = conversation,
            onRename = {
                viewModel.renameConversation(conversation.id, it)
                renameTarget = null
            },
            onDismiss = { renameTarget = null },
        )
    }
    if (state.reindexRequired) {
        AlertDialog(
            onDismissRequest = viewModel::dismissReindexPrompt,
            title = { Text("Reindex required") },
            text = {
                Text(
                    "The selected embedding model does not match the existing book index. " +
                        "Re-index your books with the current model before chatting.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.dismissReindexPrompt()
                        onOpenLibrary()
                    },
                ) {
                    Text("Open Library")
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissReindexPrompt) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun ComposerBar(
    draft: String,
    isSending: Boolean,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.Bottom,
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = onDraftChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text("Message BookGPT") },
            minLines = 1,
            maxLines = 5,
            enabled = !isSending,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSend() }),
        )
        IconButton(
            onClick = onSend,
            enabled = draft.isNotBlank() && !isSending,
        ) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
        }
    }
}

@Composable
private fun HistoryDialog(
    conversations: List<ConversationEntity>,
    activeConversationId: Long?,
    onSelect: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onRename: (ConversationEntity) -> Unit,
    onNew: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Chat history") },
        text = {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                items(conversations, key = { it.id }) { conversation ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        TextButton(
                            onClick = { onSelect(conversation.id) },
                            modifier = Modifier.weight(1f),
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    conversation.title,
                                    fontWeight = if (conversation.id == activeConversationId) {
                                        FontWeight.Bold
                                    } else {
                                        FontWeight.Normal
                                    },
                                )
                                Text(
                                    DateFormat.getDateTimeInstance(
                                        DateFormat.SHORT,
                                        DateFormat.SHORT,
                                    ).format(Date(conversation.updatedAt)),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        IconButton(onClick = { onRename(conversation) }) {
                            Icon(Icons.Default.Edit, contentDescription = "Rename conversation")
                        }
                        IconButton(onClick = { onDelete(conversation.id) }) {
                            Icon(Icons.Default.DeleteSweep, contentDescription = "Delete conversation")
                        }
                    }
                    HorizontalDivider()
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onNew) {
                Text("New chat")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        },
    )
}

@Composable
private fun RenameConversationDialog(
    conversation: ConversationEntity,
    onRename: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by remember(conversation.id) { mutableStateOf(conversation.title) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename chat") },
        text = {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                singleLine = true,
                label = { Text("Chat name") },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onRename(title) },
                enabled = title.isNotBlank(),
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun FocusSelector(
    books: List<String>,
    activeBook: String?,
    onSelect: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val label = activeBook ?: "All books"
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
    ) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Focus: $label")
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                text = { Text("All books") },
                onClick = {
                    onSelect(null)
                    expanded = false
                },
            )
            books.forEach { title ->
                DropdownMenuItem(
                    text = { Text(title) },
                    onClick = {
                        onSelect(title)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun MessageBubble(message: ChatUiMessage) {
    val isUser = message.role == "user"
    val alignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
    val background = if (isUser) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
    } else {
        MaterialTheme.colorScheme.surface
    }

    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = alignment) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .clip(RoundedCornerShape(16.dp))
                .background(background)
                .padding(12.dp),
        ) {
            Text(
                if (isUser) "You" else "BookGPT",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(message.content, modifier = Modifier.padding(top = 4.dp))
            if (message.sourcesText.isNotBlank()) {
                Text(
                    message.sourcesText.trim(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

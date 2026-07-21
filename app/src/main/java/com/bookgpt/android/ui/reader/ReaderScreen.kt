package com.bookgpt.android.ui.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
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
import com.bookgpt.android.domain.tts.TtsVoiceOption

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    viewModel: ReaderViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scrollState = rememberScrollState()

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbar.showSnackbar(message)
        viewModel.clearMessage()
    }

    LaunchedEffect(state.chunkIndex, state.currentChunk?.id) {
        scrollState.scrollTo(0)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Reader", fontWeight = FontWeight.Bold)
                        Text(
                            state.selectedBook?.displayTitle ?: "Listen to your books",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
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
            BookSelector(
                books = state.books,
                selected = state.selectedBook,
                onSelect = viewModel::selectBook,
            )

            if (state.isLoading) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(vertical = 12.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    Text("Loading book…")
                }
            }

            when {
                state.books.isEmpty() -> {
                    EmptyReader(
                        title = "No indexed books",
                        body = "Import and index a book in Library, then come back to listen.",
                    )
                }
                state.selectedBook == null -> {
                    EmptyReader(
                        title = "Choose a book",
                        body = "Pick an indexed book above to start listening.",
                    )
                }
                state.chunks.isEmpty() && !state.isLoading -> {
                    EmptyReader(
                        title = "Nothing to read",
                        body = "This book has no text chunks. Re-index it in Library.",
                    )
                }
                else -> {
                    val chunk = state.currentChunk
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            state.progressLabel,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (state.canRestart) {
                            TextButton(onClick = viewModel::restart) {
                                Icon(
                                    Icons.Default.Replay,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Text("Start over", modifier = Modifier.padding(start = 4.dp))
                            }
                        }
                    }
                    LinearProgressIndicator(
                        progress = { state.progressFraction },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp, bottom = 8.dp),
                    )
                    state.resumeChunkIndex?.let { resumeAt ->
                        val resumeChapter = state.chunks.getOrNull(resumeAt)?.chapter
                            ?.takeIf { it.isNotBlank() }
                        OutlinedButton(
                            onClick = viewModel::resumeSavedPosition,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp),
                        ) {
                            Text(
                                buildString {
                                    append("Resume from section ${resumeAt + 1}")
                                    if (resumeChapter != null) {
                                        append(" · ")
                                        append(resumeChapter)
                                    }
                                },
                            )
                        }
                    }
                    if (chunk != null) {
                        if (chunk.chapter.isNotBlank()) {
                            Text(
                                chunk.chapter,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(bottom = 8.dp),
                            )
                        }
                        if (chunk.page > 0) {
                            Text(
                                "Page ${chunk.page}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 8.dp),
                            )
                        }
                        Text(
                            chunk.text,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .verticalScroll(scrollState),
                        )
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }

                    PlaybackControls(
                        playback = state.playback,
                        speechRate = state.speechRate,
                        voices = state.voices,
                        selectedVoiceLabel = state.selectedVoiceLabel,
                        selectedVoiceName = state.selectedVoiceName,
                        canSeek = state.chunks.isNotEmpty(),
                        atStart = state.chunkIndex <= 0,
                        atEnd = state.chunkIndex >= state.chunks.lastIndex,
                        onPrevious = viewModel::previousChunk,
                        onPlayPause = viewModel::playOrPause,
                        onStop = viewModel::stop,
                        onNext = viewModel::nextChunk,
                        onSpeechRateChange = viewModel::setSpeechRate,
                        onVoiceSelected = viewModel::selectVoice,
                    )
                }
            }
        }
    }
}

@Composable
private fun BookSelector(
    books: List<BookEntity>,
    selected: BookEntity?,
    onSelect: (Long) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val label = selected?.displayTitle ?: "Select a book"
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
    ) {
        OutlinedButton(
            onClick = { expanded = true },
            enabled = books.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(label)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            books.forEach { book ->
                DropdownMenuItem(
                    text = { Text(book.displayTitle) },
                    onClick = {
                        onSelect(book.id)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun PlaybackControls(
    playback: ReaderPlaybackState,
    speechRate: Float,
    voices: List<TtsVoiceOption>,
    selectedVoiceLabel: String,
    selectedVoiceName: String?,
    canSeek: Boolean,
    atStart: Boolean,
    atEnd: Boolean,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onStop: () -> Unit,
    onNext: () -> Unit,
    onSpeechRateChange: (Float) -> Unit,
    onVoiceSelected: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onPrevious, enabled = canSeek && !atStart) {
                Icon(Icons.Default.SkipPrevious, contentDescription = "Previous section")
            }
            FilledIconButton(
                onClick = onPlayPause,
                enabled = canSeek,
                modifier = Modifier.size(56.dp),
            ) {
                Icon(
                    if (playback == ReaderPlaybackState.PLAYING) {
                        Icons.Default.Pause
                    } else {
                        Icons.Default.PlayArrow
                    },
                    contentDescription = if (playback == ReaderPlaybackState.PLAYING) {
                        "Pause"
                    } else {
                        "Play"
                    },
                    modifier = Modifier.size(28.dp),
                )
            }
            IconButton(
                onClick = onStop,
                enabled = playback != ReaderPlaybackState.IDLE,
            ) {
                Icon(Icons.Default.Stop, contentDescription = "Stop")
            }
            IconButton(onClick = onNext, enabled = canSeek && !atEnd) {
                Icon(Icons.Default.SkipNext, contentDescription = "Next section")
            }
        }

        VoiceSelector(
            voices = voices,
            selectedLabel = selectedVoiceLabel,
            selectedName = selectedVoiceName,
            onSelect = onVoiceSelected,
        )

        Text(
            "Speed ${"%.1f".format(speechRate)}x",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        Slider(
            value = speechRate,
            onValueChange = onSpeechRateChange,
            valueRange = 0.5f..2f,
            steps = 5,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun VoiceSelector(
    voices: List<TtsVoiceOption>,
    selectedLabel: String,
    selectedName: String?,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
    ) {
        OutlinedButton(
            onClick = { expanded = true },
            enabled = voices.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                if (voices.isEmpty()) "Voice unavailable" else selectedLabel,
                maxLines = 1,
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            voices.forEach { voice ->
                DropdownMenuItem(
                    text = {
                        Text(
                            buildString {
                                append(voice.label)
                                if (voice.name == selectedName) append(" ✓")
                            },
                        )
                    },
                    onClick = {
                        onSelect(voice.name)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun EmptyReader(
    title: String,
    body: String,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            body,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

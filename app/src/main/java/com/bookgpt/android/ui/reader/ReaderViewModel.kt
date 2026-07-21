package com.bookgpt.android.ui.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bookgpt.android.data.db.BookDao
import com.bookgpt.android.data.db.BookEntity
import com.bookgpt.android.data.db.BookStatus
import com.bookgpt.android.data.db.ChunkDao
import com.bookgpt.android.data.db.ChunkEntity
import com.bookgpt.android.data.settings.SettingsRepository
import com.bookgpt.android.domain.reader.ReaderStartResolver
import com.bookgpt.android.domain.tts.BookTtsController
import com.bookgpt.android.domain.tts.TtsVoiceOption
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

enum class ReaderPlaybackState {
    IDLE,
    PLAYING,
    PAUSED,
}

data class ReaderUiState(
    val books: List<BookEntity> = emptyList(),
    val selectedBook: BookEntity? = null,
    val chunks: List<ChunkEntity> = emptyList(),
    val chunkIndex: Int = 0,
    val playback: ReaderPlaybackState = ReaderPlaybackState.IDLE,
    val speechRate: Float = 1f,
    val voices: List<TtsVoiceOption> = emptyList(),
    val selectedVoiceName: String? = null,
    val selectedVoiceLabel: String = "Default voice",
    val ttsReady: Boolean = false,
    val isLoading: Boolean = false,
    val resumeChunkIndex: Int? = null,
    val message: String? = null,
) {
    val currentChunk: ChunkEntity?
        get() = chunks.getOrNull(chunkIndex)

    val progressFraction: Float
        get() = if (chunks.isEmpty()) 0f else (chunkIndex + 1).toFloat() / chunks.size

    val progressLabel: String
        get() = if (chunks.isEmpty()) "No text" else "${chunkIndex + 1} / ${chunks.size}"

    val canRestart: Boolean
        get() = chunks.isNotEmpty() && chunkIndex > 0
}

@HiltViewModel
class ReaderViewModel @Inject constructor(
    private val bookDao: BookDao,
    private val chunkDao: ChunkDao,
    private val settingsRepository: SettingsRepository,
    private val tts: BookTtsController,
) : ViewModel() {

    private val selectedBookId = MutableStateFlow<Long?>(null)
    private val chunks = MutableStateFlow<List<ChunkEntity>>(emptyList())
    private val chunkIndex = MutableStateFlow(0)
    private val playback = MutableStateFlow(ReaderPlaybackState.IDLE)
    private val ttsReady = MutableStateFlow(false)
    private val isLoading = MutableStateFlow(false)
    private val resumeChunkIndex = MutableStateFlow<Int?>(null)
    private val message = MutableStateFlow<String?>(null)
    private val voices = MutableStateFlow<List<TtsVoiceOption>>(emptyList())
    private val selectedVoiceName = MutableStateFlow(settingsRepository.readerVoiceName.value)

    private val bookState = combine(
        bookDao.observeBooks(),
        selectedBookId,
        chunks,
        chunkIndex,
    ) { books, bookId, chunkList, index ->
        val indexed = books.filter { it.status == BookStatus.INDEXED }
        val selected = indexed.find { it.id == bookId }
        BookState(indexed, selected, chunkList, index)
    }

    private val playbackState = combine(
        playback,
        settingsRepository.readerSpeechRate,
        ttsReady,
        isLoading,
        message,
    ) { play, rate, ready, loading, msg ->
        PlaybackState(play, rate, ready, loading, msg)
    }

    private val voiceState = combine(voices, selectedVoiceName) { voiceList, selected ->
        VoiceState(voiceList, selected)
    }

    val uiState: StateFlow<ReaderUiState> = combine(
        bookState,
        playbackState,
        resumeChunkIndex,
        voiceState,
    ) { books, play, resumeAt, voice ->
        val selectedLabel = voice.voices.firstOrNull { it.name == voice.selectedName }?.label
            ?: voice.voices.firstOrNull()?.label
            ?: "Default voice"
        ReaderUiState(
            books = books.books,
            selectedBook = books.selected,
            chunks = books.chunks,
            chunkIndex = books.chunkIndex,
            playback = play.playback,
            speechRate = play.speechRate,
            voices = voice.voices,
            selectedVoiceName = voice.selectedName,
            selectedVoiceLabel = selectedLabel,
            ttsReady = play.ttsReady,
            isLoading = play.isLoading,
            resumeChunkIndex = resumeAt,
            message = play.message,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReaderUiState())

    init {
        tts.setListeners(
            onDone = { utteranceId ->
                viewModelScope.launch(Dispatchers.Main.immediate) {
                    onUtteranceFinished(utteranceId)
                }
            },
            onError = { _ ->
                viewModelScope.launch(Dispatchers.Main.immediate) {
                    playback.value = ReaderPlaybackState.PAUSED
                    message.value = "Could not speak this section"
                }
            },
        )
        tts.initialize { ready ->
            viewModelScope.launch(Dispatchers.Main.immediate) {
                ttsReady.value = ready
                if (!ready) {
                    message.value = "Text-to-speech is unavailable on this device"
                    return@launch
                }
                tts.setSpeechRate(settingsRepository.readerSpeechRate.value)
                val savedVoice = settingsRepository.readerVoiceName.value
                tts.setPreferredVoice(savedVoice)
                refreshVoices()
                val applied = tts.currentVoiceName()
                selectedVoiceName.value = applied
                if (savedVoice == null && applied != null) {
                    settingsRepository.setReaderVoiceName(applied)
                }
            }
        }
        viewModelScope.launch {
            val savedId = settingsRepository.readerBookId.value ?: return@launch
            val savedChunk = settingsRepository.readerChunkIndex.value
            // Always open at the start; offer Resume if we left off later.
            loadBook(savedId, startIndex = 0, savedResumeIndex = savedChunk)
        }
    }

    fun selectBook(bookId: Long) {
        stopPlayback()
        viewModelScope.launch {
            val savedChunk = if (bookId == settingsRepository.readerBookId.value) {
                settingsRepository.readerChunkIndex.value
            } else {
                0
            }
            loadBook(bookId, startIndex = 0, savedResumeIndex = savedChunk)
        }
    }

    fun playOrPause() {
        when (playback.value) {
            ReaderPlaybackState.PLAYING -> pause()
            ReaderPlaybackState.IDLE, ReaderPlaybackState.PAUSED -> play()
        }
    }

    fun stop() {
        stopPlayback()
    }

    fun restart() {
        if (chunks.value.isEmpty()) return
        stopPlayback()
        chunkIndex.value = ReaderStartResolver.contentStartIndex(chunks.value)
        resumeChunkIndex.value = null
        persistPosition()
    }

    fun resumeSavedPosition() {
        val target = resumeChunkIndex.value ?: return
        if (chunks.value.isEmpty()) return
        stopPlayback()
        chunkIndex.value = target.coerceIn(0, chunks.value.lastIndex)
        resumeChunkIndex.value = null
        persistPosition()
    }

    fun nextChunk() {
        val next = chunkIndex.value + 1
        if (next >= chunks.value.size) return
        chunkIndex.value = next
        resumeChunkIndex.value = null
        persistPosition()
        if (playback.value == ReaderPlaybackState.PLAYING) {
            speakCurrent()
        }
    }

    fun previousChunk() {
        val prev = (chunkIndex.value - 1).coerceAtLeast(0)
        if (prev == chunkIndex.value) return
        chunkIndex.value = prev
        resumeChunkIndex.value = null
        persistPosition()
        if (playback.value == ReaderPlaybackState.PLAYING) {
            speakCurrent()
        }
    }

    fun setSpeechRate(rate: Float) {
        settingsRepository.setReaderSpeechRate(rate)
        tts.setSpeechRate(rate)
    }

    fun selectVoice(voiceName: String) {
        val wasPlaying = playback.value == ReaderPlaybackState.PLAYING
        if (wasPlaying) {
            pause()
        }
        val applied = tts.setPreferredVoice(voiceName)
        if (!applied) {
            message.value = "Could not switch voice"
            refreshVoices()
            return
        }
        settingsRepository.setReaderVoiceName(voiceName)
        selectedVoiceName.value = voiceName
        refreshVoices()
        if (wasPlaying) {
            play()
        }
    }

    fun clearMessage() {
        message.value = null
    }

    private fun refreshVoices() {
        val listed = tts.listVoices()
        voices.value = listed
        val current = tts.currentVoiceName()
        if (current != null) {
            selectedVoiceName.value = current
        }
    }

    private fun play() {
        if (!ttsReady.value) {
            message.value = "Text-to-speech is not ready yet"
            return
        }
        if (chunks.value.isEmpty()) {
            message.value = "Index a book in Library before listening"
            return
        }
        resumeChunkIndex.value = null
        playback.value = ReaderPlaybackState.PLAYING
        persistPosition()
        speakCurrent()
    }

    private fun pause() {
        // Mark paused before stop() so any stop-triggered TTS callbacks are ignored.
        playback.value = ReaderPlaybackState.PAUSED
        persistPosition()
        tts.stop()
    }

    private fun stopPlayback() {
        playback.value = ReaderPlaybackState.IDLE
        tts.stop()
    }

    private fun speakCurrent() {
        val chunk = chunks.value.getOrNull(chunkIndex.value) ?: run {
            playback.value = ReaderPlaybackState.IDLE
            return
        }
        val text = chunk.text.trim()
        if (text.isEmpty()) {
            advanceAfterUtterance()
            return
        }
        val utteranceId = utteranceIdFor(chunkIndex.value)
        val started = tts.speak(text, utteranceId)
        if (!started) {
            playback.value = ReaderPlaybackState.PAUSED
            message.value = "Could not start speech. Try another voice or check your connection for Natural voices."
        }
    }

    private fun onUtteranceFinished(utteranceId: String) {
        if (playback.value != ReaderPlaybackState.PLAYING) return
        if (utteranceId != utteranceIdFor(chunkIndex.value)) return
        advanceAfterUtterance()
    }

    private fun advanceAfterUtterance() {
        val next = chunkIndex.value + 1
        if (next >= chunks.value.size) {
            playback.value = ReaderPlaybackState.IDLE
            chunkIndex.value = ReaderStartResolver.contentStartIndex(chunks.value)
            resumeChunkIndex.value = null
            persistPosition()
            message.value = "Finished reading"
            return
        }
        chunkIndex.value = next
        persistPosition()
        speakCurrent()
    }

    private suspend fun loadBook(
        bookId: Long,
        startIndex: Int,
        savedResumeIndex: Int = 0,
    ) {
        isLoading.value = true
        try {
            val book = withContext(Dispatchers.IO) { bookDao.getById(bookId) }
            if (book == null || book.status != BookStatus.INDEXED) {
                selectedBookId.value = null
                chunks.value = emptyList()
                chunkIndex.value = 0
                resumeChunkIndex.value = null
                settingsRepository.setReaderPosition(null, 0)
                return
            }
            val loaded = withContext(Dispatchers.IO) { chunkDao.getByBookId(bookId) }
            selectedBookId.value = bookId
            chunks.value = loaded
            val contentStart = ReaderStartResolver.contentStartIndex(loaded)
            val safeStart = when {
                loaded.isEmpty() -> 0
                startIndex <= 0 -> contentStart
                else -> startIndex.coerceIn(0, loaded.lastIndex)
            }
            chunkIndex.value = safeStart
            val safeResume = if (loaded.isEmpty()) {
                null
            } else {
                savedResumeIndex
                    .takeIf { it > safeStart }
                    ?.coerceIn(0, loaded.lastIndex)
            }
            resumeChunkIndex.value = safeResume
            // Keep any later saved progress so Resume still works.
            if (safeResume == null) {
                settingsRepository.setReaderPosition(bookId, safeStart)
            } else {
                settingsRepository.setReaderPosition(bookId, safeResume)
            }
            if (loaded.isEmpty()) {
                message.value = "No readable text for this book. Re-index it in Library."
            }
        } finally {
            isLoading.value = false
        }
    }

    private fun persistPosition() {
        settingsRepository.setReaderPosition(selectedBookId.value, chunkIndex.value)
    }

    private fun utteranceIdFor(index: Int): String = "chunk-$index"

    override fun onCleared() {
        tts.stop()
        super.onCleared()
    }

    private data class BookState(
        val books: List<BookEntity>,
        val selected: BookEntity?,
        val chunks: List<ChunkEntity>,
        val chunkIndex: Int,
    )

    private data class PlaybackState(
        val playback: ReaderPlaybackState,
        val speechRate: Float,
        val ttsReady: Boolean,
        val isLoading: Boolean,
        val message: String?,
    )

    private data class VoiceState(
        val voices: List<TtsVoiceOption>,
        val selectedName: String?,
    )
}

package com.bookgpt.android.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bookgpt.android.data.db.BookDao
import com.bookgpt.android.data.db.BookStatus
import com.bookgpt.android.data.db.ChatDao
import com.bookgpt.android.data.db.ChatMessageEntity
import com.bookgpt.android.data.db.ConversationEntity
import com.bookgpt.android.data.settings.SettingsRepository
import com.bookgpt.android.domain.agent.BookAgent
import com.bookgpt.android.domain.agent.ChatSources
import com.bookgpt.android.domain.agent.formatSourcesText
import com.bookgpt.android.domain.retrieve.ReindexRequiredException
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import javax.inject.Inject

data class ChatUiMessage(
    val id: Long,
    val role: String,
    val content: String,
    val sourcesText: String = "",
)

data class ChatUiState(
    val messages: List<ChatUiMessage> = emptyList(),
    val books: List<String> = emptyList(),
    val activeBook: String? = null,
    val draft: String = "",
    val isSending: Boolean = false,
    val hasApiKey: Boolean = false,
    val error: String? = null,
    val conversations: List<ConversationEntity> = emptyList(),
    val activeConversationId: Long? = null,
    val activeConversationTitle: String = "New chat",
    val streamingResponse: String = "",
    val reindexRequired: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ChatViewModel @Inject constructor(
    private val chatDao: ChatDao,
    private val bookDao: BookDao,
    private val bookAgent: BookAgent,
    private val settingsRepository: SettingsRepository,
    private val json: Json,
) : ViewModel() {

    private val draft = MutableStateFlow("")
    private val sending = MutableStateFlow(false)
    private val error = MutableStateFlow<String?>(null)
    private val books = MutableStateFlow<List<String>>(emptyList())
    private val activeConversationId = MutableStateFlow<Long?>(null)
    private val streamingResponse = MutableStateFlow("")
    private val reindexRequired = MutableStateFlow(false)

    private val messages = activeConversationId.flatMapLatest { conversationId ->
        if (conversationId == null) {
            flowOf(emptyList())
        } else {
            chatDao.observeMessages(conversationId)
        }
    }

    private val conversationState = combine(
        messages,
        chatDao.observeConversations(),
        activeConversationId,
    ) { chatMessages, conversations, activeId ->
        ConversationState(chatMessages, conversations, activeId)
    }

    private val composerState = combine(
        books,
        settingsRepository.activeBookTitle,
        draft,
        sending,
        streamingResponse,
    ) { bookList, activeBook, draftText, isSending, streamedText ->
        ComposerState(bookList, activeBook, draftText, isSending, streamedText)
    }

    val uiState: StateFlow<ChatUiState> = combine(
        conversationState,
        composerState,
        settingsRepository.hasApiKey,
        error,
        reindexRequired,
    ) { conversation, composer, hasKey, err, needsReindex ->
        ChatUiState(
            messages = conversation.messages.map { it.toUiMessage() },
            books = composer.books,
            activeBook = composer.activeBook,
            draft = composer.draft,
            isSending = composer.isSending,
            hasApiKey = hasKey,
            error = err,
            conversations = conversation.conversations,
            activeConversationId = conversation.activeId,
            activeConversationTitle = conversation.conversations
                .firstOrNull { it.id == conversation.activeId }?.title ?: "New chat",
            streamingResponse = composer.streamingResponse,
            reindexRequired = needsReindex,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatUiState())

    init {
        refreshBooks()
        viewModelScope.launch {
            ensureConversation()
        }
    }

    fun refreshBooks() {
        viewModelScope.launch {
            books.value = bookDao.getBooks()
                .filter { it.status == BookStatus.INDEXED }
                .map { it.displayTitle }
        }
    }

    fun onDraftChange(value: String) {
        draft.value = value
    }

    fun setActiveBook(title: String?) {
        bookAgent.setActiveBook(title)
    }

    fun clearChat() {
        viewModelScope.launch {
            val conversationId = ensureConversation()
            bookAgent.clearHistory(conversationId)
        }
    }

    fun newChat() {
        viewModelScope.launch {
            activeConversationId.value = createConversation()
            draft.value = ""
        }
    }

    fun selectConversation(conversationId: Long) {
        activeConversationId.value = conversationId
        draft.value = ""
    }

    fun deleteConversation(conversationId: Long) {
        viewModelScope.launch {
            chatDao.deleteConversation(conversationId)
            if (activeConversationId.value == conversationId) {
                activeConversationId.value =
                    chatDao.getConversations().firstOrNull()?.id ?: createConversation()
            }
        }
    }

    fun renameConversation(conversationId: Long, title: String) {
        val cleaned = title.replace(Regex("""\s+"""), " ").trim().take(80)
        if (cleaned.isBlank()) return
        viewModelScope.launch {
            chatDao.updateConversation(conversationId, cleaned, System.currentTimeMillis())
        }
    }

    fun send() {
        val text = draft.value.trim()
        if (text.isBlank() || sending.value) return

        if (!settingsRepository.hasApiKey.value) {
            error.value = "Add your OpenAI API key in Settings first."
            return
        }

        viewModelScope.launch {
            sending.value = true
            streamingResponse.value = ""
            error.value = null
            draft.value = ""
            try {
                val conversationId = ensureConversation()
                bookAgent.streamChat(conversationId, text).collect { delta ->
                    streamingResponse.value += delta
                }
                refreshBooks()
            } catch (ex: CancellationException) {
                throw ex
            } catch (_: ReindexRequiredException) {
                draft.value = text
                reindexRequired.value = true
            } catch (ex: Exception) {
                error.value = ex.message ?: "Chat failed"
            } finally {
                streamingResponse.value = ""
                sending.value = false
            }
        }
    }

    fun clearError() {
        error.value = null
    }

    fun dismissReindexPrompt() {
        reindexRequired.value = false
    }

    private fun ChatMessageEntity.toUiMessage(): ChatUiMessage {
        val sourcesText = sourcesJson?.let {
            runCatching { formatSourcesText(json.decodeFromString<ChatSources>(it)) }.getOrDefault("")
        }.orEmpty()
        return ChatUiMessage(
            id = id,
            role = role,
            content = content,
            sourcesText = sourcesText,
        )
    }

    private suspend fun ensureConversation(): Long {
        activeConversationId.value?.let { return it }
        val existing = chatDao.getConversations().firstOrNull()?.id
        val id = existing ?: createConversation()
        activeConversationId.value = id
        return id
    }

    private suspend fun createConversation(): Long =
        chatDao.insertConversation(ConversationEntity())

    private data class ConversationState(
        val messages: List<ChatMessageEntity>,
        val conversations: List<ConversationEntity>,
        val activeId: Long?,
    )

    private data class ComposerState(
        val books: List<String>,
        val activeBook: String?,
        val draft: String,
        val isSending: Boolean,
        val streamingResponse: String,
    )
}

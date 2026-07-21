package com.bookgpt.android.domain.agent

import com.bookgpt.android.data.db.BookDao
import com.bookgpt.android.data.db.BookStatus
import com.bookgpt.android.data.db.ChatDao
import com.bookgpt.android.data.db.ChatMessageEntity
import com.bookgpt.android.data.openai.ChatMessageDto
import com.bookgpt.android.data.openai.OpenAiClient
import com.bookgpt.android.data.settings.SettingsRepository
import com.bookgpt.android.domain.retrieve.BookRetriever
import com.bookgpt.android.domain.retrieve.RetrievedChunk
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min

@Singleton
class BookAgent @Inject constructor(
    private val bookDao: BookDao,
    private val chatDao: ChatDao,
    private val retriever: BookRetriever,
    private val openAiClient: OpenAiClient,
    private val settingsRepository: SettingsRepository,
    private val json: Json,
    private val contextAssembler: ConversationContextAssembler,
) {
    suspend fun listBooks(): List<String> =
        bookDao.getBooks()
            .filter { it.status == BookStatus.INDEXED }
            .map { it.displayTitle }

    fun setActiveBook(title: String?) {
        settingsRepository.setActiveBook(title)
    }

    suspend fun clearHistory(conversationId: Long) {
        chatDao.clearConversationMessages(conversationId)
    }

    fun streamChat(conversationId: Long, userMessage: String): Flow<String> = flow {
        val localBooks = listBooks()
        val focusBook = resolveFocusBook(localBooks)
        val (context, sources) = buildContext(userMessage, focusBook)

        chatDao.insert(
            ChatMessageEntity(
                conversationId = conversationId,
                role = "user",
                content = userMessage,
            ),
        )

        if (context.isBlank()) {
            val emptyReply = emptyLibraryReply(focusBook)
            emit(emptyReply)
            chatDao.insert(
                ChatMessageEntity(
                    conversationId = conversationId,
                    role = "assistant",
                    content = emptyReply,
                ),
            )
            touchOrTitleConversation(conversationId, userMessage, emptyReply)
            return@flow
        }

        val activeLine = focusBook
            ?.let { "\nCurrent conversation focus: $it" }
            .orEmpty()

        val system = SYSTEM_PROMPT
            .replace("{library}", if (localBooks.isEmpty()) "(no books indexed yet)" else localBooks.joinToString(", "))
            .replace("{active_book_line}", activeLine)
            .replace(
                "{scope_rule}",
                if (focusBook != null) {
                    "Answer only from passages of \"$focusBook\". Do not use other books."
                } else {
                    "Answer only from passages in the user's library."
                },
            )

        val messages = contextAssembler.assemble(
            conversationId = conversationId,
            systemPrompt = system,
            currentUserMessage = userMessage,
            retrievalContext = context,
        )

        val reply = StringBuilder()
        openAiClient.streamChat(messages).collect { delta ->
            reply.append(delta)
            emit(delta)
        }
        chatDao.insert(
            ChatMessageEntity(
                conversationId = conversationId,
                role = "assistant",
                content = reply.toString(),
                sourcesJson = if (sources.hasSources) json.encodeToString(sources) else null,
            ),
        )
        touchOrTitleConversation(conversationId, userMessage, reply.toString())
    }

    private suspend fun touchOrTitleConversation(
        conversationId: Long,
        userMessage: String,
        assistantReply: String,
    ) {
        val conversation = chatDao.getConversation(conversationId)
        if (conversation?.title == "New chat") {
            val generatedTitle = generateConversationTitle(userMessage, assistantReply)
            chatDao.updateConversation(
                conversationId,
                generatedTitle,
                System.currentTimeMillis(),
            )
        } else {
            chatDao.touchConversation(conversationId, System.currentTimeMillis())
        }
    }

    private suspend fun generateConversationTitle(
        userMessage: String,
        assistantReply: String,
    ): String {
        val generated = runCatching {
            openAiClient.chat(
                messages = listOf(
                    ChatMessageDto(
                        role = "system",
                        content = "Create a concise 3-7 word title for this conversation. " +
                            "Return only the title, without quotes, punctuation, or explanation.",
                    ),
                    ChatMessageDto(
                        role = "user",
                        content = "User: ${userMessage.take(1500)}\n\n" +
                            "Assistant: ${assistantReply.take(2000)}",
                    ),
                ),
                temperature = 0.2,
            )
        }.getOrNull()

        return generated
            ?.lineSequence()
            ?.firstOrNull()
            ?.replace(Regex("""\s+"""), " ")
            ?.trim()
            ?.trim('"', '\'', '.', ':', '-', '–', '—')
            ?.take(60)
            ?.takeIf { it.isNotBlank() }
            ?: "Book discussion"
    }

    private fun resolveFocusBook(localBooks: List<String>): String? {
        val active = settingsRepository.activeBookTitle.value
        return active?.takeIf { it in localBooks }
    }

    private suspend fun buildContext(
        userMessage: String,
        focusBook: String?,
    ): Pair<String, ChatSources> {
        var sources = ChatSources()
        val localChunks = retriever.search(userMessage, bookTitle = focusBook)

        if (localChunks.isEmpty()) {
            return "" to sources
        }

        val context = "## Your book library\n${formatChunks(localChunks)}"
        for (chunk in localChunks) {
            sources = sources.addLibraryChunk(chunk)
        }
        return context to sources
    }

    private fun emptyLibraryReply(focusBook: String?): String =
        if (focusBook != null) {
            "I couldn't find that in \"$focusBook\". Try rephrasing, or switch focus to All books."
        } else {
            "I couldn't find that in your library. Import and index a book, or try a different question."
        }

    companion object {
        const val SYSTEM_PROMPT = """You are BookGPT, a knowledgeable literary assistant.

You help users discuss books, answer questions, give recommendations, and explore themes and characters based only on their imported library.

Rules:
- {scope_rule}
- Use only information from "Your book library" passages provided below.
- Do not use the web, general knowledge, or unsupported claims from conversation history as evidence.
- If the answer is not in the provided passages, say so honestly rather than inventing plot details.
- For recommendations, suggest only from books listed in the user's library.
- Keep a conversational, engaging tone. Remember prior messages in the chat.
- When discussing a specific book, stay focused on that book unless the user changes topic.

Available books in the user's library:
{library}
{active_book_line}
"""

        fun similarity(a: String, b: String): Float {
            val left = a.lowercase(Locale.US)
            val right = b.lowercase(Locale.US)
            if (left == right) return 1f
            val distance = levenshtein(left, right)
            val maxLen = max(left.length, right.length).coerceAtLeast(1)
            return 1f - (distance.toFloat() / maxLen)
        }

        fun detectBookInText(text: String, titles: List<String>): String? {
            val lowered = text.lowercase(Locale.US)
            val matches = mutableListOf<Pair<String, Float>>()
            for (title in titles) {
                val titleLower = title.lowercase(Locale.US)
                if (titleLower in lowered) {
                    matches += title to 1f
                    continue
                }
                val ratio = similarity(text, title)
                if (ratio > 0.65f) matches += title to ratio
            }

            val quoted = Regex(""""([^"]+)"|'([^']+)'|“([^”]+)”""").findAll(text)
            for (match in quoted) {
                val candidate = match.groupValues.drop(1).firstOrNull { it.isNotBlank() } ?: continue
                for (title in titles) {
                    if (similarity(candidate, title) > 0.8f) {
                        matches += title to 0.95f
                    }
                }
            }

            return matches.maxByOrNull { it.second }?.first
        }

        fun formatChunks(chunks: List<RetrievedChunk>): String {
            if (chunks.isEmpty()) return ""
            return chunks.joinToString("\n\n") { chunk ->
                val location = locationLabel(chunk)
                "### ${chunk.title} ($location)\n${chunk.text}"
            }
        }

        private fun locationLabel(chunk: RetrievedChunk): String {
            val parts = mutableListOf<String>()
            if (chunk.chapter.isNotBlank()) parts += chunk.chapter
            if (chunk.page > 0) parts += "p. ${chunk.page}"
            if (parts.isNotEmpty()) return parts.joinToString(", ")
            return "section ${chunk.chunkIndex + 1}"
        }

        private fun levenshtein(a: String, b: String): Int {
            val dp = Array(a.length + 1) { IntArray(b.length + 1) }
            for (i in 0..a.length) dp[i][0] = i
            for (j in 0..b.length) dp[0][j] = j
            for (i in 1..a.length) {
                for (j in 1..b.length) {
                    val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                    dp[i][j] = min(
                        min(dp[i - 1][j] + 1, dp[i][j - 1] + 1),
                        dp[i - 1][j - 1] + cost,
                    )
                }
            }
            return dp[a.length][b.length]
        }
    }
}

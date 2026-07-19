package com.bookgpt.android.domain.agent

import com.bookgpt.android.data.db.ChatDao
import com.bookgpt.android.data.db.ChatMessageEntity
import com.bookgpt.android.data.openai.ChatMessageDto
import com.bookgpt.android.data.openai.OpenAiClient
import com.bookgpt.android.domain.Config
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds LLM chat context as:
 * system prompt → optional rolling summary of older turns → last N messages →
 * current user message (with this turn's retrieval context only).
 *
 * Full message history remains in Room for the UI; only the request payload is trimmed.
 */
@Singleton
class ConversationContextAssembler @Inject constructor(
    private val chatDao: ChatDao,
    private val openAiClient: OpenAiClient,
) {
    suspend fun assemble(
        conversationId: Long,
        systemPrompt: String,
        currentUserMessage: String,
        retrievalContext: String,
        recentMessageCount: Int = Config.CHAT_RECENT_MESSAGE_COUNT,
    ): List<ChatMessageDto> {
        val history = chatDao.getMessages(conversationId)
        val (older, recent) = splitRecentWindow(history, recentMessageCount)
        val summary = ensureSummary(conversationId, older)

        val userContent = if (retrievalContext.isNotBlank()) {
            "$currentUserMessage\n\n---\nContext for this reply:\n$retrievalContext"
        } else {
            currentUserMessage
        }

        return buildList {
            add(ChatMessageDto(role = "system", content = systemPrompt))
            if (!summary.isNullOrBlank()) {
                add(
                    ChatMessageDto(
                        role = "system",
                        content = "Earlier conversation summary (older turns compacted):\n$summary",
                    ),
                )
            }
            for (message in recent) {
                add(ChatMessageDto(role = message.role, content = message.content))
            }
            add(ChatMessageDto(role = "user", content = userContent))
        }
    }

    private suspend fun ensureSummary(
        conversationId: Long,
        older: List<ChatMessageEntity>,
    ): String? {
        if (older.isEmpty()) return null

        val conversation = chatDao.getConversation(conversationId)
        val existingSummary = conversation?.summary?.takeIf { it.isNotBlank() }
        val throughId = conversation?.summarizedThroughMessageId
        val pending = if (throughId == null) {
            older
        } else {
            older.filter { it.id > throughId }
        }

        if (pending.isEmpty()) return existingSummary

        val updated = runCatching {
            summarize(existingSummary, pending)
        }.getOrElse {
            // If summarization fails, keep the previous summary and still drop pending
            // from the request by relying on the recent window only when no summary exists.
            existingSummary ?: fallbackSummary(pending)
        }.take(Config.CHAT_SUMMARY_MAX_CHARS)

        chatDao.updateConversationSummary(
            conversationId = conversationId,
            summary = updated,
            summarizedThroughMessageId = older.last().id,
        )
        return updated
    }

    private suspend fun summarize(
        existingSummary: String?,
        pending: List<ChatMessageEntity>,
    ): String {
        val transcript = pending.joinToString("\n") { message ->
            val label = when (message.role) {
                "user" -> "User"
                "assistant" -> "Assistant"
                else -> message.role
            }
            "$label: ${message.content.trim()}"
        }.take(12_000)

        val userPrompt = buildString {
            if (!existingSummary.isNullOrBlank()) {
                append("Existing summary:\n")
                append(existingSummary)
                append("\n\n")
            }
            append("New turns to fold in:\n")
            append(transcript)
        }

        val result = openAiClient.chat(
            messages = listOf(
                ChatMessageDto(role = "system", content = SUMMARY_SYSTEM_PROMPT),
                ChatMessageDto(role = "user", content = userPrompt),
            ),
            temperature = 0.2,
        )
        return result.trim().ifBlank { fallbackSummary(pending) }
    }

    companion object {
        private val SUMMARY_SYSTEM_PROMPT = """
You compress a book discussion so a later model call can continue without the full transcript.
Preserve: books discussed, characters, plot points, user preferences, conclusions, and open questions.
Write a concise third-person summary. Return only the summary text.
""".trimIndent()

        /**
         * Splits [messages] into older turns (to summarize) and a recent verbatim window.
         * The recent window is aligned so it does not start on an orphaned assistant reply.
         */
        fun splitRecentWindow(
            messages: List<ChatMessageEntity>,
            recentCount: Int,
        ): Pair<List<ChatMessageEntity>, List<ChatMessageEntity>> {
            if (messages.isEmpty()) return emptyList<ChatMessageEntity>() to emptyList()
            if (recentCount <= 0 || messages.size <= recentCount) {
                return emptyList<ChatMessageEntity>() to messages
            }

            var start = messages.size - recentCount
            while (start > 0 && messages[start].role == "assistant") {
                start--
            }
            if (messages[start].role == "assistant") {
                start++
            }
            if (start <= 0) {
                return emptyList<ChatMessageEntity>() to messages
            }
            return messages.subList(0, start) to messages.subList(start, messages.size)
        }

        fun fallbackSummary(messages: List<ChatMessageEntity>): String =
            messages.takeLast(6).joinToString("\n") { "${it.role}: ${it.content.take(200)}" }
                .take(Config.CHAT_SUMMARY_MAX_CHARS)
    }
}

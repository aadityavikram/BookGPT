package com.bookgpt.android.domain.agent

import com.bookgpt.android.data.db.ChatMessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationContextAssemblerTest {

    @Test
    fun splitRecentWindow_keepsAllWhenUnderLimit() {
        val messages = messages(
            "user" to "a",
            "assistant" to "b",
            "user" to "c",
            "assistant" to "d",
        )
        val (older, recent) = ConversationContextAssembler.splitRecentWindow(messages, recentCount = 12)
        assertTrue(older.isEmpty())
        assertEquals(messages, recent)
    }

    @Test
    fun splitRecentWindow_keepsLastN() {
        val messages = messages(
            "user" to "1",
            "assistant" to "2",
            "user" to "3",
            "assistant" to "4",
            "user" to "5",
            "assistant" to "6",
        )
        val (older, recent) = ConversationContextAssembler.splitRecentWindow(messages, recentCount = 4)
        assertEquals(listOf(1L, 2L), older.map { it.id })
        assertEquals(listOf(3L, 4L, 5L, 6L), recent.map { it.id })
    }

    @Test
    fun splitRecentWindow_doesNotStartOnOrphanAssistant() {
        // Window of 3 would start on assistant "4"; align back to user "3".
        val messages = messages(
            "user" to "1",
            "assistant" to "2",
            "user" to "3",
            "assistant" to "4",
            "user" to "5",
            "assistant" to "6",
        )
        val (older, recent) = ConversationContextAssembler.splitRecentWindow(messages, recentCount = 3)
        assertEquals(listOf(1L, 2L), older.map { it.id })
        assertEquals(listOf(3L, 4L, 5L, 6L), recent.map { it.id })
        assertEquals("user", recent.first().role)
    }

    @Test
    fun splitRecentWindow_empty() {
        val (older, recent) = ConversationContextAssembler.splitRecentWindow(emptyList(), 12)
        assertTrue(older.isEmpty())
        assertTrue(recent.isEmpty())
    }

    private fun messages(vararg turns: Pair<String, String>): List<ChatMessageEntity> =
        turns.mapIndexed { index, (role, content) ->
            ChatMessageEntity(
                id = (index + 1).toLong(),
                conversationId = 1L,
                role = role,
                content = content,
                createdAt = index.toLong(),
            )
        }
}

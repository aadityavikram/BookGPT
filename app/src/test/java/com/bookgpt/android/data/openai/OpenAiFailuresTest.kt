package com.bookgpt.android.data.openai

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiFailuresTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `bad API key is permanent`() {
        val failure = OpenAiFailures.fromHttp(
            status = 401,
            body = """{"error":{"message":"Incorrect API key","type":"invalid_request_error"}}""",
            json = json,
        )

        assertEquals(FailureCategory.AUTHENTICATION, failure.category)
        assertFalse(failure.retryable)
        assertTrue(failure.message!!.contains("API key"))
    }

    @Test
    fun `insufficient quota is permanent`() {
        val failure = OpenAiFailures.fromHttp(
            status = 429,
            body = """{"error":{"message":"You exceeded your current quota","type":"insufficient_quota","code":"insufficient_quota"}}""",
            json = json,
        )

        assertEquals(FailureCategory.QUOTA, failure.category)
        assertFalse(failure.retryable)
    }

    @Test
    fun `ordinary rate limit is retryable`() {
        val failure = OpenAiFailures.fromHttp(
            status = 429,
            body = """{"error":{"message":"Rate limit reached","type":"rate_limit_error"}}""",
            json = json,
        )

        assertEquals(FailureCategory.RATE_LIMIT, failure.category)
        assertTrue(failure.retryable)
    }

    @Test
    fun `server errors are retryable`() {
        val failure = OpenAiFailures.fromHttp(status = 503, body = null, json = json)

        assertEquals(FailureCategory.SERVER, failure.category)
        assertTrue(failure.retryable)
    }
}

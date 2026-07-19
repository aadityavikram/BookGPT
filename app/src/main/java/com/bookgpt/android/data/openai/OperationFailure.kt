package com.bookgpt.android.data.openai

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import retrofit2.HttpException
import java.io.IOException

enum class FailureCategory {
    AUTHENTICATION,
    PERMISSION,
    QUOTA,
    RATE_LIMIT,
    NETWORK,
    SERVER,
    INVALID_REQUEST,
    BOOK_FILE,
    UNKNOWN,
}

class OperationFailure(
    val category: FailureCategory,
    message: String,
    val retryable: Boolean,
    cause: Throwable? = null,
) : Exception(message, cause)

object OpenAiFailures {
    fun fromThrowable(error: Throwable, json: Json): OperationFailure {
        if (error is CancellationException) throw error
        if (error is OperationFailure) return error
        return when (error) {
            is HttpException -> fromHttp(
                status = error.code(),
                body = runCatching { error.response()?.errorBody()?.string() }.getOrNull(),
                json = json,
                cause = error,
            )
            is IOException -> OperationFailure(
                category = FailureCategory.NETWORK,
                message = "Could not reach OpenAI. Check your internet connection.",
                retryable = true,
                cause = error,
            )
            is SerializationException -> OperationFailure(
                category = FailureCategory.SERVER,
                message = "OpenAI returned a response BookGPT could not read.",
                retryable = true,
                cause = error,
            )
            else -> OperationFailure(
                category = FailureCategory.UNKNOWN,
                message = "The OpenAI request failed unexpectedly.",
                retryable = false,
                cause = error,
            )
        }
    }

    fun fromHttp(
        status: Int,
        body: String?,
        json: Json,
        cause: Throwable? = null,
    ): OperationFailure {
        val detail = body
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { json.decodeFromString<OpenAiErrorBody>(it).error }.getOrNull() }
        val diagnostic = listOfNotNull(detail?.type, detail?.code, detail?.message)
            .joinToString(" ")
            .lowercase()

        return when {
            status == 401 -> OperationFailure(
                FailureCategory.AUTHENTICATION,
                "OpenAI rejected the API key. Update it in Settings.",
                retryable = false,
                cause = cause,
            )
            status == 403 -> OperationFailure(
                FailureCategory.PERMISSION,
                "This API key does not have permission for the selected OpenAI model.",
                retryable = false,
                cause = cause,
            )
            status == 429 && ("quota" in diagnostic || "billing" in diagnostic) -> OperationFailure(
                FailureCategory.QUOTA,
                "The OpenAI account has no available quota. Check billing or usage limits.",
                retryable = false,
                cause = cause,
            )
            status == 429 -> OperationFailure(
                FailureCategory.RATE_LIMIT,
                "OpenAI is rate-limiting requests. BookGPT will retry shortly.",
                retryable = true,
                cause = cause,
            )
            status == 408 -> OperationFailure(
                FailureCategory.NETWORK,
                "The OpenAI request timed out. BookGPT will retry.",
                retryable = true,
                cause = cause,
            )
            status == 409 || status == 425 || status in 500..599 -> OperationFailure(
                FailureCategory.SERVER,
                "OpenAI is temporarily unavailable. BookGPT will retry.",
                retryable = true,
                cause = cause,
            )
            status == 400 -> OperationFailure(
                FailureCategory.INVALID_REQUEST,
                "OpenAI rejected the request. Check the selected model in Settings.",
                retryable = false,
                cause = cause,
            )
            status == 404 -> OperationFailure(
                FailureCategory.INVALID_REQUEST,
                "The selected OpenAI model was not found. Choose another model in Settings.",
                retryable = false,
                cause = cause,
            )
            else -> OperationFailure(
                FailureCategory.INVALID_REQUEST,
                "OpenAI rejected the request (HTTP $status).",
                retryable = false,
                cause = cause,
            )
        }
    }
}

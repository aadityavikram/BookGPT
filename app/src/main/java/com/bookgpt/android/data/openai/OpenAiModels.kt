package com.bookgpt.android.data.openai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class EmbeddingRequest(
    val model: String,
    val input: List<String>,
)

@Serializable
data class EmbeddingResponse(
    val data: List<EmbeddingData>,
)

@Serializable
data class EmbeddingData(
    val embedding: List<Float>,
    val index: Int = 0,
)

@Serializable
data class ChatCompletionRequest(
    val model: String,
    val messages: List<ChatMessageDto>,
    val temperature: Double = 0.7,
    val stream: Boolean = false,
)

@Serializable
data class ChatMessageDto(
    val role: String,
    val content: String,
)

@Serializable
data class ChatCompletionResponse(
    val choices: List<ChatChoice>,
)

@Serializable
data class ChatChoice(
    val message: ChatMessageDto,
)

@Serializable
data class ChatStreamResponse(
    val choices: List<ChatStreamChoice> = emptyList(),
)

@Serializable
data class ChatStreamChoice(
    val delta: ChatStreamDelta = ChatStreamDelta(),
)

@Serializable
data class ChatStreamDelta(
    val content: String? = null,
)

@Serializable
data class OpenAiErrorBody(
    val error: OpenAiErrorDetail? = null,
)

@Serializable
data class OpenAiErrorDetail(
    val message: String? = null,
    @SerialName("type") val type: String? = null,
    val code: String? = null,
)

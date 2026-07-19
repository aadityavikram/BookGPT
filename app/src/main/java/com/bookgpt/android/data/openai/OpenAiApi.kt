package com.bookgpt.android.data.openai

import retrofit2.http.Body
import retrofit2.http.POST

interface OpenAiApi {
    @POST("embeddings")
    suspend fun createEmbeddings(@Body request: EmbeddingRequest): EmbeddingResponse

    @POST("chat/completions")
    suspend fun createChatCompletion(@Body request: ChatCompletionRequest): ChatCompletionResponse
}

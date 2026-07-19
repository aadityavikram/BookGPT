package com.bookgpt.android.data.openai

import com.bookgpt.android.data.settings.SettingsRepository
import com.bookgpt.android.domain.Config
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OpenAiClient @Inject constructor(
    private val api: OpenAiApi,
    private val settingsRepository: SettingsRepository,
    private val okHttpClient: OkHttpClient,
    private val json: Json,
) {
    suspend fun embed(
        texts: List<String>,
        onBatchProgress: suspend (completed: Int, total: Int) -> Unit = { _, _ -> },
    ): List<FloatArray> = withContext(Dispatchers.IO) {
        if (texts.isEmpty()) return@withContext emptyList()
        ensureApiKey()
        val batches = batchTextsByTokenBudget(texts)
        val embeddings = mutableListOf<FloatArray>()
        onBatchProgress(0, batches.size)
        for ((index, batch) in batches.withIndex()) {
            val response = openAiCall {
                api.createEmbeddings(
                    EmbeddingRequest(model = settingsRepository.getEmbeddingModel(), input = batch),
                )
            }
            val ordered = response.data.sortedBy { it.index }
            embeddings += ordered.map { it.embedding.toFloatArray() }
            onBatchProgress(index + 1, batches.size)
        }
        embeddings
    }

    suspend fun chat(
        messages: List<ChatMessageDto>,
        temperature: Double = 0.7,
    ): String = withContext(Dispatchers.IO) {
        ensureApiKey()
        val response = openAiCall {
            api.createChatCompletion(
                ChatCompletionRequest(
                    model = settingsRepository.getChatModel(),
                    messages = messages,
                    temperature = temperature,
                ),
            )
        }
        response.choices.firstOrNull()?.message?.content.orEmpty()
    }

    fun streamChat(
        messages: List<ChatMessageDto>,
        temperature: Double = 0.7,
    ): Flow<String> = callbackFlow {
        ensureApiKey()
        val payload = ChatCompletionRequest(
            model = settingsRepository.getChatModel(),
            messages = messages,
            temperature = temperature,
            stream = true,
        )
        val request = Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .post(
                json.encodeToString(payload)
                    .toRequestBody("application/json".toMediaType()),
            )
            .header("Accept", "text/event-stream")
            .build()
        val call = okHttpClient.newCall(request)
        call.enqueue(
            object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    close(OpenAiFailures.fromThrowable(error, json))
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (!response.isSuccessful) {
                            close(
                                OpenAiFailures.fromHttp(
                                    status = response.code,
                                    body = runCatching { response.body?.string() }.getOrNull(),
                                    json = json,
                                ),
                            )
                            return
                        }
                        val source = response.body?.source()
                        if (source == null) {
                            close(
                                OperationFailure(
                                    category = FailureCategory.SERVER,
                                    message = "OpenAI returned an empty response. Try again.",
                                    retryable = true,
                                ),
                            )
                            return
                        }
                        try {
                            while (!source.exhausted()) {
                                val line = source.readUtf8Line() ?: break
                                if (!line.startsWith("data:")) continue
                                val data = line.removePrefix("data:").trim()
                                if (data == "[DONE]") break
                                val chunk = json.decodeFromString<ChatStreamResponse>(data)
                                chunk.choices.firstOrNull()?.delta?.content
                                    ?.takeIf { it.isNotEmpty() }
                                    ?.let { trySend(it) }
                            }
                            close()
                        } catch (error: CancellationException) {
                            close(error)
                        } catch (error: Exception) {
                            close(OpenAiFailures.fromThrowable(error, json))
                        }
                    }
                }
            },
        )
        awaitClose { call.cancel() }
    }

    private suspend fun <T> openAiCall(block: suspend () -> T): T {
        return try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw OpenAiFailures.fromThrowable(error, json)
        }
    }

    private suspend fun ensureApiKey() {
        if (settingsRepository.getApiKey().isBlank()) {
            throw OperationFailure(
                category = FailureCategory.AUTHENTICATION,
                message = "OpenAI API key is not set. Add it in Settings.",
                retryable = false,
            )
        }
    }

    companion object {
        fun estimateTokens(text: String): Int = maxOf(1, text.length / 4)

        fun batchTextsByTokenBudget(
            texts: List<String>,
            maxTokens: Int = Config.EMBEDDING_MAX_TOKENS_PER_REQUEST,
        ): List<List<String>> {
            val batches = mutableListOf<List<String>>()
            val batch = mutableListOf<String>()
            var batchTokens = 0

            for (text in texts) {
                val textTokens = estimateTokens(text)
                if (textTokens > maxTokens) {
                    if (batch.isNotEmpty()) {
                        batches += batch.toList()
                        batch.clear()
                        batchTokens = 0
                    }
                    batches += listOf(text)
                    continue
                }
                if (batch.isNotEmpty() && batchTokens + textTokens > maxTokens) {
                    batches += batch.toList()
                    batch.clear()
                    batchTokens = 0
                }
                batch += text
                batchTokens += textTokens
            }
            if (batch.isNotEmpty()) batches += batch.toList()
            return batches
        }
    }
}

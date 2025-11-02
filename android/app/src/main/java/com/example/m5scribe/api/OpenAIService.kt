package com.example.m5scribe.api

import com.google.gson.annotations.SerializedName
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

/**
 * OpenAI Chat Completions APIのRetrofitインターフェース
 *
 * 参考: https://platform.openai.com/docs/api-reference/chat
 */
interface OpenAIService {
    @POST("v1/chat/completions")
    suspend fun createChatCompletion(
        @Header("Authorization") authorization: String,
        @Body request: ChatCompletionRequest
    ): ChatCompletionResponse

    companion object {
        const val BASE_URL = "https://api.openai.com/"
    }
}

/**
 * リクエストボディ
 */
data class ChatCompletionRequest(
    @SerializedName("model")
    val model: String,

    @SerializedName("messages")
    val messages: List<ChatMessage>,

    @SerializedName("max_tokens")
    val maxTokens: Int? = null,

    @SerializedName("temperature")
    val temperature: Float? = null,

    @SerializedName("top_p")
    val topP: Float? = null,

    @SerializedName("n")
    val n: Int? = null,

    @SerializedName("stream")
    val stream: Boolean? = null,

    @SerializedName("stop")
    val stop: List<String>? = null
)

/**
 * メッセージ（システム、ユーザー、アシスタント）
 */
data class ChatMessage(
    @SerializedName("role")
    val role: String,  // "system", "user", "assistant"

    @SerializedName("content")
    val content: String
)

/**
 * レスポンスボディ
 */
data class ChatCompletionResponse(
    @SerializedName("id")
    val id: String,

    @SerializedName("object")
    val objectType: String,

    @SerializedName("created")
    val created: Long,

    @SerializedName("model")
    val model: String,

    @SerializedName("choices")
    val choices: List<Choice>,

    @SerializedName("usage")
    val usage: Usage?
)

/**
 * 生成された選択肢
 */
data class Choice(
    @SerializedName("index")
    val index: Int,

    @SerializedName("message")
    val message: ChatMessage,

    @SerializedName("finish_reason")
    val finishReason: String  // "stop", "length", "content_filter", "null"
)

/**
 * トークン使用量
 */
data class Usage(
    @SerializedName("prompt_tokens")
    val promptTokens: Int,

    @SerializedName("completion_tokens")
    val completionTokens: Int,

    @SerializedName("total_tokens")
    val totalTokens: Int
)

/**
 * OpenAI APIエラーレスポンス
 */
data class OpenAIErrorResponse(
    @SerializedName("error")
    val error: OpenAIError
)

data class OpenAIError(
    @SerializedName("message")
    val message: String,

    @SerializedName("type")
    val type: String,

    @SerializedName("param")
    val param: String?,

    @SerializedName("code")
    val code: String?
)

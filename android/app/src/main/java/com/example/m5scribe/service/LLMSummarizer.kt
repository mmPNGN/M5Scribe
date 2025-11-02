package com.example.m5scribe.service

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.example.m5scribe.api.ChatCompletionRequest
import com.example.m5scribe.api.ChatMessage
import com.example.m5scribe.api.OpenAIErrorResponse
import com.example.m5scribe.api.OpenAIService
import com.google.gson.Gson
import okhttp3.OkHttpClient
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * LLMを使って文字起こしを要約するサービス
 */
class LLMSummarizer(private val context: Context) {
    companion object {
        private const val TAG = "LLMSummarizer"
        private const val PREFS_FILE_NAME = "m5scribe_secure_prefs"
        private const val KEY_API_KEY = "llm_api_key"
        private const val KEY_API_PROVIDER = "api_provider"

        // OpenAI設定
        private const val MODEL = "gpt-4o-mini"  // 最新の低コストモデル
        private const val MAX_TOKENS = 500
        private const val TEMPERATURE = 0.5f
        private const val TIMEOUT_SECONDS = 30L

        // プロンプト
        private const val SYSTEM_PROMPT = """あなたは日本語の音声文字起こしを要約する専門家です。
以下のルールに従って要約を作成してください：
- 重要なポイントを3〜5個の箇条書きにまとめる
- 各ポイントは簡潔に（1〜2文）
- タイムスタンプは除外する
- 話し言葉を書き言葉に整える
- 箇条書きは「・」を使用する"""
    }

    /**
     * 文字起こしテキストを要約
     *
     * @param transcription 文字起こしテキスト
     * @return Result<String> 成功時は要約テキスト、失敗時はエラー
     */
    suspend fun summarize(transcription: String): Result<String> {
        return try {
            // APIキーを取得
            val apiKey = getApiKey()
                ?: return Result.failure(Exception("APIキーが設定されていません。設定画面でAPIキーを設定してください。"))

            // プロバイダーを確認（OpenAIのみ対応）
            val provider = getApiProvider()
            if (provider != "openai") {
                return Result.failure(Exception("現在はOpenAIのみサポートしています。設定画面でOpenAIを選択してください。"))
            }

            // 文字起こしが空の場合
            if (transcription.isBlank()) {
                return Result.failure(Exception("文字起こしテキストが空です。"))
            }

            Log.d(TAG, "Starting summarization with model: $MODEL")
            Log.d(TAG, "Transcription length: ${transcription.length} characters")

            // OpenAI APIクライアントを作成
            val openAIService = createOpenAIService()

            // リクエストを構築
            val request = ChatCompletionRequest(
                model = MODEL,
                messages = listOf(
                    ChatMessage(role = "system", content = SYSTEM_PROMPT),
                    ChatMessage(
                        role = "user",
                        content = "以下の文字起こしを要約してください：\n\n$transcription"
                    )
                ),
                maxTokens = MAX_TOKENS,
                temperature = TEMPERATURE
            )

            // API呼び出し
            val response = openAIService.createChatCompletion(
                authorization = "Bearer $apiKey",
                request = request
            )

            // レスポンスから要約を取得
            val summary = response.choices.firstOrNull()?.message?.content
                ?: return Result.failure(Exception("要約の生成に失敗しました（レスポンスが空です）"))

            // ログ出力
            Log.d(TAG, "Summarization successful")
            response.usage?.let { usage ->
                Log.d(TAG, "Token usage - Prompt: ${usage.promptTokens}, " +
                        "Completion: ${usage.completionTokens}, " +
                        "Total: ${usage.totalTokens}")
            }

            Result.success(summary.trim())

        } catch (e: HttpException) {
            // HTTPエラー（401, 429, 500など）
            val errorMessage = parseHttpError(e)
            Log.e(TAG, "HTTP error: $errorMessage", e)
            Result.failure(Exception(errorMessage))

        } catch (e: IOException) {
            // ネットワークエラー
            Log.e(TAG, "Network error", e)
            Result.failure(Exception("ネットワーク接続を確認してください。"))

        } catch (e: Exception) {
            // その他のエラー
            Log.e(TAG, "Unexpected error during summarization", e)
            Result.failure(Exception("要約の生成中にエラーが発生しました: ${e.message}"))
        }
    }

    /**
     * OpenAI APIサービスを作成
     */
    private fun createOpenAIService(): OpenAIService {
        val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()

        val retrofit = Retrofit.Builder()
            .baseUrl(OpenAIService.BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        return retrofit.create(OpenAIService::class.java)
    }

    /**
     * HTTPエラーをパース
     */
    private fun parseHttpError(e: HttpException): String {
        return when (e.code()) {
            401 -> "APIキーが無効です。設定画面で正しいAPIキーを設定してください。"
            429 -> "リクエスト制限に達しました。しばらく時間をおいてから再度お試しください。"
            500, 502, 503 -> "OpenAIサーバーエラーが発生しました。後でもう一度お試しください。"
            else -> {
                // エラーボディからメッセージを抽出
                try {
                    val errorBody = e.response()?.errorBody()?.string()
                    if (errorBody != null) {
                        val errorResponse = Gson().fromJson(errorBody, OpenAIErrorResponse::class.java)
                        "OpenAI API エラー: ${errorResponse.error.message}"
                    } else {
                        "APIエラーが発生しました (HTTP ${e.code()})"
                    }
                } catch (parseError: Exception) {
                    "APIエラーが発生しました (HTTP ${e.code()})"
                }
            }
        }
    }

    /**
     * 暗号化されたSharedPreferencesからAPIキーを取得
     */
    private fun getApiKey(): String? {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            val prefs = EncryptedSharedPreferences.create(
                context,
                PREFS_FILE_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )

            prefs.getString(KEY_API_KEY, null)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get API key", e)
            // フォールバック
            context.getSharedPreferences(PREFS_FILE_NAME, Context.MODE_PRIVATE)
                .getString(KEY_API_KEY, null)
        }
    }

    /**
     * APIプロバイダーを取得
     */
    private fun getApiProvider(): String {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            val prefs = EncryptedSharedPreferences.create(
                context,
                PREFS_FILE_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )

            prefs.getString(KEY_API_PROVIDER, "openai") ?: "openai"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get API provider", e)
            // フォールバック
            context.getSharedPreferences(PREFS_FILE_NAME, Context.MODE_PRIVATE)
                .getString(KEY_API_PROVIDER, "openai") ?: "openai"
        }
    }
}

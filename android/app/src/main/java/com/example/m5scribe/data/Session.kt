package com.example.m5scribe.data

import kotlinx.serialization.Serializable

/**
 * 文字起こしセッションのデータクラス
 *
 * Bluetooth接続から切断までの1回の文字起こしセッションを表す
 */
@Serializable
data class Session(
    val id: Int,                    // セッションID（ユニーク）
    val date: String,               // 日付（YYYY-MM-DD形式）
    val startTime: String,          // 開始時刻（HH:mm:ss形式）
    val endTime: String,            // 終了時刻（HH:mm:ss形式）
    val transcription: String,      // 文字起こし全文（タイムスタンプ付き）
    val summary: String? = null     // LLMによる要約（nullの場合は未要約）
) {
    /**
     * セッションの所要時間を分単位で計算
     */
    fun getDurationMinutes(): Int {
        return try {
            val start = startTime.split(":").map { it.toInt() }
            val end = endTime.split(":").map { it.toInt() }

            val startMinutes = start[0] * 60 + start[1]
            val endMinutes = end[0] * 60 + end[1]

            val diff = endMinutes - startMinutes
            if (diff < 0) diff + 24 * 60 else diff // 日をまたぐ場合の処理
        } catch (e: Exception) {
            0
        }
    }

    /**
     * 時刻範囲を表示用フォーマットで取得
     * 例: "10:30 - 10:45"
     */
    fun getTimeRange(): String {
        val start = startTime.substring(0, 5) // HH:mm
        val end = endTime.substring(0, 5)     // HH:mm
        return "$start - $end"
    }

    /**
     * 要約が存在するかチェック
     */
    fun hasSummary(): Boolean = !summary.isNullOrBlank()

    /**
     * 文字起こしのプレビューを取得（最初の数文字）
     *
     * @param maxLength プレビューの最大文字数（デフォルト: 60文字）
     * @return タイムスタンプを除いた文字起こしの冒頭部分
     */
    fun getTranscriptionPreview(maxLength: Int = 60): String {
        if (transcription.isBlank()) {
            return "（文字起こしなし）"
        }

        // タイムスタンプを除去してテキストのみを取得
        val textOnly = transcription
            .split("\n")
            .mapNotNull { line ->
                // タイムスタンプ部分（HH:mm:ss形式）を除去
                val parts = line.split("\t", limit = 2)
                if (parts.size > 1) {
                    parts[1].trim()  // タブの後のテキスト部分
                } else {
                    line.trim()  // タブがない場合はそのまま
                }
            }
            .filter { it.isNotBlank() }
            .joinToString(" ")

        // 最大文字数で切り詰め
        return if (textOnly.length > maxLength) {
            textOnly.substring(0, maxLength) + "..."
        } else {
            textOnly
        }
    }
}

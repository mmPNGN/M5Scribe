package com.example.m5scribe.ui

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.m5scribe.R
import com.example.m5scribe.data.Session
import com.example.m5scribe.data.SessionRepository
import com.example.m5scribe.databinding.ActivitySessionDetailBinding
import com.example.m5scribe.service.LLMSummarizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * セッション詳細画面
 *
 * セッションの文字起こし全文と要約を表示する
 * 要約生成、共有、削除機能を提供する
 */
class SessionDetailActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "SessionDetailActivity"
    }

    private lateinit var binding: ActivitySessionDetailBinding
    private lateinit var sessionRepository: SessionRepository
    private var sessionId: Int = -1
    private var isSummarizing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySessionDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize repository
        sessionRepository = SessionRepository(this)

        // Get session ID from intent
        sessionId = intent.getIntExtra("sessionId", -1)
        if (sessionId == -1) {
            finish()
            return
        }

        // Setup back button
        binding.backButton.setOnClickListener {
            finish()
        }

        // Setup summarize button
        binding.summarizeButton.setOnClickListener {
            summarizeSession()
        }

        // Setup share button
        binding.shareButton.setOnClickListener {
            shareSession()
        }

        // Setup delete button
        binding.deleteButton.setOnClickListener {
            showDeleteConfirmDialog()
        }

        // Load data
        loadData()
    }

    /**
     * データを読み込んで表示
     */
    private fun loadData() {
        val session = sessionRepository.getSessionById(sessionId)

        if (session == null) {
            Toast.makeText(this, "セッションが見つかりません", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // セッション情報を表示
        binding.sessionInfoText.text = getString(
            R.string.session_info,
            session.date,
            session.startTime.substring(0, 5),
            session.endTime.substring(0, 5),
            session.getDurationMinutes()
        )

        // 文字起こし全文を表示
        binding.transcriptionText.text = session.transcription

        // 要約を表示
        if (session.hasSummary()) {
            binding.summaryText.text = session.summary
            binding.summarizeButton.text = "要約を再生成"
        } else {
            binding.summaryText.text = getString(R.string.summary_placeholder)
            binding.summarizeButton.text = getString(R.string.button_summarize)
        }
    }

    /**
     * セッションを要約
     */
    private fun summarizeSession() {
        if (isSummarizing) {
            Log.d(TAG, "Already summarizing, ignoring request")
            return
        }

        val session = sessionRepository.getSessionById(sessionId)
        if (session == null) {
            Toast.makeText(this, "セッションが見つかりません", Toast.LENGTH_SHORT).show()
            return
        }

        // 文字起こしが空の場合
        if (session.transcription.isBlank()) {
            Toast.makeText(this, "文字起こしテキストが空です", Toast.LENGTH_SHORT).show()
            return
        }

        // 確認ダイアログを表示（再要約の場合）
        if (session.hasSummary()) {
            AlertDialog.Builder(this)
                .setTitle("要約を再生成")
                .setMessage("既存の要約を上書きしますか？")
                .setPositiveButton("再生成") { _, _ ->
                    executeSummarization(session)
                }
                .setNegativeButton("キャンセル", null)
                .show()
        } else {
            executeSummarization(session)
        }
    }

    /**
     * 要約を実行
     */
    private fun executeSummarization(session: Session) {
        isSummarizing = true

        // UIを更新（ローディング状態）
        binding.summarizeButton.isEnabled = false
        binding.summarizeButton.text = "要約中..."

        Log.d(TAG, "Starting summarization for session ${session.id}")

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // LLMで要約を生成
                val summarizer = LLMSummarizer(this@SessionDetailActivity)
                val result = summarizer.summarize(session.transcription)

                withContext(Dispatchers.Main) {
                    if (result.isSuccess) {
                        // 成功時の処理
                        val summary = result.getOrNull()!!
                        Log.d(TAG, "Summarization successful, summary length: ${summary.length}")

                        // セッションを更新
                        val updatedSession = session.copy(summary = summary)
                        val saved = sessionRepository.updateSession(sessionId, updatedSession)

                        if (saved) {
                            // UIを更新
                            binding.summaryText.text = summary
                            binding.summarizeButton.text = "要約を再生成"
                            Toast.makeText(
                                this@SessionDetailActivity,
                                "要約が完成しました",
                                Toast.LENGTH_SHORT
                            ).show()
                            Log.d(TAG, "Summary saved successfully")
                        } else {
                            Toast.makeText(
                                this@SessionDetailActivity,
                                "要約の保存に失敗しました",
                                Toast.LENGTH_SHORT
                            ).show()
                            Log.e(TAG, "Failed to save summary")
                        }
                    } else {
                        // 失敗時の処理
                        val error = result.exceptionOrNull()
                        Log.e(TAG, "Summarization failed: ${error?.message}", error)

                        // エラーダイアログを表示
                        showErrorDialog(error?.message ?: "要約の生成に失敗しました")
                    }

                    // UIを復元
                    binding.summarizeButton.isEnabled = true
                    if (!result.isSuccess) {
                        binding.summarizeButton.text = if (session.hasSummary()) {
                            "要約を再生成"
                        } else {
                            getString(R.string.button_summarize)
                        }
                    }
                    isSummarizing = false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error during summarization", e)
                withContext(Dispatchers.Main) {
                    showErrorDialog("予期しないエラーが発生しました: ${e.message}")
                    binding.summarizeButton.isEnabled = true
                    binding.summarizeButton.text = if (session.hasSummary()) {
                        "要約を再生成"
                    } else {
                        getString(R.string.button_summarize)
                    }
                    isSummarizing = false
                }
            }
        }
    }

    /**
     * エラーダイアログを表示
     */
    private fun showErrorDialog(message: String) {
        AlertDialog.Builder(this)
            .setTitle("要約エラー")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .setNeutralButton("設定画面へ") { _, _ ->
                // 設定画面を開く
                val intent = Intent(this, SettingsActivity::class.java)
                startActivity(intent)
            }
            .show()
    }

    /**
     * セッションを共有
     */
    private fun shareSession() {
        val session = sessionRepository.getSessionById(sessionId) ?: return

        val shareText = buildString {
            append("【M5Scribe 文字起こし】\n")
            append("日時: ${session.date} ${session.getTimeRange()}\n")
            append("所要時間: ${session.getDurationMinutes()}分\n\n")

            if (session.hasSummary()) {
                append("【要約】\n")
                append("${session.summary}\n\n")
            }

            append("【文字起こし全文】\n")
            append(session.transcription)
        }

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, shareText)
        }

        startActivity(Intent.createChooser(intent, "文字起こしを共有"))
    }

    /**
     * 削除確認ダイアログを表示
     */
    private fun showDeleteConfirmDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_confirm_title)
            .setMessage(R.string.delete_confirm_message)
            .setPositiveButton(R.string.delete_confirm_yes) { _, _ ->
                deleteSession()
            }
            .setNegativeButton(R.string.delete_confirm_no, null)
            .show()
    }

    /**
     * セッションを削除
     */
    private fun deleteSession() {
        val deleted = sessionRepository.deleteSession(sessionId)

        if (deleted) {
            Toast.makeText(this, R.string.toast_session_deleted, Toast.LENGTH_SHORT).show()
            finish()
        } else {
            Toast.makeText(this, "削除に失敗しました", Toast.LENGTH_SHORT).show()
        }
    }
}

package com.example.m5scribe.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.m5scribe.data.SessionRepository
import com.example.m5scribe.databinding.ActivitySessionListBinding

/**
 * セッション一覧画面
 *
 * 選択された日付のセッション一覧を表示する
 */
class SessionListActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySessionListBinding
    private lateinit var sessionRepository: SessionRepository
    private lateinit var sessionAdapter: SessionAdapter
    private var selectedDate: String = ""
    private var isSelectionMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySessionListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize repository
        sessionRepository = SessionRepository(this)

        // Get selected date from intent
        selectedDate = intent.getStringExtra("date") ?: ""
        if (selectedDate.isEmpty()) {
            finish()
            return
        }

        // Set date text
        binding.selectedDateText.text = selectedDate

        // Setup RecyclerView
        sessionAdapter = SessionAdapter(
            onSessionClick = { session ->
                // セッションがクリックされたら詳細画面へ遷移
                val intent = Intent(this, SessionDetailActivity::class.java)
                intent.putExtra("sessionId", session.id)
                startActivity(intent)
            },
            onSelectionModeChanged = { enabled ->
                isSelectionMode = enabled
                updateSelectionModeUI()
            }
        )

        binding.sessionsRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@SessionListActivity)
            adapter = sessionAdapter
        }

        // Setup back button
        binding.backButton.setOnClickListener {
            if (isSelectionMode) {
                // 選択モード時は選択をキャンセル
                sessionAdapter.exitSelectionMode()
            } else {
                finish()
            }
        }

        // Setup delete button
        binding.deleteButton.setOnClickListener {
            showDeleteConfirmationDialog()
        }

        // Load data
        loadData()
    }

    override fun onResume() {
        super.onResume()
        // 画面に戻ってきたときにデータを再読み込み
        loadData()
    }

    /**
     * データを読み込んで表示
     */
    private fun loadData() {
        val sessions = sessionRepository.getSessionsByDate(selectedDate)

        if (sessions.isEmpty()) {
            // データがない場合
            binding.sessionsRecyclerView.visibility = View.GONE
            binding.emptyStateLayout.visibility = View.VISIBLE
        } else {
            // データがある場合
            binding.sessionsRecyclerView.visibility = View.VISIBLE
            binding.emptyStateLayout.visibility = View.GONE

            sessionAdapter.updateData(sessions)
        }
    }

    /**
     * 選択モードのUIを更新
     */
    private fun updateSelectionModeUI() {
        if (isSelectionMode) {
            // 選択モード時
            binding.deleteButton.visibility = View.VISIBLE
            binding.titleText.text = "${sessionAdapter.getSelectedCount()}件選択中"
        } else {
            // 通常モード時
            binding.deleteButton.visibility = View.GONE
            binding.titleText.text = getString(com.example.m5scribe.R.string.session_list_title)
        }
    }

    /**
     * 削除確認ダイアログを表示
     */
    private fun showDeleteConfirmationDialog() {
        val selectedSessionIds = sessionAdapter.getSelectedSessionIds()
        if (selectedSessionIds.isEmpty()) return

        val message = if (selectedSessionIds.size == 1) {
            "選択したセッションを削除しますか？"
        } else {
            "選択した${selectedSessionIds.size}件のセッションを削除しますか？"
        }

        AlertDialog.Builder(this)
            .setTitle("削除の確認")
            .setMessage(message)
            .setPositiveButton("削除") { _, _ ->
                deleteSelectedSessions(selectedSessionIds)
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    /**
     * 選択されたセッションを削除
     */
    private fun deleteSelectedSessions(sessionIds: List<Int>) {
        val success = sessionRepository.deleteSessions(sessionIds)

        if (success) {
            // 削除成功
            android.widget.Toast.makeText(
                this,
                "削除しました",
                android.widget.Toast.LENGTH_SHORT
            ).show()

            // 選択モードを終了
            sessionAdapter.exitSelectionMode()

            // データを再読み込み
            loadData()

            // 全てのセッションが削除された場合は画面を閉じる
            val remainingSessions = sessionRepository.getSessionsByDate(selectedDate)
            if (remainingSessions.isEmpty()) {
                finish()
            }
        } else {
            // 削除失敗
            android.widget.Toast.makeText(
                this,
                "削除に失敗しました",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun onBackPressed() {
        if (isSelectionMode) {
            // 選択モード時は選択をキャンセル
            sessionAdapter.exitSelectionMode()
        } else {
            super.onBackPressed()
        }
    }
}

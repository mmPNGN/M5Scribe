package com.example.m5scribe.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.m5scribe.data.SessionRepository
import com.example.m5scribe.databinding.ActivityHistoryBinding

/**
 * 文字起こし履歴画面
 *
 * 文字起こしデータがある日付を一覧表示する
 */
class HistoryActivity : AppCompatActivity() {
    private lateinit var binding: ActivityHistoryBinding
    private lateinit var sessionRepository: SessionRepository
    private lateinit var dateAdapter: DateAdapter
    private var isSelectionMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize repository
        sessionRepository = SessionRepository(this)

        // Setup RecyclerView
        dateAdapter = DateAdapter(
            onDateClick = { date ->
                // 日付がクリックされたらセッション一覧画面へ遷移
                val intent = Intent(this, SessionListActivity::class.java)
                intent.putExtra("date", date)
                startActivity(intent)
            },
            onSelectionModeChanged = { enabled ->
                isSelectionMode = enabled
                updateSelectionModeUI()
            }
        )

        binding.datesRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@HistoryActivity)
            adapter = dateAdapter
        }

        // Setup back button
        binding.backButton.setOnClickListener {
            if (isSelectionMode) {
                // 選択モード時は選択をキャンセル
                dateAdapter.exitSelectionMode()
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
        val sessionsGroupedByDate = sessionRepository.getSessionsGroupedByDate()

        if (sessionsGroupedByDate.isEmpty()) {
            // データがない場合
            binding.datesRecyclerView.visibility = View.GONE
            binding.emptyStateLayout.visibility = View.VISIBLE
        } else {
            // データがある場合
            binding.datesRecyclerView.visibility = View.VISIBLE
            binding.emptyStateLayout.visibility = View.GONE

            // Map<日付, セッションリスト> から List<Pair<日付, セッション数>> に変換
            val dateList = sessionsGroupedByDate.map { (date, sessions) ->
                Pair(date, sessions.size)
            }

            dateAdapter.updateData(dateList)
        }
    }

    /**
     * 選択モードのUIを更新
     */
    private fun updateSelectionModeUI() {
        if (isSelectionMode) {
            // 選択モード時
            binding.deleteButton.visibility = View.VISIBLE
            binding.titleText.text = "${dateAdapter.getSelectedCount()}件選択中"
        } else {
            // 通常モード時
            binding.deleteButton.visibility = View.GONE
            binding.titleText.text = getString(com.example.m5scribe.R.string.history_title)
        }
    }

    /**
     * 削除確認ダイアログを表示
     */
    private fun showDeleteConfirmationDialog() {
        val selectedDates = dateAdapter.getSelectedDates()
        if (selectedDates.isEmpty()) return

        val message = if (selectedDates.size == 1) {
            "選択した日付のすべてのセッションを削除しますか？"
        } else {
            "選択した${selectedDates.size}個の日付のすべてのセッションを削除しますか？"
        }

        AlertDialog.Builder(this)
            .setTitle("削除の確認")
            .setMessage(message)
            .setPositiveButton("削除") { _, _ ->
                deleteSelectedDates(selectedDates)
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    /**
     * 選択された日付のセッションを削除
     */
    private fun deleteSelectedDates(dates: List<String>) {
        val success = sessionRepository.deleteSessionsByDates(dates)

        if (success) {
            // 削除成功
            android.widget.Toast.makeText(
                this,
                "削除しました",
                android.widget.Toast.LENGTH_SHORT
            ).show()

            // 選択モードを終了
            dateAdapter.exitSelectionMode()

            // データを再読み込み
            loadData()
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
            dateAdapter.exitSelectionMode()
        } else {
            super.onBackPressed()
        }
    }
}

package com.example.m5scribe.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.m5scribe.R
import com.example.m5scribe.data.Session

/**
 * セッションリストのRecyclerView Adapter
 */
class SessionAdapter(
    private val onSessionClick: (Session) -> Unit,
    private val onSelectionModeChanged: ((Boolean) -> Unit)? = null
) : RecyclerView.Adapter<SessionAdapter.SessionViewHolder>() {

    private var sessionList: List<Session> = emptyList()
    private var isSelectionMode = false
    private val selectedSessionIds = mutableSetOf<Int>()

    /**
     * データを更新
     */
    fun updateData(newSessionList: List<Session>) {
        sessionList = newSessionList
        notifyDataSetChanged()
    }

    /**
     * 選択モードを開始
     */
    fun startSelectionMode(initialSessionId: Int? = null) {
        isSelectionMode = true
        selectedSessionIds.clear()
        initialSessionId?.let { selectedSessionIds.add(it) }
        notifyDataSetChanged()
        onSelectionModeChanged?.invoke(true)
    }

    /**
     * 選択モードを終了
     */
    fun exitSelectionMode() {
        isSelectionMode = false
        selectedSessionIds.clear()
        notifyDataSetChanged()
        onSelectionModeChanged?.invoke(false)
    }

    /**
     * 選択されたセッションIDリストを取得
     */
    fun getSelectedSessionIds(): List<Int> = selectedSessionIds.toList()

    /**
     * 選択されたアイテム数を取得
     */
    fun getSelectedCount(): Int = selectedSessionIds.size

    /**
     * セッションの選択状態をトグル
     */
    private fun toggleSelection(sessionId: Int) {
        if (selectedSessionIds.contains(sessionId)) {
            selectedSessionIds.remove(sessionId)
        } else {
            selectedSessionIds.add(sessionId)
        }

        // 選択が全てキャンセルされたら選択モードを終了
        if (selectedSessionIds.isEmpty()) {
            exitSelectionMode()
        } else {
            notifyDataSetChanged()
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SessionViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_session, parent, false)
        return SessionViewHolder(view)
    }

    override fun onBindViewHolder(holder: SessionViewHolder, position: Int) {
        val session = sessionList[position]
        holder.bind(session)
    }

    override fun getItemCount(): Int = sessionList.size

    inner class SessionViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val timeRangeText: TextView = itemView.findViewById(R.id.timeRangeText)
        private val durationText: TextView = itemView.findViewById(R.id.durationText)
        private val summaryStatusText: TextView = itemView.findViewById(R.id.summaryStatusText)
        private val transcriptionPreviewText: TextView = itemView.findViewById(R.id.transcriptionPreviewText)
        private val checkbox: CheckBox = itemView.findViewById(R.id.itemCheckbox)
        private val sessionIcon: ImageView = itemView.findViewById(R.id.sessionIcon)

        fun bind(session: Session) {
            timeRangeText.text = session.getTimeRange()
            durationText.text = itemView.context.getString(R.string.session_duration, session.getDurationMinutes())

            if (session.hasSummary()) {
                summaryStatusText.text = " • ${itemView.context.getString(R.string.session_has_summary)}"
                summaryStatusText.visibility = View.VISIBLE
            } else {
                summaryStatusText.visibility = View.GONE
            }

            // 文字起こしプレビューを表示
            transcriptionPreviewText.text = session.getTranscriptionPreview(maxLength = 60)

            // チェックボックスの表示/非表示
            if (isSelectionMode) {
                checkbox.visibility = View.VISIBLE
                checkbox.isChecked = selectedSessionIds.contains(session.id)
            } else {
                checkbox.visibility = View.GONE
            }

            // クリックリスナー
            itemView.setOnClickListener {
                if (isSelectionMode) {
                    toggleSelection(session.id)
                } else {
                    onSessionClick(session)
                }
            }

            // 長押しリスナー（選択モード開始）
            itemView.setOnLongClickListener {
                if (!isSelectionMode) {
                    startSelectionMode(session.id)
                    true
                } else {
                    false
                }
            }
        }
    }
}

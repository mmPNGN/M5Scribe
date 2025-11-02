package com.example.m5scribe.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.m5scribe.R

/**
 * 日付リストのRecyclerView Adapter
 */
class DateAdapter(
    private val onDateClick: (String) -> Unit,
    private val onSelectionModeChanged: ((Boolean) -> Unit)? = null
) : RecyclerView.Adapter<DateAdapter.DateViewHolder>() {

    private var dateList: List<Pair<String, Int>> = emptyList() // Pair<日付, セッション数>
    private var isSelectionMode = false
    private val selectedDates = mutableSetOf<String>()

    /**
     * データを更新
     */
    fun updateData(newDateList: List<Pair<String, Int>>) {
        dateList = newDateList
        notifyDataSetChanged()
    }

    /**
     * 選択モードを開始
     */
    fun startSelectionMode(initialDate: String? = null) {
        isSelectionMode = true
        selectedDates.clear()
        initialDate?.let { selectedDates.add(it) }
        notifyDataSetChanged()
        onSelectionModeChanged?.invoke(true)
    }

    /**
     * 選択モードを終了
     */
    fun exitSelectionMode() {
        isSelectionMode = false
        selectedDates.clear()
        notifyDataSetChanged()
        onSelectionModeChanged?.invoke(false)
    }

    /**
     * 選択された日付リストを取得
     */
    fun getSelectedDates(): List<String> = selectedDates.toList()

    /**
     * 選択されたアイテム数を取得
     */
    fun getSelectedCount(): Int = selectedDates.size

    /**
     * 日付の選択状態をトグル
     */
    private fun toggleSelection(date: String) {
        if (selectedDates.contains(date)) {
            selectedDates.remove(date)
        } else {
            selectedDates.add(date)
        }

        // 選択が全てキャンセルされたら選択モードを終了
        if (selectedDates.isEmpty()) {
            exitSelectionMode()
        } else {
            notifyDataSetChanged()
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DateViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_date, parent, false)
        return DateViewHolder(view)
    }

    override fun onBindViewHolder(holder: DateViewHolder, position: Int) {
        val (date, sessionCount) = dateList[position]
        holder.bind(date, sessionCount)
    }

    override fun getItemCount(): Int = dateList.size

    inner class DateViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val dateText: TextView = itemView.findViewById(R.id.dateText)
        private val sessionCountText: TextView = itemView.findViewById(R.id.sessionCountText)
        private val checkbox: CheckBox = itemView.findViewById(R.id.itemCheckbox)
        private val dateIcon: ImageView = itemView.findViewById(R.id.dateIcon)

        fun bind(date: String, sessionCount: Int) {
            dateText.text = date
            sessionCountText.text = itemView.context.getString(R.string.history_session_count, sessionCount)

            // チェックボックスの表示/非表示
            if (isSelectionMode) {
                checkbox.visibility = View.VISIBLE
                checkbox.isChecked = selectedDates.contains(date)
            } else {
                checkbox.visibility = View.GONE
            }

            // クリックリスナー
            itemView.setOnClickListener {
                if (isSelectionMode) {
                    toggleSelection(date)
                } else {
                    onDateClick(date)
                }
            }

            // 長押しリスナー（選択モード開始）
            itemView.setOnLongClickListener {
                if (!isSelectionMode) {
                    startSelectionMode(date)
                    true
                } else {
                    false
                }
            }
        }
    }
}

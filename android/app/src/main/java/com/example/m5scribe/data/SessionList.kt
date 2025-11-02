package com.example.m5scribe.data

import kotlinx.serialization.Serializable

/**
 * セッションリストのデータクラス
 *
 * 全てのセッションデータを保持し、JSON形式でシリアライズされる
 */
@Serializable
data class SessionList(
    val sessions: MutableList<Session> = mutableListOf()
) {
    /**
     * 新しいセッションを追加
     */
    fun addSession(session: Session) {
        sessions.add(session)
    }

    /**
     * セッションを削除
     */
    fun removeSession(sessionId: Int): Boolean {
        return sessions.removeIf { it.id == sessionId }
    }

    /**
     * 複数のセッションをIDで削除
     */
    fun removeSessions(sessionIds: List<Int>): Boolean {
        if (sessionIds.isEmpty()) return false
        return sessions.removeIf { it.id in sessionIds }
    }

    /**
     * 指定した日付のすべてのセッションを削除
     */
    fun removeSessionsByDate(date: String): Boolean {
        return sessions.removeIf { it.date == date }
    }

    /**
     * 複数の日付のすべてのセッションを削除
     */
    fun removeSessionsByDates(dates: List<String>): Boolean {
        if (dates.isEmpty()) return false
        return sessions.removeIf { it.date in dates }
    }

    /**
     * セッションを更新（要約追加など）
     */
    fun updateSession(sessionId: Int, updatedSession: Session): Boolean {
        val index = sessions.indexOfFirst { it.id == sessionId }
        if (index != -1) {
            sessions[index] = updatedSession
            return true
        }
        return false
    }

    /**
     * IDでセッションを取得
     */
    fun getSessionById(sessionId: Int): Session? {
        return sessions.find { it.id == sessionId }
    }

    /**
     * 日付でセッションをグループ化
     * 返り値: Map<日付, その日のセッションリスト>
     */
    fun groupByDate(): Map<String, List<Session>> {
        return sessions
            .groupBy { it.date }
            .toSortedMap(compareByDescending { it }) // 日付降順（新しい順）
    }

    /**
     * 特定の日付のセッションを取得
     */
    fun getSessionsByDate(date: String): List<Session> {
        return sessions
            .filter { it.date == date }
            .sortedBy { it.startTime } // 時刻昇順
    }

    /**
     * 次のセッションIDを生成
     */
    fun getNextId(): Int {
        return (sessions.maxOfOrNull { it.id } ?: 0) + 1
    }

    /**
     * 全セッション数を取得
     */
    fun getSessionCount(): Int = sessions.size

    /**
     * 特定の日付のセッション数を取得
     */
    fun getSessionCountByDate(date: String): Int {
        return sessions.count { it.date == date }
    }
}

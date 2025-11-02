package com.example.m5scribe.data

import android.content.Context
import android.util.Log
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

/**
 * セッションデータのリポジトリ
 *
 * JSONファイルへの読み書きを管理する
 */
class SessionRepository(private val context: Context) {
    companion object {
        private const val TAG = "SessionRepository"
        private const val FILE_NAME = "sessions.json"
    }

    // JSON設定（Pretty printと未知のキーを無視）
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    // セッションリストのキャッシュ
    private var cachedSessionList: SessionList? = null

    /**
     * セッションリストを読み込む
     */
    fun loadSessions(): SessionList {
        // キャッシュがあればそれを返す
        cachedSessionList?.let { return it }

        val file = File(context.filesDir, FILE_NAME)

        return try {
            if (file.exists()) {
                val jsonString = file.readText()
                val sessionList = json.decodeFromString<SessionList>(jsonString)
                Log.d(TAG, "Loaded ${sessionList.getSessionCount()} sessions from $FILE_NAME")
                cachedSessionList = sessionList
                sessionList
            } else {
                Log.d(TAG, "No sessions file found, creating new empty list")
                val newList = SessionList()
                cachedSessionList = newList
                newList
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load sessions: ${e.message}", e)
            // ファイルが壊れている場合は空のリストを返す
            val newList = SessionList()
            cachedSessionList = newList
            newList
        }
    }

    /**
     * セッションリストを保存
     */
    fun saveSessions(sessionList: SessionList): Boolean {
        val file = File(context.filesDir, FILE_NAME)

        return try {
            val jsonString = json.encodeToString(sessionList)
            file.writeText(jsonString)
            cachedSessionList = sessionList // キャッシュを更新
            Log.d(TAG, "Saved ${sessionList.getSessionCount()} sessions to $FILE_NAME")
            true
        } catch (e: IOException) {
            Log.e(TAG, "Failed to save sessions: ${e.message}", e)
            false
        }
    }

    /**
     * 新しいセッションを追加
     */
    fun addSession(session: Session): Boolean {
        val sessionList = loadSessions()
        sessionList.addSession(session)
        return saveSessions(sessionList)
    }

    /**
     * セッションを削除
     */
    fun deleteSession(sessionId: Int): Boolean {
        val sessionList = loadSessions()
        val removed = sessionList.removeSession(sessionId)
        if (removed) {
            return saveSessions(sessionList)
        }
        return false
    }

    /**
     * 複数のセッションを削除
     */
    fun deleteSessions(sessionIds: List<Int>): Boolean {
        val sessionList = loadSessions()
        val removed = sessionList.removeSessions(sessionIds)
        if (removed) {
            return saveSessions(sessionList)
        }
        return false
    }

    /**
     * 指定した日付のすべてのセッションを削除
     */
    fun deleteSessionsByDate(date: String): Boolean {
        val sessionList = loadSessions()
        val removed = sessionList.removeSessionsByDate(date)
        if (removed) {
            return saveSessions(sessionList)
        }
        return false
    }

    /**
     * 複数の日付のすべてのセッションを削除
     */
    fun deleteSessionsByDates(dates: List<String>): Boolean {
        val sessionList = loadSessions()
        val removed = sessionList.removeSessionsByDates(dates)
        if (removed) {
            return saveSessions(sessionList)
        }
        return false
    }

    /**
     * セッションを更新（要約追加など）
     */
    fun updateSession(sessionId: Int, updatedSession: Session): Boolean {
        val sessionList = loadSessions()
        val updated = sessionList.updateSession(sessionId, updatedSession)
        if (updated) {
            return saveSessions(sessionList)
        }
        return false
    }

    /**
     * IDでセッションを取得
     */
    fun getSessionById(sessionId: Int): Session? {
        return loadSessions().getSessionById(sessionId)
    }

    /**
     * 日付でセッションをグループ化
     */
    fun getSessionsGroupedByDate(): Map<String, List<Session>> {
        return loadSessions().groupByDate()
    }

    /**
     * 特定の日付のセッションを取得
     */
    fun getSessionsByDate(date: String): List<Session> {
        return loadSessions().getSessionsByDate(date)
    }

    /**
     * 次のセッションIDを生成
     */
    fun getNextSessionId(): Int {
        return loadSessions().getNextId()
    }

    /**
     * キャッシュをクリア（テスト用）
     */
    fun clearCache() {
        cachedSessionList = null
    }

    /**
     * JSONファイルを削除（テスト用）
     */
    fun deleteAllSessions(): Boolean {
        val file = File(context.filesDir, FILE_NAME)
        cachedSessionList = null
        return try {
            if (file.exists()) {
                file.delete()
            } else {
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete sessions file: ${e.message}", e)
            false
        }
    }
}

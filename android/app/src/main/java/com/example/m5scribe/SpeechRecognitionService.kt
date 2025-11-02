package com.example.m5scribe

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * 音声認識サービス
 * 端末のマイクから音声を録音してAndroidの音声認識エンジンで文字起こしする
 *
 * 特徴:
 * - 端末の最適な音声認識エンジンを自動選択（Pixelなら高精度エンジン）
 * - リアルタイム文字起こし（連続認識）
 * - オフライン対応（端末がサポートしている場合）
 *
 * 動作:
 * Bluetoothから受信した音声をスピーカーで再生し、その音を端末のマイクで拾って認識
 */
class SpeechRecognitionService(
    private val context: Context,
    private val onPartialResult: (String) -> Unit,
    private val onFinalResult: (String) -> Unit,
    private val onError: (String) -> Unit,
    private val onDebug: ((String) -> Unit)? = null  // デバッグ用コールバック
) {
    companion object {
        private const val TAG = "SpeechRecognitionSvc"
    }

    private var speechRecognizer: SpeechRecognizer? = null
    private var isRecognizing = false
    private var shouldRestart = false
    @Volatile private var lastPartialResult: String = ""  // 最後の部分結果を保存
    private var lastRmsReportTime: Long = 0  // 最後に音量を報告した時刻
    private val partialResultLock = Object()  // 同期用ロック
    private var sessionCounter = 0  // セッションカウンター（デバッグ用）

    init {
        // 音声認識の利用可能性をチェック
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.e(TAG, "Speech recognition is not available on this device")
            onError("この端末では音声認識が利用できません")
        }
    }

    /**
     * 音声認識を開始
     */
    fun startRecognition() {
        if (isRecognizing) {
            Log.w(TAG, "Recognition is already running")
            return
        }

        try {
            shouldRestart = true
            startListening()
            Log.d(TAG, "Speech recognition started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recognition", e)
            onError("音声認識の開始に失敗しました: ${e.message}")
        }
    }

    /**
     * 音声認識のリスニングを開始
     */
    private fun startListening() {
        if (!shouldRestart) return

        // SpeechRecognizerを初期化（端末の最適なエンジンを自動選択）
        if (speechRecognizer == null) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(createRecognitionListener())
            }
        }

        val recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            // 認識モデル（自由形式の音声認識）
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)

            // 言語設定（日本語）
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ja-JP")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ja-JP")

            // 部分的な認識結果を取得
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)

            // オフライン認識を優先（利用可能な場合）
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)

            // 認識結果の最大数
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)

            // 無音検出の設定（句読点の代わりに無音で区切る）
            // 最小認識継続時間：音声認識を開始してから最低5秒は認識を続ける
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 2000)

            // 完全な無音と判断する時間：0.8秒無音が続いたら発話完了とみなして確定結果を出力
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 150)

            // 発話完了の可能性がある無音時間：0.8秒無音で早めに認識終了
            // （COMPLETE_SILENCEと同じ値 = 確実に区切る）
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 150)
        }

        try {
            // 新しいセッション開始前に、前回の部分結果を確定結果として出力
            val previousPartial = synchronized(partialResultLock) {
                val temp = lastPartialResult
                if (temp.isNotBlank()) {
                    lastPartialResult = ""  // クリア
                }
                temp
            }

            if (previousPartial.isNotBlank()) {
                Log.d(TAG, "Flushing previous partial result: $previousPartial")
                onFinalResult(previousPartial)
            }

            isRecognizing = true
            sessionCounter++
            speechRecognizer?.startListening(recognizerIntent)
            Log.d(TAG, "Started listening (session #$sessionCounter)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start listening", e)
            isRecognizing = false

            // 少し待ってから再試行
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (shouldRestart) {
                    startListening()
                }
            }, 1000)
        }
    }

    /**
     * 音声認識のリスナーを作成
     */
    private fun createRecognitionListener() = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            Log.d(TAG, "Ready for speech")
            // デバッグ出力なし
        }

        override fun onBeginningOfSpeech() {
            Log.d(TAG, "Speech detected")
            // デバッグ出力なし
        }

        override fun onRmsChanged(rmsdB: Float) {
            // 音量レベルの変化（デバッグ出力なし）
        }

        override fun onBufferReceived(buffer: ByteArray?) {
            // 音声バッファ受信（通常は使用しない）
        }

        override fun onEndOfSpeech() {
            Log.d(TAG, "End of speech")
            isRecognizing = false
        }

        override fun onError(error: Int) {
            val errorMessage = when (error) {
                SpeechRecognizer.ERROR_AUDIO -> "音声エラー"
                SpeechRecognizer.ERROR_CLIENT -> "クライアントエラー"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "権限が不足しています"
                SpeechRecognizer.ERROR_NETWORK -> "ネットワークエラー"
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "ネットワークタイムアウト"
                SpeechRecognizer.ERROR_NO_MATCH -> "認識結果なし"
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "認識エンジンがビジー状態"
                SpeechRecognizer.ERROR_SERVER -> "サーバーエラー"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "音声タイムアウト"
                else -> "不明なエラー: $error"
            }

            Log.e(TAG, "Recognition error: $errorMessage (code: $error)")
            Log.d(TAG, "Last partial result before error: '$lastPartialResult'")
            isRecognizing = false

            // エラーの種類によって処理を分ける
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    // 部分結果がある場合は、それを確定結果として扱う
                    val partialToUse = synchronized(partialResultLock) {
                        val temp = lastPartialResult
                        if (temp.isNotBlank()) {
                            lastPartialResult = ""  // クリア
                        }
                        temp
                    }

                    if (partialToUse.isNotBlank()) {
                        Log.d(TAG, ">>> Using last partial result as final: $partialToUse")
                        onFinalResult(partialToUse)
                    } else {
                        Log.w(TAG, ">>> No partial result to use as final")
                    }

                    // 音声が認識されなかった場合は自動的に再開
                    Log.d(TAG, "No speech detected, restarting...")
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        if (shouldRestart) {
                            startListening()
                        }
                    }, 500)
                }
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                    // ビジー状態の場合は少し待って再試行
                    Log.d(TAG, "Recognizer busy, retrying...")
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        if (shouldRestart) {
                            startListening()
                        }
                    }, 1000)
                }
                SpeechRecognizer.ERROR_CLIENT -> {
                    // クライアントエラーの場合は認識を停止して再初期化
                    Log.e(TAG, "Client error detected, reinitializing...")
                    speechRecognizer?.destroy()
                    speechRecognizer = null
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        if (shouldRestart) {
                            startListening()
                        }
                    }, 1000)
                }
                else -> {
                    // その他のエラーはユーザーに通知
                    onError(errorMessage)
                    // それでも再試行
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        if (shouldRestart) {
                            startListening()
                        }
                    }, 2000)
                }
            }
        }

        override fun onResults(results: Bundle?) {
            // 確定した認識結果
            var hasResult = false

            results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.let { matches ->
                if (matches.isNotEmpty()) {
                    val result = matches[0]
                    Log.d(TAG, "Final result: $result")
                    onFinalResult(result)
                    synchronized(partialResultLock) {
                        lastPartialResult = ""  // クリア
                    }
                    hasResult = true
                }
            }

            // resultsがnullまたは空で、部分結果がある場合は確定結果として使う
            if (!hasResult) {
                val partialToUse = synchronized(partialResultLock) {
                    val temp = lastPartialResult
                    if (temp.isNotBlank()) {
                        lastPartialResult = ""  // クリア
                    }
                    temp
                }

                if (partialToUse.isNotBlank()) {
                    Log.d(TAG, "Using partial as final in onResults: $partialToUse")
                    onFinalResult(partialToUse)
                }
            }

            isRecognizing = false

            // 連続認識のため自動的に再開
            if (shouldRestart) {
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    if (shouldRestart) {
                        startListening()
                    }
                }, 300)
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            // 部分的な認識結果（リアルタイム）
            partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.let { matches ->
                if (matches.isNotEmpty()) {
                    val result = matches[0]
                    // 空でない場合のみ保存（空の部分結果で上書きしない）
                    if (result.isNotBlank()) {
                        synchronized(partialResultLock) {
                            lastPartialResult = result  // 最後の部分結果を保存
                        }
                        Log.d(TAG, "Partial result: $result (saved as lastPartialResult)")
                        onPartialResult(result)
                    } else {
                        Log.d(TAG, "Partial result is blank, not updating lastPartialResult")
                    }
                }
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {
            // カスタムイベント（通常は使用しない）
        }
    }

    /**
     * 音声データを処理（このメソッドは互換性のために残すが使用しない）
     */
    @Deprecated("This method is not used in microphone-based recognition")
    fun processAudioData(audioData: ByteArray) {
        // マイクベースの認識では使用しない
    }

    /**
     * 音声認識を停止
     */
    fun stopRecognition() {
        Log.d(TAG, "Stopping recognition")
        shouldRestart = false
        isRecognizing = false

        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.destroy()
            speechRecognizer = null
            Log.d(TAG, "Speech recognition stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping recognition", e)
        }
    }

    /**
     * リソースのクリーンアップ
     */
    fun release() {
        stopRecognition()
    }
}

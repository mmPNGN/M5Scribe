package com.example.m5scribe

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat

/**
 * バックグラウンドで文字起こしを実行するForeground Service
 *
 * 画面がOFFの状態でも音声認識を継続する
 */
class TranscriptionForegroundService : Service() {
    companion object {
        private const val TAG = "TranscriptionService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "transcription_service_channel"

        const val ACTION_START = "com.example.m5scribe.START_TRANSCRIPTION"
        const val ACTION_STOP = "com.example.m5scribe.STOP_TRANSCRIPTION"
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var speechRecognitionService: SpeechRecognitionService? = null
    private var isTranscribing = false

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")

        // WakeLockを取得（CPUをスリープさせない）
        acquireWakeLock()

        // 通知チャンネルを作成
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: ${intent?.action}")

        when (intent?.action) {
            ACTION_START -> {
                startForegroundService()
                startTranscription()
            }
            ACTION_STOP -> {
                stopTranscription()
                stopSelf()
            }
        }

        // サービスが強制終了された場合は再起動
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Foreground Serviceとして開始
     */
    private fun startForegroundService() {
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)
        Log.d(TAG, "Started as foreground service")
    }

    /**
     * 通知チャンネルを作成
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_description)
                setShowBadge(false)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * 通知を作成
     */
    private fun createNotification(): Notification {
        // MainActivityを開くIntent
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val openPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // 停止するIntent
        val stopIntent = Intent(this, TranscriptionForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(openPendingIntent)
            .addAction(
                android.R.drawable.ic_media_pause,
                getString(R.string.notification_action_stop),
                stopPendingIntent
            )
            .setOngoing(true)
            .build()
    }

    /**
     * WakeLockを取得
     */
    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "M5Scribe::TranscriptionWakeLock"
        ).apply {
            acquire(10 * 60 * 1000L) // 10分間有効
        }
        Log.d(TAG, "WakeLock acquired")
    }

    /**
     * 文字起こしを開始
     */
    private fun startTranscription() {
        if (isTranscribing) {
            Log.w(TAG, "Transcription already running")
            return
        }

        // 権限チェック
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "RECORD_AUDIO permission not granted")
            stopSelf()
            return
        }

        speechRecognitionService = SpeechRecognitionService(
            context = this,
            onPartialResult = { text ->
                // 部分結果はブロードキャストでMainActivityに送信
                val intent = Intent("com.example.m5scribe.PARTIAL_RESULT").apply {
                    putExtra("text", text)
                    setPackage(packageName)  // 明示的にパッケージを指定
                }
                sendBroadcast(intent)
                Log.d(TAG, "Sent PARTIAL_RESULT broadcast: $text")
            },
            onFinalResult = { text ->
                // 確定結果はブロードキャストでMainActivityに送信
                val intent = Intent("com.example.m5scribe.FINAL_RESULT").apply {
                    putExtra("text", text)
                    setPackage(packageName)  // 明示的にパッケージを指定
                }
                sendBroadcast(intent)
                Log.d(TAG, "Sent FINAL_RESULT broadcast: $text")
            },
            onError = { error ->
                Log.e(TAG, "Transcription error: $error")
            }
        )

        try {
            speechRecognitionService?.startRecognition()
            isTranscribing = true
            Log.d(TAG, "Transcription started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start transcription", e)
            stopSelf()
        }
    }

    /**
     * 文字起こしを停止
     */
    private fun stopTranscription() {
        speechRecognitionService?.stopRecognition()
        speechRecognitionService = null
        isTranscribing = false
        Log.d(TAG, "Transcription stopped")
    }

    override fun onDestroy() {
        super.onDestroy()
        stopTranscription()

        // WakeLockを解放
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }

        Log.d(TAG, "Service destroyed")
    }
}

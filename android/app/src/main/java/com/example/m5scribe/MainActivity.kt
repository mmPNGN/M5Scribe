package com.example.m5scribe

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.edit
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.example.m5scribe.databinding.ActivityMainBinding
import com.example.m5scribe.data.Session
import com.example.m5scribe.data.SessionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var bluetoothAdapter: BluetoothAdapter
    private var bluetoothService: BluetoothAudioService? = null
    private val transcriptionBuilder = StringBuilder()

    // セッション管理
    private lateinit var sessionRepository: SessionRepository
    private var currentSessionId: Int? = null
    private var currentSessionStartTime: String? = null
    private var isReceiverRegistered = false

    // BroadcastReceiver for receiving transcription results from service and disconnect requests
    private val transcriptionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d("MainActivity", "Broadcast received: ${intent?.action}")
            when (intent?.action) {
                "com.example.m5scribe.PARTIAL_RESULT" -> {
                    val text = intent.getStringExtra("text") ?: return
                    Log.d("MainActivity", "Partial result received: $text")
                    runOnUiThread {
                        updatePartialTranscription(text)
                    }
                }
                "com.example.m5scribe.FINAL_RESULT" -> {
                    val text = intent.getStringExtra("text") ?: return
                    Log.d("MainActivity", "Final result received: $text")
                    runOnUiThread {
                        appendTranscription(text)
                    }
                }
                "com.example.m5scribe.DISCONNECT_REQUEST" -> {
                    Log.d("MainActivity", "Disconnect request received")
                    runOnUiThread {
                        disconnectFromDevice()
                    }
                }
                "com.example.m5scribe.VOLUME_CHANGED" -> {
                    val volume = intent.getIntExtra("volume", 80)
                    Log.d("MainActivity", "Volume change received: $volume")
                    runOnUiThread {
                        bluetoothService?.setVolume(volume / 100f)
                    }
                }
                "com.example.m5scribe.AUDIO_PLAYBACK_CHANGED" -> {
                    val enabled = intent.getBooleanExtra("enabled", false)
                    Log.d("MainActivity", "Audio playback change received: $enabled")
                    runOnUiThread {
                        handleAudioPlaybackChange(enabled)
                    }
                }
            }
        }
    }

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            Toast.makeText(this, R.string.toast_permissions_granted, Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, R.string.toast_permissions_required, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // Initialize SessionRepository
        sessionRepository = SessionRepository(this)

        // Initialize Bluetooth adapter
        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        // Check if Bluetooth is supported
        if (bluetoothAdapter == null) {
            Toast.makeText(this, R.string.toast_bt_not_supported, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        // Request necessary permissions
        requestBluetoothPermissions()

        // Setup history button
        binding.historyButton.setOnClickListener {
            val intent = Intent(this, com.example.m5scribe.ui.HistoryActivity::class.java)
            startActivity(intent)
        }

        // Setup settings button
        binding.settingsButton.setOnClickListener {
            val intent = Intent(this, com.example.m5scribe.ui.SettingsActivity::class.java)
            startActivity(intent)
        }

        // Setup reconnect button
        binding.reconnectButton.setOnClickListener {
            Log.d("MainActivity", "Reconnect button clicked")
            tryReconnect()
        }


        // BroadcastReceiverを登録（アプリのライフサイクル全体で維持）
        registerTranscriptionReceiver()

        // 自動接続を試行
        tryAutoConnect()

        // 設定画面からのデバイス接続リクエストを処理
        handleConnectionRequest(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // アプリが既に起動している場合の接続リクエストを処理
        handleConnectionRequest(intent)
    }

    /**
     * 設定画面からの接続リクエストを処理
     */
    @SuppressLint("MissingPermission")
    private fun handleConnectionRequest(intent: Intent?) {
        if (intent?.action == "com.example.m5scribe.CONNECT_DEVICE") {
            val deviceAddress = intent.getStringExtra("device_address")
            if (deviceAddress != null) {
                try {
                    val device = bluetoothAdapter.getRemoteDevice(deviceAddress)
                    Log.d("MainActivity", "Connection request received for device: ${device.address}")
                    Toast.makeText(this, "接続中: ${device.name ?: deviceAddress}", Toast.LENGTH_SHORT).show()
                    connectToDevice(device)
                } catch (e: Exception) {
                    Log.e("MainActivity", "Failed to connect to device from settings", e)
                    Toast.makeText(this, "デバイスへの接続に失敗しました", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * BroadcastReceiverを登録
     */
    private fun registerTranscriptionReceiver() {
        if (isReceiverRegistered) {
            Log.d("MainActivity", "BroadcastReceiver already registered, skipping")
            return
        }

        val filter = IntentFilter().apply {
            addAction("com.example.m5scribe.PARTIAL_RESULT")
            addAction("com.example.m5scribe.FINAL_RESULT")
            addAction("com.example.m5scribe.DISCONNECT_REQUEST")
            addAction("com.example.m5scribe.VOLUME_CHANGED")
            addAction("com.example.m5scribe.AUDIO_PLAYBACK_CHANGED")
        }

        // Android 8.0以降はRECEIVER_NOT_EXPORTEDフラグを設定
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            registerReceiver(transcriptionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            // Android 8未満ではフラグなしで登録
            registerReceiver(transcriptionReceiver, filter)
        }
        isReceiverRegistered = true
        Log.d("MainActivity", "BroadcastReceiver registered")
    }

    /**
     * BroadcastReceiverを解除
     */
    private fun unregisterTranscriptionReceiver() {
        if (!isReceiverRegistered) {
            Log.d("MainActivity", "BroadcastReceiver not registered, skipping unregister")
            return
        }

        try {
            unregisterReceiver(transcriptionReceiver)
            isReceiverRegistered = false
            Log.d("MainActivity", "BroadcastReceiver unregistered")
        } catch (_: IllegalArgumentException) {
            // 既に解除されている場合は無視
            Log.w("MainActivity", "BroadcastReceiver was already unregistered")
            isReceiverRegistered = false
        }
    }

    private fun requestBluetoothPermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.RECORD_AUDIO
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.RECORD_AUDIO
            )
        }

        val permissionsToRequest = permissions.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (permissionsToRequest.isNotEmpty()) {
            requestPermissionsLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: BluetoothDevice) {
        binding.statusText.text = getString(R.string.status_connecting)
        binding.statusText.setTextColor(getColor(android.R.color.holo_orange_dark))

        CoroutineScope(Dispatchers.Main).launch {
            try {
                // 音声再生設定を読み込む
                val audioPlaybackEnabled = getAudioPlaybackSetting()
                Log.d("MainActivity", "Audio playback enabled: $audioPlaybackEnabled")
                Toast.makeText(this@MainActivity,
                    if (audioPlaybackEnabled) "音声再生: ON" else "音声再生: OFF（マイクのみで認識）",
                    Toast.LENGTH_SHORT).show()

                bluetoothService = BluetoothAudioService(
                    device = device,
                    onConnectionStateChanged = { connected ->
                        runOnUiThread {
                            if (connected) {
                                binding.statusText.text = getString(R.string.status_connected, device.name)
                                binding.statusText.setTextColor(getColor(android.R.color.holo_green_dark))
                                Toast.makeText(this@MainActivity, R.string.toast_connected, Toast.LENGTH_SHORT).show()

                                // 最後に接続したデバイスとして保存
                                saveLastConnectedDevice(device.address)

                                // 設定画面に接続状態を通知
                                notifyConnectionState(true, device.name ?: "")

                                // 保存された音量設定を適用
                                val volume = getVolumeSetting()
                                bluetoothService?.setVolume(volume / 100f)
                                Log.d("MainActivity", "Applied saved volume: $volume")

                                // 新しいセッションを開始
                                startNewSession()

                                // 接続時に自動的に文字起こしを開始
                                startTranscription()

                                // 再接続ボタンを非表示にする
                                binding.reconnectButton.visibility = android.view.View.GONE
                                binding.reconnectButton.isEnabled = true
                            } else {
                                binding.statusText.text = getString(R.string.status_disconnected)
                                binding.statusText.setTextColor(getColor(android.R.color.holo_red_dark))

                                // 設定画面に接続状態を通知
                                notifyConnectionState(false, "")

                                // 切断時に自動的に文字起こしを停止
                                stopTranscription()

                                // セッションを終了
                                endCurrentSession()

                                Toast.makeText(this@MainActivity, R.string.toast_disconnected, Toast.LENGTH_SHORT).show()

                                // 再接続ボタンを表示する
                                binding.reconnectButton.visibility = android.view.View.VISIBLE
                                binding.reconnectButton.isEnabled = true
                            }
                        }
                    },
                    onAudioDataReceived = null,  // マイクベースの認識では使用しない
                    audioPlaybackEnabled = audioPlaybackEnabled  // 設定から読み込んだ値
                )
                bluetoothService?.connect()
            } catch (e: Exception) {
                binding.statusText.text = getString(R.string.status_disconnected)
                binding.statusText.setTextColor(getColor(android.R.color.holo_red_dark))
                Toast.makeText(this@MainActivity, getString(R.string.toast_connection_failed, e.message), Toast.LENGTH_LONG).show()

                // 接続失敗時に再接続ボタンを表示
                binding.reconnectButton.visibility = android.view.View.VISIBLE
                binding.reconnectButton.isEnabled = true
            }
        }
    }

    private fun disconnectFromDevice() {
        stopTranscription()
        endCurrentSession()

        // Bluetooth切断を非同期で実行（メインスレッドをブロックしない）
        CoroutineScope(Dispatchers.IO).launch {
            try {
                bluetoothService?.disconnect()
            } catch (e: Exception) {
                Log.e("MainActivity", "Error during disconnect", e)
            }
        }
        bluetoothService = null

        binding.statusText.text = getString(R.string.status_not_connected)
        binding.statusText.setTextColor(getColor(android.R.color.holo_red_dark))
        Toast.makeText(this, R.string.toast_disconnected, Toast.LENGTH_SHORT).show()
    }

    /**
     * 新しいセッションを開始
     */
    private fun startNewSession() {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val now = Date()

        currentSessionId = sessionRepository.getNextSessionId()
        currentSessionStartTime = timeFormat.format(now)

        // 文字起こし内容をクリア
        transcriptionBuilder.clear()
        binding.transcriptionText.text = getString(R.string.transcription_placeholder)
        binding.partialResultText.text = getString(R.string.partial_result_placeholder)

        Log.d("MainActivity", "New session started: ID=$currentSessionId, StartTime=$currentSessionStartTime")
    }

    /**
     * 現在のセッションを終了
     *
     * 注意: 文字起こしが空の場合は保存せずにセッション情報だけリセットする
     */
    private fun endCurrentSession() {
        val sessionId = currentSessionId
        val startTime = currentSessionStartTime

        if (sessionId == null || startTime == null) {
            Log.d("MainActivity", "No active session to end")
            return
        }

        // 文字起こしが空でない場合のみ保存
        val transcriptionText = transcriptionBuilder.toString().trim()
        if (transcriptionText.isNotEmpty()) {
            // 最後に一度更新保存（最新の終了時刻で）
            updateCurrentSession()

            // セッションの所要時間を非同期で取得して表示
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val session = sessionRepository.getSessionById(sessionId)
                    if (session != null) {
                        Log.d("MainActivity", "Session ended: ID=$sessionId, Duration=${session.getDurationMinutes()}min")
                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                this@MainActivity,
                                "セッションを保存しました（${session.getDurationMinutes()}分）",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                } catch (e: Exception) {
                    Log.e("MainActivity", "Error loading session for end notification", e)
                }
            }
        } else {
            Log.d("MainActivity", "Session ended without transcription, not saving")
        }

        // セッション情報をリセット
        currentSessionId = null
        currentSessionStartTime = null
    }

    /**
     * 文字起こしを開始（Foreground Serviceを使用）
     */
    private fun startTranscription() {
        val intent = Intent(this, TranscriptionForegroundService::class.java).apply {
            action = TranscriptionForegroundService.ACTION_START
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        Toast.makeText(this, R.string.toast_transcription_started, Toast.LENGTH_SHORT).show()
        Log.d("MainActivity", "Started transcription foreground service")
    }

    /**
     * 文字起こしを停止
     */
    private fun stopTranscription() {
        val intent = Intent(this, TranscriptionForegroundService::class.java).apply {
            action = TranscriptionForegroundService.ACTION_STOP
        }
        startService(intent)

        Toast.makeText(this, R.string.toast_transcription_stopped, Toast.LENGTH_SHORT).show()
        Log.d("MainActivity", "Stopped transcription foreground service")
    }

    /**
     * 文字起こし結果を追加（確定結果）
     */
    private fun appendTranscription(text: String) {
        android.util.Log.d("MainActivity", "appendTranscription called with: $text")

        if (text.isNotBlank()) {
            val timestamp = java.text.SimpleDateFormat(
                "HH:mm:ss",
                java.util.Locale.getDefault()
            ).format(java.util.Date())

            if (transcriptionBuilder.isNotEmpty()) {
                transcriptionBuilder.append("\n")
            }
            // タイムスタンプを横に表示（タブで区切り）
            transcriptionBuilder.append("$timestamp\t$text")

            // 確定結果のみを表示
            binding.transcriptionText.text = transcriptionBuilder.toString()

            // 部分結果ボックスをクリア
            binding.partialResultText.text = getString(R.string.partial_result_placeholder)

            // 自動スクロール（最新の結果を表示）
            binding.transcriptionScrollView.post {
                binding.transcriptionScrollView.fullScroll(android.view.View.FOCUS_DOWN)
            }

            // リアルタイムで現在のセッションを更新保存
            updateCurrentSession()

            // デバッグログで確認
            android.util.Log.d("MainActivity", "Transcription saved: $text")
            android.util.Log.d("MainActivity", "Total length: ${transcriptionBuilder.length} chars")
        } else {
            android.util.Log.w("MainActivity", "appendTranscription called with blank text!")
        }
    }

    /**
     * 現在のセッションを更新保存（リアルタイム保存）
     * ファイルI/Oを非同期で実行してメインスレッドをブロックしない
     *
     * 注意: 文字起こしが空の場合は保存しない
     */
    private fun updateCurrentSession() {
        val sessionId = currentSessionId
        val startTime = currentSessionStartTime

        if (sessionId == null || startTime == null) {
            return
        }

        // 文字起こしが空の場合は保存しない
        val transcriptionText = transcriptionBuilder.toString().trim()
        if (transcriptionText.isEmpty()) {
            Log.d("MainActivity", "Skipping session save: transcription is empty")
            return
        }

        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val now = Date()

        val session = Session(
            id = sessionId,
            date = dateFormat.format(now),
            startTime = startTime,
            endTime = timeFormat.format(now),
            transcription = transcriptionText,
            summary = null
        )

        // ファイルI/Oを非同期で実行
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 既存のセッションを更新、なければ追加
                val sessionList = sessionRepository.loadSessions()
                val existingSession = sessionList.getSessionById(sessionId)

                if (existingSession != null) {
                    sessionRepository.updateSession(sessionId, session)
                    Log.d("MainActivity", "Session updated: ID=$sessionId, length=${transcriptionText.length}")
                } else {
                    sessionRepository.addSession(session)
                    Log.d("MainActivity", "Session created: ID=$sessionId, length=${transcriptionText.length}")
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Error updating session", e)
            }
        }
    }

    /**
     * 部分的な文字起こし結果を更新
     */
    private fun updatePartialTranscription(text: String) {
        android.util.Log.d("MainActivity", "updatePartialTranscription called: text=$text")
        if (text.isNotBlank()) {
            // 部分結果ボックスに表示（transcriptionBuilderは変更しない）
            binding.partialResultText.text = text
            android.util.Log.d("MainActivity", "Partial result displayed in UI")
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // 文字起こし結果を保存
        outState.putString("transcription", transcriptionBuilder.toString())
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        // 文字起こし結果を復元
        savedInstanceState.getString("transcription")?.let { savedText ->
            transcriptionBuilder.clear()
            transcriptionBuilder.append(savedText)
            binding.transcriptionText.text = savedText
            android.util.Log.d("MainActivity", "Transcription restored: ${savedText.length} chars")
        }
    }

    /**
     * 再接続を試行
     */
    @SuppressLint("MissingPermission")
    private fun tryReconnect() {
        Log.d("MainActivity", "Attempting manual reconnect")

        // Bluetoothが有効かチェック
        if (!bluetoothAdapter.isEnabled) {
            Toast.makeText(this, "Bluetoothを有効にしてください", Toast.LENGTH_SHORT).show()
            Log.d("MainActivity", "Bluetooth is disabled, cannot reconnect")
            return
        }

        // 権限チェック
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Bluetooth接続権限が必要です", Toast.LENGTH_SHORT).show()
            Log.d("MainActivity", "BLUETOOTH_CONNECT permission not granted")
            return
        }

        // 最後に接続したデバイスのアドレスを取得
        val lastDeviceAddress = getLastConnectedDevice()
        if (lastDeviceAddress == null) {
            Toast.makeText(this, "接続先デバイスが見つかりません。設定画面でデバイスを選択してください。", Toast.LENGTH_LONG).show()
            Log.d("MainActivity", "No previously connected device found")
            return
        }

        try {
            // デバイスを取得
            val device = bluetoothAdapter.getRemoteDevice(lastDeviceAddress)
            Log.d("MainActivity", "Attempting reconnect to ${device.name} ($lastDeviceAddress)")

            // ステータス表示
            binding.statusText.text = getString(R.string.status_connecting)
            binding.statusText.setTextColor(getColor(android.R.color.holo_orange_dark))

            // 再接続ボタンを一時的に無効化（連打防止）
            binding.reconnectButton.isEnabled = false

            // 接続を試みる
            connectToDevice(device)
        } catch (e: Exception) {
            Log.e("MainActivity", "Reconnect failed", e)
            binding.statusText.text = getString(R.string.status_disconnected)
            binding.statusText.setTextColor(getColor(android.R.color.holo_red_dark))
            binding.reconnectButton.isEnabled = true
            Toast.makeText(this, "再接続に失敗しました: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 自動接続を試行
     */
    @SuppressLint("MissingPermission")
    private fun tryAutoConnect() {
        // Bluetoothが有効かチェック
        if (!bluetoothAdapter.isEnabled) {
            Log.d("MainActivity", "Bluetooth is disabled, skipping auto-connect")
            return
        }

        // 権限チェック
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            Log.d("MainActivity", "BLUETOOTH_CONNECT permission not granted, skipping auto-connect")
            return
        }

        // 最後に接続したデバイスのアドレスを取得
        val lastDeviceAddress = getLastConnectedDevice()
        if (lastDeviceAddress == null) {
            Log.d("MainActivity", "No previously connected device found")
            return
        }

        try {
            // デバイスを取得
            val device = bluetoothAdapter.getRemoteDevice(lastDeviceAddress)
            Log.d("MainActivity", "Attempting auto-connect to ${device.name} ($lastDeviceAddress)")

            // ステータス表示
            binding.statusText.text = getString(R.string.status_auto_connecting, device.name ?: lastDeviceAddress)
            binding.statusText.setTextColor(getColor(android.R.color.holo_orange_dark))

            // 接続を試みる
            connectToDevice(device)
        } catch (e: Exception) {
            Log.e("MainActivity", "Auto-connect failed", e)
            binding.statusText.text = getString(R.string.status_not_connected)
            binding.statusText.setTextColor(getColor(android.R.color.holo_red_dark))

            // 自動接続失敗時に再接続ボタンを表示
            binding.reconnectButton.visibility = android.view.View.VISIBLE
            binding.reconnectButton.isEnabled = true
        }
    }

    /**
     * 最後に接続したデバイスのアドレスを保存
     */
    private fun saveLastConnectedDevice(deviceAddress: String) {
        try {
            val masterKey = MasterKey.Builder(this)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            val prefs = EncryptedSharedPreferences.create(
                this,
                "m5scribe_secure_prefs",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )

            prefs.edit {
                putString("last_connected_device", deviceAddress)
            }

            Log.d("MainActivity", "Saved last connected device: $deviceAddress")
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to save last connected device", e)
            // フォールバック
            getSharedPreferences("m5scribe_secure_prefs", Context.MODE_PRIVATE).edit {
                putString("last_connected_device", deviceAddress)
            }
        }
    }

    /**
     * 最後に接続したデバイスのアドレスを取得
     */
    private fun getLastConnectedDevice(): String? {
        return try {
            val masterKey = MasterKey.Builder(this)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            val prefs = EncryptedSharedPreferences.create(
                this,
                "m5scribe_secure_prefs",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )

            prefs.getString("last_connected_device", null)
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to get last connected device", e)
            // フォールバック
            getSharedPreferences("m5scribe_secure_prefs", Context.MODE_PRIVATE)
                .getString("last_connected_device", null)
        }
    }

    /**
     * 接続状態を設定画面に通知
     */
    private fun notifyConnectionState(connected: Boolean, deviceName: String) {
        val intent = Intent("com.example.m5scribe.CONNECTION_STATE_CHANGED").apply {
            putExtra("connected", connected)
            putExtra("deviceName", deviceName)
            setPackage(packageName)
        }
        sendBroadcast(intent)
        Log.d("MainActivity", "Notified connection state: connected=$connected, device=$deviceName")
    }

    /**
     * 音声再生設定を取得
     */
    private fun getAudioPlaybackSetting(): Boolean {
        return try {
            val masterKey = MasterKey.Builder(this)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            val prefs = EncryptedSharedPreferences.create(
                this,
                "m5scribe_secure_prefs",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )

            // デフォルトはfalse (OFF)
            prefs.getBoolean("audio_playback_enabled", false)
        } catch (e: Exception) {
            // フォールバック
            getSharedPreferences("m5scribe_secure_prefs", Context.MODE_PRIVATE)
                .getBoolean("audio_playback_enabled", false)
        }
    }

    /**
     * 音量設定を取得
     */
    private fun getVolumeSetting(): Int {
        return try {
            val masterKey = MasterKey.Builder(this)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            val prefs = EncryptedSharedPreferences.create(
                this,
                "m5scribe_secure_prefs",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )

            // デフォルトは80
            prefs.getInt("audio_volume", 80)
        } catch (e: Exception) {
            // フォールバック
            getSharedPreferences("m5scribe_secure_prefs", Context.MODE_PRIVATE)
                .getInt("audio_volume", 80)
        }
    }

    /**
     * 音声再生設定の変更を処理
     */
    private fun handleAudioPlaybackChange(enabled: Boolean) {
        if (bluetoothService != null) {
            // 接続中の場合は、ユーザーに再接続を促す
            Toast.makeText(
                this,
                "音声再生設定を変更しました。新しい設定は次回接続時に反映されます。",
                Toast.LENGTH_LONG
            ).show()
            Log.d("MainActivity", "Audio playback setting changed while connected. User should reconnect.")
        } else {
            // 未接続の場合は、次回接続時に自動的に反映される
            Toast.makeText(
                this,
                "音声再生設定を変更しました。",
                Toast.LENGTH_SHORT
            ).show()
            Log.d("MainActivity", "Audio playback setting changed while not connected.")
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        // 文字起こしサービスを停止
        stopTranscription()

        // Bluetooth切断を非同期で実行（onDestroyをブロックしない）
        val service = bluetoothService
        if (service != null) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    service.disconnect()
                } catch (e: Exception) {
                    Log.e("MainActivity", "Error during disconnect in onDestroy", e)
                }
            }
            bluetoothService = null
        }

        // BroadcastReceiverを解除
        unregisterTranscriptionReceiver()
    }
}
package com.example.m5scribe.ui

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
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.core.content.edit
import com.example.m5scribe.BluetoothAudioService
import com.example.m5scribe.BluetoothDeviceAdapter
import com.example.m5scribe.MainActivity
import com.example.m5scribe.R
import com.example.m5scribe.TranscriptionForegroundService
import com.example.m5scribe.databinding.ActivitySettingsBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 設定画面
 *
 * LLM APIキーの管理とBluetooth接続管理を行う
 */
class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding
    private lateinit var bluetoothAdapter: BluetoothAdapter
    private lateinit var deviceAdapter: BluetoothDeviceAdapter
    private var isReceiverRegistered = false

    // 接続状態を監視するBroadcastReceiver
    private val connectionStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "com.example.m5scribe.CONNECTION_STATE_CHANGED" -> {
                    val connected = intent.getBooleanExtra("connected", false)
                    val deviceName = intent.getStringExtra("deviceName") ?: ""
                    updateConnectionStatus(connected, deviceName)
                }
            }
        }
    }

    private val enableBluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            Toast.makeText(this, R.string.toast_bt_enabled, Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, R.string.toast_bt_required, Toast.LENGTH_SHORT).show()
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

    companion object {
        private const val TAG = "SettingsActivity"
        private const val PREFS_FILE_NAME = "m5scribe_secure_prefs"
        private const val KEY_API_KEY = "llm_api_key"
        private const val KEY_API_PROVIDER = "api_provider"
        private const val KEY_AUDIO_PLAYBACK = "audio_playback_enabled"
        private const val KEY_VOLUME = "audio_volume"
        private const val PROVIDER_OPENAI = "openai"
        private const val PROVIDER_ANTHROPIC = "anthropic"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize Bluetooth adapter
        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        // Setup RecyclerView
        deviceAdapter = BluetoothDeviceAdapter { device ->
            connectToDevice(device)
        }
        binding.devicesRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@SettingsActivity)
            adapter = deviceAdapter
        }

        // Setup scan button
        binding.scanButton.setOnClickListener {
            scanForDevices()
        }

        // Setup disconnect button
        binding.disconnectButton.setOnClickListener {
            disconnectFromDevice()
        }

        // Setup forget device button
        binding.forgetDeviceButton.setOnClickListener {
            forgetSavedDevice()
        }

        // Setup back button
        binding.backButton.setOnClickListener {
            finish()
        }

        // Setup save button
        binding.saveButton.setOnClickListener {
            saveApiKey()
        }

        // Setup clear button
        binding.clearApiKeyButton.setOnClickListener {
            clearApiKey()
        }

        // Setup audio playback switch
        binding.audioPlaybackSwitch.setOnCheckedChangeListener { _, isChecked ->
            saveAudioPlaybackSetting(isChecked)
        }

        // Setup volume control
        binding.volumeSeekBar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                binding.volumeValueText.text = "$progress%"
                if (fromUser) {
                    saveVolumeSetting(progress)
                    // MainActivityに音量変更を通知
                    notifyVolumeChange(progress)
                }
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })

        // Register BroadcastReceiver for connection state
        registerConnectionStateReceiver()

        // Load current settings
        loadCurrentSettings()

        // Display saved device
        displaySavedDevice()

        // Update current connection status
        updateConnectionStatusFromMainActivity()
    }

    /**
     * 暗号化されたSharedPreferencesを取得
     */
    private fun getEncryptedPreferences() = try {
        val masterKey = MasterKey.Builder(this)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            this,
            PREFS_FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        // フォールバック: 通常のSharedPreferences
        getSharedPreferences(PREFS_FILE_NAME, Context.MODE_PRIVATE)
    }

    /**
     * 現在の設定を読み込む
     */
    private fun loadCurrentSettings() {
        val prefs = getEncryptedPreferences()

        // APIキーを読み込む
        val apiKey = prefs.getString(KEY_API_KEY, null)
        val provider = prefs.getString(KEY_API_PROVIDER, PROVIDER_OPENAI)

        // APIキー設定状態を視覚的に表示
        if (apiKey != null) {
            // APIキーが設定されている場合
            // マスク表示（最初の7文字と最後の4文字のみ表示）
            val maskedKey = if (apiKey.length > 11) {
                "${apiKey.take(7)}...${apiKey.takeLast(4)}"
            } else {
                "******"
            }

            // ステータス表示を更新
            binding.apiKeyStatusIcon.setImageResource(android.R.drawable.checkbox_on_background)
            binding.apiKeyStatusIcon.setColorFilter(getColor(android.R.color.holo_green_dark))
            binding.currentApiKeyText.text = getString(R.string.settings_api_key_current, maskedKey)
            binding.currentApiKeyText.setTextColor(getColor(android.R.color.holo_green_dark))

            // 背景色を緑系に
            val parentLayout = binding.currentApiKeyText.parent as? android.view.ViewGroup
            parentLayout?.setBackgroundColor(getColor(android.R.color.holo_green_light).let { color ->
                android.graphics.Color.argb(30,
                    android.graphics.Color.red(color),
                    android.graphics.Color.green(color),
                    android.graphics.Color.blue(color))
            })
        } else {
            // APIキーが未設定の場合
            binding.apiKeyStatusIcon.setImageResource(android.R.drawable.ic_delete)
            binding.apiKeyStatusIcon.setColorFilter(getColor(android.R.color.holo_red_dark))
            binding.currentApiKeyText.text = getString(R.string.settings_api_key_not_set)
            binding.currentApiKeyText.setTextColor(getColor(android.R.color.holo_red_dark))

            // 背景色をデフォルトのグレーに
            val parentLayout = binding.currentApiKeyText.parent as? android.view.ViewGroup
            parentLayout?.setBackgroundColor(android.graphics.Color.parseColor("#F5F5F5"))
        }

        // プロバイダー選択を反映
        when (provider) {
            PROVIDER_OPENAI -> binding.openaiRadioButton.isChecked = true
            PROVIDER_ANTHROPIC -> binding.anthropicRadioButton.isChecked = true
        }

        // 音声再生設定を読み込む（デフォルトはfalse = OFF）
        val audioPlaybackEnabled = prefs.getBoolean(KEY_AUDIO_PLAYBACK, false)
        binding.audioPlaybackSwitch.isChecked = audioPlaybackEnabled

        // 音量設定を読み込む（デフォルトは80）
        val volume = prefs.getInt(KEY_VOLUME, 80)
        binding.volumeSeekBar.progress = volume
        binding.volumeValueText.text = "$volume%"
    }

    /**
     * APIキーを保存
     */
    private fun saveApiKey() {
        val apiKey = binding.apiKeyEditText.text.toString().trim()

        if (apiKey.isEmpty()) {
            Toast.makeText(this, R.string.toast_api_key_empty, Toast.LENGTH_SHORT).show()
            return
        }

        val provider = when {
            binding.openaiRadioButton.isChecked -> PROVIDER_OPENAI
            binding.anthropicRadioButton.isChecked -> PROVIDER_ANTHROPIC
            else -> PROVIDER_OPENAI
        }

        val prefs = getEncryptedPreferences()
        prefs.edit {
            putString(KEY_API_KEY, apiKey)
            putString(KEY_API_PROVIDER, provider)
        }

        Toast.makeText(this, R.string.toast_api_key_saved, Toast.LENGTH_SHORT).show()

        // 入力フィールドをクリア
        binding.apiKeyEditText.text?.clear()

        // 表示を更新
        loadCurrentSettings()
    }

    /**
     * APIキーをクリア
     */
    private fun clearApiKey() {
        val prefs = getEncryptedPreferences()
        prefs.edit {
            remove(KEY_API_KEY)
            remove(KEY_API_PROVIDER)
        }

        Toast.makeText(this, R.string.toast_api_key_cleared, Toast.LENGTH_SHORT).show()

        // 表示を更新
        loadCurrentSettings()
    }

    /**
     * 音声再生設定を保存
     */
    private fun saveAudioPlaybackSetting(enabled: Boolean) {
        val prefs = getEncryptedPreferences()
        prefs.edit {
            putBoolean(KEY_AUDIO_PLAYBACK, enabled)
        }

        // MainActivityに音声再生設定の変更を通知
        notifyAudioPlaybackChange(enabled)

        Toast.makeText(
            this,
            if (enabled) "音声再生: ON（再接続時に有効）" else "音声再生: OFF（再接続時に有効）",
            Toast.LENGTH_SHORT
        ).show()
    }

    /**
     * 音声再生設定の変更をMainActivityに通知
     */
    private fun notifyAudioPlaybackChange(enabled: Boolean) {
        val intent = Intent("com.example.m5scribe.AUDIO_PLAYBACK_CHANGED").apply {
            putExtra("enabled", enabled)
            setPackage(packageName)
        }
        sendBroadcast(intent)
        Log.d(TAG, "Notified audio playback change: $enabled")
    }

    /**
     * 音量設定を保存
     */
    private fun saveVolumeSetting(volume: Int) {
        val prefs = getEncryptedPreferences()
        prefs.edit {
            putInt(KEY_VOLUME, volume)
        }
    }

    /**
     * 音量変更をMainActivityに通知
     */
    private fun notifyVolumeChange(volume: Int) {
        val intent = Intent("com.example.m5scribe.VOLUME_CHANGED").apply {
            putExtra("volume", volume)
            setPackage(packageName)
        }
        sendBroadcast(intent)
        Log.d(TAG, "Notified volume change: $volume")
    }

    /**
     * BroadcastReceiverを登録
     */
    private fun registerConnectionStateReceiver() {
        if (isReceiverRegistered) {
            return
        }

        val filter = IntentFilter().apply {
            addAction("com.example.m5scribe.CONNECTION_STATE_CHANGED")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(connectionStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(connectionStateReceiver, filter)
        }
        isReceiverRegistered = true
    }

    /**
     * BroadcastReceiverを解除
     */
    private fun unregisterConnectionStateReceiver() {
        if (!isReceiverRegistered) {
            return
        }

        try {
            unregisterReceiver(connectionStateReceiver)
            isReceiverRegistered = false
        } catch (_: IllegalArgumentException) {
            Log.w(TAG, "Receiver was already unregistered")
            isReceiverRegistered = false
        }
    }

    @SuppressLint("MissingPermission")
    private fun scanForDevices() {
        if (!bluetoothAdapter.isEnabled) {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            enableBluetoothLauncher.launch(enableBtIntent)
            return
        }

        // Check permissions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            requestBluetoothPermissions()
            return
        }

        // Get paired devices
        val pairedDevices: Set<BluetoothDevice>? = bluetoothAdapter.bondedDevices
        pairedDevices?.let {
            deviceAdapter.updateDevices(it.toList())
            Toast.makeText(this, getString(R.string.toast_devices_found, it.size), Toast.LENGTH_SHORT).show()
        } ?: run {
            Toast.makeText(this, R.string.toast_no_devices, Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestBluetoothPermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN
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
        // MainActivityの接続処理を呼び出す
        val intent = Intent(this, MainActivity::class.java).apply {
            action = "com.example.m5scribe.CONNECT_DEVICE"
            putExtra("device_address", device.address)
        }
        startActivity(intent)
        finish() // 設定画面を閉じてホーム画面に戻る
    }

    private fun disconnectFromDevice() {
        // MainActivityに切断要求を送信
        val intent = Intent("com.example.m5scribe.DISCONNECT_REQUEST").apply {
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    private fun updateConnectionStatus(connected: Boolean, deviceName: String) {
        runOnUiThread {
            if (connected) {
                binding.connectionStatusText.text = getString(R.string.status_connected, deviceName)
                binding.connectionStatusText.setTextColor(getColor(android.R.color.holo_green_dark))
                binding.disconnectButton.isEnabled = true
            } else {
                binding.connectionStatusText.text = getString(R.string.status_not_connected)
                binding.connectionStatusText.setTextColor(getColor(android.R.color.holo_red_dark))
                binding.disconnectButton.isEnabled = false
            }
        }
    }

    private fun updateConnectionStatusFromMainActivity() {
        // SharedPreferencesから最後の接続状態を取得（仮実装）
        // 本来はMainActivityから状態を取得する必要がある
        binding.connectionStatusText.text = getString(R.string.status_not_connected)
        binding.connectionStatusText.setTextColor(getColor(android.R.color.holo_red_dark))
        binding.disconnectButton.isEnabled = false
    }

    /**
     * 保存済みデバイスを表示
     */
    @SuppressLint("MissingPermission")
    private fun displaySavedDevice() {
        val savedDeviceAddress = getSavedDeviceAddress()

        if (savedDeviceAddress != null) {
            try {
                // デバイス名を取得
                val device = bluetoothAdapter.getRemoteDevice(savedDeviceAddress)
                val deviceName = device.name ?: "不明なデバイス"

                binding.savedDeviceText.text = getString(
                    R.string.settings_saved_device_info,
                    deviceName,
                    savedDeviceAddress
                )
                binding.forgetDeviceButton.visibility = android.view.View.VISIBLE
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get device info", e)
                binding.savedDeviceText.text = getString(
                    R.string.settings_saved_device_info,
                    "不明なデバイス",
                    savedDeviceAddress
                )
                binding.forgetDeviceButton.visibility = android.view.View.VISIBLE
            }
        } else {
            binding.savedDeviceText.text = getString(R.string.settings_saved_device_none)
            binding.forgetDeviceButton.visibility = android.view.View.GONE
        }
    }

    /**
     * 保存済みデバイスを削除
     */
    private fun forgetSavedDevice() {
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
                remove("last_connected_device")
            }

            Log.d(TAG, "Saved device forgotten")
            Toast.makeText(this, R.string.toast_device_forgotten, Toast.LENGTH_SHORT).show()

            // 表示を更新
            displaySavedDevice()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to forget saved device", e)
            // フォールバック
            getSharedPreferences("m5scribe_secure_prefs", Context.MODE_PRIVATE).edit {
                remove("last_connected_device")
            }

            Toast.makeText(this, R.string.toast_device_forgotten, Toast.LENGTH_SHORT).show()
            displaySavedDevice()
        }
    }

    /**
     * 保存済みデバイスのアドレスを取得
     */
    private fun getSavedDeviceAddress(): String? {
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
            Log.e(TAG, "Failed to get saved device", e)
            // フォールバック
            getSharedPreferences("m5scribe_secure_prefs", Context.MODE_PRIVATE)
                .getString("last_connected_device", null)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterConnectionStateReceiver()
    }
}

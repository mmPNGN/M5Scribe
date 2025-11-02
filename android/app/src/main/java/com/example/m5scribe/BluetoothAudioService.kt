package com.example.m5scribe

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import java.io.IOException
import java.io.InputStream
import java.util.UUID

class BluetoothAudioService(
    private val device: BluetoothDevice,
    private val onConnectionStateChanged: (Boolean) -> Unit,
    private val onAudioDataReceived: ((ByteArray) -> Unit)? = null,
    private var audioPlaybackEnabled: Boolean = false  // デフォルトはOFF
) {
    companion object {
        private const val TAG = "BluetoothAudioService"
        // Standard SPP (Serial Port Profile) UUID
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        // Audio configuration matching M5Stack settings
        private const val SAMPLE_RATE = 16000  // 16kHz to match M5Stack
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val BUFFER_SIZE = 2048  // Smaller buffer for lower latency
    }

    private var bluetoothSocket: BluetoothSocket? = null
    private var inputStream: InputStream? = null
    private var audioTrack: AudioTrack? = null
    private var receiveJob: Job? = null
    private var isConnected = false
    private var volumeScale = 0.8f

    @SuppressLint("MissingPermission")
    suspend fun connect() {
        try {
            Log.d(TAG, "Connecting to ${device.name} (${device.address})...")

            // Create socket
            bluetoothSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)

            // Connect (this is blocking)
            bluetoothSocket?.connect()

            if (bluetoothSocket?.isConnected == true) {
                inputStream = bluetoothSocket?.inputStream
                isConnected = true
                onConnectionStateChanged(true)
                Log.d(TAG, "Connected successfully")

                // Initialize audio playback (only if enabled)
                if (audioPlaybackEnabled) {
                    initializeAudioTrack()
                    Log.d(TAG, "Audio playback is ENABLED")
                } else {
                    Log.d(TAG, "Audio playback is DISABLED")
                }

                // Start receiving audio data
                startReceiving()
            }
        } catch (e: IOException) {
            Log.e(TAG, "Connection failed", e)
            disconnect()
            throw e
        }
    }

    private fun initializeAudioTrack() {
        val minBufferSize = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT
        )

        val bufferSize = maxOf(minBufferSize, BUFFER_SIZE)

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(CHANNEL_CONFIG)
                    .setEncoding(AUDIO_FORMAT)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        audioTrack?.play()
        Log.d(TAG, "AudioTrack initialized (buffer: $bufferSize bytes)")
    }

    private fun startReceiving() {
        receiveJob = CoroutineScope(Dispatchers.IO).launch {
            val buffer = ByteArray(BUFFER_SIZE)
            val scaledBuffer = ShortArray(BUFFER_SIZE / 2)

            Log.d(TAG, "Started receiving audio data")

            try {
                while (isActive && isConnected) {
                    val bytesRead = inputStream?.read(buffer) ?: -1

                    if (bytesRead > 0) {
                        // 音声認識サービスに生データを渡す（音量調整前）
                        onAudioDataReceived?.invoke(buffer.copyOf(bytesRead))

                        // AudioTrackが初期化されている場合のみ再生
                        if (audioPlaybackEnabled && audioTrack != null) {
                            // Convert bytes to shorts and apply volume
                            for (i in 0 until bytesRead / 2) {
                                val sample = ((buffer[i * 2].toInt() and 0xFF) or
                                             (buffer[i * 2 + 1].toInt() shl 8)).toShort()
                                scaledBuffer[i] = (sample * volumeScale).toInt().toShort()
                            }

                            // Write to AudioTrack
                            audioTrack?.write(scaledBuffer, 0, bytesRead / 2)
                        }
                    } else if (bytesRead == -1) {
                        Log.w(TAG, "End of stream reached")
                        break
                    }
                }
            } catch (e: IOException) {
                Log.e(TAG, "Error receiving audio data", e)
            } finally {
                Log.d(TAG, "Stopped receiving audio data")
                disconnect()
            }
        }
    }

    fun setVolume(volume: Float) {
        volumeScale = volume.coerceIn(0f, 1f)
        Log.d(TAG, "Volume set to ${(volumeScale * 100).toInt()}%")
    }

    fun disconnect() {
        isConnected = false
        receiveJob?.cancel()

        try {
            audioTrack?.stop()
            audioTrack?.release()
            audioTrack = null

            inputStream?.close()
            inputStream = null

            bluetoothSocket?.close()
            bluetoothSocket = null

            onConnectionStateChanged(false)
            Log.d(TAG, "Disconnected")
        } catch (e: IOException) {
            Log.e(TAG, "Error during disconnect", e)
        }
    }
}

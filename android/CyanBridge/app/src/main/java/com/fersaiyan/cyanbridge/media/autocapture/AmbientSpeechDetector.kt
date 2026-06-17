package com.fersaiyan.cyanbridge.media.autocapture

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import kotlin.math.sqrt

/**
 * Ambient speech detector that uses the glasses' Bluetooth microphone to check
 * whether the user is currently in a conversation.
 *
 * Uses the same two-tier BT routing strategy as MeetingCaptureService:
 * 1. Android 12+ (API 31+): setCommunicationDevice() + MODE_IN_COMMUNICATION
 * 2. Legacy fallback: Bluetooth SCO routing
 *
 * AudioRecord with VOICE_COMMUNICATION source reads from the BT mic when
 * properly routed. This does NOT record or store audio — only computes
 * RMS energy to detect speech presence.
 *
 * Used by AutoAudioCaptureService to decide whether to extend a loop
 * instead of stopping and risking cutting off meaningful speech.
 */
object AmbientSpeechDetector {
    private const val TAG = "AmbientSpeech"

    /**
     * Listen for [durationMs] on the glasses' Bluetooth mic and return true
     * if significant speech-like energy is detected.
     *
     * Falls back to the phone mic if no Bluetooth headset is detected.
     */
    fun detectSpeechFor(
        context: Context,
        durationMs: Long = 60_000L,
        sampleRate: Int = 16_000,
        rmsThreshold: Double = 200.0,
        minVoicedFraction: Double = 0.10,
    ): Boolean {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val btRoute = startBluetoothInputRoutingBestEffort(audioManager)
        val usedBt = btRoute.isActive

        if (usedBt) {
            Log.i(TAG, "Using Bluetooth mic for speech detection")
        } else {
            Log.i(TAG, "Bluetooth mic not available, falling back to phone mic")
        }

        return try {
            detectWithAudioRecord(btRoute, durationMs, sampleRate, rmsThreshold, minVoicedFraction)
        } finally {
            stopBluetoothInputRoutingBestEffort(audioManager, btRoute)
        }
    }

    private fun detectWithAudioRecord(
        btRoute: BtInputRoute,
        durationMs: Long,
        sampleRate: Int,
        rmsThreshold: Double,
        minVoicedFraction: Double,
    ): Boolean {
        val bufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (bufferSize <= 0) {
            Log.w(TAG, "AudioRecord.getMinBufferSize returned $bufferSize")
            return false
        }

        val audioSource = if (btRoute.isActive) {
            MediaRecorder.AudioSource.VOICE_COMMUNICATION
        } else {
            MediaRecorder.AudioSource.MIC
        }

        val record = AudioRecord(
            audioSource,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize * 2,
        )

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.w(TAG, "AudioRecord not initialized (state=${record.state})")
            return false
        }

        // Prefer the BT input device explicitly when available.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && btRoute.preferredInput != null) {
            runCatching { record.setPreferredDevice(btRoute.preferredInput) }
        }

        return try {
            record.startRecording()
            val buffer = ShortArray(bufferSize.coerceAtLeast(1024))
            var totalFrames = 0
            var voicedFrames = 0
            var maxRms = 0.0

            val frameSize = (sampleRate / 50).coerceAtLeast(160)
            val deadline = System.currentTimeMillis() + durationMs

            while (System.currentTimeMillis() < deadline) {
                val read = record.read(buffer, 0, buffer.size)
                if (read <= 0) {
                    Thread.sleep(10)
                    continue
                }

                var i = 0
                while (i < read) {
                    val end = minOf(i + frameSize, read)
                    var sumSq = 0.0
                    val n = end - i
                    for (j in i until end) {
                        val v = buffer[j].toDouble()
                        sumSq += v * v
                    }
                    val rms = if (n > 0) sqrt(sumSq / n) else 0.0
                    if (rms > maxRms) maxRms = rms
                    if (rms >= rmsThreshold) voicedFrames++
                    totalFrames++
                    i = end
                }
            }

            runCatching { record.stop() }

            if (totalFrames == 0) {
                Log.i(TAG, "No frames read")
                return false
            }

            val fraction = voicedFrames.toDouble() / totalFrames
            val result = fraction >= minVoicedFraction || maxRms >= rmsThreshold * 2.0
            Log.i(
                TAG,
                "Speech check (${if (btRoute.isActive) "BT" else "phone"} mic): " +
                    "frames=$totalFrames voiced=$voicedFrames " +
                    "(${String.format("%.0f", fraction * 100)}%) maxRms=${String.format("%.0f", maxRms)} " +
                    "result=$result"
            )
            result
        } catch (e: SecurityException) {
            Log.w(TAG, "Missing RECORD_AUDIO permission: ${e.message}")
            false
        } catch (e: Exception) {
            Log.e(TAG, "Speech detection failed: ${e.message}", e)
            false
        } finally {
            runCatching { record.release() }
        }
    }

    // ── Bluetooth routing (mirrors MeetingCaptureService) ──

    private data class BtInputRoute(
        val isActive: Boolean,
        val usedSco: Boolean,
        val usedCommunicationDevice: Boolean,
        val preferredInput: AudioDeviceInfo?,
    )

    private fun isBluetoothHeadsetLikelyConnected(audioManager: AudioManager): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
                if (inputs.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }) return true
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (inputs.any { it.type == AudioDeviceInfo.TYPE_BLE_HEADSET }) return true
                }
                val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                if (outputs.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }) return true
                if (outputs.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }) return true
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (outputs.any { it.type == AudioDeviceInfo.TYPE_BLE_HEADSET }) return true
                }
            }
            audioManager.isBluetoothScoAvailableOffCall
        } catch (_: Throwable) {
            false
        }
    }

    private fun findBluetoothInputDevice(audioManager: AudioManager): AudioDeviceInfo? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
        val inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        return inputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
            ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                inputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLE_HEADSET }
            } else {
                null
            }
    }

    private fun findBluetoothCommunicationDevice(audioManager: AudioManager): AudioDeviceInfo? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        return runCatching {
            audioManager.availableCommunicationDevices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
            }
        }.getOrNull()
    }

    private fun startBluetoothInputRoutingBestEffort(audioManager: AudioManager): BtInputRoute {
        if (!isBluetoothHeadsetLikelyConnected(audioManager)) {
            return BtInputRoute(false, usedSco = false, usedCommunicationDevice = false, preferredInput = null)
        }

        // Android 12+: explicit communication-device routing.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val comm = findBluetoothCommunicationDevice(audioManager)
            if (comm != null) {
                val ok = runCatching {
                    audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                    audioManager.setCommunicationDevice(comm)
                    true
                }.getOrDefault(false)

                Thread.sleep(250)
                val preferred = findBluetoothInputDevice(audioManager)
                if (ok) {
                    Log.i(TAG, "Using communication device routing for BT mic (type=${comm.type})")
                    return BtInputRoute(
                        isActive = true,
                        usedSco = false,
                        usedCommunicationDevice = true,
                        preferredInput = preferred,
                    )
                }
            }
        }

        // Legacy fallback: SCO routing.
        val scoConnected = startBluetoothScoBestEffort(audioManager)
        val preferred = findBluetoothInputDevice(audioManager)
        return BtInputRoute(
            isActive = scoConnected,
            usedSco = scoConnected,
            usedCommunicationDevice = false,
            preferredInput = preferred,
        )
    }

    private fun stopBluetoothInputRoutingBestEffort(audioManager: AudioManager, route: BtInputRoute?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && (route?.usedCommunicationDevice == true || route == null)) {
            runCatching { audioManager.clearCommunicationDevice() }
        }
        stopBluetoothScoBestEffort(audioManager)
    }

    private fun startBluetoothScoBestEffort(audioManager: AudioManager): Boolean {
        return runCatching {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            runCatching { audioManager.startBluetoothSco() }
            runCatching { audioManager.isBluetoothScoOn = true }

            // Poll for SCO connection (non-suspend; max 6s).
            val deadline = System.currentTimeMillis() + 6_000
            while (System.currentTimeMillis() < deadline) {
                if (audioManager.isBluetoothScoOn) {
                    return@runCatching true
                }
                Thread.sleep(200)
            }
            false
        }.getOrElse { e ->
            Log.w(TAG, "Failed to start Bluetooth SCO: ${e.message}")
            false
        }
    }

    private fun stopBluetoothScoBestEffort(audioManager: AudioManager) {
        runCatching { audioManager.isBluetoothScoOn = false }
        runCatching { audioManager.stopBluetoothSco() }
        runCatching { audioManager.mode = AudioManager.MODE_NORMAL }
    }
}

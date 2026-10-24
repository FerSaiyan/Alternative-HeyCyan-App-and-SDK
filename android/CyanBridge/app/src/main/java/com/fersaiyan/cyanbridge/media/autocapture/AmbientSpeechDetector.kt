package com.fersaiyan.cyanbridge.media.autocapture

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import com.fersaiyan.cyanbridge.audio.AudioRouteResult
import com.fersaiyan.cyanbridge.audio.AudioSessionCoordinator
import com.fersaiyan.cyanbridge.audio.AudioSessionHandle
import com.fersaiyan.cyanbridge.audio.PcmRecorder
import com.fersaiyan.cyanbridge.shared.audio.AudioSessionRequest
import com.fersaiyan.cyanbridge.shared.audio.SpeechActivityDetector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Ambient speech detector that uses the glasses' Bluetooth microphone to check
 * whether the user is currently in a conversation.
 *
 * Runs as an [AudioSessionRequest.AmbientListening] session: it never displaces another
 * microphone user (Gemini Live, a spoken prompt, meeting capture, a voice plugin) and stops
 * early when one starts. Routing follows the shared Bluetooth communication route.
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
     * Falls back to the phone mic if no Bluetooth headset is detected, and returns false when
     * another session holds the microphone.
     */
    suspend fun detectSpeechFor(
        context: Context,
        durationMs: Long = 60_000L,
        sampleRate: Int = 16_000,
        rmsThreshold: Double = 200.0,
        minVoicedFraction: Double = 0.10,
    ): Boolean {
        if (
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "Skipping speech detection: RECORD_AUDIO is not granted")
            return false
        }
        val session = AudioSessionCoordinator.tryOpen(context, AudioSessionRequest.AmbientListening(TAG))
        if (session == null) {
            Log.i(TAG, "Skipping speech detection: the microphone is in use")
            return false
        }
        return try {
            val btRoute = session.connectRoute()
            if (btRoute.bluetoothActive) {
                Log.i(TAG, "Using Bluetooth mic for speech detection")
            } else {
                Log.i(TAG, "Bluetooth mic not available, falling back to phone mic")
            }
            detectWithAudioRecord(session, btRoute, durationMs, sampleRate, rmsThreshold, minVoicedFraction)
        } finally {
            session.close()
        }
    }

    private suspend fun detectWithAudioRecord(
        session: AudioSessionHandle,
        btRoute: AudioRouteResult,
        durationMs: Long,
        sampleRate: Int,
        rmsThreshold: Double,
        minVoicedFraction: Double,
    ): Boolean {
        val audioSource = if (btRoute.bluetoothActive) {
            MediaRecorder.AudioSource.VOICE_COMMUNICATION
        } else {
            MediaRecorder.AudioSource.MIC
        }

        val record = try {
            // Prefer the BT input device explicitly when available.
            session.openRecorder(audioSource, sampleRate, { minBuffer -> minBuffer * 2 }, btRoute.inputDevice)
        } catch (e: PcmRecorder.OpenException) {
            Log.w(TAG, e.detail)
            return false
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Speech detection could not open the microphone: ${e.message}")
            return false
        }

        return try {
            record.start()
            val buffer = ShortArray(record.minBufferBytes.coerceAtLeast(1024))
            val detector = SpeechActivityDetector(sampleRate, rmsThreshold, minVoicedFraction)
            val deadline = System.currentTimeMillis() + durationMs

            while (System.currentTimeMillis() < deadline) {
                if (!session.isActive) {
                    Log.i(TAG, "Speech check stopped early: another session took the microphone")
                    break
                }
                val read = record.read(buffer, 0, buffer.size)
                if (read <= 0) {
                    delay(10)
                    continue
                }
                detector.feed(buffer, 0, read)
            }

            record.stop()

            if (detector.totalFrames == 0) {
                Log.i(TAG, "No frames read")
                return false
            }

            val result = detector.hasSignificantSpeech()
            Log.i(
                TAG,
                "Speech check (${if (btRoute.bluetoothActive) "BT" else "phone"} mic): " +
                    "${detector.debugInfo()} result=$result"
            )
            result
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            Log.w(TAG, "Missing RECORD_AUDIO permission: ${e.message}")
            false
        } catch (e: Exception) {
            Log.e(TAG, "Speech detection failed: ${e.message}", e)
            false
        } finally {
            session.closeRecorder(record)
        }
    }
}

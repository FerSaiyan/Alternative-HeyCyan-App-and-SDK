package com.fersaiyan.cyanbridge.ai.live

import android.content.Context
import android.util.Base64
import android.util.Log
import com.fersaiyan.cyanbridge.agent.ProSubscriptionServerPrefs
import com.fersaiyan.cyanbridge.ai.router.AiProviderPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Server-side relay for free-tier Gemini Live.
 * Phone sends PCM → Vercel (holds GEMINI_API_KEY) → Google generateContent → text back.
 * No GEMINI_API_KEY or ephemeral token is exposed to the phone.
 * Paid migration keeps direct WS via GeminiLiveClient + ephemeral bidiGenerateContentSetup token.
 */
class GeminiLiveRelayClient(
    private val appContext: Context,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build(),
) {
    suspend fun sendAudioAndGetText(
        pcm16: ShortArray,
        sampleRateHz: Int = 16000,
        prompt: String = "Respond helpfully in the user's language.",
        imageJpegBase64: String? = null,
    ): String = withContext(Dispatchers.IO) {
        val authToken = ProSubscriptionServerPrefs.getApiToken(appContext).trim()
        check(authToken.isNotBlank()) { "Sign in to CyanBridge before using Gemini Live relay" }
        val base = AiProviderPrefs.getRelayBaseUrl(appContext).trim().trimEnd('/')
        check(base.startsWith("https://")) { "Relay requires https" }

        // Convert PCM16 mono to WAV base64 for Google (audio/wav)
        val wavBase64 = pcmToWavBase64(pcm16, sampleRateHz)

        val body = JSONObject().apply {
            put("audio_base64", wavBase64)
            put("mime_type", "audio/wav")
            put("prompt", prompt)
            if (imageJpegBase64 != null) put("image_base64", imageJpegBase64)
        }.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

        val req = Request.Builder()
            .url("$base/api/pro/live/relay")
            .header("Authorization", "Bearer $authToken")
            .post(body)
            .build()

        http.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            val json = JSONObject(raw.ifBlank { "{}" })
            if (!resp.isSuccessful) {
                throw IllegalStateException(json.optString("error", "Relay failed: $raw"))
            }
            val text = json.optString("text", "").trim()
            if (text.isBlank()) throw IllegalStateException("Empty relay reply")
            Log.d("GeminiLiveRelay", "relay text ${text.take(120)}")
            return@withContext text
        }
    }

    private fun pcmToWavBase64(pcm: ShortArray, sampleRateHz: Int): String {
        val pcmBytes = ByteArray(pcm.size * 2)
        for (i in pcm.indices) {
            val v = pcm[i].toInt()
            pcmBytes[i * 2] = (v and 0xff).toByte()
            pcmBytes[i * 2 + 1] = ((v ushr 8) and 0xff).toByte()
        }
        // Minimal WAV header 44 bytes
        val header = ByteArray(44)
        val totalAudioLen = pcmBytes.size
        val totalDataLen = totalAudioLen + 36
        val sampleRate = sampleRateHz
        val channels = 1
        val byteRate = sampleRate * channels * 2
        fun writeInt(offset: Int, value: Int) {
            header[offset] = (value and 0xff).toByte()
            header[offset + 1] = ((value ushr 8) and 0xff).toByte()
            header[offset + 2] = ((value ushr 16) and 0xff).toByte()
            header[offset + 3] = ((value ushr 24) and 0xff).toByte()
        }
        fun writeShort(offset: Int, value: Int) {
            header[offset] = (value and 0xff).toByte()
            header[offset + 1] = ((value ushr 8) and 0xff).toByte()
        }
        header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte(); header[2] = 'F'.code.toByte(); header[3] = 'F'.code.toByte()
        writeInt(4, totalDataLen)
        header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte(); header[10] = 'V'.code.toByte(); header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte(); header[14] = 't'.code.toByte(); header[15] = ' '.code.toByte()
        writeInt(16, 16); writeShort(20, 1); writeShort(22, channels); writeInt(24, sampleRate); writeInt(28, byteRate); writeShort(32, channels * 2); writeShort(34, 16)
        header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte(); header[38] = 't'.code.toByte(); header[39] = 'a'.code.toByte()
        writeInt(40, totalAudioLen)
        val wav = ByteArray(header.size + pcmBytes.size)
        System.arraycopy(header, 0, wav, 0, header.size)
        System.arraycopy(pcmBytes, 0, wav, header.size, pcmBytes.size)
        return Base64.encodeToString(wav, Base64.NO_WRAP)
    }
}

package com.fersaiyan.cyanbridge.devices.eyevue

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Transport boundary shared by the visible preview and headless vision decoder. */
internal interface EyevueLiveConnection {
    suspend fun open(onStatus: (String, String) -> Unit): String
    suspend fun close()
}

internal class EyevueGlassesLiveConnection(context: Context, private val manager: EyevueManager) : EyevueLiveConnection {
    private val transport = EyevueWifiTransport(context)
    private var proxy: RtspPlayRewriteProxy? = null
    private var commandAttempted = false

    override suspend fun open(onStatus: (String, String) -> Unit): String {
        if (!manager.isConnected()) throw IOException("Eyevue BLE is not connected")
        val project = manager.awaitProject() ?: throw IOException("Eyevue did not report its project/model")
        val profile = EyevueMediaProfile.fromProject(project)
        onStatus("Starting live mode", "Sending Eyevue 0x67 command")
        commandAttempted = true
        val ssid = manager.startLiveAndAwaitSsid(profile.mode == EyevueWifiMode.AP)
            ?: throw IOException("Eyevue did not report the live Wi-Fi SSID")
        onStatus("Connecting Wi-Fi", "Joining $ssid")
        transport.connect(profile.mode, ssid, "12345678", profile.baseIp).getOrElse { throw it }
        profile.liveControlUrl?.let { url ->
            onStatus("Starting stream", "Requesting Eyevue HTTP live endpoint")
            withContext(Dispatchers.IO) {
                val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(10, TimeUnit.SECONDS).build()
                var failure: IOException? = null
                for (attempt in 0 until 5) {
                    try {
                        client.newCall(Request.Builder().url(url).get().build()).execute().use {
                            if (it.isSuccessful) return@withContext
                            failure = IOException("Eyevue live HTTP request failed: ${it.code}")
                        }
                    } catch (error: IOException) { failure = error }
                    if (attempt < 4) delay(500)
                }
                throw failure ?: IOException("Eyevue live HTTP request failed")
            }
        }
        val relay = RtspPlayRewriteProxy(
            profile.baseIp, 554, streamPath = Uri.parse(profile.liveStreamUrl).path ?: "/xxx.mov",
            log = { Log.i("EyevueRtspProxy", it) },
        )
        proxy = relay
        return "rtsp://127.0.0.1:${relay.start()}/live/"
    }

    override suspend fun close() {
        proxy?.stop()
        proxy = null
        if (commandAttempted && manager.isConnected()) manager.stopLiveBlocking()
        commandAttempted = false
        transport.disconnect()
    }
}

package com.fersaiyan.cyanbridge.ota.family

import com.fersaiyan.cyanbridge.shared.devices.firmware.EyevueOfficialFirmware
import com.fersaiyan.cyanbridge.shared.devices.firmware.EyevueOtaOffer
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** HTTP for [EyevueOfficialFirmware]: the anonymous version check and the verified download. */
internal object EyevueOfficialClient {
    private const val MAX_IMAGE_BYTES = 8L * 1024 * 1024

    /** The newer BLE firmware the vendor offers for [modelCode] at [version], or null when up to date. */
    fun check(modelCode: String, version: String): EyevueOtaOffer? {
        var lastError: IOException? = null
        for (host in EyevueOfficialFirmware.HOSTS) {
            val url = EyevueOfficialFirmware.checkUrl(host, modelCode, version)
                ?: throw IllegalArgumentException("The glasses reported an unexpected model or version ($modelCode $version).")
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 15_000
                conn.readTimeout = 20_000
                conn.setRequestProperty("Accept", "application/json")
                val status = conn.responseCode
                if (status != HttpURLConnection.HTTP_OK) {
                    lastError = IOException("HTTP $status from ${URL(host).host}")
                    continue
                }
                val body = conn.inputStream.use { it.readBytes().decodeToString() }
                return EyevueOfficialFirmware.parseOffer(body, modelCode, version)
            } catch (error: IOException) {
                lastError = error
            } finally {
                conn.disconnect()
            }
        }
        throw IOException("Could not reach the EyeVue update server (${lastError?.message ?: "no answer"}).")
    }

    /** Downloads the offer's BLE image and verifies size, MD5 and (for mirrored versions) sha256. */
    fun download(offer: EyevueOtaOffer, onProgress: (Int?) -> Unit): ByteArray {
        require(EyevueOfficialFirmware.isAllowedDownload(offer.bleUrl)) { "Unexpected firmware download address." }
        val conn = URL(offer.bleUrl).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 20_000
            conn.readTimeout = 60_000
            conn.instanceFollowRedirects = false
            val status = conn.responseCode
            if (status != HttpURLConnection.HTTP_OK) throw IOException("The firmware download failed (HTTP $status).")
            val length = conn.contentLengthLong.takeIf { it > 0 }
            if (length != null && length > MAX_IMAGE_BYTES) throw IOException("The firmware download is unexpectedly large.")
            val expected = EyevueOfficialFirmware.expectation(
                offer,
                contentLength = length,
                contentMd5Base64 = conn.getHeaderField("Content-MD5"),
                etag = conn.getHeaderField("ETag"),
            )
            val md5 = MessageDigest.getInstance("MD5")
            val sha256 = MessageDigest.getInstance("SHA-256")
            val out = ByteArrayOutputStream(length?.toInt() ?: 2 * 1024 * 1024)
            var lastPercent = -1
            conn.inputStream.use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    md5.update(buffer, 0, n)
                    sha256.update(buffer, 0, n)
                    out.write(buffer, 0, n)
                    if (out.size() > MAX_IMAGE_BYTES) throw IOException("The firmware download is unexpectedly large.")
                    val percent = length?.let { (out.size() * 100 / it).toInt() }
                    if (percent != lastPercent) {
                        lastPercent = percent ?: -1
                        onProgress(percent)
                    }
                }
            }
            val bytes = out.toByteArray()
            EyevueOfficialFirmware.verifyDownload(expected, bytes.size.toLong(), md5.digest().toHex(), sha256.digest().toHex())
                ?.let { throw IOException(it) }
            return bytes
        } finally {
            conn.disconnect()
        }
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
}

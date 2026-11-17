package com.fersaiyan.cyanbridge.ai.router

import com.fersaiyan.cyanbridge.shared.diagnostics.DiagnosticEvents
import com.fersaiyan.cyanbridge.shared.diagnostics.DiagnosticSubsystem
import com.fersaiyan.cyanbridge.shared.platform.HttpRequest
import com.fersaiyan.cyanbridge.shared.platform.HttpResponse
import com.fersaiyan.cyanbridge.shared.platform.HttpTransport
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Blocking HttpURLConnection transport for the shared AI clients, with the
 * per-request timeouts they ask for. Like the clients it replaced, it runs on the
 * caller's dispatcher.
 */
object UrlConnectionHttpTransport : HttpTransport {
    override suspend fun execute(request: HttpRequest): HttpResponse {
        val conn = URL(request.url).openConnection() as HttpURLConnection
        conn.requestMethod = request.method
        conn.connectTimeout = request.connectTimeoutMs
        conn.readTimeout = request.readTimeoutMs
        request.headers.forEach { (name, value) -> conn.setRequestProperty(name, value) }
        request.body?.let { body ->
            conn.doOutput = true
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }
        }
        val code = try {
            conn.responseCode
        } catch (error: java.io.IOException) {
            DiagnosticEvents.warn(DiagnosticSubsystem.PRO, "HTTP request failed", requestFields(request), error)
            throw error
        }
        if (code !in 200..299) {
            DiagnosticEvents.warn(DiagnosticSubsystem.PRO, "HTTP $code", requestFields(request))
        }
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val body = (stream ?: conn.inputStream).bufferedReader().use { it.readText() }
        conn.disconnect()
        return HttpResponse(statusCode = code, headers = emptyMap(), body = body)
    }

    /** Method, host and path only: queries and bodies can carry keys or user text. */
    private fun requestFields(request: HttpRequest): Map<String, String> {
        val url = runCatching { URL(request.url) }.getOrNull()
        return mapOf("request" to "${request.method} ${url?.host.orEmpty()}${url?.path.orEmpty()}")
    }
}

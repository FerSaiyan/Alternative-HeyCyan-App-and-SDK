package com.fersaiyan.cyanbridge.ota

import android.util.Log
import java.io.File
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Minimal HTTP server that serves a single file to the glasses during OTA.
 *
 * The glasses' ai_glass_ota agent does a plain HTTP GET to fetch the .swu file.
 * This server binds to the phone's P2P IP and serves the firmware from [serveFile].
 */
class OtaHttpServer(
    private val port: Int = 8080,
) {
    private var serverSocket: ServerSocket? = null
    @Volatile
    private var activeClient: Socket? = null
    private var serveFile: File? = null

    @Volatile
    private var running = false

    val isRunning: Boolean get() = running

    @Synchronized
    fun start(file: File, bindAddress: String) {
        val restarting = running
        if (restarting) {
            Log.w(TAG, "Server already running, stopping previous instance")
            stop()
        }
        val socket = bindSocket(bindAddress, retryAfterClose = restarting)
        serveFile = file
        serverSocket = socket
        running = true
        Log.i(TAG, "Listening on $bindAddress:$port, serving ${file.name} (${file.length()} bytes)")
        Thread(
            {
                try {
                    while (running && serverSocket === socket) {
                        try {
                            val client = socket.accept()
                            client.soTimeout = CLIENT_READ_TIMEOUT_MS
                            val shouldHandle = synchronized(this@OtaHttpServer) {
                                if (running && serverSocket === socket) {
                                    activeClient = client
                                    true
                                } else {
                                    false
                                }
                            }
                            if (!shouldHandle) {
                                client.close()
                                continue
                            }
                            try {
                                handleClient(client, file)
                            } finally {
                                if (activeClient === client) activeClient = null
                            }
                        } catch (e: Exception) {
                            if (running && serverSocket === socket) {
                                Log.e(TAG, "Accept error: ${e.message}")
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (running && serverSocket === socket) Log.e(TAG, "Server error: ${e.message}", e)
                } finally {
                    synchronized(this@OtaHttpServer) {
                        if (serverSocket === socket) {
                            serverSocket = null
                            serveFile = null
                            running = false
                        }
                    }
                }
            },
            "OtaHttpServer",
        ).start()
    }

    @Synchronized
    fun stop() {
        running = false
        val client = activeClient
        activeClient = null
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
        try {
            client?.close()
        } catch (_: Exception) {
        }
        serveFile = null
        Log.i(TAG, "Stopped")
    }

    private fun bindSocket(bindAddress: String, retryAfterClose: Boolean): ServerSocket {
        val attempts = if (retryAfterClose) BIND_RETRY_ATTEMPTS else 1
        var lastBindError: BindException? = null
        repeat(attempts) { attempt ->
            val socket = ServerSocket()
            try {
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(InetAddress.getByName(bindAddress), port))
                return socket
            } catch (e: BindException) {
                lastBindError = e
                runCatching { socket.close() }
                if (attempt + 1 < attempts) {
                    try {
                        Thread.sleep(BIND_RETRY_DELAY_MS)
                    } catch (interrupted: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw e
                    }
                }
            } catch (e: Exception) {
                runCatching { socket.close() }
                throw e
            }
        }
        throw lastBindError ?: BindException("Unable to bind OTA HTTP server to $bindAddress:$port")
    }

    private fun handleClient(socket: Socket, file: File) {
        try {
            val input = socket.getInputStream().bufferedReader()
            val requestLine = input.readLine() ?: return
            // Drain remaining headers
            while (true) {
                val line = input.readLine()
                if (line.isNullOrEmpty()) break
            }

            if (requestLine.startsWith("GET /")) {
                val out = socket.getOutputStream()
                val header = buildString {
                    append("HTTP/1.1 200 OK\r\n")
                    append("Content-Type: application/octet-stream\r\n")
                    append("Content-Length: ${file.length()}\r\n")
                    append("Connection: close\r\n")
                    append("Access-Control-Allow-Origin: *\r\n")
                    append("\r\n")
                }
                out.write(header.toByteArray())
                file.inputStream().use { fis ->
                    val buf = ByteArray(8192)
                    var read: Int
                    while (fis.read(buf).also { read = it } > 0) {
                        out.write(buf, 0, read)
                        out.flush()
                    }
                }
                Log.i(TAG, "Served ${file.name} to ${socket.inetAddress.hostAddress}")
            } else {
                val resp = "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                socket.getOutputStream().write(resp.toByteArray())
            }
        } catch (e: Exception) {
            if (running && activeClient === socket) Log.e(TAG, "Client error: ${e.message}")
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
    }

    companion object {
        private const val TAG = "OtaHttpServer"
        private const val CLIENT_READ_TIMEOUT_MS = 10_000
        private const val BIND_RETRY_ATTEMPTS = 5
        private const val BIND_RETRY_DELAY_MS = 25L
    }
}

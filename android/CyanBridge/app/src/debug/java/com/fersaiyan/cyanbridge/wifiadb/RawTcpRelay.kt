package com.fersaiyan.cyanbridge.wifiadb

import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

internal class RawTcpRelay(
    private val listenPort: Int,
    private val outboundSocket: () -> Socket,
) : Closeable {
    private val lock = Any()
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private var serverSocket: ServerSocket? = null
    private var relayJob: Job? = null
    private var relayScope: CoroutineScope? = null

    val isRunning: Boolean
        get() = synchronized(lock) { serverSocket?.isClosed == false && relayJob?.isActive == true }

    fun start() {
        synchronized(lock) {
            if (isRunning) return
            val server = ServerSocket().apply {
                reuseAddress = true
                // adb forward connects to the app from the phone itself. Keep this privileged
                // relay off every LAN/P2P/tethering interface so another network peer cannot
                // obtain the glasses' ADB transport while the debug session is armed.
                bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), listenPort))
            }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            serverSocket = server
            relayScope = scope
            relayJob = scope.launch {
                try {
                    while (isActive) {
                        val incoming = try {
                            server.accept()
                        } catch (_: SocketException) {
                            break
                        } catch (_: IOException) {
                            break
                        }
                        val shouldForward = synchronized(lock) {
                            if (serverSocket !== server || !isActive) {
                                false
                            } else {
                                sockets += incoming
                                true
                            }
                        }
                        if (!shouldForward) {
                            closeSocket(incoming)
                            break
                        }
                        launch { forward(incoming) }
                    }
                } finally {
                    var cancelScope = false
                    synchronized(lock) {
                        if (serverSocket === server) {
                            runCatching { server.close() }
                            serverSocket = null
                            relayJob = null
                            relayScope = null
                            cancelScope = true
                        }
                    }
                    if (cancelScope) scope.cancel()
                }
            }
        }
    }

    private suspend fun forward(incoming: Socket) {
        var outgoing: Socket? = null
        try {
            outgoing = outboundSocket()
            sockets += outgoing
            val connectedOutgoing = outgoing
            coroutineScope {
                val upstream = launch(Dispatchers.IO) {
                    incoming.getInputStream().copyTo(connectedOutgoing.getOutputStream())
                    runCatching { connectedOutgoing.shutdownOutput() }
                }
                val downstream = launch(Dispatchers.IO) {
                    connectedOutgoing.getInputStream().copyTo(incoming.getOutputStream())
                    runCatching { incoming.shutdownOutput() }
                }
                joinAll(upstream, downstream)
            }
        } finally {
            closeSocket(incoming)
            outgoing?.let(::closeSocket)
        }
    }

    override fun close() {
        val scope: CoroutineScope?
        synchronized(lock) {
            runCatching { serverSocket?.close() }
            serverSocket = null
            // toList() reads size() and then iterates; a relay thread removing a socket in between made it throw
            // NoSuchElementException (flaky RawTcpRelayTest). ArrayList copies through toArray(), which is safe.
            ArrayList(sockets).forEach(::closeSocket)
            sockets.clear()
            relayJob?.cancel()
            relayJob = null
            scope = relayScope
            relayScope = null
        }
        scope?.cancel()
    }

    private fun closeSocket(socket: Socket) {
        sockets -= socket
        runCatching { socket.close() }
    }
}

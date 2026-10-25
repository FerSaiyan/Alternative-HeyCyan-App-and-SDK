package com.fersaiyan.cyanbridge.devices.memomind

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.fersaiyan.cyanbridge.shared.devices.memomind.GmCodec
import com.fersaiyan.cyanbridge.shared.devices.memomind.MemoMindRecordingLink
import com.fersaiyan.cyanbridge.shared.devices.memomind.MemoMindSession
import com.fersaiyan.cyanbridge.shared.devices.memomind.MemoMindTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID

enum class MemoMindLinkState { DISCONNECTED, CONNECTING, CONNECTED }

/**
 * MemoMind over Classic Bluetooth SPP, the transport the official App uses: GM commands on
 * service 0x7033 (legacy fallback 0x1101) and the recording stream on 0x2024, opened only while
 * the microphone streams. Received bytes feed [session].
 */
class MemoMindSppTransport(context: Context) : MemoMindTransport {
    private val context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connectionMutex = Mutex()
    private val _state = MutableStateFlow(MemoMindLinkState.DISCONNECTED)

    val state: StateFlow<MemoMindLinkState> = _state.asStateFlow()

    /** The MemoMind business session over this link; reset on every connect. */
    val session = MemoMindSession(this)

    /** Called on the reader thread when an established command link drops on its own. */
    var onLinkLost: (() -> Unit)? = null

    @Volatile private var commandSocket: BluetoothSocket? = null
    @Volatile private var recordingSocket: BluetoothSocket? = null
    @Volatile private var device: BluetoothDevice? = null
    private var commandReader: Job? = null
    private var recordingReader: Job? = null

    /** The SPP service UUID the command link uses, for diagnostics. */
    @Volatile var commandServiceUuid: UUID? = null
        private set

    override val isConnected: Boolean
        get() = _state.value == MemoMindLinkState.CONNECTED && commandSocket?.isConnected == true

    override val maxFrameBytes: Int = GmCodec.MAX_FRAME_BYTES
    override val awaitsFragmentAcks: Boolean = false

    override val recordingLink: MemoMindRecordingLink = object : MemoMindRecordingLink {
        override suspend fun open() {
            val target = device ?: throw IOException("MemoMind is not connected")
            closeRecording()
            val socket = openSocket(target, RECORDING_UUID)
            recordingSocket = socket
            recordingReader = startReader(socket, "recording") { session.onRecordingBytes(it) }
            Log.i(TAG, "Recording link open")
        }

        override suspend fun write(bytes: ByteArray) {
            val socket = recordingSocket ?: throw IOException("MemoMind recording link is closed")
            withContext(Dispatchers.IO) {
                socket.outputStream.write(bytes)
                socket.outputStream.flush()
            }
        }

        override suspend fun close() = closeRecording()
    }

    @SuppressLint("MissingPermission")
    suspend fun connect(address: String): Result<Unit> = connectionMutex.withLock {
        if (!hasConnectPermission()) {
            return@withLock Result.failure(SecurityException("BLUETOOTH_CONNECT permission is required"))
        }
        if (isConnected) return@withLock Result.success(Unit)
        disconnectInternal()
        _state.value = MemoMindLinkState.CONNECTING
        try {
            val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
                ?: throw IOException("Bluetooth is unavailable")
            if (!adapter.isEnabled) throw IOException("Bluetooth is disabled")
            val target = adapter.getRemoteDevice(address.trim())
            runCatching { adapter.cancelDiscovery() }
            val (socket, uuid) = openCommandSocket(target)
            device = target
            commandSocket = socket
            commandServiceUuid = uuid
            session.onDisconnected()
            _state.value = MemoMindLinkState.CONNECTED
            commandReader = startReader(socket, "command") { session.onCommandBytes(it) }
            Log.i(TAG, "SPP connected on $uuid")
            Result.success(Unit)
        } catch (error: Throwable) {
            Log.w(TAG, "SPP connect failed: ${error.message}")
            disconnectInternal()
            _state.value = MemoMindLinkState.DISCONNECTED
            Result.failure(error)
        }
    }

    fun disconnect() {
        disconnectInternal()
        _state.value = MemoMindLinkState.DISCONNECTED
    }

    /** The session serializes requests, so the frames of one message go out back to back. */
    override suspend fun writeCommand(frame: ByteArray) {
        val socket = commandSocket?.takeIf { isConnected } ?: throw IOException("MemoMind SPP is not connected")
        withContext(Dispatchers.IO) {
            socket.outputStream.write(frame)
            socket.outputStream.flush()
        }
    }

    private suspend fun openCommandSocket(target: BluetoothDevice): Pair<BluetoothSocket, UUID> {
        var lastError: Throwable? = null
        for (uuid in COMMAND_UUIDS) {
            try {
                return openSocket(target, uuid) to uuid
            } catch (error: IOException) {
                Log.i(TAG, "SPP service $uuid unavailable: ${error.message}")
                lastError = error
            }
        }
        throw IOException("MemoMind command service not reachable; pair the glasses and close the official app", lastError)
    }

    /** Connects an RFCOMM socket, closing it from a watchdog when the stack does not answer. */
    @SuppressLint("MissingPermission")
    private suspend fun openSocket(target: BluetoothDevice, uuid: UUID): BluetoothSocket = coroutineScope {
        val socket = target.createRfcommSocketToServiceRecord(uuid)
        val watchdog = launch {
            delay(CONNECT_TIMEOUT_MS)
            runCatching { socket.close() }
        }
        try {
            runInterruptible(Dispatchers.IO) { socket.connect() }
            socket
        } catch (error: Throwable) {
            runCatching { socket.close() }
            throw if (error is IOException) error else IOException(error.message, error)
        } finally {
            watchdog.cancel()
        }
    }

    private fun startReader(socket: BluetoothSocket, label: String, onBytes: (ByteArray) -> Unit): Job = scope.launch {
        val buffer = ByteArray(READ_BUFFER_BYTES)
        try {
            while (isActive) {
                val count = socket.inputStream.read(buffer)
                if (count < 0) throw IOException("$label stream closed")
                if (count > 0) onBytes(buffer.copyOf(count))
            }
        } catch (error: Throwable) {
            if (isActive) Log.i(TAG, "SPP $label reader ended: ${error.message}")
        } finally {
            if (label == "command" && commandSocket === socket) {
                Log.w(TAG, "SPP command link lost")
                disconnectInternal(cancelCommandReader = false)
                _state.value = MemoMindLinkState.DISCONNECTED
                onLinkLost?.invoke()
            }
        }
    }

    private fun closeRecording() {
        recordingReader?.cancel()
        recordingReader = null
        val socket = recordingSocket
        recordingSocket = null
        runCatching { socket?.close() }
    }

    private fun disconnectInternal(cancelCommandReader: Boolean = true) {
        closeRecording()
        if (cancelCommandReader) commandReader?.cancel()
        commandReader = null
        val socket = commandSocket
        commandSocket = null
        device = null
        runCatching { socket?.close() }
        session.onDisconnected()
    }

    private fun hasConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAG = "MemoMindSpp"
        private const val CONNECT_TIMEOUT_MS = 12_000L
        private const val READ_BUFFER_BYTES = 4096

        /** GM commands: the App's preferred service, then the legacy serial-port fallback. */
        val COMMAND_UUIDS: List<UUID> = listOf(
            UUID.fromString("00007033-0000-1000-8000-00805f9b34fb"),
            UUID.fromString("00001101-0000-1000-8000-00805f9b34fb"),
        )
        val RECORDING_UUID: UUID = UUID.fromString("00002024-0000-1000-8000-00805f9b34fb")
    }
}

package com.fersaiyan.cyanbridge.devices.tunebuds

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class TuneBudsSppState {
    DISCONNECTED,
    BONDING,
    CONNECTING,
    CONNECTED,
    ERROR,
}

/** Classic Bluetooth RFCOMM transport used by the TuneBuds XK ONE PRO build. */
class TuneBudsSppClient(context: Context) {
    companion object {
        private const val TAG = "TuneBudsSpp"
        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val BOND_TIMEOUT_MS = 30_000L
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")
    }

    private val context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connectionMutex = Mutex()
    private val writeMutex = Mutex()
    private val decoder = TuneBudsFrameDecoder()
    private val _state = MutableStateFlow(TuneBudsSppState.DISCONNECTED)
    private val _frames = MutableSharedFlow<TuneBudsFrame>(extraBufferCapacity = 64)

    val state: StateFlow<TuneBudsSppState> = _state.asStateFlow()
    val frames: SharedFlow<TuneBudsFrame> = _frames.asSharedFlow()

    @Volatile
    private var socket: BluetoothSocket? = null
    private var readJob: Job? = null
    private var nextHostSequence = 0
    private var maxPacketSize = TuneBudsProtocol.DEFAULT_MAX_PACKET_SIZE

    @SuppressLint("MissingPermission")
    suspend fun connect(address: String): Result<Unit> = connectionMutex.withLock {
        if (!hasConnectPermission()) {
            return@withLock Result.failure(SecurityException("BLUETOOTH_CONNECT permission is required"))
        }
        val normalizedAddress = address.trim()
        if (normalizedAddress.isBlank()) {
            return@withLock Result.failure(IllegalArgumentException("TuneBuds Bluetooth address is empty"))
        }
        if (_state.value == TuneBudsSppState.CONNECTED && socket?.isConnected == true) {
            return@withLock Result.success(Unit)
        }

        disconnectInternal()
        return@withLock try {
            val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                ?: throw IOException("BluetoothManager is unavailable")
            val adapter = manager.adapter ?: throw IOException("BluetoothAdapter is unavailable")
            if (!adapter.isEnabled) throw IOException("Bluetooth is disabled")
            val device = adapter.getRemoteDevice(normalizedAddress)
            ensureBonded(device)
            _state.value = TuneBudsSppState.CONNECTING
            runCatching { adapter.cancelDiscovery() }

            val pendingSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)
            socket = pendingSocket
            withTimeout(CONNECT_TIMEOUT_MS) {
                runInterruptible(Dispatchers.IO) { pendingSocket.connect() }
            }
            decoder.reset()
            nextHostSequence = 0
            maxPacketSize = TuneBudsProtocol.DEFAULT_MAX_PACKET_SIZE
            _state.value = TuneBudsSppState.CONNECTED
            startReadLoop(pendingSocket)
            Log.i(TAG, "RFCOMM connected to $normalizedAddress")
            Result.success(Unit)
        } catch (error: Throwable) {
            disconnectInternal()
            _state.value = TuneBudsSppState.ERROR
            Result.failure(
                if (error is TimeoutCancellationException) IOException("TuneBuds RFCOMM connection timed out", error)
                else error,
            )
        }
    }

    suspend fun send(command: Int, payload: ByteArray = byteArrayOf()): Result<Unit> = writeMutex.withLock {
        val currentSocket = socket
        if (_state.value != TuneBudsSppState.CONNECTED || currentSocket?.isConnected != true) {
            return@withLock Result.failure(IOException("TuneBuds SPP is not connected"))
        }
        return@withLock try {
            val encoded = TuneBudsProtocol.encode(
                command = command,
                payload = payload,
                maxPacketSize = maxPacketSize,
                initialSequence = nextHostSequence,
            )
            withContext(Dispatchers.IO) {
                encoded.frames.forEach { currentSocket.outputStream.write(it) }
                currentSocket.outputStream.flush()
            }
            nextHostSequence = encoded.nextSequence
            Result.success(Unit)
        } catch (error: Throwable) {
            _state.value = TuneBudsSppState.ERROR
            Result.failure(error)
        }
    }

    fun setMaxPacketSize(size: Int) {
        if (size > TuneBudsProtocol.HEADER_SIZE) {
            maxPacketSize = size.coerceAtMost(260)
        }
    }

    fun isConnected(): Boolean =
        _state.value == TuneBudsSppState.CONNECTED && socket?.isConnected == true

    fun disconnect() {
        disconnectInternal()
        _state.value = TuneBudsSppState.DISCONNECTED
    }

    private fun startReadLoop(connectedSocket: BluetoothSocket) {
        readJob?.cancel()
        readJob = scope.launch {
            val buffer = ByteArray(1024)
            try {
                while (isActive && connectedSocket.isConnected) {
                    val count = connectedSocket.inputStream.read(buffer)
                    if (count < 0) throw IOException("TuneBuds RFCOMM stream closed")
                    if (count == 0) continue
                    val malformedBefore = decoder.malformedFrameCount
                    val frames = decoder.append(buffer.copyOf(count))
                    if (decoder.malformedFrameCount > malformedBefore) {
                        Log.w(
                            TAG,
                            "Accepted TuneBuds data after ${decoder.malformedFrameCount - malformedBefore} frame warning(s)",
                        )
                    }
                    for (frame in frames) {
                        _frames.emit(frame)
                    }
                }
            } catch (error: Throwable) {
                if (isActive) Log.w(TAG, "RFCOMM read loop ended: ${error.message}")
            } finally {
                if (socket === connectedSocket) {
                    disconnectInternal(closeReadJob = false)
                    _state.value = TuneBudsSppState.DISCONNECTED
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun ensureBonded(device: BluetoothDevice) {
        if (device.bondState == BluetoothDevice.BOND_BONDED) return
        _state.value = TuneBudsSppState.BONDING
        withTimeout(BOND_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                val receiver = BondReceiver(device.address, continuation)
                ContextCompat.registerReceiver(
                    context,
                    receiver,
                    IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
                    ContextCompat.RECEIVER_EXPORTED,
                )
                continuation.invokeOnCancellation { runCatching { context.unregisterReceiver(receiver) } }
                if (!device.createBond()) {
                    runCatching { context.unregisterReceiver(receiver) }
                    continuation.resumeWithException(IOException("Could not start TuneBuds Bluetooth bonding"))
                }
            }
        }
    }

    private inner class BondReceiver(
        private val expectedAddress: String,
        private val continuation: CancellableContinuation<Unit>,
    ) : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(receiverContext: Context?, intent: Intent?) {
            if (intent?.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
            val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            } ?: return
            if (!device.address.equals(expectedAddress, ignoreCase = true)) return
            when (device.bondState) {
                BluetoothDevice.BOND_BONDED -> finishBond(receiverContext, null)
                BluetoothDevice.BOND_NONE -> finishBond(receiverContext, IOException("TuneBuds Bluetooth bonding failed"))
            }
        }

        private fun finishBond(receiverContext: Context?, error: Throwable?) {
            runCatching { (receiverContext ?: context).unregisterReceiver(this) }
            if (!continuation.isActive) return
            if (error == null) continuation.resume(Unit) else continuation.resumeWithException(error)
        }
    }

    private fun disconnectInternal(closeReadJob: Boolean = true) {
        if (closeReadJob) readJob?.cancel()
        readJob = null
        val currentSocket = socket
        socket = null
        runCatching { currentSocket?.close() }
        decoder.reset()
    }

    private fun hasConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
}

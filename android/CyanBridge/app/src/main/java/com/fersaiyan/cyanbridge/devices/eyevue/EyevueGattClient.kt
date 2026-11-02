package com.fersaiyan.cyanbridge.devices.eyevue

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.fersaiyan.cyanbridge.shared.devices.eyevue.EyevueFrame
import com.fersaiyan.cyanbridge.shared.devices.eyevue.EyevueFrameDecoder
import com.fersaiyan.cyanbridge.shared.devices.eyevue.EyevuePhotoAssembler
import com.fersaiyan.cyanbridge.shared.devices.eyevue.EyevueProtocol
import com.fersaiyan.cyanbridge.shared.devices.eyevue.JieliRcsp
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class EyevueGattState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR,
}

/** Native GATT transport for the Eyevue AA12/AA13/AA14 service. */
class EyevueGattClient(
    private val context: Context,
) {
    companion object {
        private const val TAG = "EyevueGatt"
        private val SERVICE_UUID: UUID = UUID.fromString(EyevueProtocol.SERVICE_UUID)
        private val COMMAND_WRITE_UUID: UUID = UUID.fromString(EyevueProtocol.COMMAND_WRITE_UUID)
        private val COMMAND_NOTIFY_UUID: UUID = UUID.fromString(EyevueProtocol.COMMAND_NOTIFY_UUID)
        private val PHOTO_NOTIFY_UUID: UUID = UUID.fromString(EyevueProtocol.PHOTO_NOTIFY_UUID)
        private val CCCD_UUID: UUID = UUID.fromString(EyevueProtocol.CCCD_UUID)
        private val RCSP_SERVICE_UUID: UUID = UUID.fromString(JieliRcsp.SERVICE_UUID)
        private val RCSP_WRITE_UUID: UUID = UUID.fromString(JieliRcsp.WRITE_UUID)
        private val RCSP_NOTIFY_UUID: UUID = UUID.fromString(JieliRcsp.NOTIFY_UUID)
        private const val OPERATION_TIMEOUT_MS = 10_000L
        private val ENABLE_NOTIFICATION_VALUE = byteArrayOf(0x01, 0x00)
    }

    private val operationMutex = Mutex()
    private val decoder = EyevueFrameDecoder()
    private val photoAssembler = EyevuePhotoAssembler()
    private val _state = MutableStateFlow(EyevueGattState.DISCONNECTED)
    private val _frames = MutableSharedFlow<EyevueFrame>(extraBufferCapacity = 64)
    private val _photos = MutableSharedFlow<ByteArray>(extraBufferCapacity = 1)

    val state: StateFlow<EyevueGattState> = _state.asStateFlow()
    val frames: SharedFlow<EyevueFrame> = _frames.asSharedFlow()
    val photos: SharedFlow<ByteArray> = _photos.asSharedFlow()

    @Volatile internal var photoListener: ((ByteArray) -> Unit)? = null

    /**
     * Receives every glasses-mic 0x46 payload on the GATT thread while set. Voice frames then
     * bypass [frames]: at 50 notifies per second its 64-slot buffer dropped audio.
     */
    @Volatile internal var voiceListener: ((ByteArray) -> Unit)? = null

    /** Receives every JieLi RCSP (AE02) notification while set. */
    @Volatile internal var rcspListener: ((ByteArray) -> Unit)? = null

    /** The negotiated ATT MTU (23 until the glasses accept a larger one). */
    @Volatile var mtu: Int = 23
        private set
    @Volatile internal var photoPacketListener: ((ByteArray) -> Unit)? = null

    internal fun resetPhotoStream() = photoAssembler.reset()

    @Volatile
    private var gatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var notifyCharacteristic: BluetoothGattCharacteristic? = null
    private var photoNotifyCharacteristic: BluetoothGattCharacteristic? = null
    private var rcspWriteCharacteristic: BluetoothGattCharacteristic? = null
    private var rcspNotifyCharacteristic: BluetoothGattCharacteristic? = null
    private var rcspNotificationsEnabled = false

    /**
     * True once this GATT link passed JieLi RCSP auth. The vendor SDK authenticates once per link
     * (`isDeviceAuthPass`), so later RCSP sessions on the same link skip auth. Cleared with the link.
     */
    @Volatile var rcspAuthenticated = false
        private set

    fun markRcspAuthenticated() {
        if (gatt != null) rcspAuthenticated = true
    }

    private var connectContinuation: CancellableContinuation<Unit>? = null
    private var serviceContinuation: CancellableContinuation<Unit>? = null
    private var descriptorContinuation: CancellableContinuation<Unit>? = null
    private var pendingWrite: CompletableDeferred<Boolean>? = null

    @SuppressLint("MissingPermission")
    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(
            callbackGatt: BluetoothGatt,
            status: Int,
            newState: Int,
        ) {
            if (callbackGatt !== gatt) return
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "GATT connected: ${callbackGatt.device.address} status=$status")
                connectContinuation?.let { continuation ->
                    connectContinuation = null
                    if (continuation.isActive) continuation.resume(Unit)
                }
                return
            }

            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.i(TAG, "GATT disconnected: status=$status")
                connectContinuation?.let { continuation ->
                    connectContinuation = null
                    if (continuation.isActive) {
                        continuation.resumeWithException(
                            IOException("Eyevue GATT disconnected during connect (status=$status)"),
                        )
                    }
                }
                pendingWrite?.complete(false)
                pendingWrite = null
                serviceContinuation?.let { continuation ->
                    serviceContinuation = null
                    if (continuation.isActive) {
                        continuation.resumeWithException(IOException("Eyevue GATT disconnected"))
                    }
                }
                descriptorContinuation?.let { continuation ->
                    descriptorContinuation = null
                    if (continuation.isActive) {
                        continuation.resumeWithException(IOException("Eyevue GATT disconnected"))
                    }
                }
                closeGatt()
                _state.value = EyevueGattState.DISCONNECTED
            }
        }

        override fun onServicesDiscovered(callbackGatt: BluetoothGatt, status: Int) {
            if (callbackGatt !== gatt) return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "Eyevue services discovered: ${callbackGatt.services.size}")
                resolveCharacteristics(callbackGatt)
                serviceContinuation?.let { continuation ->
                    serviceContinuation = null
                    if (continuation.isActive) continuation.resume(Unit)
                }
            } else {
                serviceContinuation?.let { continuation ->
                    serviceContinuation = null
                    if (continuation.isActive) {
                        continuation.resumeWithException(
                            IOException("Eyevue service discovery failed (status=$status)"),
                        )
                    }
                }
            }
        }

        override fun onDescriptorWrite(
            callbackGatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            if (callbackGatt !== gatt) return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                descriptorContinuation?.let { continuation ->
                    descriptorContinuation = null
                    if (continuation.isActive) continuation.resume(Unit)
                }
            } else {
                descriptorContinuation?.let { continuation ->
                    descriptorContinuation = null
                    if (continuation.isActive) {
                        continuation.resumeWithException(
                            IOException("Eyevue notification setup failed (status=$status)"),
                        )
                    }
                }
            }
        }

        override fun onMtuChanged(callbackGatt: BluetoothGatt, newMtu: Int, status: Int) {
            if (callbackGatt !== gatt) return
            if (status == BluetoothGatt.GATT_SUCCESS) mtu = newMtu
            Log.i(TAG, "Eyevue MTU ${newMtu} status=$status")
        }

        override fun onCharacteristicWrite(
            callbackGatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (callbackGatt !== gatt) return
            val write = pendingWrite ?: return
            pendingWrite = null
            write.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            callbackGatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (callbackGatt !== gatt) return
            handleNotification(characteristic, characteristic.value.copyOf())
        }

        override fun onCharacteristicChanged(
            callbackGatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (callbackGatt !== gatt) return
            handleNotification(characteristic, value.copyOf())
        }

        private fun handleNotification(
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            val voice = voiceListener
            if (voice == null && rcspListener == null) {
                val hex = value.joinToString("") { "%02X".format(it) }
                Log.d(TAG, "Eyevue notify uuid=${characteristic.uuid} len=${value.size} hex=$hex")
            }
            when (characteristic.uuid) {
                COMMAND_NOTIFY_UUID -> {
                    decoder.append(value).forEach { frame ->
                        if (voice != null && frame.commandId == EyevueProtocol.CMD_RECEIVE_VOICE_DATA) {
                            voice(frame.payload)
                        } else {
                            _frames.tryEmit(frame)
                        }
                    }
                }
                RCSP_NOTIFY_UUID -> rcspListener?.invoke(value)
                PHOTO_NOTIFY_UUID -> {
                    photoPacketListener?.invoke(value)
                    photoAssembler.append(value)?.let { image ->
                        photoListener?.invoke(image)
                        _photos.tryEmit(image)
                    }
                }
                else -> Log.w(TAG, "Eyevue notify on unexpected characteristic ${characteristic.uuid}")
            }
        }
    }

    /**
     * Connects to the glasses. With [requireVendorService] false (firmware update) the JieLi AE00
     * service alone is enough: the update loader exposes only that service.
     */
    @SuppressLint("MissingPermission")
    suspend fun connect(address: String, requireVendorService: Boolean = true): Result<Unit> {
        if (!hasConnectPermission()) {
            return Result.failure(SecurityException("BLUETOOTH_CONNECT permission is required"))
        }
        val normalizedAddress = address.trim()
        if (normalizedAddress.isBlank()) {
            return Result.failure(IllegalArgumentException("Eyevue Bluetooth address is empty"))
        }

        return try {
            operationMutex.withLock {
                if (_state.value == EyevueGattState.CONNECTED) return@withLock
                disconnect()
                _state.value = EyevueGattState.CONNECTING

                val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE)
                    as? android.bluetooth.BluetoothManager
                    ?: throw IOException("BluetoothManager is unavailable")
                val adapter = bluetoothManager.adapter
                    ?: throw IOException("BluetoothAdapter is unavailable")
                val device = adapter.getRemoteDevice(normalizedAddress)

                withTimeout(OPERATION_TIMEOUT_MS) {
                    suspendCancellableCoroutine<Unit> { continuation ->
                        connectContinuation = continuation
                        continuation.invokeOnCancellation {
                            connectContinuation = null
                            closeGatt()
                        }
                        val opened = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
                        } else {
                            @Suppress("DEPRECATION")
                            device.connectGatt(context, false, callback)
                        }
                        gatt = opened
                        if (opened == null) {
                            connectContinuation = null
                            continuation.resumeWithException(IOException("connectGatt returned null"))
                        }
                    }
                }

                val connectedGatt = gatt ?: throw IOException("Eyevue GATT object is missing")
                withTimeout(OPERATION_TIMEOUT_MS) {
                    suspendCancellableCoroutine<Unit> { continuation ->
                        serviceContinuation = continuation
                        continuation.invokeOnCancellation {
                            serviceContinuation = null
                            closeGatt()
                        }
                        if (!connectedGatt.discoverServices()) {
                            serviceContinuation = null
                            continuation.resumeWithException(IOException("discoverServices returned false"))
                        }
                    }
                }

                val rcspOnly = !requireVendorService && writeCharacteristic == null && rcspWriteCharacteristic != null
                if (!rcspOnly) {
                    val commandWrite = writeCharacteristic
                        ?: throw IOException("Eyevue AA13 write characteristic was not found")
                    val commandNotify = notifyCharacteristic
                        ?: throw IOException("Eyevue AA14 notify characteristic was not found")
                    val photoNotify = photoNotifyCharacteristic
                        ?: throw IOException("Eyevue AA15 photo characteristic was not found")

                    enableNotifications(connectedGatt, commandNotify, "AA14")
                    enableNotifications(connectedGatt, photoNotify, "AA15")
                    commandWrite.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                }
                connectedGatt.requestMtu(512)
                _state.value = EyevueGattState.CONNECTED
            }
            Result.success(Unit)
        } catch (timeout: TimeoutCancellationException) {
            closeGatt()
            _state.value = EyevueGattState.ERROR
            Result.failure(IOException("Eyevue GATT operation timed out", timeout))
        } catch (error: Throwable) {
            closeGatt()
            _state.value = EyevueGattState.ERROR
            Result.failure(error)
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun write(packet: ByteArray): Result<Unit> {
        if (!hasConnectPermission()) {
            return Result.failure(SecurityException("BLUETOOTH_CONNECT permission is required"))
        }
        if (_state.value != EyevueGattState.CONNECTED) {
            return Result.failure(IOException("Eyevue GATT is not connected"))
        }
        return operationMutex.withLock {
            val characteristic = writeCharacteristic
                ?: return@withLock Result.failure(IOException("Eyevue AA13 characteristic is unavailable"))
            writeLocked(characteristic, packet, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        }
    }

    /** True when the glasses expose the JieLi RCSP service (AE00). */
    fun hasRcsp(): Boolean = rcspWriteCharacteristic != null && rcspNotifyCharacteristic != null

    /** Turns on AE02 notifications once per connection. */
    @SuppressLint("MissingPermission")
    suspend fun enableRcsp(): Result<Unit> = runCatching {
        operationMutex.withLock {
            if (rcspNotificationsEnabled) return@withLock
            val currentGatt = gatt ?: throw IOException("Eyevue GATT is closed")
            val notify = rcspNotifyCharacteristic ?: throw IOException("The glasses have no JieLi AE00 service")
            enableNotifications(currentGatt, notify, "AE02")
            rcspNotificationsEnabled = true
        }
    }

    /** Writes [data] to AE01 in ATT-sized pieces (MTU - 3, at most 512 bytes each). */
    @SuppressLint("MissingPermission")
    suspend fun writeRcsp(data: ByteArray): Result<Unit> {
        if (_state.value != EyevueGattState.CONNECTED) return Result.failure(IOException("Eyevue GATT is not connected"))
        val characteristic = rcspWriteCharacteristic ?: return Result.failure(IOException("The glasses have no JieLi AE01 characteristic"))
        val writeType = if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }
        val piece = JieliRcsp.attWriteSize(mtu)
        var offset = 0
        while (offset < data.size) {
            val end = minOf(offset + piece, data.size)
            val result = operationMutex.withLock { writeLocked(characteristic, data.copyOfRange(offset, end), writeType) }
            if (result.isFailure) return result
            offset = end
        }
        return Result.success(Unit)
    }

    @SuppressLint("MissingPermission")
    private suspend fun writeLocked(
        characteristic: BluetoothGattCharacteristic,
        packet: ByteArray,
        writeType: Int,
    ): Result<Unit> {
        run {
            val currentGatt = gatt ?: return Result.failure(IOException("Eyevue GATT is closed"))
            try {
                characteristic.value = packet
                characteristic.writeType = writeType
                val result = CompletableDeferred<Boolean>()
                pendingWrite = result
                if (!currentGatt.writeCharacteristic(characteristic)) {
                    pendingWrite = null
                    return Result.failure(IOException("Eyevue writeCharacteristic returned false"))
                }
                return if (withTimeout(OPERATION_TIMEOUT_MS) { result.await() }) {
                    Result.success(Unit)
                } else {
                    Result.failure(IOException("Eyevue characteristic write failed"))
                }
            } catch (timeout: TimeoutCancellationException) {
                pendingWrite = null
                return Result.failure(IOException("Eyevue characteristic write timed out", timeout))
            } catch (error: Throwable) {
                pendingWrite = null
                return Result.failure(error)
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        pendingWrite?.complete(false)
        pendingWrite = null
        closeGatt()
        decoder.reset()
        photoAssembler.reset()
        _state.value = EyevueGattState.DISCONNECTED
    }

    fun isConnected(): Boolean = _state.value == EyevueGattState.CONNECTED

    @SuppressLint("MissingPermission")
    private fun closeGatt() {
        photoAssembler.reset()
        val currentGatt = gatt
        gatt = null
        writeCharacteristic = null
        notifyCharacteristic = null
        photoNotifyCharacteristic = null
        rcspWriteCharacteristic = null
        rcspNotifyCharacteristic = null
        rcspNotificationsEnabled = false
        rcspAuthenticated = false
        mtu = 23
        try {
            currentGatt?.disconnect()
            currentGatt?.close()
        } catch (_: Throwable) {
            // Permission loss during lifecycle cleanup should not escape.
        }
    }

    private fun resolveCharacteristics(currentGatt: BluetoothGatt) {
        val rcsp = currentGatt.getService(RCSP_SERVICE_UUID)
        rcspWriteCharacteristic = rcsp?.getCharacteristic(RCSP_WRITE_UUID)
        rcspNotifyCharacteristic = rcsp?.getCharacteristic(RCSP_NOTIFY_UUID)
        val service = currentGatt.getService(SERVICE_UUID)
            ?: run {
                writeCharacteristic = null
                notifyCharacteristic = null
                photoNotifyCharacteristic = null
                return
            }
        writeCharacteristic = service.getCharacteristic(COMMAND_WRITE_UUID)
        notifyCharacteristic = service.getCharacteristic(COMMAND_NOTIFY_UUID)
        photoNotifyCharacteristic = service.getCharacteristic(PHOTO_NOTIFY_UUID)
    }

    @SuppressLint("MissingPermission")
    private suspend fun enableNotifications(
        currentGatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        label: String,
    ) {
        val cccd = characteristic.getDescriptor(CCCD_UUID)
            ?: throw IOException("Eyevue $label CCCD was not found")
        withTimeout(OPERATION_TIMEOUT_MS) {
            suspendCancellableCoroutine<Unit> { continuation ->
                descriptorContinuation = continuation
                continuation.invokeOnCancellation {
                    descriptorContinuation = null
                    closeGatt()
                }
                currentGatt.setCharacteristicNotification(characteristic, true)
                cccd.value = ENABLE_NOTIFICATION_VALUE
                if (!currentGatt.writeDescriptor(cccd)) {
                    descriptorContinuation = null
                    continuation.resumeWithException(IOException("Eyevue $label descriptor write returned false"))
                }
            }
        }
    }

    private fun hasConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT,
            ) == PackageManager.PERMISSION_GRANTED
}

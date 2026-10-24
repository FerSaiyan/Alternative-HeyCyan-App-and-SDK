package com.fersaiyan.cyanbridge.shared.ble

import com.fersaiyan.cyanbridge.shared.platform.PlatformLogger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow

/**
 * Android implementation of [BleManager] that wraps the vendor SDK.
 *
 * Uses BleOperateManager for connection management, LargeDataHandler for
 * command/data transfer, and DeviceManager for device info.
 */
class AndroidBleManager(
    private val bleOperateManager: Any,  // com.oudmon.ble.base.bluetooth.BleOperateManager
    private val largeDataHandler: Any,   // com.oudmon.ble.base.communication.LargeDataHandler
    private val deviceManager: Any,      // com.oudmon.ble.base.bluetooth.DeviceManager
) : BleManager {

    private val _isBluetoothEnabled = MutableStateFlow(true)
    override val isBluetoothEnabled: StateFlow<Boolean> = _isBluetoothEnabled.asStateFlow()

    private val _connectedDeviceMac = MutableStateFlow<String?>(null)
    override val connectedDeviceMac: StateFlow<String?> = _connectedDeviceMac.asStateFlow()

    private val _connectionState = MutableStateFlow(BleConnectionState.DISCONNECTED)
    override val connectionState: Flow<BleConnectionState> = _connectionState.asStateFlow()

    private val listeners = mutableListOf<BleNotificationListener>()
    private val listenersLock = Any()

    override fun startScan(timeoutMs: Long?): Flow<BleScannedDevice> = flow {
        // Vendor SDK scanning delegates to BleOperateManager
        // This is a simplified wrapper - the actual implementation uses the vendor SDK's scan methods
        PlatformLogger.i(TAG, "Starting BLE scan (timeout=${timeoutMs}ms)")
        // TODO: Integrate with vendor SDK scan callbacks
        // For now, emit devices from the vendor SDK's scan results
    }

    override fun stopScan() {
        PlatformLogger.i(TAG, "Stopping BLE scan")
        // TODO: Call vendor SDK stop scan
    }

    override suspend fun connect(identifier: String) {
        PlatformLogger.i(TAG, "Connecting to device: $identifier")
        _connectionState.value = BleConnectionState.CONNECTING
        // TODO: Call vendor SDK connect
        _connectedDeviceMac.value = identifier
        _connectionState.value = BleConnectionState.CONNECTED
    }

    override suspend fun disconnect() {
        PlatformLogger.i(TAG, "Disconnecting from device")
        _connectionState.value = BleConnectionState.DISCONNECTING
        // TODO: Call vendor SDK disconnect
        _connectedDeviceMac.value = null
        _connectionState.value = BleConnectionState.DISCONNECTED
    }

    override suspend fun sendCommand(command: ByteArray) {
        PlatformLogger.d(TAG, "Sending command: ${command.size} bytes")
        // This maps to LargeDataHandler.getInstance().glassesControl(command)
        // TODO: Call vendor SDK glassesControl
    }

    override fun addNotificationListener(listener: BleNotificationListener) {
        synchronized(listenersLock) {
            listeners.add(listener)
        }
    }

    override fun removeNotificationListener(listener: BleNotificationListener) {
        synchronized(listenersLock) {
            listeners.remove(listener)
        }
    }

    override fun isConnected(): Boolean = _connectionState.value == BleConnectionState.CONNECTED

    override suspend fun requestBatteryLevel(): Int? {
        // TODO: Send battery request command and parse response
        return null
    }

    override suspend fun requestFirmwareVersion(): String? {
        // TODO: Send version request command and parse response
        return null
    }

    /**
     * Called by the vendor SDK when a notification is received.
     * This should be registered as a GlassesDeviceNotifyListener.
     */
    fun onVendorNotification(loadData: ByteArray) {
        synchronized(listenersLock) {
            listeners.forEach { listener ->
                try {
                    listener.onNotification("vendor_notify", loadData)
                } catch (e: Exception) {
                    PlatformLogger.e(TAG, "Error in notification listener", e)
                }
            }
        }
    }

    /**
     * Called when Bluetooth state changes.
     */
    fun onBluetoothStateChanged(enabled: Boolean) {
        _isBluetoothEnabled.value = enabled
        if (!enabled) {
            _connectedDeviceMac.value = null
            _connectionState.value = BleConnectionState.DISCONNECTED
        }
    }

    companion object {
        private const val TAG = "AndroidBleManager"
    }
}

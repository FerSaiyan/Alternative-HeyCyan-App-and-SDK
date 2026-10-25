package com.fersaiyan.cyanbridge.shared.ble

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.readBytes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import platform.CoreBluetooth.CBCentralManager
import platform.CoreBluetooth.CBCentralManagerDelegateProtocol
import platform.CoreBluetooth.CBAdvertisementDataLocalNameKey
import platform.CoreBluetooth.CBCharacteristic
import platform.CoreBluetooth.CBCharacteristicPropertyIndicate
import platform.CoreBluetooth.CBCharacteristicPropertyNotify
import platform.CoreBluetooth.CBCharacteristicPropertyWrite
import platform.CoreBluetooth.CBCharacteristicPropertyWriteWithoutResponse
import platform.CoreBluetooth.CBCharacteristicWriteWithResponse
import platform.CoreBluetooth.CBCharacteristicWriteWithoutResponse
import platform.CoreBluetooth.CBManagerStatePoweredOn
import platform.CoreBluetooth.CBPeripheral
import platform.CoreBluetooth.CBPeripheralDelegateProtocol
import platform.CoreBluetooth.CBService
import platform.CoreBluetooth.CBUUID
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSNumber
import platform.Foundation.NSUUID
import platform.darwin.NSObject
import com.fersaiyan.cyanbridge.shared.devices.BleDeviceClassifier
import com.fersaiyan.cyanbridge.shared.platform.PlatformLogger
import com.fersaiyan.cyanbridge.shared.platform.toIosNSData
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * CoreBluetooth implementation for the shared BLE contract.
 *
 * The glasses vendor UUIDs are not part of the public iOS headers, so this
 * adapter discovers all services and selects writable/notifying characteristics
 * by properties. The vendor command bytes remain in common callers, exactly as
 * they are on Android.
 */
@OptIn(ExperimentalForeignApi::class)
class IosBleManager : BleManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val peripherals = mutableMapOf<String, CBPeripheral>()
    private val notifyCharacteristics = mutableMapOf<String, CBCharacteristic>()
    private var writeCharacteristic: CBCharacteristic? = null
    private var connectedPeripheral: CBPeripheral? = null
    private val pendingCommands = mutableListOf<ByteArray>()
    private var scanJob = null as kotlinx.coroutines.Job?
    private var activeScan: ProducerScope<BleScannedDevice>? = null
    private var isScanning = false
    private var pendingConnect: kotlin.coroutines.Continuation<Unit>? = null
    private var pendingBattery: kotlin.coroutines.Continuation<Int?>? = null
    private var pendingFirmware: kotlin.coroutines.Continuation<String?>? = null
    private var characteristicsReady = CompletableDeferred<Unit>()

    private val _isBluetoothEnabled = MutableStateFlow(false)
    override val isBluetoothEnabled: StateFlow<Boolean> = _isBluetoothEnabled.asStateFlow()

    private val _connectedDeviceMac = MutableStateFlow<String?>(null)
    override val connectedDeviceMac: StateFlow<String?> = _connectedDeviceMac.asStateFlow()

    private val _connectionState = MutableStateFlow(BleConnectionState.DISCONNECTED)
    override val connectionState: Flow<BleConnectionState> = _connectionState.asStateFlow()

    private val listeners = mutableListOf<BleNotificationListener>()

    /** QCSDK bridge from the Swift host; HeyCyan sessions hand the peripheral to it. */
    var vendorBridge: VendorGlassesBridge? = null
    var attachVendorOnConnect: Boolean = false

    /** Protocols with a known command characteristic (e.g. Eyevue AA13) write only to it. */
    var preferredWriteCharacteristicUuid: String? = null
    private val _isVendorAttached = MutableStateFlow(false)
    val isVendorAttached: StateFlow<Boolean> = _isVendorAttached.asStateFlow()
    private val centralDelegate = CentralDelegate()
    private val central = CBCentralManager(
        delegate = centralDelegate,
        queue = null,
        options = null,
    )

    override fun startScan(timeoutMs: Long?): Flow<BleScannedDevice> = callbackFlow {
        val emitter = this
        activeScan?.close()
        activeScan = emitter
        isScanning = true
        if (central.state == CBManagerStatePoweredOn) {
            central.scanForPeripheralsWithServices(serviceUUIDs = null, options = null)
        }
        scanJob?.cancel()
        timeoutMs?.takeIf { it > 0L }?.let { timeout ->
            scanJob = scope.launch {
                delay(timeout)
                stopScan()
            }
        }
        awaitClose {
            if (activeScan === emitter) {
                activeScan = null
                stopScan()
            }
        }
    }

    override fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        if (isScanning) {
            central.stopScan()
            isScanning = false
        }
        val emitter = activeScan
        activeScan = null
        emitter?.close()
    }

    override suspend fun connect(identifier: String) {
        check(central.state == CBManagerStatePoweredOn) { "Bluetooth is not powered on" }
        check(pendingConnect == null) { "A BLE connection is already pending" }
        // After an app restart the saved profile identifier is known but not yet discovered.
        val peripheral = peripherals[identifier]
            ?: NSUUID(uUIDString = identifier).let { uuid ->
                central.retrievePeripheralsWithIdentifiers(listOf(uuid))
                    .filterIsInstance<CBPeripheral>()
                    .firstOrNull()
                    ?.also { peripherals[identifier] = it }
            }
            ?: throw IllegalArgumentException("iOS BLE peripheral was not discovered: $identifier")

        suspendCancellableCoroutine<Unit> { continuation ->
            pendingConnect = continuation
            _connectionState.value = BleConnectionState.CONNECTING
            central.connectPeripheral(peripheral, options = null)
            continuation.invokeOnCancellation {
                // A timed-out connect must not leave the manager stuck in "pending".
                pendingConnect = null
                _connectionState.value = BleConnectionState.DISCONNECTED
                central.cancelPeripheralConnection(peripheral)
            }
        }
    }

    override suspend fun disconnect() {
        val peripheral = connectedPeripheral ?: run {
            _connectionState.value = BleConnectionState.DISCONNECTED
            return
        }
        _connectionState.value = BleConnectionState.DISCONNECTING
        central.cancelPeripheralConnection(peripheral)
    }

    override suspend fun sendCommand(command: ByteArray) {
        if (command.isEmpty()) return
        val peripheral = connectedPeripheral
            ?: throw IllegalStateException("No iOS BLE peripheral is connected")
        val characteristic = writeCharacteristic ?: run {
            check(awaitCommandReady()) { "BLE command channel is not ready" }
            writeCharacteristic ?: throw IllegalStateException("BLE write characteristic is unavailable")
        }
        writeCommand(peripheral, characteristic, command)
    }

    override suspend fun awaitCommandReady(timeoutMs: Long): Boolean {
        if (!isConnected()) return false
        if (writeCharacteristic != null) return true
        return runCatching {
            withTimeoutOrNull(timeoutMs.coerceAtLeast(1L)) {
                characteristicsReady.await()
                true
            } ?: false
        }.getOrDefault(false)
    }

    private fun writeCommand(
        peripheral: CBPeripheral,
        characteristic: CBCharacteristic,
        command: ByteArray,
    ) {
        if (command.isEmpty()) return
        val data = command.toIosNSData()
        val writeType = if (characteristic.hasProperty(CBCharacteristicPropertyWrite)) {
            CBCharacteristicWriteWithResponse
        } else {
            CBCharacteristicWriteWithoutResponse
        }
        peripheral.writeValue(data, forCharacteristic = characteristic, type = writeType)
    }

    /** Largest single write the connected peripheral accepts (ATT MTU minus 3); 20 before connecting. */
    fun maximumWriteLength(): Int =
        connectedPeripheral?.maximumWriteValueLengthForType(CBCharacteristicWriteWithoutResponse)?.toInt()
            ?.takeIf { it > 0 } ?: 20

    /** True once notifications are enabled on the connected peripheral's [characteristicUuid]. */
    fun isNotifying(characteristicUuid: String): Boolean =
        notifyCharacteristic(characteristicUuid)?.isNotifying == true

    /** The service the discovered [characteristicUuid] belongs to, for diagnostics. */
    fun serviceUuidOf(characteristicUuid: String): String? =
        notifyCharacteristic(characteristicUuid)?.service?.UUID?.UUIDString

    private fun notifyCharacteristic(characteristicUuid: String): CBCharacteristic? {
        val wanted = BleDeviceClassifier.normalizeUuid(characteristicUuid)
        return notifyCharacteristics.values.firstOrNull { BleDeviceClassifier.normalizeUuid(it.UUID.UUIDString) == wanted }
    }

    /**
     * Peripherals with one of [serviceUuids] that are already connected to iOS (for example by
     * another app) and therefore no longer advertise; they become connectable by identifier.
     */
    fun connectedPeripherals(serviceUuids: List<String>): List<BleScannedDevice> {
        if (central.state != CBManagerStatePoweredOn) return emptyList()
        return central.retrieveConnectedPeripheralsWithServices(serviceUuids.map { CBUUID.UUIDWithString(it) })
            .filterIsInstance<CBPeripheral>()
            .map { peripheral ->
                val identifier = peripheral.identifier.UUIDString
                peripherals[identifier] = peripheral
                BleScannedDevice(identifier = identifier, name = peripheral.name, rssi = 0, advertisementData = emptyMap())
            }
    }

    override fun addNotificationListener(listener: BleNotificationListener) {
        if (!listeners.contains(listener)) listeners += listener
    }

    override fun removeNotificationListener(listener: BleNotificationListener) {
        listeners -= listener
    }

    override fun isConnected(): Boolean = _connectionState.value == BleConnectionState.CONNECTED

    override suspend fun requestBatteryLevel(): Int? {
        val characteristic = batteryCharacteristic ?: return null
        val peripheral = connectedPeripheral ?: return null
        return suspendCancellableCoroutine { continuation ->
            pendingBattery?.resume(null)
            pendingBattery = continuation
            peripheral.readValueForCharacteristic(characteristic)
            continuation.invokeOnCancellation { pendingBattery = null }
        }
    }

    override suspend fun requestFirmwareVersion(): String? {
        val characteristic = firmwareCharacteristic ?: return null
        val peripheral = connectedPeripheral ?: return null
        return suspendCancellableCoroutine { continuation ->
            pendingFirmware?.resume(null)
            pendingFirmware = continuation
            peripheral.readValueForCharacteristic(characteristic)
            continuation.invokeOnCancellation { pendingFirmware = null }
        }
    }

    private var batteryCharacteristic: CBCharacteristic? = null
    private var firmwareCharacteristic: CBCharacteristic? = null

    /** Explicit hooks used by host tests or a future Swift integration layer. */
    fun onDeviceDiscovered(identifier: String, name: String?, rssi: Int) {
        PlatformLogger.i(TAG, "Device discovered: $name ($identifier) RSSI=$rssi")
    }

    fun onNotificationReceived(characteristicId: String, data: ByteArray) {
        listeners.toList().forEach { listener ->
            runCatching { listener.onNotification(characteristicId, data) }
                .onFailure { PlatformLogger.e(TAG, "Notification listener failed", it) }
        }
    }

    fun onBluetoothStateChanged(enabled: Boolean) {
        _isBluetoothEnabled.value = enabled
        if (!enabled) {
            connectedPeripheral?.let { peripheral ->
                detachVendor(peripheral)
                central.cancelPeripheralConnection(peripheral)
            }
            pendingConnect?.resumeWithException(IllegalStateException("Bluetooth was disabled"))
            pendingBattery?.resume(null)
            pendingFirmware?.resume(null)
            pendingConnect = null
            pendingBattery = null
            pendingFirmware = null
            connectedPeripheral = null
            writeCharacteristic = null
            characteristicsReady.cancel()
            notifyCharacteristics.clear()
            batteryCharacteristic = null
            firmwareCharacteristic = null
            pendingCommands.clear()
            _connectedDeviceMac.value = null
            _connectionState.value = BleConnectionState.DISCONNECTED
        }
    }

    private fun detachVendor(peripheral: CBPeripheral) {
        if (_isVendorAttached.value) {
            vendorBridge?.detach(peripheral)
            _isVendorAttached.value = false
        }
    }

    private inner class CentralDelegate : NSObject(), CBCentralManagerDelegateProtocol, CBPeripheralDelegateProtocol {
        override fun centralManagerDidUpdateState(central: CBCentralManager) {
            val enabled = central.state == CBManagerStatePoweredOn
            onBluetoothStateChanged(enabled)
            PlatformLogger.i(TAG, "Bluetooth state: ${central.state}")
            if (!enabled) stopScan()
            if (enabled && isScanning) {
                central.scanForPeripheralsWithServices(serviceUUIDs = null, options = null)
            }
        }

        override fun centralManager(
            central: CBCentralManager,
            didDiscoverPeripheral: CBPeripheral,
            advertisementData: Map<Any?, *>,
            RSSI: NSNumber,
        ) {
            val identifier = didDiscoverPeripheral.identifier.UUIDString
            peripherals[identifier] = didDiscoverPeripheral
            val name = didDiscoverPeripheral.name
                ?: advertisementData[CBAdvertisementDataLocalNameKey]?.toString()
            val data = buildMap<String, Any> {
                advertisementData.forEach { (key, value) ->
                    val stringKey = key?.toString()
                    if (stringKey != null && value != null) put(stringKey, value)
                }
            }
            val device = BleScannedDevice(
                identifier = identifier,
                name = name,
                rssi = RSSI.intValue,
                advertisementData = data,
            )
            onDeviceDiscovered(identifier, name, RSSI.intValue)
            activeScan?.trySend(device)
        }

        override fun centralManager(central: CBCentralManager, didConnectPeripheral: CBPeripheral) {
            connectedPeripheral = didConnectPeripheral
            writeCharacteristic = null
            characteristicsReady = CompletableDeferred()
            _connectedDeviceMac.value = didConnectPeripheral.identifier.UUIDString
            val bridge = vendorBridge?.takeIf { attachVendorOnConnect }
            if (bridge == null) {
                completeGenericConnection(didConnectPeripheral)
                return
            }
            // QCSDK installs its own peripheral delegate, so the generic discovery is skipped.
            bridge.attach(didConnectPeripheral) { success ->
                scope.launch {
                    if (connectedPeripheral !== didConnectPeripheral) return@launch
                    if (success) {
                        _isVendorAttached.value = true
                        _connectionState.value = BleConnectionState.CONNECTED
                        pendingConnect?.resume(Unit)
                        pendingConnect = null
                        PlatformLogger.i(TAG, "QCSDK attached to ${didConnectPeripheral.name ?: didConnectPeripheral.identifier.UUIDString}")
                    } else {
                        PlatformLogger.w(TAG, "QCSDK could not attach; using the generic BLE path")
                        completeGenericConnection(didConnectPeripheral)
                    }
                }
            }
        }

        fun completeGenericConnection(peripheral: CBPeripheral) {
            peripheral.delegate = this
            _connectionState.value = BleConnectionState.CONNECTED
            pendingConnect?.resume(Unit)
            pendingConnect = null
            peripheral.discoverServices(null)
            PlatformLogger.i(TAG, "Connected to ${peripheral.name ?: peripheral.identifier.UUIDString}")
        }

        @ObjCSignatureOverride
        override fun centralManager(
            central: CBCentralManager,
            didFailToConnectPeripheral: CBPeripheral,
            error: NSError?,
        ) {
            characteristicsReady.cancel()
            _connectionState.value = BleConnectionState.DISCONNECTED
            pendingConnect?.resumeWithException(
                IllegalStateException(error?.localizedDescription ?: "iOS BLE connection failed"),
            )
            pendingConnect = null
        }

        @ObjCSignatureOverride
        override fun centralManager(
            central: CBCentralManager,
            didDisconnectPeripheral: CBPeripheral,
            error: NSError?,
        ) {
            if (connectedPeripheral?.identifier?.UUIDString == didDisconnectPeripheral.identifier.UUIDString) {
                detachVendor(didDisconnectPeripheral)
                connectedPeripheral = null
                writeCharacteristic = null
                characteristicsReady.cancel()
                pendingCommands.clear()
                notifyCharacteristics.clear()
                batteryCharacteristic = null
                firmwareCharacteristic = null
                _connectedDeviceMac.value = null
                _connectionState.value = BleConnectionState.DISCONNECTED
            }
            pendingConnect?.resumeWithException(
                IllegalStateException(error?.localizedDescription ?: "iOS BLE peripheral disconnected"),
            )
            pendingConnect = null
            PlatformLogger.i(TAG, "Disconnected from ${didDisconnectPeripheral.identifier.UUIDString}")
        }

        override fun peripheral(peripheral: CBPeripheral, didDiscoverServices: NSError?) {
            didDiscoverServices?.let {
                PlatformLogger.e(TAG, "Service discovery failed: ${it.localizedDescription}")
                characteristicsReady.cancel()
                return
            }
            peripheral.services.orEmpty().filterIsInstance<CBService>().forEach { service ->
                peripheral.discoverCharacteristics(null, forService = service)
            }
        }

        @ObjCSignatureOverride
        override fun peripheral(
            peripheral: CBPeripheral,
            didDiscoverCharacteristicsForService: CBService,
            error: NSError?,
        ) {
            error?.let {
                PlatformLogger.e(TAG, "Characteristic discovery failed: ${it.localizedDescription}")
                characteristicsReady.cancel()
                return
            }
            didDiscoverCharacteristicsForService.characteristics.orEmpty()
                .filterIsInstance<CBCharacteristic>()
                .forEach { characteristic ->
                if (characteristic.hasProperty(CBCharacteristicPropertyNotify) ||
                    characteristic.hasProperty(CBCharacteristicPropertyIndicate)
                ) {
                    notifyCharacteristics[characteristic.UUID.UUIDString] = characteristic
                    peripheral.setNotifyValue(true, forCharacteristic = characteristic)
                }
                val writable = characteristic.hasProperty(CBCharacteristicPropertyWrite) ||
                    characteristic.hasProperty(CBCharacteristicPropertyWriteWithoutResponse)
                val preferred = preferredWriteCharacteristicUuid
                if (writable && if (preferred == null) {
                        writeCharacteristic == null
                    } else {
                        BleDeviceClassifier.normalizeUuid(characteristic.UUID.UUIDString) ==
                            BleDeviceClassifier.normalizeUuid(preferred)
                    }
                ) {
                    writeCharacteristic = characteristic
                }
                when (characteristic.UUID.UUIDString.uppercase()) {
                    BATTERY_CHARACTERISTIC_UUID -> batteryCharacteristic = characteristic
                    FIRMWARE_CHARACTERISTIC_UUID -> firmwareCharacteristic = characteristic
                }
                }
            PlatformLogger.i(
                TAG,
                "Characteristics ready: write=${writeCharacteristic?.UUID?.UUIDString}, " +
                    "notify=${notifyCharacteristics.keys.joinToString()}",
            )
            val queued = pendingCommands.toList()
            pendingCommands.clear()
            val writable = writeCharacteristic
            if (writable != null) {
                characteristicsReady.complete(Unit)
                queued.forEach { command -> writeCommand(peripheral, writable, command) }
            }
        }

        @ObjCSignatureOverride
        override fun peripheral(
            peripheral: CBPeripheral,
            didUpdateValueForCharacteristic: CBCharacteristic,
            error: NSError?,
        ) {
            error?.let {
                pendingBattery?.resume(null)
                pendingBattery = null
                pendingFirmware?.resume(null)
                pendingFirmware = null
                PlatformLogger.e(TAG, "Characteristic read/notify failed: ${it.localizedDescription}")
                return
            }
            val data = didUpdateValueForCharacteristic.value?.toByteArray() ?: ByteArray(0)
            val id = didUpdateValueForCharacteristic.UUID.UUIDString
            onNotificationReceived(id, data)
            when (id.uppercase()) {
                BATTERY_CHARACTERISTIC_UUID -> {
                    pendingBattery?.resume(data.firstOrNull()?.toInt()?.and(0xFF))
                    pendingBattery = null
                }
                FIRMWARE_CHARACTERISTIC_UUID -> {
                    pendingFirmware?.resume(data.decodeToString())
                    pendingFirmware = null
                }
            }
        }
    }

    companion object {
        private const val TAG = "IosBleManager"
        private const val BATTERY_CHARACTERISTIC_UUID = "2A19"
        private const val FIRMWARE_CHARACTERISTIC_UUID = "2A26"
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val pointer = bytes ?: return ByteArray(0)
    return pointer.readBytes(length.toInt())
}

private fun CBCharacteristic.hasProperty(property: ULong): Boolean =
    (properties and property) != 0UL

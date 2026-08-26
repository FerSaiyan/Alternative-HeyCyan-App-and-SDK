package com.fersaiyan.cyanbridge.ui

import com.fersaiyan.cyanbridge.shared.devices.DeviceProfile
import com.fersaiyan.cyanbridge.shared.devices.ScannedDevice as SharedScannedDevice

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanResult
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.fersaiyan.cyanbridge.shared.devices.DeviceClass
import com.fersaiyan.cyanbridge.devices.DeviceClassifier
import com.fersaiyan.cyanbridge.devices.DeviceProfileStore
import com.fersaiyan.cyanbridge.devices.eyevue.EyevueManager
import com.fersaiyan.cyanbridge.devices.metarayban.MetaRaybanManager
import com.fersaiyan.cyanbridge.devices.meizumyvu.MeizuMyvuManager
import com.fersaiyan.cyanbridge.devices.tunebuds.TuneBudsManager
import com.fersaiyan.cyanbridge.devices.tunebuds.TuneBudsProtocol
import com.fersaiyan.cyanbridge.devices.ScannedDevice
import com.fersaiyan.cyanbridge.ui.appearance.AppearancePreferences
import com.fersaiyan.cyanbridge.ui.appearance.rememberAppearanceSettings
import com.fersaiyan.cyanbridge.shared.ui.DeviceBindScreen
import com.fersaiyan.cyanbridge.ui.theme.CyanBridgeTheme
import com.hjq.permissions.OnPermissionCallback
import com.hjq.permissions.XXPermissions
import com.oudmon.ble.base.bluetooth.BleOperateManager
import com.oudmon.ble.base.communication.LargeDataHandler
import com.oudmon.ble.base.communication.utils.ByteUtil
import com.oudmon.ble.base.scan.BleScannerHelper
import com.oudmon.ble.base.scan.ScanRecord
import com.oudmon.ble.base.scan.ScanWrapperCallback
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode

class DeviceBindActivity : BaseActivity() {
    private var scanSize = 0
    private val scanTimeout = ScanTimeout()
    private val handler = Handler(Looper.getMainLooper())
    private val deviceList = mutableListOf<ScannedDevice>()
    private val bleScanCallback = BleCallback()

    private var scannedDevices by mutableStateOf<List<ScannedDevice>>(emptyList())
    private var isScanning by mutableStateOf(false)
    private var connectingDevice by mutableStateOf<ScannedDevice?>(null)
    private var selectedDeviceClass by mutableStateOf(DeviceClass.HEY_CYAN)
    private var initialScanStarted = false
    private var lastDeviceListPublishAtMs = 0L
    private var protocolDetectionActive = false
    private var protocolDetectionJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EventBus.getDefault().register(this)
        val appearancePreferences = AppearancePreferences(this)
        setContent {
            val appearance by rememberAppearanceSettings(appearancePreferences)
            CyanBridgeTheme(appearance) {
                DeviceBindScreen(
                    devices = scannedDevices.map { it.toShared() },
                    isScanning = isScanning,
                    connectingDevice = connectingDevice?.toShared(),
                    selectedClass = selectedDeviceClass,
                    onScan = ::startScan,
                    onSelectDevice = { sharedDevice ->
                        val device = deviceList.firstOrNull {
                            it.macAddress.equals(sharedDevice.macAddress, ignoreCase = true)
                        }
                        if (device != null) {
                            connectingDevice = device
                            selectedDeviceClass = pairingChoiceFor(device.effectiveSelectedClass())
                        }
                    },
                    onSelectedClassChange = { selectedDeviceClass = it },
                    onConfirmConnection = ::confirmConnection,
                    onDismissConnection = { connectingDevice = null },
                    onBack = ::finish,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!initialScanStarted) {
            initialScanStarted = true
            startScan()
        }
    }

    // BaseActivity invokes this after Compose installs its host view; no ViewBinding remains.
    override fun setupViews() = Unit

    /** Meta wearables are registered through DAT, never through the Oudmon Bluetooth connector. */
    private fun openMetaPairing() {
        stopScan()
        DeviceProfileStore.saveLastSelected(
            this,
            DeviceProfile(
                macAddress = META_DAT_PROFILE_ID,
                advertisedName = "Meta glasses",
                detectedClass = DeviceClass.META_RAYBAN,
                selectedClass = DeviceClass.META_RAYBAN,
                userOverridden = false,
            ),
        )
        startActivity(Intent(this, MetaPairingActivity::class.java))
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onMessageEvent(messageEvent: BluetoothEvent) {
        Log.i(TAG, "onMessageEvent: ${messageEvent.connect}")
        // During protocol detection the connection event is only the first half of the HeyCyan
        // probe; wait for an actual command response before deciding which dashboard to use.
        if (messageEvent.connect && !protocolDetectionActive) finish()
    }

    private fun startScan() {
        handler.removeCallbacks(scanTimeout)
        deviceList.clear()
        scannedDevices = emptyList()
        lastDeviceListPublishAtMs = 0L
        if (!hasBluetooth(this)) {
            isScanning = false
            requestBluetoothPermission(this, PermissionCallback())
            return
        }
        BleScannerHelper.getInstance().reSetCallback()
        if (!BluetoothUtils.isEnabledBluetooth(this)) {
            startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQUEST_ENABLE_BLUETOOTH)
            return
        }
        scanSize = 0
        isScanning = true
        BleScannerHelper.getInstance().scanDevice(this, null, bleScanCallback)
        handler.postDelayed(scanTimeout, 15_000)
    }

    private fun stopScan() {
        handler.removeCallbacks(scanTimeout)
        BleScannerHelper.getInstance().stopScan(this)
        isScanning = false
    }

    private fun confirmConnection() {
        val device = connectingDevice ?: return
        if (!hasBluetooth(this)) {
            Toast.makeText(this, "Bluetooth permission is required to connect", Toast.LENGTH_SHORT).show()
            requestBluetoothPermission(this, PermissionCallback())
            return
        }

        connectingDevice = null
        stopScan()
        AutoPairManager.setAutoReconnectSuppressed(false, reason = "user_manual_pair")

        when (selectedDeviceClass) {
            DeviceClass.META_RAYBAN -> {
                // Selecting Meta in the normal scan flow now routes directly into DAT pairing.
                openMetaPairing()
            }

            DeviceClass.MEIZU_MYVU -> {
                saveSelectedProfile(device, DeviceClass.MEIZU_MYVU, userOverridden = true)
                connectMeizuMyvu(device)
            }

            DeviceClass.GENERIC_AUDIO -> {
                saveSelectedProfile(device, DeviceClass.GENERIC_AUDIO, userOverridden = true)
                Toast.makeText(this, "Audio device selected.", Toast.LENGTH_SHORT).show()
                finish()
            }

            DeviceClass.HEY_CYAN,
            DeviceClass.EYEVUE,
            DeviceClass.TUNEBUDS,
            DeviceClass.UNKNOWN,
            -> detectAndConnectConsumerGlasses(device)
        }
    }

    /**
     * The UI deliberately exposes one HeyCyan / EyeVue / TuneBuds choice. We use scan metadata as
     * a probe-order hint, but only persist the concrete protocol after that protocol responds to
     * its own battery/storage/version-style request.
     */
    private fun detectAndConnectConsumerGlasses(device: ScannedDevice) {
        if (protocolDetectionJob?.isActive == true) return
        protocolDetectionActive = true
        Toast.makeText(this, "Detecting glasses protocol…", Toast.LENGTH_SHORT).show()
        protocolDetectionJob = lifecycleScope.launch {
            val detectedClass = detectConsumerProtocol(device)
            if (detectedClass == null) {
                protocolDetectionActive = false
                Toast.makeText(
                    this@DeviceBindActivity,
                    "Could not identify a compatible glasses protocol. Check that the glasses are available and try again.",
                    Toast.LENGTH_LONG,
                ).show()
                return@launch
            }

            saveSelectedProfile(device, detectedClass, userOverridden = false)
            applyMaximumCaptureDefaults(detectedClass)
            protocolDetectionActive = false
            Toast.makeText(
                this@DeviceBindActivity,
                "Connected as ${detectedClass.displayName()}",
                Toast.LENGTH_SHORT,
            ).show()
            finish()
        }
    }

    private suspend fun detectConsumerProtocol(device: ScannedDevice): DeviceClass? {
        val order = protocolProbeOrder(device.detectedClass)
        Log.i(TAG, "Consumer protocol probe order=$order scanHint=${device.detectedClass}")
        for (candidate in order) {
            val responded = when (candidate) {
                DeviceClass.EYEVUE -> probeEyevue(device)
                DeviceClass.TUNEBUDS -> probeTuneBuds(device)
                DeviceClass.HEY_CYAN -> probeHeyCyan(device)
                else -> false
            }
            if (responded) {
                Log.i(TAG, "Consumer glasses protocol confirmed: $candidate")
                return candidate
            }
            Log.i(TAG, "Consumer glasses protocol did not respond: $candidate")
        }
        return null
    }

    private fun protocolProbeOrder(scanHint: DeviceClass): List<DeviceClass> = when (scanHint) {
        DeviceClass.EYEVUE -> listOf(DeviceClass.EYEVUE, DeviceClass.TUNEBUDS, DeviceClass.HEY_CYAN)
        DeviceClass.TUNEBUDS -> listOf(DeviceClass.TUNEBUDS, DeviceClass.EYEVUE, DeviceClass.HEY_CYAN)
        // HeyCyan's vendor connector does not expose a clean probe-only disconnect. Its strong
        // advertising-name heuristics therefore go straight to a battery-response confirmation.
        DeviceClass.HEY_CYAN -> listOf(DeviceClass.HEY_CYAN)
        else -> listOf(DeviceClass.EYEVUE, DeviceClass.TUNEBUDS, DeviceClass.HEY_CYAN)
    }

    private suspend fun probeEyevue(device: ScannedDevice): Boolean {
        val manager = EyevueManager.getInstance(this)
        manager.disconnect()
        manager.connect(device.macAddress, device.advertisedName)
        val response = withTimeoutOrNull(EYEVUE_PROBE_TIMEOUT_MS) {
            manager.state
                .filter { state ->
                    state.batteryPercent != null ||
                        state.storageCount != null ||
                        !state.customer.isNullOrBlank() ||
                        !state.project.isNullOrBlank()
                }
                .first()
        }
        if (response == null) manager.disconnect()
        return response != null
    }

    private suspend fun probeTuneBuds(device: ScannedDevice): Boolean {
        val manager = TuneBudsManager.getInstance(this)
        manager.disconnect()
        manager.connect(device.connectionAddress, device.advertisedName)
        val response = withTimeoutOrNull(TUNEBUDS_PROBE_TIMEOUT_MS) {
            manager.state
                .filter { state ->
                    state.batteryPercent != null ||
                        !state.firmwareVersion.isNullOrBlank() ||
                        !state.model.isNullOrBlank() ||
                        state.storage != null
                }
                .first()
        }
        if (response == null) manager.disconnect()
        return response != null
    }

    private suspend fun probeHeyCyan(device: ScannedDevice): Boolean {
        val batteryResponse = CompletableDeferred<Boolean>()
        val handler = LargeDataHandler.getInstance()
        runCatching { handler.removeBatteryCallBack(HEY_CYAN_PROBE_CALLBACK) }
        handler.addBatteryCallBack(HEY_CYAN_PROBE_CALLBACK) { _, response ->
            if (response != null && !batteryResponse.isCompleted) batteryResponse.complete(true)
        }
        return try {
            BleOperateManager.getInstance().connectDirectly(device.macAddress)
            val connected = withTimeoutOrNull(HEY_CYAN_CONNECT_TIMEOUT_MS) {
                while (!BleOperateManager.getInstance().isConnected) delay(100L)
                true
            } == true
            if (!connected) return false
            handler.syncBattery()
            withTimeoutOrNull(HEY_CYAN_RESPONSE_TIMEOUT_MS) { batteryResponse.await() } == true
        } finally {
            runCatching { handler.removeBatteryCallBack(HEY_CYAN_PROBE_CALLBACK) }
        }
    }

    /**
     * Remove duration choices from the UI and choose the highest known safe value automatically.
     * TuneBuds reports video/audio limits as capabilities and has no writable duration command in
     * its documented protocol, so its normal start commands already run up to those device limits.
     */
    private suspend fun applyMaximumCaptureDefaults(deviceClass: DeviceClass) {
        when (deviceClass) {
            DeviceClass.HEY_CYAN -> {
                setHeyCyanCaptureDuration(dataType = 0x02, seconds = HEY_CYAN_MAX_VIDEO_SECONDS)
                delay(150L)
                setHeyCyanCaptureDuration(dataType = 0x06, seconds = HEY_CYAN_MAX_AUDIO_SECONDS)
            }

            DeviceClass.EYEVUE -> {
                // Decompiled EyeVue settings expose 1/3/5/7/10 minute choices; 10 min is max.
                EyevueManager.getInstance(this).setRecordingDuration(EYEVUE_MAX_RECORDING_SECONDS)
            }

            DeviceClass.TUNEBUDS -> {
                TuneBudsManager.getInstance(this).refreshStatus()
            }

            else -> Unit
        }
    }

    private fun setHeyCyanCaptureDuration(dataType: Int, seconds: Int) {
        if (!BleOperateManager.getInstance().isConnected) return
        val command = byteArrayOf(
            0x02,
            dataType.toByte(),
            0x00,
            ByteUtil.loword(seconds).toByte(),
            ByteUtil.hiword(seconds).toByte(),
        )
        LargeDataHandler.getInstance().glassesControl(command) { _, response ->
            Log.i(TAG, "Applied HeyCyan maximum capture duration type=$dataType seconds=$seconds response=${response.dataType}")
        }
    }

    private fun saveSelectedProfile(
        device: ScannedDevice,
        deviceClass: DeviceClass,
        userOverridden: Boolean,
    ) {
        device.userSelectedClass = deviceClass
        DeviceProfileStore.saveLastSelected(
            this,
            DeviceProfile(
                macAddress = device.connectionAddress,
                advertisedName = device.advertisedName,
                detectedClass = deviceClass,
                selectedClass = deviceClass,
                userOverridden = userOverridden,
            ),
        )
    }

    private fun pairingChoiceFor(detected: DeviceClass): DeviceClass = when (detected) {
        DeviceClass.HEY_CYAN,
        DeviceClass.EYEVUE,
        DeviceClass.TUNEBUDS,
        DeviceClass.UNKNOWN,
        -> DeviceClass.HEY_CYAN
        else -> detected
    }

    private fun connectMeizuMyvu(device: ScannedDevice) {
        val bonded = runCatching {
            BluetoothAdapter.getDefaultAdapter()
                ?.getRemoteDevice(device.macAddress)
                ?.bondState == BluetoothDevice.BOND_BONDED
        }.getOrDefault(false)
        if (!bonded) {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Pair MYVU in Android first")
                .setMessage(
                    "The upstream MYVU client expects a Classic Bluetooth bond before opening its RFCOMM relay. " +
                        "Pair the glasses in Android Bluetooth settings, then force-stop the official MYVU app because the glasses accept only one app connection at a time.",
                )
                .setNegativeButton("Cancel", null)
                .setNeutralButton("Bluetooth settings") { _, _ ->
                    startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                }
                .setPositiveButton("Connect anyway") { _, _ -> startMeizuMyvuConnection(device.macAddress) }
                .show()
            return
        }
        startMeizuMyvuConnection(device.macAddress)
    }

    private fun startMeizuMyvuConnection(address: String) {
        MeizuMyvuManager.getInstance(this).connect(address, this, userInitiated = true)
        Toast.makeText(
            this,
            "Connecting to Meizu MYVU. Force-stop the official MYVU app while using CyanBridge.",
            Toast.LENGTH_LONG,
        ).show()
        finish()
    }

    private fun upsertDevice(
        mac: String,
        name: String?,
        rssi: Int,
        scanRecord: ScanRecord? = null,
        manufacturerCompanyIds: Set<Int> = emptySet(),
        connectionAddress: String? = null,
    ) {
        val sanitizedName = name?.trim()?.takeIf { it.isNotEmpty() }
        val existingIndex = deviceList.indexOfFirst { it.macAddress.equals(mac, ignoreCase = true) }
        if (existingIndex >= 0) {
            val existing = deviceList[existingIndex]
            val previousName = existing.advertisedName
            val previousClass = existing.detectedClass
            existing.rssi = rssi
            connectionAddress?.let { existing.connectionAddress = it }
            if (existing.advertisedName.isNullOrBlank() && sanitizedName != null) {
                existing.advertisedName = sanitizedName
            }
            scanRecord?.serviceUuids?.takeIf { it.isNotEmpty() }?.let { existing.serviceUuids = it }
            existing.setDetectedClass(
                DeviceClassifier.guessDeviceClass(
                    existing.advertisedName,
                    existing.serviceUuids,
                    manufacturerCompanyIds,
                ),
            )
            publishDevices(
                force = previousName != existing.advertisedName || previousClass != existing.detectedClass,
            )
            return
        }
        val detectedClass = DeviceClassifier.guessDeviceClass(
            sanitizedName,
            scanRecord?.serviceUuids.orEmpty(),
            manufacturerCompanyIds,
        )
        if (sanitizedName == null && detectedClass == DeviceClass.UNKNOWN) return

        val newDevice = ScannedDevice(
            macAddress = mac,
            advertisedName = sanitizedName ?: detectedClass.displayName(),
            rssi = rssi,
            serviceUuids = scanRecord?.serviceUuids.orEmpty(),
        )
        connectionAddress?.let { newDevice.connectionAddress = it }
        DeviceProfileStore.getUserOverrideForMac(this, newDevice.connectionAddress)?.let { override ->
            if (override != newDevice.detectedClass) newDevice.userSelectedClass = override
        }
        scanSize++
        deviceList += newDevice
        publishDevices(force = true)
        if (scanSize > 30) BleScannerHelper.getInstance().stopScan(this)
    }

    /** Avoid repeatedly recreating scan rows while TalkBack is navigating them. */
    private fun publishDevices(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastDeviceListPublishAtMs < DEVICE_LIST_PUBLISH_INTERVAL_MS) return
        lastDeviceListPublishAtMs = now
        // Meta is intentionally kept in the normal scan list now. Choosing the Meta type routes
        // to MetaPairingActivity instead of attempting a direct Bluetooth connection.
        scannedDevices = deviceList.toList()
    }

    override fun onDestroy() {
        protocolDetectionJob?.cancel()
        protocolDetectionJob = null
        protocolDetectionActive = false
        handler.removeCallbacks(scanTimeout)
        if (EventBus.getDefault().isRegistered(this)) EventBus.getDefault().unregister(this)
        super.onDestroy()
    }

    @Deprecated("Deprecated in AndroidX Activity; retained for the vendor scanner flow.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_ENABLE_BLUETOOTH && BluetoothUtils.isEnabledBluetooth(this)) {
            startScan()
        }
    }

    private inner class ScanTimeout : Runnable {
        override fun run() {
            BleScannerHelper.getInstance().stopScan(this@DeviceBindActivity)
            isScanning = false
        }
    }

    private inner class PermissionCallback : OnPermissionCallback {
        override fun onGranted(permissions: MutableList<String>, all: Boolean) {
            if (all) startScan()
        }

        override fun onDenied(permissions: MutableList<String>, never: Boolean) {
            super.onDenied(permissions, never)
            Toast.makeText(
                this@DeviceBindActivity,
                "Bluetooth permission is required to find and connect to glasses",
                Toast.LENGTH_LONG,
            ).show()
            if (never) XXPermissions.startPermissionActivity(this@DeviceBindActivity, permissions)
        }
    }

    private inner class BleCallback : ScanWrapperCallback {
        override fun onStart() {
            isScanning = true
        }

        override fun onStop() {
            isScanning = false
        }

        override fun onLeScan(device: BluetoothDevice?, rssi: Int, scanRecord: ByteArray?) {
            if (!hasBluetooth(this@DeviceBindActivity)) return
            val bluetoothDevice = device ?: return
            val address = bluetoothDevice.address
            val name = runCatching { bluetoothDevice.name }.getOrNull()
            upsertDevice(address, name, rssi)
        }

        override fun onScanFailed(errorCode: Int) {
            isScanning = false
            Log.w(TAG, "Scan failed: $errorCode")
        }

        override fun onParsedData(device: BluetoothDevice?, scanRecord: ScanRecord?) {
            if (!hasBluetooth(this@DeviceBindActivity)) return
            val bluetoothDevice = device ?: return
            val address = bluetoothDevice.address
            val name = runCatching { scanRecord?.deviceName ?: bluetoothDevice.name }.getOrNull()
            val rssi = deviceList.firstOrNull { it.macAddress.equals(address, true) }?.rssi ?: 0
            val manufacturerData = scanRecord?.manufacturerSpecificData
            val companyIds = buildSet {
                if (manufacturerData != null) {
                    for (index in 0 until manufacturerData.size()) add(manufacturerData.keyAt(index))
                }
            }
            val tuneBudsData = companyIds.firstOrNull { it in TUNEBUDS_COMPANY_IDS }
                ?.let { companyId -> scanRecord?.getManufacturerSpecificData(companyId) }
            val classicAddress = tuneBudsData?.let { data ->
                runCatching { TuneBudsProtocol.deriveClassicAddress(data) }
                    .onFailure { Log.w(TAG, "Could not derive TuneBuds Classic Bluetooth address", it) }
                    .getOrNull()
            }
            upsertDevice(
                address,
                name,
                rssi,
                scanRecord,
                manufacturerCompanyIds = companyIds,
                connectionAddress = classicAddress,
            )
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>?) = Unit
    }

    private fun ScannedDevice.toShared(): SharedScannedDevice = SharedScannedDevice(
        macAddress = macAddress,
        advertisedName = advertisedName,
        rssi = rssi,
        detectedClass = detectedClass,
        selectedClass = userSelectedClass,
        userOverridden = userOverridden(),
    )

    private companion object {
        const val TAG = "DeviceBindActivity"
        const val REQUEST_ENABLE_BLUETOOTH = 300
        const val META_DAT_PROFILE_ID = "META_DAT"
        const val DEVICE_LIST_PUBLISH_INTERVAL_MS = 1_000L
        const val HEY_CYAN_PROBE_CALLBACK = "device_bind_protocol_probe"
        const val EYEVUE_PROBE_TIMEOUT_MS = 6_000L
        const val TUNEBUDS_PROBE_TIMEOUT_MS = 12_000L
        const val HEY_CYAN_CONNECT_TIMEOUT_MS = 6_000L
        const val HEY_CYAN_RESPONSE_TIMEOUT_MS = 4_000L
        const val HEY_CYAN_MAX_VIDEO_SECONDS = 720
        const val HEY_CYAN_MAX_AUDIO_SECONDS = 7_200
        const val EYEVUE_MAX_RECORDING_SECONDS = 600
        val TUNEBUDS_COMPANY_IDS = setOf(0x475A, 0x455A, 0x535A, 0x4D5A)
    }
}

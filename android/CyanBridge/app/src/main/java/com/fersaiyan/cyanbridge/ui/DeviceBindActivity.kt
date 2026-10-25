package com.fersaiyan.cyanbridge.ui

import com.fersaiyan.cyanbridge.shared.devices.tunebuds.TuneBudsProtocol
import com.fersaiyan.cyanbridge.shared.devices.BleDeviceClassifier
import com.fersaiyan.cyanbridge.shared.devices.DeviceProfile
import com.fersaiyan.cyanbridge.shared.devices.ScannedDevice as SharedScannedDevice

import android.annotation.SuppressLint
import com.fersaiyan.cyanbridge.R
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
import androidx.lifecycle.lifecycleScope
import com.fersaiyan.cyanbridge.shared.devices.DeviceClass
import com.fersaiyan.cyanbridge.devices.DeviceClassifier
import com.fersaiyan.cyanbridge.devices.DeviceProfileStore
import com.fersaiyan.cyanbridge.devices.eyevue.EyevueManager
import com.fersaiyan.cyanbridge.devices.meizumyvu.MeizuMyvuManager
import com.fersaiyan.cyanbridge.devices.memomind.MemoMindManager
import com.fersaiyan.cyanbridge.devices.moyoung.MoyoungW620Manager
import com.fersaiyan.cyanbridge.devices.tunebuds.TuneBudsManager
import com.fersaiyan.cyanbridge.devices.ScannedDevice
import com.fersaiyan.cyanbridge.ui.appearance.AppearancePreferences
import com.fersaiyan.cyanbridge.ui.appearance.rememberAppearanceSettings
import com.fersaiyan.cyanbridge.shared.ui.DeviceBindConnection
import com.fersaiyan.cyanbridge.shared.ui.DeviceBindScreen
import com.fersaiyan.cyanbridge.shared.ui.DeviceBindViewModel
import com.fersaiyan.cyanbridge.ui.theme.CyanBridgeTheme
import com.hjq.permissions.OnPermissionCallback
import com.hjq.permissions.XXPermissions
import com.oudmon.ble.base.bluetooth.BleOperateManager
import com.oudmon.ble.base.communication.LargeDataHandler
import com.oudmon.ble.base.communication.utils.ByteUtil
import com.oudmon.ble.base.scan.BleScannerHelper
import com.oudmon.ble.base.scan.ScanRecord
import com.oudmon.ble.base.scan.ScanWrapperCallback
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode

class DeviceBindActivity : BaseActivity() {
    private var scanSize = 0
    private val scanTimeout = ScanTimeout()
    private val handler = Handler(Looper.getMainLooper())
    private val deviceList = mutableListOf<ScannedDevice>()
    private val bleScanCallback = BleCallback()

    /** Not retained across recreation: the scan records behind its devices live in [deviceList]. */
    private val viewModel = DeviceBindViewModel()
    private var initialScanStarted = false
    private var lastDeviceListPublishAtMs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EventBus.getDefault().register(this)
        val appearancePreferences = AppearancePreferences(this)
        setContent {
            val appearance by rememberAppearanceSettings(appearancePreferences)
            CyanBridgeTheme(appearance) {
                DeviceBindScreen(
                    devices = viewModel.devices,
                    isScanning = viewModel.isScanning,
                    connectingDevice = viewModel.connectingDevice,
                    selectedClass = viewModel.selectedClass,
                    onScan = ::startScan,
                    onPairMetaGlasses = ::openMetaPairing,
                    onPairMentraGlasses = ::openMentraPairing,
                    onSelectDevice = { sharedDevice -> scannedDevice(sharedDevice)?.let { viewModel.selectDevice(it.toShared()) } },
                    onSelectedClassChange = viewModel::selectDeviceClass,
                    onConfirmConnection = { viewModel.confirmConnection(::canConnect)?.let(::connect) },
                    onConfirmManualProtocol = { viewModel.confirmManualProtocol(it, ::canConnect)?.let(::connect) },
                    onDismissConnection = viewModel::dismissConnection,
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

    /** Mentra has its own SDK scanner; never hand these devices to Oudmon. */
    private fun openMentraPairing() {
        stopScan()
        startActivity(Intent(this, MentraPairingActivity::class.java))
        finish()
    }

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
        if (messageEvent.connect) finish()
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        handler.removeCallbacks(scanTimeout)
        deviceList.clear()
        viewModel.onScanStarted()
        lastDeviceListPublishAtMs = 0L
        if (!hasBluetooth(this)) {
            viewModel.isScanning = false
            requestBluetoothPermission(this, PermissionCallback())
            return
        }
        BleScannerHelper.getInstance().reSetCallback()
        if (!BluetoothUtils.isEnabledBluetooth(this)) {
            startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQUEST_ENABLE_BLUETOOTH)
            return
        }
        scanSize = 0
        viewModel.isScanning = true
        addBondedMemoMindGlasses()
        BleScannerHelper.getInstance().scanDevice(this, null, bleScanCallback)
        handler.postDelayed(scanTimeout, 15_000)
    }

    private fun stopScan() {
        handler.removeCallbacks(scanTimeout)
        BleScannerHelper.getInstance().stopScan(this)
        viewModel.isScanning = false
    }

    private fun scannedDevice(device: SharedScannedDevice): ScannedDevice? =
        deviceList.firstOrNull { it.macAddress.equals(device.macAddress, ignoreCase = true) }

    private fun canConnect(): Boolean {
        if (hasBluetooth(this)) return true
        Toast.makeText(this, "Bluetooth permission is required to connect", Toast.LENGTH_SHORT).show()
        requestBluetoothPermission(this, PermissionCallback())
        return false
    }

    /**
     * Runs a pairing confirmed in [DeviceBindViewModel]. A consumer protocol (HeyCyan / EyeVue /
     * TuneBuds / MoYoung) is persisted as chosen (userOverridden = true) so re-pairing is not
     * required to change it, gets maximum capture defaults, and connects via its manager.
     */
    private fun connect(connection: DeviceBindConnection) {
        val device = scannedDevice(connection.device) ?: return
        stopScan()
        AutoPairManager.setAutoReconnectSuppressed(false, reason = "user_manual_pair")
        val deviceClass = connection.deviceClass
        if (deviceClass in DeviceBindViewModel.CONSUMER_PROTOCOLS) {
            saveSelectedProfile(device, deviceClass, userOverridden = true)
            // Apply device-specific maximum capture defaults (HeyCyan video/audio,
            // EyeVue recording duration). TuneBuds/MoYoung have no writable duration.
            lifecycleScope.launch { applyMaximumCaptureDefaults(deviceClass) }
        }
        when (deviceClass) {
            DeviceClass.HEY_CYAN -> {
                BleOperateManager.getInstance().connectDirectly(device.macAddress)
                Toast.makeText(this@DeviceBindActivity, "Connecting as HeyCyan.", Toast.LENGTH_SHORT).show()
                // HeyCyan connection completion is observed via BluetoothEvent;
                // finish now to return to dashboard where AutoPair/status will follow.
                finish()
            }

            DeviceClass.EYEVUE -> {
                EyevueManager.getInstance(this).connect(device.macAddress, device.advertisedName)
                Toast.makeText(this@DeviceBindActivity, "Connecting to EyeVue.", Toast.LENGTH_SHORT).show()
                finish()
            }

            DeviceClass.TUNEBUDS -> {
                TuneBudsManager.getInstance(this).connect(device.connectionAddress, device.advertisedName)
                Toast.makeText(this@DeviceBindActivity, "Connecting to TuneBuds.", Toast.LENGTH_SHORT).show()
                finish()
            }

            DeviceClass.MOYOUNG_W620 -> {
                MoyoungW620Manager.getInstance(this).connect(device.macAddress, device.advertisedName)
                Toast.makeText(this@DeviceBindActivity, "Connecting as MoYoung / W620.", Toast.LENGTH_SHORT).show()
                finish()
            }

            // Selecting Meta in the normal scan flow routes directly into DAT pairing.
            DeviceClass.META_RAYBAN -> openMetaPairing()

            DeviceClass.MEIZU_MYVU -> {
                saveSelectedProfile(device, DeviceClass.MEIZU_MYVU, userOverridden = true)
                connectMeizuMyvu(device)
            }

            DeviceClass.MEMOMIND -> {
                saveSelectedProfile(device, DeviceClass.MEMOMIND, userOverridden = true)
                connectMemoMind(device)
            }

            DeviceClass.GENERIC_AUDIO -> {
                saveSelectedProfile(device, DeviceClass.GENERIC_AUDIO, userOverridden = true)
                Toast.makeText(this, "Audio device selected.", Toast.LENGTH_SHORT).show()
                finish()
            }

            // Should not happen; the pairing choices only expose the classes above.
            else -> Toast.makeText(this, "Unknown device type.", Toast.LENGTH_SHORT).show()
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

            DeviceClass.MOYOUNG_W620 -> Unit

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

    @SuppressLint("MissingPermission")
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

    /**
     * MemoMind glasses already bonded in Android may not advertise over BLE while the official
     * App holds them, so bonded devices with a MemoMind name are listed too.
     */
    @SuppressLint("MissingPermission")
    private fun addBondedMemoMindGlasses() {
        val bonded = runCatching { BluetoothAdapter.getDefaultAdapter()?.bondedDevices.orEmpty() }.getOrDefault(emptySet())
        bonded.filter { BleDeviceClassifier.isMemoMindName(it.name.orEmpty().lowercase()) }
            .forEach { upsertDevice(it.address, it.name, rssi = 0) }
    }

    /** MemoMind talks Classic SPP, which needs an Android bond (the RFCOMM connect can also prompt). */
    @SuppressLint("MissingPermission")
    private fun connectMemoMind(device: ScannedDevice) {
        val bonded = runCatching {
            BluetoothAdapter.getDefaultAdapter()
                ?.getRemoteDevice(device.macAddress)
                ?.bondState == BluetoothDevice.BOND_BONDED
        }.getOrDefault(false)
        val start = {
            MemoMindManager.getInstance(this).connect(device.connectionAddress, device.advertisedName)
            Toast.makeText(this, R.string.memomind_connecting_toast, Toast.LENGTH_LONG).show()
            finish()
        }
        if (bonded) {
            start()
            return
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.memomind_pair_first_title)
            .setMessage(R.string.memomind_pair_first_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.memomind_bluetooth_settings) { _, _ ->
                startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            }
            .setPositiveButton(R.string.memomind_connect_anyway) { _, _ -> start() }
            .show()
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
                    existing.connectionAddress,
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
            connectionAddress ?: mac,
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
        viewModel.onDevicesChanged(deviceList.map { it.toShared() })
    }

    override fun onDestroy() {
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
            viewModel.isScanning = false
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
            viewModel.isScanning = true
        }

        override fun onStop() {
            viewModel.isScanning = false
        }

        @SuppressLint("MissingPermission")
        override fun onLeScan(device: BluetoothDevice?, rssi: Int, scanRecord: ByteArray?) {
            if (!hasBluetooth(this@DeviceBindActivity)) return
            val bluetoothDevice = device ?: return
            val address = bluetoothDevice.address
            val name = runCatching { bluetoothDevice.name }.getOrNull()
            upsertDevice(address, name, rssi)
        }

        override fun onScanFailed(errorCode: Int) {
            viewModel.isScanning = false
            Log.w(TAG, "Scan failed: $errorCode")
        }

        @SuppressLint("MissingPermission")
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
        const val HEY_CYAN_MAX_VIDEO_SECONDS = 720
        const val HEY_CYAN_MAX_AUDIO_SECONDS = 7_200
        const val EYEVUE_MAX_RECORDING_SECONDS = 600
        val TUNEBUDS_COMPANY_IDS = setOf(0x475A, 0x455A, 0x535A, 0x4D5A)
    }
}

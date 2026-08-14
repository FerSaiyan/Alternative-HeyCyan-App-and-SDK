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
import com.oudmon.ble.base.scan.BleScannerHelper
import com.oudmon.ble.base.scan.ScanRecord
import com.oudmon.ble.base.scan.ScanWrapperCallback
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
                    onPairMetaGlasses = ::openMetaPairing,
                    onSelectDevice = { sharedDevice ->
                        val device = deviceList.firstOrNull {
                            it.macAddress.equals(sharedDevice.macAddress, ignoreCase = true)
                        }
                        if (device != null) {
                            connectingDevice = device
                            selectedDeviceClass = device.effectiveSelectedClass().takeUnless {
                                it == DeviceClass.UNKNOWN
                            } ?: DeviceClass.HEY_CYAN
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

    private fun openMetaPairing() {
        handler.removeCallbacks(scanTimeout)
        BleScannerHelper.getInstance().stopScan(this)
        isScanning = false
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

    private fun confirmConnection() {
        val device = connectingDevice ?: return
        if (!hasBluetooth(this)) {
            Toast.makeText(this, "Bluetooth permission is required to connect", Toast.LENGTH_SHORT).show()
            requestBluetoothPermission(this, PermissionCallback())
            return
        }
        connectingDevice = null
        handler.removeCallbacks(scanTimeout)
        BleScannerHelper.getInstance().stopScan(this)
        isScanning = false

        AutoPairManager.setAutoReconnectSuppressed(false, reason = "user_manual_pair")
        device.userSelectedClass = selectedDeviceClass
        DeviceProfileStore.saveLastSelected(
            this,
            DeviceProfile(
                macAddress = device.connectionAddress,
                advertisedName = device.advertisedName,
                detectedClass = device.detectedClass,
                selectedClass = selectedDeviceClass,
                userOverridden = true,
            ),
        )

        if (selectedDeviceClass == DeviceClass.META_RAYBAN) {
            // Meta devices are owned by DAT. Saving the profile is enough here; calling
            // the Oudmon connector would make the rest of the app treat Meta as HeyCyan.
            MetaRaybanManager.getInstance(this).initialize()
            Toast.makeText(
                this,
                "Meta Ray-Ban selected. Register it from the glasses dashboard.",
                Toast.LENGTH_LONG,
            ).show()
            finish()
            return
        }

        if (selectedDeviceClass == DeviceClass.MEIZU_MYVU) {
            // MYVU owns a BLE ECDH session and an RFCOMM relay. The HeyCyan SDK
            // connector cannot establish either transport.
            connectMeizuMyvu(device)
            return
        }

        if (selectedDeviceClass == DeviceClass.EYEVUE) {
            EyevueManager.getInstance(this).connect(device.macAddress, device.advertisedName)
            Toast.makeText(
                this,
                "Connecting to Eyevue over Bluetooth.",
                Toast.LENGTH_LONG,
            ).show()
            finish()
            return
        }

        if (selectedDeviceClass == DeviceClass.TUNEBUDS) {
            TuneBudsManager.getInstance(this).connect(device.connectionAddress, device.advertisedName)
            Toast.makeText(
                this,
                "Connecting to TuneBuds over Classic Bluetooth.",
                Toast.LENGTH_LONG,
            ).show()
            finish()
            return
        }

        BleOperateManager.getInstance().connectDirectly(device.macAddress)
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
        scannedDevices = deviceList
            .filter { it.effectiveSelectedClass() != DeviceClass.META_RAYBAN }
            .toList()
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
            if (!hasBluetooth(this@DeviceBindActivity)) {
                return
            }
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
            if (!hasBluetooth(this@DeviceBindActivity)) {
                return
            }
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
        const val REQUEST_ENABLE_BLUETOOTH = 300
        const val META_DAT_PROFILE_ID = "META_DAT"
        const val DEVICE_LIST_PUBLISH_INTERVAL_MS = 1_000L
        val TUNEBUDS_COMPANY_IDS = setOf(0x475A, 0x455A, 0x535A, 0x4D5A)
    }
}

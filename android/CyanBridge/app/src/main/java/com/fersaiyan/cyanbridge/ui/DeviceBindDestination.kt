package com.fersaiyan.cyanbridge.ui

import androidx.activity.result.contract.ActivityResultContracts
import com.fersaiyan.cyanbridge.ui.nav.AppRoute
import com.fersaiyan.cyanbridge.ui.nav.NavScreen
import com.fersaiyan.cyanbridge.shared.devices.tunebuds.TuneBudsProtocol
import com.fersaiyan.cyanbridge.shared.devices.BleDeviceClassifier
import com.fersaiyan.cyanbridge.shared.devices.DeviceProfile
import com.fersaiyan.cyanbridge.shared.devices.ScannedDevice as SharedScannedDevice
import com.fersaiyan.cyanbridge.shared.devices.mentra.Ar99Advertisement
import com.fersaiyan.cyanbridge.shared.devices.mentra.MentraSdkModel

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
import androidx.lifecycle.lifecycleScope
import com.fersaiyan.cyanbridge.shared.devices.DeviceClass
import com.fersaiyan.cyanbridge.devices.DeviceClassifier
import com.fersaiyan.cyanbridge.devices.DeviceProfileStore
import com.fersaiyan.cyanbridge.devices.eyevue.EyevueManager
import com.fersaiyan.cyanbridge.devices.mentra.MentraLiveManager
import com.fersaiyan.cyanbridge.devices.meizumyvu.MeizuMyvuManager
import com.fersaiyan.cyanbridge.devices.memomind.MemoMindManager
import com.fersaiyan.cyanbridge.devices.moyoung.MoyoungW620Manager
import com.fersaiyan.cyanbridge.devices.tunebuds.TuneBudsManager
import com.fersaiyan.cyanbridge.devices.ScannedDevice
import com.fersaiyan.cyanbridge.shared.ui.DeviceBindConnection
import com.fersaiyan.cyanbridge.shared.ui.DeviceBindScreen
import com.fersaiyan.cyanbridge.shared.ui.DeviceBindViewModel
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

class DeviceBindDestination : NavScreen() {
    /** The system "turn on Bluetooth" request (was onActivityResult with REQUEST_ENABLE_BLUETOOTH). */
    private val enableBluetooth = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (BluetoothUtils.isEnabledBluetooth(this)) startScan()
    }
    private var scanSize = 0
    private val scanTimeout = ScanTimeout()
    private val handler = Handler(Looper.getMainLooper())
    private val deviceList = mutableListOf<ScannedDevice>()
    private val bleScanCallback = BleCallback()

    /** Not retained across recreation: the scan records behind its devices live in [deviceList]. */
    private val viewModel = DeviceBindViewModel()

    /** Addresses the Mentra SDK scanner reported; their rows are Mentra Live whatever their name. */
    private val mentraSdkAddresses = mutableSetOf<String>()

    /** AR99 advertisements by row address; only the exact "AR99" project ID gets here. */
    private val ar99Advertisements = mutableMapOf<String, Ar99Advertisement>()
    private var mentraScanStarted = false
    private var initialScanStarted = false
    private var lastDeviceListPublishAtMs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EventBus.getDefault().register(this)
        setContent {
            DeviceBindScreen(
                devices = viewModel.devices,
                isScanning = viewModel.isScanning,
                connectingDevice = viewModel.connectingDevice,
                selectedClass = viewModel.selectedClass,
                onScan = ::startScan,
                onPairMetaGlasses = ::openMetaPairing,
                onSelectDevice = { sharedDevice -> scannedDevice(sharedDevice)?.let { viewModel.selectDevice(it.toShared()) } },
                onSelectedClassChange = viewModel::selectDeviceClass,
                onConfirmConnection = { viewModel.confirmConnection(::canConnect)?.let(::connect) },
                onConfirmManualProtocol = { viewModel.confirmManualProtocol(it, ::canConnect)?.let(::connect) },
                onDismissConnection = viewModel::dismissConnection,
                onBack = ::finish,
            )
        }
    }

    override fun onResume() {
        super.onResume()
        if (!initialScanStarted) {
            initialScanStarted = true
            startScan()
        }
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
        startActivity(AppRoute.META_PAIRING.intent(this))
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
            requestBluetoothPermission(activity, PermissionCallback())
            return
        }
        BleScannerHelper.getInstance().reSetCallback()
        if (!BluetoothUtils.isEnabledBluetooth(this)) {
            enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }
        scanSize = 0
        viewModel.isScanning = true
        addBondedMemoMindGlasses()
        BleScannerHelper.getInstance().scanDevice(this, null, bleScanCallback)
        handler.postDelayed(scanTimeout, 15_000)
        mentraSdkAddresses.clear()
        ar99Advertisements.clear()
        mentraScanStarted = false
        // An existing Mentra SDK means Mentra glasses were used before: scan for Live right away,
        // unless that would drop the SDK's link to display glasses (one model at a time).
        if (MentraLiveManager.instanceOrNull() != null) startMentraScan()
    }

    private fun stopScan() {
        handler.removeCallbacks(scanTimeout)
        BleScannerHelper.getInstance().stopScan(this)
        MentraLiveManager.instanceOrNull()?.stopScan()
        viewModel.isScanning = false
    }

    /**
     * Adds the Mentra Bluetooth SDK scanner to this scan, so Mentra Live rows carry the SDK's own
     * device records. Started only once Mentra Live shows up (or was used before), because
     * creating the SDK starts its foreground service.
     */
    private fun startMentraScan() {
        if (mentraScanStarted) return
        if (MentraLiveManager.instanceOrNull()?.scanWouldDropLink(MentraSdkModel.LIVE) == true) return
        mentraScanStarted = true
        runCatching {
            MentraLiveManager.getInstance(this).startScan { devices ->
                runOnUiThread {
                    devices.forEach { device ->
                        val address = device.address?.takeIf { it.isNotBlank() } ?: device.id
                        mentraSdkAddresses += address.uppercase()
                        upsertDevice(address, device.name, device.rssi ?: 0)
                    }
                    publishDevices(force = true)
                }
            }
        }.onFailure { Log.w(TAG, "Mentra SDK scan did not start: ${it.message}") }
    }

    private fun scannedDevice(device: SharedScannedDevice): ScannedDevice? =
        deviceList.firstOrNull { it.macAddress.equals(device.macAddress, ignoreCase = true) }

    private fun canConnect(): Boolean {
        if (hasBluetooth(this)) return true
        Toast.makeText(this, "Bluetooth permission is required to connect", Toast.LENGTH_SHORT).show()
        requestBluetoothPermission(activity, PermissionCallback())
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
        if (!MentraSdkModel.usesMentraSdk(deviceClass)) MentraLiveManager.releaseIfStarted("selected $deviceClass")
        if (deviceClass in DeviceBindViewModel.CONSUMER_PROTOCOLS) {
            saveSelectedProfile(device, deviceClass, userOverridden = true)
            // Apply device-specific maximum capture defaults (HeyCyan video/audio,
            // EyeVue recording duration). TuneBuds/MoYoung have no writable duration.
            lifecycleScope.launch { applyMaximumCaptureDefaults(deviceClass) }
        }
        when (deviceClass) {
            DeviceClass.HEY_CYAN -> {
                BleOperateManager.getInstance().connectDirectly(device.macAddress)
                Toast.makeText(this@DeviceBindDestination, "Connecting as HeyCyan.", Toast.LENGTH_SHORT).show()
                // HeyCyan connection completion is observed via BluetoothEvent;
                // finish now to return to dashboard where AutoPair/status will follow.
                finish()
            }

            DeviceClass.EYEVUE -> {
                EyevueManager.getInstance(this).connect(device.macAddress, device.advertisedName)
                Toast.makeText(this@DeviceBindDestination, "Connecting to EyeVue.", Toast.LENGTH_SHORT).show()
                finish()
            }

            DeviceClass.TUNEBUDS -> {
                TuneBudsManager.getInstance(this).connect(device.connectionAddress, device.advertisedName)
                Toast.makeText(this@DeviceBindDestination, "Connecting to TuneBuds.", Toast.LENGTH_SHORT).show()
                finish()
            }

            DeviceClass.MOYOUNG_W620 -> {
                MoyoungW620Manager.getInstance(this).connect(device.macAddress, device.advertisedName)
                Toast.makeText(this@DeviceBindDestination, "Connecting as MoYoung / W620.", Toast.LENGTH_SHORT).show()
                finish()
            }

            // Selecting Meta in the normal scan flow routes directly into DAT pairing.
            DeviceClass.META_RAYBAN -> openMetaPairing()

            // The Mentra SDK bonds, connects and saves the glasses as its default device.
            DeviceClass.MENTRA_LIVE -> {
                saveSelectedProfile(device, DeviceClass.MENTRA_LIVE, userOverridden = true)
                runCatching { MentraLiveManager.getInstance(this).connect(device.connectionAddress, device.advertisedName) }
                    .onSuccess {
                        Toast.makeText(this, R.string.mentra_connecting, Toast.LENGTH_SHORT).show()
                        finish()
                    }
                    .onFailure { error ->
                        Log.w(TAG, "Mentra Live connect failed", error)
                        Toast.makeText(
                            this,
                            getString(R.string.mentra_connect_failed, error.message.orEmpty()),
                            Toast.LENGTH_LONG,
                        ).show()
                    }
            }

            DeviceClass.EVEN_G1,
            DeviceClass.EVEN_G2,
            DeviceClass.MENTRA_DISPLAY,
            DeviceClass.NIMO,
            DeviceClass.XINGYI_AR99,
            -> connectMentraDisplay(device, deviceClass)

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
     * Display glasses pair through the Mentra SDK: it scans for the chosen model, connects the
     * row's glasses (both arms of G1/G2) and saves them as its default. AR99 is matched by the
     * address in its advertisement and refused without the exact project ID.
     */
    private fun connectMentraDisplay(device: ScannedDevice, deviceClass: DeviceClass) {
        val model = MentraSdkModel.forClass(deviceClass) ?: return
        val name = deviceClass.displayName()
        val address = if (model == MentraSdkModel.AR99) {
            ar99Advertisements[device.macAddress.uppercase()]?.bleAddress ?: run {
                Log.w(TAG, "AR99 refused: no AR99 project ID for ${device.macAddress}")
                Toast.makeText(this, getString(R.string.mentra_display_not_found, name), Toast.LENGTH_LONG).show()
                return
            }
        } else {
            device.connectionAddress
        }
        saveSelectedProfile(device, deviceClass, userOverridden = true)
        val appContext = applicationContext
        runCatching {
            MentraLiveManager.getInstance(this).connectDisplayGlasses(model, device.advertisedName, address) { failure ->
                val message = when (failure) {
                    MentraLiveManager.DisplayConnectFailure.SEVERAL_FOUND -> R.string.mentra_display_several_found
                    else -> R.string.mentra_display_not_found
                }
                Toast.makeText(appContext, appContext.getString(message, name), Toast.LENGTH_LONG).show()
            }
        }.onSuccess {
            Toast.makeText(this, getString(R.string.mentra_display_connecting, name), Toast.LENGTH_SHORT).show()
            finish()
        }.onFailure { error ->
            Log.w(TAG, "$name connect failed", error)
            Toast.makeText(this, getString(R.string.mentra_display_connect_failed, name, error.message.orEmpty()), Toast.LENGTH_LONG).show()
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
        // G1/G2: the SDK connects both arms from the left one, so the right arm gets no row.
        if (MentraSdkModel.isSecondaryArm(sanitizedName)) return
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
        val isAr99 = mac.uppercase() in ar99Advertisements
        if (sanitizedName == null && detectedClass == DeviceClass.UNKNOWN && !isAr99) return

        val newDevice = ScannedDevice(
            macAddress = mac,
            advertisedName = sanitizedName ?: (if (isAr99) DeviceClass.XINGYI_AR99 else detectedClass).displayName(),
            rssi = rssi,
            serviceUuids = scanRecord?.serviceUuids.orEmpty(),
        )
        connectionAddress?.let { newDevice.connectionAddress = it }
        DeviceProfileStore.getUserOverrideForMac(this, newDevice.connectionAddress)?.let { override ->
            if (override != newDevice.detectedClass) newDevice.userSelectedClass = override
        }
        scanSize++
        deviceList += newDevice
        if (newDevice.detectedClass == DeviceClass.MENTRA_LIVE) startMentraScan()
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
        MentraLiveManager.instanceOrNull()?.stopScan()
        if (EventBus.getDefault().isRegistered(this)) EventBus.getDefault().unregister(this)
        super.onDestroy()
    }



    private inner class ScanTimeout : Runnable {
        override fun run() {
            BleScannerHelper.getInstance().stopScan(this@DeviceBindDestination)
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
                this@DeviceBindDestination,
                "Bluetooth permission is required to find and connect to glasses",
                Toast.LENGTH_LONG,
            ).show()
            if (never) XXPermissions.startPermissionActivity(this@DeviceBindDestination, permissions)
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
            if (!hasBluetooth(this@DeviceBindDestination)) return
            val bluetoothDevice = device ?: return
            val address = bluetoothDevice.address
            val name = runCatching { bluetoothDevice.name }.getOrNull()
            Ar99Advertisement.parse(scanRecord)?.let { ar99Advertisements[address.uppercase()] = it }
            upsertDevice(address, name, rssi)
        }

        override fun onScanFailed(errorCode: Int) {
            viewModel.isScanning = false
            Log.w(TAG, "Scan failed: $errorCode")
        }

        @SuppressLint("MissingPermission")
        override fun onParsedData(device: BluetoothDevice?, scanRecord: ScanRecord?) {
            if (!hasBluetooth(this@DeviceBindDestination)) return
            val bluetoothDevice = device ?: return
            val address = bluetoothDevice.address
            val name = runCatching { scanRecord?.deviceName ?: bluetoothDevice.name }.getOrNull()
            Ar99Advertisement.parse(scanRecord?.bytes)?.let { ar99Advertisements[address.uppercase()] = it }
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
        detectedClass = when (macAddress.uppercase()) {
            in mentraSdkAddresses -> DeviceClass.MENTRA_LIVE
            in ar99Advertisements -> DeviceClass.XINGYI_AR99
            else -> detectedClass
        },
        selectedClass = userSelectedClass,
        userOverridden = userOverridden(),
    )

    private companion object {
        const val TAG = "DeviceBindActivity"
        const val META_DAT_PROFILE_ID = "META_DAT"
        const val DEVICE_LIST_PUBLISH_INTERVAL_MS = 1_000L
        const val HEY_CYAN_MAX_VIDEO_SECONDS = 720
        const val HEY_CYAN_MAX_AUDIO_SECONDS = 7_200
        const val EYEVUE_MAX_RECORDING_SECONDS = 600
        val TUNEBUDS_COMPANY_IDS = setOf(0x475A, 0x455A, 0x535A, 0x4D5A)
    }
}

package com.fersaiyan.cyanbridge.shared.ble

import com.fersaiyan.cyanbridge.shared.devices.BleDeviceClassifier
import com.fersaiyan.cyanbridge.shared.devices.memomind.GmCodec
import com.fersaiyan.cyanbridge.shared.devices.memomind.MemoMindRecordingLink
import com.fersaiyan.cyanbridge.shared.devices.memomind.MemoMindTransport
import com.fersaiyan.cyanbridge.shared.platform.PlatformLogger
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * MemoMind GM commands over BLE GATT (`BLUETOOTH_DEVELOPER_GUIDE.md`): one complete GM frame
 * per write to 0x2021, sized to the negotiated MTU, each fragment waiting for the firmware's
 * empty flow-control response; responses and events arrive on 0x2022. The shared
 * [IosBleManager] owns the connection; [IosAppController] selects 0x2021 as its write
 * characteristic for MemoMind. The firmware has no BLE recording channel, so
 * [recordingLink] is null and voice input uses the glasses' HFP microphone.
 */
class IosMemoMindBleTransport(private val bleManager: IosBleManager) : MemoMindTransport {
    private val notifyUuid = BleDeviceClassifier.normalizeUuid(NOTIFY_UUID)
    private var listener: BleNotificationListener? = null

    override val isConnected: Boolean get() = bleManager.isConnected()

    /** The ATT write limit (MTU - 3), capped at the 512-byte GM frame. */
    override val maxFrameBytes: Int
        get() = bleManager.maximumWriteLength().coerceIn(MIN_FRAME_BYTES, GmCodec.MAX_FRAME_BYTES)

    override val awaitsFragmentAcks: Boolean = true

    override val recordingLink: MemoMindRecordingLink? = null

    override suspend fun writeCommand(frame: ByteArray) {
        check(bleManager.awaitCommandReady()) { "MemoMind BLE command channel is not ready" }
        bleManager.sendCommand(frame)
    }

    /** Routes 0x2022 notifications to [onBytes] (the session's command reader). */
    fun attach(onBytes: (ByteArray) -> Unit) {
        listener?.let(bleManager::removeNotificationListener)
        val routed = object : BleNotificationListener {
            override fun onNotification(characteristicId: String, data: ByteArray) {
                if (BleDeviceClassifier.normalizeUuid(characteristicId) == notifyUuid) onBytes(data)
            }
        }
        listener = routed
        bleManager.addNotificationListener(routed)
    }

    /**
     * Waits after the BLE connection until 0x2021 was discovered and 0x2022 notifies: the
     * firmware ignores writes from a central that has not subscribed yet. Fails when the
     * glasses expose no GM GATT service (MFi firmware builds may offer GM over iAP2 only).
     */
    suspend fun awaitReady() {
        if (!bleManager.awaitCommandReady(READY_TIMEOUT_MS)) {
            throw IllegalStateException(MISSING_SERVICE)
        }
        withTimeoutOrNull(READY_TIMEOUT_MS) {
            while (!bleManager.isNotifying(NOTIFY_UUID)) delay(POLL_MS)
            true
        } ?: throw IllegalStateException(MISSING_SERVICE)
        PlatformLogger.i(
            TAG,
            "GM GATT ready service=${bleManager.serviceUuidOf(NOTIFY_UUID)} frame=$maxFrameBytes",
        )
    }

    companion object {
        private const val TAG = "IosMemoMindBle"
        const val SERVICE_UUID = "fb349b5f-8000-0080-0010-002020000000"

        /** The Bluetooth-base form the guide warns some releases may use instead. */
        const val ALTERNATE_SERVICE_UUID = "00002020-0000-1000-8000-00805f9b34fb"
        const val WRITE_UUID = "2021"
        const val NOTIFY_UUID = "2022"
        private const val MIN_FRAME_BYTES = 20
        private const val READY_TIMEOUT_MS = 10_000L
        private const val POLL_MS = 50L
        const val MISSING_SERVICE =
            "These MemoMind glasses expose no GM Bluetooth LE service (0x2021/0x2022). Firmware built for " +
                "MFi offers commands over iAP2 only, which needs an app build declaring the MemoMind accessory protocols."
    }
}

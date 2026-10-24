package com.fersaiyan.cyanbridge.shared.ble

import com.fersaiyan.cyanbridge.shared.devices.BleDeviceClassifier
import com.fersaiyan.cyanbridge.shared.devices.tunebuds.TuneBudsSession
import com.fersaiyan.cyanbridge.shared.devices.tunebuds.TuneBudsSessionState
import com.fersaiyan.cyanbridge.shared.devices.tunebuds.TuneBudsTransport
import com.fersaiyan.cyanbridge.shared.platform.PlatformLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * TuneBuds / AB Mate glasses over BLE. Android talks to them over RFCOMM, which iOS does not
 * allow; the AB Mate iOS app uses the BLE companion service instead (FDB3: write FF17,
 * notify FF18) with the same frames, so the shared [TuneBudsSession] runs on top of it.
 */
class IosTuneBudsSession(
    private val bleManager: IosBleManager,
    scope: CoroutineScope,
    private val onPhoto: (ByteArray) -> Unit,
) {
    private val notifyUuid = BleDeviceClassifier.normalizeUuid(NOTIFY_UUID)

    private val transport = object : TuneBudsTransport {
        override val isConnected: Boolean get() = bleManager.isConnected()
        override val maxWriteLength: Int get() = bleManager.maximumWriteLength()

        override suspend fun write(packet: ByteArray) {
            check(bleManager.awaitCommandReady()) { "TuneBuds BLE channel is not ready" }
            bleManager.sendCommand(packet)
        }
    }

    val session = TuneBudsSession(transport)
    val state: StateFlow<TuneBudsSessionState> = session.state

    init {
        bleManager.addNotificationListener(object : BleNotificationListener {
            override fun onNotification(characteristicId: String, data: ByteArray) {
                if (BleDeviceClassifier.normalizeUuid(characteristicId) == notifyUuid) session.onBytesReceived(data)
            }
        })
        // Photos from the glasses' own AI button arrive without a request.
        scope.launch { session.aiPhotos.collect(onPhoto) }
    }

    suspend fun onConnected(): Boolean {
        session.reset()
        return runCatching {
            check(bleManager.awaitCommandReady()) { "TuneBuds BLE channel is not ready" }
            session.initialize()
            session.syncTime()
        }.onFailure { PlatformLogger.w(TAG, "TuneBuds initialization failed: ${it.message}") }.isSuccess
    }

    /** Runs [block], turning failures into a message for the dashboard (null on success). */
    suspend fun run(label: String, block: suspend TuneBudsSession.() -> Unit): String? =
        runCatching { session.block() }.exceptionOrNull()?.let { error ->
            PlatformLogger.w(TAG, "$label failed: ${error.message}")
            "$label failed: ${error.message ?: "unknown error"}"
        }

    companion object {
        private const val TAG = "IosTuneBuds"
        const val WRITE_UUID = "FF17"
        const val NOTIFY_UUID = "FF18"
    }
}

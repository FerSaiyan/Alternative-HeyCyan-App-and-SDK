package com.fersaiyan.cyanbridge.shared.ble

import com.fersaiyan.cyanbridge.shared.devices.BleDeviceClassifier
import com.fersaiyan.cyanbridge.shared.devices.eyevue.EyevueBattery
import com.fersaiyan.cyanbridge.shared.devices.eyevue.EyevueFrameDecoder
import com.fersaiyan.cyanbridge.shared.devices.eyevue.EyevuePhotoAssembler
import com.fersaiyan.cyanbridge.shared.devices.eyevue.EyevueProtocol
import com.fersaiyan.cyanbridge.shared.platform.PlatformLogger
import platform.Foundation.NSCalendar
import platform.Foundation.NSCalendarUnitDay
import platform.Foundation.NSCalendarUnitHour
import platform.Foundation.NSCalendarUnitMinute
import platform.Foundation.NSCalendarUnitMonth
import platform.Foundation.NSCalendarUnitSecond
import platform.Foundation.NSCalendarUnitYear
import platform.Foundation.NSDate

/**
 * Eyevue glasses over plain BLE GATT (Android: EyevueGattClient + EyevueManager).
 * Commands go to AA13; replies arrive on AA14 and AI photos on AA15.
 */
class IosEyevueSession(
    private val bleManager: IosBleManager,
    private val onBattery: (EyevueBattery) -> Unit,
    private val onWifiSsid: (String) -> Unit,
    private val onPhoto: (ByteArray) -> Unit,
) {
    private val decoder = EyevueFrameDecoder()
    private val photoAssembler = EyevuePhotoAssembler()
    private val commandNotify = BleDeviceClassifier.normalizeUuid(EyevueProtocol.COMMAND_NOTIFY_UUID)
    private val photoNotify = BleDeviceClassifier.normalizeUuid(EyevueProtocol.PHOTO_NOTIFY_UUID)

    private val listener = object : BleNotificationListener {
        override fun onNotification(characteristicId: String, data: ByteArray) {
            when (BleDeviceClassifier.normalizeUuid(characteristicId)) {
                commandNotify -> decoder.append(data).forEach { frame ->
                    EyevueProtocol.parseBattery(frame)?.let(onBattery)
                    EyevueProtocol.parseWifiSsid(frame)?.let(onWifiSsid)
                }
                photoNotify -> photoAssembler.append(data)?.let(onPhoto)
            }
        }
    }

    init {
        bleManager.addNotificationListener(listener)
    }

    suspend fun onConnected() {
        decoder.reset()
        syncTime()
        requestBattery()
    }

    suspend fun takePhoto(highQuality: Boolean) = send(EyevueProtocol.buildPhotoPacket(highQuality))

    suspend fun setVideoRecording(start: Boolean) =
        send(if (start) EyevueProtocol.buildStartVideoPacket() else EyevueProtocol.buildStopVideoPacket())

    suspend fun setAudioRecording(start: Boolean) = send(EyevueProtocol.buildAudioPacket(start))

    suspend fun requestBattery() = send(EyevueProtocol.buildGetBatteryPacket())

    suspend fun requestWifiInfo() = send(EyevueProtocol.buildGetWifiInfoPacket())

    suspend fun setWearingDetection(enabled: Boolean) = send(EyevueProtocol.buildWearDetectionPacket(enabled))

    suspend fun setRecordingDuration(seconds: Int) = send(EyevueProtocol.buildRecordDurationPacket(seconds))

    suspend fun syncTime(): Boolean {
        val calendar = NSCalendar.currentCalendar
        val units = NSCalendarUnitYear or NSCalendarUnitMonth or NSCalendarUnitDay or
            NSCalendarUnitHour or NSCalendarUnitMinute or NSCalendarUnitSecond
        val now = calendar.components(units, fromDate = NSDate())
        return send(
            EyevueProtocol.buildSetTimePacket(
                year = now.year.toInt(),
                month = now.month.toInt(),
                day = now.day.toInt(),
                hour = now.hour.toInt(),
                minute = now.minute.toInt(),
                second = now.second.toInt(),
            ),
        )
    }

    private suspend fun send(packet: ByteArray): Boolean = runCatching {
        check(bleManager.awaitCommandReady()) { "Eyevue command channel is not ready" }
        bleManager.sendCommand(packet)
    }.onFailure { PlatformLogger.w(TAG, "Eyevue command failed: ${it.message}") }.isSuccess

    private companion object {
        const val TAG = "IosEyevue"
    }
}

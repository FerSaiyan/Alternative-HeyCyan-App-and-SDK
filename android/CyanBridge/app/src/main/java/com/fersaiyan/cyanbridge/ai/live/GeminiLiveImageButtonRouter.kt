package com.fersaiyan.cyanbridge.ai.live

import com.fersaiyan.cyanbridge.glasses.GlassesSessionCoordinator
import com.fersaiyan.cyanbridge.glasses.HeyCyanPhotoCapture
import com.fersaiyan.cyanbridge.glasses.GlassesPhotoCapture
import com.fersaiyan.cyanbridge.shared.devices.DeviceClass
import kotlinx.coroutines.Deferred
import com.oudmon.ble.base.communication.LargeDataHandler
import com.oudmon.ble.base.communication.bigData.resp.GlassesDeviceNotifyListener
import com.oudmon.ble.base.communication.bigData.resp.GlassesDeviceNotifyRsp

/** Native stream-start events carry acquisition; HeyCyan requests its fresh ready thumbnail. */
data class ManualGlassesPhoto(
    val device: DeviceClass,
    val acquisition: Deferred<ByteArray>? = null,
)

/** One device-filtered handler. Owned captures never become manual Live inputs. */
object GeminiLiveImageButtonRouter {
    private class Registration(val device: DeviceClass, val identity: Any, val callback: (ManualGlassesPhoto) -> Unit)
    @Volatile
    private var registration: Registration? = null

    private val listener = object : GlassesDeviceNotifyListener() {
        override fun parseData(cmdType: Int, response: GlassesDeviceNotifyRsp) {
            if (response.loadData.size > 6 && response.loadData[6].toInt() == 0x02) {
                handleImageButton()
            }
        }
    }

    @Synchronized
    fun register(activeHandler: () -> Unit) {
        install(Registration(DeviceClass.HEY_CYAN, activeHandler) { activeHandler() })
    }

    @Synchronized
    fun register(device: DeviceClass, activeHandler: (ManualGlassesPhoto) -> Unit) {
        install(Registration(device, activeHandler, activeHandler))
    }

    private fun install(next: Registration) {
        if (registration?.device == DeviceClass.HEY_CYAN && next.device != DeviceClass.HEY_CYAN) {
            LargeDataHandler.getInstance().removeOutDeviceListener(LISTENER_ID)
        }
        if (next.device == DeviceClass.HEY_CYAN) LargeDataHandler.getInstance().addOutDeviceListener(LISTENER_ID, listener)
        registration = next
    }

    @Synchronized
    fun unregister(activeHandler: () -> Unit) {
        uninstall(activeHandler)
    }

    @Synchronized
    fun unregister(activeHandler: (ManualGlassesPhoto) -> Unit) { uninstall(activeHandler) }

    private fun uninstall(identity: Any) {
        val current = registration ?: return
        if (current.identity !== identity) return
        registration = null
        if (current.device == DeviceClass.HEY_CYAN) LargeDataHandler.getInstance().removeOutDeviceListener(LISTENER_ID)
    }

    fun isRegistered(): Boolean = registration?.device == DeviceClass.HEY_CYAN

    /** Bind a fresh native stream to the current Live registration, including acquisition failure. */
    fun nativePhotoReceiver(device: DeviceClass): ((Deferred<ByteArray>) -> Unit)? {
        val current = registration?.takeIf { it.device == device } ?: return null
        return { acquisition ->
            if (registration === current) current.callback(ManualGlassesPhoto(device, acquisition = acquisition))
            else acquisition.cancel()
        }
    }

    fun handleImageButton(): Boolean {
        return route(ManualGlassesPhoto(DeviceClass.HEY_CYAN))
    }

    private fun route(photo: ManualGlassesPhoto): Boolean {
        val current = registration?.takeIf { it.device == photo.device } ?: return false
        // An owned programmatic capture receives its event through its own waiter. Returning
        // true also suppresses fallback: never start a competing read for the same photo.
        if (GlassesPhotoCapture.currentDevice() != null || HeyCyanPhotoCapture.isActive() ||
            !GlassesSessionCoordinator.canRunBackgroundCommand()
        ) return true
        current.callback(photo)
        return true
    }

    private const val LISTENER_ID = 7_105
}

package com.fersaiyan.cyanbridge.shared.glasses

import com.fersaiyan.cyanbridge.shared.devices.DeviceClass
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * The vendor SDK exposes singleton BLE response/listener slots and a singleton P2P controller.
 * Only one workflow may control those resources at a time.
 */
enum class GlassesSession(val label: String) {
    MEDIA_SYNC("media sync"),
    LIVE_PREVIEW("live preview"),
    OTA("firmware update"),
    WIFI_ADB_DEBUG("Wi-Fi ADB debug"),
    META_CAMERA("Meta camera"),
}

class GlassesSessionLease internal constructor(
    val session: GlassesSession,
    internal val id: Long,
)

class BackgroundGlassesCommandPermit internal constructor(internal val id: Long, val device: DeviceClass)

/**
 * Exclusive workflow leases and one-shot command permits for the glasses. There is no
 * preemption: a held lease or an outstanding one-shot permit refuses every other request, and
 * only the exact lease/permit object can release itself. A real Bluetooth disconnect clears
 * everything so a stale lease cannot outlive the link it protected.
 */
object GlassesSessionCoordinator {
    private val lock = SynchronizedObject()
    private var activeLease: GlassesSessionLease? = null
    private var nextSessionId = 0L
    private var nextBackgroundCommandId = 0L
    private val activeBackgroundCommandIds = mutableMapOf<Long, DeviceClass>()

    fun tryAcquire(session: GlassesSession): Boolean {
        return synchronized(lock) {
            tryAcquireLease(session) != null
        }
    }

    fun tryAcquireLease(session: GlassesSession): GlassesSessionLease? {
        return synchronized(lock) {
            if (activeLease != null || activeBackgroundCommandIds.isNotEmpty()) return null
            GlassesSessionLease(session, ++nextSessionId).also { activeLease = it }
        }
    }

    fun release(session: GlassesSession): Boolean {
        return synchronized(lock) {
            val lease = activeLease ?: return false
            if (lease.session != session) return false
            activeLease = null
            true
        }
    }

    fun release(lease: GlassesSessionLease): Boolean {
        return synchronized(lock) {
            if (activeLease !== lease) return false
            activeLease = null
            true
        }
    }

    fun currentSession(): GlassesSession? = synchronized(lock) { activeLease?.session }

    fun isOwnedBy(session: GlassesSession): Boolean = synchronized(lock) { activeLease?.session == session }

    fun isActive(lease: GlassesSessionLease): Boolean = synchronized(lock) { activeLease === lease }

    fun canRunBackgroundCommand(): Boolean = synchronized(lock) {
        activeLease == null && activeBackgroundCommandIds.isEmpty()
    }

    /** Atomically reserves the shared SDK response slot for a short one-shot command. */
    fun tryAcquireBackgroundCommand(device: DeviceClass = DeviceClass.HEY_CYAN): BackgroundGlassesCommandPermit? {
        return synchronized(lock) {
            if (activeLease != null || activeBackgroundCommandIds.isNotEmpty()) return null
            val permit = BackgroundGlassesCommandPermit(++nextBackgroundCommandId, device)
            activeBackgroundCommandIds[permit.id] = device
            permit
        }
    }

    fun releaseBackgroundCommand(permit: BackgroundGlassesCommandPermit) {
        synchronized(lock) {
            activeBackgroundCommandIds.remove(permit.id)
        }
    }

    fun isBackgroundCommandActive(permit: BackgroundGlassesCommandPermit): Boolean = synchronized(lock) {
        permit.id in activeBackgroundCommandIds
    }

    /** A BLE reconnect discards any vendor callback slot that may have been left pending. */
    fun clearBackgroundCommands(device: DeviceClass? = null) {
        synchronized(lock) {
            if (device == null) {
                activeBackgroundCommandIds.clear()
            } else {
                val stale = activeBackgroundCommandIds.filterValues { it == device }.keys.toList()
                stale.forEach(activeBackgroundCommandIds::remove)
            }
        }
    }

    /** A real BLE disconnect invalidates every pending vendor response and P2P workflow. */
    fun clearForDisconnectedDevice(device: DeviceClass? = null) {
        synchronized(lock) {
            activeLease = null
            clearBackgroundCommands(device)
        }
    }
}

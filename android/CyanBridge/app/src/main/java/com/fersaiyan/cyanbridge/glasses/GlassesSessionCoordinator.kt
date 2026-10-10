package com.fersaiyan.cyanbridge.glasses

import com.fersaiyan.cyanbridge.shared.devices.DeviceClass

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

object GlassesSessionCoordinator {
    private var activeLease: GlassesSessionLease? = null
    private var nextSessionId = 0L
    private var nextBackgroundCommandId = 0L
    private val activeBackgroundCommandIds = mutableMapOf<Long, DeviceClass>()

    @Synchronized
    fun tryAcquire(session: GlassesSession): Boolean {
        return tryAcquireLease(session) != null
    }

    @Synchronized
    fun tryAcquireLease(session: GlassesSession): GlassesSessionLease? {
        if (activeLease != null || activeBackgroundCommandIds.isNotEmpty()) return null
        return GlassesSessionLease(session, ++nextSessionId).also { activeLease = it }
    }

    @Synchronized
    fun release(session: GlassesSession): Boolean {
        val lease = activeLease ?: return false
        if (lease.session != session) return false
        activeLease = null
        return true
    }

    @Synchronized
    fun release(lease: GlassesSessionLease): Boolean {
        if (activeLease !== lease) return false
        activeLease = null
        return true
    }

    @Synchronized
    fun currentSession(): GlassesSession? = activeLease?.session

    @Synchronized
    fun isOwnedBy(session: GlassesSession): Boolean = activeLease?.session == session

    @Synchronized
    fun isActive(lease: GlassesSessionLease): Boolean = activeLease === lease

    @Synchronized
    fun canRunBackgroundCommand(): Boolean =
        activeLease == null && activeBackgroundCommandIds.isEmpty()

    /** Atomically reserves the shared SDK response slot for a short one-shot command. */
    @Synchronized
    fun tryAcquireBackgroundCommand(device: DeviceClass = DeviceClass.HEY_CYAN): BackgroundGlassesCommandPermit? {
        if (activeLease != null || activeBackgroundCommandIds.isNotEmpty()) return null
        val permit = BackgroundGlassesCommandPermit(++nextBackgroundCommandId, device)
        activeBackgroundCommandIds[permit.id] = device
        return permit
    }

    @Synchronized
    fun releaseBackgroundCommand(permit: BackgroundGlassesCommandPermit) {
        activeBackgroundCommandIds.remove(permit.id)
    }

    @Synchronized
    fun isBackgroundCommandActive(permit: BackgroundGlassesCommandPermit): Boolean =
        permit.id in activeBackgroundCommandIds

    /** A BLE reconnect discards any vendor callback slot that may have been left pending. */
    @Synchronized
    fun clearBackgroundCommands(device: DeviceClass? = null) {
        if (device == null) activeBackgroundCommandIds.clear()
        else activeBackgroundCommandIds.entries.removeAll { it.value == device }
    }

    /** A real BLE disconnect invalidates every pending vendor response and P2P workflow. */
    @Synchronized
    fun clearForDisconnectedDevice(device: DeviceClass? = null) {
        activeLease = null
        clearBackgroundCommands(device)
    }
}

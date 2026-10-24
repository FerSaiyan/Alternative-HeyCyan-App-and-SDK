package com.fersaiyan.cyanbridge.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.util.Log
import com.fersaiyan.cyanbridge.shared.devices.DeviceClass
import com.fersaiyan.cyanbridge.shared.network.session.BleLossOutcome
import com.fersaiyan.cyanbridge.shared.network.session.GlassesLinkClaim
import com.fersaiyan.cyanbridge.shared.network.session.GlassesLinkClaimResult
import com.fersaiyan.cyanbridge.shared.network.session.GlassesLinkClient
import com.fersaiyan.cyanbridge.shared.network.session.GlassesLinkReleaseMode
import com.fersaiyan.cyanbridge.shared.network.session.GlassesLinkSnapshot
import com.fersaiyan.cyanbridge.shared.network.session.GlassesLinkTeardownEvent
import com.fersaiyan.cyanbridge.shared.network.session.GlassesNetworkSessionCore
import com.fersaiyan.cyanbridge.shared.network.session.P2pGroupDriver
import com.fersaiyan.cyanbridge.shared.network.session.P2pTeardownPolicy
import com.fersaiyan.cyanbridge.ui.wifi.p2p.WifiP2pManagerSingleton
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * A HeyCyan Wi-Fi Direct link held through the vendor-shaped [WifiP2pManagerSingleton].
 * The owner drives discovery and connection through [manager]; acquisition, teardown and
 * Bluetooth-loss handling belong to [GlassesNetworkSessionCoordinator].
 */
class HeyCyanP2pLink internal constructor(
    val claim: GlassesLinkClaim,
    val manager: WifiP2pManagerSingleton,
    internal val callback: WifiP2pManagerSingleton.WifiP2pCallback,
) {
    internal val receiverRegistered = AtomicBoolean(false)
    internal val closed = AtomicBoolean(false)

    val client: GlassesLinkClient get() = claim.client
    val isActive: Boolean get() = GlassesNetworkSessionCoordinator.core.isActive(claim)

    fun markConnectRequested() {
        GlassesNetworkSessionCoordinator.core.markConnectRequested(claim)
    }

    fun markGroupFormed(formed: Boolean) {
        val core = GlassesNetworkSessionCoordinator.core
        if (formed) core.markGroupFormed(claim) else core.markGroupLost(claim)
    }
}

/**
 * The process-wide owner of the phone <-> glasses network lifecycle: link claims (one owner at
 * a time), Wi-Fi Direct registration and group teardown, process network binding and the
 * Bluetooth-loss rules. HeyCyan media sync, OTA, live preview and Wi-Fi ADB debug use
 * [openHeyCyanLink]/[closeHeyCyanLink]; the Eyevue and TuneBuds transports use the vendor-link
 * calls with their own [P2pGroupDriver]. The decisions themselves live in the shared
 * [GlassesNetworkSessionCore].
 */
object GlassesNetworkSessionCoordinator {
    const val TAG = "GlassesNetSession"

    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val policy = P2pTeardownPolicy()
    val core = GlassesNetworkSessionCore(mainScope, policy) { message -> Log.i(TAG, message) }
    val snapshot: StateFlow<GlassesLinkSnapshot> get() = core.snapshot

    private val bindingLock = Any()
    private var boundNetwork: Network? = null
    private var boundClaim: GlassesLinkClaim? = null

    fun routes(context: Context): AndroidGlassesNetworkRoutes = AndroidGlassesNetworkRoutes(context)

    // ---- HeyCyan Wi-Fi Direct links ---------------------------------------------------------

    /**
     * Claims the link for [client], waiting for a previous owner's teardown and removing a group
     * that an abandoned link may have left. Registers [callback] and the P2P broadcast receiver
     * and clears the manager's retry state; the caller then starts discovery.
     */
    suspend fun openHeyCyanLink(
        context: Context,
        client: GlassesLinkClient,
        callback: WifiP2pManagerSingleton.WifiP2pCallback,
    ): HeyCyanP2pLink? {
        val manager = WifiP2pManagerSingleton.getInstance(context)
        val result = core.awaitClaim(client, preflightDriver = { HeyCyanGroupDriver(manager, trustTrackedState = false) })
        if (result !is GlassesLinkClaimResult.Granted) {
            Log.w(TAG, "${client.label} could not claim the glasses link: $result")
            return null
        }
        val link = HeyCyanP2pLink(result.claim, manager, callback)
        manager.addCallback(callback)
        try {
            manager.registerReceiver()
            link.receiverRegistered.set(true)
        } catch (error: Exception) {
            manager.removeCallback(callback)
            core.release(result.claim, GlassesLinkReleaseMode.NO_GROUP)
            Log.e(TAG, "${client.label}: failed to register the P2P receiver: ${error.message}", error)
            return null
        }
        manager.resetFailCount()
        Log.i(TAG, "${client.label} owns the glasses link (${result.claim})")
        return link
    }

    /** Main-thread callback form of [openHeyCyanLink] for non-suspending callers. */
    fun openHeyCyanLinkAsync(
        context: Context,
        client: GlassesLinkClient,
        callback: WifiP2pManagerSingleton.WifiP2pCallback,
        onResult: (HeyCyanP2pLink?) -> Unit,
    ) {
        val appContext = context.applicationContext
        mainScope.launch { onResult(openHeyCyanLink(appContext, client, callback)) }
    }

    /**
     * Stops discovery, cancels any pending connect, restores the default process network and
     * removes the group with the shared retry/quarantine policy. [onTeardownConfirmed] runs on
     * the main thread once the group is confirmed gone; it never runs for a link abandoned by
     * Bluetooth loss.
     */
    fun closeHeyCyanLink(context: Context, link: HeyCyanP2pLink, onTeardownConfirmed: () -> Unit) {
        if (!link.closed.compareAndSet(false, true)) {
            Log.w(TAG, "${link.client.label}: link teardown already requested")
            return
        }
        val manager = link.manager
        manager.removeCallback(link.callback)
        // Mirror the official app: stop rearming discovery and cancel the P2P connection.
        manager.stopP2pOperations()
        manager.cancelP2pConnection()
        unbindProcess(context, link.claim)

        // Disconnect broadcasts confirm the removal without waiting for the next poll.
        val teardownCallback = object : WifiP2pCallbackAdapter() {
            override fun onConnected(info: WifiP2pInfo) {
                if (!info.groupFormed) core.signalGroupGone()
            }

            override fun onDisconnected() {
                core.signalGroupGone()
            }
        }
        manager.addCallback(teardownCallback)
        val driver = HeyCyanGroupDriver(manager, trustTrackedState = true) {
            manager.removeCallback(teardownCallback)
            unregisterReceiver(link)
            WifiP2pManagerSingleton.lastGroupOwnerIp = null
        }
        val release = core.release(link.claim, GlassesLinkReleaseMode.CONFIRM_TEARDOWN, driver) { event ->
            if (event == GlassesLinkTeardownEvent.Confirmed) onTeardownConfirmed()
        }
        if (release == null) {
            // The link was already abandoned; nothing is reserved any more.
            driver.onTeardownFinished()
            Log.i(TAG, "${link.client.label}: link was no longer claimed; teardown is a no-op")
            mainScope.launch { onTeardownConfirmed() }
        }
    }

    /**
     * Drops [link] without confirming group removal (its owner's Bluetooth link is gone). The
     * next claim removes any stale group first.
     */
    fun abandonHeyCyanLink(context: Context, link: HeyCyanP2pLink) {
        link.closed.set(true)
        val manager = link.manager
        manager.removeCallback(link.callback)
        manager.stopP2pOperations()
        manager.cancelP2pConnection()
        unregisterReceiver(link)
        unbindProcess(context, link.claim)
        core.abandon(link.claim)
    }

    private fun unregisterReceiver(link: HeyCyanP2pLink) {
        if (link.receiverRegistered.compareAndSet(true, false)) {
            link.manager.unregisterReceiver()
        }
    }

    // ---- Vendor transports -----------------------------------------------------------------

    /** Claims the link for a vendor transport; null when another workflow still owns it. */
    suspend fun claimVendorLink(context: Context, client: GlassesLinkClient): GlassesLinkClaim? {
        val manager = WifiP2pManagerSingleton.getInstance(context)
        val result = core.awaitClaim(client, preflightDriver = { HeyCyanGroupDriver(manager, trustTrackedState = false) })
        if (result !is GlassesLinkClaimResult.Granted) {
            Log.w(TAG, "${client.label} could not claim the glasses link: $result")
            return null
        }
        Log.i(TAG, "${client.label} owns the glasses link (${result.claim})")
        return result.claim
    }

    /**
     * Releases a vendor link. With a [driver] the group removal runs (and is retried) in the
     * background while the link stays reserved; without one the link is free immediately.
     */
    fun releaseVendorLink(context: Context, claim: GlassesLinkClaim, driver: P2pGroupDriver?) {
        unbindProcess(context, claim)
        val mode = if (driver == null) GlassesLinkReleaseMode.NO_GROUP else GlassesLinkReleaseMode.CONFIRM_TEARDOWN
        if (core.release(claim, mode, driver) == null) driver?.onTeardownFinished()
    }

    /** Releases with confirmation; true once the group is gone, false when it was quarantined. */
    suspend fun releaseVendorLinkAndAwait(context: Context, claim: GlassesLinkClaim, driver: P2pGroupDriver): Boolean {
        unbindProcess(context, claim)
        if (!core.isActive(claim)) {
            driver.onTeardownFinished()
            return true
        }
        return core.releaseAndAwait(claim, driver)
    }

    // ---- Process network binding -----------------------------------------------------------

    /**
     * Binds the whole process to [network] for [claim]. Attempted even while a VPN is active:
     * a bypassable VPN accepts it, and a refusal leaves per-socket binding as the fallback.
     */
    fun bindProcessToNetwork(context: Context, claim: GlassesLinkClaim, network: Network?): Boolean {
        network ?: return false
        synchronized(bindingLock) {
            if (!core.isActive(claim)) {
                Log.w(TAG, "${claim.client.label}: refusing process binding for a released link")
                return false
            }
            if (boundNetwork == network && boundClaim === claim) return true
            val connectivity = connectivity(context) ?: return false
            val bound = try {
                connectivity.bindProcessToNetwork(network)
            } catch (error: Exception) {
                Log.w(TAG, "${claim.client.label}: bindProcessToNetwork failed: ${error.message}")
                false
            }
            if (bound) {
                boundNetwork = network
                boundClaim = claim
                core.markRouteBound(claim, true)
                Log.i(TAG, "${claim.client.label}: process bound to the glasses network")
            }
            return bound
        }
    }

    /** Restores the default network if [claim] (or, when null, anyone) bound the process. */
    fun unbindProcess(context: Context, claim: GlassesLinkClaim?): Boolean {
        synchronized(bindingLock) {
            if (boundNetwork == null) return false
            if (claim != null && boundClaim !== claim) return false
            val owner = boundClaim
            try {
                connectivity(context)?.bindProcessToNetwork(null)
            } catch (error: Exception) {
                Log.w(TAG, "Failed to restore the default process network: ${error.message}")
            } finally {
                boundNetwork = null
                boundClaim = null
            }
            owner?.let { core.markRouteBound(it, false) }
            Log.i(TAG, "${owner?.client?.label ?: "glasses link"}: restored the default process network")
            return true
        }
    }

    /** Unconditionally restores the default process network (firmware-update stage boundary). */
    fun restoreDefaultProcessNetwork(context: Context) {
        synchronized(bindingLock) {
            val connectivity = connectivity(context) ?: return
            try {
                connectivity.bindProcessToNetwork(null)
            } finally {
                boundClaim?.let { core.markRouteBound(it, false) }
                boundNetwork = null
                boundClaim = null
            }
        }
    }

    fun isProcessBound(claim: GlassesLinkClaim): Boolean = synchronized(bindingLock) { boundClaim === claim }

    // ---- Bluetooth -----------------------------------------------------------------------------

    /** Applies each owner's Bluetooth-loss rule; restores the default network if it was bound. */
    fun onBluetoothDisconnected(context: Context, device: DeviceClass): BleLossOutcome {
        val outcome = core.onBluetoothDisconnected(device)
        if (outcome is BleLossOutcome.Abandoned && outcome.routeWasBound) unbindProcess(context, null)
        return outcome
    }

    /** Retries a quarantined teardown for [device] now instead of waiting for the next period. */
    fun onBluetoothConnected(device: DeviceClass) {
        core.onBluetoothConnected(device)
    }

    /** A workflow escalated its own teardown (for example OTA failure after a stage teardown). */
    fun retryQuarantinedTeardown(device: DeviceClass) {
        core.onBluetoothConnected(device)
    }

    private fun connectivity(context: Context): ConnectivityManager? =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
}

/** [P2pGroupDriver] over the HeyCyan [WifiP2pManagerSingleton] channel. */
internal class HeyCyanGroupDriver(
    private val manager: WifiP2pManagerSingleton,
    /** Accept the singleton's tracked idle state as confirmation (the established HeyCyan check). */
    private val trustTrackedState: Boolean,
    private val onFinished: () -> Unit = {},
) : P2pGroupDriver {
    private val finished = AtomicBoolean(false)

    override fun removeGroup(onResult: (accepted: Boolean) -> Unit) = manager.removeGroup(onResult)

    override fun requestGroupState(onState: (formed: Boolean) -> Unit) {
        manager.requestConnectionInfo { info -> onState(info.groupFormed) }
    }

    override fun trackedLinkIdle(): Boolean? =
        if (trustTrackedState) !manager.isConnecting() && !manager.isConnected() else null

    override fun canUseP2p(): Boolean = manager.canUseP2p()

    override fun onTeardownFinished() {
        if (finished.compareAndSet(false, true)) onFinished()
    }
}

/** No-op base for observers that only care about a few P2P events. */
internal open class WifiP2pCallbackAdapter : WifiP2pManagerSingleton.WifiP2pCallback {
    override fun onWifiP2pEnabled() = Unit
    override fun onWifiP2pDisabled() = Unit
    override fun onPeersChanged(peers: Collection<WifiP2pDevice>) = Unit
    override fun onThisDeviceChanged(device: WifiP2pDevice) = Unit
    override fun onConnected(info: WifiP2pInfo) = Unit
    override fun onDisconnected() = Unit
    override fun onPeerDiscoveryStarted() = Unit
    override fun onPeerDiscoveryFailed(reason: Int) = Unit
    override fun onConnectRequestSent() = Unit
    override fun onConnectRequestFailed(reason: Int) = Unit
    override fun connecting() = Unit
    override fun cancelConnect() = Unit
    override fun cancelConnectFail(reason: Int) = Unit
    override fun retryAlsoFailed() = Unit
}

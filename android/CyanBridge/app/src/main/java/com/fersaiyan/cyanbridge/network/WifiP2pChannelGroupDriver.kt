package com.fersaiyan.cyanbridge.network

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.p2p.WifiP2pManager
import com.fersaiyan.cyanbridge.shared.network.session.P2pGroupDriver
import com.fersaiyan.cyanbridge.ui.hasWifiP2pPermission
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [P2pGroupDriver] over a vendor transport's own Wi-Fi Direct channel. The channel stays open
 * until the shared teardown finishes with it, then it is closed.
 */
class WifiP2pChannelGroupDriver(
    context: Context,
    private val manager: WifiP2pManager,
    private val channel: WifiP2pManager.Channel,
) : P2pGroupDriver {
    private val context = context.applicationContext
    private val finished = AtomicBoolean(false)

    @SuppressLint("MissingPermission")
    override fun removeGroup(onResult: (accepted: Boolean) -> Unit) {
        manager.removeGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = onResult(true)
            override fun onFailure(reason: Int) = onResult(false)
        })
    }

    @SuppressLint("MissingPermission")
    override fun requestGroupState(onState: (formed: Boolean) -> Unit) {
        if (!canUseP2p()) return
        manager.requestConnectionInfo(channel) { info -> onState(info?.groupFormed == true) }
    }

    override fun canUseP2p(): Boolean = hasWifiP2pPermission(context)

    override fun onTeardownFinished() {
        if (finished.compareAndSet(false, true)) runCatching { channel.close() }
    }
}

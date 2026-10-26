package com.fersaiyan.cyanbridge.shared.ui.glasses

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fersaiyan.cyanbridge.shared.devices.mentra.MentraFirmwareUpdateSession
import com.fersaiyan.cyanbridge.shared.devices.mentra.MentraOtaBlocker
import com.fersaiyan.cyanbridge.shared.devices.mentra.MentraOtaGlasses
import com.fersaiyan.cyanbridge.shared.devices.mentra.MentraOtaRules
import com.fersaiyan.cyanbridge.shared.devices.mentra.MentraOtaState
import com.fersaiyan.cyanbridge.shared.devices.mentra.MentraWifiNetwork
import com.fersaiyan.cyanbridge.shared.devices.mentra.MentraWifiSetupState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn

/** Everything the Mentra firmware update screen renders. */
data class MentraFirmwareUpdateUiState(
    val ota: MentraOtaState = MentraOtaState(),
    val glasses: MentraOtaGlasses = MentraOtaGlasses(),
    val wifi: MentraWifiSetupState = MentraWifiSetupState(),
    val blockers: Set<MentraOtaBlocker> = emptySet(),
    val chargingRecommended: Boolean = false,
)

/**
 * The Mentra Live firmware update screen. The update itself runs in the process-wide
 * [MentraFirmwareUpdateSession]; this model adds the screen's own state (confirmation, Wi-Fi
 * setup) and refuses to leave while the update runs.
 */
class MentraFirmwareUpdateViewModel(private val session: MentraFirmwareUpdateSession) : ViewModel() {
    private val ticker = flow {
        while (true) {
            emit(Unit)
            delay(1_000)
        }
    }

    val uiState: StateFlow<MentraFirmwareUpdateUiState> =
        combine(session.state, session.glasses, session.wifi, ticker) { ota, glasses, wifi, _ ->
            MentraFirmwareUpdateUiState(
                ota = ota,
                glasses = glasses,
                wifi = wifi,
                blockers = session.blockers(),
                chargingRecommended = MentraOtaRules.chargingRecommended(glasses),
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, MentraFirmwareUpdateUiState())

    var confirming by mutableStateOf(false)
        private set
    var showWifiSetup by mutableStateOf(false)
        private set
    var passwordFor by mutableStateOf<MentraWifiNetwork?>(null)
        private set

    /** Incremented when the user tries to leave during the update; the screen shows a notice. */
    var leaveRefusals by mutableIntStateOf(0)
        private set

    fun onOpened() = session.onScreenOpened()

    fun check() = session.check()

    fun requestStart() {
        if (session.blockers().isEmpty()) confirming = true
    }

    fun dismissConfirmation() {
        confirming = false
    }

    fun confirmStart() {
        confirming = false
        session.start()
    }

    fun openWifiSetup() {
        showWifiSetup = true
        if (!session.wifi.value.scanned) session.scanWifi()
    }

    fun closeWifiSetup() {
        showWifiSetup = false
        passwordFor = null
    }

    fun scanWifi() = session.scanWifi()

    fun chooseNetwork(network: MentraWifiNetwork) {
        if (network.secured) passwordFor = network else session.connectWifi(network.ssid, "")
    }

    fun dismissPassword() {
        passwordFor = null
    }

    fun connectWifi(password: String) {
        val network = passwordFor ?: return
        passwordFor = null
        session.connectWifi(network.ssid, password)
    }

    /** Returns true when the screen may close. */
    fun onLeaveRequested(): Boolean {
        if (!session.state.value.isActive) return true
        leaveRefusals++
        return false
    }
}

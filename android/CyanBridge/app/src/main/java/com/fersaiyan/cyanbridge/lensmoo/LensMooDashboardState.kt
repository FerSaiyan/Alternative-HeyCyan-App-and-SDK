package com.fersaiyan.cyanbridge.lensmoo

import com.fersaiyan.cyanbridge.shared.glasses.GlassesDashboardUiState
import com.fersaiyan.cyanbridge.shared.glasses.LensMooUiState

data class LensMooLocalUiState(
    val connected:Boolean=false,
    val batteryPercent:Int?=null,
    val busy:Boolean=false,
    val previewRunning:Boolean=false,
    val deviceName:String?=null,
    val message:String="",
)

internal object LensMooDashboardState {
    fun build(local:LensMooLocalUiState,service:LensMooServiceUiState,ownerConfigured:Boolean):GlassesDashboardUiState {
        val connected=if(service.running) service.ready else local.connected
        val battery=(if(service.running) service.batteryPercent else local.batteryPercent).takeIf { connected }
        return GlassesDashboardUiState(
            connectionLabel=if(connected) "Connected" else if(service.running) "Connecting…" else "Disconnected",
            deviceClassLabel="LensMoo / XKOENPRO",
            deviceInfoLabel=local.deviceName,
            batteryPercent=battery,showBattery=battery!=null,showMediaSync=false,
            lensMoo=LensMooUiState(
                isConnected=connected,isBusy=local.busy || service.busy,
                assistantRunning=service.running,assistantReady=service.ready,
                previewRunning=local.previewRunning,hasOwnerId=ownerConfigured,
                status=if(service.running) service.message else local.message,
                deviceName=local.deviceName,
            ),
        )
    }
}

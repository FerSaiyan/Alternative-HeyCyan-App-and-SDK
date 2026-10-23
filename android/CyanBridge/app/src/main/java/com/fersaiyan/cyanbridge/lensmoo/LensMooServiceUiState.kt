package com.fersaiyan.cyanbridge.lensmoo

/** Device status only; never contains owner credentials, transcript, or model output. */
data class LensMooServiceUiState(
    val running:Boolean=false,
    val ready:Boolean=false,
    val busy:Boolean=false,
    val batteryPercent:Int?=null,
    val message:String="",
)

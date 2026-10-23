package com.fersaiyan.cyanbridge.lensmoo

internal object LensMooBattery {
    fun percent(format:Int,data:ByteArray):Int {
        require(format==0 && data.size>=2) { "Invalid battery response format" }
        val percent=data[1].toInt() and 255
        require(percent<=100) { "Invalid battery percentage" }
        return percent
    }
}

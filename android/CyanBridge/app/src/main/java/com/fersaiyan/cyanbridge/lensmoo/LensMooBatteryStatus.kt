package com.fersaiyan.cyanbridge.lensmoo

internal class LensMooBatteryStatus {
    data class Reading(val percent: Int, val elapsedMillis: Long, val wallMillis: Long)
    private var reading: Reading? = null
    fun update(percent: Int, elapsedMillis: Long, wallMillis: Long) {
        require(percent in 0..100)
        reading=Reading(percent,elapsedMillis,wallMillis)
    }
    fun latest(): Reading? = reading
    fun isFresh(nowElapsedMillis: Long, connected: Boolean): Boolean {
        val current=reading ?: return false
        return connected && nowElapsedMillis-current.elapsedMillis in 0..45_000L
    }
}

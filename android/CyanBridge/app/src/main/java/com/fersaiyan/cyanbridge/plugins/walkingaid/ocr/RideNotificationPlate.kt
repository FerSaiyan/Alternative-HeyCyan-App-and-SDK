package com.fersaiyan.cyanbridge.plugins.walkingaid.ocr

import android.os.SystemClock

/**
 * The plate of the ride the wearer is waiting for, taken from the Uber/99 app's own notification ("…ABC1D23…"), so
 * Walking Aid's ride mode can check cars without being told. Read on the phone, kept in memory only (never logged,
 * stored or sent), and forgotten after [MAX_AGE_MS]. Needs the notification access CyanBridge already asks for.
 */
object RideNotificationPlate {
    private val RIDE_APPS = setOf("com.ubercab", "com.ubercab.uberlite", "com.taxis99", "br.com.easytaxi", "com.cabify.rider", "com.indriver")
    private const val MAX_AGE_MS = 60 * 60_000L

    @Volatile private var plate: String? = null
    @Volatile private var seenAtMs = 0L

    fun onNotification(packageName: String, vararg texts: String?) {
        if (packageName !in RIDE_APPS) return
        val found = texts.firstNotNullOfOrNull { it?.let(WalkingAidGoalParser::plateIn) } ?: return
        plate = found
        seenAtMs = SystemClock.elapsedRealtime()
    }

    fun current(): String? = plate?.takeIf { SystemClock.elapsedRealtime() - seenAtMs < MAX_AGE_MS }
}

package com.fersaiyan.cyanbridge.shared.platform

import java.util.TimeZone

actual fun platformCurrentTimeMillis(): Long = System.currentTimeMillis()

actual fun platformUtcOffsetMinutes(epochMillis: Long): Int =
    TimeZone.getDefault().getOffset(epochMillis) / 60_000

actual fun platformElapsedRealtimeMillis(): Long = System.nanoTime() / 1_000_000L

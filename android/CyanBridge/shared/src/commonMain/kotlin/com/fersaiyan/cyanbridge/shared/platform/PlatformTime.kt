package com.fersaiyan.cyanbridge.shared.platform

expect fun platformCurrentTimeMillis(): Long

/** Offset of the device's time zone from UTC at [epochMillis], in minutes. */
expect fun platformUtcOffsetMinutes(epochMillis: Long): Int

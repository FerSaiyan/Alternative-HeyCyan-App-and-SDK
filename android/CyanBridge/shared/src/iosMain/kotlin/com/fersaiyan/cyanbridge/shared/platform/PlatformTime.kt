package com.fersaiyan.cyanbridge.shared.platform

import platform.Foundation.NSDate
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSTimeZone
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.localTimeZone

actual fun platformCurrentTimeMillis(): Long =
    ((NSDate().timeIntervalSinceReferenceDate + 978307200.0) * 1000.0).toLong()

actual fun platformUtcOffsetMinutes(epochMillis: Long): Int =
    (NSTimeZone.localTimeZone.secondsFromGMTForDate(NSDate.dateWithTimeIntervalSince1970(epochMillis / 1000.0)) / 60).toInt()

/** Uptime: iOS has no public boot clock that counts sleep, which the short assistant windows do not need. */
actual fun platformElapsedRealtimeMillis(): Long = (NSProcessInfo.processInfo.systemUptime * 1000.0).toLong()

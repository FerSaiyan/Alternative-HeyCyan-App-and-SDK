package com.fersaiyan.cyanbridge.shared.platform

import android.util.Log
import com.fersaiyan.cyanbridge.shared.diagnostics.DiagnosticEvents
import com.fersaiyan.cyanbridge.shared.diagnostics.DiagnosticLevel

/** Logs to android.util.Log; warnings and errors also go to the support-report event buffer. */
actual object PlatformLogger {
    actual fun d(tag: String, message: String) { Log.d(tag, message) }
    actual fun i(tag: String, message: String) { Log.i(tag, message) }
    actual fun w(tag: String, message: String) = w(tag, message, null)
    actual fun w(tag: String, message: String, throwable: Throwable?) {
        Log.w(tag, message, throwable)
        DiagnosticEvents.fromLog(tag, DiagnosticLevel.WARN, message, throwable)
    }
    actual fun e(tag: String, message: String) = e(tag, message, null)
    actual fun e(tag: String, message: String, throwable: Throwable?) {
        Log.e(tag, message, throwable)
        DiagnosticEvents.fromLog(tag, DiagnosticLevel.ERROR, message, throwable)
    }
}

package com.fersaiyan.cyanbridge.shared.platform

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileHandle
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSLock
import platform.Foundation.NSLog
import platform.Foundation.NSNumber
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Foundation.fileHandleForWritingAtPath
import platform.Foundation.seekToEndOfFile
import platform.Foundation.writeData

actual object PlatformLogger {
    actual fun d(tag: String, message: String) = log("DEBUG", tag, message)
    actual fun i(tag: String, message: String) = log("INFO", tag, message)
    actual fun w(tag: String, message: String) = log("WARN", tag, message)
    actual fun w(tag: String, message: String, throwable: Throwable?) {
        log("WARN", tag, "$message (${throwable?.message ?: "unknown"})")
    }
    actual fun e(tag: String, message: String) = log("ERROR", tag, message)
    actual fun e(tag: String, message: String, throwable: Throwable?) {
        log("ERROR", tag, "$message (${throwable?.message ?: "unknown"})")
    }

    private const val MAX_BUFFERED_LINES = 500
    private const val LOG_FILE_NAME = "cyanbridge-debug.log"
    private const val MAX_LOG_FILE_BYTES = 2L * 1024 * 1024
    private val recentLines = ArrayDeque<String>()
    private val lock = NSLock()
    private var logFile: NSFileHandle? = null
    private var logFileOpened = false
    private val timeFormatter = NSDateFormatter().apply { dateFormat = "HH:mm:ss.SSS" }

    /** Most recent log lines, attached to debug-log reports from Settings. */
    fun recentLogs(): String {
        lock.lock()
        try {
            return recentLines.joinToString("\n")
        } finally {
            lock.unlock()
        }
    }

    // Kotlin/Native passes String varargs to NSLog as raw pointers (SIGSEGV on launch),
    // so format in Kotlin and hand NSLog a single escaped format string.
    private fun log(level: String, tag: String, message: String) {
        val line = "[$level] $tag: $message"
        NSLog(line.replace("%", "%%"))
        lock.lock()
        try {
            recentLines.addLast("${platformCurrentTimeMillis()} $line")
            while (recentLines.size > MAX_BUFFERED_LINES) recentLines.removeFirst()
            appendToFile("${timeFormatter.stringFromDate(NSDate())} $line\n")
        } finally {
            lock.unlock()
        }
    }

    /**
     * Mirrors logs to Documents/cyanbridge-debug.log so hardware test sessions can be
     * pulled with `xcrun devicectl device copy from` without a debugger attached.
     */
    @OptIn(BetaInteropApi::class)
    private fun appendToFile(text: String) {
        if (!logFileOpened) {
            logFileOpened = true
            logFile = runCatching { openLogFile() }.getOrNull()
        }
        val data = NSString.create(string = text).dataUsingEncoding(NSUTF8StringEncoding) ?: return
        runCatching { logFile?.writeData(data) }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun openLogFile(): NSFileHandle? {
        val documents = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true)
            .firstOrNull() as? String ?: return null
        val path = "$documents/$LOG_FILE_NAME"
        val fileManager = NSFileManager.defaultManager
        val size = (fileManager.attributesOfItemAtPath(path, null)?.get(NSFileSize) as? NSNumber)?.longLongValue ?: 0L
        if (size > MAX_LOG_FILE_BYTES) fileManager.removeItemAtPath(path, null)
        if (!fileManager.fileExistsAtPath(path)) fileManager.createFileAtPath(path, null, null)
        return NSFileHandle.fileHandleForWritingAtPath(path)?.also { it.seekToEndOfFile() }
    }
}

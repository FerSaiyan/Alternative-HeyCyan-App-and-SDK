package com.fersaiyan.cyanbridge.devices.metarayban

import com.meta.wearable.dat.core.types.PermissionError
import kotlinx.coroutines.delay

internal sealed interface MetaCameraPermissionCheck {
    data object Granted : MetaCameraPermissionCheck
    data object Denied : MetaCameraPermissionCheck
    data class Failed(val error: PermissionError, val message: String) : MetaCameraPermissionCheck
}

/** Registration/discovery can precede the DAT connection. Retry transport errors, never denial. */
internal suspend fun checkMetaCameraPermissionWithRetry(
    attempts: Int = 8,
    read: suspend () -> MetaCameraPermissionCheck,
): MetaCameraPermissionCheck {
    require(attempts > 0)
    repeat(attempts) { attempt ->
        val result = read()
        val reconnecting = result is MetaCameraPermissionCheck.Failed && result.error in setOf(
            PermissionError.NO_DEVICE, PermissionError.NO_DEVICE_WITH_CONNECTION, PermissionError.CONNECTION_ERROR,
        )
        if (!reconnecting || attempt == attempts - 1) return result
        delay(500)
    }
    error("No permission result")
}

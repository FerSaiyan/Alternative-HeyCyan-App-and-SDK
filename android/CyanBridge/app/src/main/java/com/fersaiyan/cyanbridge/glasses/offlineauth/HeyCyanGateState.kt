package com.fersaiyan.cyanbridge.glasses.offlineauth

/**
 * Last-seen state of the HeyCyan post-update command gate, for support reports: the cmd-47
 * capability byte, the device-auth / bind outcomes of the last offline-unlock run, the last gated
 * command that got the `03` NAK, and the terminal unlock result. Cleared on disconnect, like
 * [com.fersaiyan.cyanbridge.glasses.GlassesWifiChipInfo].
 */
object HeyCyanGateState {

    @Volatile var capabilityByte: Int? = null
        private set

    @Volatile var deviceAuthOk: Boolean? = null
        private set

    @Volatile var bindOk: Boolean? = null
        private set

    @Volatile var lastNakCommand: Int? = null
        private set

    @Volatile var lastUnlockResult: OfflineUnlockResult? = null
        private set

    fun recordRun(capabilityByte: Int?, deviceAuthOk: Boolean?, bindOk: Boolean?, result: OfflineUnlockResult) {
        capabilityByte?.let { this.capabilityByte = it }
        this.deviceAuthOk = deviceAuthOk
        this.bindOk = bindOk
        this.lastUnlockResult = result
    }

    fun recordNak(cmdId: Int) {
        lastNakCommand = cmdId
    }

    fun clear() {
        capabilityByte = null
        deviceAuthOk = null
        bindOk = null
        lastNakCommand = null
        lastUnlockResult = null
    }

    /** Diagnostics key/value pairs (only the fields that have been observed). */
    fun diagnostics(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        capabilityByte?.let { out["gate_capability_byte"] = "0x%02X".format(it) }
        deviceAuthOk?.let { out["gate_device_auth"] = if (it) "ok" else "fail" }
        bindOk?.let { out["gate_bind"] = if (it) "ok" else "fail" }
        lastNakCommand?.let { out["gate_last_nak_cmd"] = "0x%02X".format(it) }
        lastUnlockResult?.let { out["gate_last_unlock_result"] = it.name }
        return out
    }
}

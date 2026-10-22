package com.fersaiyan.cyanbridge.shared.devices

enum class DeviceClass {
    HEY_CYAN,
    EYEVUE,
    TUNEBUDS,
    MOYOUNG_W620,
    MENTRA_LIVE,
    MEIZU_MYVU,
    GENERIC_AUDIO,
    UNKNOWN;

    fun displayName(): String = when (this) {
        HEY_CYAN -> "HeyCyan"
        EYEVUE -> "Eyevue"
        TUNEBUDS -> "TuneBuds / AB Mate"
        MOYOUNG_W620 -> "MoYoung / W620"
        MENTRA_LIVE -> "Mentra Live"
        MEIZU_MYVU -> "Meizu MYVU / Star Air"
        GENERIC_AUDIO -> "Earbuds / Audio-only glasses"
        UNKNOWN -> "Unknown"
    }
}

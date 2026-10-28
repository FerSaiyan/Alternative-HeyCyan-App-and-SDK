package com.fersaiyan.cyanbridge.shared.devices

enum class DeviceClass {
    HEY_CYAN,
    EYEVUE,
    TUNEBUDS,
    MOYOUNG_W620,
    META_RAYBAN,
    MENTRA_LIVE,
    MEIZU_MYVU,
    MEMOMIND,

    // Display glasses driven by the Mentra Bluetooth SDK (see devices/mentra/MentraSdkGlasses).
    EVEN_G1,
    EVEN_G2,
    MENTRA_DISPLAY,
    NIMO,
    XINGYI_AR99,
    GENERIC_AUDIO,
    UNKNOWN;

    fun displayName(): String = when (this) {
        HEY_CYAN -> "HeyCyan"
        EYEVUE -> "Eyevue"
        TUNEBUDS -> "TuneBuds / AB Mate"
        MOYOUNG_W620 -> "MoYoung / W620"
        META_RAYBAN -> "Meta Rayban"
        MENTRA_LIVE -> "Mentra Live"
        MEIZU_MYVU -> "Meizu MYVU / Star Air"
        MEMOMIND -> "MemoMind"
        EVEN_G1 -> "Even Realities G1"
        EVEN_G2 -> "Even Realities G2"
        MENTRA_DISPLAY -> "Mentra Display"
        NIMO -> "NIMO"
        XINGYI_AR99 -> "Xingyi AR99"
        GENERIC_AUDIO -> "Earbuds / Audio-only glasses"
        UNKNOWN -> "Unknown"
    }
}

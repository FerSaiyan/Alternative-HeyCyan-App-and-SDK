package com.fersaiyan.cyanbridge.plugins.walkingaid

import com.fersaiyan.cyanbridge.shared.devices.DeviceClass

enum class WalkingAidVideoMode {
    PERIODIC_PHOTOS, EYEVUE_VIDEO, META_VIDEO;

    fun supports(device: DeviceClass, sdk: Int): Boolean = when (this) {
        PERIODIC_PHOTOS -> true
        EYEVUE_VIDEO -> device == DeviceClass.EYEVUE && sdk >= 29
        META_VIDEO -> device == DeviceClass.META_RAYBAN
    }
}

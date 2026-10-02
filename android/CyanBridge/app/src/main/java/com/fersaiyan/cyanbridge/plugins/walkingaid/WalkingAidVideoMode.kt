package com.fersaiyan.cyanbridge.plugins.walkingaid

import com.fersaiyan.cyanbridge.shared.devices.DeviceClass

enum class WalkingAidVideoMode {
    PERIODIC_PHOTOS, HEYCYAN_VIDEO, EYEVUE_VIDEO, META_VIDEO;

    fun supports(device: DeviceClass, sdk: Int): Boolean = when (this) {
        PERIODIC_PHOTOS -> true
        HEYCYAN_VIDEO -> device == DeviceClass.HEY_CYAN
        EYEVUE_VIDEO -> device == DeviceClass.EYEVUE && sdk >= 29
        META_VIDEO -> device == DeviceClass.META_RAYBAN
    }
}

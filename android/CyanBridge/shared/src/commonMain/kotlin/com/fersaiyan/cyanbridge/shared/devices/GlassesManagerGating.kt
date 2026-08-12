package com.fersaiyan.cyanbridge.shared.devices

object GlassesManagerGating {

    enum class Action {
        MEETING_CAPTURE,
        STATUS_BATTERY,
        STATUS_STORAGE,
        HEY_CYAN_EXTRAS,
        META_RAYBAN_CONTROLS,
        META_RAYBAN_REGISTRATION,
        MEIZU_MYVU_CONTROLS,
        EYEVUE_CONTROLS,
        TUNEBUDS_CONTROLS,
        CAPTURE_SETTINGS,
        AI_WAKE_WORD_ROUTING,
        ADVANCED_CONTROLS,
        ADVANCED_LOCAL_AGENT,
        ADVANCED_DEVICE_INFO,
        ADVANCED_DEVICE_VOLUME,
        ADVANCED_IMAGE_QUALITY,
        ADVANCED_DEVELOPER_TOOLS,
        ADVANCED_OTA,
        WIFI_ADB_DEBUG,
    }

    data class UiModel(
        val visibleActions: Set<Action>,
    ) {
        fun isVisible(action: Action): Boolean = visibleActions.contains(action)
    }

    fun uiModel(profile: DeviceProfile?): UiModel = UiModel(visibleActions(profile))

    fun visibleActions(profile: DeviceProfile?): Set<Action> {
        val selected = profile?.selectedClass ?: DeviceClass.UNKNOWN
        return visibleActions(selected)
    }

    fun visibleActions(deviceClass: DeviceClass): Set<Action> {
        val base = linkedSetOf(Action.MEETING_CAPTURE)
        when (deviceClass) {
            DeviceClass.HEY_CYAN -> {
                base.add(Action.HEY_CYAN_EXTRAS)
                base.add(Action.STATUS_BATTERY)
                base.add(Action.STATUS_STORAGE)
                base.addAll(heyCyanAdvancedActions)
                base.add(Action.CAPTURE_SETTINGS)
                base.add(Action.AI_WAKE_WORD_ROUTING)
                base.add(Action.WIFI_ADB_DEBUG)
            }
            DeviceClass.META_RAYBAN -> {
                base.add(Action.META_RAYBAN_CONTROLS)
                base.add(Action.META_RAYBAN_REGISTRATION)
            }
            DeviceClass.MEIZU_MYVU -> {
                base.add(Action.MEIZU_MYVU_CONTROLS)
                base.add(Action.STATUS_BATTERY)
            }
            DeviceClass.EYEVUE -> {
                base.add(Action.EYEVUE_CONTROLS)
                base.add(Action.STATUS_BATTERY)
                base.add(Action.STATUS_STORAGE)
                base.addAll(heyCyanAdvancedActions)
                base.add(Action.CAPTURE_SETTINGS)
                base.add(Action.AI_WAKE_WORD_ROUTING)
            }
            DeviceClass.TUNEBUDS -> {
                base.add(Action.TUNEBUDS_CONTROLS)
                base.add(Action.STATUS_BATTERY)
                base.add(Action.STATUS_STORAGE)
                base.add(Action.ADVANCED_CONTROLS)
                base.add(Action.ADVANCED_LOCAL_AGENT)
                base.add(Action.ADVANCED_DEVICE_INFO)
            }
            else -> {}
        }
        return base
    }

    private val heyCyanAdvancedActions = setOf(
        Action.ADVANCED_CONTROLS,
        Action.ADVANCED_LOCAL_AGENT,
        Action.ADVANCED_DEVICE_INFO,
        Action.ADVANCED_DEVICE_VOLUME,
        Action.ADVANCED_IMAGE_QUALITY,
        Action.ADVANCED_DEVELOPER_TOOLS,
        Action.ADVANCED_OTA,
    )
}

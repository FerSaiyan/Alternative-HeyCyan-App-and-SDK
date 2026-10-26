package com.fersaiyan.cyanbridge.shared.network.session

import com.fersaiyan.cyanbridge.shared.devices.DeviceClass

/** What a workflow must do with its glasses link when that device's Bluetooth link drops. */
enum class BleLossLinkAction {
    /** Release the link immediately; the next claim first clears any stale group. */
    ABANDON,

    /** Keep the link until its confirmed teardown finishes (the workflow drives the teardown). */
    KEEP_CONFIRMED_TEARDOWN,

    /** The Bluetooth loss belongs to another device family. */
    NOT_AFFECTED,
}

/**
 * Workflows that can own the phone's local link to the glasses (Wi-Fi Direct group, glasses
 * access point and the process network binding that goes with them). Only one may own it.
 */
enum class GlassesLinkClient(
    val label: String,
    val device: DeviceClass,
    private val keepsTeardownAfterBleLoss: Boolean = false,
) {
    HEYCYAN_MEDIA_SYNC("HeyCyan media sync", DeviceClass.HEY_CYAN),

    /** The Wi-Fi OTA stage must confirm group removal before its BLE DFU stage may run. */
    HEYCYAN_OTA("HeyCyan firmware update", DeviceClass.HEY_CYAN, keepsTeardownAfterBleLoss = true),
    HEYCYAN_LIVE_PREVIEW("HeyCyan live preview", DeviceClass.HEY_CYAN),
    HEYCYAN_WIFI_ADB("HeyCyan Wi-Fi ADB debug", DeviceClass.HEY_CYAN),
    EYEVUE_WIFI("Eyevue Wi-Fi transport", DeviceClass.EYEVUE),
    TUNEBUDS_WIFI_DIRECT("TuneBuds Wi-Fi Direct transport", DeviceClass.TUNEBUDS),

    /** The phone joins the glasses' own hotspot (access point, no group) to download the gallery. */
    MENTRA_GALLERY("Mentra Live gallery download", DeviceClass.MENTRA_LIVE),
    ;

    fun onBleLoss(lostDevice: DeviceClass): BleLossLinkAction = when {
        lostDevice != device -> BleLossLinkAction.NOT_AFFECTED
        keepsTeardownAfterBleLoss -> BleLossLinkAction.KEEP_CONFIRMED_TEARDOWN
        else -> BleLossLinkAction.ABANDON
    }
}

package com.fersaiyan.cyanbridge.shared.plugins

import com.fersaiyan.cyanbridge.bridge.core.GlassesCapability
import com.fersaiyan.cyanbridge.shared.devices.DeviceClass
import com.fersaiyan.cyanbridge.shared.devices.GlassesFamilyProfile

/** Third-party plugin ecosystems the Plugins page can offer next to CyanBridge's native plugins. */
enum class PluginEcosystem {
    /** MemoMind Web plugins (`.mmpkg`), hosted in CyanBridge's sandbox; MemoMind glasses only. */
    MEMOMIND_WEB_PLUGINS,

    /** Mentra miniapps (miniapp.json + bundle), run by CyanBridge's miniapp host. */
    MENTRA_MINIAPPS,
}

/**
 * One ecosystem section of the Plugins page. [enabled] is the experimental switch (null for
 * ecosystems without one); [installedCount] is shown when known.
 */
data class PluginEcosystemSection(
    val ecosystem: PluginEcosystem,
    val enabled: Boolean? = null,
    val installedCount: Int? = null,
)

/** Which ecosystem sections the Plugins page shows for the selected glasses. */
object PluginEcosystems {
    /**
     * MemoMind Web plugins need MemoMind glasses (their scenes and events are MemoMind's
     * protocol). Mentra miniapps run on any glasses with a display, because CyanBridge maps
     * `display.render` onto the shared display bridge, and on Mentra families (Mentra Live has
     * no display, but its microphone and button still serve display-optional miniapps).
     */
    fun sectionsFor(profile: GlassesFamilyProfile): List<PluginEcosystem> = buildList {
        if (profile.deviceClass == DeviceClass.MEMOMIND) add(PluginEcosystem.MEMOMIND_WEB_PLUGINS)
        if (runsMentraMiniapps(profile)) add(PluginEcosystem.MENTRA_MINIAPPS)
    }

    fun runsMentraMiniapps(profile: GlassesFamilyProfile): Boolean =
        isMentraFamily(profile.deviceClass) || profile.supports(GlassesCapability.TEXT_DISPLAY)

    /** Families paired through the Mentra Bluetooth SDK (their class names start with MENTRA). */
    fun isMentraFamily(deviceClass: DeviceClass): Boolean = deviceClass.name.startsWith("MENTRA")
}

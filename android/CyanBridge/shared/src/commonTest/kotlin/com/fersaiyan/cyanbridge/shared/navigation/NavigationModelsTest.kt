package com.fersaiyan.cyanbridge.shared.navigation

import com.fersaiyan.cyanbridge.shared.icons.AppIcon
import kotlin.test.Test
import kotlin.test.assertEquals

class NavigationModelsTest {
    @Test
    fun bottomBarDestinationsKeepTheirOrderLabelsAndIcons() {
        assertEquals(
            listOf(
                AppDestination.GLASSES to ("Glasses" to AppIcon.Glasses),
                AppDestination.CHATS to ("Chats" to AppIcon.Chat),
                AppDestination.MEDIA to ("Media" to AppIcon.Recordings),
                AppDestination.PLUGINS to ("Plugins" to AppIcon.Plugins),
                AppDestination.SETTINGS to ("Settings" to AppIcon.Settings),
            ),
            AppDestination.entries.map { it to (it.label to it.icon) },
        )
    }
}

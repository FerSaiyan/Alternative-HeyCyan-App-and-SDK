package com.fersaiyan.cyanbridge.shared.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * System back for shared sub-screens (chat thread, note editor...). The iOS host provides the
 * UIKit edge-swipe gesture; Android keeps its activities and leaves the no-op default.
 */
interface PlatformBackHandler {
    @Composable
    fun Handle(enabled: Boolean, onBack: () -> Unit)
}

private object NoPlatformBackHandler : PlatformBackHandler {
    @Composable
    override fun Handle(enabled: Boolean, onBack: () -> Unit) = Unit
}

val LocalPlatformBackHandler = staticCompositionLocalOf<PlatformBackHandler> { NoPlatformBackHandler }

/** Closes the current sub-screen on the platform back gesture, when the host supports one. */
@Composable
fun SystemBackHandler(enabled: Boolean = true, onBack: () -> Unit) {
    LocalPlatformBackHandler.current.Handle(enabled, onBack)
}

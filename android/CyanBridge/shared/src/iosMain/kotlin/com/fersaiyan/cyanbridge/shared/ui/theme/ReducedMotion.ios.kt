package com.fersaiyan.cyanbridge.shared.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import platform.UIKit.UIAccessibilityIsReduceMotionEnabled

/** iOS Settings > Accessibility > Motion > Reduce Motion. */
@Composable
internal actual fun rememberSystemReducedMotion(): Boolean = remember { UIAccessibilityIsReduceMotionEnabled() }

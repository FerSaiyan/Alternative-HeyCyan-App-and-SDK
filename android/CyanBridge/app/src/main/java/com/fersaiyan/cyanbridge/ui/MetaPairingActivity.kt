package com.fersaiyan.cyanbridge.ui

import com.fersaiyan.cyanbridge.ui.nav.AppRoute
import com.fersaiyan.cyanbridge.ui.nav.RouteTrampolineActivity

/**
 * The `cyanbridge://` link Meta AI opens after registration, kept under its old name; it hands
 * the callback to the Meta setup screen (single-task, so an open one gets it in onNewIntent).
 * Registration itself starts from MainActivity, which receives Meta AI's activity result.
 */
class MetaPairingActivity : RouteTrampolineActivity(AppRoute.META_PAIRING)

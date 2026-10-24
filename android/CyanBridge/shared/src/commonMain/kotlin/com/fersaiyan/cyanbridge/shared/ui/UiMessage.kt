package com.fersaiyan.cyanbridge.shared.ui

/** A short notice a screen model asks its host to show (a toast on Android). */
data class UiMessage(val text: String, val long: Boolean = false)

package com.fersaiyan.cyanbridge.shared.platform

actual fun platformLanguageTag(): String = java.util.Locale.getDefault().toLanguageTag()

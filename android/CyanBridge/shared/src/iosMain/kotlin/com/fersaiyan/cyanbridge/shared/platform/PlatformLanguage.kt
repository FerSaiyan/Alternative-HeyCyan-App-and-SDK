package com.fersaiyan.cyanbridge.shared.platform

import platform.Foundation.NSLocale
import platform.Foundation.preferredLanguages

/** Follows the in-app language choice too (IosAppLanguage sets AppleLanguages). */
actual fun platformLanguageTag(): String = (NSLocale.preferredLanguages.firstOrNull() as? String) ?: "en"

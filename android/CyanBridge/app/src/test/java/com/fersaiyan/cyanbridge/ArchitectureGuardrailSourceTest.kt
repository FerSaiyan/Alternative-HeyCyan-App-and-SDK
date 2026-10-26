package com.fersaiyan.cyanbridge

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ratchets that keep the refactor's structure from eroding. When one of these fails because code
 * moved the right way (fewer lines, fewer exceptions), lower the budget or drop the entry.
 * The Wi-Fi Direct and process-network owners are guarded in GlassesNetworkOwnershipSourceTest.
 */
class ArchitectureGuardrailSourceTest {
    private fun sources(root: String, extensions: Set<String> = setOf("kt", "java")): Map<String, String> =
        File(root).walkTopDown()
            .filter { it.isFile && it.extension in extensions }
            .associate { it.invariantSeparatorsPath to it.readText() }

    @Test
    fun mainActivityDoesNotGrow() {
        // Target: under 800 lines once the assistant and device flows leave the activity.
        val lines = File("src/main/java/com/fersaiyan/cyanbridge/MainActivity.kt").readLines().size
        assertTrue("MainActivity has $lines lines; the budget is $MAIN_ACTIVITY_LINE_BUDGET", lines <= MAIN_ACTIVITY_LINE_BUDGET)
    }

    @Test
    fun vendorSdksStayOutOfUiOutsideTheDeviceAdapters() {
        val vendorImport = Regex("""^import (com\.oudmon|com\.glasses\.sdk|org\.jvcompress|com\.moyoung|com\.meta\.wearable|com\.mentra|com\.myvu)\.""", RegexOption.MULTILINE)
        val offenders = sources("src/main/java/com/fersaiyan/cyanbridge/ui")
            .filterKeys { !it.startsWith("src/main/java/com/fersaiyan/cyanbridge/ui/main/glasses/") }
            .filterValues { vendorImport.containsMatchIn(it) }
            .keys.map { it.removePrefix("src/main/java/com/fersaiyan/cyanbridge/") }
        assertEquals("New vendor SDK imports in ui/; go through a ui/main/glasses adapter", emptyList<String>(), offenders - UI_VENDOR_IMPORT_EXCEPTIONS)
    }

    @Test
    fun sharedCommonCodeHasNoJvmOrAndroidImports() {
        val platformImport = Regex("""^import (android|java)\.""", RegexOption.MULTILINE)
        val offenders = sources("../shared/src/commonMain", setOf("kt")).filterValues { platformImport.containsMatchIn(it) }.keys
        assertEquals(emptySet<String>(), offenders)
    }

    @Test
    fun sharedPreferencesAreOpenedOnlyByTheTypedPreferencesLayer() {
        val roots = listOf("src/main/java", "src/debug/java", "src/release/java", "../shared/src")
        val callers = roots.flatMap { sources(it).filterValues { text -> "getSharedPreferences(" in text }.keys }
        assertEquals("Read settings through shared/prefs definitions", emptyList<String>(), callers - SHARED_PREFERENCES_CALLERS)
    }

    private companion object {
        /** Lowered from 5,069 when the glasses questions moved to ai/questions (A3). */
        const val MAIN_ACTIVITY_LINE_BUDGET = 1860

        /** Files that imported a vendor SDK when this test was added; shrink, never grow. */
        val UI_VENDOR_IMPORT_EXCEPTIONS = setOf(
            "ui/AutoPairManager.kt",
            "ui/BluetoothReceiver.kt",
            "ui/CommunityPluginsDestination.kt",
            "ui/DeviceBindDestination.kt",
            "ui/MetaPairingDestination.kt",
            "ui/MyApplication.kt",
            "ui/MyBluetoothReceiver.kt",
            "ui/main/DeviceConnectionCoordinator.kt",
            "ui/main/HeyCyanSettingsController.kt",
            "ui/main/LegacyGlassesControlsBinder.kt",
            "ui/main/MetaRaybanUiController.kt",
            "ui/wifi/p2p/WifiP2pManagerSingleton.kt",
        )

        /** The Android preference backends plus the screens that still read keys directly. */
        val SHARED_PREFERENCES_CALLERS = setOf(
            "../shared/src/androidMain/kotlin/com/fersaiyan/cyanbridge/shared/platform/PlatformPreferences.kt",
            "../shared/src/androidMain/kotlin/com/fersaiyan/cyanbridge/shared/prefs/AndroidPrefs.kt",
            "src/main/java/com/fersaiyan/cyanbridge/ai/vision/ImageQuestionPreferences.kt",
            "src/main/java/com/fersaiyan/cyanbridge/privacy/LocalDataBackupManager.kt",
            "src/main/java/com/fersaiyan/cyanbridge/ui/OnboardingFeatureActivity.kt",
            "src/main/java/com/fersaiyan/cyanbridge/ui/SettingsDestination.kt",
            "src/main/java/com/fersaiyan/cyanbridge/ui/WelcomeActivity.kt",
        )
    }
}

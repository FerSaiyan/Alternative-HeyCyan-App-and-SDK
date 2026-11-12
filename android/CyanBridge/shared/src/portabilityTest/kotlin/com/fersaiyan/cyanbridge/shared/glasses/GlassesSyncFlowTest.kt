package com.fersaiyan.cyanbridge.shared.glasses

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Sync picker and Settings → Media sync offer three flows, labelled in every locale. */
class GlassesSyncFlowTest {
    @Test
    fun threeFlowsOnlyAdaptiveIsExperimentalAndUnknownStoredNamesAsk() {
        assertEquals(
            listOf(GlassesSyncFlow.OFFICIAL_HEYCYAN, GlassesSyncFlow.CUSTOM, GlassesSyncFlow.ADAPTIVE),
            GlassesSyncFlow.entries,
        )
        assertEquals(listOf(GlassesSyncFlow.ADAPTIVE), GlassesSyncFlow.entries.filter { it.experimental })
        // Log and support-report labels stay comparable with v2.3.0.
        assertEquals("Custom flow", GlassesSyncFlow.CUSTOM.label)
        assertEquals("Adaptive flow", GlassesSyncFlow.ADAPTIVE.label)
        GlassesSyncFlow.entries.forEach { assertEquals(it, GlassesSyncFlow.fromStoredName(it.name)) }
        listOf(null, "", "custom", "SMART", "LEGACY").forEach { assertNull(GlassesSyncFlow.fromStoredName(it)) }
    }

    @Test
    fun everyLocaleLabelsEveryFlowAndTheMediaSyncSetting() {
        val resources = File("src/commonMain/composeResources")
        val locales = resources.listFiles { file -> file.isDirectory && file.name.startsWith("values") }.orEmpty()
        assertEquals(9, locales.size, "locales: ${locales.map { it.name }}")
        val keys = GlassesSyncFlow.entries.flatMap { flow ->
            val key = when (flow) {
                GlassesSyncFlow.OFFICIAL_HEYCYAN -> "official"
                GlassesSyncFlow.CUSTOM -> "custom"
                GlassesSyncFlow.ADAPTIVE -> "adaptive"
            }
            listOf("sync_flow_${key}_title", "sync_flow_${key}_description")
        } + listOf(
            "sync_flow_title",
            "sync_flow_experimental",
            "settings_media_sync",
            "settings_media_sync_description",
            "settings_media_sync_ask",
            "settings_media_sync_hint",
        )
        locales.forEach { locale ->
            val text = File(locale, "strings_extra.xml").readText()
            keys.forEach { key ->
                val value = Regex("""<string name="$key">([^<]*)</string>""").find(text)?.groupValues?.get(1)
                assertTrue(!value.isNullOrBlank(), "${locale.name} is missing $key")
            }
        }
    }
}

package com.fersaiyan.cyanbridge.plugins.settings

import android.content.Context
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.fersaiyan.cyanbridge.R
import com.fersaiyan.cyanbridge.media.autocapture.AutoAudioCaptureService
import com.fersaiyan.cyanbridge.plugins.errandbrain.ErrandBrainService
import com.fersaiyan.cyanbridge.plugins.errandbrain.ErrandBrainStore
import com.fersaiyan.cyanbridge.plugins.handsfreetranslator.HandsFreeTranslatorService
import com.fersaiyan.cyanbridge.plugins.handsfreetranslator.HandsFreeTranslatorStore
import com.fersaiyan.cyanbridge.plugins.livecaptionrelay.LiveCaptionRelayService
import com.fersaiyan.cyanbridge.plugins.livecaptionrelay.LiveCaptionRelayStore
import com.fersaiyan.cyanbridge.plugins.meetingsparknotes.MeetingSparkNotesService
import com.fersaiyan.cyanbridge.plugins.meetingsparknotes.MeetingSparkNotesStore
import com.fersaiyan.cyanbridge.plugins.walkingaid.WalkingAidService
import com.fersaiyan.cyanbridge.shared.platform.PlatformPreferences
import com.fersaiyan.cyanbridge.shared.plugins.NativePluginIds
import com.fersaiyan.cyanbridge.shared.plugins.settings.NativePluginHost
import com.fersaiyan.cyanbridge.shared.prefs.PluginPrefs
import com.fersaiyan.cyanbridge.shared.prefs.PrefsFile
import com.fersaiyan.cyanbridge.shared.prefs.store

/** The Android services and history stores behind the native plugin settings screens. */
internal class AndroidNativePluginHost(private val context: Context) : NativePluginHost {
    override fun stopPlugin(pluginId: String) {
        when (pluginId) {
            NativePluginIds.WALKING_AID -> WalkingAidService.stop(context)
            NativePluginIds.MEETING_SPARK_NOTES -> MeetingSparkNotesService.deactivate(context)
            NativePluginIds.LIVE_CAPTION_RELAY -> LiveCaptionRelayService.stop(context)
            NativePluginIds.HANDS_FREE_TRANSLATOR -> HandsFreeTranslatorService.stop(context)
            NativePluginIds.ERRAND_BRAIN -> ErrandBrainService.stop(context)
            NativePluginIds.AUTO_AUDIO -> AutoAudioCaptureService.stop(context)
        }
    }

    override fun clearHistory(pluginId: String) {
        when (pluginId) {
            NativePluginIds.MEETING_SPARK_NOTES -> MeetingSparkNotesStore().clear(context)
            NativePluginIds.LIVE_CAPTION_RELAY -> LiveCaptionRelayStore().clear(context)
            NativePluginIds.HANDS_FREE_TRANSLATOR -> HandsFreeTranslatorStore().clear(context)
            NativePluginIds.ERRAND_BRAIN -> ErrandBrainStore().clear(context)
            else -> return
        }
        Toast.makeText(context, context.getString(R.string.compose_history_cleared), Toast.LENGTH_SHORT).show()
    }
}

/**
 * A native plugin settings ViewModel over the plugin's preference [file], the plugin catalog and
 * [AndroidNativePluginHost], all bound to the application context.
 */
internal inline fun <reified VM : ViewModel> ComponentActivity.nativePluginViewModel(
    file: PrefsFile,
    crossinline create: (store: PlatformPreferences, catalog: PlatformPreferences, host: NativePluginHost) -> VM,
): Lazy<VM> = viewModels {
    viewModelFactory {
        initializer {
            val context = applicationContext
            create(file.store(context), PluginPrefs.Community.store(context), AndroidNativePluginHost(context))
        }
    }
}

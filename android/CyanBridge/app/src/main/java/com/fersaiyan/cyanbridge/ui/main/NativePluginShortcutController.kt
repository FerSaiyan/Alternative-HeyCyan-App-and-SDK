package com.fersaiyan.cyanbridge.ui.main

import android.util.Log
import android.widget.Toast
import com.fersaiyan.cyanbridge.audio.MeetingCapturePrefs
import com.fersaiyan.cyanbridge.media.autocapture.AutoAudioCapturePrefs
import com.fersaiyan.cyanbridge.media.autocapture.AutoAudioCaptureService
import com.fersaiyan.cyanbridge.plugins.PluginVoicePermissions
import com.fersaiyan.cyanbridge.plugins.autodiary.AutoDiaryService
import com.fersaiyan.cyanbridge.plugins.errandbrain.ErrandBrainPreferences
import com.fersaiyan.cyanbridge.plugins.errandbrain.ErrandBrainService
import com.fersaiyan.cyanbridge.plugins.handsfreetranslator.HandsFreeTranslatorPreferences
import com.fersaiyan.cyanbridge.plugins.handsfreetranslator.HandsFreeTranslatorService
import com.fersaiyan.cyanbridge.plugins.livecaptionrelay.LiveCaptionRelayPreferences
import com.fersaiyan.cyanbridge.plugins.livecaptionrelay.LiveCaptionRelayService
import com.fersaiyan.cyanbridge.plugins.localagent.LocalAgentPlugin
import com.fersaiyan.cyanbridge.plugins.meetingsparknotes.MeetingSparkNotesPreferences
import com.fersaiyan.cyanbridge.plugins.meetingsparknotes.MeetingSparkNotesService
import com.fersaiyan.cyanbridge.plugins.visualdiary.VisualDiaryPreferences
import com.fersaiyan.cyanbridge.plugins.visualdiary.VisualDiaryService
import com.fersaiyan.cyanbridge.plugins.walkingaid.WalkingAidPreferences
import com.fersaiyan.cyanbridge.plugins.walkingaid.WalkingAidService
import com.fersaiyan.cyanbridge.shared.plugins.NativePluginIds
import com.fersaiyan.cyanbridge.shared.plugins.NativePluginShortcutAction
import com.fersaiyan.cyanbridge.shared.plugins.NativePluginShortcutButton
import com.fersaiyan.cyanbridge.shared.plugins.NativePluginShortcutUiState
import com.fersaiyan.cyanbridge.ui.CommunityPluginPrefs
import com.fersaiyan.cyanbridge.ui.main.glasses.AndroidGlassesDevices

/** The native plugin shortcut card on the glasses tab and the plugin start/stop it drives. */
internal class NativePluginShortcutController(
    host: MainScreenHost,
    private val glasses: AndroidGlassesDevices,
    private val meetingCaptureUi: MeetingCaptureUiController,
    private val transportPermissions: GlassesTransportPermissions,
) : MainScreenController(host) {
    fun refreshNativePluginShortcutState() {
        syncLegacyNativePluginState()
        dashboardState = dashboardState.copy(
            nativePluginShortcut = buildNativePluginShortcutState(
                CommunityPluginPrefs.getGlassesTabShortcutPluginId(activity),
            ),
        )
    }

    private fun syncLegacyNativePluginState() {
        LocalAgentPlugin.syncNativePluginState(activity)
        CommunityPluginPrefs.setNativePluginEnabled(
            activity,
            NativePluginIds.AUTO_DIARY,
            AutoDiaryService.isEnabled(activity),
        )
        CommunityPluginPrefs.setNativePluginEnabled(
            activity,
            NativePluginIds.AUTO_AUDIO,
            AutoAudioCapturePrefs.isEnabled(activity),
        )
        CommunityPluginPrefs.setNativePluginEnabled(
            activity,
            NativePluginIds.VISUAL_DIARY,
            VisualDiaryPreferences.isEnabled(activity),
        )
    }

    private fun buildNativePluginShortcutState(pluginId: String?): NativePluginShortcutUiState? {
        val id = pluginId ?: return null
        val definition = when (id) {
            NativePluginIds.LOCAL_AGENT -> NativePluginShortcutUiState(
                id = id,
                title = "Local Agent",
                description = "Run private phone automation with approval controls for risky actions.",
                isEnabled = LocalAgentPlugin.isEnabled(activity),
                buttons = listOf(
                    NativePluginShortcutButton(NativePluginShortcutAction.START, "Enable automation"),
                    NativePluginShortcutButton(NativePluginShortcutAction.STOP, "Disable automation"),
                ),
            )
            NativePluginIds.MEETING_SPARK_NOTES -> NativePluginShortcutUiState(
                id = id,
                title = "Meeting Spark Notes",
                description = "Capture a live meeting transcript and turn it into concise notes.",
                isEnabled = MeetingCapturePrefs.getState(activity).isRecording,
                buttons = listOf(
                    NativePluginShortcutButton(NativePluginShortcutAction.START, "Start capture"),
                    NativePluginShortcutButton(NativePluginShortcutAction.STOP, "Stop capture"),
                    NativePluginShortcutButton(NativePluginShortcutAction.SUMMARIZE, "Summarize"),
                ),
            )
            NativePluginIds.LIVE_CAPTION_RELAY -> NativePluginShortcutUiState(
                id = id,
                title = "Live Caption Relay",
                description = "Caption live speech from the phone or glasses microphone.",
                isEnabled = CommunityPluginPrefs.isNativePluginEnabled(activity, id),
                buttons = listOf(
                    NativePluginShortcutButton(NativePluginShortcutAction.START, "Start captions"),
                    NativePluginShortcutButton(NativePluginShortcutAction.STOP, "Stop captions"),
                ),
            )
            NativePluginIds.HANDS_FREE_TRANSLATOR -> NativePluginShortcutUiState(
                id = id,
                title = "Hands-Free Translator",
                description = "Translate live speech while the translator service is enabled.",
                isEnabled = CommunityPluginPrefs.isNativePluginEnabled(activity, id),
                buttons = listOf(
                    NativePluginShortcutButton(NativePluginShortcutAction.START, "Start translator"),
                    NativePluginShortcutButton(NativePluginShortcutAction.STOP, "Stop translator"),
                ),
            )
            NativePluginIds.ERRAND_BRAIN -> NativePluginShortcutUiState(
                id = id,
                title = "Errand Brain",
                description = "Listen for spoken tasks and reminders.",
                isEnabled = CommunityPluginPrefs.isNativePluginEnabled(activity, id),
                buttons = listOf(
                    NativePluginShortcutButton(NativePluginShortcutAction.START, "Start listening"),
                    NativePluginShortcutButton(NativePluginShortcutAction.STOP, "Stop listening"),
                ),
            )
            NativePluginIds.WALKING_AID -> NativePluginShortcutUiState(
                id = id,
                title = "Walking Aid",
                description = "Start or stop scene descriptions and obstacle warnings.",
                isEnabled = CommunityPluginPrefs.isNativePluginEnabled(activity, id),
                buttons = listOf(
                    NativePluginShortcutButton(NativePluginShortcutAction.START, "Start walking aid"),
                    NativePluginShortcutButton(NativePluginShortcutAction.STOP, "Stop walking aid"),
                ),
            )
            NativePluginIds.AUTO_DIARY -> NativePluginShortcutUiState(
                id = id,
                title = "AutoDiary",
                description = "Collect screen context and turn it into daily facts, bullets, and summaries.",
                isEnabled = CommunityPluginPrefs.isNativePluginEnabled(activity, id),
                buttons = listOf(
                    NativePluginShortcutButton(NativePluginShortcutAction.START, "Start diary"),
                    NativePluginShortcutButton(NativePluginShortcutAction.STOP, "Stop diary"),
                    NativePluginShortcutButton(NativePluginShortcutAction.SUMMARIZE, "Summarize today"),
                ),
            )
            NativePluginIds.AUTO_AUDIO -> NativePluginShortcutUiState(
                id = id,
                title = "Auto Audio",
                description = "Record glasses audio in resilient loops and sync it periodically.",
                isEnabled = CommunityPluginPrefs.isNativePluginEnabled(activity, id),
                buttons = listOf(
                    NativePluginShortcutButton(NativePluginShortcutAction.START, "Start audio loop"),
                    NativePluginShortcutButton(NativePluginShortcutAction.STOP, "Stop audio loop"),
                    NativePluginShortcutButton(NativePluginShortcutAction.SYNC, "Sync now"),
                ),
            )
            NativePluginIds.VISUAL_DIARY -> NativePluginShortcutUiState(
                id = id,
                title = "Visual Diary",
                description = "Capture a glasses scene and append a Gemma-generated diary note.",
                isEnabled = CommunityPluginPrefs.isNativePluginEnabled(activity, id),
                buttons = listOf(
                    NativePluginShortcutButton(NativePluginShortcutAction.START, "Start visual diary"),
                    NativePluginShortcutButton(NativePluginShortcutAction.STOP, "Stop visual diary"),
                    NativePluginShortcutButton(NativePluginShortcutAction.CAPTURE, "Capture scene"),
                ),
            )
            else -> null
        }
        return definition
    }

    fun runNativePluginShortcut(action: NativePluginShortcutAction) {
        val pluginId = CommunityPluginPrefs.getGlassesTabShortcutPluginId(activity) ?: return
        if (pluginId == NativePluginIds.MEETING_SPARK_NOTES) {
            when (action) {
                NativePluginShortcutAction.START -> {
                    Log.i("NativePluginShortcut", "Meeting Spark Notes START -> MeetingCaptureService")
                    meetingCaptureUi.startMeetingCaptureFromUi()
                }
                NativePluginShortcutAction.STOP -> {
                    Log.i("NativePluginShortcut", "Meeting Spark Notes STOP -> MeetingCaptureService")
                    meetingCaptureUi.stopMeetingCaptureFromUi()
                }
                NativePluginShortcutAction.SUMMARIZE -> MeetingSparkNotesService.summarize(activity)
                else -> Unit
            }
        } else when (action) {
            NativePluginShortcutAction.START -> startNativePlugin(pluginId)
            NativePluginShortcutAction.STOP -> stopNativePlugin(pluginId)
            NativePluginShortcutAction.CAPTURE -> if (pluginId == NativePluginIds.VISUAL_DIARY) {
                VisualDiaryService.captureNow(activity)
            }
            NativePluginShortcutAction.SYNC -> if (pluginId == NativePluginIds.AUTO_AUDIO) {
                binding.btnDataDownload.performClick()
            }
            NativePluginShortcutAction.SUMMARIZE -> if (pluginId == NativePluginIds.AUTO_DIARY) {
                AutoDiaryService.summarize(activity)
            }
        }
        refreshNativePluginShortcutState()
    }

    private fun startNativePlugin(pluginId: String) {
        glasses.selectedProfile().unsupportedNativePlugins[pluginId]?.let { message ->
            Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
            return
        }
        val start = {
            when (pluginId) {
                NativePluginIds.AUTO_DIARY -> AutoDiaryService.enable(activity)
                NativePluginIds.VISUAL_DIARY -> VisualDiaryService.enable(activity)
                else -> {
                    CommunityPluginPrefs.setNativePluginEnabled(activity, pluginId, true)
                    when (pluginId) {
                        NativePluginIds.LOCAL_AGENT -> {
                            LocalAgentPlugin.setEnabled(activity, true)
                            Toast.makeText(
                                activity,
                                "Local Agent enabled. Complete Tasker and AutoInput setup in Plugins.",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                        NativePluginIds.MEETING_SPARK_NOTES -> {
                            MeetingSparkNotesPreferences.setEnabled(activity, true)
                            MeetingSparkNotesService.start(activity)
                        }
                        NativePluginIds.LIVE_CAPTION_RELAY -> {
                            LiveCaptionRelayPreferences.setEnabled(activity, true)
                            LiveCaptionRelayService.start(activity)
                        }
                        NativePluginIds.HANDS_FREE_TRANSLATOR -> {
                            HandsFreeTranslatorPreferences.setEnabled(activity, true)
                            HandsFreeTranslatorService.start(activity)
                        }
                        NativePluginIds.ERRAND_BRAIN -> {
                            ErrandBrainPreferences.setEnabled(activity, true)
                            ErrandBrainService.start(activity)
                        }
                        NativePluginIds.WALKING_AID -> {
                            WalkingAidPreferences.setEnabled(activity, true)
                            WalkingAidService.start(activity)
                        }
                        NativePluginIds.AUTO_AUDIO -> AutoAudioCaptureService.start(activity)
                    }
                }
            }
            refreshNativePluginShortcutState()
        }

        val cameraPluginGate = glasses.selected().cameraPluginGate
        if (cameraPluginGate != null &&
            pluginId in setOf(NativePluginIds.WALKING_AID, NativePluginIds.VISUAL_DIARY)
        ) {
            cameraPluginGate.runWhenCameraReady(start)
            return
        }

        if (pluginId == NativePluginIds.WALKING_AID ||
            pluginId == NativePluginIds.LOCAL_AGENT ||
            pluginId == NativePluginIds.AUTO_DIARY ||
            pluginId == NativePluginIds.VISUAL_DIARY
        ) {
            if (pluginId == NativePluginIds.WALKING_AID &&
                WalkingAidPreferences.getVideoMode(activity) == com.fersaiyan.cyanbridge.plugins.walkingaid.WalkingAidVideoMode.EYEVUE_VIDEO
            ) {
                transportPermissions.ensureGlassesTransportPermissions("Walking Aid video", start)
            } else start()
        } else {
            PluginVoicePermissions.ensure(activity, onGranted = start)
        }
    }

    private fun stopNativePlugin(pluginId: String) {
        when (pluginId) {
            NativePluginIds.AUTO_DIARY -> AutoDiaryService.disable(activity)
            NativePluginIds.VISUAL_DIARY -> VisualDiaryService.disable(activity)
            else -> {
                CommunityPluginPrefs.setNativePluginEnabled(activity, pluginId, false)
                when (pluginId) {
                    NativePluginIds.LOCAL_AGENT -> LocalAgentPlugin.stop(activity)
                    NativePluginIds.MEETING_SPARK_NOTES -> {
                        MeetingSparkNotesPreferences.setEnabled(activity, false)
                        MeetingSparkNotesService.stop(activity)
                    }
                    NativePluginIds.LIVE_CAPTION_RELAY -> {
                        LiveCaptionRelayPreferences.setEnabled(activity, false)
                        LiveCaptionRelayService.stop(activity)
                    }
                    NativePluginIds.HANDS_FREE_TRANSLATOR -> {
                        HandsFreeTranslatorPreferences.setEnabled(activity, false)
                        HandsFreeTranslatorService.stop(activity)
                    }
                    NativePluginIds.ERRAND_BRAIN -> {
                        ErrandBrainPreferences.setEnabled(activity, false)
                        ErrandBrainService.stop(activity)
                    }
                    NativePluginIds.WALKING_AID -> {
                        WalkingAidPreferences.setEnabled(activity, false)
                        WalkingAidService.stop(activity)
                    }
                    NativePluginIds.AUTO_AUDIO -> AutoAudioCaptureService.stop(activity)
                }
            }
        }
    }
}

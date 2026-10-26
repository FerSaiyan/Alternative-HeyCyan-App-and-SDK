package com.fersaiyan.cyanbridge.ui

import com.fersaiyan.cyanbridge.ui.nav.NavScreen
import com.fersaiyan.cyanbridge.ui.nav.AppRoute
import com.fersaiyan.cyanbridge.R
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import com.fersaiyan.cyanbridge.ui.nav.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.fersaiyan.cyanbridge.ai.image.ExternalAssistantAutomationSetupDestination
import com.fersaiyan.cyanbridge.ai.router.AiProviderPrefs
import com.fersaiyan.cyanbridge.devices.DeviceProfileStore
import com.fersaiyan.cyanbridge.devices.metarayban.MetaRaybanManager
import com.fersaiyan.cyanbridge.media.autocapture.AutoAudioCapturePrefs
import com.fersaiyan.cyanbridge.media.autocapture.AutoAudioCaptureService
import com.fersaiyan.cyanbridge.plugins.PluginVoicePermissions
import com.fersaiyan.cyanbridge.plugins.autoaudio.AutoAudioSettingsDestination
import com.fersaiyan.cyanbridge.plugins.autodiary.AutoDiarySettingsDestination
import com.fersaiyan.cyanbridge.plugins.errandbrain.ErrandBrainSettingsDestination
import com.fersaiyan.cyanbridge.plugins.handsfreetranslator.HandsFreeTranslatorSettingsDestination
import com.fersaiyan.cyanbridge.plugins.livecaptionrelay.LiveCaptionRelaySettingsDestination
import com.fersaiyan.cyanbridge.plugins.localagent.LocalAgentSettingsDestination
import com.fersaiyan.cyanbridge.plugins.meetingsparknotes.MeetingSparkNotesSettingsDestination
import com.fersaiyan.cyanbridge.plugins.settings.AndroidNativePluginHost
import com.fersaiyan.cyanbridge.plugins.visualdiary.VisualDiarySettingsDestination
import com.fersaiyan.cyanbridge.plugins.walkingaid.WalkingAidService
import com.fersaiyan.cyanbridge.plugins.walkingaid.WalkingAidSettingsDestination
import com.fersaiyan.cyanbridge.shared.devices.DeviceClass
import com.fersaiyan.cyanbridge.shared.navigation.AppDestination
import com.fersaiyan.cyanbridge.shared.plugins.CommunityPluginCardData
import com.fersaiyan.cyanbridge.shared.plugins.CommunityPluginCatalogParser
import com.fersaiyan.cyanbridge.shared.plugins.NativePluginCardData
import com.fersaiyan.cyanbridge.shared.plugins.NativePluginIds
import com.fersaiyan.cyanbridge.shared.plugins.settings.nativePluginEnabledPrefs
import com.fersaiyan.cyanbridge.shared.prefs.set
import com.fersaiyan.cyanbridge.shared.ui.UiMessage
import com.fersaiyan.cyanbridge.shared.ui.plugins.CommunityPluginsPlatform
import com.fersaiyan.cyanbridge.shared.ui.plugins.CommunityPluginsScreen
import com.fersaiyan.cyanbridge.shared.ui.plugins.CommunityPluginsViewModel
import com.fersaiyan.cyanbridge.tasker.TaskerProfileGuidance
import com.fersaiyan.cyanbridge.ui.main.openAppDestination
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.lang.ref.WeakReference

private val TASKER_FILE_SUFFIXES = listOf(".prj.xml", ".prf.xml", ".tsk.xml", ".scn.xml")

internal fun taskerDownloadFileName(sourceFileName: String, uniqueId: Long): String {
    val suffix = TASKER_FILE_SUFFIXES.firstOrNull { sourceFileName.endsWith(it, ignoreCase = true) }
        ?: ".prj.xml"
    val baseName = if (sourceFileName.endsWith(suffix, ignoreCase = true)) {
        sourceFileName.dropLast(suffix.length)
    } else {
        sourceFileName.removeSuffix(".xml")
    }
    return "${baseName}_$uniqueId$suffix"
}

internal fun requireValidTaskerProject(xml: String) {
    require(xml.length <= 1_000_000) { "The downloaded Tasker project is unexpectedly large" }
    val normalized = xml.removePrefix("\uFEFF").trimStart()
    require(normalized.startsWith("<TaskerData")) {
        "The server did not return a Tasker XML file"
    }
    require(Regex("<Project(?:\\s|>)").containsMatchIn(normalized)) {
        "The downloaded XML is not a Tasker project"
    }
}

class CommunityPluginsDestination : NavScreen() {

    private val viewModel by viewModels<CommunityPluginsViewModel> {
        viewModelFactory { initializer { CommunityPluginsViewModel(AndroidCommunityPluginsPlatform(applicationContext)) } }
    }
    private var pendingMetaCameraPlugin: String? = null
    private var pendingTaskerDownloadTitle: String? = null
    private var pendingTaskerDownloadUrl: String? = null
    private var pendingTaskerDownloadFileName: String? = null

    private val saveTaskerProfileLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/xml"),
    ) { uri ->
        val title = pendingTaskerDownloadTitle
        val link = pendingTaskerDownloadUrl
        val fileName = pendingTaskerDownloadFileName
        pendingTaskerDownloadTitle = null
        pendingTaskerDownloadUrl = null
        pendingTaskerDownloadFileName = null
        if (uri == null || title == null || link == null || fileName == null) return@registerForActivityResult
        saveTaskerProfile(title, link, fileName, uri)
    }

    private val metaWearablePermissionLauncher =
        registerForActivityResult(Wearables.RequestPermissionContract()) { result ->
            val pluginId = pendingMetaCameraPlugin
            pendingMetaCameraPlugin = null
            if (pluginId == null) return@registerForActivityResult
            if (result.getOrDefault(PermissionStatus.Denied) == PermissionStatus.Granted) {
                applyNativePluginToggle(pluginId, enabled = true)
            } else {
                val manager = MetaRaybanManager.getInstance(this)
                val detail = manager.reportExternalError(
                    "pluginCameraPermission",
                    "Meta camera permission was denied",
                )
                Toast.makeText(this, detail, Toast.LENGTH_LONG).show()
            }
        }

    /**
     * These integrations are intentionally not part of the native-plugin pool. Their feature
     * state/settings remain in CyanBridge, but Android observation/execution/scheduling requires
     * the matching user-imported Tasker profile.
     */
    private fun taskerIntegrationPool(): List<CommunityPluginCardData> = listOf(
        taskerIntegration(
            id = TASKER_AI_ID,
            title = "Gemini / ChatGPT Image Assistant",
            description = "Tasker + AutoInput adapter used by the AI image-question flow. The current bundle contains Gemini v3 and ChatGPT v1 and safely supports both profiles on the same phone.",
            fileName = "Tasker_AI.prj.xml",
        ),
        taskerIntegration(
            id = NativePluginIds.LOCAL_AGENT,
            title = "Local Agent",
            description = "Required Tasker + AutoInput observer/executor for CyanBridge Local Agent. CyanBridge still owns planning, approvals, memory and recovery.",
            fileName = "CyanBridge_LocalAgent_Tasker.prj.xml",
        ),
        taskerIntegration(
            id = NativePluginIds.AUTO_DIARY,
            title = "AutoDiary",
            description = "Tasker profile for periodic screen-memory capture and package exclusions. Daily facts, summaries, Memory Vault and RAG remain inside CyanBridge.",
            fileName = "CyanBridge_AutoDiary_Tasker.prj.xml",
        ),
        taskerIntegration(
            id = NativePluginIds.VISUAL_DIARY,
            title = "Visual Diary",
            description = "Tasker profile that owns the periodic trigger. CyanBridge still captures from the glasses, saves images, runs vision inference and writes visual memory.",
            fileName = "CyanBridge_VisualDiary_Tasker.prj.xml",
        ),
    )

    private fun taskerIntegration(
        id: String,
        title: String,
        description: String,
        fileName: String,
    ) = CommunityPluginCardData(
        id = id,
        title = title,
        author = "CyanBridge",
        description = description,
        badge = "Tasker",
        downloadsAll = 0,
        downloadsMonthly = 0,
        downloadsWeekly = 0,
        votesAll = 0,
        votesMonthly = 0,
        votesWeekly = 0,
        trendAll = 0,
        trendMonthly = 0,
        trendWeekly = 0,
        downloadUrl = "$TASKER_PROFILE_BASE_URL/$fileName",
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingTaskerDownloadTitle = savedInstanceState?.getString(STATE_PENDING_TASKER_TITLE)
        pendingTaskerDownloadUrl = savedInstanceState?.getString(STATE_PENDING_TASKER_URL)
        pendingTaskerDownloadFileName = savedInstanceState?.getString(STATE_PENDING_TASKER_FILE_NAME)
        viewModel.refreshNativePlugins()
        setContent {
            CommunityPluginsScreen(
                plugins = viewModel.communityPlugins,
                selectedWindow = viewModel.selectedWindow,
                isRefreshing = viewModel.isRefreshing,
                nativePlugins = viewModel.nativePlugins,
                taskerIntegrations = taskerIntegrationPool(),
                onOpenNativePluginSettings = ::openNativePluginSettings,
                onToggleNativePlugin = ::toggleNativePlugin,
                onDownloadTaskerIntegration = ::downloadTaskerIntegration,
                onOpenTaskerIntegrationSettings = ::openTaskerIntegrationSettings,
                onWatchTaskerTutorial = { TaskerProfileGuidance.openTutorial(this) },
                onWindowSelected = viewModel::onWindowSelected,
                onRefresh = viewModel::refresh,
                onOpenCommunityPlugin = ::openCommunityPlugin,
                onPublishPlugin = {
                    startActivity(AppRoute.PUBLISH_PLUGIN.intent(this))
                },
                onDestinationSelected = { if (it != AppDestination.PLUGINS) openAppDestination(it) },
            )
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_PENDING_TASKER_TITLE, pendingTaskerDownloadTitle)
        outState.putString(STATE_PENDING_TASKER_URL, pendingTaskerDownloadUrl)
        outState.putString(STATE_PENDING_TASKER_FILE_NAME, pendingTaskerDownloadFileName)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshNativePlugins()
    }

    private fun openTaskerIntegrationSettings(pluginId: String) {
        val intent = when (pluginId) {
            TASKER_AI_ID -> AppRoute.EXTERNAL_ASSISTANT_SETUP.intent(this)
            NativePluginIds.LOCAL_AGENT -> AppRoute.LOCAL_AGENT_SETTINGS.intent(this)
            NativePluginIds.AUTO_DIARY -> AppRoute.AUTO_DIARY_SETTINGS.intent(this)
            NativePluginIds.VISUAL_DIARY -> AppRoute.VISUAL_DIARY_SETTINGS.intent(this)
            else -> null
        }
        if (intent != null) startActivity(intent)
    }

    private fun openNativePluginSettings(pluginId: String) {
        val intent = when (pluginId) {
            NativePluginIds.WALKING_AID -> AppRoute.WALKING_AID_SETTINGS.intent(this)
            NativePluginIds.MEETING_SPARK_NOTES -> AppRoute.MEETING_SPARK_NOTES_SETTINGS.intent(this)
            NativePluginIds.LIVE_CAPTION_RELAY -> AppRoute.LIVE_CAPTION_RELAY_SETTINGS.intent(this)
            NativePluginIds.HANDS_FREE_TRANSLATOR -> AppRoute.HANDS_FREE_TRANSLATOR_SETTINGS.intent(this)
            NativePluginIds.ERRAND_BRAIN -> AppRoute.ERRAND_BRAIN_SETTINGS.intent(this)
            NativePluginIds.AUTO_AUDIO -> AppRoute.AUTO_AUDIO_SETTINGS.intent(this)
            else -> null
        }
        if (intent != null) startActivity(intent)
    }

    private fun toggleNativePlugin(pluginId: String, enabled: Boolean) {
        if (enabled && pluginId == NativePluginIds.AUTO_AUDIO && DeviceProfileStore.isMetaSelected(this)) {
            Toast.makeText(this, "Auto Audio is unavailable for Meta Ray-Ban devices", Toast.LENGTH_LONG).show()
            return
        }
        if (enabled &&
            DeviceProfileStore.isMetaSelected(this) &&
            pluginId == NativePluginIds.WALKING_AID
        ) {
            val manager = MetaRaybanManager.getInstance(this)
            if (!manager.isInitialized.value) manager.initialize()
            lifecycleScope.launch {
                if (!manager.awaitCameraReady()) {
                    val detail = manager.lastError.value
                        ?: "Register and connect a Meta camera before enabling $pluginId"
                    android.util.Log.e(
                        "CommunityPluginsDestination",
                        "Unable to enable Meta plugin=$pluginId: $detail\n${manager.diagnosticsSnapshot()}",
                    )
                    Toast.makeText(
                        this@CommunityPluginsDestination,
                        "Meta camera unavailable: $detail",
                        Toast.LENGTH_LONG,
                    ).show()
                    return@launch
                }
                manager.checkCameraPermission(
                    onGranted = { applyNativePluginToggle(pluginId, enabled = true) },
                    onRequestNeeded = {
                        pendingMetaCameraPlugin = pluginId
                        metaWearablePermissionLauncher.launch(Permission.CAMERA)
                    },
                    onError = { error ->
                        android.util.Log.e(
                            "CommunityPluginsDestination",
                            "Meta camera permission error for plugin=$pluginId: $error\n${manager.diagnosticsSnapshot()}",
                        )
                        Toast.makeText(
                            this@CommunityPluginsDestination,
                            "Meta camera permission error: $error",
                            Toast.LENGTH_LONG,
                        ).show()
                    },
                )
            }
            return
        }
        if (enabled && pluginId in VOICE_PLUGIN_IDS && !PluginVoicePermissions.hasRequiredPermissions(this)) {
            PluginVoicePermissions.request(activity) { granted ->
                if (granted) {
                    applyNativePluginToggle(pluginId, enabled = true)
                } else {
                    Toast.makeText(
                        this,
                        "Microphone and notification permissions are required for $pluginId",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
            return
        }
        if (enabled && pluginId in NOTIFICATION_PLUGIN_IDS && !hasNotificationPermission(this)) {
            ensureNotificationPermission(activity, pluginId) {
                applyNativePluginToggle(pluginId, enabled = true)
            }
            return
        }
        applyNativePluginToggle(pluginId, enabled)
    }

    private fun applyNativePluginToggle(pluginId: String, enabled: Boolean) {
        CommunityPluginPrefs.setNativePluginEnabled(this, pluginId, enabled)
        viewModel.onNativePluginToggled(pluginId, enabled)
        val pluginFlag = nativePluginEnabledPrefs[pluginId] ?: return
        pluginFlag[this] = enabled
        when {
            !enabled -> AndroidNativePluginHost(this).stopPlugin(pluginId)
            pluginId == NativePluginIds.WALKING_AID -> WalkingAidService.start(this)
            pluginId == NativePluginIds.AUTO_AUDIO -> AutoAudioCaptureService.start(this)
        }
    }

    private fun openCommunityPlugin(plugin: CommunityPluginCardData) {
        if (plugin.badge.equals("Tasker", ignoreCase = true) &&
            plugin.downloadUrl?.let { Uri.parse(it).lastPathSegment?.endsWith(".xml", ignoreCase = true) } == true
        ) {
            downloadTaskerIntegration(plugin)
            return
        }
        val link = plugin.taskerNetLink ?: plugin.downloadUrl ?: return
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)))
        }.onFailure {
            Toast.makeText(this, "Could not open ${plugin.title}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun downloadTaskerIntegration(plugin: CommunityPluginCardData) {
        val link = plugin.downloadUrl ?: return
        val sourceFileName = Uri.parse(link).lastPathSegment ?: "${plugin.id}.prj.xml"
        Toast.makeText(this, "Preparing ${plugin.title} for Tasker...", Toast.LENGTH_SHORT).show()
        val appContext = applicationContext
        val screenRef = WeakReference(this)
        taskerDownloadScope.launch {
            val result = runCatching { downloadTaskerProject(link, sourceFileName) }
            withContext(Dispatchers.Main) {
                val screen = screenRef.get()?.takeUnless { it.isFinishing || it.isDestroyed }
                result.onSuccess { file ->
                    if (screen == null) {
                        Toast.makeText(
                            appContext,
                            "${plugin.title} is ready. Open Plugins and tap Download profile again to import it.",
                            Toast.LENGTH_LONG,
                        ).show()
                        return@onSuccess
                    }
                    val uri = FileProvider.getUriForFile(
                        screen,
                        "${screen.packageName}.fileprovider",
                        file,
                    )
                    if (!TaskerProfileGuidance.openImporter(screen, uri, sourceFileName)) {
                        val fallbackFileName = taskerDownloadFileName(sourceFileName, System.currentTimeMillis())
                        screen.pendingTaskerDownloadTitle = plugin.title
                        screen.pendingTaskerDownloadUrl = link
                        screen.pendingTaskerDownloadFileName = fallbackFileName
                        Toast.makeText(
                            screen,
                            "Tasker could not import directly. Choose where to save the project.",
                            Toast.LENGTH_LONG,
                        ).show()
                        screen.saveTaskerProfileLauncher.launch(fallbackFileName)
                    }
                }.onFailure { error ->
                    Toast.makeText(
                        screen ?: appContext,
                        "Could not prepare ${plugin.title}: ${error.message}",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    private fun downloadTaskerProject(link: String, fileName: String): File {
        val connection = java.net.URL(link).openConnection() as java.net.HttpURLConnection
        val xml = try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = true
            if (connection.responseCode !in 200..299) error("HTTP ${connection.responseCode}")
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
        requireValidTaskerProject(xml)

        val directory = File(cacheDir, "tasker-downloads").apply { mkdirs() }
        val safeFileName = fileName.substringAfterLast('/').substringAfterLast('\\')
        val destination = File(directory, safeFileName)
        val temporary = File(directory, "$safeFileName.part")
        temporary.writeText(xml, Charsets.UTF_8)
        if (destination.exists()) check(destination.delete()) { "Could not replace the cached Tasker project" }
        check(temporary.renameTo(destination)) { "Could not finish the Tasker project download" }
        return destination
    }

    private fun saveTaskerProfile(title: String, link: String, fileName: String, destination: Uri) {
        Toast.makeText(this, "Downloading $title...", Toast.LENGTH_SHORT).show()
        val appContext = applicationContext
        val screenRef = WeakReference(this)
        taskerDownloadScope.launch {
            val result = runCatching {
                val connection = java.net.URL(link).openConnection() as java.net.HttpURLConnection
                try {
                    connection.connectTimeout = 10_000
                    connection.readTimeout = 30_000
                    if (connection.responseCode !in 200..299) error("HTTP ${connection.responseCode}")
                    connection.inputStream.use { input ->
                        appContext.contentResolver.openOutputStream(destination)?.use(input::copyTo)
                            ?: error("The selected folder could not create the file")
                    }
                } finally {
                    connection.disconnect()
                }
            }
            if (result.isFailure) {
                runCatching { appContext.contentResolver.delete(destination, null, null) }
            }
            withContext(Dispatchers.Main) {
                val screen = screenRef.get()?.takeUnless { it.isFinishing || it.isDestroyed }
                result.onSuccess {
                    if (screen != null) {
                        TaskerProfileGuidance.showSavedDialog(screen.activity, fileName, destination)
                    } else {
                        Toast.makeText(appContext, "$title saved to the selected folder", Toast.LENGTH_LONG).show()
                    }
                }.onFailure { error ->
                    Toast.makeText(
                        screen ?: appContext,
                        "Could not download $title: ${error.message}",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    private companion object {
        private const val TASKER_AI_ID = "tasker_ai_assistant"
        private const val TASKER_PROFILE_BASE_URL =
            "https://cyanbridge.vercel.app/downloads/tasker/2026-08-24"
        private const val STATE_PENDING_TASKER_TITLE = "pending_tasker_download_title"
        private const val STATE_PENDING_TASKER_URL = "pending_tasker_download_url"
        private const val STATE_PENDING_TASKER_FILE_NAME = "pending_tasker_download_file_name"
        private val taskerDownloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        private val VOICE_PLUGIN_IDS = setOf(
            NativePluginIds.AUTO_AUDIO,
        )

        private val NOTIFICATION_PLUGIN_IDS = setOf(
            NativePluginIds.WALKING_AID,
        )
    }
}

/** The native plugins the selected glasses support, and the relay's community catalog. */
private class AndroidCommunityPluginsPlatform(private val context: Context) : CommunityPluginsPlatform {
    override fun nativePlugins(): List<NativePluginCardData> {
        val selectedClass = DeviceProfileStore.selectedClass(context)
        val hasCamera = selectedClass in setOf(
            DeviceClass.HEY_CYAN,
            DeviceClass.META_RAYBAN,
            DeviceClass.UNKNOWN,
        )
        val hasOnboardStorage = selectedClass == DeviceClass.HEY_CYAN || selectedClass == DeviceClass.UNKNOWN

        val autoAudioDescription = when (selectedClass) {
            DeviceClass.META_RAYBAN -> "Unavailable for Meta Ray-Ban: DAT does not expose HeyCyan onboard audio-file recording."
            DeviceClass.MEIZU_MYVU -> "Unavailable for Meizu MYVU: device has no onboard audio file storage."
            DeviceClass.MEMOMIND -> context.getString(R.string.memomind_no_onboard_audio)
            DeviceClass.GENERIC_AUDIO -> "Unavailable for Earbuds / Audio-only glasses: device has no onboard audio file storage."
            else -> "Record glasses audio in resilient 15-minute loops with optional speech extension and sync."
        }

        val cameraUnavailableReason = when (selectedClass) {
            DeviceClass.MEIZU_MYVU -> "Unavailable for Meizu MYVU: device has no camera."
            DeviceClass.MEMOMIND -> context.getString(R.string.memomind_no_camera)
            DeviceClass.GENERIC_AUDIO -> "Unavailable for Earbuds / Audio-only glasses: device has no camera."
            DeviceClass.MOYOUNG_W620 -> "Unavailable for MoYoung / W620: direct camera capture is not validated yet."
            else -> null
        }

        return listOf(
            NativePluginCardData(
                id = NativePluginIds.WALKING_AID,
                title = "Walking Aid",
                description = cameraUnavailableReason
                    ?: "Real-time scene description and obstacle warnings for blind navigation. Captures images from glasses at regular intervals and describes the environment.",
                badge = "Accessibility",
                enabled = hasCamera && CommunityPluginPrefs.isNativePluginEnabled(context, NativePluginIds.WALKING_AID),
                hasSettings = true,
                isAvailable = hasCamera,
            ),
            NativePluginCardData(
                id = NativePluginIds.MEETING_SPARK_NOTES,
                title = "Meeting Spark Notes",
                description = "Turns live voice capture and chats into concise meeting summaries with action items.",
                badge = "Productivity",
                enabled = CommunityPluginPrefs.isNativePluginEnabled(context, NativePluginIds.MEETING_SPARK_NOTES),
                hasSettings = true,
            ),
            NativePluginCardData(
                id = NativePluginIds.LIVE_CAPTION_RELAY,
                title = "Live Caption Relay",
                description = if (selectedClass == DeviceClass.MEIZU_MYVU) {
                    "Captions live speech and streams text directly to your Meizu MYVU heads-up display."
                } else if (selectedClass == DeviceClass.MEMOMIND) {
                    context.getString(R.string.memomind_live_caption_description)
                } else {
                    "Captions live speech from your phone or Bluetooth glasses mic, with optional translation."
                },
                badge = "Accessibility",
                enabled = CommunityPluginPrefs.isNativePluginEnabled(context, NativePluginIds.LIVE_CAPTION_RELAY),
                hasSettings = true,
            ),
            NativePluginCardData(
                id = NativePluginIds.HANDS_FREE_TRANSLATOR,
                title = "Hands-Free Translator",
                description = if (selectedClass == DeviceClass.MEIZU_MYVU) {
                    "Continuously translates live speech and displays translated subtitles on your Meizu MYVU HUD."
                } else if (selectedClass == DeviceClass.MEMOMIND) {
                    context.getString(R.string.memomind_translator_description)
                } else {
                    "Continuously translates live speech from your phone or Bluetooth glasses mic while enabled."
                },
                badge = "Language",
                enabled = CommunityPluginPrefs.isNativePluginEnabled(context, NativePluginIds.HANDS_FREE_TRANSLATOR),
                hasSettings = true,
            ),
            NativePluginCardData(
                id = NativePluginIds.ERRAND_BRAIN,
                title = "Errand Brain",
                description = "Turns live voice notes into checklist tasks. Say “remind me in…” to schedule a phone reminder.",
                badge = "Planner",
                enabled = CommunityPluginPrefs.isNativePluginEnabled(context, NativePluginIds.ERRAND_BRAIN),
                hasSettings = true,
            ),
            NativePluginCardData(
                id = NativePluginIds.AUTO_AUDIO,
                title = "Auto Audio",
                description = autoAudioDescription,
                badge = "Media",
                enabled = hasOnboardStorage && AutoAudioCapturePrefs.isEnabled(context),
                hasSettings = true,
                isAvailable = hasOnboardStorage,
            ),
        )
    }

    override suspend fun fetchCommunityPlugins(): List<CommunityPluginCardData> = withContext(Dispatchers.IO) {
        val relayUrl = AiProviderPrefs.getRelayBaseUrl(context).trimEnd('/')
        val connection = java.net.URL("$relayUrl/plugins").openConnection()
            as java.net.HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            if (connection.responseCode != java.net.HttpURLConnection.HTTP_OK) {
                error("HTTP ${connection.responseCode}")
            }
            CommunityPluginCatalogParser.parse(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }

    override fun showMessage(message: UiMessage) = context.showToast(message)
}

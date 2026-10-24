package com.fersaiyan.cyanbridge
import com.fersaiyan.cyanbridge.shared.devices.DeviceProfile
import com.fersaiyan.cyanbridge.shared.devices.DeviceClass
import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.widget.ArrayAdapter
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.fersaiyan.cyanbridge.shared.settings.AgentProviderType
import com.fersaiyan.cyanbridge.ai.AiWakeWordPreferences
import com.fersaiyan.cyanbridge.agent.LocalAgentPrefs as AutomationPrefs
import com.fersaiyan.cyanbridge.ui.VersionUpdateChecker
import com.fersaiyan.cyanbridge.ui.OfficialHeyCyanWarningDialog
import com.fersaiyan.cyanbridge.localagent.AudioSessionCoordinator
import com.fersaiyan.cyanbridge.localagent.LocalAgentController
import com.fersaiyan.cyanbridge.localagent.LocalAgentIntents
import com.fersaiyan.cyanbridge.localagent.LocalAgentPrefs
import com.fersaiyan.cyanbridge.shared.settings.CaptureSource
import com.fersaiyan.cyanbridge.audio.MeetingCapturePrefs
import com.fersaiyan.cyanbridge.audio.MeetingCaptureService
import com.fersaiyan.cyanbridge.media.GlassesMediaPrefs
import com.fersaiyan.cyanbridge.media.SyncedMediaFolder
import com.fersaiyan.cyanbridge.media.VendorAlbumDownloader
import com.fersaiyan.cyanbridge.media.HeyCyanP2pPolicy
import com.fersaiyan.cyanbridge.media.OfficialHeyCyanApp
import com.fersaiyan.cyanbridge.media.AdaptiveHttpRoute
import com.fersaiyan.cyanbridge.media.AdaptiveP2pProfileStore
import com.fersaiyan.cyanbridge.media.AdaptiveP2pSyncSession
import com.fersaiyan.cyanbridge.media.AdaptiveSyncCheckpoint
import com.fersaiyan.cyanbridge.ota.LivePreviewCoordinator
import com.fersaiyan.cyanbridge.ota.OtaCoordinator
import com.fersaiyan.cyanbridge.media.sync.GlassesMediaLibrary
import com.fersaiyan.cyanbridge.media.sync.GlassesMediaSyncCoordinator
import com.fersaiyan.cyanbridge.media.sync.GlassesMediaSyncHost
import com.fersaiyan.cyanbridge.media.sync.TransferProgressPresenter
import com.fersaiyan.cyanbridge.media.sync.VendorMediaSyncController
import com.fersaiyan.cyanbridge.media.sync.VendorMediaSyncHost
import com.fersaiyan.cyanbridge.ota.OtaManager
import com.fersaiyan.cyanbridge.glasses.GlassesSession
import com.fersaiyan.cyanbridge.glasses.GlassesSessionLease
import com.fersaiyan.cyanbridge.glasses.HeyCyanRecordingControls
import com.fersaiyan.cyanbridge.glasses.GlassesSessionCoordinator
import com.fersaiyan.cyanbridge.glasses.GlassesPhotoCapture
import com.fersaiyan.cyanbridge.glasses.BackgroundGlassesCommandPermit
import com.fersaiyan.cyanbridge.wifiadb.DefaultWifiAdbDebugControllerFactory
import com.fersaiyan.cyanbridge.wifiadb.WifiAdbDebugCoordinator
import com.fersaiyan.cyanbridge.media.autocapture.AutoAudioCapturePrefs
import com.fersaiyan.cyanbridge.media.autocapture.AutoAudioCaptureService
import com.fersaiyan.cyanbridge.media.autocapture.GlassesSyncedAudioIngestor
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import android.view.View
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.hjq.permissions.OnPermissionCallback
import com.hjq.permissions.Permission as HjqPermission
import com.hjq.permissions.XXPermissions
import com.oudmon.ble.base.communication.utils.ByteUtil
import com.oudmon.ble.base.bluetooth.BleOperateManager
import com.oudmon.ble.base.bluetooth.DeviceManager
import com.oudmon.ble.base.communication.LargeDataHandler
import com.oudmon.ble.base.communication.bigData.resp.GlassesDeviceNotifyListener
import com.oudmon.ble.base.communication.bigData.resp.GlassesDeviceNotifyRsp
import com.fersaiyan.cyanbridge.databinding.AcitivytMainBinding
import com.fersaiyan.cyanbridge.ui.DeviceBindActivity
import com.fersaiyan.cyanbridge.ui.MetaPairingActivity
import com.fersaiyan.cyanbridge.devices.metarayban.MetaDatPermissions
import com.fersaiyan.cyanbridge.ui.ChatListActivity
import com.fersaiyan.cyanbridge.ui.ChatThreadActivity
import com.fersaiyan.cyanbridge.ui.CommunityPluginPrefs
import com.fersaiyan.cyanbridge.ui.CommunityPluginsActivity
import com.fersaiyan.cyanbridge.ui.SettingsActivity
import com.fersaiyan.cyanbridge.plugins.PluginVoicePermissions
import com.fersaiyan.cyanbridge.plugins.autodiary.AutoDiaryService
import com.fersaiyan.cyanbridge.plugins.localagent.LocalAgentPlugin
import com.fersaiyan.cyanbridge.plugins.visualdiary.VisualDiaryPreferences
import com.fersaiyan.cyanbridge.plugins.visualdiary.VisualDiaryService
import com.fersaiyan.cyanbridge.plugins.errandbrain.ErrandBrainPreferences
import com.fersaiyan.cyanbridge.plugins.errandbrain.ErrandBrainService
import com.fersaiyan.cyanbridge.plugins.handsfreetranslator.HandsFreeTranslatorPreferences
import com.fersaiyan.cyanbridge.plugins.handsfreetranslator.HandsFreeTranslatorService
import com.fersaiyan.cyanbridge.plugins.livecaptionrelay.LiveCaptionRelayPreferences
import com.fersaiyan.cyanbridge.plugins.livecaptionrelay.LiveCaptionRelayService
import com.fersaiyan.cyanbridge.plugins.meetingsparknotes.MeetingSparkNotesPreferences
import com.fersaiyan.cyanbridge.plugins.meetingsparknotes.MeetingSparkNotesService
import com.fersaiyan.cyanbridge.plugins.walkingaid.WalkingAidPreferences
import com.fersaiyan.cyanbridge.plugins.walkingaid.WalkingAidImageCapture
import com.fersaiyan.cyanbridge.plugins.walkingaid.WalkingAidService
import com.fersaiyan.cyanbridge.ui.recordings.RecordingsListActivity
import com.fersaiyan.cyanbridge.ui.BluetoothUtils
import com.fersaiyan.cyanbridge.ui.BluetoothEvent
import com.fersaiyan.cyanbridge.ui.AutoPairManager
import com.fersaiyan.cyanbridge.chat.ChatStore
import com.fersaiyan.cyanbridge.devices.DeviceProfileStore
import com.fersaiyan.cyanbridge.devices.eyevue.EyevueManager
import com.fersaiyan.cyanbridge.devices.eyevue.EyevueLivePreviewManager
import com.fersaiyan.cyanbridge.devices.eyevue.EyevueLivePreviewPolicy
import com.fersaiyan.cyanbridge.devices.eyevue.EyevueMediaProfile
import com.fersaiyan.cyanbridge.devices.eyevue.EyevueMediaSync
import com.fersaiyan.cyanbridge.devices.eyevue.EyevueMediaType
import com.fersaiyan.cyanbridge.devices.eyevue.EyevueWifiTransport
import com.fersaiyan.cyanbridge.devices.meizumyvu.MeizuMyvuManager
import com.fersaiyan.cyanbridge.devices.meizumyvu.MeizuMyvuFailure
import com.fersaiyan.cyanbridge.devices.moyoung.MoyoungW620Manager
import com.fersaiyan.cyanbridge.devices.moyoung.MoyoungWifiCredentials
import com.fersaiyan.cyanbridge.devices.tunebuds.TuneBudsManager
import com.fersaiyan.cyanbridge.devices.tunebuds.TuneBudsLivePreviewManager
import com.fersaiyan.cyanbridge.devices.tunebuds.TuneBudsLiveForegroundService
import com.fersaiyan.cyanbridge.devices.tunebuds.TuneBudsLocalHotspot
import com.fersaiyan.cyanbridge.devices.tunebuds.TuneBudsMediaSync
import com.fersaiyan.cyanbridge.devices.tunebuds.TuneBudsMediaType
import com.fersaiyan.cyanbridge.devices.tunebuds.isSupportedForTuneBudsDashboard
import com.fersaiyan.cyanbridge.shared.devices.GlassesManagerGating
import com.fersaiyan.cyanbridge.privacy.PrivacyPrefs
import com.fersaiyan.cyanbridge.ui.MyApplication
import com.fersaiyan.cyanbridge.ui.bleIpBridge
import com.fersaiyan.cyanbridge.ui.hasBluetooth
import com.fersaiyan.cyanbridge.ui.hasNotificationPermission
import com.fersaiyan.cyanbridge.ui.hasWifiP2pPermission
import com.fersaiyan.cyanbridge.ui.requestBluetoothPermission
import com.fersaiyan.cyanbridge.ui.ensureNotificationPermission
import com.fersaiyan.cyanbridge.ui.requestWifiP2pPermission
import com.fersaiyan.cyanbridge.ui.setOnClickListener
import com.fersaiyan.cyanbridge.ui.startKtxActivity
import com.fersaiyan.cyanbridge.ui.debug.DebugLogSupport
import com.fersaiyan.cyanbridge.localmodels.remote.RemoteOpenAiPrefs
import com.fersaiyan.cyanbridge.ui.wifi.p2p.WifiP2pManagerSingleton
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.ConnectivityManager
import android.net.Network
import android.provider.MediaStore
import android.content.ContentValues
import android.media.MediaScannerConnection
import android.os.Environment
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import com.fersaiyan.cyanbridge.ui.BatteryOptimizationGuideActivity
// import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference
import javax.net.SocketFactory
import java.text.SimpleDateFormat
import java.util.Locale
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import androidx.core.content.FileProvider
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume

import android.provider.Settings
import android.net.Uri
import android.app.KeyguardManager

import kotlinx.coroutines.sync.withLock
import com.fersaiyan.cyanbridge.ai.image.AudioQuestionRecorder
import com.fersaiyan.cyanbridge.ai.transcription.OfflineQuestionTranscriber
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.unit.dp
import com.fersaiyan.cyanbridge.agent.ProSubscriptionAiPrefs
import com.fersaiyan.cyanbridge.agent.ProSubscriptionActivity
import com.fersaiyan.cyanbridge.agent.ProSubscriptionPrefs
import com.fersaiyan.cyanbridge.agent.LocalModelsConfigureActivity
import com.fersaiyan.cyanbridge.ai.router.AssistantSetupDestination
import com.fersaiyan.cyanbridge.ai.router.AssistantTestKind
import com.fersaiyan.cyanbridge.ai.router.AssistantTestReadiness
import com.fersaiyan.cyanbridge.ai.router.AssistantIntent
import com.fersaiyan.cyanbridge.ai.router.AssistantRequest
import com.fersaiyan.cyanbridge.ai.router.AssistantRequestRouter
import com.fersaiyan.cyanbridge.ai.router.AssistantRequestSource
import com.fersaiyan.cyanbridge.ai.router.AssistantSpeechPolicy
import com.fersaiyan.cyanbridge.ai.router.GlassesAssistantRoute
import com.fersaiyan.cyanbridge.ai.router.GlassesAssistantRoutingPolicy
import com.fersaiyan.cyanbridge.ai.router.CliRelayClient
import com.fersaiyan.cyanbridge.ai.router.RelayErrorLocalizer
import com.fersaiyan.cyanbridge.ai.live.GeminiLiveActivity
import com.fersaiyan.cyanbridge.ai.live.GeminiLiveVisionPreferences
import com.fersaiyan.cyanbridge.ai.router.MediaInferenceRoutingPolicy
import com.fersaiyan.cyanbridge.ai.vision.ImageQuestionPreferences
import com.fersaiyan.cyanbridge.ai.vision.ImageQuestionDefaults
import com.fersaiyan.cyanbridge.ai.vision.ImageQuestionPromptResolver
import com.fersaiyan.cyanbridge.ai.vision.ImageQuestionRoute
import com.fersaiyan.cyanbridge.ai.vision.ResolvedImageQuestionPrompt
import com.fersaiyan.cyanbridge.ai.image.DefaultAssistantResolver
import com.fersaiyan.cyanbridge.ai.image.ExternalAssistantAutomationInspector
import com.fersaiyan.cyanbridge.ai.image.ExternalAssistantAutomationPolicy
import com.fersaiyan.cyanbridge.ai.image.ExternalAssistantAutomationSetupActivity
import com.fersaiyan.cyanbridge.ai.image.ExternalImageAutomationIntents
import com.fersaiyan.cyanbridge.ai.image.ExternalImageAutomationStage
import com.fersaiyan.cyanbridge.ai.image.ExternalImageAutomationStore
import com.fersaiyan.cyanbridge.ai.image.ImageAutomationTarget
import com.fersaiyan.cyanbridge.ai.image.ImageQuestionBroadcast
import com.fersaiyan.cyanbridge.ai.image.ImageQuestionSource
import com.fersaiyan.cyanbridge.ai.image.ImageQuestionSourcePolicy
import com.fersaiyan.cyanbridge.ai.image.ImageThumbnailQuality
import com.fersaiyan.cyanbridge.ai.AiQuestionForegroundService
import com.fersaiyan.cyanbridge.ai.image.HighQualityFailureChoice
import com.fersaiyan.cyanbridge.shared.glasses.GlassesAssistantMode
import com.fersaiyan.cyanbridge.shared.glasses.AiWakeWordRoute
import com.fersaiyan.cyanbridge.shared.glasses.GlassesDashboardAction
import com.fersaiyan.cyanbridge.shared.glasses.GlassesDashboardUiState
import com.fersaiyan.cyanbridge.shared.glasses.FirmwarePatchRequestUiState
import com.fersaiyan.cyanbridge.shared.glasses.GlassesSyncFlow
import com.fersaiyan.cyanbridge.shared.glasses.GlassesTransferUiState
import com.fersaiyan.cyanbridge.shared.glasses.MetaRaybanUiState
import com.fersaiyan.cyanbridge.shared.glasses.MeizuMyvuUiState
import com.fersaiyan.cyanbridge.shared.glasses.OtaFirmwareSource
import com.fersaiyan.cyanbridge.shared.glasses.WifiAdbDebugUiState
import com.fersaiyan.cyanbridge.shared.navigation.AppDestination
import com.fersaiyan.cyanbridge.shared.plugins.NativePluginIds
import com.fersaiyan.cyanbridge.shared.plugins.NativePluginShortcutAction
import com.fersaiyan.cyanbridge.shared.plugins.NativePluginShortcutButton
import com.fersaiyan.cyanbridge.shared.plugins.NativePluginShortcutUiState
import com.fersaiyan.cyanbridge.tasker.TaskerIntegrationManager
import com.fersaiyan.cyanbridge.localagent.context.LocalAgentContextBuilder
import com.fersaiyan.cyanbridge.localagent.dailyfacts.DailyFactsStorage
import com.fersaiyan.cyanbridge.localagent.memory.LocalAgentMemorySearch
import com.fersaiyan.cyanbridge.localagent.memory.RagProfile
import com.fersaiyan.cyanbridge.localagent.memory.LocalAgentMemoryStore
import com.fersaiyan.cyanbridge.localagent.userfacts.CandidateUserFactsStorage
import com.fersaiyan.cyanbridge.localmodels.provider.LocalModelsProvider
import com.fersaiyan.cyanbridge.localmodels.tts.StreamingSpeechSessionManager
import com.fersaiyan.cyanbridge.localmodels.settings.LocalModelRuntime
import com.fersaiyan.cyanbridge.localmodels.settings.LocalModelSettingsRepository
import com.fersaiyan.cyanbridge.localmodels.storage.LocalModelStorageRepository
import com.fersaiyan.cyanbridge.ai.decision.LocalDecisionEngine
import com.fersaiyan.cyanbridge.ai.decision.SingleTokenDecisionEngine
import com.fersaiyan.cyanbridge.ai.router.AgentInferenceRouter
import com.fersaiyan.cyanbridge.memoryvault.MemoryPolicyService
import com.fersaiyan.cyanbridge.ui.appearance.AppearancePreferences
import com.fersaiyan.cyanbridge.ui.appearance.rememberAppearanceSettings
import com.fersaiyan.cyanbridge.shared.ui.CyanBridgeApp
import com.fersaiyan.cyanbridge.ui.theme.CyanBridgeTheme
import android.content.ClipboardManager
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge


class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val localSpeechSessionManager by lazy {
        StreamingSpeechSessionManager.getInstance(applicationContext)
    }
    private val ttsDoneCallbacks = ConcurrentHashMap<String, () -> Unit>()
    private val assistantDecisionEngines = ConcurrentHashMap<AgentProviderType, LocalDecisionEngine>()
    private val assistantRequestRouter = AssistantRequestRouter { providerType ->
        assistantDecisionEngines.computeIfAbsent(providerType) {
            SingleTokenDecisionEngine(
                generate = { systemPrompt, userPrompt ->
                    AgentInferenceRouter.completeDecisionToken(
                        context = applicationContext,
                        sessionId = "assistant-decision-${System.currentTimeMillis()}",
                        systemPrompt = systemPrompt,
                        userPrompt = userPrompt,
                        maxTokens = 8,
                        providerType = providerType,
                    )
                },
            )
        }
    }
    private var pendingVoiceImageQuestion: String? = null
    private var pendingImageQuestionOfferSpokenQuestion = false

    // Optional Local Agent UI status
    private var agentReceiverRegistered = false
    private var imageAutomationStatusReceiverRegistered = false
    private val imageAutomationStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ExternalImageAutomationIntents.internalStatusAction(packageName)) {
                handleExternalImageAutomationStatus()
            }
        }
    }
    private val agentStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return

            val status = intent.getStringExtra(LocalAgentIntents.EXTRA_STATUS)
            val lastError = intent.getStringExtra(LocalAgentIntents.EXTRA_LAST_ERROR)
            val isTerminal = intent.getBooleanExtra(LocalAgentIntents.EXTRA_IS_TERMINAL, false)
            val userMessage = intent.getStringExtra(LocalAgentIntents.EXTRA_USER_MESSAGE)

            if (!status.isNullOrBlank()) {
                LocalAgentPrefs.setStatus(this@MainActivity, status)
            }
            if (!lastError.isNullOrBlank()) {
                LocalAgentPrefs.setLastError(this@MainActivity, lastError)
            }

            if (isTerminal && !userMessage.isNullOrBlank()) {
                Toast.makeText(this@MainActivity, userMessage, Toast.LENGTH_SHORT).show()
            }

            refreshAgentStatusUi()
        }
    }
    override fun onInit(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS
        Log.i("ImageQuestionAudio", "TTS initialization status=$status ready=$ttsReady")
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.getDefault()
        }
        localSpeechSessionManager.attachTtsEngine(tts, ttsReady)
    }

    private fun speak(text: String) {
        speak(text, languageTag = null, utteranceId = null, onDone = null, streamType = null)
    }

    private fun speakVision(text: String, onDone: (() -> Unit)? = null) {
        speak(
            text = text,
            languageTag = ImageQuestionPreferences.get(this).appLanguageTag,
            utteranceId = null,
            onDone = onDone,
            streamType = null,
        )
    }

    private fun speak(
        text: String,
        languageTag: String? = null,
        utteranceId: String?,
        streamType: Int? = null,
        onDone: (() -> Unit)? = null,
    ) {
        val engine = tts
        languageTag?.takeIf { it.isNotBlank() }?.let { tag ->
            val result = engine?.setLanguage(Locale.forLanguageTag(tag))
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.w(TAG, "Text-to-speech voice unavailable for $tag")
            }
            Log.i("ImageQuestionAudio", "TTS language tag=$tag result=$result")
        }
        val id = utteranceId ?: "utt_${System.currentTimeMillis()}"
        val wrappedOnDone: () -> Unit = {
            try {
                onDone?.invoke()
            } finally {
                AudioSessionCoordinator.markIdle()
            }
        }
        ttsDoneCallbacks[id] = wrappedOnDone

        val bundle = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, id)
            streamType?.let { putString(TextToSpeech.Engine.KEY_PARAM_STREAM, it.toString()) }
        }

        AudioSessionCoordinator.markBusy()
        val result = engine?.speak(text, TextToSpeech.QUEUE_FLUSH, bundle, id)
        Log.i(
            "ImageQuestionAudio",
            "TTS enqueue id=$id ready=$ttsReady stream=$streamType textLength=${text.length} result=$result",
        )
        if (result != TextToSpeech.SUCCESS) {
            // No progress callback is delivered when enqueueing fails (including when the
            // engine is not initialized). Complete the caller now instead of leaking its
            // callback or leaving SCO/foreground work waiting forever.
            ttsDoneCallbacks.remove(id)?.invoke()
        }
    }
    companion object {
        const val EXTRA_TASKER_COMMAND = "tasker_command"
        const val EXTRA_START_META_IMAGE_QUESTION = "start_meta_image_question"
        private const val TAG = "MainActivity"
        private var loggedLargeDataHandlerMethods = false
        private const val AI_MODE_PHONE_ASSISTANT = "PhoneAssistant"
        private const val AI_MODE_TASKER = "Tasker"
        private const val AI_MODE_CUSTOM_AI_PROVIDER = "CustomAiProvider"
        private const val QUERY_MAX_AGENT_PERSONA_CHARS = 1200
        private const val QUERY_MAX_USER_FACTS_CHARS = 1400
        private const val QUERY_MAX_CONFIRMED_FACTS_CHARS = 1800
        private const val QUERY_MAX_DAILY_SUMMARY_CHARS = 2200
        private const val QUERY_MAX_TOTAL_CONTEXT_CHARS = 6500

        private const val IMAGE_QUESTION_MAX_IMAGE_AGE_MS = 3L * 60L * 1000L
        private const val ONE_SHOT_BLE_COMMAND_TIMEOUT_MS = 6_000L
        private const val IMAGE_THUMBNAIL_TRANSFER_TIMEOUT_MS = 20_000L
        private const val VOICE_CUE_ROUTE_SETTLE_MS = 500L
        private const val VOICE_BLUETOOTH_ROUTE_TIMEOUT_MS = 3_000L
        private const val VOICE_QUESTION_ONSET_TIMEOUT_MS = 5_000L
        private const val IMAGE_QUESTION_CUE_BLUETOOTH_TAIL_MS = 50L
        private const val IMAGE_QUESTION_INITIAL_LISTENING_TIMEOUT_MS = 3_300L
        private val DEFAULT_VIDEO_DURATION_OPTIONS_SECONDS = listOf(15, 30, 60, 180, 540, 720)
        private val AUDIO_DURATION_OPTIONS_SECONDS = listOf(1_800, 3_600, 7_200)

        fun actionTaskerCommand(appPackageName: String): String =
            "$appPackageName.ACTION_TASKER_COMMAND"

        fun aiEventAction(appPackageName: String): String =
            "$appPackageName.AI_EVENT"
    }

    // Keeps the existing Android control handlers alive while Compose owns the visible tree.
    private lateinit var binding: AcitivytMainBinding
    private var dashboardState by mutableStateOf(
        GlassesDashboardUiState(
            wifiAdbDebug = WifiAdbDebugUiState(isAvailable = false),
        ),
    )
    private var showDownloadFlowPicker by mutableStateOf(false)
    private var showOfficialHeyCyanWarningDialog by mutableStateOf(false)
    private val deviceNotifyListener by lazy { MyDeviceNotifyListener() }
    private var otaSessionLease: GlassesSessionLease? = null
    private var livePreviewSessionLease: GlassesSessionLease? = null
    private var eyevueLivePreviewManager: EyevueLivePreviewManager? = null
    private var tuneBudsLivePreviewManager: TuneBudsLivePreviewManager? = null
    private var tuneBudsLivePreviewUiJob: Job? = null
    private var tuneBudsLivePlayer by mutableStateOf<org.videolan.libvlc.MediaPlayer?>(null)
    private var tuneBudsLiveAudioMuted by mutableStateOf(false)
    private var tuneBudsLiveStopReceiver: android.content.BroadcastReceiver? = null
    private var eyevueLivePreviewUiJob: Job? = null
    // Inline EyeVue video: exposed to Compose so the dashboard shows video
    // in-screen (EyeVue only, after live starts). No popup dialog.
    private var eyevueLivePlayer by mutableStateOf<org.videolan.libvlc.MediaPlayer?>(null)
    private var eyevueLiveStopReceiver: android.content.BroadcastReceiver? = null
    private var eyevueLiveAudioMuted by mutableStateOf(false)
    private var heyCyanLivePlayer by mutableStateOf<org.videolan.libvlc.MediaPlayer?>(null)
    private var mediaSessionLease: GlassesSessionLease? = null
    private var wifiAdbDebugSessionLease: GlassesSessionLease? = null
    private val otaManager by lazy { OtaManager(this) }
    private var otaPreparationJob: Job?
        get() = otaCoordinator.otaPreparationJob
        set(value) {
            otaCoordinator.otaPreparationJob = value
        }
    private val personalFirmwarePicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            otaCoordinator.onPersonalFirmwareDocumentPicked(uri)
        }
    private val livePreviewManager by lazy { com.fersaiyan.cyanbridge.ota.LivePreviewManager(this) }
    private var livePreviewDialog: AlertDialog? = null
    private val wifiAdbDebugController by lazy { DefaultWifiAdbDebugControllerFactory.create(this) }
    private val glassesFeatureHost = object :
        OtaCoordinator.Host,
        HeyCyanRecordingControls.Host,
        LivePreviewCoordinator.Host,
        WifiAdbDebugCoordinator.Host {
        override var dashboardState: GlassesDashboardUiState
            get() = this@MainActivity.dashboardState
            set(value) {
                this@MainActivity.dashboardState = value
            }

        override var otaSessionLease: GlassesSessionLease?
            get() = this@MainActivity.otaSessionLease
            set(value) {
                this@MainActivity.otaSessionLease = value
            }

        override var livePreviewSessionLease: GlassesSessionLease?
            get() = this@MainActivity.livePreviewSessionLease
            set(value) {
                this@MainActivity.livePreviewSessionLease = value
            }

        override var wifiAdbDebugSessionLease: GlassesSessionLease?
            get() = this@MainActivity.wifiAdbDebugSessionLease
            set(value) {
                this@MainActivity.wifiAdbDebugSessionLease = value
            }

        override var heyCyanLivePlayer: org.videolan.libvlc.MediaPlayer?
            get() = this@MainActivity.heyCyanLivePlayer
            set(value) {
                this@MainActivity.heyCyanLivePlayer = value
            }

        override var livePreviewDialog: AlertDialog?
            get() = this@MainActivity.livePreviewDialog
            set(value) {
                this@MainActivity.livePreviewDialog = value
            }

        override var eyevueLiveAudioMuted: Boolean
            get() = this@MainActivity.eyevueLiveAudioMuted
            set(value) {
                this@MainActivity.eyevueLiveAudioMuted = value
            }

        override var tuneBudsLiveAudioMuted: Boolean
            get() = this@MainActivity.tuneBudsLiveAudioMuted
            set(value) {
                this@MainActivity.tuneBudsLiveAudioMuted = value
            }

        override val glassesTeardownScope: CoroutineScope
            get() = this@MainActivity.glassesTeardownScope

        override fun launchPersonalFirmwarePicker() {
            personalFirmwarePicker.launch(arrayOf("*/*"))
        }

        override fun isHeyCyanSelected(): Boolean = this@MainActivity.isHeyCyanSelected()

        override fun isTuneBudsSelected(): Boolean = this@MainActivity.isTuneBudsSelected()

        override fun ensureGlassesTransportPermissions(feature: String, onGranted: () -> Unit) =
            this@MainActivity.ensureGlassesTransportPermissions(feature, onGranted)

        override fun acquireExclusiveGlassesSession(session: GlassesSession): GlassesSessionLease? =
            this@MainActivity.acquireExclusiveGlassesSession(session)

        override fun releaseExclusiveGlassesSession(lease: GlassesSessionLease?) =
            this@MainActivity.releaseExclusiveGlassesSession(lease)

        override fun isHeyCyanWifiSyncActive(): Boolean =
            downloadInProgress || downloadAttemptJob?.isActive == true || downloadP2pConnected

        override fun getOrCreateEyevueManager(): EyevueManager = this@MainActivity.getOrCreateEyevueManager()

        override fun getOrCreateEyevueLivePreviewManager(): EyevueLivePreviewManager =
            this@MainActivity.getOrCreateEyevueLivePreviewManager()

        override fun isEyevueMediaSyncActive(): Boolean = eyevueMediaJob?.isActive == true

        override fun getOrCreateTuneBudsManager(): TuneBudsManager = this@MainActivity.getOrCreateTuneBudsManager()

        override fun getOrCreateTuneBudsLivePreviewManager(): TuneBudsLivePreviewManager =
            this@MainActivity.getOrCreateTuneBudsLivePreviewManager()

        override fun isTuneBudsMediaSyncActive(): Boolean = tuneBudsMediaJob?.isActive == true

        override fun isTuneBudsAiPhotoInProgress(): Boolean = tuneBudsAiPhotoInProgress.get()

        override fun isImageQuestionWorkActive(): Boolean = this@MainActivity.isImageQuestionWorkActive()

        override fun rejectHeyCyanOnlyFeature(feature: String): Boolean =
            this@MainActivity.rejectHeyCyanOnlyFeature(feature)

        override fun isGlassesCommandBlocked(source: String): Boolean =
            this@MainActivity.isGlassesCommandBlocked(source)

        override fun acquireBackgroundGlassesCommand(source: String): BackgroundGlassesCommandPermit? =
            this@MainActivity.acquireBackgroundGlassesCommand(source)

        override fun warnIfBackgroundGlassesCommandTimesOut(permit: BackgroundGlassesCommandPermit) =
            this@MainActivity.warnIfBackgroundGlassesCommandTimesOut(permit)
    }
    private val otaCoordinator: OtaCoordinator by lazy {
        OtaCoordinator(this, { otaManager }, glassesFeatureHost)
    }
    private val recordingControls: HeyCyanRecordingControls by lazy {
        HeyCyanRecordingControls(this, glassesFeatureHost)
    }
    private val livePreviewCoordinator: LivePreviewCoordinator by lazy {
        LivePreviewCoordinator(this, { livePreviewManager }, glassesFeatureHost)
    }
    private val wifiAdbDebugCoordinator: WifiAdbDebugCoordinator by lazy {
        WifiAdbDebugCoordinator(this, { wifiAdbDebugController }, glassesFeatureHost)
    }

    // AI Hijack settings
    private var isAiHijackEnabled = true // Default to enabled
    private var overlayPermissionPromptShown = false
    private var isImageAssistantMode = true // Use assistant vs share intent
    private var aiAssistantMode = AI_MODE_PHONE_ASSISTANT
    private var wakeWordConfiguredForConnection = false

    // Must outlive Activity destruction long enough to confirm P2P teardown.
    private val glassesTeardownScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val transferProgress = TransferProgressPresenter(
        bindingProvider = { binding },
        updateDashboardState = { transform -> updateDashboardState(transform) },
    )
    private val glassesMediaLibrary by lazy { GlassesMediaLibrary(this) }
    private val mediaSync by lazy {
        GlassesMediaSyncCoordinator(this, mediaSyncHost, transferProgress, glassesMediaLibrary, glassesTeardownScope)
    }
    private val vendorMediaSync by lazy {
        VendorMediaSyncController(this, mediaSyncHost, transferProgress, glassesMediaLibrary)
    }
    private val downloadP2pConnected: Boolean get() = mediaSync.downloadP2pConnected
    private val downloadInProgress: Boolean get() = mediaSync.downloadInProgress
    private val downloadAttemptJob: Job? get() = mediaSync.downloadAttemptJob
    private val eyevueMediaJob: Job? get() = vendorMediaSync.eyevueMediaJob
    private val tuneBudsMediaJob: Job? get() = vendorMediaSync.tuneBudsMediaJob

    // Guard against concurrent/duplicate image queries
    private val imageQueryInProgress = java.util.concurrent.atomic.AtomicBoolean(false)
    private val voiceQueryInProgress = AtomicReference<Any?>(null)
    private data class VoiceAudioRouteOwner(
        val queryToken: Any,
        val audioManager: android.media.AudioManager,
    )
    private val activeVoiceAudioRoute = AtomicReference<VoiceAudioRouteOwner?>(null)
    private val imageThumbnailRequestInProgress = java.util.concurrent.atomic.AtomicBoolean(false)
    private val imageCaptureAwaitingNotification = java.util.concurrent.atomic.AtomicBoolean(false)
    private val metaPhotoCaptureInProgress = java.util.concurrent.atomic.AtomicBoolean(false)
    private val pendingImageCapturePermit = AtomicReference<BackgroundGlassesCommandPermit?>(null)
    @Volatile
    private var pendingImageCaptureSourceTag: String? = null
    private var pendingImageQuestionSource = ImageQuestionSourcePolicy.defaultSource()
    private var pendingImageThumbnailQuality = ImageQuestionSourcePolicy.defaultThumbnailQuality()
    private var pendingImageCaptureStartedAtMs: Long = 0L
    private val highQualityImageRequest: GlassesMediaSyncCoordinator.HighQualityImageRequest?
        get() = mediaSync.highQualityImageRequest
    private var lastImageQueryAtMs: Long = 0L
    private var activeParallelAudioQuestionDeferred: kotlinx.coroutines.CompletableDeferred<CapturedImageQuestion?>? = null
    private val questionMicrophoneMutex = kotlinx.coroutines.sync.Mutex()
    private var activeParallelAudioQuestionJob: Job? = null

    private var batteryPollJob: Job? = null
    private val batteryPollIntervalMs = 60_000L
    private var pendingBatteryToast = false
    private var batteryCallbackRegistered = false
    private var enabledFeaturePermissionRequestActive = false
    private var enabledMetaCameraCheckActive = false

    // Chapter 5: meeting capture UI + state
    private val meetingTimerOptions: List<Pair<Long?, String>> = listOf(
        null to "No timer",
        15L * 60L to "15 min",
        60L * 60L to "1 hour",
        3L * 60L * 60L to "3 hours",
    )
    private var meetingCaptureStateReceiver: BroadcastReceiver? = null

    // Meta Ray-Ban integration
    private var metaRaybanManager: com.fersaiyan.cyanbridge.devices.metarayban.MetaRaybanManager? = null
    private var metaRaybanUiJob: Job? = null
    private var pendingMetaDatAction: (() -> Unit)? = null
    private var pendingMetaCameraAction: (() -> Unit)? = null
    private var metaCameraReadinessInProgress = false
    private var meizuMyvuManager: MeizuMyvuManager? = null
    private var meizuMyvuUiJob: Job? = null
    private var meizuMyvuFailureJob: Job? = null
    private var pendingMeizuMyvuFailure: MeizuMyvuFailure? = null
    private var eyevueManager: EyevueManager? = null
    private var eyevueUiJob: Job? = null
    private var eyevueWakeWordJob: Job? = null
    private val eyevueAiPhotoInProgress = AtomicBoolean(false)
    private var tuneBudsManager: TuneBudsManager? = null
    private var tuneBudsUiJob: Job? = null
    private var moyoungW620Manager: MoyoungW620Manager? = null
    private var moyoungW620UiJob: Job? = null
    private val tuneBudsAiPhotoInProgress = AtomicBoolean(false)
    private var pendingTransportPermissionAction: (() -> Unit)? = null

    private val metaAndroidPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            MetaDatPermissions.recordRequestResult(this, result)
            val action = pendingMetaDatAction
            pendingMetaDatAction = null
            enabledMetaCameraCheckActive = false
            if (metaAndroidPermissionsMissing().isEmpty()) {
                val manager = getOrCreateMetaRaybanManager()
                manager.initialize()
                if (manager.isInitialized.value) {
                    action?.invoke()
                } else {
                    showMetaError(
                        "Android/DAT initialization",
                        manager.lastError.value ?: "Unable to initialize Meta Wearables DAT",
                    )
                }
            } else {
                val denied = result.filterValues { !it }.keys.joinToString().ifBlank { "unknown" }
                showMetaError(
                    "Android permissions",
                    "Meta needs Bluetooth and camera permissions; denied=$denied",
                )
            }
        }

    private val metaWearablePermissionLauncher =
        registerForActivityResult(Wearables.RequestPermissionContract()) { result ->
            val action = pendingMetaCameraAction
            pendingMetaCameraAction = null
            enabledMetaCameraCheckActive = false
            metaCameraReadinessInProgress = false
            result.fold(
                onSuccess = { status ->
                    val granted = status == PermissionStatus.Granted
                    getOrCreateMetaRaybanManager().recordCameraPermissionResult(granted)
                    if (granted) action?.invoke() else showMetaError("DAT camera permission", "Meta camera permission was denied")
                },
                onFailure = { error, _ ->
                    getOrCreateMetaRaybanManager().recordCameraPermissionResult(false)
                    showMetaError("DAT camera permission", error.description)
                },
            )
        }

    // Transcription UI moved to the "Transcriptions & recordings" section

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = AcitivytMainBinding.inflate(layoutInflater)
        aiAssistantMode = when (AutomationPrefs.getGlassesAssistantMode(this)) {
            GlassesAssistantMode.PHONE_ASSISTANT -> AI_MODE_PHONE_ASSISTANT
            GlassesAssistantMode.CUSTOM_AI_PROVIDER -> AI_MODE_CUSTOM_AI_PROVIDER
        }
        initView()
        refreshImageThumbnailQuality()
        refreshGeminiLiveImageDelay()
        setupMeetingCaptureUi()
        setupAgentControlsUi()
        setupMetaRaybanUi()
        refreshNativePluginShortcutState()
        val appearancePreferences = AppearancePreferences(this)
        // Hide the view-based bottom navigation; the shared CMP nav shell owns it now.
        binding.bottomNavigation.visibility = View.GONE
        setContent {
            val appearance by rememberAppearanceSettings(appearancePreferences)
            CyanBridgeTheme(appearance) {
                CyanBridgeApp(
                    dashboardState = dashboardState,
                    onDashboardAction = ::handleDashboardAction,
                    showSyncFlowPicker = showDownloadFlowPicker,
                    onSyncFlowPickerDismiss = { showDownloadFlowPicker = false },
                    onSyncFlowSelected = { flow ->
                        showDownloadFlowPicker = false
                        Log.i("DataDownload", "User selected sync flow: ${flow.label}")
                        startDataDownload(flow)
                    },
                    appearanceSettings = appearance,
                    onNavigateToActivity = ::navigateToDestination,
                    liveVideoSlot = {
                        val live = dashboardState.livePreview
                        val isEyevueLive = dashboardState.showEyevueControls
                        val isHeyCyanLive = dashboardState.showHeyCyanControls
                        val isTuneBudsLive = dashboardState.showTuneBudsControls
                        val livePlayer = when {
                            isEyevueLive -> eyevueLivePlayer
                            isTuneBudsLive -> tuneBudsLivePlayer
                            else -> heyCyanLivePlayer
                        }
                        if ((isEyevueLive || isHeyCyanLive || isTuneBudsLive) &&
                            live.isPlaying &&
                            live.streamUrl != null &&
                            livePlayer != null
                        ) {
                            val liveUrl = live.streamUrl ?: ""
                            androidx.compose.foundation.layout.Column(
                                modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
                            ) {
                                androidx.compose.foundation.layout.Spacer(
                                    modifier = androidx.compose.ui.Modifier.height(8.dp),
                                )
                                if (isEyevueLive) {
                                    androidx.compose.ui.viewinterop.AndroidView(
                                        factory = { ctx ->
                                            com.fersaiyan.cyanbridge.devices.eyevue.EyevueLiveVideoView(ctx)
                                        },
                                        update = { view -> eyevueLivePreviewManager?.attachVideoView(view) },
                                        onRelease = { view -> eyevueLivePreviewManager?.detachVideoView(view) },
                                        modifier = androidx.compose.ui.Modifier
                                            .fillMaxWidth()
                                            .aspectRatio(0.75f),
                                    )
                                } else if (isTuneBudsLive) {
                                    androidx.compose.ui.viewinterop.AndroidView(
                                        factory = { ctx -> com.fersaiyan.cyanbridge.devices.tunebuds.TuneBudsLiveVideoView(ctx) },
                                        update = { view -> tuneBudsLivePreviewManager?.attachVideoView(view) },
                                        onRelease = { view -> tuneBudsLivePreviewManager?.detachVideoView(view) },
                                        modifier = androidx.compose.ui.Modifier.fillMaxWidth().aspectRatio(4f / 3f),
                                    )
                                } else {
                                    androidx.compose.ui.viewinterop.AndroidView(
                                        factory = { ctx ->
                                            com.fersaiyan.cyanbridge.devices.heycyan.HeyCyanLiveVideoView(ctx)
                                        },
                                        update = { view -> livePreviewManager.attachVideoView(view) },
                                        onRelease = { view -> livePreviewManager.detachVideoView(view) },
                                        modifier = androidx.compose.ui.Modifier
                                            .fillMaxWidth()
                                            .aspectRatio(4f / 3f),
                                    )
                                }
                                androidx.compose.foundation.layout.Spacer(
                                    modifier = androidx.compose.ui.Modifier.height(8.dp),
                                )
                                androidx.compose.material3.Text(
                                    text = liveUrl,
                                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                androidx.compose.material3.Text(
                                    text = "Shared local RTSP feed — keep Live preview running",
                                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (isEyevueLive) {
                                    androidx.compose.material3.TextButton(
                                        onClick = {
                                            eyevueLiveAudioMuted = !eyevueLiveAudioMuted
                                            eyevueLivePreviewManager?.setAudioMuted(eyevueLiveAudioMuted)
                                        },
                                    ) {
                                        androidx.compose.material3.Text(
                                            if (eyevueLiveAudioMuted) "Listen to audio" else "Mute audio",
                                        )
                                    }
                                }
                                if (isTuneBudsLive) {
                                    androidx.compose.material3.TextButton(onClick = {
                                        tuneBudsLiveAudioMuted = !tuneBudsLiveAudioMuted
                                        tuneBudsLivePreviewManager?.setAudioMuted(tuneBudsLiveAudioMuted)
                                    }) {
                                        androidx.compose.material3.Text(if (tuneBudsLiveAudioMuted) "Listen to audio" else "Mute audio")
                                    }
                                }
                                androidx.compose.foundation.layout.Row(
                                    modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
                                ) {
                                    androidx.compose.material3.TextButton(
                                        onClick = {
                                            val clipboard =
                                                getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                                    as android.content.ClipboardManager
                                            clipboard.setPrimaryClip(
                                                android.content.ClipData.newPlainText(
                                                    "Glasses live URL",
                                                    liveUrl,
                                                ),
                                            )
                                            android.widget.Toast.makeText(
                                                this@MainActivity,
                                                "Stream URL copied — open it while live preview is running",
                                                android.widget.Toast.LENGTH_SHORT,
                                            ).show()
                                        },
                                    ) {
                                        androidx.compose.material3.Text("Copy URL")
                                    }
                                    androidx.compose.foundation.layout.Spacer(
                                        modifier = androidx.compose.ui.Modifier.width(8.dp),
                                    )
                                    androidx.compose.material3.TextButton(
                                        onClick = {
                                            if (isEyevueLive) {
                                                eyevueLiveAudioMuted = true
                                                eyevueLivePreviewManager?.setAudioMuted(true)
                                            }
                                            if (isTuneBudsLive) {
                                                tuneBudsLiveAudioMuted = true
                                                tuneBudsLivePreviewManager?.setAudioMuted(true)
                                            }
                                            val uri = android.net.Uri.parse(liveUrl)
                                            val vlcIntent = android.content.Intent(
                                                android.content.Intent.ACTION_VIEW,
                                                uri,
                                            ).setPackage("org.videolan.vlc")
                                            val opened = runCatching {
                                                startActivity(vlcIntent)
                                                true
                                            }.getOrDefault(false)
                                            if (!opened) {
                                                runCatching {
                                                    startActivity(
                                                        android.content.Intent(
                                                            android.content.Intent.ACTION_VIEW,
                                                            uri,
                                                        ),
                                                    )
                                                }.onFailure {
                                                    android.widget.Toast.makeText(
                                                        this@MainActivity,
                                                        "No app found for RTSP",
                                                        android.widget.Toast.LENGTH_SHORT,
                                                    ).show()
                                                }
                                            }
                                        },
                                    ) {
                                        androidx.compose.material3.Text("Open in VLC")
                                    }
                                }
                            }
                        }
                    },
                )
                if (showOfficialHeyCyanWarningDialog) {
                    OfficialHeyCyanWarningDialog(
                        onDismissRequest = { showOfficialHeyCyanWarningDialog = false },
                        onOpenAppInfo = {
                            showOfficialHeyCyanWarningDialog = false
                            runCatching { startActivity(OfficialHeyCyanApp.appInfoIntent()) }
                                .onFailure {
                                    Toast.makeText(
                                        this@MainActivity,
                                        "Could not open HeyCyan App info.",
                                        Toast.LENGTH_LONG,
                                    ).show()
                                }
                        },
                        onSyncAnyway = { suppressFutureWarnings ->
                            showOfficialHeyCyanWarningDialog = false
                            if (suppressFutureWarnings) {
                                OfficialHeyCyanApp.suppressWarning(this@MainActivity)
                            }
                            showDownloadFlowPicker()
                        },
                    )
                }
            }
        }
        observeOtaState()
        observeLivePreviewState()
        observeWifiAdbDebugState()
        // Transcription UI moved to the "Transcriptions & recordings" section
        logLargeDataHandlerMethodsOnce()
        // Check for app updates
        VersionUpdateChecker.checkForUpdates(this)
        // Initialize TTS
        tts = TextToSpeech(this, this)
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                localSpeechSessionManager.speechQueueController.onUtteranceStart(utteranceId)
                if (utteranceId?.startsWith("image_question_cue_") == true ||
                    utteranceId?.startsWith("voice_listening_") == true
                ) {
                    Log.i("ImageQuestionAudio", "TTS cue started id=$utteranceId")
                }
            }

            override fun onDone(utteranceId: String?) {
                localSpeechSessionManager.speechQueueController.onUtteranceDone(utteranceId)
                if (utteranceId?.startsWith("image_question_cue_") == true ||
                    utteranceId?.startsWith("voice_listening_") == true
                ) {
                    Log.i("ImageQuestionAudio", "TTS cue completed id=$utteranceId")
                }
                utteranceId?.let { ttsDoneCallbacks.remove(it)?.invoke() }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                localSpeechSessionManager.speechQueueController.onUtteranceError(utteranceId)
                Log.w("ImageQuestionAudio", "TTS failed id=$utteranceId")
                utteranceId?.let { ttsDoneCallbacks.remove(it)?.invoke() }
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                localSpeechSessionManager.speechQueueController.onUtteranceError(utteranceId, errorCode)
                Log.w("ImageQuestionAudio", "TTS failed id=$utteranceId errorCode=$errorCode")
                utteranceId?.let { ttsDoneCallbacks.remove(it)?.invoke() }
            }
        })

        // Ensure we always listen for HeyCyan reports. Meta notifications come from DAT,
        // so do not register the vendor listener for a selected Meta profile.
        if (!isMetaRaybanSelected() && !isEyevueSelected() && !isTuneBudsSelected()) {
            LargeDataHandler.getInstance().addOutDeviceListener(100, deviceNotifyListener)
        }

        // Lazily register the import/download notify listener the first time we need it.
        handleMetaRegistrationIntent(intent)
        handleTaskerCommand(intent)
        maybeStartMetaImageQuestion(intent)

        BatteryOptimizationGuideActivity.launchIfNeeded(this)
    }

    override fun onStart() {
        super.onStart()
        if (!EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().register(this)
        }
        val bluetoothConnected = BleOperateManager.getInstance().isConnected
        updateConnectionStatus(bluetoothConnected)
        if (!bluetoothConnected &&
            GlassesSessionCoordinator.currentSession() == GlassesSession.WIFI_ADB_DEBUG
        ) {
            GlassesSessionCoordinator.clearForDisconnectedDevice(DeviceClass.HEY_CYAN)
            wifiAdbDebugSessionLease = null
        }
        registerMeetingCaptureReceiver()
        syncMeetingCaptureUiFromPrefs()

        if (!agentReceiverRegistered) {
            LocalBroadcastManager.getInstance(this)
                .registerReceiver(agentStatusReceiver, IntentFilter(LocalAgentIntents.ACTION_STATUS_CHANGED))
            agentReceiverRegistered = true
        }
        if (!imageAutomationStatusReceiverRegistered) {
            LocalBroadcastManager.getInstance(this).registerReceiver(
                imageAutomationStatusReceiver,
                IntentFilter(ExternalImageAutomationIntents.internalStatusAction(packageName)),
            )
            imageAutomationStatusReceiverRegistered = true
        }
        LocalAgentController.requestStatus(this)
        refreshAgentStatusUi()
    }

    override fun onStop() {
        if (BuildConfig.DEBUG) wifiAdbDebugController.stop()
        // Glasses live previews keep running in the background behind their
        // foreground service so other apps can use the relay. It stops only
        // via user Stop (dashboard, notification) or session end.
        super.onStop()
        stopBatteryPolling()
        unregisterMeetingCaptureReceiver()

        if (agentReceiverRegistered) {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(agentStatusReceiver)
            agentReceiverRegistered = false
        }
        if (imageAutomationStatusReceiverRegistered) {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(imageAutomationStatusReceiver)
            imageAutomationStatusReceiverRegistered = false
        }

        if (EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().unregister(this)
        }
    }

    override fun onDestroy() {
        cancelLocalStreamingSpeech("activity destroyed")
        val voiceQueryWasActive = voiceQueryInProgress.getAndSet(null) != null
        activeVoiceAudioRoute.getAndSet(null)?.audioManager?.let { audioManager ->
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    audioManager.clearCommunicationDevice()
                }
                audioManager.isBluetoothScoOn = false
                audioManager.stopBluetoothSco()
                audioManager.mode = android.media.AudioManager.MODE_NORMAL
            }
        }
        if (voiceQueryWasActive) finishAiQuestionForegroundWork()
        if (BuildConfig.DEBUG) wifiAdbDebugController.release()
        livePreviewDialog?.dismiss()
        livePreviewDialog = null
        livePreviewManager.release()
        eyevueLivePreviewManager?.release()
        tuneBudsLivePreviewManager?.release()
        tuneBudsLiveStopReceiver?.let { runCatching { unregisterReceiver(it) } }
        tuneBudsLiveStopReceiver = null
        eyevueLiveStopReceiver?.let { runCatching { unregisterReceiver(it) } }
        eyevueLiveStopReceiver = null
        livePreviewCoordinator.unregisterHeyCyanLiveStopReceiver()
        com.fersaiyan.cyanbridge.devices.heycyan.HeyCyanLiveForegroundService.stop(this)
        vendorMediaSync.cancelSyncJobsForDestroy(moyoungW620Manager)
        otaCoordinator.onActivityDestroyed()
        if (isMoyoungW620Selected() && GlassesSessionCoordinator.currentSession() == GlassesSession.MEDIA_SYNC) {
            moyoungW620Manager?.stopMediaSync()
            releaseExclusiveGlassesSession(mediaSessionLease)
            mediaSessionLease = null
        } else if (isEyevueSelected() && GlassesSessionCoordinator.currentSession() == GlassesSession.MEDIA_SYNC) {
            getOrCreateEyevueManager().finishTransfer()
            vendorMediaSync.disconnectEyevueTransport()
            releaseExclusiveGlassesSession(mediaSessionLease)
            mediaSessionLease = null
        } else if (GlassesSessionCoordinator.currentSession() == GlassesSession.MEDIA_SYNC) {
            mediaSync.stopForActivityDestroy()
        }
        try {
            LargeDataHandler.getInstance().removeOutDeviceListener(100)
            LargeDataHandler.getInstance().removeBatteryCallBack("init")
            batteryCallbackRegistered = false
        } catch (e: Exception) {
            Log.w("DeviceNotify", "Failed to unregister SDK callbacks", e)
        }
        super.onDestroy()
        tts?.stop()
        tts?.shutdown()
    }
    inner class PermissionCallback : OnPermissionCallback {
        override fun onGranted(permissions: MutableList<String>, all: Boolean) {
            if (!all) {
                // Permissions not fully granted; do nothing for now
            } else {
                this@MainActivity.startKtxActivity<DeviceBindActivity>()
            }
        }

        override fun onDenied(permissions: MutableList<String>, never: Boolean) {
            super.onDenied(permissions, never)
            Toast.makeText(
                this@MainActivity,
                "Bluetooth permission is required to find glasses",
                Toast.LENGTH_LONG,
            ).show()
            if (never) {
                XXPermissions.startPermissionActivity(this@MainActivity, permissions)
            }
        }

    }


    override fun onResume() {
        super.onResume()
        if (isMeizuMyvuSelected()) getOrCreateMeizuMyvuManager()
        showPendingMeizuMyvuFailureIfNeeded()
        handleExternalImageAutomationStatus()
        if (
            com.fersaiyan.cyanbridge.localmodels.remote.RemoteOpenAiPrefs.isBridgeConfigured(this) &&
            !com.fersaiyan.cyanbridge.studiobridge.StudioBridgeClient.isRunning() &&
            com.fersaiyan.cyanbridge.studiobridge.StudioApprovalHandler.canCaptureVoice(this)
        ) {
            (application as? com.fersaiyan.cyanbridge.ui.MyApplication)?.startStudioBridge()
        }
        try {
                if (!BluetoothUtils.isEnabledBluetooth(this)) {
                    val intent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        if (ActivityCompat.checkSelfPermission(
                                this,
                                Manifest.permission.BLUETOOTH_CONNECT
                            ) == PackageManager.PERMISSION_GRANTED
                        ) {
                            startActivityForResult(intent, 300)
                        }
                    } else {
                        startActivityForResult(intent, 300)
                    }
                }
        } catch (e: Exception) {
        }
        if (!hasBluetooth(this)) {
            requestBluetoothPermission(this, BluetoothPermissionCallback())
        }

        // Check for Overlay permission needed for background launch
        if (isAiHijackEnabled && !Settings.canDrawOverlays(this) && !overlayPermissionPromptShown) {
            overlayPermissionPromptShown = true
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivityForResult(intent, 1234)
            Toast.makeText(this, "Please enable Overlay permission for background AI", Toast.LENGTH_LONG).show()
        }

        refreshAiQueryButtonsState()
        refreshGeminiLiveImageDelay()
        refreshNativePluginShortcutState()
        ensureEnabledBackgroundFeaturePermissions()
        ensureEnabledMetaCameraFeature()
    }

    private fun ensureEnabledBackgroundFeaturePermissions() {
        if (enabledFeaturePermissionRequestActive) return

        val voicePluginEnabled =
            com.fersaiyan.cyanbridge.localmodels.remote.RemoteOpenAiPrefs.isBridgeConfigured(this) ||
                AutoAudioCapturePrefs.isEnabled(this) ||
                CommunityPluginPrefs.isNativePluginEnabled(this, NativePluginIds.AUTO_AUDIO)

        if (voicePluginEnabled && !PluginVoicePermissions.hasRequiredPermissions(this)) {
            enabledFeaturePermissionRequestActive = true
            PluginVoicePermissions.ensure(
                this,
                onGranted = {
                    enabledFeaturePermissionRequestActive = false
                    restartEnabledBackgroundFeatures()
                },
                onDenied = {
                    enabledFeaturePermissionRequestActive = false
                },
            )
            return
        }

        val notificationFeatureEnabled =
            WalkingAidPreferences.isEnabled(this) ||
                AutoDiaryService.isEnabled(this) ||
                VisualDiaryPreferences.isEnabled(this) ||
                LocalAgentPlugin.isEnabled(this) ||
                isMeizuMyvuSelected()
        if (notificationFeatureEnabled && !hasNotificationPermission(this)) {
            enabledFeaturePermissionRequestActive = true
            ensureNotificationPermission(this, "enabled background features") {
                enabledFeaturePermissionRequestActive = false
                restartEnabledBackgroundFeatures()
            }
            return
        }

    }

    private fun restartEnabledBackgroundFeatures() {
        if (AutoAudioCapturePrefs.isEnabled(this)) AutoAudioCaptureService.start(this)
        if (AutoDiaryService.isEnabled(this)) AutoDiaryService.startIfEnabled(this)
        startEnabledCameraFeatures()
        if (LocalAgentPlugin.isEnabled(this)) {
            LocalAgentController.requestStatus(this)
        }
        if (isMeizuMyvuSelected()) {
            DeviceProfileStore.loadLastSelected(this)?.macAddress?.let { address ->
                getOrCreateMeizuMyvuManager().connect(address, this)
            }
        }

        // Mic plugins (Live Caption Relay, Hands-Free Translator, Errand Brain) are user-controlled via
        // the glasses-tab shortcut START/STOP and must not auto-start when merely enabled in Plugins.
        // See fix for Meeting Spark Notes (afef09b) - same pattern keeps microphone off until explicit START.
        if (com.fersaiyan.cyanbridge.localmodels.remote.RemoteOpenAiPrefs.isBridgeConfigured(this)) {
            (application as? MyApplication)?.startStudioBridge()
        }
    }

    private fun ensureEnabledMetaCameraFeature() {
        if (!isMetaRaybanSelected() || !hasNotificationPermission(this)) return
        if (!WalkingAidPreferences.isEnabled(this) && !VisualDiaryPreferences.isEnabled(this)) return
        if (enabledMetaCameraCheckActive) return

        enabledMetaCameraCheckActive = true
        ensureMetaCameraReady {
            enabledMetaCameraCheckActive = false
            if (WalkingAidPreferences.isEnabled(this)) WalkingAidService.start(this)
            if (VisualDiaryPreferences.isEnabled(this)) VisualDiaryService.startIfEnabled(this)
        }
    }

    private fun startEnabledCameraFeatures() {
        if (isMetaRaybanSelected()) {
            if (WalkingAidPreferences.isEnabled(this) || VisualDiaryPreferences.isEnabled(this)) {
                ensureEnabledMetaCameraFeature()
            }
            return
        }
        if (WalkingAidPreferences.isEnabled(this)) WalkingAidService.start(this)
        if (VisualDiaryPreferences.isEnabled(this)) VisualDiaryService.startIfEnabled(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (handleMetaRegistrationIntent(intent)) {
            updateMetaRaybanUiState()
        }
        handleTaskerCommand(intent)
        maybeStartMetaImageQuestion(intent)
    }

    private fun handleMetaRegistrationIntent(callbackIntent: Intent): Boolean {
        if (!callbackIntent.data?.scheme.equals("cyanbridge", ignoreCase = true)) return false
        val manager = getOrCreateMetaRaybanManager()
        return manager.handleRegistrationCallback(callbackIntent)
    }

    private fun maybeStartMetaImageQuestion(sourceIntent: Intent) {
        if (!sourceIntent.getBooleanExtra(EXTRA_START_META_IMAGE_QUESTION, false)) return
        sourceIntent.removeExtra(EXTRA_START_META_IMAGE_QUESTION)
        binding.root.post(::startImageQuestionFromUi)
    }

    private fun startImageQuestionFromUi() {
        if (showAssistantSetupIfNeeded(AssistantTestKind.IMAGE)) return
        if (maybeShowGeminiChatGptImageRequirementsWarning()) return
        triggerCliRelayImageCaptureAndQuery()
    }

    private fun startVoiceQuestionFromUi() {
        if (showAssistantSetupIfNeeded(AssistantTestKind.VOICE)) return
        triggerAssistantVoiceQuery()
    }

    private fun showAssistantSetupIfNeeded(kind: AssistantTestKind): Boolean {
        val issue = AssistantTestReadiness.blockingIssue(this, currentAssistantRoute(), kind)
        if (issue != null) {
            AlertDialog.Builder(this)
                .setTitle(issue.title)
                .setMessage(issue.message)
                .setNegativeButton("Not now", null)
                .setPositiveButton(issue.actionLabel) { _, _ ->
                    val destination = when (issue.destination) {
                        AssistantSetupDestination.LOCAL_MODELS -> LocalModelsConfigureActivity::class.java
                        AssistantSetupDestination.PRO_SUBSCRIPTION -> ProSubscriptionActivity::class.java
                    }
                    startActivity(Intent(this, destination))
                }
                .show()
            return true
        }
        if (currentAssistantRoute() == GlassesAssistantRoute.TASKER_EXTERNAL_UI) {
            val capability = ExternalAssistantAutomationInspector.inspect(this)
            val reason = when (kind) {
                AssistantTestKind.VOICE -> ExternalAssistantAutomationPolicy.voiceBlockingReason(capability)
                AssistantTestKind.IMAGE -> ExternalAssistantAutomationPolicy.imageBlockingReason(capability)
            }
            if (reason != null) {
                AlertDialog.Builder(this)
                    .setTitle("Tasker setup required")
                    .setMessage(reason)
                    .setNegativeButton("Not now", null)
                    .setPositiveButton("Open setup") { _, _ ->
                        startActivity(Intent(this, ExternalAssistantAutomationSetupActivity::class.java))
                    }
                    .show()
                return true
            }
        }
        return false
    }

    private fun getOrCreateMetaRaybanManager(): com.fersaiyan.cyanbridge.devices.metarayban.MetaRaybanManager {
        return metaRaybanManager
            ?: com.fersaiyan.cyanbridge.devices.metarayban.MetaRaybanManager
                .getInstance(this)
                .also { manager ->
                    metaRaybanManager = manager
                    observeMetaRaybanState(manager)
                }
    }

    private fun observeMetaRaybanState(
        manager: com.fersaiyan.cyanbridge.devices.metarayban.MetaRaybanManager,
    ) {
        if (metaRaybanUiJob != null) return
        metaRaybanUiJob = lifecycleScope.launch {
            merge(
                manager.registrationState.map { Unit },
                manager.deviceSessionState.map { Unit },
                manager.streamState.map { Unit },
                manager.isDisplayActive.map { Unit },
                manager.selectedDeviceIsDisplayCapable.map { Unit },
                manager.availableDeviceCount.map { Unit },
                manager.selectedDeviceName.map { Unit },
                manager.lastError.map { Unit },
            ).collect {
                updateMetaRaybanUiState()
                if (isMetaRaybanSelected()) updateConnectionStatus(false)
            }
        }
    }

    private fun getOrCreateMeizuMyvuManager(): MeizuMyvuManager =
        meizuMyvuManager ?: MeizuMyvuManager.getInstance(this).also { manager ->
            meizuMyvuManager = manager
            if (meizuMyvuUiJob == null) {
                meizuMyvuUiJob = lifecycleScope.launch {
                    manager.state.collect { state ->
                        if (state.relayReady) {
                            pendingMeizuMyvuFailure = null
                        }
                        updateMeizuMyvuUiState()
                        if (isMeizuMyvuSelected()) updateConnectionStatus(false)
                    }
                }
            }
            if (meizuMyvuFailureJob == null) {
                meizuMyvuFailureJob = lifecycleScope.launch {
                    manager.failurePrompt.collect { failure ->
                        if (failure == null) return@collect
                        pendingMeizuMyvuFailure = failure
                        showPendingMeizuMyvuFailureIfNeeded()
                    }
                }
            }
        }

    private fun showPendingMeizuMyvuFailureIfNeeded() {
        val failure = pendingMeizuMyvuFailure ?: meizuMyvuManager?.failurePrompt?.value ?: return
        if (!isMeizuMyvuSelected()) {
            pendingMeizuMyvuFailure = null
            meizuMyvuManager?.consumeFailurePrompt()
            return
        }
        if (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) return

        val manager = getOrCreateMeizuMyvuManager()
        pendingMeizuMyvuFailure = null
        manager.consumeFailurePrompt()
        DebugLogSupport.showSupportOptionsDialog(
            activity = this,
            title = "MYVU connection failed",
            issueType = "Meizu MYVU connection failure",
            description = buildString {
                appendLine("CyanBridge could not finish the MYVU connection during ${failure.stage}.")
                appendLine()
                appendLine("Reason: ${failure.reason}")
                appendLine()
                append("Force-stop the official MYVU app, confirm the glasses are paired in Android Bluetooth settings, and toggle Bluetooth off and on if RFCOMM remains stuck. You can send the redacted diagnostics below to CyanBridge support.")
            },
            extraInfo = linkedMapOf(
                "myvu_failure_stage" to failure.stage,
                "myvu_failure_reason" to failure.reason,
            ),
            dismissButtonLabel = "Later",
        )
    }

    private fun getOrCreateEyevueManager(): EyevueManager =
        eyevueManager ?: EyevueManager.getInstance(this).also { manager ->
            eyevueManager = manager
            if (eyevueUiJob == null) {
                eyevueUiJob = lifecycleScope.launch {
                    manager.state.collect { eyevue ->
                        if (!isEyevueSelected()) return@collect
                        binding.statusText.text = eyevue.connectionLabel
                        binding.storageText.text = eyevue.storageCount?.toString() ?: "--"
                        updateBatteryText(eyevue.batteryPercent)
                        updateDashboardState { state ->
                            state.copy(
                                connectionLabel = eyevue.connectionLabel,
                                batteryPercent = eyevue.batteryPercent,
                                storageLabel = eyevue.storageCount?.toString() ?: "--",
                            )
                        }
                    }
                }
            }
            if (eyevueWakeWordJob == null) {
                eyevueWakeWordJob = lifecycleScope.launch {
                    manager.wakeWordEvents.collect {
                        if (isEyevueSelected() && isAiHijackEnabled) {
                            handleAiWakeWordActivation("eyevue")
                        }
                    }
                }
            }
        }

    private fun getOrCreateTuneBudsManager(): TuneBudsManager =
        tuneBudsManager ?: TuneBudsManager.getInstance(this).also { manager ->
            tuneBudsManager = manager
            if (tuneBudsUiJob == null) {
                tuneBudsUiJob = lifecycleScope.launch {
                    manager.state.collect { tuneBuds ->
                        if (!isTuneBudsSelected()) return@collect
                        val storage = tuneBuds.storage?.let {
                            "${it.usedMiB} MiB used / ${it.freeMiB} MiB free"
                        } ?: "--"
                        val counts = tuneBuds.mediaCounts
                        val deviceInfo = listOfNotNull(
                            tuneBuds.model?.let { "Model: $it" },
                            tuneBuds.firmwareVersion?.let { "Firmware: $it" },
                            tuneBuds.coprocessorVersion?.let { "Coprocessor: $it" },
                            counts?.let { "Media: ${it.images} photos / ${it.videos} videos / ${it.audio} audio" },
                        ).joinToString("  ").ifBlank { null }
                        binding.statusText.text = tuneBuds.connectionLabel
                        binding.storageText.text = storage
                        updateBatteryText(tuneBuds.batteryPercent)
                        updateDashboardState { state ->
                            state.copy(
                                connectionLabel = tuneBuds.connectionLabel,
                                batteryPercent = tuneBuds.batteryPercent,
                                storageLabel = storage,
                                deviceInfoLabel = deviceInfo,
                                isVideoRecording = tuneBuds.isVideoRecording,
                                isAudioRecording = tuneBuds.isAudioRecording,
                                transfer = state.transfer.copy(
                                    countsLabel = counts?.let {
                                        "Photos: ${it.images}  Videos: ${it.videos}  Audio: ${it.audio}"
                                    } ?: state.transfer.countsLabel,
                                ),
                            )
                        }
                    }
                }
            }
        }

    private fun getOrCreateTuneBudsLivePreviewManager(): TuneBudsLivePreviewManager =
        tuneBudsLivePreviewManager ?: TuneBudsLivePreviewManager(this, getOrCreateTuneBudsManager()).also { manager ->
            tuneBudsLivePreviewManager = manager
            tuneBudsLivePreviewUiJob = lifecycleScope.launch {
                manager.uiState.collect { lp ->
                    if (!isTuneBudsSelected()) return@collect
                    dashboardState = dashboardState.copy(
                        livePreview = com.fersaiyan.cyanbridge.shared.glasses.LivePreviewUiState(
                            isAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O,
                            stateLabel = lp.stateLabel, detail = lp.detail,
                            isScanning = lp.isScanning, isPlaying = lp.isPlaying,
                            streamUrl = lp.streamUrl, canStart = lp.canStart, canStop = lp.canStop,
                        ),
                    )
                    tuneBudsLivePlayer = if (lp.isPlaying) manager.getPlayer() else null
                }
            }
            val receiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
                    manager.stop()
                }
            }
            tuneBudsLiveStopReceiver = receiver
            androidx.core.content.ContextCompat.registerReceiver(this, receiver,
                android.content.IntentFilter(TuneBudsLiveForegroundService.ACTION_TUNEBUDS_LIVE_STOP),
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        }

    private fun getOrCreateMoyoungW620Manager(): MoyoungW620Manager =
        moyoungW620Manager ?: MoyoungW620Manager.getInstance(this).also { manager ->
            moyoungW620Manager = manager
            if (moyoungW620UiJob == null) {
                moyoungW620UiJob = lifecycleScope.launch {
                    manager.state.collect { moyoung ->
                        if (!isMoyoungW620Selected()) return@collect
                        val counts = listOfNotNull(
                            moyoung.photoCount?.let { "$it photos" },
                            moyoung.videoCount?.let { "$it videos" },
                            moyoung.audioCount?.let { "$it audio" },
                        ).joinToString(" / ").ifBlank { "--" }
                        binding.statusText.text = moyoung.connectionLabel
                        binding.storageText.text = counts
                        updateBatteryText(moyoung.batteryPercent)
                        updateDashboardState { state ->
                            state.copy(
                                connectionLabel = moyoung.connectionLabel,
                                batteryPercent = moyoung.batteryPercent,
                                storageLabel = counts,
                                transfer = state.transfer.copy(countsLabel = counts),
                            )
                        }
                    }
                }
            }
        }

    private fun getOrCreateEyevueLivePreviewManager(): EyevueLivePreviewManager =
        eyevueLivePreviewManager ?: EyevueLivePreviewManager(
            context = this,
            eyevueManager = getOrCreateEyevueManager(),
        ).also { manager ->
            eyevueLivePreviewManager = manager
            if (eyevueLivePreviewUiJob == null) {
                eyevueLivePreviewUiJob = lifecycleScope.launch {
                    manager.uiState.collect { lp ->
                        if (!isEyevueSelected()) return@collect
                        dashboardState = dashboardState.copy(
                            livePreview = com.fersaiyan.cyanbridge.shared.glasses.LivePreviewUiState(
                                isAvailable = EyevueLivePreviewPolicy.isSupported(Build.VERSION.SDK_INT),
                                stateLabel = lp.stateLabel,
                                detail = lp.detail,
                                isScanning = lp.isScanning,
                                isPlaying = lp.isPlaying,
                                streamUrl = lp.streamUrl,
                                canStart = lp.canStart,
                                canStop = lp.canStop,
                            ),
                        )
                        // Inline video (EyeVue only): expose the player to
                        // Compose after live starts. No popup dialog.
                        // Dismiss any legacy dialog from older builds.
                        livePreviewDialog?.let { dialog ->
                            livePreviewDialog = null
                            runCatching { dialog.dismiss() }
                        }
                        eyevueLivePlayer = if (lp.isPlaying) manager.getPlayer() else null
                    }
                }
            }
            if (eyevueLiveStopReceiver == null) {
                val receiver = object : android.content.BroadcastReceiver() {
                    override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
                        eyevueLivePreviewManager?.stop()
                    }
                }
                eyevueLiveStopReceiver = receiver
                androidx.core.content.ContextCompat.registerReceiver(
                    this@MainActivity,
                    receiver,
                    android.content.IntentFilter(
                        com.fersaiyan.cyanbridge.devices.eyevue.EyevueLiveForegroundService.ACTION_EYEVUE_LIVE_STOP,
                    ),
                    androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
                )
            }
        }

    private fun metaAndroidPermissionsMissing(): Array<String> {
        return MetaDatPermissions.missing(this)
    }

    private fun ensureMetaDatReady(action: () -> Unit) {
        val missing = metaAndroidPermissionsMissing()
        if (missing.isNotEmpty()) {
            if (MetaDatPermissions.permanentlyDenied(this)) {
                showMetaError("Android permissions", "Allow Camera and Nearby devices (Location on Android 10–11) in CyanBridge's Android app settings, then try again.")
                return
            }
            pendingMetaDatAction = action
            metaAndroidPermissionLauncher.launch(missing)
            return
        }

        val manager = getOrCreateMetaRaybanManager()
        manager.initialize()
        if (manager.isInitialized.value) {
            action()
        } else {
            showMetaError(
                "Android/DAT initialization",
                manager.lastError.value ?: "Unable to initialize Meta Wearables DAT",
            )
        }
    }

    private fun ensureMetaCameraReady(action: () -> Unit) {
        if (metaCameraReadinessInProgress) return
        metaCameraReadinessInProgress = true
        ensureMetaDatReady {
            val manager = getOrCreateMetaRaybanManager()
            if (manager.isDebugMockEnabled()) {
                metaCameraReadinessInProgress = false
                action()
                return@ensureMetaDatReady
            }
            lifecycleScope.launch {
                if (!manager.awaitCameraReady()) {
                    showMetaError("Glasses connection", "Reconnect your glasses in Meta AI, then return to CyanBridge.")
                    return@launch
                }
                manager.checkCameraPermission(
                    onGranted = { metaCameraReadinessInProgress = false; action() },
                    onRequestNeeded = {
                        pendingMetaCameraAction = action
                        metaWearablePermissionLauncher.launch(Permission.CAMERA)
                    },
                    onError = { error -> showMetaError("DAT camera permission", error) },
                )
            }
        }
    }

    inner class BluetoothPermissionCallback : OnPermissionCallback {
        override fun onGranted(permissions: MutableList<String>, all: Boolean) {
            if (all) {
                AutoPairManager.requestConnect(this@MainActivity, reason = "bluetooth_permission_granted")
            }
        }

        override fun onDenied(permissions: MutableList<String>, never: Boolean) {
            super.onDenied(permissions, never)
            Toast.makeText(
                this@MainActivity,
                "Bluetooth permission is required to reconnect to glasses",
                Toast.LENGTH_LONG,
            ).show()
            if (never) {
                XXPermissions.startPermissionActivity(this@MainActivity, permissions)
            }
        }

    }

    private fun ensureBluetoothPermission(feature: String, onGranted: () -> Unit) {
        if (hasBluetooth(this)) {
            onGranted()
            return
        }

        requestBluetoothPermission(this, object : OnPermissionCallback {
            override fun onGranted(permissions: MutableList<String>, all: Boolean) {
                if (all) {
                    onGranted()
                } else {
                    Toast.makeText(
                        this@MainActivity,
                        "Bluetooth permission is required for $feature",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }

            override fun onDenied(permissions: MutableList<String>, never: Boolean) {
                super.onDenied(permissions, never)
                Toast.makeText(
                    this@MainActivity,
                    "Bluetooth permission is required for $feature",
                    Toast.LENGTH_LONG,
                ).show()
                if (never) {
                    XXPermissions.startPermissionActivity(this@MainActivity, permissions)
                }
            }
        })
    }

    private fun ensureGlassesTransportPermissions(feature: String, onGranted: () -> Unit) {
        ensureBluetoothPermission(feature) {
            if (hasWifiP2pPermission(this)) {
                onGranted()
                return@ensureBluetoothPermission
            }

            // Show Material 3 Expressive dialog first (permission rationale) instead of silent Toast.
            // The dialog explains Wi-Fi Direct + Nearby devices/Location need for TuneBuds Detailed
            // (and similar Wi-Fi flows) and offers Grant/Cancel.
            pendingTransportPermissionAction = onGranted
            updateDashboardState { state ->
                state.copy(
                    showTransportPermissionDialog = true,
                    transportPermissionFeature = feature,
                )
            }
        }
    }

    private fun requestPendingTransportPermission() {
        val onGranted = pendingTransportPermissionAction ?: return
        pendingTransportPermissionAction = null
        // Keep dialog visible state reset; request callback will handle success/denial UI.
        updateDashboardState { state -> state.copy(showTransportPermissionDialog = false) }
        requestWifiP2pPermission(this, object : OnPermissionCallback {
            override fun onGranted(permissions: MutableList<String>, all: Boolean) {
                if (all) {
                    onGranted()
                } else {
                    // User granted via system sheet but not all - surface via dialog state + toast fallback
                    updateDashboardState { state ->
                        state.copy(
                            showTransportPermissionDialog = true,
                            transportPermissionFeature = dashboardState.transportPermissionFeature,
                        )
                    }
                    Toast.makeText(
                        this@MainActivity,
                        "Nearby devices or Location permission is required",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }

            override fun onDenied(permissions: MutableList<String>, never: Boolean) {
                super.onDenied(permissions, never)
                updateDashboardState { state -> state.copy(showTransportPermissionDialog = false) }
                Toast.makeText(
                    this@MainActivity,
                    "Nearby devices or Location permission is required",
                    Toast.LENGTH_LONG,
                ).show()
                if (never) {
                    XXPermissions.startPermissionActivity(this@MainActivity, permissions)
                } else {
                    // Re-show rationale dialog so user can retry, instead of failing silently
                    pendingTransportPermissionAction = onGranted
                    updateDashboardState { state ->
                        state.copy(
                            showTransportPermissionDialog = true,
                            transportPermissionFeature = state.transportPermissionFeature,
                        )
                    }
                }
            }
        })
    }

    private fun updateDashboardState(
        transform: (GlassesDashboardUiState) -> GlassesDashboardUiState,
    ) {
        runOnUiThread {
            dashboardState = transform(dashboardState)
        }
    }

    private fun acquireExclusiveGlassesSession(session: GlassesSession): GlassesSessionLease? {
        val lease = GlassesSessionCoordinator.tryAcquireLease(session)
        if (lease != null) {
            Log.i("GlassesSession", "Acquired ${session.label} session")
            return lease
        }

        val activeSession = GlassesSessionCoordinator.currentSession()
        if (activeSession == null) {
            Log.w("GlassesSession", "Could not acquire ${session.label} session; a one-shot glasses command is still active")
            Toast.makeText(
                this,
                "Waiting for the current glasses command to finish.",
                Toast.LENGTH_SHORT,
            ).show()
            return null
        }

        Log.w(
            "GlassesSession",
            "Cannot start ${session.label}; ${activeSession.label} still owns BLE/P2P",
        )
        Toast.makeText(
            this,
            "Stop ${activeSession.label} before starting ${session.label}.",
            Toast.LENGTH_LONG,
        ).show()
        return null
    }

    private fun releaseExclusiveGlassesSession(lease: GlassesSessionLease?) {
        if (lease != null && GlassesSessionCoordinator.release(lease)) {
            if (mediaSessionLease === lease) mediaSessionLease = null
            if (otaSessionLease === lease) otaSessionLease = null
            if (livePreviewSessionLease === lease) livePreviewSessionLease = null
            if (wifiAdbDebugSessionLease === lease) wifiAdbDebugSessionLease = null
            Log.i("GlassesSession", "Released ${lease.session.label} session")
        }
    }

    private fun isGlassesCommandBlocked(source: String): Boolean {
        val activeSession = GlassesSessionCoordinator.currentSession() ?: return false
        if (activeSession == GlassesSession.META_CAMERA &&
            isMetaRaybanSelected() &&
            source == "voice-query command"
        ) {
            // Meta voice queries use Android's audio route and do not contend for the
            // HeyCyan SDK response slot owned by the DAT camera session.
            return false
        }
        Log.w(
            "GlassesSession",
            "Skipping $source; ${activeSession.label} owns the SDK BLE/P2P slots",
        )
        return true
    }

    private fun acquireBackgroundGlassesCommand(source: String): BackgroundGlassesCommandPermit? {
        val permit = GlassesSessionCoordinator.tryAcquireBackgroundCommand()
        if (permit == null) {
            val owner = GlassesSessionCoordinator.currentSession()?.label ?: "another glasses command"
            Log.w("GlassesSession", "Skipping $source; $owner owns the SDK BLE/P2P slots")
        }
        return permit
    }

    private fun warnIfBackgroundGlassesCommandTimesOut(
        permit: BackgroundGlassesCommandPermit,
        timeoutMs: Long = ONE_SHOT_BLE_COMMAND_TIMEOUT_MS,
    ) {
        glassesTeardownScope.launch {
            delay(timeoutMs)
            if (GlassesSessionCoordinator.isBackgroundCommandActive(permit)) {
                Log.w(
                    "GlassesSession",
                    "Glasses command timed out; keeping the SDK response slot isolated until its response or Bluetooth reconnect",
                )
            }
        }
    }

    private fun isDashboardActionBlockedByExclusiveSession(action: GlassesDashboardAction): Boolean {
        if (
            action is GlassesDashboardAction.SubmitFirmwarePatchRequest ||
            action is GlassesDashboardAction.SelectImageThumbnailQuality ||
            action is GlassesDashboardAction.SetGeminiLiveImageDelay ||
            action is GlassesDashboardAction.SetAiWakeWordRoute ||
            action == GlassesDashboardAction.DismissFirmwarePatchRequest ||
            action == GlassesDashboardAction.MetaSendDiagnostics
        ) {
            return false
        }
        val activeSession = GlassesSessionCoordinator.currentSession() ?: return false
        val isAllowed = if (activeSession == GlassesSession.WIFI_ADB_DEBUG) {
            action == GlassesDashboardAction.StopWifiAdbDebug
        } else if (activeSession == GlassesSession.META_CAMERA) {
            when (action) {
                is GlassesDashboardAction.Navigate,
                GlassesDashboardAction.StartMeetingCapture,
                GlassesDashboardAction.StopMeetingCapture,
                is GlassesDashboardAction.RunNativePluginShortcut,
                is GlassesDashboardAction.SelectAssistantMode,
                GlassesDashboardAction.TestVoiceQuestion,
                GlassesDashboardAction.TestImageQuestion,
                GlassesDashboardAction.OpenExternalImageAutomationDiagnostics,
                GlassesDashboardAction.StartAgent,
                GlassesDashboardAction.StopAgent,
                GlassesDashboardAction.RunAgentDemo,
                GlassesDashboardAction.MetaStopSession,
                GlassesDashboardAction.MetaStopStream,
                GlassesDashboardAction.MetaStopDisplay,
                GlassesDashboardAction.MetaCapturePhoto,
                GlassesDashboardAction.MetaViewPhoto,
                GlassesDashboardAction.MetaStartSession,
                GlassesDashboardAction.MetaStartStream,
                GlassesDashboardAction.MetaStartDisplay,
                GlassesDashboardAction.MetaSendDiagnostics -> true
                else -> false
            }
        } else {
            when (action) {
                is GlassesDashboardAction.Navigate -> true
                GlassesDashboardAction.StopSync -> activeSession == GlassesSession.MEDIA_SYNC
                GlassesDashboardAction.StopLivePreview -> activeSession == GlassesSession.LIVE_PREVIEW
                GlassesDashboardAction.CancelOta -> activeSession == GlassesSession.OTA
                else -> false
            }
        }
        if (isAllowed) return false

        Toast.makeText(
            this,
            "${activeSession.label.replaceFirstChar { it.uppercase() }} is using the glasses connection.",
            Toast.LENGTH_SHORT,
        ).show()
        return true
    }

    private fun handleDashboardAction(action: GlassesDashboardAction) {
        if (isDashboardActionBlockedByExclusiveSession(action)) return
        if (isTuneBudsSelected() && !action.isSupportedForTuneBudsDashboard()) {
            Log.i("Dashboard", "Ignoring unsupported TuneBuds dashboard action: $action")
            return
        }
        when (action) {
            is GlassesDashboardAction.Navigate -> navigateToDestination(action.destination)
            GlassesDashboardAction.Scan -> {
                if (isMoyoungW620Selected()) {
                    startKtxActivity<DeviceBindActivity>()
                } else if (isEyevueSelected()) {
                    startKtxActivity<DeviceBindActivity>()
                } else if (isTuneBudsSelected()) {
                    startKtxActivity<DeviceBindActivity>()
                } else if (isMeizuMyvuSelected()) {
                    startKtxActivity<DeviceBindActivity>()
                } else if (isMetaRaybanSelected()) {
                    startKtxActivity<DeviceBindActivity>()
                } else {
                    binding.btnScan.performClick()
                }
            }
            GlassesDashboardAction.Reconnect -> {
                if (isMoyoungW620Selected()) {
                    DeviceProfileStore.loadLastSelected(this)?.let { profile ->
                        getOrCreateMoyoungW620Manager().connect(profile.macAddress, profile.advertisedName)
                    } ?: startKtxActivity<DeviceBindActivity>()
                } else if (isEyevueSelected()) {
                    DeviceProfileStore.loadLastSelected(this)?.let { profile ->
                        getOrCreateEyevueManager().connect(profile.macAddress, profile.advertisedName)
                    } ?: startKtxActivity<DeviceBindActivity>()
                } else if (isTuneBudsSelected()) {
                    DeviceProfileStore.loadLastSelected(this)?.let { profile ->
                        getOrCreateTuneBudsManager().connect(profile.macAddress, profile.advertisedName)
                    } ?: startKtxActivity<DeviceBindActivity>()
                } else if (isMeizuMyvuSelected()) {
                    DeviceProfileStore.loadLastSelected(this)?.macAddress?.let {
                        getOrCreateMeizuMyvuManager().connect(it, this, userInitiated = true)
                    } ?: startKtxActivity<DeviceBindActivity>()
                } else if (isMetaRaybanSelected()) {
                    startActivity(Intent(this, MetaPairingActivity::class.java))
                } else {
                    binding.btnConnect.performClick()
                }
            }
            GlassesDashboardAction.Disconnect -> {
                if (isMoyoungW620Selected()) {
                    AutoPairManager.setAutoReconnectSuppressed(true, reason = "user_disconnect_button")
                    getOrCreateMoyoungW620Manager().disconnect()
                } else if (isEyevueSelected()) {
                    getOrCreateEyevueManager().disconnect()
                } else if (isTuneBudsSelected()) {
                    AutoPairManager.setAutoReconnectSuppressed(true, reason = "user_disconnect_button")
                    getOrCreateTuneBudsManager().disconnect()
                } else if (isMeizuMyvuSelected()) {
                    getOrCreateMeizuMyvuManager().disconnect()
                } else if (isMetaRaybanSelected()) {
                    metaRaybanManager?.stopSession()
                    updateConnectionStatus(false)
                } else {
                    binding.btnDisconnect.performClick()
                }
            }
            is GlassesDashboardAction.SelectMeetingTimer -> {
                val index = action.index.coerceIn(0, meetingTimerOptions.lastIndex)
                binding.spinnerMeetingTimer.setSelection(index)
                updateDashboardState { state ->
                    state.copy(meeting = state.meeting.copy(timerIndex = index))
                }
            }
            GlassesDashboardAction.StartMeetingCapture -> binding.btnMeetingStart.performClick()
            GlassesDashboardAction.StopMeetingCapture -> binding.btnMeetingStop.performClick()
            is GlassesDashboardAction.RunNativePluginShortcut -> {
                runNativePluginShortcut(action.action)
            }
            is GlassesDashboardAction.SelectAssistantMode -> when (action.mode) {
                GlassesAssistantMode.PHONE_ASSISTANT -> selectPhoneAssistant()
                GlassesAssistantMode.CUSTOM_AI_PROVIDER -> selectCustomAiProvider()
            }
            is GlassesDashboardAction.SetAiWakeWordRoute -> {
                if (isHeyCyanOrEyevueSelected()) {
                    AiWakeWordPreferences.setRoute(this, action.route)
                    updateDashboardState { state -> state.copy(aiWakeWordRoute = action.route) }
                }
            }
            is GlassesDashboardAction.SelectImageThumbnailQuality -> {
                if (!isHeyCyanSelected() && !isTuneBudsSelected()) return
                val quality = ImageQuestionPreferences.setThumbnailQuality(this, action.sdkValue)
                pendingImageThumbnailQuality = quality
                updateDashboardState { state ->
                    state.copy(
                        imageThumbnailQualitySdkValue = quality.sdkValue,
                        imageThumbnailQualityLabel = quality.label,
                    )
                }
            }
            is GlassesDashboardAction.SetGeminiLiveImageDelay -> {
                val seconds = GeminiLiveVisionPreferences.setImageDelaySeconds(this, action.seconds)
                updateDashboardState { state -> state.copy(geminiLiveImageDelaySeconds = seconds) }
            }
            GlassesDashboardAction.TestVoiceQuestion -> binding.btnTestHijackVoice.performClick()
            GlassesDashboardAction.TestImageQuestion -> binding.btnTestHijackImage.performClick()
            GlassesDashboardAction.OpenExternalImageAutomationDiagnostics -> {
                startActivity(Intent(this, ExternalAssistantAutomationSetupActivity::class.java))
            }
            GlassesDashboardAction.CapturePhoto -> if (isEyevueSelected()) {
                getOrCreateEyevueManager().takeManualPhoto()
            } else if (isTuneBudsSelected()) {
                getOrCreateTuneBudsManager().takePhoto()
            } else {
                binding.btnCamera.performClick()
            }
            GlassesDashboardAction.ToggleVideo -> if (isEyevueSelected()) {
                getOrCreateEyevueManager().toggleVideo()
            } else if (isTuneBudsSelected()) {
                getOrCreateTuneBudsManager().toggleVideo()
            } else {
                binding.btnVideo.performClick()
            }
            GlassesDashboardAction.StartAudioRecording -> if (isEyevueSelected()) {
                getOrCreateEyevueManager().toggleAudio()
            } else if (isTuneBudsSelected()) {
                getOrCreateTuneBudsManager().toggleAudio()
            } else {
                binding.btnRecord.performClick()
            }
            GlassesDashboardAction.RequestMediaCount -> if (isEyevueSelected()) {
                Toast.makeText(this, "Requesting media count…", Toast.LENGTH_SHORT).show()
                lifecycleScope.launch {
                    val count = try {
                        getOrCreateEyevueManager().awaitMediaCount()
                    } catch (_: Exception) {
                        null
                    }
                    Toast.makeText(
                        this@MainActivity,
                        if (count != null) "Media count: $count" else "Eyevue did not report a media count",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            } else if (isTuneBudsSelected()) {
                getOrCreateTuneBudsManager().requestMediaCount()
            } else if (isMoyoungW620Selected()) {
                getOrCreateMoyoungW620Manager().requestMediaCount()
            } else {
                binding.btnMediaCount.performClick()
            }
            GlassesDashboardAction.StartSync -> if (isEyevueSelected()) {
                startEyevueMediaSync()
            } else if (isTuneBudsSelected()) {
                startTuneBudsMediaSync()
            } else if (isMoyoungW620Selected()) {
                startMoyoungW620MediaSync()
            } else {
                binding.btnDataDownload.performClick()
            }
            GlassesDashboardAction.StopSync -> if (isEyevueSelected()) {
                stopEyevueMediaSync()
            } else if (isTuneBudsSelected()) {
                stopTuneBudsMediaSync()
            } else if (isMoyoungW620Selected()) {
                stopMoyoungW620MediaSync()
            } else {
                binding.btnTransferStop.performClick()
            }
            GlassesDashboardAction.ToggleAdvanced -> {
                if (!dashboardState.showAdvancedControls) return
                binding.btnToggleAdvanced.performClick()
                updateDashboardState { state ->
                    state.copy(advancedExpanded = !state.advancedExpanded)
                }
            }
            GlassesDashboardAction.StartAgent -> LocalAgentController.requestStatus(this)
            GlassesDashboardAction.StopAgent -> binding.btnAgentStop.performClick()
            GlassesDashboardAction.RunAgentDemo -> binding.btnAgentDemo.performClick()
            GlassesDashboardAction.RequestBattery -> if (isEyevueSelected()) {
                Toast.makeText(this, "Requesting battery level…", Toast.LENGTH_SHORT).show()
                lifecycleScope.launch {
                    val percent = try {
                        getOrCreateEyevueManager().awaitBatteryPercent()
                    } catch (_: Exception) {
                        null
                    }
                    Toast.makeText(
                        this@MainActivity,
                        if (percent != null) "Eyevue battery: $percent%" else "Eyevue did not report battery",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            } else if (isTuneBudsSelected()) {
                getOrCreateTuneBudsManager().requestBattery()
            } else if (isMoyoungW620Selected()) {
                getOrCreateMoyoungW620Manager().requestBattery()
            } else {
                binding.btnBattery.performClick()
            }
            GlassesDashboardAction.RequestVersion -> if (isEyevueSelected()) {
                getOrCreateEyevueManager().requestDeviceInfo()
            } else if (isTuneBudsSelected()) {
                getOrCreateTuneBudsManager().requestVersion()
            } else {
                binding.btnVersion.performClick()
            }
            GlassesDashboardAction.SyncTime -> if (isEyevueSelected()) {
                getOrCreateEyevueManager().syncTime()
            } else if (isTuneBudsSelected()) {
                getOrCreateTuneBudsManager().syncTime()
            } else {
                binding.btnSetTime.performClick()
            }
            GlassesDashboardAction.RequestVolume -> if (isEyevueSelected()) {
                getOrCreateEyevueManager().requestVolume()
            } else {
                binding.btnVolume.performClick()
            }
            GlassesDashboardAction.AddDeviceListener -> if (isEyevueSelected()) {
                getOrCreateEyevueManager().requestSupportFunction()
            } else {
                binding.btnAddListener.performClick()
            }
            GlassesDashboardAction.StartClassicBluetoothScan -> binding.btnBt.performClick()
            GlassesDashboardAction.DumpOtaInfo -> binding.btnOtaInfo.performClick()
            GlassesDashboardAction.TestPullOta -> binding.btnPullOtaTest.performClick()
            is GlassesDashboardAction.RequestOtaFirmware -> requestOtaFirmware(action.source)
            is GlassesDashboardAction.SubmitFirmwarePatchRequest -> {
                val request = dashboardState.firmwarePatchRequest ?: return
                if (request.isSubmitting) return
                dashboardState = dashboardState.copy(
                    firmwarePatchRequest = request.copy(
                        isSubmitting = true,
                        submissionError = null,
                    ),
                )
                submitFirmwarePatchRequest(request, action.contactEmail)
            }
            GlassesDashboardAction.DismissFirmwarePatchRequest -> {
                if (dashboardState.firmwarePatchRequest?.isSubmitting != true) {
                    dashboardState = dashboardState.copy(firmwarePatchRequest = null)
                }
            }
            GlassesDashboardAction.CancelOta -> otaCoordinator.cancelFromDashboard()
            GlassesDashboardAction.StartLivePreview -> if (isEyevueSelected()) {
                startEyevueLivePreview()
            } else if (isTuneBudsSelected()) {
                startTuneBudsLivePreview()
            } else if (isHeyCyanSelected()) {
                startLivePreview()
            }
            GlassesDashboardAction.StopLivePreview -> {
                Log.i("LivePreview", "BUTTON TAP: Stop Live Preview")
                if (isEyevueSelected()) {
                    if (eyevueLivePreviewManager?.isActive == true) {
                        getOrCreateEyevueLivePreviewManager().stop()
                    }
                    com.fersaiyan.cyanbridge.devices.eyevue.EyevueLiveForegroundService.stop(this)
                    releaseExclusiveGlassesSession(livePreviewSessionLease)
                    livePreviewSessionLease = null
                } else if (isTuneBudsSelected()) {
                    tuneBudsLivePreviewManager?.stop()
                    // Release the session and foreground service only after cleanup.
                } else {
                    stopLivePreview()
                }
            }
            GlassesDashboardAction.RequestStartWifiAdbDebug -> {
                if (BuildConfig.DEBUG && isHeyCyanSelected()) startWifiAdbDebug()
            }
            GlassesDashboardAction.StopWifiAdbDebug -> {
                if (BuildConfig.DEBUG && isHeyCyanSelected()) wifiAdbDebugController.stop()
            }
            GlassesDashboardAction.MetaRegister -> binding.btnMetaRegister.performClick()
            GlassesDashboardAction.MetaOpenPairing ->
                startActivity(Intent(this, MetaPairingActivity::class.java))
            GlassesDashboardAction.MetaOpenMetaAi -> openMetaAiAppOrStore()
            GlassesDashboardAction.MetaUnregister -> binding.btnMetaUnregister.performClick()
            GlassesDashboardAction.MetaStartSession -> binding.btnMetaSessionStart.performClick()
            GlassesDashboardAction.MetaStopSession -> binding.btnMetaSessionStop.performClick()
            GlassesDashboardAction.MetaStartStream -> binding.btnMetaStreamStart.performClick()
            GlassesDashboardAction.MetaStopStream -> binding.btnMetaStreamStop.performClick()
            GlassesDashboardAction.MetaCapturePhoto -> binding.btnMetaCapturePhoto.performClick()
            GlassesDashboardAction.MetaViewPhoto -> binding.btnMetaViewPhoto.performClick()
            GlassesDashboardAction.MetaStartDisplay -> binding.btnMetaDisplayStart.performClick()
            GlassesDashboardAction.MetaStopDisplay -> binding.btnMetaDisplayStop.performClick()
            GlassesDashboardAction.MetaSendDiagnostics -> showMetaDiagnostics()
            GlassesDashboardAction.MeizuConnect -> {
                DeviceProfileStore.loadLastSelected(this)?.macAddress?.let {
                    getOrCreateMeizuMyvuManager().connect(it, this, userInitiated = true)
                } ?: Toast.makeText(this, "Select MYVU glasses from Scan first", Toast.LENGTH_LONG).show()
            }
            GlassesDashboardAction.MeizuDisconnect -> getOrCreateMeizuMyvuManager().disconnect()
            GlassesDashboardAction.MeizuSendTestNotification -> getOrCreateMeizuMyvuManager().sendTestNotification()
            GlassesDashboardAction.MeizuShowTestTeleprompter -> getOrCreateMeizuMyvuManager().showTeleprompter(
                "CyanBridge",
                "MYVU display connected\n\nNative voice plugins can now use the MYVU headset microphone and display bridge.",
            )
            GlassesDashboardAction.MeizuSyncClock -> getOrCreateMeizuMyvuManager().syncClock()
            GlassesDashboardAction.MeizuSetComfortBrightness -> getOrCreateMeizuMyvuManager().setBrightness(70)
            GlassesDashboardAction.RefreshRecordingSettings -> if (isEyevueSelected()) {
                getOrCreateEyevueManager().requestSupportFunction()
            } else {
                refreshHeyCyanRecordingSettings()
            }
            is GlassesDashboardAction.SetVideoRecordingDuration -> if (isEyevueSelected()) {
                getOrCreateEyevueManager().setRecordingDuration(action.seconds)
            } else if (isHeyCyanSelected()) {
                setHeyCyanRecordingDuration(isAudio = false, seconds = action.seconds)
            }
            is GlassesDashboardAction.SetAudioRecordingDuration -> if (isEyevueSelected()) {
                getOrCreateEyevueManager().setRecordingDuration(action.seconds)
            } else if (isHeyCyanSelected()) {
                setHeyCyanRecordingDuration(isAudio = true, seconds = action.seconds)
            }
            is GlassesDashboardAction.SetWearingDetection -> if (isEyevueSelected()) {
                getOrCreateEyevueManager().setWearingDetection(action.enabled)
            } else if (isTuneBudsSelected()) {
                Log.i("Dashboard", "TuneBuds wearing detection is not exposed until hardware capability is confirmed")
            } else {
                Log.i("Dashboard", "SetWearingDetection (no-op in temp branch)")
            }
            GlassesDashboardAction.DismissTransportPermissionDialog -> {
                pendingTransportPermissionAction = null
                updateDashboardState { state -> state.copy(showTransportPermissionDialog = false) }
            }
            GlassesDashboardAction.RequestTransportPermission -> requestPendingTransportPermission()
        }
    }

    private fun refreshNativePluginShortcutState() {
        syncLegacyNativePluginState()
        dashboardState = dashboardState.copy(
            nativePluginShortcut = buildNativePluginShortcutState(
                CommunityPluginPrefs.getGlassesTabShortcutPluginId(this),
            ),
        )
    }

    private fun syncLegacyNativePluginState() {
        LocalAgentPlugin.syncNativePluginState(this)
        CommunityPluginPrefs.setNativePluginEnabled(
            this,
            NativePluginIds.AUTO_DIARY,
            AutoDiaryService.isEnabled(this),
        )
        CommunityPluginPrefs.setNativePluginEnabled(
            this,
            NativePluginIds.AUTO_AUDIO,
            AutoAudioCapturePrefs.isEnabled(this),
        )
        CommunityPluginPrefs.setNativePluginEnabled(
            this,
            NativePluginIds.VISUAL_DIARY,
            VisualDiaryPreferences.isEnabled(this),
        )
    }

    private fun buildNativePluginShortcutState(pluginId: String?): NativePluginShortcutUiState? {
        val id = pluginId ?: return null
        val definition = when (id) {
            NativePluginIds.LOCAL_AGENT -> NativePluginShortcutUiState(
                id = id,
                title = "Local Agent",
                description = "Run private phone automation with approval controls for risky actions.",
                isEnabled = LocalAgentPlugin.isEnabled(this),
                buttons = listOf(
                    NativePluginShortcutButton(NativePluginShortcutAction.START, "Enable automation"),
                    NativePluginShortcutButton(NativePluginShortcutAction.STOP, "Disable automation"),
                ),
            )
            NativePluginIds.MEETING_SPARK_NOTES -> NativePluginShortcutUiState(
                id = id,
                title = "Meeting Spark Notes",
                description = "Capture a live meeting transcript and turn it into concise notes.",
                isEnabled = MeetingCapturePrefs.getState(this).isRecording,
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
                isEnabled = CommunityPluginPrefs.isNativePluginEnabled(this, id),
                buttons = listOf(
                    NativePluginShortcutButton(NativePluginShortcutAction.START, "Start captions"),
                    NativePluginShortcutButton(NativePluginShortcutAction.STOP, "Stop captions"),
                ),
            )
            NativePluginIds.HANDS_FREE_TRANSLATOR -> NativePluginShortcutUiState(
                id = id,
                title = "Hands-Free Translator",
                description = "Translate live speech while the translator service is enabled.",
                isEnabled = CommunityPluginPrefs.isNativePluginEnabled(this, id),
                buttons = listOf(
                    NativePluginShortcutButton(NativePluginShortcutAction.START, "Start translator"),
                    NativePluginShortcutButton(NativePluginShortcutAction.STOP, "Stop translator"),
                ),
            )
            NativePluginIds.ERRAND_BRAIN -> NativePluginShortcutUiState(
                id = id,
                title = "Errand Brain",
                description = "Listen for spoken tasks and reminders.",
                isEnabled = CommunityPluginPrefs.isNativePluginEnabled(this, id),
                buttons = listOf(
                    NativePluginShortcutButton(NativePluginShortcutAction.START, "Start listening"),
                    NativePluginShortcutButton(NativePluginShortcutAction.STOP, "Stop listening"),
                ),
            )
            NativePluginIds.WALKING_AID -> NativePluginShortcutUiState(
                id = id,
                title = "Walking Aid",
                description = "Start or stop scene descriptions and obstacle warnings.",
                isEnabled = CommunityPluginPrefs.isNativePluginEnabled(this, id),
                buttons = listOf(
                    NativePluginShortcutButton(NativePluginShortcutAction.START, "Start walking aid"),
                    NativePluginShortcutButton(NativePluginShortcutAction.STOP, "Stop walking aid"),
                ),
            )
            NativePluginIds.AUTO_DIARY -> NativePluginShortcutUiState(
                id = id,
                title = "AutoDiary",
                description = "Collect screen context and turn it into daily facts, bullets, and summaries.",
                isEnabled = CommunityPluginPrefs.isNativePluginEnabled(this, id),
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
                isEnabled = CommunityPluginPrefs.isNativePluginEnabled(this, id),
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
                isEnabled = CommunityPluginPrefs.isNativePluginEnabled(this, id),
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

    private fun runNativePluginShortcut(action: NativePluginShortcutAction) {
        val pluginId = CommunityPluginPrefs.getGlassesTabShortcutPluginId(this) ?: return
        if (pluginId == NativePluginIds.MEETING_SPARK_NOTES) {
            when (action) {
                NativePluginShortcutAction.START -> {
                    Log.i("NativePluginShortcut", "Meeting Spark Notes START -> MeetingCaptureService")
                    startMeetingCaptureFromUi()
                }
                NativePluginShortcutAction.STOP -> {
                    Log.i("NativePluginShortcut", "Meeting Spark Notes STOP -> MeetingCaptureService")
                    stopMeetingCaptureFromUi()
                }
                NativePluginShortcutAction.SUMMARIZE -> MeetingSparkNotesService.summarize(this)
                else -> Unit
            }
        } else when (action) {
            NativePluginShortcutAction.START -> startNativePlugin(pluginId)
            NativePluginShortcutAction.STOP -> stopNativePlugin(pluginId)
            NativePluginShortcutAction.CAPTURE -> if (pluginId == NativePluginIds.VISUAL_DIARY) {
                VisualDiaryService.captureNow(this)
            }
            NativePluginShortcutAction.SYNC -> if (pluginId == NativePluginIds.AUTO_AUDIO) {
                binding.btnDataDownload.performClick()
            }
            NativePluginShortcutAction.SUMMARIZE -> if (pluginId == NativePluginIds.AUTO_DIARY) {
                AutoDiaryService.summarize(this)
            }
        }
        refreshNativePluginShortcutState()
    }

    private fun startNativePlugin(pluginId: String) {
        if (isMeizuMyvuSelected() && pluginId in setOf(
                NativePluginIds.AUTO_AUDIO,
                NativePluginIds.VISUAL_DIARY,
                NativePluginIds.WALKING_AID,
            )
        ) {
            Toast.makeText(
                this,
                "This plugin requires a camera or HeyCyan onboard media. MYVU supports display and microphone plugins only.",
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        if (isMetaRaybanSelected() && pluginId == NativePluginIds.AUTO_AUDIO) {
            Toast.makeText(
                this,
                "Auto Audio records HeyCyan onboard files and is unavailable for Meta Ray-Ban.",
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        val start = {
            when (pluginId) {
                NativePluginIds.AUTO_DIARY -> AutoDiaryService.enable(this)
                NativePluginIds.VISUAL_DIARY -> VisualDiaryService.enable(this)
                else -> {
                    CommunityPluginPrefs.setNativePluginEnabled(this, pluginId, true)
                    when (pluginId) {
                        NativePluginIds.LOCAL_AGENT -> {
                            LocalAgentPlugin.setEnabled(this, true)
                            Toast.makeText(
                                this,
                                "Local Agent enabled. Complete Tasker and AutoInput setup in Plugins.",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                        NativePluginIds.MEETING_SPARK_NOTES -> {
                            MeetingSparkNotesPreferences.setEnabled(this, true)
                            MeetingSparkNotesService.start(this)
                        }
                        NativePluginIds.LIVE_CAPTION_RELAY -> {
                            LiveCaptionRelayPreferences.setEnabled(this, true)
                            LiveCaptionRelayService.start(this)
                        }
                        NativePluginIds.HANDS_FREE_TRANSLATOR -> {
                            HandsFreeTranslatorPreferences.setEnabled(this, true)
                            HandsFreeTranslatorService.start(this)
                        }
                        NativePluginIds.ERRAND_BRAIN -> {
                            ErrandBrainPreferences.setEnabled(this, true)
                            ErrandBrainService.start(this)
                        }
                        NativePluginIds.WALKING_AID -> {
                            WalkingAidPreferences.setEnabled(this, true)
                            WalkingAidService.start(this)
                        }
                        NativePluginIds.AUTO_AUDIO -> AutoAudioCaptureService.start(this)
                    }
                }
            }
            refreshNativePluginShortcutState()
        }

        if (isMetaRaybanSelected() &&
            pluginId in setOf(NativePluginIds.WALKING_AID, NativePluginIds.VISUAL_DIARY)
        ) {
            val manager = getOrCreateMetaRaybanManager()
            if (!manager.isInitialized.value) manager.initialize()
            lifecycleScope.launch {
                if (!manager.awaitCameraReady()) {
                    Toast.makeText(
                        this@MainActivity,
                        "Register and connect a Meta camera before starting this plugin.",
                        Toast.LENGTH_LONG,
                    ).show()
                    return@launch
                }
                ensureMetaCameraReady(start)
            }
            return
        }

        if (pluginId == NativePluginIds.WALKING_AID ||
            pluginId == NativePluginIds.LOCAL_AGENT ||
            pluginId == NativePluginIds.AUTO_DIARY ||
            pluginId == NativePluginIds.VISUAL_DIARY
        ) {
            if (pluginId == NativePluginIds.WALKING_AID &&
                WalkingAidPreferences.getVideoMode(this) == com.fersaiyan.cyanbridge.plugins.walkingaid.WalkingAidVideoMode.EYEVUE_VIDEO
            ) {
                ensureGlassesTransportPermissions("Walking Aid video", start)
            } else start()
        } else {
            PluginVoicePermissions.ensure(this, onGranted = start)
        }
    }

    private fun stopNativePlugin(pluginId: String) {
        when (pluginId) {
            NativePluginIds.AUTO_DIARY -> AutoDiaryService.disable(this)
            NativePluginIds.VISUAL_DIARY -> VisualDiaryService.disable(this)
            else -> {
                CommunityPluginPrefs.setNativePluginEnabled(this, pluginId, false)
                when (pluginId) {
                    NativePluginIds.LOCAL_AGENT -> LocalAgentPlugin.stop(this)
                    NativePluginIds.MEETING_SPARK_NOTES -> {
                        MeetingSparkNotesPreferences.setEnabled(this, false)
                        MeetingSparkNotesService.stop(this)
                    }
                    NativePluginIds.LIVE_CAPTION_RELAY -> {
                        LiveCaptionRelayPreferences.setEnabled(this, false)
                        LiveCaptionRelayService.stop(this)
                    }
                    NativePluginIds.HANDS_FREE_TRANSLATOR -> {
                        HandsFreeTranslatorPreferences.setEnabled(this, false)
                        HandsFreeTranslatorService.stop(this)
                    }
                    NativePluginIds.ERRAND_BRAIN -> {
                        ErrandBrainPreferences.setEnabled(this, false)
                        ErrandBrainService.stop(this)
                    }
                    NativePluginIds.WALKING_AID -> {
                        WalkingAidPreferences.setEnabled(this, false)
                        WalkingAidService.stop(this)
                    }
                    NativePluginIds.AUTO_AUDIO -> AutoAudioCaptureService.stop(this)
                }
            }
        }
    }

    private fun navigateToDestination(destination: AppDestination) {
        when (destination) {
            AppDestination.GLASSES -> Unit
            AppDestination.CHATS -> {
                val last = ChatStore.listNonEmptyThreads().firstOrNull()
                val now = System.currentTimeMillis()
                val lastUserAt = last?.let { thread ->
                    ChatStore.listMessages(thread.id)
                        .lastOrNull { it.role == com.fersaiyan.cyanbridge.shared.chat.ChatRole.USER }
                        ?.createdAt
                } ?: 0L
                val openChatId = last?.id?.takeIf { lastUserAt > 0L && now - lastUserAt < 30 * 60 * 1000L }
                startActivity(Intent(this, ChatThreadActivity::class.java).apply {
                    openChatId?.let { putExtra(ChatThreadActivity.EXTRA_CHAT_ID, it) }
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                })
            }
            AppDestination.MEDIA -> startActivity(Intent(this, RecordingsListActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            })
            AppDestination.PLUGINS -> startActivity(Intent(this, CommunityPluginsActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            })
            AppDestination.SETTINGS -> startActivity(Intent(this, SettingsActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            })
        }
        if (destination != AppDestination.GLASSES) {
            overridePendingTransition(0, 0)
        }
    }

    private fun initView() {
        setOnClickListener(
            binding.btnScan,
            binding.btnConnect,
            binding.btnDisconnect,
            binding.btnAddListener,
            binding.btnSetTime,
            binding.btnVersion,
            binding.btnCamera,
            binding.btnVideo,
            binding.btnRecord,
            binding.btnBt,
            binding.btnBattery,
            binding.btnVolume,
            binding.btnMediaCount,
            binding.btnDataDownload,
            binding.btnOtaInfo,
            binding.btnPullOtaTest,
            binding.btnModeGemini,
            binding.btnModeChatgpt,
            binding.btnModeTasker,
            binding.btnTestHijackVoice,
            binding.btnTestHijackImage,
            binding.btnToggleAdvanced,
            // binding.btnNotes,
            binding.btnMeetingStart,
            binding.btnMeetingStop,
            binding.btnMeetingBannerStop,
            binding.btnTransferStop,
        ) {
            val activeSession = GlassesSessionCoordinator.currentSession()
            val isAllowedStop = this == binding.btnTransferStop &&
                activeSession == GlassesSession.MEDIA_SYNC
            if (activeSession != null && !isAllowedStop) {
                Toast.makeText(
                    this@MainActivity,
                    "${activeSession.label.replaceFirstChar { it.uppercase() }} is using the glasses connection.",
                    Toast.LENGTH_SHORT,
                ).show()
                return@setOnClickListener
            }

            if (isMetaRaybanSelected() && this in setOf(
                    binding.btnConnect,
                    binding.btnDisconnect,
                    binding.btnAddListener,
                    binding.btnSetTime,
                    binding.btnVersion,
                    binding.btnVideo,
                    binding.btnRecord,
                    binding.btnBt,
                    binding.btnBattery,
                    binding.btnVolume,
                    binding.btnMediaCount,
                    binding.btnDataDownload,
                    binding.btnOtaInfo,
                    binding.btnPullOtaTest,
                )
            ) {
                rejectHeyCyanOnlyFeature("This HeyCyan control")
                return@setOnClickListener
            }

            val needsBluetoothPermission = this == binding.btnConnect ||
                this == binding.btnDisconnect ||
                this == binding.btnAddListener ||
                this == binding.btnSetTime ||
                this == binding.btnVersion ||
                this == binding.btnCamera ||
                this == binding.btnVideo ||
                this == binding.btnRecord ||
                this == binding.btnBt ||
                this == binding.btnBattery ||
                this == binding.btnVolume ||
                this == binding.btnMediaCount ||
                this == binding.btnDataDownload ||
                this == binding.btnTestHijackImage ||
                this == binding.btnOtaInfo ||
                this == binding.btnPullOtaTest
            if (needsBluetoothPermission && !hasBluetooth(this@MainActivity)) {
                ensureBluetoothPermission("this glasses feature") {
                    performClick()
                }
                return@setOnClickListener
            }

            // Do not queue an unawaited audio-stop command immediately before another command
            // that needs the vendor SDK's single glassesControl response slot.
            val actionSendsGlassesControl = this == binding.btnCamera ||
                this == binding.btnVideo ||
                this == binding.btnRecord ||
                this == binding.btnMediaCount ||
                this == binding.btnDataDownload ||
                this == binding.btnTestHijackVoice ||
                this == binding.btnTestHijackImage ||
                this == binding.btnPullOtaTest
            val shouldStopGlassesAudio = this != binding.btnScan &&
                this != binding.btnConnect &&
                this != binding.btnTransferStop &&
                !actionSendsGlassesControl
            if (shouldStopGlassesAudio) {
                controlAudioRecording(false)
                // If auto audio capture is enabled, give the user a short window to operate other controls.
                if (AutoAudioCapturePrefs.isEnabled(this@MainActivity) && this != binding.btnRecord) {
                    AutoAudioCapturePrefs.pauseForMs(this@MainActivity, 90_000)
                }
            }

            when (this) {
                binding.btnToggleAdvanced -> {
                    val container = binding.layoutAdvancedContainer
                    if (container.visibility == android.view.View.VISIBLE) {
                        container.visibility = android.view.View.GONE
                        binding.btnToggleAdvanced.text = "Advanced ▼"
                    } else {
                        container.visibility = android.view.View.VISIBLE
                        binding.btnToggleAdvanced.text = "Advanced ▲"
                    }
                }

                binding.btnTestHijackVoice -> {
                    startVoiceQuestionFromUi()
                }

                binding.btnTestHijackImage -> {
                    startImageQuestionFromUi()
                }

                binding.btnModeGemini -> {
                    selectPhoneAssistant()
                }

                binding.btnModeChatgpt -> {
                    selectPhoneAssistant()
                }

                binding.btnModeTasker -> {
                    selectCustomAiProvider()
                }

                // Notes & Summaries entry removed (moved to Transcriptions & recordings section)

                binding.btnMeetingStart -> {
                    startMeetingCaptureFromUi()
                }

                binding.btnMeetingStop, binding.btnMeetingBannerStop -> {
                    stopMeetingCaptureFromUi()
                }

                binding.btnScan -> {
                    requestBluetoothPermission(this@MainActivity, PermissionCallback())
                }

                binding.btnConnect -> {
                    // User explicitly wants to reconnect, so re-enable auto pairing.
                    AutoPairManager.setAutoReconnectSuppressed(false, reason = "user_reconnect_button")
                    Toast.makeText(this@MainActivity, "Reconnecting to glasses…", Toast.LENGTH_SHORT).show()
                    BleOperateManager.getInstance()
                        .connectDirectly(DeviceManager.getInstance().deviceAddress)
                }

                binding.btnDisconnect -> {
                    // Prevent the background reconnection loop from immediately reconnecting.
                    AutoPairManager.setAutoReconnectSuppressed(true, reason = "user_disconnect_button")
                    Toast.makeText(this@MainActivity, "Disconnecting from glasses…", Toast.LENGTH_SHORT).show()
                    BleOperateManager.getInstance().unBindDevice()
                }

                binding.btnAddListener -> {
                    Toast.makeText(this@MainActivity, "Registering device event listener…", Toast.LENGTH_SHORT).show()
                    LargeDataHandler.getInstance().addOutDeviceListener(100, deviceNotifyListener)
                }

                binding.btnSetTime -> {
                    Toast.makeText(this@MainActivity, "Syncing glasses time…", Toast.LENGTH_SHORT).show()
                    Log.i("setTime", "setTime" + BleOperateManager.getInstance().isConnected)
                    LargeDataHandler.getInstance().syncTime { _, _ -> }
                }

                binding.btnVersion -> {
                    Toast.makeText(this@MainActivity, "Reading device version…", Toast.LENGTH_SHORT).show()
                    LargeDataHandler.getInstance().syncDeviceInfo { _, response ->
                        if (response != null) {
                            val message =
                                "WiFi FW: ${response.wifiFirmwareVersion}, BT FW: ${response.firmwareVersion}"
                            Log.i("DeviceInfo", message)
                            runOnUiThread {
                                Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                            }
                        } else {
                            runOnUiThread {
                                Toast.makeText(
                                    this@MainActivity,
                                    "Failed to get device version",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                }

                binding.btnCamera -> {
                    if (isMetaRaybanSelected()) {
                        captureMetaPhotoForGallery()
                        return@setOnClickListener
                    }
                    val permit = acquireBackgroundGlassesCommand("camera command")
                        ?: return@setOnClickListener
                    try {
                        LargeDataHandler.getInstance().glassesControl(
                            byteArrayOf(0x02, 0x01, 0x01)
                        ) { _, it ->
                            try {
                                if (it.dataType == 1 && it.errorCode == 0) {
                                    when (it.workTypeIng) {
                                        2 -> {
                                            //Glasses are recording video
                                        }
                                        4 -> {
                                            //Glasses are in transfer mode
                                        }
                                        5 -> {
                                            //Glasses are in OTA mode
                                        }
                                        1, 6 ->{
                                            //Glasses are in camera mode
                                        }
                                        7 -> {
                                            //Glasses are in AI conversation
                                        }
                                        8 ->{
                                            //Glasses are in recording mode
                                        }
                                    }
                                }
                            } finally {
                                GlassesSessionCoordinator.releaseBackgroundCommand(permit)
                            }
                        }
                        warnIfBackgroundGlassesCommandTimesOut(permit)
                    } catch (e: Exception) {
                        GlassesSessionCoordinator.releaseBackgroundCommand(permit)
                        Log.e("Camera", "Failed to send camera command", e)
                    }
                }

                binding.btnVideo -> {
                    // Toggle video recording. While video is active, pause the auto audio loop.
                    val isRecording = GlassesMediaPrefs.isVideoRecording(this@MainActivity)
                    if (isRecording) {
                        Toast.makeText(this@MainActivity, "Stopping video recording…", Toast.LENGTH_SHORT).show()
                        controlVideoRecording(false)
                    } else {
                        Toast.makeText(this@MainActivity, "Starting video recording…", Toast.LENGTH_SHORT).show()
                        controlVideoRecording(true)
                    }
                }

                binding.btnRecord -> {
                    // Default UI behavior: start audio recording
                    controlAudioRecording(true)
                }

                binding.btnBt -> {
                    Toast.makeText(this@MainActivity, "Starting classic Bluetooth scan…", Toast.LENGTH_SHORT).show()
                    //BT scan
                    BleOperateManager.getInstance().classicBluetoothStartScan()

                }
                binding.btnBattery -> {
                    requestBatteryStatus(showToast = true)
                }
                binding.btnVolume ->{
                    Toast.makeText(this@MainActivity, "Requesting volume info…", Toast.LENGTH_SHORT).show()
                    //Read volume control and show values
                    LargeDataHandler.getInstance().getVolumeControl { _, response ->
                        if (response != null) {
                            val msg = """
                                Music: ${response.currVolumeMusic}/${response.maxVolumeMusic}
                                Call: ${response.currVolumeCall}/${response.maxVolumeCall}
                                System: ${response.currVolumeSystem}/${response.maxVolumeSystem}
                                Mode: ${response.currVolumeType}
                            """.trimIndent()
                            Log.i("VolumeControl", msg.replace('\n', ' '))
                            runOnUiThread {
                                Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
                            }
                        } else {
                            runOnUiThread {
                                Toast.makeText(
                                    this@MainActivity,
                                    "Failed to read volume info",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                }
                binding.btnMediaCount ->{
                    Toast.makeText(this@MainActivity, "Requesting media count…", Toast.LENGTH_SHORT).show()
                    val permit = acquireBackgroundGlassesCommand("media-count command")
                        ?: return@setOnClickListener
                    try {
                        LargeDataHandler.getInstance().glassesControl(byteArrayOf(0x02, 0x04)) { _, it ->
                            try {
                                if (it.dataType == 4) {
                                    val mediaCount = it.imageCount + it.videoCount + it.recordCount
                                    val msg = if (mediaCount > 0) {
                                        "Media not uploaded - Photos: ${it.imageCount}, Videos: ${it.videoCount}, Records: ${it.recordCount}"
                                    } else {
                                        "No pending media on glasses"
                                    }
                                    Log.i("MediaCount", msg)
                                    runOnUiThread {
                                        Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
                                    }
                                }
                            } finally {
                                GlassesSessionCoordinator.releaseBackgroundCommand(permit)
                            }
                        }
                        warnIfBackgroundGlassesCommandTimesOut(permit)
                    } catch (e: Exception) {
                        GlassesSessionCoordinator.releaseBackgroundCommand(permit)
                        Log.e("MediaCount", "Failed to request media count", e)
                    }
                }
                binding.btnDataDownload -> {
                    ensureGlassesTransportPermissions("Wi-Fi media sync") {
                        if (isMoyoungW620Selected()) {
                            startMoyoungW620MediaSync()
                        } else {
                            showOfficialHeyCyanWarningOrContinue()
                        }
                    }
                }
                binding.btnTransferStop -> {
                    cancelDataDownloadAttempt(
                        reason = "Sync stopped by user",
                        showToast = true,
                    )
                }
                binding.btnOtaInfo -> {
                    Toast.makeText(this@MainActivity, "Dumping OTA server info…", Toast.LENGTH_SHORT).show()
                    dumpOtaServerInfo()
                }
                binding.btnPullOtaTest -> {
                    Toast.makeText(this@MainActivity, "Triggering pull‑mode OTA test…", Toast.LENGTH_SHORT).show()
                    testPullModeOta()
                }
            }
        }

        refreshAiModeButtons()

        binding.cbHijackEnabled.setOnCheckedChangeListener { _, isChecked ->

            isAiHijackEnabled = isChecked
            if (isChecked) configureHeyCyanWakeWordIfNeeded()
            Toast.makeText(this, "Hijack ${if (isChecked) "Enabled" else "Disabled"}", Toast.LENGTH_SHORT).show()
        }

        binding.cbImageAsAssistant.isChecked = isImageAssistantMode
        binding.cbImageAsAssistant.text = if (isImageAssistantMode) "Direct Assistant" else "App Sharing"
        
        binding.cbImageAsAssistant.setOnCheckedChangeListener { _, isChecked ->
            isImageAssistantMode = isChecked
            val modeName = if (isChecked) "Direct Assistant" else "App Sharing"
            binding.cbImageAsAssistant.text = modeName
            Toast.makeText(this, "Image Hijack: $modeName", Toast.LENGTH_SHORT).show()
        }
    }

    private fun dumpOtaServerInfo() = otaCoordinator.dumpOtaServerInfo()

    private fun testPullModeOta() = otaCoordinator.testPullModeOta()

    private fun observeOtaState() = otaCoordinator.observeOtaState()

    private fun requestOtaFirmware(source: OtaFirmwareSource) = otaCoordinator.requestOtaFirmware(source)

    private fun resetOtaDashboardToIdle() = otaCoordinator.resetOtaDashboardToIdle()

    private fun submitFirmwarePatchRequest(
        request: FirmwarePatchRequestUiState,
        contactEmail: String,
    ) = otaCoordinator.submitFirmwarePatchRequest(request, contactEmail)

    private fun startLivePreview() = livePreviewCoordinator.startLivePreview()

    private fun startEyevueLivePreview() = livePreviewCoordinator.startEyevueLivePreview()

    private fun startTuneBudsLivePreview() = livePreviewCoordinator.startTuneBudsLivePreview()

    private fun observeLivePreviewState() = livePreviewCoordinator.observeLivePreviewState()

    private fun stopLivePreview() = livePreviewCoordinator.stopLivePreview()

    private fun startWifiAdbDebug() = wifiAdbDebugCoordinator.startWifiAdbDebug()

    private fun observeWifiAdbDebugState() = wifiAdbDebugCoordinator.observeWifiAdbDebugState()

    private fun controlVideoRecording(start: Boolean) = recordingControls.controlVideoRecording(start)

    private fun controlAudioRecording(start: Boolean) = recordingControls.controlAudioRecording(start)

    private fun stopGlassesAiAudio(source: String) {
        if (isMetaRaybanSelected()) {
            // Meta audio is managed by DAT/Android audio routing; never send Oudmon
            // command bytes to a Meta wearable.
            Log.d("AIHijack", "Skipping HeyCyan AI-audio stop for Meta ($source)")
            return
        }
        if (isGlassesCommandBlocked(source)) return
        val permit = acquireBackgroundGlassesCommand(source) ?: return
        try {
            LargeDataHandler.getInstance().glassesControl(byteArrayOf(0x02, 0x01, 0x0b)) { _, _ ->
                GlassesSessionCoordinator.releaseBackgroundCommand(permit)
            }
            warnIfBackgroundGlassesCommandTimesOut(permit)
        } catch (e: Exception) {
            GlassesSessionCoordinator.releaseBackgroundCommand(permit)
            Log.e("AIHijack", "Failed to stop glasses AI audio for $source", e)
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onBluetoothEvent(event: BluetoothEvent) {
        if (isEyevueSelected()) {
            // Oudmon notifications cannot invalidate a native AA15 photo lease.
            updateConnectionStatus(eyevueManager?.isConnected() == true)
            return
        }
        if (isTuneBudsSelected()) {
            // Vendor-AAR BLE events do not own AB Mate's Classic/SPP session.
            // TuneBuds transport loss is monitored by its live connection.
            updateConnectionStatus(tuneBudsManager?.isConnected() == true)
            return
        }
        updateConnectionStatus(event.connect)
        if (event.connect) {
            otaManager.onBluetoothConnected()
            requestBatteryStatus(showToast = false)
        } else {
            val expectedOtaReconnect = otaManager.isAwaitingFreshBleReadiness
            val otaWasActive = otaManager.isActive
            val otaPreparationWasActive = otaPreparationJob?.isActive == true
            otaManager.onBluetoothDisconnected()
            if (!expectedOtaReconnect) {
                otaPreparationJob?.cancel()
                otaPreparationJob = null
                if (!otaWasActive && otaPreparationWasActive) {
                    resetOtaDashboardToIdle()
                }
                abandonDownloadP2pForBluetoothDisconnect()
                livePreviewManager.onBluetoothDisconnected()
                if (BuildConfig.DEBUG) wifiAdbDebugController.onBluetoothDisconnected()
                if (otaWasActive) {
                    Log.i("Ota", "Keeping the OTA lease until transport cleanup finishes after Bluetooth loss")
                } else {
                    releaseExclusiveGlassesSession(otaSessionLease)
                    GlassesSessionCoordinator.clearForDisconnectedDevice(DeviceClass.HEY_CYAN)
                }
                mediaSessionLease = null
                livePreviewSessionLease = null
                wifiAdbDebugSessionLease = null
            } else {
                Log.i("Ota", "Preserving the OTA lease during its intentional BLE readiness reconnect")
            }
            updateBatteryText(null)
        }
    }

    private fun startBatteryPolling() {
        if (batteryPollJob?.isActive == true) {
            return
        }
        batteryPollJob = CoroutineScope(Dispatchers.Main).launch {
            while (isActive) {
                if (BleOperateManager.getInstance().isConnected) {
                    requestBatteryStatus(showToast = false)
                } else {
                    updateBatteryText(null)
                }
                delay(batteryPollIntervalMs)
            }
        }
    }

    private fun stopBatteryPolling() {
        batteryPollJob?.cancel()
        batteryPollJob = null
    }

    private fun resolveEffectiveAiAssistantMode(): String {
        return when (currentAssistantRoute()) {
            GlassesAssistantRoute.PHONE_ASSISTANT -> AI_MODE_PHONE_ASSISTANT
            GlassesAssistantRoute.TASKER_EXTERNAL_UI -> AI_MODE_TASKER
            GlassesAssistantRoute.LOCAL,
            GlassesAssistantRoute.PRO -> AI_MODE_CUSTOM_AI_PROVIDER
        }
    }

    private fun isCustomAiProviderMode(): Boolean = aiAssistantMode == AI_MODE_CUSTOM_AI_PROVIDER

    private fun currentAssistantRoute(): GlassesAssistantRoute =
        GlassesAssistantRoutingPolicy.resolve(
            mode = if (isCustomAiProviderMode()) {
                GlassesAssistantMode.CUSTOM_AI_PROVIDER
            } else {
                GlassesAssistantMode.PHONE_ASSISTANT
            },
            customProvider = AutomationPrefs.getProviderType(this),
        )

    private fun usesExternalAssistantUi(): Boolean = when (currentAssistantRoute()) {
        GlassesAssistantRoute.PHONE_ASSISTANT -> true
        GlassesAssistantRoute.TASKER_EXTERNAL_UI,
        GlassesAssistantRoute.LOCAL,
        GlassesAssistantRoute.PRO -> false
    }

    private fun selectedImageAutomationTarget(): ImageAutomationTarget {
        return ImageAutomationTarget.forDefaultAssistant(DefaultAssistantResolver.packageName(this))
    }

    private fun externalImageAutomationUnsupportedReason(): String? {
        if (!usesExternalAssistantUi()) return null
        return ExternalAssistantAutomationPolicy.imageBlockingReason(
            ExternalAssistantAutomationInspector.inspect(this),
        )
    }

    private fun maybeShowGeminiChatGptImageRequirementsWarning(): Boolean {
        if (!usesExternalAssistantUi()) return false

        val capability = ExternalAssistantAutomationInspector.inspect(this)
        val msg = ExternalAssistantAutomationPolicy.imageBlockingReason(capability) ?: return false
        if (capability.phoneLocked) {
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            speak(msg)
            return true
        }

        AlertDialog.Builder(this)
            .setTitle("AI image setup required")
            .setMessage(msg)
            .setNegativeButton("Not now", null)
            .setPositiveButton("Open setup") { _, _ ->
                startActivity(Intent(this, ExternalAssistantAutomationSetupActivity::class.java))
            }
            .show()

        return true
    }

    private fun refreshAiQueryButtonsState() {
        binding.btnTestHijackImage.isEnabled = true
        binding.btnTestHijackImage.alpha = 1f
        binding.btnTestHijackImage.text = "Test Image AI description"
        updateDashboardState { state ->
            state.copy(
                imageQueryEnabled = true,
                imageQueryLabel = "Test image AI description",
            )
        }
    }

    private fun refreshImageThumbnailQuality() {
        val quality = ImageQuestionPreferences.thumbnailQuality(this)
        pendingImageThumbnailQuality = quality
        updateDashboardState { state ->
            state.copy(
                imageThumbnailQualitySdkValue = quality.sdkValue,
                imageThumbnailQualityLabel = quality.label,
            )
        }
    }

    private fun refreshGeminiLiveImageDelay() {
        val seconds = GeminiLiveVisionPreferences.imageDelaySeconds(this)
        updateDashboardState { state ->
            state.copy(
                showGeminiLiveImageDelay = true,
                geminiLiveImageDelaySeconds = seconds,
            )
        }
    }

    private fun refreshAiModeButtons() {
        val activeColor = ContextCompat.getColor(this, R.color.cyan_accent)
        val inactiveColor = ContextCompat.getColor(this, R.color.text_secondary)

        val phoneAssistantSelected = aiAssistantMode == AI_MODE_PHONE_ASSISTANT
        binding.btnModeGemini.setTextColor(if (phoneAssistantSelected) activeColor else inactiveColor)
        binding.btnModeChatgpt.setTextColor(if (phoneAssistantSelected) activeColor else inactiveColor)
        binding.btnModeTasker.setTextColor(if (!phoneAssistantSelected) activeColor else inactiveColor)
        updateDashboardState { state ->
            state.copy(
                assistantMode = when (aiAssistantMode) {
                    AI_MODE_CUSTOM_AI_PROVIDER -> GlassesAssistantMode.CUSTOM_AI_PROVIDER
                    else -> GlassesAssistantMode.PHONE_ASSISTANT
                },
            )
        }
        refreshAiQueryButtonsState()
    }

    private fun selectPhoneAssistant() {
        aiAssistantMode = AI_MODE_PHONE_ASSISTANT
        AutomationPrefs.setGlassesAssistantMode(this, GlassesAssistantMode.PHONE_ASSISTANT)
        refreshAiModeButtons()
        val assistantPackage = DefaultAssistantResolver.packageName(this)
        val result = when (selectedImageAutomationTarget()) {
            ImageAutomationTarget.GEMINI -> "Gemini external image automation"
            ImageAutomationTarget.CHATGPT -> "ChatGPT external image automation"
            ImageAutomationTarget.NONE -> "voice only; external image automation unavailable"
        }
        Toast.makeText(
            this,
            "Phone Assistant: ${assistantPackage ?: "not detected"}. $result",
            Toast.LENGTH_LONG,
        ).show()
    }

    private fun selectCustomAiProvider() {
        aiAssistantMode = AI_MODE_CUSTOM_AI_PROVIDER
        AutomationPrefs.setGlassesAssistantMode(this, GlassesAssistantMode.CUSTOM_AI_PROVIDER)
        refreshAiModeButtons()
        val provider = AutomationPrefs.getProviderType(this).label
        Toast.makeText(this, "Local / Pro / Tasker: $provider", Toast.LENGTH_SHORT).show()
    }

    private fun sendAiBroadcast(
        type: String,
        path: String? = null,
        prompt: String? = null,
        imageUri: Uri? = null,
        imageSource: ImageQuestionSource? = null,
        handoffMode: String? = null,
        callbackSession: String? = null,
        callbackToken: String? = null,
        assistantMode: String = resolveEffectiveAiAssistantMode(),
    ) {
        val payload = ImageQuestionBroadcast.Payload(
            type = type,
            imagePath = path,
            imageUri = imageUri?.toString(),
            question = prompt,
            assistant = assistantMode,
            source = imageSource,
            handoffMode = handoffMode,
            callbackAction = ExternalImageAutomationIntents.statusAction(packageName),
            callbackSession = callbackSession,
            callbackToken = callbackToken,
        )
        val intent = Intent(aiEventAction(packageName)).apply {
            payload.extras().forEach { (key, value) -> putExtra(key, value) }
            // Kept for profiles created before the explicit question extra existed.
            prompt?.let { putExtra("prompt", it) }
            setPackage(ExternalImageAutomationIntents.TASKER_PACKAGE)
            addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
        }
        sendBroadcast(intent)
        Log.i(
            "AIHijack",
            "Sent Broadcast to Tasker: type=$type source=${imageSource?.wireName.orEmpty()} handoff=${handoffMode.orEmpty()}",
        )
    }

    private fun todayDateString(tsMs: Long = System.currentTimeMillis()): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        return fmt.format(java.util.Date(tsMs))
    }

    private fun tokenizeMemoryQuery(text: String): List<String> {
        val stopwords = setOf(
            "the", "and", "for", "with", "that", "this", "from", "into", "what", "when",
            "how", "who", "why", "are", "was", "were", "can", "could", "should", "would",
            "will", "just", "like", "your", "you", "about", "have", "has", "had", "then",
            "que", "para", "com", "uma", "nao", "não", "isso", "essa", "esse", "foi", "tem",
            "como", "porque", "por", "das", "dos", "uns", "umas"
        )

        return text
            .lowercase(Locale.US)
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .map { it.trim() }
            .filter { it.length >= 3 && it !in stopwords }
            .distinct()
    }

    private fun selectRelevantMemoryItems(items: List<String>, queryText: String, maxItems: Int): List<String> {
        val clean = items
            .map { it.trim().removePrefix("- ").removePrefix("* ").trim() }
            .filter { it.isNotBlank() }
            .distinct()

        if (clean.isEmpty()) return emptyList()
        val tokens = tokenizeMemoryQuery(queryText)
        if (tokens.isEmpty()) return clean.take(minOf(maxItems, 2))

        val scored = clean.map { item ->
            val hay = item.lowercase(Locale.US)
            var score = 0
            for (token in tokens) {
                if (hay.contains(token)) score += 1
            }
            item to score
        }

        val hits = scored
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first.length })
            .map { it.first }
            .take(maxItems)

        return if (hits.isNotEmpty()) hits else clean.take(minOf(maxItems, 2))
    }

    private fun buildCompactMemoryAwareSystemPrompt(
        queryText: String,
        date: String,
        ragProfile: RagProfile,
    ): String {
        if (ragProfile == RagProfile.NONE) return ""
        val light = ragProfile == RagProfile.LIGHT
        val extraSections = mutableListOf<LocalAgentContextBuilder.Section>()

        val retrieval = LocalAgentMemorySearch.buildRelevantMemoryBlock(
            context = this,
            queryText = queryText,
            date = date,
            lookbackDaysFacts = if (light) 3 else 5,
            topFacts = if (light) 2 else 4,
            topSummaryLines = if (light) 1 else 3,
            maxChars = if (light) 500 else 900,
            ragProfile = ragProfile,
        )
        if (retrieval.isNotBlank()) {
            extraSections += LocalAgentContextBuilder.Section(
                title = "Relevant memory (search hits)",
                content = retrieval,
            )
        }

        val draftFacts = runCatching { DailyFactsStorage.load(this, date).draft }.getOrDefault(emptyList())
        val draftRef = LocalAgentMemoryStore.memoryRefForFile(
            this,
            LocalAgentMemoryStore.dailyFactsFileForDate(this, date),
        )
        val relevantDraft = if (MemoryPolicyService.isMemoryRefSearchEligible(this, draftRef)) {
            selectRelevantMemoryItems(draftFacts, queryText, maxItems = if (light) 1 else 4)
        } else {
            emptyList()
        }
        if (relevantDraft.isNotEmpty()) {
            extraSections += LocalAgentContextBuilder.Section(
                title = "Today's draft daily facts (unconfirmed)",
                content = relevantDraft.joinToString("\n") { "- $it" },
            )
        }

        val candidateFacts = runCatching { CandidateUserFactsStorage.load(this, date) }.getOrDefault(emptyList())
        val candidateRef = LocalAgentMemoryStore.memoryRefForFile(
            this,
            LocalAgentMemoryStore.userFactsCandidatesFileForDate(this, date),
        )
        val relevantCandidates = if (MemoryPolicyService.isMemoryRefSearchEligible(this, candidateRef)) {
            selectRelevantMemoryItems(candidateFacts, queryText, maxItems = if (light) 1 else 3)
        } else {
            emptyList()
        }
        if (relevantCandidates.isNotEmpty()) {
            extraSections += LocalAgentContextBuilder.Section(
                title = "Candidate user facts (pending review)",
                content = relevantCandidates.joinToString("\n") { "- $it" },
            )
        }

        val builder = LocalAgentContextBuilder(
            maxAgentPersonaChars = if (light) 300 else QUERY_MAX_AGENT_PERSONA_CHARS,
            maxUserFactsChars = if (light) 400 else QUERY_MAX_USER_FACTS_CHARS,
            maxConfirmedDailyFactsChars = if (light) 350 else QUERY_MAX_CONFIRMED_FACTS_CHARS,
            maxDailySummaryChars = if (light) 300 else QUERY_MAX_DAILY_SUMMARY_CHARS,
            maxTotalChars = if (light) 1_800 else QUERY_MAX_TOTAL_CONTEXT_CHARS,
        )

        return builder.buildSystemMessage(
            context = this,
            date = date,
            extraSections = extraSections,
        )
    }

    private suspend fun runChosenProviderQuery(
        userPrompt: String,
        providerType: AgentProviderType,
        ragProfile: RagProfile,
        imagePaths: List<String> = emptyList(),
        audioPath: String? = null,
        onToken: ((String) -> Unit)? = null,
    ): String {
        val date = todayDateString()
        val languageTag = recognitionLanguageTag()
        val systemPrompt = buildString {
            val memoryContext = buildCompactMemoryAwareSystemPrompt(
                queryText = userPrompt,
                date = date,
                ragProfile = ragProfile,
            )
            if (memoryContext.isNotBlank()) {
                append(memoryContext)
                append("\n\n")
            }
            append(ImageQuestionDefaults.responseLanguageInstruction(languageTag))
        }

        val messages = listOf(
            mapOf("role" to "System", "content" to systemPrompt),
            mapOf("role" to "User", "content" to userPrompt),
        )

        return when (providerType) {
            AgentProviderType.PRO_SUBSCRIPTION -> {
                val chosenQuestionsModel = ProSubscriptionAiPrefs.getQuestionsModel(this).trim()
                CliRelayClient.chat(
                    context = this,
                    chatId = "glasses_${System.currentTimeMillis()}",
                    prompt = userPrompt,
                    messages = messages,
                    modelOverride = chosenQuestionsModel,
                ).getOrElse {
                    RelayErrorLocalizer.localizedMessage(this, it)
                }
            }

            AgentProviderType.LOCAL_AGENT ->
                runCatching {
                    val modelIssue = validateSelectedLocalMediaModel(mediaRequested = imagePaths.isNotEmpty() || !audioPath.isNullOrBlank())
                    if (modelIssue != null) {
                        return@runCatching modelIssue
                    }
                    LocalModelsProvider().streamChat(
                        context = this,
                        messages = messages,
                        imagePaths = imagePaths,
                        audioPath = audioPath,
                        onToken = onToken,
                    )
                }.getOrElse {
                    "Local Models error: ${it.message ?: "unknown error"}"
                }

            AgentProviderType.TASKER -> {
                CliRelayClient.chat(
                    context = this,
                    chatId = "glasses_${System.currentTimeMillis()}",
                    prompt = userPrompt,
                    messages = messages,
                ).getOrElse { RelayErrorLocalizer.localizedMessage(this, it) }
            }
        }.trim()
    }

    private fun validateSelectedLocalMediaModel(mediaRequested: Boolean): String? {
        if (RemoteOpenAiPrefs.isActive(this)) return null

        val selected = LocalModelStorageRepository.resolveSelectedModel(this)
            ?: return "No local model selected. Install or select a local model in Settings."
        val settings = LocalModelSettingsRepository.getForModel(this, selected.id)
        // LocalModelsProvider can select an installed LiteRT/compatible GGUF media model
        // for this request, without changing the user's text-model selection or calling a relay.
        return null
    }

    private fun resolveImageQuestionPrompt(userQuestion: String?): ResolvedImageQuestionPrompt {
        return ImageQuestionPromptResolver.resolve(
            settings = ImageQuestionPreferences.get(this),
            userQuestion = userQuestion,
        )
    }

    private fun shouldUseGeminiLiveQuestions(providerType: AgentProviderType): Boolean {
        if (providerType != AgentProviderType.PRO_SUBSCRIPTION) return false
        val isProActive = ProSubscriptionPrefs.isActiveLocally(this)
        val questionsModel = ProSubscriptionAiPrefs.getQuestionsModel(this)
        val result = ProSubscriptionAiPrefs.shouldUseGeminiLiveForQuestions(
            isProActive = isProActive,
            questionsModel = questionsModel,
        )
        Log.i("GeminiLive", "shouldUseGeminiLiveQuestions isProActive=$isProActive questionsModel=$questionsModel result=$result")
        return result
    }

    /** Returns true when the *image* question button would route to Gemini Live (voice+vision). */
    private fun isGeminiLiveRoutedForImageQuestion(): Boolean {
        val assistantRoute = currentAssistantRoute()
        if (assistantRoute == GlassesAssistantRoute.PHONE_ASSISTANT) return false
        val internalProvider = when (assistantRoute) {
            GlassesAssistantRoute.LOCAL,
            GlassesAssistantRoute.PRO,
            GlassesAssistantRoute.TASKER_EXTERNAL_UI -> MediaInferenceRoutingPolicy.resolve(this)
            GlassesAssistantRoute.PHONE_ASSISTANT -> null
        } ?: return false
        return shouldUseGeminiLiveQuestions(internalProvider)
    }

    private fun launchGeminiLiveQuestion(
        prompt: String,
        imagePath: String? = null,
        onLaunched: (() -> Unit)? = null,
    ) {
        val languageTag = recognitionLanguageTag()
        // Base image question without strict "Answer only in X" - Live systemInstruction handles 97-language switching permissively.
        // Single-shot paths keep strict Resolver; Live uses baseQuestion as imagePrompt, while initialPrompt is the user's actual turn.
        val baseImagePrompt = ImageQuestionPromptResolver.baseQuestion(
            settings = ImageQuestionPreferences.get(this),
            userQuestion = null,
        )
        val initialPrompt = prompt.trim().ifBlank { baseImagePrompt }
        // Foreground service keeps mic+vision alive with screen off/locked.
        // The dedicated GeminiLiveActivity is now optional debug UI; the image
        // button path prefers the notification-driven service with a Stop action.
        val launch: () -> Unit = {
            try {
                val isPro = com.fersaiyan.cyanbridge.agent.ProSubscriptionPrefs.isActiveLocally(this) &&
                    com.fersaiyan.cyanbridge.agent.ProSubscriptionPrefs.getPlan(this).lowercase() in setOf("cheap", "standard", "max")
                com.fersaiyan.cyanbridge.ai.live.GeminiLivePermissionGateActivity.launch(
                    activity = this,
                    language = languageTag,
                    imagePrompt = baseImagePrompt,
                    initialImagePath = imagePath,
                    initialPrompt = initialPrompt,
                    useRelay = !isPro,
                )
                Log.i(
                    "GeminiLive",
                    "Requested Live launch initialImage=${!imagePath.isNullOrBlank()} useRelay=${!isPro}",
                )
                // Keep a visible in-app stop action when MainActivity is open; the
                // foreground service notification remains the background Stop control.
                com.google.android.material.snackbar.Snackbar
                    .make(binding.root, "Gemini Live: microphone may be active", com.google.android.material.snackbar.Snackbar.LENGTH_INDEFINITE)
                    .setAction("Stop Live") {
                        com.fersaiyan.cyanbridge.ai.live.GeminiLiveForegroundService.stop(this)
                    }
                    .show()
                // Keep activity launch as fallback for debugging if service is unavailable
                // (no-op if service already handles it). Not started by default.
            } catch (error: Exception) {
                Log.e("GeminiLive", "Could not start Live foreground service", error)
                Toast.makeText(this, "Could not start Gemini Live.", Toast.LENGTH_LONG).show()
                // Fallback to legacy activity path
                try {
                    startActivity(
                        Intent(this, GeminiLiveActivity::class.java)
                            .putExtra(GeminiLiveActivity.EXTRA_AUTO_START, true)
                            .putExtra(GeminiLiveActivity.EXTRA_INITIAL_PROMPT, prompt)
                            .apply {
                                imagePath?.takeIf { it.isNotBlank() }?.let {
                                    putExtra(GeminiLiveActivity.EXTRA_INITIAL_IMAGE_PATH, it)
                                }
                            },
                    )
                } catch (_: Exception) {}
            } finally {
                onLaunched?.invoke()
            }
        }
        runOnUiThread {
            speak(
                text = ImageQuestionDefaults.initializingLiveCueForLanguage(languageTag),
                languageTag = languageTag,
                utteranceId = "initializing_live_${System.nanoTime()}",
                streamType = null,
                onDone = launch,
            )
        }
    }

    private fun triggerChosenProviderImageQuery(
        imagePath: String,
        providerType: AgentProviderType,
        resolvedPrompt: ResolvedImageQuestionPrompt,
        audioPath: String? = null,
        onReplySpoken: (() -> Unit)? = null,
    ) {
        Log.i("AIHijack", "Running image query with RAG disabled for chosen provider $providerType: $imagePath")

        if (shouldUseGeminiLiveQuestions(providerType)) {
            // Live systemInstruction is permissive (97 languages, switch on request) - use base question
            // without strict "Answer only in X". Single-shot PRO_RELAY keeps strict via forRoute.
            val liveBasePrompt = ImageQuestionPromptResolver.baseQuestion(
                settings = ImageQuestionPreferences.get(this),
                userQuestion = null,
            ).ifBlank { resolvedPrompt.text.substringBefore("\n\n") }
            launchGeminiLiveQuestion(
                prompt = liveBasePrompt,
                imagePath = imagePath,
                onLaunched = {
                    imageQueryInProgress.set(false)
                    finishAiQuestionForegroundWork()
                },
            )
            return
        }

        val onSpeechCompleted: () -> Unit = {
            finishAiQuestionForegroundWork()
            onReplySpoken?.invoke()
            Unit
        }
        val localSpeechSessionId = if (providerType == AgentProviderType.LOCAL_AGENT) {
            localSpeechSessionManager.startNewSession(
                languageTag = ImageQuestionPreferences.get(this).appLanguageTag,
                onSpeechCompleted = onSpeechCompleted,
            )
        } else {
            null
        }

        val queryJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                val finalReply = when (providerType) {
                    AgentProviderType.PRO_SUBSCRIPTION -> {
                        // Continuous Gemini Live selections return before this one-shot query is
                        // created. Match main for an active Pro user's selected non-Live model.
                        val questionsModel = ProSubscriptionAiPrefs.getQuestionsModel(this@MainActivity)
                        val visionResult = CliRelayClient.imageQuery(
                            context = this@MainActivity,
                            imagePath = imagePath,
                            prompt = resolvedPrompt.forRoute(ImageQuestionRoute.PRO_RELAY),
                            modelOverride = questionsModel,
                        )
                        if (visionResult.isFailure) {
                            val throwable = visionResult.exceptionOrNull() ?: Exception("unknown error")
                            val localized = RelayErrorLocalizer.localizedMessage(this@MainActivity, throwable)
                            val isQuota = RelayErrorLocalizer.isQuotaError(throwable)
                            Log.e("AIHijack", "Image query failed: ${throwable.message}")
                            runOnUiThread {
                                Toast.makeText(this@MainActivity, if (isQuota) localized else "Vision error: ${throwable.message?.take(80) ?: "unknown error"}", Toast.LENGTH_LONG).show()
                            }
                            if (isQuota) localized else "I couldn't analyze the image. Please try again."
                        } else {
                            val visionReply = visionResult.getOrNull()?.trim() ?: ""
                            if (visionReply.isBlank()) {
                                "I couldn't analyze that image right now. Please try again."
                            } else if (looksLikeVisionFailed(visionReply)) {
                                Log.w("AIHijack", "Vision relay couldn't process image. Reply: ${visionReply.take(100)}")
                                runOnUiThread {
                                    Toast.makeText(this@MainActivity, "Vision model couldn't process image", Toast.LENGTH_LONG).show()
                                }
                                "I couldn't analyze the image. Please try again."
                            } else {
                                visionReply
                            }
                        }
                    }

                    AgentProviderType.LOCAL_AGENT -> {
                        var receivedModelText = false
                        runChosenProviderQuery(
                            userPrompt = if (audioPath == null) resolvedPrompt.forRoute(ImageQuestionRoute.LOCAL_GEMMA)
                                else "Answer the question spoken in the attached audio about the attached image. Do not merely transcribe it.\n\n" +
                                    ImageQuestionDefaults.responseLanguageInstruction(recognitionLanguageTag()),
                            providerType = AgentProviderType.LOCAL_AGENT,
                            ragProfile = RagProfile.NONE,
                            imagePaths = listOf(imagePath),
                            audioPath = audioPath,
                            onToken = { fragment ->
                                receivedModelText = true
                                localSpeechSessionId?.let { sessionId ->
                                    localSpeechSessionManager.onModelTokenDelta(fragment, sessionId)
                                }
                            },
                        )
                            .also { reply ->
                                if (!receivedModelText) {
                                    localSpeechSessionId?.let { sessionId ->
                                        localSpeechSessionManager.onModelTokenDelta(reply, sessionId)
                                    }
                                }
                            }
                    }

                    AgentProviderType.TASKER -> {
                        val visionResult = CliRelayClient.imageQuery(
                            context = this@MainActivity,
                            imagePath = imagePath,
                            prompt = resolvedPrompt.forRoute(ImageQuestionRoute.TASKER_GEMINI),
                        )
                        if (visionResult.isFailure) {
                            val throwable = visionResult.exceptionOrNull() ?: Exception("unknown error")
                            val localized = RelayErrorLocalizer.localizedMessage(this@MainActivity, throwable)
                            val isQuota = RelayErrorLocalizer.isQuotaError(throwable)
                            Log.e("AIHijack", "Image query failed: ${throwable.message}")
                            runOnUiThread {
                                Toast.makeText(this@MainActivity, if (isQuota) localized else "Vision error: ${throwable.message?.take(80) ?: "unknown error"}", Toast.LENGTH_LONG).show()
                            }
                            if (isQuota) localized else "I couldn't analyze the image. Please try again."
                        } else {
                            val visionReply = visionResult.getOrNull()?.trim() ?: ""
                            if (visionReply.isBlank()) {
                                "I couldn't analyze that image right now. Please try again."
                            } else {
                                visionReply
                            }
                        }
                    }
                }

                val replyToSpeak = finalReply.ifBlank {
                    "I couldn't generate an answer for that image. Please try again."
                }
                if (providerType == AgentProviderType.LOCAL_AGENT && finalReply.isBlank()) {
                    localSpeechSessionId?.let { sessionId ->
                        localSpeechSessionManager.onModelTokenDelta(replyToSpeak, sessionId)
                    }
                }
                Log.i(
                    "AIHijack",
                    "Image query completed provider=$providerType replyLength=${replyToSpeak.length}",
                )
                runOnUiThread {
                    if (providerType == AgentProviderType.LOCAL_AGENT) {
                        localSpeechSessionId?.let(localSpeechSessionManager::onModelGenerationCompleted)
                    } else {
                        speakVision(replyToSpeak, onDone = onSpeechCompleted)
                    }
                }
            } finally {
                audioPath?.let { File(it).delete() }
                imageQueryInProgress.set(false)
            }
        }
        localSpeechSessionId?.let { sessionId ->
            localSpeechSessionManager.attachGenerationJob(sessionId, queryJob)
        }
    }

    private fun cancelLocalStreamingSpeech(reason: String) {
        if (localSpeechSessionManager.activeSessionId == 0L) return
        Log.i("AIHijack", "Cancelling local streaming speech: $reason")
        localSpeechSessionManager.cancelActiveStreamingResponse()
        CoroutineScope(Dispatchers.IO).launch {
            LocalModelsProvider().cancelGeneration()
        }
    }

    private fun beginAiQuestionForegroundWork(status: String) {
        AiQuestionForegroundService.start(this, status)
    }

    private fun finishAiQuestionForegroundWork() {
        AiQuestionForegroundService.stop(this)
    }

    private fun recognitionLanguageTag(): String =
        ImageQuestionPreferences.get(this).appLanguageTag.ifBlank {
            resources.configuration.locales[0]?.toLanguageTag().orEmpty()
                .ifBlank { Locale.getDefault().toLanguageTag() }
        }

    private fun triggerCliRelayImageCaptureAndQuery() {
        handleGlassesImageButtonPressed(
            triggerCapture = true,
            sourceTag = "test_button",
            thumbnailQuality = pendingImageThumbnailQuality,
        )
    }

    private fun handleGlassesImageButtonPressed(
        triggerCapture: Boolean,
        sourceTag: String,
        source: ImageQuestionSource = ImageQuestionSourcePolicy.defaultSource(),
        thumbnailQuality: ImageThumbnailQuality = ImageQuestionSourcePolicy.defaultThumbnailQuality(),
        offerSpokenQuestion: Boolean = true,
    ) {
        Log.i(
            "ImageQuestionTransfer",
            "[$sourceTag] Image request triggerCapture=$triggerCapture source=${source.wireName} " +
                "thumbnailQuality=${thumbnailQuality.label}/${thumbnailQuality.sdkValue} " +
                "offerSpokenQuestion=$offerSpokenQuestion connected=${BleOperateManager.getInstance().isConnected} " +
                "activeSession=${GlassesSessionCoordinator.currentSession()}",
        )
        if (
            offerSpokenQuestion &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            XXPermissions.with(this)
                .permission(HjqPermission.RECORD_AUDIO)
                .request(object : OnPermissionCallback {
                    override fun onGranted(permissions: MutableList<String>, all: Boolean) {
                        handleGlassesImageButtonPressed(
                            triggerCapture = triggerCapture,
                            sourceTag = sourceTag,
                            source = source,
                            thumbnailQuality = thumbnailQuality,
                            offerSpokenQuestion = all,
                        )
                    }

                    override fun onDenied(permissions: MutableList<String>, never: Boolean) {
                        Toast.makeText(
                            this@MainActivity,
                            "Microphone access is needed to ask about the image before its description.",
                            Toast.LENGTH_LONG,
                        ).show()
                        handleGlassesImageButtonPressed(
                            triggerCapture = triggerCapture,
                            sourceTag = sourceTag,
                            source = source,
                            thumbnailQuality = thumbnailQuality,
                            offerSpokenQuestion = false,
                        )
                    }
                })
            return
        }
        pendingImageQuestionSource = source
        pendingImageThumbnailQuality = thumbnailQuality
        pendingImageCaptureStartedAtMs = System.currentTimeMillis()
        pendingImageQuestionOfferSpokenQuestion = offerSpokenQuestion
        if (isMetaRaybanSelected()) {
            captureMetaImageForQuestion(sourceTag)
            return
        }
        if (isEyevueSelected()) {
            captureEyevueImageForQuestion(sourceTag, offerSpokenQuestion)
            return
        }
        if (isTuneBudsSelected()) {
            captureTuneBudsImageForQuestion(sourceTag, offerSpokenQuestion)
            return
        }

        if (isGlassesCommandBlocked("AI image capture")) {
            clearPendingVoiceImageQuestion(sourceTag)
            return
        }
        if (!BleOperateManager.getInstance().isConnected) {
            clearPendingVoiceImageQuestion(sourceTag)
            runOnUiThread {
                Toast.makeText(
                    this@MainActivity,
                    "Glasses are not connected. Connect first to use image query.",
                    Toast.LENGTH_SHORT,
                ).show()
            }
            return
        }
        prepareAiQuestionForLockScreen()
        beginAiQuestionForegroundWork("Capturing image from glasses")

        if (triggerCapture) {
            val permit = acquireBackgroundGlassesCommand("AI image capture") ?: return
            if (imageThumbnailRequestInProgress.get() ||
                !imageCaptureAwaitingNotification.compareAndSet(false, true)
            ) {
                GlassesSessionCoordinator.releaseBackgroundCommand(permit)
                Log.i("AIHijack", "[$sourceTag] Image capture already in progress")
                return
            }

            pendingImageCaptureSourceTag = sourceTag
            pendingImageCapturePermit.set(permit)
            Toast.makeText(this, "Triggering glasses camera…", Toast.LENGTH_SHORT).show()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val thumbnailSize = pendingImageThumbnailQuality.sdkValue.toByte()
                    Log.i(
                        "AIHijack",
                        "[$sourceTag] Requesting BLE AI capture at ${pendingImageThumbnailQuality.label} " +
                            "(${pendingImageThumbnailQuality.sdkValue})",
                    )
                    // Match the official AI-chat sequence: configure the quality-aware vision
                    // mode first, then use the normal shutter command to take the fresh photo.
                    LargeDataHandler.getInstance().glassesControl(
                        byteArrayOf(0x02, 0x01, 0x06, thumbnailSize, thumbnailSize),
                    ) { _, response ->
                        Log.i(
                            "ImageQuestionTransfer",
                            "[$sourceTag] AI vision command response dataType=${response.dataType} " +
                                "error=${response.errorCode}",
                        )
                    }
                    delay(250)
                    check(GlassesSessionCoordinator.isBackgroundCommandActive(permit)) {
                        "Image capture ownership expired"
                    }
                    LargeDataHandler.getInstance().glassesControl(
                        byteArrayOf(0x02, 0x01, 0x01),
                    ) { _, response ->
                        Log.i(
                            "ImageQuestionTransfer",
                            "[$sourceTag] AI shutter command response dataType=${response.dataType} " +
                                "error=${response.errorCode}",
                        )
                    }

                    // The device's 0x02 notification is the authoritative signal that its
                    // image is ready. Never request a thumbnail without it: that could return
                    // an earlier capture and cause the AI to analyze the wrong image.
                    delay(4_000)
                    if (imageCaptureAwaitingNotification.compareAndSet(true, false)) {
                        pendingImageCaptureSourceTag = null
                        clearPendingVoiceImageQuestion(sourceTag)
                        finishAiQuestionForegroundWork()
                        Log.w("AIHijack", "[$sourceTag] No AI photo-ready signal; refusing stale thumbnail fallback")
                        runOnUiThread {
                            Toast.makeText(
                                this@MainActivity,
                                "Glasses did not confirm a new photo. Please try again.",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    }
                } catch (error: Exception) {
                    imageCaptureAwaitingNotification.set(false)
                    pendingImageCaptureSourceTag = null
                    finishAiQuestionForegroundWork()
                    Log.e("AIHijack", "[$sourceTag] Failed to trigger glasses camera: ${error.message}", error)
                } finally {
                    if (pendingImageCapturePermit.compareAndSet(permit, null)) {
                        GlassesSessionCoordinator.releaseBackgroundCommand(permit)
                    }
                }
            }
        } else {
            requestSelectedImageSourceForQuestion(sourceTag)
        }
    }

    private fun requestSelectedImageSourceForQuestion(sourceTag: String) {
        val offerSpokenQuestion = pendingImageQuestionOfferSpokenQuestion
        pendingImageQuestionOfferSpokenQuestion = false
        Log.i(
            "ImageQuestionAudio",
            "[$sourceTag] Photo ready; starting parallel question window after the 500 ms settling delay " +
                "offerSpokenQuestion=$offerSpokenQuestion",
        )
        startParallelAudioQuestionIfEligible(offerSpokenQuestion)
        when (pendingImageQuestionSource) {
            ImageQuestionSource.HIGH_QUALITY -> requestHighQualityImageForQuestion(sourceTag)
            ImageQuestionSource.FAST_PREVIEW -> requestImageThumbnailForQuestion(sourceTag)
        }
    }

    private fun captureMetaImageForQuestion(sourceTag: String) {
        ensureMetaCameraReady {
            if (!metaPhotoCaptureInProgress.compareAndSet(false, true)) {
                Log.i("AIHijack", "[$sourceTag] Meta photo capture already in progress")
                return@ensureMetaCameraReady
            }

            val manager = getOrCreateMetaRaybanManager()
            lifecycleScope.launch(Dispatchers.IO) {
                runCatching {
                    val photo = manager.capturePhotoOnce()
                    manager.savePhotoForProcessing(photo, "META_AI_$sourceTag")
                }.onSuccess { file ->
                    withContext(Dispatchers.Main) {
                        onImageReadyForQuestion(
                            imagePath = file.absolutePath,
                            source = ImageQuestionSource.HIGH_QUALITY,
                            transferDurationMs = System.currentTimeMillis() - pendingImageCaptureStartedAtMs,
                        )
                    }
                }.onFailure { error ->
                    clearPendingVoiceImageQuestion(sourceTag)
                    withContext(Dispatchers.Main) {
                        showMetaError(
                            "AI photo capture ($sourceTag)",
                            error.message ?: "capture failed",
                        )
                    }
                }
                metaPhotoCaptureInProgress.set(false)
            }
        }
    }

    private fun captureEyevueImageForQuestion(sourceTag: String, offerSpokenQuestion: Boolean) {
        val manager = getOrCreateEyevueManager()
        if (!manager.isConnected()) {
            clearPendingVoiceImageQuestion(sourceTag)
            Toast.makeText(this, "Connect Eyevue glasses first.", Toast.LENGTH_SHORT).show()
            return
        }
        if (!eyevueAiPhotoInProgress.compareAndSet(false, true)) {
            Log.i("AIHijack", "[$sourceTag] Eyevue AI photo capture already in progress")
            return
        }

        prepareAiQuestionForLockScreen()
        beginAiQuestionForegroundWork("Capturing image from Eyevue glasses")
        pendingImageQuestionOfferSpokenQuestion = false
        startParallelAudioQuestionIfEligible(offerSpokenQuestion)
        val startedAt = System.currentTimeMillis()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Eyevue's AI-photo path streams over AA15 only for the 0x31 command.
                val imageBytes = manager.capturePhotoForAi()
                val imageFile = File(cacheDir, "Eyevue_AI_${System.currentTimeMillis()}.jpg")
                imageFile.writeBytes(imageBytes)
                withContext(Dispatchers.Main) {
                    onImageReadyForQuestion(
                        imagePath = imageFile.absolutePath,
                        source = ImageQuestionSource.HIGH_QUALITY,
                        transferDurationMs = System.currentTimeMillis() - startedAt,
                    )
                }
            } catch (error: Exception) {
                Log.e("AIHijack", "[$sourceTag] Eyevue AI photo failed", error)
                withContext(Dispatchers.Main) {
                    clearPendingVoiceImageQuestion(sourceTag)
                    finishAiQuestionForegroundWork()
                    Toast.makeText(
                        this@MainActivity,
                        "Eyevue did not return a photo. Please try again.",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            } finally {
                eyevueAiPhotoInProgress.set(false)
            }
        }
    }

    private fun captureTuneBudsImageForQuestion(sourceTag: String, offerSpokenQuestion: Boolean) {
        // Detailed = Wi-Fi high-resolution (slow, via hotspot); all other levels = fast BLE transfer (documented 0xE1 0x01 -> 0xE3)
        val quality = pendingImageThumbnailQuality
        if (quality == ImageThumbnailQuality.DETAILED) {
            captureTuneBudsHighQualityImageForQuestion(sourceTag, offerSpokenQuestion)
        } else {
            captureTuneBudsBleImageForQuestion(sourceTag, offerSpokenQuestion)
        }
    }

    private fun captureTuneBudsBleImageForQuestion(sourceTag: String, offerSpokenQuestion: Boolean) {
        val manager = getOrCreateTuneBudsManager()
        if (!manager.isConnected()) {
            clearPendingVoiceImageQuestion(sourceTag)
            Toast.makeText(this, "Connect TuneBuds glasses first.", Toast.LENGTH_SHORT).show()
            return
        }
        if (!tuneBudsAiPhotoInProgress.compareAndSet(false, true)) {
            Log.i("AIHijack", "[$sourceTag] TuneBuds AI photo capture already in progress")
            return
        }

        prepareAiQuestionForLockScreen()
        beginAiQuestionForegroundWork("Capturing image from TuneBuds glasses")
        pendingImageQuestionOfferSpokenQuestion = false
        startParallelAudioQuestionIfEligible(offerSpokenQuestion)
        val startedAt = System.currentTimeMillis()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val imageBytes = manager.capturePhotoForAi()
                val imageFile = File(cacheDir, "TuneBuds_AI_${System.currentTimeMillis()}.jpg")
                imageFile.writeBytes(imageBytes)
                withContext(Dispatchers.Main) {
                    onImageReadyForQuestion(
                        imagePath = imageFile.absolutePath,
                        source = ImageQuestionSource.FAST_PREVIEW,
                        transferDurationMs = System.currentTimeMillis() - startedAt,
                    )
                }
            } catch (error: Exception) {
                Log.e("AIHijack", "[$sourceTag] TuneBuds AI photo failed", error)
                withContext(Dispatchers.Main) {
                    clearPendingVoiceImageQuestion(sourceTag)
                    finishAiQuestionForegroundWork()
                    Toast.makeText(
                        this@MainActivity,
                        error.message ?: "TuneBuds photo capture failed",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            } finally {
                tuneBudsAiPhotoInProgress.set(false)
            }
        }
    }

    private fun captureTuneBudsHighQualityImageForQuestion(sourceTag: String, offerSpokenQuestion: Boolean) {
        val manager = getOrCreateTuneBudsManager()
        if (!manager.isConnected()) {
            clearPendingVoiceImageQuestion(sourceTag)
            Toast.makeText(this, "Connect TuneBuds glasses first.", Toast.LENGTH_SHORT).show()
            return
        }
        if (!tuneBudsAiPhotoInProgress.compareAndSet(false, true)) {
            Log.i("AIHijack", "[$sourceTag] TuneBuds AI photo capture already in progress")
            return
        }
        if (!hasBluetooth(this) || !hasWifiP2pPermission(this)) {
            tuneBudsAiPhotoInProgress.set(false)
            clearPendingVoiceImageQuestion(sourceTag)
            ensureGlassesTransportPermissions("TuneBuds high-resolution capture") {
                captureTuneBudsHighQualityImageForQuestion(sourceTag, offerSpokenQuestion)
            }
            return
        }

        prepareAiQuestionForLockScreen()
        beginAiQuestionForegroundWork("Capturing high-resolution image from TuneBuds glasses")
        pendingImageQuestionOfferSpokenQuestion = false
        startParallelAudioQuestionIfEligible(offerSpokenQuestion)
        val startedAt = System.currentTimeMillis()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                GlassesPhotoCapture.withCapture(DeviceClass.TUNEBUDS) { lease ->
                    var hotspot: TuneBudsLocalHotspot? = null
                    var tempDir: File? = null
                    try {
                        Log.i("AIHijack", "[$sourceTag] TuneBuds Detailed requested – using Wi-Fi high-quality path")
                        manager.takePhotoBlocking(lease)
                        Log.i("ImageQuestionTransfer", "[$sourceTag] TuneBuds photo saved and camera closed")

                        hotspot = TuneBudsLocalHotspot(this@MainActivity)
                        tempDir = File(cacheDir, "tunebuds_ai_high_${System.currentTimeMillis()}").apply { mkdirs() }
                        val credentials = hotspot.start()
                        Log.i("ImageQuestionTransfer", "[$sourceTag] TuneBuds hotspot started ssid=${credentials.ssid}")
                        val baseUrl = manager.startFileManager(credentials.ssid, credentials.password, credentials.channel)
                            ?: throw IOException("TuneBuds did not report its media server address")
                        Log.i("ImageQuestionTransfer", "[$sourceTag] TuneBuds media endpoint $baseUrl")

                        val sync = TuneBudsMediaSync(manager, hotspot, tempDir)
                        var photoItem: com.fersaiyan.cyanbridge.devices.tunebuds.TuneBudsMediaItem? = null
                        for (attempt in 0 until 12) {
                            val photos = sync.fetchManifest(baseUrl).filter { it.type == TuneBudsMediaType.PHOTO }
                            Log.i("ImageQuestionTransfer", "[$sourceTag] TuneBuds manifest attempt ${attempt + 1}: ${photos.size} photos")
                            if (photos.isNotEmpty()) {
                                // Existing Detailed storage selection is newest timestamped remote name.
                                photoItem = photos.maxByOrNull { it.remoteName }
                                break
                            }
                            if (attempt < 11) kotlinx.coroutines.delay(1000)
                        }
                        val item = photoItem ?: throw IOException("No photos found on TuneBuds after capture")
                        Log.i("ImageQuestionTransfer", "[$sourceTag] TuneBuds downloading ${item.remoteName} via Wi-Fi")
                        val downloaded = sync.downloadSingle(baseUrl, item)
                        lease.ensureActive()
                        val imageFile = File(cacheDir, "TuneBuds_AI_HQ_${System.currentTimeMillis()}.jpg")
                        downloaded.copyTo(imageFile, overwrite = true)
                        withContext(Dispatchers.Main) {
                            onImageReadyForQuestion(
                                imagePath = imageFile.absolutePath,
                                source = ImageQuestionSource.HIGH_QUALITY,
                                transferDurationMs = System.currentTimeMillis() - startedAt,
                            )
                        }
                    } finally {
                        withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) {
                            runCatching { manager.finishTransferBlocking() }
                            runCatching { hotspot?.stop() }
                            tempDir?.let { runCatching { it.deleteRecursively() } }
                        }
                    }
                }
            } catch (error: Exception) {
                Log.e("AIHijack", "[$sourceTag] TuneBuds high-quality photo failed", error)
                withContext(Dispatchers.Main) {
                    clearPendingVoiceImageQuestion(sourceTag)
                    finishAiQuestionForegroundWork()
                    Toast.makeText(
                        this@MainActivity,
                        error.message ?: "TuneBuds high-resolution capture failed",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            } finally {
                tuneBudsAiPhotoInProgress.set(false)
            }
        }
    }

    private fun requestImageThumbnailForQuestion(sourceTag: String) {
        if (isGlassesCommandBlocked("AI thumbnail request")) return
        val permit = pendingImageCapturePermit.getAndSet(null)
            ?: acquireBackgroundGlassesCommand("AI thumbnail request")
            ?: return
        if (!imageThumbnailRequestInProgress.compareAndSet(false, true)) {
            GlassesSessionCoordinator.releaseBackgroundCommand(permit)
            Log.i("AIHijack", "[$sourceTag] Thumbnail request already in progress")
            return
        }

        val outDir = getExternalFilesDir("DCIM") ?: filesDir
        val file = File(outDir, "AI_Thumb_${sourceTag}_${System.currentTimeMillis()}.jpg")
        runCatching {
            file.parentFile?.mkdirs()
            if (file.exists()) file.delete()
        }

        val startedAtMs = System.currentTimeMillis()
        Log.i(
            "ImageQuestionTransfer",
            "[$sourceTag] Starting thumbnail transfer file=${file.absolutePath} " +
                "quality=${pendingImageThumbnailQuality.label}/${pendingImageThumbnailQuality.sdkValue}",
        )
        CoroutineScope(Dispatchers.IO).launch {
            try {
                runCatching {
                    FileOutputStream(file, false).use { }
                }.onFailure { error ->
                    Log.e("AIHijack", "[$sourceTag] Failed to prepare thumbnail file: ${error.message}", error)
                }

                if (receivePictureThumbnail(file, sourceTag, permit)) {
                    Log.i(
                        "AIHijack",
                        "[$sourceTag] Thumbnail transfer complete: ${file.absolutePath} (${file.length()} bytes)",
                    )
                    withContext(Dispatchers.Main) {
                        onImageReadyForQuestion(
                            imagePath = file.absolutePath,
                            source = ImageQuestionSource.FAST_PREVIEW,
                            transferDurationMs = System.currentTimeMillis() - startedAtMs,
                        )
                    }
                    return@launch
                }

                clearPendingVoiceImageQuestion(sourceTag)
                finishAiQuestionForegroundWork()
                Log.w("AIHijack", "[$sourceTag] BLE preview did not complete")
                runOnUiThread {
                    Toast.makeText(
                        this@MainActivity,
                        "Fast preview transfer failed. Please try again.",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            } catch (error: Exception) {
                file.delete()
                clearPendingVoiceImageQuestion(sourceTag)
                finishAiQuestionForegroundWork()
                Log.w("ImageQuestionTransfer", "[$sourceTag] Thumbnail transfer failed", error)
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Fast preview transfer failed. Please try again.", Toast.LENGTH_LONG).show()
                }
            } finally {
                imageThumbnailRequestInProgress.set(false)
                GlassesSessionCoordinator.releaseBackgroundCommand(permit)
            }
        }
    }

    private suspend fun receivePictureThumbnail(
        file: File,
        sourceTag: String,
        permit: BackgroundGlassesCommandPermit,
    ): Boolean {
        check(GlassesSessionCoordinator.isBackgroundCommandActive(permit))
        val image = com.fersaiyan.cyanbridge.glasses.HeyCyanThumbnailReceiver(
            valid = { GlassesSessionCoordinator.isBackgroundCommandActive(permit) },
        ).receive()
        file.writeBytes(image)
        Log.i("ImageQuestionTransfer", "[$sourceTag] Ordered thumbnail fully decoded bytes=${image.size}")
        return true
    }

    private data class ImageQuestionImageMetrics(
        val width: Int,
        val height: Int,
        val bytes: Long,
    )

    private fun onImageReadyForQuestion(
        imagePath: String,
        source: ImageQuestionSource,
        transferDurationMs: Long,
    ) {
        val imageFile = File(imagePath)
        val metrics = readImageQuestionMetrics(imageFile)
        if (metrics == null) {
            pendingVoiceImageQuestion = null
            Log.e("AIHijack", "Image file is missing or invalid: $imagePath (${imageFile.length()} bytes)")
            runOnUiThread {
                Toast.makeText(this, "Image transfer was incomplete. Please try again.", Toast.LENGTH_LONG).show()
            }
            return
        }

        val ageMs = System.currentTimeMillis() - imageFile.lastModified()
        if (ageMs > IMAGE_QUESTION_MAX_IMAGE_AGE_MS || ageMs < 0) {
            pendingVoiceImageQuestion = null
            Log.w("AIHijack", "Image too old: age=${ageMs / 1000}s, path=$imagePath")
            runOnUiThread {
                Toast.makeText(
                    this,
                    "Image is ${ageMs / 60000} min old — too old to use.",
                    Toast.LENGTH_LONG,
                ).show()
            }
            return
        }

        val sourceLabel = if (source == ImageQuestionSource.FAST_PREVIEW) {
            "${pendingImageThumbnailQuality.label} BLE preview"
        } else {
            source.label
        }
        val transferSummary = "$sourceLabel: ${metrics.width}x${metrics.height}, " +
            "${formatTransferBytes(metrics.bytes)}, ${transferDurationMs.coerceAtLeast(0L)} ms"
        Log.i(
            "ImageQuestion",
            "Ready for AI provider: source=${source.wireName}, dimensions=${metrics.width}x${metrics.height}, " +
                "thumbnailQuality=${pendingImageThumbnailQuality.label}, bytes=${metrics.bytes}, " +
                "transferDurationMs=${transferDurationMs.coerceAtLeast(0L)}, path=$imagePath",
        )
        runOnUiThread {
            Toast.makeText(this, transferSummary, Toast.LENGTH_LONG).show()
        }

        lifecycleScope.launch(Dispatchers.IO) {
            var initialQuestion = pendingVoiceImageQuestion
            var captured: CapturedImageQuestion? = null
            pendingVoiceImageQuestion = null
            // Live routes own their continuous microphone session.
            // Non-Live (local, tasker, gemini 3.7 flash) keeps the parallel + sequential windows.
            val isLiveRoute = withContext(Dispatchers.Main) { isGeminiLiveRoutedForImageQuestion() }
            if (isLiveRoute) {
                withContext(Dispatchers.Main) { cancelParallelAudioQuestion() }
                Log.i("ImageQuestion", "Live route: skipping legacy Ask windows, using system image prompt")
            } else if (initialQuestion.isNullOrBlank()) {
                val parallelDeferred = activeParallelAudioQuestionDeferred
                activeParallelAudioQuestionDeferred = null
                activeParallelAudioQuestionJob = null
                if (parallelDeferred != null) {
                    Log.i("ImageQuestion", "Awaiting parallel audio question recording...")
                    captured = parallelDeferred.await()
                    initialQuestion = captured?.text
                } else if (
                    ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                ) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@MainActivity,
                            "Ask about the image now, or wait for the default description.",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                    captured = captureOptionalImageQuestionFromBluetoothMic(
                        timeoutMs = IMAGE_QUESTION_INITIAL_LISTENING_TIMEOUT_MS,
                    )
                    initialQuestion = captured.text
                }
            } else {
                cancelParallelAudioQuestion()
            }
            if (captured?.error != null) {
                withContext(Dispatchers.Main) { speak(captured.error!!); finishAiQuestionForegroundWork() }
                return@launch
            }
            val externalAutomation = usesExternalImageAutomation()
            fun offerFollowUp() {
                lifecycleScope.launch {
                    delay(500L)
                    val followUp = captureOptionalImageQuestionFromBluetoothMic(
                        timeoutMs = IMAGE_QUESTION_INITIAL_LISTENING_TIMEOUT_MS,
                    )
                    if (followUp.error != null) {
                        speak(followUp.error)
                    } else if (followUp.heardSpeech) {
                        triggerAssistantImageQuery(
                            imagePath = imagePath,
                            userQuestion = followUp.text,
                            audioPath = followUp.audioPath,
                            source = source,
                            onReplySpoken = ::offerFollowUp,
                        )
                    }
                }
            }
            triggerAssistantImageQuery(
                imagePath = imagePath,
                userQuestion = initialQuestion,
                audioPath = captured?.audioPath,
                source = source,
                onReplySpoken = if (externalAutomation) null else ::offerFollowUp,
            )

            // External assistant apps own response playback and their own conversation UI.
            if (externalAutomation) return@launch
        }
    }

    private fun readImageQuestionMetrics(file: File): ImageQuestionImageMetrics? {
        if (!file.exists() || file.length() <= 0L) return null
        return runCatching {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, options)
            if (options.outWidth <= 0 || options.outHeight <= 0) {
                null
            } else {
                ImageQuestionImageMetrics(options.outWidth, options.outHeight, file.length())
            }
        }.getOrNull()
    }

    private fun isDecodableImageFile(file: File): Boolean {
        return readImageQuestionMetrics(file) != null
    }

    private suspend fun waitForTtsToFinish(timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        var warned = false
        while (isTtsSpeaking() && System.currentTimeMillis() < deadline) {
            if (!warned) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Replying…", Toast.LENGTH_SHORT).show()
                }
                warned = true
            }
            delay(500)
        }
        if (isTtsSpeaking()) {
            Log.w("AIHijack", "TTS still speaking after ${timeoutMs}ms, proceeding anyway")
        }
    }

    private fun isTtsSpeaking(): Boolean {
        return tts?.isSpeaking == true
    }

    private fun isDeviceLockedForAutomation(): Boolean {
        val keyguardManager = getSystemService(KEYGUARD_SERVICE) as? KeyguardManager ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            keyguardManager.isDeviceLocked
        } else {
            keyguardManager.isKeyguardLocked
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun cancelParallelAudioQuestion() {
        val job = activeParallelAudioQuestionJob
        activeParallelAudioQuestionJob = null
        job?.cancel()
        val deferred = activeParallelAudioQuestionDeferred
        activeParallelAudioQuestionDeferred = null
        if (deferred?.isCompleted == true) runCatching { deferred.getCompleted()?.audioPath?.let { File(it).delete() } }
        if (deferred?.isCompleted == false) {
            deferred.complete(null)
        }
    }

    private fun startParallelAudioQuestionIfEligible(offerSpokenQuestion: Boolean) {
        // Gemini Live handles voice continuously after the session is listening.
        // Skipping the recorded Ask window here keeps local/tasker/
        // non-Live Pro (e.g. gemini 3.7 flash) parallel behavior unchanged while
        // Live takes a photo and shows Initializing Live instead.
        if (isGeminiLiveRoutedForImageQuestion()) {
            cancelParallelAudioQuestion()
            pendingImageQuestionOfferSpokenQuestion = false
            Log.i("ImageQuestionAudio", "Skipping parallel Ask window for Gemini Live route")
            return
        }
        cancelParallelAudioQuestion()
        if (
            offerSpokenQuestion &&
            pendingVoiceImageQuestion.isNullOrBlank() &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        ) {
            val deferred = kotlinx.coroutines.CompletableDeferred<CapturedImageQuestion?>()
            activeParallelAudioQuestionDeferred = deferred
            activeParallelAudioQuestionJob = lifecycleScope.launch(Dispatchers.Main) {
                // 500 ms settling delay for photo capture command & hardware shutter sound
                delay(500L)
                if (!deferred.isCompleted && !deferred.isCancelled) {
                    Toast.makeText(
                        this@MainActivity,
                        "Ask about the image now, or wait for the default description.",
                        Toast.LENGTH_SHORT,
                    ).show()
                    val spokenQuestion = captureOptionalImageQuestionFromBluetoothMic(
                        timeoutMs = IMAGE_QUESTION_INITIAL_LISTENING_TIMEOUT_MS,
                    )
                    if (!deferred.complete(spokenQuestion)) spokenQuestion.audioPath?.let { File(it).delete() }
                }
            }
        }
    }

    private fun clearPendingVoiceImageQuestion(sourceTag: String) {
        if (sourceTag == "voice_request") {
            pendingVoiceImageQuestion = null
        }
        pendingImageQuestionOfferSpokenQuestion = false
        cancelParallelAudioQuestion()
    }

    /**
     * Copy an image file to DCIM/Camera/ with the Glasses_AI_ naming convention.
     * Returns the public file path on success, null on failure.
     */
    private fun copyImageToPublicCamera(sourcePath: String): String? {
        val source = File(sourcePath)
        if (!source.exists() || source.length() == 0L) {
            Log.w("AIHijack", "Source image missing or empty: $sourcePath")
            return null
        }
        return try {
            val publicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
            val cameraDir = File(publicDir, "Camera")
            if (!cameraDir.exists()) cameraDir.mkdirs()
            val publicFile = File(cameraDir, "Glasses_AI_${System.currentTimeMillis()}.jpg")
            source.copyTo(publicFile, overwrite = true)
            // Scan so MediaStore / Tasker file picker can see it immediately
            MediaScannerConnection.scanFile(this, arrayOf(publicFile.absolutePath), arrayOf("image/jpeg")) { _, _ ->
                Log.i("AIHijack", "Scanned to gallery: ${publicFile.absolutePath} (${publicFile.length()} bytes)")
            }
            Log.i("AIHijack", "Copied thumbnail to public: ${publicFile.absolutePath}")
            publicFile.absolutePath
        } catch (e: Exception) {
            Log.e("AIHijack", "Failed to copy image to public DCIM: ${e.message}")
            null
        }
    }

    /** Detect when the vision model couldn't actually see the image (server-side issue). */
    private fun looksLikeVisionFailed(reply: String): Boolean {
        val lower = reply.lowercase()
        return lower.contains("upload") && lower.contains("image") ||
            lower.contains("please provide the image") ||
            lower.contains("i can't see") ||
            lower.contains("no image") && lower.contains("provided") ||
            lower.contains("attach") && lower.contains("image") ||
            lower.contains("invalid") && lower.contains("image") ||
            lower.contains("does not represent a valid image") ||
            lower.contains("image data") && lower.contains("invalid") ||
            lower.contains("vision") && lower.contains("failed") ||
            lower.contains("couldn't analyze") ||
            lower.contains("openrouter_image_failed")
    }

    private data class CapturedImageQuestion(
        val text: String?,
        val heardSpeech: Boolean,
        val audioPath: String? = null,
        val error: String? = null,
    )

    private suspend fun captureOptionalImageQuestionFromBluetoothMic(
        timeoutMs: Long,
        voiceOnly: Boolean = false,
    ): CapturedImageQuestion = questionMicrophoneMutex.withLock {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        val owner = VoiceAudioRouteOwner(Any(), audioManager)
        activeVoiceAudioRoute.set(owner)
        var audioFile: File? = null
        try {
            withContext(Dispatchers.Main) {
                val ready = startBluetoothMicRouteAndAwait(audioManager)
                Log.i("ImageQuestionAudio", "Question route ready=$ready ${audioRouteSummary(audioManager)}")
                if (Build.VERSION.SDK_INT >= 31 && audioManager.availableCommunicationDevices.any {
                    it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == android.media.AudioDeviceInfo.TYPE_BLE_HEADSET
                }) check(ready) { "Could not connect the glasses microphone" }
                playImageQuestionTone(android.media.ToneGenerator.TONE_PROP_BEEP)
                speakImageQuestionCue(voiceOnly)
            }
            val communicationName = if (Build.VERSION.SDK_INT >= 31) audioManager.communicationDevice?.productName?.toString() else null
            val input = audioManager.getDevices(android.media.AudioManager.GET_DEVICES_INPUTS).firstOrNull {
                (it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    (Build.VERSION.SDK_INT >= 31 && it.type == android.media.AudioDeviceInfo.TYPE_BLE_HEADSET)) &&
                    (communicationName == null || it.productName.toString() == communicationName)
            }
            audioFile = AudioQuestionRecorder(applicationContext).record(timeoutMs, input)
            withContext(Dispatchers.Main) { playImageQuestionTone(android.media.ToneGenerator.TONE_PROP_BEEP2) }
        } catch (cancelled: CancellationException) {
            audioFile?.delete()
            throw cancelled
        } catch (error: Exception) {
            audioFile?.delete()
            Log.w("ImageQuestionAudio", "Question capture failed", error)
            return@withLock CapturedImageQuestion(null, false, error = error.message ?: "Microphone capture failed")
        } finally {
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) {
                if (activeVoiceAudioRoute.compareAndSet(owner, null)) {
                    if (Build.VERSION.SDK_INT >= 31) audioManager.clearCommunicationDevice()
                    audioManager.isBluetoothScoOn = false
                    audioManager.stopBluetoothSco()
                    audioManager.mode = android.media.AudioManager.MODE_NORMAL
                }
            }
        }
        val recorded = audioFile ?: return@withLock CapturedImageQuestion(null, false)
        try {
            val text = OfflineQuestionTranscriber(applicationContext).transcribe(recorded, recognitionLanguageTag())
            if (text != null) {
                recorded.delete()
                Log.i("ImageQuestionAudio", "On-device transcript ready chars=${text.length}")
                CapturedImageQuestion(text, true)
            } else {
                Log.i("ImageQuestionAudio", "On-device STT failed/unavailable; handing original audio to model")
                CapturedImageQuestion(null, true, recorded.absolutePath)
            }
        } catch (cancelled: CancellationException) {
            recorded.delete()
            throw cancelled
        }
    }

    private suspend fun playImageQuestionTone(toneType: Int) {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        val tone = android.media.ToneGenerator(android.media.AudioManager.STREAM_VOICE_CALL, 90)
        try {
            val played = tone.startTone(toneType, 240)
            Log.i(
                "ImageQuestionAudio",
                "Image-question tone type=$toneType played=$played route=${audioRouteSummary(audioManager)}",
            )
            delay(300L)
        } finally {
            tone.release()
        }
    }

    private suspend fun speakImageQuestionCue(voiceOnly: Boolean = false) {
        suspendCancellableCoroutine { cont ->
            val completed = AtomicBoolean(false)
            val utteranceId = "image_question_cue_${System.nanoTime()}"
            fun complete(reason: String) {
                if (completed.compareAndSet(false, true)) {
                    ttsDoneCallbacks.remove(utteranceId)
                    Log.i("ImageQuestionAudio", "Image-question cue complete reason=$reason id=$utteranceId")
                    // Keep only enough separation to avoid clipping the route transition. The
                    // reclaimed tail time is added to the initial listening window.
                    lifecycleScope.launch {
                        delay(IMAGE_QUESTION_CUE_BLUETOOTH_TAIL_MS)
                        if (cont.isActive) {
                            cont.resume(Unit)
                        }
                    }
                }
            }

            val questionSettings = ImageQuestionPreferences.get(this)
            val cue = if (voiceOnly) ImageQuestionDefaults.listeningCueForLanguage(questionSettings.appLanguageTag)
                else ImageQuestionDefaults.questionCueForLanguage(questionSettings.appLanguageTag)
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            configureTtsForVoiceCommunication("image-question cue")
            Log.i(
                "ImageQuestionAudio",
                "Queueing image-question cue='$cue' language=${questionSettings.appLanguageTag} " +
                    "attributes=voice_communication/speech " +
                    "ttsReady=$ttsReady route=${audioRouteSummary(audioManager)}",
            )
            speak(
                text = cue,
                languageTag = questionSettings.appLanguageTag,
                utteranceId = utteranceId,
                onDone = { complete("tts callback") },
                streamType = android.media.AudioManager.STREAM_VOICE_CALL,
            )
            // Avoid blocking the image question if the system TTS service never returns a callback.
            lifecycleScope.launch {
                delay(2_000L)
                complete("2s fallback")
            }
            cont.invokeOnCancellation {
                ttsDoneCallbacks.remove(utteranceId)
            }
        }
    }

    private fun configureTtsForVoiceCommunication(reason: String) {
        val result = tts?.setAudioAttributes(
            android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        Log.i("ImageQuestionAudio", "Configured TTS voice-communication audio reason=$reason result=$result")
    }

    private fun startBluetoothMicRoute(audioManager: android.media.AudioManager) {
        runCatching {
            Log.i("ImageQuestionAudio", "Selecting Bluetooth microphone route: ${audioRouteSummary(audioManager)}")
            audioManager.mode = android.media.AudioManager.MODE_IN_COMMUNICATION
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val devices = audioManager.availableCommunicationDevices.filter {
                    it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                        it.type == android.media.AudioDeviceInfo.TYPE_BLE_HEADSET
                }
                val glassesName = runCatching { DeviceManager.getInstance().deviceName }.getOrNull()
                val device = devices.firstOrNull { it.productName.toString().equals(glassesName, ignoreCase = true) }
                    ?: devices.singleOrNull()
                check(devices.size <= 1 || device != null) { "Multiple Bluetooth microphones are connected; could not identify the glasses" }
                if (device != null) {
                    val selected = audioManager.setCommunicationDevice(device)
                    Log.i(
                        "ImageQuestionAudio",
                        "Communication device candidate type=${device.type} name=${device.productName} " +
                            "selected=$selected route=${audioRouteSummary(audioManager)}",
                    )
                    if (selected) return
                }
            }
            @Suppress("DEPRECATION")
            audioManager.startBluetoothSco()
            @Suppress("DEPRECATION")
            audioManager.isBluetoothScoOn = true
            Log.i("ImageQuestionAudio", "Requested legacy Bluetooth SCO route: ${audioRouteSummary(audioManager)}")
        }.onFailure {
            Log.w("AIHijack", "Bluetooth microphone route unavailable; using phone microphone", it)
        }
    }

    private suspend fun startBluetoothMicRouteAndAwait(
        audioManager: android.media.AudioManager,
        timeoutMs: Long = VOICE_BLUETOOTH_ROUTE_TIMEOUT_MS,
    ): Boolean {
        val connected = CompletableDeferred<Boolean>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != android.media.AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED) return
                val state = intent.getIntExtra(android.media.AudioManager.EXTRA_SCO_AUDIO_STATE, -1)
                Log.i("ImageQuestionAudio", "Bluetooth SCO state=$state route=${audioRouteSummary(audioManager)}")
                when (state) {
                    android.media.AudioManager.SCO_AUDIO_STATE_CONNECTED -> connected.complete(true)
                    android.media.AudioManager.SCO_AUDIO_STATE_ERROR -> connected.complete(false)
                }
            }
        }
        var receiverRegistered = false

        return try {
            ContextCompat.registerReceiver(
                this,
                receiver,
                IntentFilter(android.media.AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            receiverRegistered = true
            startBluetoothMicRoute(audioManager)

            val ready: Boolean = if (isBluetoothCommunicationDeviceSelected(audioManager)) {
                true
            } else {
                withTimeoutOrNull<Boolean>(timeoutMs) {
                    while (!isBluetoothCommunicationDeviceSelected(audioManager) && !connected.isCompleted) {
                        delay(100L)
                    }
                    isBluetoothCommunicationDeviceSelected(audioManager) || connected.await()
                } ?: false
            }
            if (ready) delay(VOICE_CUE_ROUTE_SETTLE_MS)
            ready
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w("ImageQuestionAudio", "Could not confirm Bluetooth communication route", error)
            false
        } finally {
            if (receiverRegistered) runCatching { unregisterReceiver(receiver) }
        }
    }

    private fun isBluetoothCommunicationDeviceSelected(audioManager: android.media.AudioManager): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        return when (audioManager.communicationDevice?.type) {
            android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            android.media.AudioDeviceInfo.TYPE_BLE_HEADSET -> true
            else -> false
        }
    }

    private fun audioRouteSummary(audioManager: android.media.AudioManager): String {
        val communicationDevice = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.communicationDevice?.let { "${it.type}:${it.productName}" } ?: "none"
        } else {
            "unsupported"
        }
        val devices = audioManager.getDevices(
            android.media.AudioManager.GET_DEVICES_INPUTS or
                android.media.AudioManager.GET_DEVICES_OUTPUTS,
        )
            .joinToString { "${it.type}:${it.productName}" }
        return "mode=${audioManager.mode}, sco=${audioManager.isBluetoothScoOn}, " +
            "communication=$communicationDevice, devices=[$devices]"
    }

    private fun triggerCliRelayVoiceQuery(
        memoryAwareChosenProvider: Boolean = false,
        chosenProviderType: AgentProviderType? = null,
    ) {
        if (isGlassesCommandBlocked("voice-query command")) return
        if (isImageQuestionWorkActive()) {
            Log.i("AIHijack", "Ignoring voice query while an image question is active")
            Toast.makeText(this, "An image question is already active", Toast.LENGTH_SHORT).show()
            return
        }
        cancelLocalStreamingSpeech("new voice query")
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            XXPermissions.with(this)
                .permission(HjqPermission.RECORD_AUDIO)
                .request(object : OnPermissionCallback {
                    override fun onGranted(permissions: MutableList<String>, all: Boolean) {
                        if (all) {
                            triggerCliRelayVoiceQuery(memoryAwareChosenProvider, chosenProviderType)
                        } else {
                            Toast.makeText(
                                this@MainActivity,
                                "Microphone permission is required for voice questions",
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }

                    override fun onDenied(permissions: MutableList<String>, never: Boolean) {
                        super.onDenied(permissions, never)
                        Toast.makeText(
                            this@MainActivity,
                            "Microphone permission is required for voice questions",
                            Toast.LENGTH_SHORT,
                        ).show()
                        if (never) {
                            XXPermissions.startPermissionActivity(this@MainActivity, permissions)
                        }
                    }
                })
            return
        }
        prepareAiQuestionForLockScreen()
        val voiceQueryToken = Any()
        if (!voiceQueryInProgress.compareAndSet(null, voiceQueryToken)) {
            Log.i("AIHijack", "Ignoring duplicate voice query while another query is active")
            Toast.makeText(this, "A voice question is already active", Toast.LENGTH_SHORT).show()
            return
        }
        beginAiQuestionForegroundWork("Listening for glasses voice question")
        // Wake up screen if locked
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val keyguardManager = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
            keyguardManager.requestDismissKeyguard(this, null)
        }

        // Tell glasses to stop proprietary AI audio stream
        stopGlassesAiAudio("voice-query command")

        val audioManager = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        val audioRouteOwner = VoiceAudioRouteOwner(voiceQueryToken, audioManager)
        activeVoiceAudioRoute.set(audioRouteOwner)

        fun stopSco() {
            // A delayed callback from an older query must not clear the route selected by a
            // newer query. AudioManager is a process singleton, so the per-query owner object
            // is what makes this check race-safe.
            if (!activeVoiceAudioRoute.compareAndSet(audioRouteOwner, null)) return
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    audioManager.clearCommunicationDevice()
                }
                audioManager.isBluetoothScoOn = false
                audioManager.stopBluetoothSco()
                audioManager.mode = android.media.AudioManager.MODE_NORMAL
            }
        }

        fun finishVoiceQueryWork() {
            if (voiceQueryInProgress.compareAndSet(voiceQueryToken, null)) {
                finishAiQuestionForegroundWork()
            }
        }

        Toast.makeText(this, "Listening for voice query…", Toast.LENGTH_SHORT).show()

        fun processVoicePrompt(prompt: String) {

                if (prompt.isBlank()) {
                    stopSco()
                    finishVoiceQueryWork()
                    return
                }

                Toast.makeText(this@MainActivity, "Asking: $prompt", Toast.LENGTH_SHORT).show()

                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val selectedProvider = chosenProviderType
                            ?: AutomationPrefs.getProviderType(this@MainActivity)
                        val routingProvider = if (memoryAwareChosenProvider) {
                            selectedProvider
                        } else {
                            // This legacy branch is retained only for direct callers outside the
                            // two-mode Glasses dashboard.
                            AgentProviderType.TASKER
                        }
                        val routing = assistantRequestRouter.route(
                            context = this@MainActivity,
                            request = AssistantRequest(
                                text = prompt,
                                source = AssistantRequestSource.GLASSES_VOICE,
                            ),
                            providerType = routingProvider,
                        )

                        when (routing.intent) {
                            AssistantIntent.ANSWER_QUESTION -> {
                                if (
                                    memoryAwareChosenProvider &&
                                    shouldUseGeminiLiveQuestions(selectedProvider)
                                ) {
                                    withContext(Dispatchers.Main) {
                                        stopSco()
                                        finishVoiceQueryWork()
                                        launchGeminiLiveQuestion(prompt = prompt)
                                    }
                                    return@launch
                                }
                                val reply = if (memoryAwareChosenProvider) {
                                    runChosenProviderQuery(
                                        userPrompt = prompt,
                                        providerType = selectedProvider,
                                        ragProfile = RagProfile.LIGHT,
                                    )
                                } else {
                                    val modelOverride = if (selectedProvider == AgentProviderType.PRO_SUBSCRIPTION) {
                                        ProSubscriptionAiPrefs.getQuestionsModel(this@MainActivity)
                                    } else {
                                        null
                                    }

                                    CliRelayClient.voiceQuery(
                                        context = this@MainActivity,
                                        prompt = prompt,
                                        modelOverride = modelOverride,
                                    ).getOrElse { RelayErrorLocalizer.localizedMessage(this@MainActivity, it) }
                                }

                                runOnUiThread {
                                    speakVision(reply) {
                                        stopSco()
                                        finishVoiceQueryWork()
                                    }
                                }
                            }

                            AssistantIntent.ANALYZE_IMAGE -> runOnUiThread {
                                stopSco()
                                // The image flow now owns the shared foreground service. Release
                                // only this voice-query guard without stopping that service.
                                voiceQueryInProgress.compareAndSet(voiceQueryToken, null)
                                pendingVoiceImageQuestion = routing.normalizedGoal ?: prompt
                                speak("Okay. I'll check what you see.")
                                handleGlassesImageButtonPressed(
                                    triggerCapture = true,
                                    sourceTag = "voice_request",
                                )
                            }

                            AssistantIntent.EXECUTE_UI_TASK -> runOnUiThread {
                                stopSco()
                                if (!AutomationPrefs.isLocalAgentAutomationEnabled(this@MainActivity)) {
                                    finishVoiceQueryWork()
                                    speak("Enable Local Agent phone control in CyanBridge settings first.")
                                    return@runOnUiThread
                                }
                                if (isDeviceLockedForAutomation()) {
                                    finishVoiceQueryWork()
                                    speak("Unlock your phone before I control it.")
                                    return@runOnUiThread
                                }
                                if (!TaskerIntegrationManager.inspect(this@MainActivity).automationEnvironmentReady) {
                                    finishVoiceQueryWork()
                                    speak("Complete Tasker and AutoInput setup in CyanBridge Plugins first.")
                                    return@runOnUiThread
                                }

                                val goal = routing.normalizedGoal ?: prompt
                                val result = LocalAgentController.start(this@MainActivity, goal)
                                if (result.ok) {
                                    speak("Okay. I'll do that.")
                                } else {
                                    speak("I couldn't start phone control.")
                                }
                                finishVoiceQueryWork()
                            }

                            AssistantIntent.CLARIFY -> runOnUiThread {
                                stopSco()
                                finishVoiceQueryWork()
                                speak(
                                    AssistantSpeechPolicy.clarification(routing.clarification)
                                )
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        stopSco()
                        finishVoiceQueryWork()
                        throw cancelled
                    } catch (error: Exception) {
                        Log.e("AIHijack", "Voice query processing failed", error)
                        runOnUiThread {
                            stopSco()
                            finishVoiceQueryWork()
                            speak("I couldn't process that voice question. Please try again.")
                        }
                    }
                }

        }

        val provider = chosenProviderType ?: AutomationPrefs.getProviderType(this)
        if (memoryAwareChosenProvider && shouldUseGeminiLiveQuestions(provider)) {
            stopSco()
            finishVoiceQueryWork()
            launchGeminiLiveQuestion(prompt = "Listen to the user's spoken question and answer it.")
            return
        }
        lifecycleScope.launch {
            var originalAudio: String? = null
            try {
                val captured = captureOptionalImageQuestionFromBluetoothMic(VOICE_QUESTION_ONSET_TIMEOUT_MS, voiceOnly = true)
                originalAudio = captured.audioPath
                stopSco()
                when {
                    captured.error != null -> { finishVoiceQueryWork(); speak(captured.error) }
                    !captured.heardSpeech -> { finishVoiceQueryWork(); speak("I couldn't hear a voice question. Please try again.") }
                    captured.text != null -> processVoicePrompt(captured.text)
                    captured.audioPath != null -> {
                        // Transcription failure keeps the original question; raw audio is never
                        // sent for cloud transcription or replaced with a generic question.
                        val reply = withContext(Dispatchers.IO) {
                            runChosenProviderQuery(
                                userPrompt = "Answer the question spoken in the attached audio. Do not merely transcribe it.",
                                providerType = AgentProviderType.LOCAL_AGENT,
                                ragProfile = RagProfile.NONE,
                                audioPath = captured.audioPath,
                            )
                        }
                        speakVision(reply) { finishVoiceQueryWork() }
                    }
                }
            } catch (cancelled: CancellationException) {
                finishVoiceQueryWork()
                throw cancelled
            } catch (error: Exception) {
                Log.e("AIHijack", "Voice audio query failed", error)
                finishVoiceQueryWork()
                speak("I couldn't process that voice question. Please try again.")
            } finally {
                originalAudio?.let { File(it).delete() }
                stopSco()
            }
        }
    }

    private fun isImageQuestionWorkActive(): Boolean {
        return imageQueryInProgress.get() ||
            imageCaptureAwaitingNotification.get() ||
            imageThumbnailRequestInProgress.get() ||
            metaPhotoCaptureInProgress.get() ||
            eyevueAiPhotoInProgress.get() ||
            highQualityImageRequest != null ||
            activeParallelAudioQuestionJob?.isActive == true
    }

    private fun triggerAssistantVoiceQuery() {
        if (isGlassesCommandBlocked("voice-query command")) return
        val effectiveMode = resolveEffectiveAiAssistantMode()
        Log.i("AIHijack", "Triggering Voice Query for $effectiveMode")

        when (currentAssistantRoute()) {
            GlassesAssistantRoute.LOCAL -> {
                triggerCliRelayVoiceQuery(
                    memoryAwareChosenProvider = true,
                    chosenProviderType = AgentProviderType.LOCAL_AGENT,
                )
            }

            GlassesAssistantRoute.PRO -> {
                triggerCliRelayVoiceQuery(
                    memoryAwareChosenProvider = true,
                    chosenProviderType = AgentProviderType.PRO_SUBSCRIPTION,
                )
            }

            GlassesAssistantRoute.TASKER_EXTERNAL_UI -> triggerTaskerVoiceAutomation()
            GlassesAssistantRoute.PHONE_ASSISTANT -> launchPhoneAssistant()
        }
    }

    private fun handleAiWakeWordActivation(source: String) {
        if (!isAiHijackEnabled) return
        val route = AiWakeWordPreferences.route(this)
        Log.i("AIHijack", "AI wake activation source=$source route=$route")
        runOnUiThread {
            if (source == "eyevue") {
                getOrCreateEyevueManager().stopVoiceRecognition()
            } else {
                stopGlassesAiAudio("$source wake-word route")
            }
            when (route) {
                AiWakeWordRoute.VOICE_QUESTION -> triggerAssistantVoiceQuery()
                AiWakeWordRoute.IMAGE_QUESTION -> handleGlassesImageButtonPressed(
                    triggerCapture = true,
                    sourceTag = "${source}_wake_word",
                    source = ImageQuestionSourcePolicy.defaultSource(),
                    thumbnailQuality = ImageQuestionSourcePolicy.defaultThumbnailQuality(),
                    offerSpokenQuestion = true,
                )
            }
        }
    }

    private fun triggerTaskerVoiceAutomation() {
        prepareAiQuestionForLockScreen()
        lifecycleScope.launch {
            delay(350L)
            val capability = ExternalAssistantAutomationInspector.inspect(this@MainActivity)
            val blockingReason = ExternalAssistantAutomationPolicy.voiceBlockingReason(capability)
            if (blockingReason != null) {
                Toast.makeText(this@MainActivity, blockingReason, Toast.LENGTH_LONG).show()
                speak(blockingReason)
                return@launch
            }

            stopGlassesAiAudio("Tasker voice-query command")
            sendAiBroadcast(
                type = "voice",
                assistantMode = capability.target.label,
            )
        }
    }

    private fun launchPhoneAssistant() {
        // Wake up screen if locked. Android's selected assistant remains the authority for
        // whether voice interaction itself is allowed over the keyguard.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val keyguardManager = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
            keyguardManager.requestDismissKeyguard(this, null)
        }

        stopGlassesAiAudio("phone-assistant voice command")

        try {
            startActivity(Intent(Intent.ACTION_VOICE_COMMAND).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
        } catch (e: Exception) {
            Log.e("AIHijack", "Failed to trigger phone assistant: ${e.message}")
            runOnUiThread {
                Toast.makeText(this, "No phone assistant is configured", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun usesExternalImageAutomation(): Boolean {
        return usesExternalAssistantUi()
    }

    private fun externalImageQuestion(userQuestion: String?): String {
        return resolveImageQuestionPrompt(userQuestion)
            .forRoute(ImageQuestionRoute.TASKER_GEMINI)
    }

    private fun stageImageForExternalShare(source: File): File? {
        if (!source.isFile || source.length() <= 0L) return null
        val authority = "$packageName.fileprovider"
        if (runCatching { FileProvider.getUriForFile(this, authority, source) }.isSuccess) {
            return source
        }

        return runCatching {
            val dir = (getExternalFilesDir("image-questions") ?: File(filesDir, "image-questions"))
                .apply { mkdirs() }
            val extension = source.extension.ifBlank { "jpg" }
            File(dir, "AI_Share_${System.currentTimeMillis()}.$extension").also { target ->
                source.copyTo(target, overwrite = true)
            }
        }.onFailure { error ->
            Log.e("ImageQuestion", "Could not stage image for FileProvider sharing", error)
        }.getOrNull()
    }

    private fun canStartExternalImageShare(packageName: String): Boolean {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            setPackage(packageName)
        }
        return intent.resolveActivity(packageManager) != null
    }

    private fun startExternalImageShare(
        targetPackage: String,
        imageUri: Uri,
        question: String,
    ): Boolean {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            setPackage(targetPackage)
            putExtra(Intent.EXTRA_STREAM, imageUri)
            putExtra(Intent.EXTRA_TEXT, question)
            clipData = ClipData.newRawUri("CyanBridge image", imageUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return runCatching {
            grantUriPermission(targetPackage, imageUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(shareIntent)
            true
        }.onFailure { error ->
            Log.w("ImageQuestion", "Direct image share failed for $targetPackage", error)
        }.getOrDefault(false)
    }

    private fun handOffImageToExternalAssistant(
        imagePath: String,
        userQuestion: String?,
        source: ImageQuestionSource,
    ) {
        val capability = ExternalAssistantAutomationInspector.inspect(this)
        ExternalAssistantAutomationPolicy.imageBlockingReason(capability)?.let { reason ->
            Toast.makeText(this, reason, Toast.LENGTH_LONG).show()
            speak(reason)
            return
        }
        val stagedFile = stageImageForExternalShare(File(imagePath))
        if (stagedFile == null) {
            Log.e("ImageQuestion", "External handoff image is unavailable: $imagePath")
            Toast.makeText(this, "The image file is unavailable for the phone assistant.", Toast.LENGTH_LONG).show()
            return
        }
        val imageUri = runCatching {
            FileProvider.getUriForFile(this, "$packageName.fileprovider", stagedFile)
        }.getOrElse { error ->
            Log.e("ImageQuestion", "Could not create image content URI", error)
            Toast.makeText(this, "Could not share the image with the phone assistant.", Toast.LENGTH_LONG).show()
            return
        }
        val question = externalImageQuestion(userQuestion)
        val session = ExternalImageAutomationStore.begin(
            context = this,
            imagePath = stagedFile.absolutePath,
            imageUri = imageUri.toString(),
            question = question,
            source = source,
        )
        val targetPackage = capability.targetPackage
        val directSharePackage = targetPackage?.takeIf {
            !isDeviceLockedForAutomation() && canStartExternalImageShare(it)
        }
        val handoffMode = if (directSharePackage != null) {
            ImageQuestionBroadcast.HANDOFF_DIRECT_SHARE
        } else {
            ImageQuestionBroadcast.HANDOFF_AUTOINPUT_FALLBACK
        }

        if (directSharePackage != null) {
            if (startExternalImageShare(directSharePackage, imageUri, question)) {
                ExternalImageAutomationStore.recordLocalStage(this, ExternalImageAutomationStage.IMAGE_ATTACHED)
                // The selectors run only after the image has reached this explicit package.
                sendAiBroadcast(
                    type = ImageQuestionBroadcast.TYPE_IMAGE,
                    path = stagedFile.absolutePath,
                    prompt = question,
                    imageUri = imageUri,
                    imageSource = source,
                    handoffMode = handoffMode,
                    callbackSession = session.sessionId,
                    callbackToken = session.callbackToken,
                    assistantMode = selectedImageAutomationTarget().label,
                )
                ExternalImageAutomationStore.recordLocalStage(this, ExternalImageAutomationStage.PROMPT_SENT)
                Log.i("ImageQuestion", "Started direct image share to $directSharePackage")
                return
            }
        }

        sendAiBroadcast(
            type = ImageQuestionBroadcast.TYPE_IMAGE,
            path = stagedFile.absolutePath,
            prompt = question,
            imageUri = imageUri,
            imageSource = source,
            handoffMode = ImageQuestionBroadcast.HANDOFF_AUTOINPUT_FALLBACK,
            callbackSession = session.sessionId,
            callbackToken = session.callbackToken,
            assistantMode = selectedImageAutomationTarget().label,
        )

        Toast.makeText(
            this,
            "Using Tasker AutoInput fallback for ${capability.target.label}.",
            Toast.LENGTH_LONG,
        ).show()
    }

    private fun handleExternalImageAutomationStatus() {
        val session = ExternalImageAutomationStore.current(this) ?: return
        when (session.state.stage) {
            ExternalImageAutomationStage.ANSWER_READY -> {
                if (session.followUpPromptShown || isFinishing || isDestroyed) return
                ExternalImageAutomationStore.markFollowUpPromptShown(this)
                Toast.makeText(
                    this,
                    "${selectedImageAutomationTarget().label} owns response playback.",
                    Toast.LENGTH_SHORT,
                ).show()
            }

            ExternalImageAutomationStage.FAILED -> {
                session.state.error?.takeIf { it.isNotBlank() }?.let { error ->
                    Toast.makeText(this, "Assistant automation failed: $error", Toast.LENGTH_LONG).show()
                }
            }

            else -> Unit
        }
    }

    private fun triggerAssistantImageQuery(
        imagePath: String,
        userQuestion: String? = null,
        audioPath: String? = null,
        source: ImageQuestionSource = ImageQuestionSourcePolicy.defaultSource(),
        onReplySpoken: (() -> Unit)? = null,
    ) {
        // Debounce: prevent duplicate requests within 5 seconds
        val now = System.currentTimeMillis()
        if (now - lastImageQueryAtMs < 5000) {
            audioPath?.let { File(it).delete() }
            Log.w("AIHijack", "Image query debounced (last was ${now - lastImageQueryAtMs}ms ago)")
            return
        }
        
        // Guard against concurrent requests
        if (!imageQueryInProgress.compareAndSet(false, true)) {
            audioPath?.let { File(it).delete() }
            Log.w("AIHijack", "Image query already in progress; treating duplicate action as barge-in")
            cancelLocalStreamingSpeech("duplicate image-query action")
            imageQueryInProgress.set(false)
            return
        }
        beginAiQuestionForegroundWork("Analyzing glasses image")
        
        lastImageQueryAtMs = now
        val resolvedPrompt = resolveImageQuestionPrompt(userQuestion)

        val externalReason = if (audioPath == null) externalImageAutomationUnsupportedReason() else null
        if (externalReason != null) {
            audioPath?.let { File(it).delete() }
            Toast.makeText(this, externalReason, Toast.LENGTH_LONG).show()
            if (usesExternalAssistantUi() && ExternalAssistantAutomationInspector.inspect(this).phoneLocked) {
                speak(externalReason)
            }
            imageQueryInProgress.set(false)
            finishAiQuestionForegroundWork()
            return
        }
        
        val internalProvider = if (audioPath != null) AgentProviderType.LOCAL_AGENT else when (currentAssistantRoute()) {
            GlassesAssistantRoute.LOCAL -> AgentProviderType.LOCAL_AGENT
            GlassesAssistantRoute.PRO,
            GlassesAssistantRoute.TASKER_EXTERNAL_UI -> MediaInferenceRoutingPolicy.resolve(this)
            GlassesAssistantRoute.PHONE_ASSISTANT -> null
        }
        if (internalProvider != null) {
            triggerChosenProviderImageQuery(
                imagePath = imagePath,
                providerType = internalProvider,
                resolvedPrompt = resolvedPrompt,
                audioPath = audioPath,
                onReplySpoken = onReplySpoken,
            )
            return
        }

        Log.i("AIHijack", "Handing image query to external assistant: $imagePath")
        try {
            stopGlassesAiAudio("image-query command")
            handOffImageToExternalAssistant(
                imagePath = imagePath,
                userQuestion = userQuestion,
                source = source,
            )
        } catch (error: Exception) {
            Log.e("AIHijack", "Failed to hand image to external assistant: ${error.message}", error)
            Toast.makeText(this, "Could not start the phone-assistant image question.", Toast.LENGTH_LONG).show()
        } finally {
            imageQueryInProgress.set(false)
            finishAiQuestionForegroundWork()
        }
    }

    private fun updateConnectionStatus(connected: Boolean) {
        if (isTuneBudsSelected()) {
            val tuneBuds = getOrCreateTuneBudsManager().state.value
            val storage = tuneBuds.storage?.let {
                "${it.usedMiB} MiB used / ${it.freeMiB} MiB free"
            } ?: "--"
            binding.statusText.text = tuneBuds.connectionLabel
            binding.storageText.text = storage
            updateBatteryText(tuneBuds.batteryPercent)
            updateDashboardState { state ->
                state.copy(
                    connectionLabel = tuneBuds.connectionLabel,
                    batteryPercent = tuneBuds.batteryPercent,
                    storageLabel = storage,
                )
            }
            updateDeviceClassText()
            return
        }
        if (isEyevueSelected()) {
            val eyevue = getOrCreateEyevueManager().state.value
            val status = eyevue.connectionLabel
            binding.statusText.text = status
            binding.storageText.text = eyevue.storageCount?.toString() ?: "--"
            updateBatteryText(eyevue.batteryPercent)
            updateDashboardState { state ->
                state.copy(
                    connectionLabel = status,
                    storageLabel = eyevue.storageCount?.toString() ?: "--",
                )
            }
            updateDeviceClassText()
            return
        }
        if (isMeizuMyvuSelected()) {
            val myvu = getOrCreateMeizuMyvuManager().state.value
            binding.statusText.text = myvu.connectionLabel
            updateDashboardState { state ->
                state.copy(
                    connectionLabel = myvu.connectionLabel,
                    batteryPercent = myvu.batteryPercent,
                )
            }
            updateDeviceClassText()
            return
        }
        if (isMetaRaybanSelected()) {
            val manager = getOrCreateMetaRaybanManager()
            val status = when {
                !manager.isInitialized.value -> "Meta Ray-Ban selected"
                manager.isCameraReady() -> "Meta Ray-Ban ready"
                manager.registrationState.value == com.fersaiyan.cyanbridge.devices.metarayban.MetaRaybanManager.RegistrationState.REGISTERED ->
                    "Meta Ray-Ban registered"
                else -> "Meta Ray-Ban not registered"
            }
            binding.statusText.text = status
            updateDashboardState { state -> state.copy(connectionLabel = status) }
            updateDeviceClassText()
            return
        }

        val deviceName = DeviceManager.getInstance().deviceName
        val status = if (connected) {
            if (!deviceName.isNullOrBlank()) {
                "Connected - $deviceName"
            } else {
                "Connected"
            }
        } else {
            "Disconnected"
        }
        binding.statusText.text = status
        updateDashboardState { state -> state.copy(connectionLabel = status) }
        updateDeviceClassText()
        if (connected) {
            configureHeyCyanWakeWordIfNeeded()
            refreshHeyCyanRecordingSettings(showDisconnectedMessage = false)
        } else {
            AiQuestionForegroundService.stop(this)
            wakeWordConfiguredForConnection = false
        }
        if (!connected) {
            updateBatteryText(null)
        }
    }

    private fun configureHeyCyanWakeWordIfNeeded() {
        if (wakeWordConfiguredForConnection ||
            !BleOperateManager.getInstance().isConnected ||
            DeviceProfileStore.selectedClass(this) != com.fersaiyan.cyanbridge.shared.devices.DeviceClass.HEY_CYAN
        ) {
            return
        }
        wakeWordConfiguredForConnection = true
        runCatching {
            // The official app enables the onboard "Hey Cyan" detector with this SDK call.
            LargeDataHandler.getInstance().aiVoiceWake(true, true) { _, response ->
                val enabled = response.isOpen
                wakeWordConfiguredForConnection = enabled
                Log.i("DeviceNotify", "Hey Cyan wake word enabled=$enabled")
            }
        }.onFailure { error ->
            wakeWordConfiguredForConnection = false
            Log.w("DeviceNotify", "Failed to enable Hey Cyan wake word", error)
        }
    }

    private fun refreshHeyCyanRecordingSettings(showDisconnectedMessage: Boolean = true) {
        if (!BleOperateManager.getInstance().isConnected ||
            DeviceProfileStore.selectedClass(this) != com.fersaiyan.cyanbridge.shared.devices.DeviceClass.HEY_CYAN
        ) {
            if (showDisconnectedMessage) {
                Toast.makeText(this, "Connect HeyCyan glasses to load recording limits", Toast.LENGTH_SHORT).show()
            }
            return
        }

        val handler = LargeDataHandler.getInstance()
        handler.glassesControl(byteArrayOf(0x01, 0x02)) { _, response ->
            updateHeyCyanRecordingSettings(response)
        }
        handler.glassesControl(byteArrayOf(0x01, 0x06)) { _, response ->
            updateHeyCyanRecordingSettings(response)
        }
    }

    private fun updateHeyCyanRecordingSettings(
        response: com.oudmon.ble.base.communication.bigData.resp.GlassModelControlResponse,
    ) {
        when (response.dataType) {
            2 -> runOnUiThread {
                updateDashboardState { state ->
                    state.copy(
                        videoRecordingDurationSeconds = response.videoDuration,
                        videoRecordingDurationOptionsSeconds = DEFAULT_VIDEO_DURATION_OPTIONS_SECONDS,
                    )
                }
            }
            6 -> runOnUiThread {
                updateDashboardState { state ->
                    state.copy(
                        audioRecordingDurationSeconds = response.recordAudioDuration,
                        audioRecordingDurationOptionsSeconds = AUDIO_DURATION_OPTIONS_SECONDS,
                    )
                }
            }
        }
    }

    private fun setHeyCyanRecordingDuration(isAudio: Boolean, seconds: Int) {
        val allowed = if (isAudio) {
            AUDIO_DURATION_OPTIONS_SECONDS
        } else {
            dashboardState.videoRecordingDurationOptionsSeconds.ifEmpty {
                DEFAULT_VIDEO_DURATION_OPTIONS_SECONDS
            }
        }
        if (seconds !in allowed) return
        if (!BleOperateManager.getInstance().isConnected ||
            DeviceProfileStore.selectedClass(this) != com.fersaiyan.cyanbridge.shared.devices.DeviceClass.HEY_CYAN
        ) {
            Toast.makeText(this, "Connect HeyCyan glasses first", Toast.LENGTH_SHORT).show()
            return
        }

        val dataType = if (isAudio) 0x06 else 0x02
        val command = byteArrayOf(
            0x02,
            dataType.toByte(),
            0x00,
            ByteUtil.loword(seconds).toByte(),
            ByteUtil.hiword(seconds).toByte(),
        )
        LargeDataHandler.getInstance().glassesControl(command) { _, response ->
            Log.i(
                "Dashboard",
                "Set ${if (isAudio) "audio" else "video"} duration=$seconds responseType=${response.dataType}",
            )
        }
        updateDashboardState { state ->
            if (isAudio) {
                state.copy(audioRecordingDurationSeconds = seconds)
            } else {
                state.copy(videoRecordingDurationSeconds = seconds)
            }
        }
    }

    private fun prepareAiQuestionForLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
    }

    private fun isMetaRaybanSelected(): Boolean = DeviceProfileStore.isMetaSelected(this)

    private fun isMeizuMyvuSelected(): Boolean = DeviceProfileStore.isMeizuMyvuSelected(this)

    private fun isEyevueSelected(): Boolean =
        DeviceProfileStore.selectedClass(this) == com.fersaiyan.cyanbridge.shared.devices.DeviceClass.EYEVUE

    private fun isTuneBudsSelected(): Boolean =
        DeviceProfileStore.selectedClass(this) == com.fersaiyan.cyanbridge.shared.devices.DeviceClass.TUNEBUDS

    private fun isMoyoungW620Selected(): Boolean = DeviceProfileStore.isMoyoungW620Selected(this)

    private fun isHeyCyanSelected(): Boolean =
        DeviceProfileStore.selectedClass(this) == com.fersaiyan.cyanbridge.shared.devices.DeviceClass.HEY_CYAN

    private fun isHeyCyanOrEyevueSelected(): Boolean =
        DeviceProfileStore.selectedClass(this) in setOf(
            com.fersaiyan.cyanbridge.shared.devices.DeviceClass.HEY_CYAN,
            com.fersaiyan.cyanbridge.shared.devices.DeviceClass.EYEVUE,
        )

    private fun rejectHeyCyanOnlyFeature(feature: String): Boolean {
        if (!isMetaRaybanSelected()) return false
        Toast.makeText(
            this,
            "$feature is unavailable for Meta Ray-Ban. Use the DAT controls instead.",
            Toast.LENGTH_LONG,
        ).show()
        return true
    }

    private fun updateDeviceClassText() {
        val profile = DeviceProfileStore.loadLastSelected(this)
        val classLabel = profile?.selectedClass?.displayName() ?: "Unknown"
        binding.tvDeviceClass.text = "Class: $classLabel"
        updateDashboardState { state -> state.copy(deviceClassLabel = classLabel) }

        applyGlassesManagerGating(profile)
    }

    /** Applies device-profile capabilities to both the legacy controls and shared dashboard. */
    private fun applyGlassesManagerGating(profile: DeviceProfile?) {
        val model = GlassesManagerGating.uiModel(profile)

        // The hidden legacy panel remains HeyCyan-specific; Compose uses granular flags below.
        binding.layoutHeycyanExtras.visibility =
            if (model.isVisible(GlassesManagerGating.Action.HEY_CYAN_EXTRAS)) android.view.View.VISIBLE else android.view.View.GONE

        // Meta Ray-Ban controls panel
        binding.layoutMetaRayban.visibility =
            if (model.isVisible(GlassesManagerGating.Action.META_RAYBAN_CONTROLS)) android.view.View.VISIBLE else android.view.View.GONE

        // Status placeholders
        val showBattery = model.isVisible(GlassesManagerGating.Action.STATUS_BATTERY)
        val showStorage = model.isVisible(GlassesManagerGating.Action.STATUS_STORAGE)

        binding.layoutBattery.visibility = if (showBattery) android.view.View.VISIBLE else android.view.View.GONE
        binding.layoutStorage.visibility = if (showStorage) android.view.View.VISIBLE else android.view.View.GONE
        binding.layoutStatusMetrics.visibility =
            if (showBattery || showStorage) android.view.View.VISIBLE else android.view.View.GONE
        updateDashboardState { state ->
            state.copy(
                showHeyCyanControls = model.isVisible(GlassesManagerGating.Action.HEY_CYAN_EXTRAS),
                showEyevueControls = model.isVisible(GlassesManagerGating.Action.EYEVUE_CONTROLS),
                showTuneBudsControls = model.isVisible(GlassesManagerGating.Action.TUNEBUDS_CONTROLS),
                showCaptureSettings = model.isVisible(GlassesManagerGating.Action.CAPTURE_SETTINGS),
                showMediaSync = true,
                showAiWakeWordRouting = model.isVisible(GlassesManagerGating.Action.AI_WAKE_WORD_ROUTING),
                showAdvancedControls = model.isVisible(GlassesManagerGating.Action.ADVANCED_CONTROLS),
                showAdvancedLocalAgent = model.isVisible(GlassesManagerGating.Action.ADVANCED_LOCAL_AGENT),
                showAdvancedDeviceInfo = model.isVisible(GlassesManagerGating.Action.ADVANCED_DEVICE_INFO),
                showAdvancedDeviceVolume = model.isVisible(GlassesManagerGating.Action.ADVANCED_DEVICE_VOLUME),
                showAdvancedImageQuality = model.isVisible(GlassesManagerGating.Action.ADVANCED_IMAGE_QUALITY),
                showAdvancedDeveloperTools = model.isVisible(GlassesManagerGating.Action.ADVANCED_DEVELOPER_TOOLS),
                showAdvancedOta = model.isVisible(GlassesManagerGating.Action.ADVANCED_OTA),
                deviceInfoLabel = state.deviceInfoLabel.takeIf {
                    model.isVisible(GlassesManagerGating.Action.TUNEBUDS_CONTROLS)
                },
                firmwarePatchRequest = state.firmwarePatchRequest.takeIf {
                    model.isVisible(GlassesManagerGating.Action.ADVANCED_OTA)
                },
                aiWakeWordRoute = AiWakeWordPreferences.route(this),
                showMetaRaybanControls = model.isVisible(GlassesManagerGating.Action.META_RAYBAN_CONTROLS),
                showMeizuMyvuControls = model.isVisible(GlassesManagerGating.Action.MEIZU_MYVU_CONTROLS),
                showBattery = showBattery,
                showStorage = showStorage,
                storageLabel = if (showStorage) state.storageLabel else "--",
                wifiAdbDebug = state.wifiAdbDebug.copy(
                    isAvailable = BuildConfig.DEBUG &&
                        model.isVisible(GlassesManagerGating.Action.WIFI_ADB_DEBUG),
                ),
                livePreview = when {
                    model.isVisible(GlassesManagerGating.Action.HEY_CYAN_EXTRAS) ->
                        livePreviewUiState(livePreviewManager.uiState.value, isAvailable = true)
                    model.isVisible(GlassesManagerGating.Action.EYEVUE_CONTROLS) ->
                        livePreviewUiState(eyevueLivePreviewManager?.uiState?.value,
                            isAvailable = EyevueLivePreviewPolicy.isSupported(Build.VERSION.SDK_INT))
                    model.isVisible(GlassesManagerGating.Action.TUNEBUDS_CONTROLS) ->
                        livePreviewUiState(tuneBudsLivePreviewManager?.uiState?.value,
                            isAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    else -> com.fersaiyan.cyanbridge.shared.glasses.LivePreviewUiState()
                },
            )
        }

        // Only poll battery for profiles that claim to support it.
        if (showBattery &&
            DeviceProfileStore.selectedClass(this) == com.fersaiyan.cyanbridge.shared.devices.DeviceClass.HEY_CYAN
        ) {
            startBatteryPolling()
        } else {
            stopBatteryPolling()
            updateBatteryText(null)
            if (isEyevueSelected()) getOrCreateEyevueManager().requestBattery()
            if (isTuneBudsSelected()) {
                getOrCreateTuneBudsManager().takeIf { it.isConnected() }?.requestBattery()
            }
            if (isMoyoungW620Selected()) {
                getOrCreateMoyoungW620Manager().takeIf { it.isConnected() }?.requestBattery()
            }
        }

        if (!showStorage) {
            binding.storageText.text = "--"
        }

        // Initialize Meta Ray-Ban manager if needed
        if (model.isVisible(GlassesManagerGating.Action.META_RAYBAN_CONTROLS)) {
            val manager = getOrCreateMetaRaybanManager()
            if (metaAndroidPermissionsMissing().isEmpty()) {
                manager.initialize()
            }
            updateMetaRaybanUiState()
        }

        if (model.isVisible(GlassesManagerGating.Action.MEIZU_MYVU_CONTROLS)) {
            getOrCreateMeizuMyvuManager()
            updateMeizuMyvuUiState()
        }

        if (model.isVisible(GlassesManagerGating.Action.EYEVUE_CONTROLS)) {
            getOrCreateEyevueLivePreviewManager()
        }

        if (model.isVisible(GlassesManagerGating.Action.TUNEBUDS_CONTROLS)) {
            getOrCreateTuneBudsLivePreviewManager()
            if (GlassesSessionCoordinator.currentSession() == null) {
                getOrCreateTuneBudsManager().takeIf { it.isConnected() }?.refreshStatus()
            }
        }
        if (isMoyoungW620Selected()) {
            getOrCreateMoyoungW620Manager().takeIf { it.isConnected() }?.requestMediaCount()
        }
    }

    private fun livePreviewUiState(
        state: com.fersaiyan.cyanbridge.ota.LivePreviewState?,
        isAvailable: Boolean,
    ): com.fersaiyan.cyanbridge.shared.glasses.LivePreviewUiState {
        val live = state ?: com.fersaiyan.cyanbridge.ota.LivePreviewState()
        return com.fersaiyan.cyanbridge.shared.glasses.LivePreviewUiState(
            isAvailable = isAvailable, stateLabel = live.stateLabel, detail = live.detail,
            isScanning = live.isScanning, isPlaying = live.isPlaying, streamUrl = live.streamUrl,
            canStart = live.canStart, canStop = live.canStop,
        )
    }

    // --- Chapter 5: Meeting capture pipeline (start/stop, timer, indicator) ---

    private fun setupMeetingCaptureUi() {
        val labels = meetingTimerOptions.map { it.second }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        binding.spinnerMeetingTimer.adapter = adapter
        binding.spinnerMeetingTimer.setSelection(dashboardState.meeting.timerIndex)
        syncMeetingCaptureUiFromPrefs()
    }

    private fun setupAgentControlsUi() {
        binding.btnAgentStart.text = "Agent status"
        binding.btnAgentStart.setOnClickListener {
            Toast.makeText(this, "Enter a goal before starting the agent.", Toast.LENGTH_SHORT).show()
            LocalAgentController.requestStatus(this)
        }

        binding.btnAgentStop.setOnClickListener {
            val res = LocalAgentController.stop(this)
            if (res.ok) {
                LocalAgentPrefs.setStatus(this, "Stopping…")
                LocalAgentPrefs.clearLastError(this)
            } else {
                LocalAgentPrefs.setStatus(this, "Error")
                LocalAgentPrefs.setLastError(this, res.error ?: res.userMessage)
            }
            refreshAgentStatusUi()
            Toast.makeText(this, res.userMessage, Toast.LENGTH_SHORT).show()
            LocalAgentController.requestStatus(this)
        }

        binding.btnAgentDemo.setOnClickListener {
            Toast.makeText(
                this,
                "Demo: I will read the screen content through your glasses in 5 seconds…",
                Toast.LENGTH_LONG
            ).show()

            val res = LocalAgentController.demo(this)
            if (res.ok) {
                LocalAgentPrefs.setStatus(this, "Running demo…")
                LocalAgentPrefs.clearLastError(this)
            } else {
                LocalAgentPrefs.setStatus(this, "Error")
                LocalAgentPrefs.setLastError(this, res.error ?: res.userMessage)
            }
            refreshAgentStatusUi()
            Toast.makeText(this, res.userMessage, Toast.LENGTH_SHORT).show()
            LocalAgentController.requestStatus(this)
        }

        refreshAgentStatusUi()
    }

    private fun refreshAgentStatusUi() {
        binding.tvAgentStatus.text = "Status: ${LocalAgentPrefs.getStatus(this)}"
        binding.tvAgentLastError.text = "Last error: ${LocalAgentPrefs.getLastError(this)}"
        updateDashboardState { state ->
            state.copy(
                agentStatus = LocalAgentPrefs.getStatus(this),
                agentLastError = LocalAgentPrefs.getLastError(this),
            )
        }
    }

    // --- Meta Ray-Ban UI setup ---

    private fun setupMetaRaybanUi() {
        // Registration buttons
        binding.btnMetaRegister.setOnClickListener {
            ensureMetaDatReady {
                getOrCreateMetaRaybanManager().startRegistration(this)
            }
        }

        binding.btnMetaUnregister.setOnClickListener {
            ensureMetaDatReady {
                getOrCreateMetaRaybanManager().startUnregistration(this)
            }
        }

        // Session buttons
        binding.btnMetaSessionStart.setOnClickListener {
            ensureMetaDatReady {
                getOrCreateMetaRaybanManager().startSession(
                    onSuccess = {
                        runOnUiThread {
                            Toast.makeText(this, "Meta session started", Toast.LENGTH_SHORT).show()
                            updateMetaRaybanUiState()
                        }
                    },
                    onError = { error ->
                        runOnUiThread {
                            showMetaError("DAT session", error)
                        }
                    },
                )
            }
        }

        binding.btnMetaSessionStop.setOnClickListener {
            metaRaybanManager?.stopSession()
            updateMetaRaybanUiState()
        }

        // Streaming buttons
        binding.btnMetaStreamStart.setOnClickListener {
            ensureMetaCameraReady {
                getOrCreateMetaRaybanManager().startStreaming(
                    onFrame = { bitmap ->
                        Log.d(TAG, "Received Meta video frame: ${bitmap.width}x${bitmap.height}")
                    },
                    onSuccess = {
                        runOnUiThread {
                            Toast.makeText(this, "Streaming started", Toast.LENGTH_SHORT).show()
                            updateMetaRaybanUiState()
                        }
                    },
                    onError = { error ->
                        runOnUiThread {
                            showMetaError("DAT stream", error)
                        }
                    },
                )
            }
        }

        binding.btnMetaStreamStop.setOnClickListener {
            metaRaybanManager?.stopStreaming()
            updateMetaRaybanUiState()
        }

        // Photo capture button
        binding.btnMetaCapturePhoto.setOnClickListener {
            metaRaybanManager?.capturePhoto(
                onSuccess = { photoData ->
                    runOnUiThread {
                        Toast.makeText(this, "Photo captured!", Toast.LENGTH_SHORT).show()
                        binding.btnMetaViewPhoto.isEnabled = true
                        updateDashboardState { state ->
                            state.copy(metaRayban = state.metaRayban.copy(hasCapturedPhoto = true))
                        }
                    }
                },
                onError = { error ->
                    runOnUiThread {
                        showMetaError("DAT photo capture", error)
                    }
                }
            )
        }

        // View last photo button
        binding.btnMetaViewPhoto.setOnClickListener {
            val photo = metaRaybanManager?.lastCapturedPhoto?.value
            val uri = photo?.uri
            if (uri != null) {
                runCatching {
                    startActivity(
                        Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, photo.mimeType)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        },
                    )
                }.onFailure {
                    Toast.makeText(this, "No photo viewer is installed", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "No captured Meta photo is available", Toast.LENGTH_SHORT).show()
            }
        }

        // Display buttons
        binding.btnMetaDisplayStart.setOnClickListener {
            ensureMetaDatReady {
                getOrCreateMetaRaybanManager().startDisplay(
                    onSuccess = {
                        runOnUiThread {
                            Toast.makeText(this, "Display started", Toast.LENGTH_SHORT).show()
                            updateMetaRaybanUiState()
                        }
                    },
                    onError = { error ->
                        runOnUiThread {
                            showMetaError("DAT display", error)
                        }
                    }
                )
            }
        }

        binding.btnMetaDisplayStop.setOnClickListener {
            metaRaybanManager?.stopDisplay()
            updateMetaRaybanUiState()
        }
    }

    private fun captureMetaPhotoForGallery() {
        ensureMetaCameraReady {
            val manager = getOrCreateMetaRaybanManager()
            lifecycleScope.launch(Dispatchers.IO) {
                runCatching { manager.capturePhotoOnce() }
                    .onSuccess {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@MainActivity, "Meta photo captured", Toast.LENGTH_SHORT).show()
                            updateMetaRaybanUiState()
                        }
                    }
                    .onFailure { error ->
                        withContext(Dispatchers.Main) {
                            showMetaError(
                                "DAT background photo",
                                error.message ?: "camera unavailable",
                            )
                        }
                    }
            }
        }
    }

    private fun showMetaDiagnostics() {
        val manager = getOrCreateMetaRaybanManager()
        DebugLogSupport.showSupportOptionsDialog(
            activity = this,
            title = getString(R.string.meta_diagnostics_title),
            issueType = "Meta Ray-Ban / DAT",
            description = getString(R.string.meta_diagnostics_description),
            extraInfo = linkedMapOf(
                "Meta DAT snapshot" to manager.diagnosticsSnapshot(),
            ),
            dismissButtonLabel = getString(R.string.action_cancel),
        )
    }

    private fun openMetaAiAppOrStore() {
        val manager = getOrCreateMetaRaybanManager()
        val launchIntent = manager.installedMetaAiPackageName()
            ?.let(packageManager::getLaunchIntentForPackage)
        if (launchIntent != null) {
            startActivity(launchIntent)
            return
        }
        runCatching {
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.facebook.stella"))
                    .setPackage("com.android.vending"),
            )
        }.recoverCatching {
            startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/apps/details?id=com.facebook.stella"),
                ),
            )
        }.onFailure {
            Toast.makeText(this, "Could not open the Meta AI download page", Toast.LENGTH_LONG).show()
        }
    }

    private fun showMetaError(operation: String, message: String) {
        enabledMetaCameraCheckActive = false
        metaCameraReadinessInProgress = false
        val manager = getOrCreateMetaRaybanManager()
        val detail = message
        Log.e(
            "MainActivity",
            "$operation failed: $detail\n${manager.diagnosticsSnapshot()}",
        )
        updateMetaRaybanUiState()
        if (!isFinishing && !isDestroyed) {
            AlertDialog.Builder(this)
                .setTitle("Complete Meta glasses setup")
                .setMessage("$operation: $detail\n\nOpen Meta setup for the next step: Android permissions, Meta AI connection, or glasses camera authorization.")
                .setPositiveButton("Open Meta setup") { _, _ -> startActivity(Intent(this, MetaPairingActivity::class.java)) }
                .setNegativeButton("Close", null)
                .show()
        }
    }

    private fun updateMetaRaybanUiState() {
        val manager = metaRaybanManager ?: return
        manager.refreshRegistrationState()

        // Update registration status
        val regState = manager.registrationState.value
        binding.tvMetaRegistrationStatus.text = "Registration: ${regState.name}"

        // Update session state
        val sessionState = manager.deviceSessionState.value
        binding.tvMetaSessionState.text = "Session: ${sessionState.name}"

        // Update stream state
        val streamState = manager.streamState.value
        binding.tvMetaStreamState.text = "Stream: ${streamState.name}"

        // Update display state
        val isDisplayActive = manager.isDisplayActive.value
        val displayCapable = manager.selectedDeviceIsDisplayCapable.value
        binding.layoutMetaDisplay.visibility = if (displayCapable) View.VISIBLE else View.GONE
        binding.tvMetaDisplayState.text = "Display: ${if (isDisplayActive) "Active" else "Inactive"}"

        // Enable/disable buttons based on state
        binding.btnMetaRegister.isEnabled = regState != com.fersaiyan.cyanbridge.devices.metarayban.MetaRaybanManager.RegistrationState.REGISTERED &&
            regState != com.fersaiyan.cyanbridge.devices.metarayban.MetaRaybanManager.RegistrationState.REGISTERING
        binding.btnMetaUnregister.isEnabled = regState == com.fersaiyan.cyanbridge.devices.metarayban.MetaRaybanManager.RegistrationState.REGISTERED

        binding.btnMetaSessionStart.isEnabled = regState == com.fersaiyan.cyanbridge.devices.metarayban.MetaRaybanManager.RegistrationState.REGISTERED &&
            manager.availableDeviceCount.value > 0 &&
            sessionState == com.fersaiyan.cyanbridge.devices.metarayban.MetaRaybanManager.DeviceSessionState.IDLE
        binding.btnMetaSessionStop.isEnabled = sessionState != com.fersaiyan.cyanbridge.devices.metarayban.MetaRaybanManager.DeviceSessionState.IDLE

        binding.btnMetaStreamStart.isEnabled = sessionState == com.fersaiyan.cyanbridge.devices.metarayban.MetaRaybanManager.DeviceSessionState.STARTED &&
            streamState == com.fersaiyan.cyanbridge.devices.metarayban.MetaRaybanManager.StreamState.STOPPED
        binding.btnMetaStreamStop.isEnabled = streamState == com.fersaiyan.cyanbridge.devices.metarayban.MetaRaybanManager.StreamState.STREAMING

        binding.btnMetaCapturePhoto.isEnabled = streamState == com.fersaiyan.cyanbridge.devices.metarayban.MetaRaybanManager.StreamState.STREAMING

        binding.btnMetaViewPhoto.isEnabled = manager.lastCapturedPhoto.value != null
        binding.btnMetaDisplayStart.isEnabled = displayCapable && sessionState == com.fersaiyan.cyanbridge.devices.metarayban.MetaRaybanManager.DeviceSessionState.STARTED && !isDisplayActive
        binding.btnMetaDisplayStop.isEnabled = displayCapable && isDisplayActive
        updateDashboardState { state ->
            state.copy(
                metaRayban = MetaRaybanUiState(
                    registrationLabel = regState.name,
                    sessionLabel = sessionState.name,
                    streamLabel = streamState.name,
                    selectedDeviceName = manager.selectedDeviceName.value,
                    availableDeviceCount = manager.availableDeviceCount.value,
                    setupGuidance = manager.registrationGuidance(),
                    lastError = manager.lastError.value,
                    metaAiInstalled = manager.isMetaAiInstalled(),
                    displayCapable = displayCapable,
                    displayActive = isDisplayActive,
                    canRegister = regState != com.fersaiyan.cyanbridge.devices.metarayban.MetaRaybanManager.RegistrationState.REGISTERED &&
                        regState != com.fersaiyan.cyanbridge.devices.metarayban.MetaRaybanManager.RegistrationState.REGISTERING,
                    canUnregister = regState == com.fersaiyan.cyanbridge.devices.metarayban.MetaRaybanManager.RegistrationState.REGISTERED,
                    canStartSession = regState == com.fersaiyan.cyanbridge.devices.metarayban.MetaRaybanManager.RegistrationState.REGISTERED &&
                        manager.availableDeviceCount.value > 0 &&
                        sessionState == com.fersaiyan.cyanbridge.devices.metarayban.MetaRaybanManager.DeviceSessionState.IDLE,
                    canStopSession = sessionState != com.fersaiyan.cyanbridge.devices.metarayban.MetaRaybanManager.DeviceSessionState.IDLE,
                    canStartStream = sessionState == com.fersaiyan.cyanbridge.devices.metarayban.MetaRaybanManager.DeviceSessionState.STARTED &&
                        streamState == com.fersaiyan.cyanbridge.devices.metarayban.MetaRaybanManager.StreamState.STOPPED,
                    canStopStream = streamState == com.fersaiyan.cyanbridge.devices.metarayban.MetaRaybanManager.StreamState.STREAMING,
                    canCapturePhoto = streamState == com.fersaiyan.cyanbridge.devices.metarayban.MetaRaybanManager.StreamState.STREAMING,
                    hasCapturedPhoto = manager.lastCapturedPhoto.value != null,
                ),
            )
        }
    }

    private fun updateMeizuMyvuUiState() {
        val manager = meizuMyvuManager ?: return
        val myvu = manager.state.value
        val connected = myvu.relayReady
        updateDashboardState { state ->
            state.copy(
                connectionLabel = myvu.connectionLabel,
                batteryPercent = myvu.batteryPercent,
                meizuMyvu = MeizuMyvuUiState(
                    connectionLabel = myvu.connectionLabel,
                    protocolState = myvu.protocolState,
                    deviceName = myvu.deviceName,
                    batteryPercent = myvu.batteryPercent,
                    lastError = myvu.lastError,
                    canConnect = !connected && myvu.selectedAddress != null,
                    canDisconnect = myvu.protocolState != com.myvu.client.service.ConnectionState.IDLE.name,
                    canSend = connected,
                ),
            )
        }
    }

    private fun selectedMeetingTimerDurationSec(): Long? { 
        val idx = dashboardState.meeting.timerIndex
        return meetingTimerOptions.getOrNull(idx)?.first
    }

    private fun requestMeetingCapturePermissions(onGranted: () -> Unit) {
        val perms = mutableListOf<String>(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        XXPermissions.with(this)
            .permission(perms)
            .request(object : OnPermissionCallback {
                override fun onGranted(permissions: MutableList<String>, all: Boolean) {
                    if (all) onGranted() else {
                        Toast.makeText(this@MainActivity, "Missing permissions for recording", Toast.LENGTH_SHORT).show()
                    }
                }

                override fun onDenied(permissions: MutableList<String>, never: Boolean) {
                    super.onDenied(permissions, never)
                    Toast.makeText(this@MainActivity, "Recording permission denied", Toast.LENGTH_SHORT).show()
                    if (never) {
                        XXPermissions.startPermissionActivity(this@MainActivity, permissions)
                    }
                }
            })
    }

    private fun startMeetingCaptureFromUi() {
        requestMeetingCapturePermissions {
            val deviceClass = DeviceProfileStore.loadLastSelected(this)?.selectedClass?.name ?: "UNKNOWN"
            val durationSec = selectedMeetingTimerDurationSec()

            // Optimistic UI so user instantly sees a recording indicator.
            setRecordingUi(isRecording = true, source = null)
            binding.tvMeetingBanner.text = "Starting recording…"
            updateDashboardState { state ->
                state.copy(meeting = state.meeting.copy(bannerLabel = "Starting recording…"))
            }

            MeetingCaptureService.start(this, timerDurationSec = durationSec, deviceClass = deviceClass)
        }
    }

    private fun stopMeetingCaptureFromUi() {
        binding.tvMeetingBanner.text = "Stopping…"
        updateDashboardState { state ->
            state.copy(meeting = state.meeting.copy(bannerLabel = "Stopping…"))
        }
        MeetingCaptureService.stop(this)
    }

    private fun registerMeetingCaptureReceiver() {
        if (meetingCaptureStateReceiver != null) return

        meetingCaptureStateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != MeetingCaptureService.ACTION_STATE) return

                val isRecording = intent.getBooleanExtra(MeetingCaptureService.EXTRA_IS_RECORDING, false)
                val source = intent.getStringExtra(MeetingCaptureService.EXTRA_SOURCE)?.let {
                    runCatching { CaptureSource.valueOf(it) }.getOrNull()
                }
                val stopReason = intent.getStringExtra(MeetingCaptureService.EXTRA_STOP_REASON)
                val error = intent.getStringExtra(MeetingCaptureService.EXTRA_ERROR)

                setRecordingUi(isRecording = isRecording, source = source)

                if (!isRecording && stopReason == "timer") {
                    Toast.makeText(this@MainActivity, "Meeting capture auto-stopped (timer)", Toast.LENGTH_SHORT).show()
                }
                if (!error.isNullOrBlank()) {
                    Toast.makeText(this@MainActivity, "Recording error: $error", Toast.LENGTH_LONG).show()
                }
                refreshNativePluginShortcutState()
            }
        }

        LocalBroadcastManager.getInstance(this)
            .registerReceiver(meetingCaptureStateReceiver!!, IntentFilter(MeetingCaptureService.ACTION_STATE))
    }

    private fun unregisterMeetingCaptureReceiver() {
        val r = meetingCaptureStateReceiver ?: return
        meetingCaptureStateReceiver = null
        runCatching {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(r)
        }
    }

    private fun syncMeetingCaptureUiFromPrefs() {
        val state = MeetingCapturePrefs.getState(this)
        setRecordingUi(isRecording = state.isRecording, source = state.source)
    }

    private fun setRecordingUi(isRecording: Boolean, source: CaptureSource?) {
        binding.btnMeetingStart.isEnabled = !isRecording
        binding.btnMeetingStop.isEnabled = isRecording
        binding.btnMeetingBannerStop.isEnabled = isRecording

        if (isRecording) {
            binding.meetingRecordingBanner.visibility = android.view.View.VISIBLE
            val src = when (source) {
                CaptureSource.BLUETOOTH_MIC -> "Bluetooth mic"
                CaptureSource.PHONE_MIC -> "Phone mic"
                null -> "(detecting…)"
            }
            binding.tvMeetingBanner.text = "Recording active · $src"
            binding.tvMeetingSource.text = "Source: $src"
            updateDashboardState { state ->
                state.copy(
                    meeting = state.meeting.copy(
                        isRecording = true,
                        sourceLabel = src,
                        bannerLabel = "Recording active · $src",
                    ),
                )
            }
        } else {
            binding.meetingRecordingBanner.visibility = android.view.View.GONE
            binding.tvMeetingSource.text = "Source: (not recording)"
            updateDashboardState { state ->
                state.copy(
                    meeting = state.meeting.copy(
                        isRecording = false,
                        sourceLabel = "(not recording)",
                        bannerLabel = "",
                    ),
                )
            }
        }
    }

    // --- end Chapter 5 meeting capture ---

    // --- Transcription UI moved to RecordingsListActivity (per-item) ---



    private fun updateBatteryText(battery: Int?) {
        binding.batteryText.text = battery?.let { "$it%" } ?: "--%"
        updateDashboardState { state -> state.copy(batteryPercent = battery) }
    }

    private fun requestBatteryStatus(showToast: Boolean) {
        if (rejectHeyCyanOnlyFeature("Battery status")) return
        if (isGlassesCommandBlocked("battery status request")) return
        if (showToast) {
            pendingBatteryToast = true
            Toast.makeText(this@MainActivity, "Requesting battery level…", Toast.LENGTH_SHORT).show()
        }
        ensureBatteryCallback()
        // Trigger battery sync
        LargeDataHandler.getInstance().syncBattery()
    }

    private fun ensureBatteryCallback() {
        if (batteryCallbackRegistered) {
            return
        }
        batteryCallbackRegistered = true
        // Add battery listener. According to the SDK docs this
        // callback is invoked when syncBattery completes.
        LargeDataHandler.getInstance().addBatteryCallBack("init") { _, response ->
            val result = parseBatteryResponse(response)
            Log.i("BatteryCallback", result.message)
            runOnUiThread {
                updateBatteryText(result.battery)
                if (pendingBatteryToast) {
                    Toast.makeText(
                        this@MainActivity,
                        result.message,
                        Toast.LENGTH_LONG
                    ).show()
                    pendingBatteryToast = false
                }
            }
        }
    }

    private data class BatteryResult(
        val battery: Int?,
        val charging: Boolean?,
        val message: String
    )

    private fun parseBatteryResponse(response: Any?): BatteryResult {
        if (response == null) {
            return BatteryResult(null, null, "Battery callback: null response")
        }
        return try {
            val clazz = response.javaClass
            val batteryField = clazz.getDeclaredField("battery").apply {
                isAccessible = true
            }
            val chargingField = clazz.getDeclaredField("charging").apply {
                isAccessible = true
            }

            val battery = batteryField.getInt(response)
            val charging = chargingField.getBoolean(response)
            val message =
                "Battery: $battery% (${if (charging) "charging" else "not charging"})"
            BatteryResult(battery, charging, message)
        } catch (e: Exception) {
            Log.e("BatteryCallback", "Failed to parse BatteryResponse", e)
            BatteryResult(null, null, "Battery: $response")
        }
    }

    private fun handleBatteryReport(battery: Int, charging: Boolean) {
        val message = "Battery: $battery% (${if (charging) "charging" else "not charging"})"
        Log.i("BatteryCallback", message)
        runOnUiThread {
            updateBatteryText(battery)
            if (pendingBatteryToast) {
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                pendingBatteryToast = false
            }
        }
    }

    private fun handleTaskerCommand(startIntent: Intent?) {
        if (startIntent == null) return

        val isFromTaskerAction = startIntent.action == actionTaskerCommand(packageName)
        val command = startIntent.getStringExtra(EXTRA_TASKER_COMMAND)

        if (!isFromTaskerAction && command.isNullOrBlank()) {
            return
        }

        val normalizedCommand = command?.lowercase() ?: return
        val activeSession = GlassesSessionCoordinator.currentSession()
        if (activeSession != null) {
            Log.w(
                "GlassesSession",
                "Ignoring Tasker command '$normalizedCommand'; ${activeSession.label} owns the SDK BLE/P2P slots",
            )
            Toast.makeText(
                this,
                "${activeSession.label.replaceFirstChar { it.uppercase() }} is using the glasses connection.",
                Toast.LENGTH_SHORT,
            ).show()
            return
        }

        when (normalizedCommand) {
            "scan" -> binding.btnScan.performClick()
            "connect" -> binding.btnConnect.performClick()
            "disconnect" -> binding.btnDisconnect.performClick()
            "add_listener" -> binding.btnAddListener.performClick()
            "set_time" -> binding.btnSetTime.performClick()
            "version" -> binding.btnVersion.performClick()
            "camera" -> binding.btnCamera.performClick()

            // Video recording controls
            "video" -> binding.btnVideo.performClick()
            "video_start" -> controlVideoRecording(true)
            "video_stop" -> controlVideoRecording(false)

            // Audio recording controls
            "record" -> binding.btnRecord.performClick()
            "record_start" -> controlAudioRecording(true)
            "record_stop" -> controlAudioRecording(false)

            "bt_scan" -> binding.btnBt.performClick()
            "battery" -> binding.btnBattery.performClick()
            "volume" -> binding.btnVolume.performClick()
            "media_count" -> binding.btnMediaCount.performClick()
            "data_download" -> binding.btnDataDownload.performClick()
        }
    }

    private fun showDownloadFlowPicker() {
        showDownloadFlowPicker = true
    }

    private fun showOfficialHeyCyanWarningOrContinue() {
        val selectedClass = DeviceProfileStore.selectedClass(this)
        if (!OfficialHeyCyanApp.shouldWarn(this, selectedClass)) {
            showDownloadFlowPicker()
            return
        }
        showOfficialHeyCyanWarningDialog = true
    }

    private val mediaSyncHost = object : GlassesMediaSyncHost, VendorMediaSyncHost {
        override var mediaSessionLease: GlassesSessionLease?
            get() = this@MainActivity.mediaSessionLease
            set(value) {
                this@MainActivity.mediaSessionLease = value
            }
        override val pendingImageCaptureStartedAtMs: Long
            get() = this@MainActivity.pendingImageCaptureStartedAtMs
        override var pendingImageQuestionSource: ImageQuestionSource
            get() = this@MainActivity.pendingImageQuestionSource
            set(value) {
                this@MainActivity.pendingImageQuestionSource = value
            }

        override fun ensureGlassesTransportPermissions(feature: String, onGranted: () -> Unit) =
            this@MainActivity.ensureGlassesTransportPermissions(feature, onGranted)

        override fun acquireExclusiveGlassesSession(session: GlassesSession): GlassesSessionLease? =
            this@MainActivity.acquireExclusiveGlassesSession(session)

        override fun releaseExclusiveGlassesSession(lease: GlassesSessionLease?) =
            this@MainActivity.releaseExclusiveGlassesSession(lease)

        override fun getOrCreateEyevueManager(): EyevueManager = this@MainActivity.getOrCreateEyevueManager()

        override fun getOrCreateTuneBudsManager(): TuneBudsManager = this@MainActivity.getOrCreateTuneBudsManager()

        override fun getOrCreateMoyoungW620Manager(): MoyoungW620Manager =
            this@MainActivity.getOrCreateMoyoungW620Manager()

        override fun isTuneBudsAiPhotoInProgress(): Boolean = tuneBudsAiPhotoInProgress.get()

        override fun isImageQuestionWorkActive(): Boolean = this@MainActivity.isImageQuestionWorkActive()

        override fun rejectHeyCyanOnlyFeature(feature: String): Boolean =
            this@MainActivity.rejectHeyCyanOnlyFeature(feature)

        override fun dashboardTransferState(): GlassesTransferUiState = dashboardState.transfer

        override fun isOtaActive(): Boolean = otaManager.isActive

        override fun onImageReadyForQuestion(
            imagePath: String,
            source: ImageQuestionSource,
            transferDurationMs: Long,
        ) = this@MainActivity.onImageReadyForQuestion(
            imagePath = imagePath,
            source = source,
            transferDurationMs = transferDurationMs,
        )

        override fun cancelParallelAudioQuestion() = this@MainActivity.cancelParallelAudioQuestion()

        override fun requestImageThumbnailForQuestion(sourceTag: String) =
            this@MainActivity.requestImageThumbnailForQuestion(sourceTag)

        override fun clearPendingVoiceImageQuestion(sourceTag: String) =
            this@MainActivity.clearPendingVoiceImageQuestion(sourceTag)

        override fun isDecodableImageFile(file: File): Boolean = this@MainActivity.isDecodableImageFile(file)
    }

    private fun requestHighQualityImageForQuestion(sourceTag: String) {
        mediaSync.requestHighQualityImageForQuestion(sourceTag)
    }

    private fun startEyevueMediaSync() {
        vendorMediaSync.startEyevueMediaSync()
    }

    private fun stopEyevueMediaSync() {
        vendorMediaSync.stopEyevueMediaSync()
    }

    private fun startTuneBudsMediaSync() {
        vendorMediaSync.startTuneBudsMediaSync()
    }

    private fun stopTuneBudsMediaSync() {
        vendorMediaSync.stopTuneBudsMediaSync()
    }

    private fun startMoyoungW620MediaSync() {
        vendorMediaSync.startMoyoungW620MediaSync()
    }

    private fun stopMoyoungW620MediaSync() {
        vendorMediaSync.stopMoyoungW620MediaSync()
    }

    private fun startDataDownload(mode: GlassesSyncFlow = GlassesSyncFlow.CUSTOM) {
        mediaSync.startDataDownload(mode)
    }

    private fun formatTransferBytes(bytes: Long): String = TransferProgressPresenter.formatTransferBytes(bytes)

    private fun cancelDataDownloadAttempt(reason: String, showToast: Boolean) {
        mediaSync.cancelDataDownloadAttempt(reason, showToast)
    }

    private fun abandonDownloadP2pForBluetoothDisconnect() {
        mediaSync.abandonDownloadP2pForBluetoothDisconnect()
    }

    private fun isOfficialSyncActive(): Boolean = mediaSync.isOfficialSyncActive()

    private fun onDownloadBleIp(ip: String) {
        mediaSync.onDownloadBleIp(ip)
    }

    private fun maybeResetP2pAfterError255(source: String) {
        mediaSync.maybeResetP2pAfterError255(source)
    }

    /**
     * Debug helper: log all methods on LargeDataHandler so we can
     * discover additional SDK capabilities (such as WiFi transfer APIs)
     * without needing decompiled sources.
     */
    private fun logLargeDataHandlerMethodsOnce() {
        if (loggedLargeDataHandlerMethods) return
        loggedLargeDataHandlerMethods = true
        try {
            val clazz = LargeDataHandler.getInstance()::class.java
            val methods = clazz.declaredMethods
            for (m in methods) {
                val params = m.parameterTypes.joinToString(",") { it.simpleName ?: it.name }
                val ret = m.returnType.simpleName ?: m.returnType.name
                Log.i("LDHMethods", "method=${m.name}, params=($params), return=$ret")
            }
        } catch (e: Exception) {
            Log.e("LDHMethods", "Failed to introspect LargeDataHandler methods", e)
        }
    }

    inner class MyDeviceNotifyListener : GlassesDeviceNotifyListener() {

        @RequiresApi(Build.VERSION_CODES.O)
        override fun parseData(cmdType: Int, response: GlassesDeviceNotifyRsp) {
            Log.i(
                "DeviceNotify",
                "cmdType=$cmdType, loadData=${response.loadData.joinToString(separator = ",") { it.toInt().toString() }}"
            )
            if (otaManager.isActive) {
                Log.d("DeviceNotify", "Skipping general device-notify handling during OTA")
                return
            }
            if (response.loadData.size <= 6) return
            when (response.loadData[6].toInt()) {
                //Glasses battery report
                0x05 -> {
                    //Current battery
                    val battery = response.loadData[7].toInt()
                    //Is it charging
                    val changing = response.loadData[8].toInt()
                    handleBatteryReport(battery, changing == 1)
                }
                //Glasses pass quick recognition / AI Photo
                0x02 -> {
                    if (com.fersaiyan.cyanbridge.glasses.HeyCyanPhotoCapture.isActive()) {
                        Log.i("DeviceNotify", "Photo-ready belongs to the background capture owner")
                        return
                    }
                    if (WalkingAidImageCapture.isAwaitingPhotoReady()) {
                        Log.i("DeviceNotify", "Walking Aid consumed its requested photo-ready notification")
                        return
                    }
                    val appRequestedCapture = imageCaptureAwaitingNotification.get()
                    // Live's dedicated listener delivers manual events even when this Activity
                    // is stopped. A requested MainActivity capture retains precedence.
                    if (!appRequestedCapture &&
                        com.fersaiyan.cyanbridge.ai.live.GeminiLiveImageButtonRouter.isRegistered()
                    ) return
                    val sourceTag = pendingImageCaptureSourceTag ?: "hardware_image_button"
                    Log.i(
                        "ImageQuestionTransfer",
                        "AI photo-ready notify source=$sourceTag awaiting=${imageCaptureAwaitingNotification.get()} " +
                            "thumbnailInProgress=${imageThumbnailRequestInProgress.get()} " +
                            "captureAgeMs=${System.currentTimeMillis() - pendingImageCaptureStartedAtMs} " +
                            "payload=${response.loadData.joinToString { (it.toInt() and 0xFF).toString() }}",
                    )
                    if (isAiHijackEnabled) {
                        runOnUiThread {
                            if (maybeShowGeminiChatGptImageRequirementsWarning()) {
                                imageCaptureAwaitingNotification.set(false)
                                pendingImageCaptureSourceTag = null
                                return@runOnUiThread
                            }
                            imageCaptureAwaitingNotification.set(false)
                            pendingImageCaptureSourceTag = null
                            if (appRequestedCapture) {
                                requestSelectedImageSourceForQuestion(sourceTag)
                            } else {
                                // A hardware AI-photo press starts a complete image-question
                                // turn. It must not fall through to the 0x03 voice route.
                                handleGlassesImageButtonPressed(
                                    triggerCapture = false,
                                    sourceTag = sourceTag,
                                    source = ImageQuestionSourcePolicy.defaultSource(),
                                    thumbnailQuality = ImageQuestionSourcePolicy.defaultThumbnailQuality(),
                                    offerSpokenQuestion = true,
                                )
                            }
                        }
                    }
                }

                //Glasses activate microphone / AI button
                0x03 -> {
                    if (response.loadData.size > 7 && response.loadData[7].toInt() == 1) {
                        Log.i("DeviceNotify", "AI Button Pressed - Hijacking to Phone Assistant")
                        if (isAiHijackEnabled) {
                            handleAiWakeWordActivation("heycyan")
                        } else {
                            //The glasses activate the microphone to start speaking
                            runOnUiThread {
                                Toast.makeText(
                                    this@MainActivity,
                                    "Glasses microphone activated (Original Path)",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                }
                //ota upgrade
                0x04 -> {
                    try {
                        val download = response.loadData[7].toInt()
                        val soc = response.loadData[8].toInt()
                        val nor = response.loadData[9].toInt()
                        //download firmware download progress soc download progress nor upgrade progress
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

                0x0c -> {
                    //The glasses trigger a pause event, voice broadcast
                    if (response.loadData[7].toInt() == 1) {
                        //to do
                    }
                }

                0x0d -> {
                    //Unbind APP event
                    if (response.loadData[7].toInt() == 1) {
                        //to do
                    }
                }
                //Glasses memory low event
                0x0e -> {

                }
                //Translation pause event
                0x10 -> {

                }
                //Glasses volume change event
                0x12 -> {
                    //Music volume
                    //Minimum volume
                    response.loadData[8].toInt()
                    //Maximum volume
                    response.loadData[9].toInt()
                    //Current volume
                    response.loadData[10].toInt()

                    //Incoming call volume
                    //Minimum volume
                    response.loadData[12].toInt()
                    //Maximum volume
                    response.loadData[13].toInt()
                    //Current volume
                    response.loadData[14].toInt()

                    //Glasses system volume
                    //Minimum volume
                    response.loadData[16].toInt()
                    //Maximum volume
                    response.loadData[17].toInt()
                    //Current volume
                    response.loadData[18].toInt()

                    //Current volume mode
                    val mode = response.loadData[19].toInt()

                    runOnUiThread {
                        Toast.makeText(
                            this@MainActivity,
                            "Volume changed (mode=$mode)",
                            Toast.LENGTH_SHORT
                        ).show()
                    }

                }
                // Glasses report WiFi IP for data download
                0x08 -> {
                    if (isOfficialSyncActive()) {
                        Log.i("DeviceNotify", "Skipping main 0x08 handling during official sync flow")
                        return
                    }
                    if (response.loadData.size >= 11) {
                        val ip = "${ByteUtil.byteToInt(response.loadData[7])}." +
                                "${ByteUtil.byteToInt(response.loadData[8])}." +
                                "${ByteUtil.byteToInt(response.loadData[9])}." +
                                "${ByteUtil.byteToInt(response.loadData[10])}"
                        Log.i("DeviceNotify", "BLE reported WiFi IP: $ip")
                        onDownloadBleIp(ip)
                    } else {
                        Log.w(
                            "DeviceNotify",
                            "0x08 notify with too-short payload, size=${response.loadData.size}"
                        )
                    }
                }
                // Glasses report P2P / WiFi error during data download
                0x09 -> {
                    if (isOfficialSyncActive()) {
                        Log.i("DeviceNotify", "Skipping main 0x09 handling during official sync flow")
                        return
                    }
                    val raw = response.loadData.getOrNull(7) ?: 0
                    val errorCode = ByteUtil.byteToInt(raw)
                    Log.e("DeviceNotify", "P2P/WiFi error from device: $errorCode (raw=$raw)")
                    if (errorCode == 255) {
                        // Mirror the official app: ask the glasses/phone P2P
                        // layer to reset, but do NOT treat this as a fatal
                        // error for the whole download flow. The official app
                        // still proceeds to receive an IP and download.
                        maybeResetP2pAfterError255("main")
                    }
                }
            }
        }
    }
}

package com.fersaiyan.cyanbridge
import com.fersaiyan.cyanbridge.shared.devices.DeviceProfile
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
import com.fersaiyan.cyanbridge.ota.FirmwareClient
import com.fersaiyan.cyanbridge.ota.InstalledFirmwareVersions
import com.fersaiyan.cyanbridge.ota.FirmwareResult
import com.fersaiyan.cyanbridge.ota.OtaManager
import com.fersaiyan.cyanbridge.ota.OtaReadinessStage
import com.fersaiyan.cyanbridge.ota.OtaState
import com.fersaiyan.cyanbridge.ota.OtaTarget
import com.fersaiyan.cyanbridge.ota.expectedFirmwareExtension
import com.fersaiyan.cyanbridge.ota.firmwareRelayBaseUrl
import com.fersaiyan.cyanbridge.ota.firmwareSubscriptionGateCopy
import com.fersaiyan.cyanbridge.ota.isExpectedFirmwareFilename
import com.fersaiyan.cyanbridge.glasses.GlassesSession
import com.fersaiyan.cyanbridge.glasses.GlassesSessionLease
import com.fersaiyan.cyanbridge.glasses.GlassesSessionCoordinator
import com.fersaiyan.cyanbridge.glasses.BackgroundGlassesCommandPermit
import com.fersaiyan.cyanbridge.wifiadb.DefaultWifiAdbDebugControllerFactory
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
// import com.fersaiyan.cyanbridge.ui.notes.NotesListActivity
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
import android.provider.OpenableColumns
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

import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.fersaiyan.cyanbridge.agent.ProSubscriptionAiPrefs
import com.fersaiyan.cyanbridge.agent.ProSubscriptionActivity
import com.fersaiyan.cyanbridge.agent.ProSubscriptionServerPrefs
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
    private val assistantRequestRouter = AssistantRequestRouter()
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
        private const val P2P_GROUP_REMOVAL_RETRY_MS = 1_000L
        private const val P2P_GROUP_REMOVE_ACTION_TIMEOUT_MS = 5_000L
        private const val P2P_GROUP_DISCONNECT_TIMEOUT_MS = 5_000L
        private const val P2P_GROUP_REMOVAL_MAX_ATTEMPTS = 3
        private const val PULL_OTA_TEST_LEASE_MS = 10_000L
        private const val ONE_SHOT_BLE_COMMAND_TIMEOUT_MS = 6_000L
        private const val TRANSFER_MODE_COMMAND_TIMEOUT_MS = 10_000L
        private const val IMAGE_THUMBNAIL_TRANSFER_TIMEOUT_MS = 20_000L
        private const val VOICE_CUE_ROUTE_SETTLE_MS = 500L
        private const val VOICE_BLUETOOTH_ROUTE_TIMEOUT_MS = 3_000L
        private const val VOICE_CUE_BLUETOOTH_TAIL_MS = 50L
        private const val VOICE_CUE_CALLBACK_TIMEOUT_MS = 3_000L
        private const val VOICE_RECOGNITION_RETRY_DELAY_MS = 250L
        private const val IMAGE_QUESTION_CUE_BLUETOOTH_TAIL_MS = 50L
        private const val IMAGE_QUESTION_INITIAL_LISTENING_TIMEOUT_MS = 3_300L
        private val DEFAULT_VIDEO_DURATION_OPTIONS_SECONDS = listOf(15, 30, 60, 180, 540, 720)
        private val AUDIO_DURATION_OPTIONS_SECONDS = listOf(1_800, 3_600, 7_200)

        fun actionTaskerCommand(appPackageName: String): String =
            "$appPackageName.ACTION_TASKER_COMMAND"

        fun aiEventAction(appPackageName: String): String =
            "$appPackageName.AI_EVENT"

        // Edit this URL before using the pull-mode OTA test button.
        // In the official app, the phone runs an HTTP server on its own
        // Wi‑Fi Direct address and the glasses fetch the file from there.
        // For experiments you can point this at a simple `python -m http.server`
        // instance on the phone or on a reachable host.
        private const val TEST_PULL_OTA_URL =
            "http://192.168.49.1:8080/dummy.swu"
    }

    // Keeps the existing Android control handlers alive while Compose owns the visible tree.
    private lateinit var binding: AcitivytMainBinding
    private var dashboardState by mutableStateOf(
        GlassesDashboardUiState(
            wifiAdbDebug = WifiAdbDebugUiState(isAvailable = false),
        ),
    )
    private var showDownloadFlowPicker by mutableStateOf(false)
    private val deviceNotifyListener by lazy { MyDeviceNotifyListener() }
    private var otaSessionLease: GlassesSessionLease? = null
    private var livePreviewSessionLease: GlassesSessionLease? = null
    private var eyevueLivePreviewManager: EyevueLivePreviewManager? = null
    private var eyevueLivePreviewUiJob: Job? = null
    private var mediaSessionLease: GlassesSessionLease? = null
    private var eyevueMediaJob: Job? = null
    private var eyevueMediaTransport: EyevueWifiTransport? = null
    private var eyevueMediaCancelled = false
    private var tuneBudsMediaJob: Job? = null
    private var tuneBudsMediaHotspot: TuneBudsLocalHotspot? = null
    private var tuneBudsMediaCancelled = false
    private var moyoungMediaJob: Job? = null
    private var moyoungMediaCancelled = false
    private var wifiAdbDebugSessionLease: GlassesSessionLease? = null
    private val otaManager by lazy { OtaManager(this) }
    private var otaPreparationJob: Job? = null
    private var pendingPersonalFirmwareTarget: OtaTarget? = null
    private var stagedPersonalWifiFirmware: File? = null
    private var otaExpectedDeviceAddress: String? = null
    private val personalFirmwarePicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val target = pendingPersonalFirmwareTarget
            pendingPersonalFirmwareTarget = null
            if (target == null) return@registerForActivityResult
            if (uri == null) {
                abortPersonalFirmwareSelection("Both Wi-Fi and BLE firmware files are required.")
            } else {
                stagePersonalFirmware(uri, target)
            }
        }
    private val livePreviewManager by lazy { com.fersaiyan.cyanbridge.ota.LivePreviewManager(this) }
    private var livePreviewDialog: AlertDialog? = null
    private val wifiAdbDebugController by lazy { DefaultWifiAdbDebugControllerFactory.create(this) }

    // AI Hijack settings
    private var isAiHijackEnabled = true // Default to enabled
    private var overlayPermissionPromptShown = false
    private var isImageAssistantMode = true // Use assistant vs share intent
    private var aiAssistantMode = AI_MODE_PHONE_ASSISTANT
    private var wakeWordConfiguredForConnection = false

    // State used by the BLE+WiFi P2P data-download flow
    private var downloadP2pConnected = false
    private var downloadFlowMode = GlassesSyncFlow.CUSTOM
    private var downloadBleIp: String? = null
    private var downloadWifiIp: String? = null
    private var downloadPhoneIsGroupOwner: Boolean? = null
    private var downloadInProgress = false
    private var downloadAttemptJob: Job? = null
    private var downloadSessionJob: Job? = null
    private var downloadSessionScope: CoroutineScope? = null
    // Must outlive Activity destruction long enough to confirm P2P teardown.
    private val glassesTeardownScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var downloadSessionId: Long = 0L
    private var vendorAlbumDownloader: VendorAlbumDownloader? = null
    private var downloadResolvedHttpIp: String? = null
    private var downloadP2pNetwork: Network? = null
    private var boundNetwork: Network? = null
    private var lastP2pResetAtMs: Long = 0L
    private var downloadWifiP2pManager: WifiP2pManagerSingleton? = null
    private var downloadWifiP2pCallback: WifiP2pManagerSingleton.WifiP2pCallback? = null
    private var downloadP2pTeardownInProgress = false
    private var downloadExitTransferRequested = false
    private var downloadCancelledByUser = false
    private var lastDownloadBleIpAtMs: Long = 0L
    private var downloadInitialPhaseTimeoutJob: Job? = null
    private var downloadInitialPhaseCompleted = false
    private var downloadSupportDialogShown = false
    private var downloadStartedAtMs: Long = 0L
    private var noMatchPeerCount = 0
    private var downloadP2pRestartCount = 0
    private val maxP2pRestarts = 3
    private val seenP2pPeers = mutableSetOf<String>()
    private var lastPeerSetHash: Int = 0
    private var officialSystemSuccess = false
    private var officialBleCallbackSuccess = false
    private var officialFlowRetryCount = 0
    private val officialFlowRetryLimit = 1
    private var officialDisconnectRecoveryJob: Job? = null
    private var officialMediaErrorCount = 0
    private var transferModeCommandAttempt = 0
    private var transferModeCommandSentAtMs = 0L
    private var transferModeCommandCallbackLatencyMs: Long? = null
    private var transferModeCommandCallbackReceived = false
    private var transferModeCommandEvidenceReceived = false
    private var transferModeCommandTimeoutJob: Job? = null
    private var selectedDownloadNetworkSummary = "none"
    private var officialFlowRetryRequired = false

    // Guard against concurrent/duplicate image queries
    private val imageQueryInProgress = java.util.concurrent.atomic.AtomicBoolean(false)
    private val voiceQueryInProgress = AtomicReference<Any?>(null)
    private val activeVoiceRecognizer = AtomicReference<SpeechRecognizer?>(null)
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
    private var mediaDownloadPurpose = MediaDownloadPurpose.FULL_SYNC
    private var highQualityImageRequest: HighQualityImageRequest? = null
    private var lastImageQueryAtMs: Long = 0L
    private var activeParallelAudioQuestionDeferred: kotlinx.coroutines.CompletableDeferred<String?>? = null
    private var activeParallelAudioQuestionJob: Job? = null

    // Official app registers the notify listener with cmdType=2 for album import.
    // Keep our main listener (cmdType=100) for general events, and add a narrow
    // one for the download flow so we don't duplicate thumbnail/audio handling.
    private val downloadNotifyListener by lazy { DownloadNotifyListener() }
    private var downloadNotifyListenerRegistered = false

    // UI state for P2P sync progress
    private var transferTotalJpg = 0
    private var transferTotalMp4 = 0
    private var transferTotalOpus = 0
    private var transferDoneJpg = 0
    private var transferDoneMp4 = 0
    private var transferDoneOpus = 0
    private var lastFileProgressUiAtMs: Long = 0L
    private var batteryPollJob: Job? = null
    private val batteryPollIntervalMs = 60_000L
    private val downloadInitialPhaseTimeoutMs = 45_000L
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

    private val metaAndroidPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val action = pendingMetaDatAction
            pendingMetaDatAction = null
            enabledMetaCameraCheckActive = false
            if (result.values.all { it }) {
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
            if (result.getOrDefault(PermissionStatus.Denied) == PermissionStatus.Granted) {
                action?.invoke()
            } else {
                showMetaError("DAT camera permission", "Meta camera permission was denied")
            }
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
                )
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
            GlassesSessionCoordinator.clearForDisconnectedDevice()
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
        if (eyevueLivePreviewManager?.isActive == true) {
            eyevueLivePreviewManager?.stop()
        }
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
        activeVoiceRecognizer.getAndSet(null)?.let { recognizer ->
            runCatching { recognizer.destroy() }
        }
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
        if (eyevueMediaJob?.isActive == true) {
            eyevueMediaCancelled = true
            eyevueMediaTransport?.disconnect()
            eyevueMediaJob?.cancel()
        }
        if (tuneBudsMediaJob?.isActive == true) {
            tuneBudsMediaCancelled = true
            tuneBudsMediaHotspot?.stop()
            tuneBudsMediaJob?.cancel()
        }
        if (moyoungMediaJob?.isActive == true) {
            moyoungMediaCancelled = true
            moyoungW620Manager?.stopMediaSync()
            moyoungMediaJob?.cancel()
        }
        otaPreparationJob?.cancel()
        otaPreparationJob = null
        otaManager.cancel()
        pendingPersonalFirmwareTarget = null
        stagedPersonalWifiFirmware?.takeIf { it.exists() }?.delete()
        stagedPersonalWifiFirmware = null
        if (!otaManager.isActive) {
            releaseExclusiveGlassesSession(otaSessionLease)
        }
        if (isMoyoungW620Selected() && GlassesSessionCoordinator.currentSession() == GlassesSession.MEDIA_SYNC) {
            moyoungW620Manager?.stopMediaSync()
            releaseExclusiveGlassesSession(mediaSessionLease)
            mediaSessionLease = null
        } else if (isEyevueSelected() && GlassesSessionCoordinator.currentSession() == GlassesSession.MEDIA_SYNC) {
            getOrCreateEyevueManager().finishTransfer()
            eyevueMediaTransport?.disconnect()
            releaseExclusiveGlassesSession(mediaSessionLease)
            mediaSessionLease = null
        } else if (GlassesSessionCoordinator.currentSession() == GlassesSession.MEDIA_SYNC) {
            downloadCancelledByUser = true
            teardownDownloadP2pSession(
                sendExitTransfer = true,
                hideTransferUi = false,
            )
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
        refreshNativePluginShortcutState()
        ensureEnabledBackgroundFeaturePermissions()
        ensureEnabledMetaCameraFeature()
    }

    private fun ensureEnabledBackgroundFeaturePermissions() {
        if (enabledFeaturePermissionRequestActive) return

        val voicePluginEnabled =
            com.fersaiyan.cyanbridge.localmodels.remote.RemoteOpenAiPrefs.isBridgeConfigured(this) ||
                AutoAudioCapturePrefs.isEnabled(this) ||
                setOf(
                    NativePluginIds.LIVE_CAPTION_RELAY,
                    NativePluginIds.HANDS_FREE_TRANSLATOR,
                    NativePluginIds.ERRAND_BRAIN,
                    NativePluginIds.AUTO_AUDIO,
                ).any { CommunityPluginPrefs.isNativePluginEnabled(this, it) }

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

        if (CommunityPluginPrefs.isNativePluginEnabled(this, NativePluginIds.LIVE_CAPTION_RELAY)) {
            LiveCaptionRelayService.start(this)
        }
        if (CommunityPluginPrefs.isNativePluginEnabled(this, NativePluginIds.HANDS_FREE_TRANSLATOR)) {
            HandsFreeTranslatorService.start(this)
        }
        if (CommunityPluginPrefs.isNativePluginEnabled(this, NativePluginIds.ERRAND_BRAIN)) {
            ErrandBrainService.start(this)
        }
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
                        if (lp.isPlaying && lp.streamUrl != null) {
                            showRtspPlayerDialog(
                                streamUrl = lp.streamUrl,
                                player = manager.getPlayer(),
                                onClose = manager::stop,
                            )
                        } else {
                            livePreviewDialog?.let { dialog ->
                                livePreviewDialog = null
                                dialog.dismiss()
                            }
                        }
                    }
                }
            }
        }

    private fun metaAndroidPermissionsMissing(): Array<String> {
        val permissions = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions += Manifest.permission.BLUETOOTH_CONNECT
            permissions += Manifest.permission.BLUETOOTH_SCAN
        }
        return permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()
    }

    private fun ensureMetaDatReady(action: () -> Unit) {
        val missing = metaAndroidPermissionsMissing()
        if (missing.isNotEmpty()) {
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
        ensureMetaDatReady {
            val manager = getOrCreateMetaRaybanManager()
            manager.checkCameraPermission(
                onGranted = action,
                onRequestNeeded = {
                    pendingMetaCameraAction = action
                    metaWearablePermissionLauncher.launch(Permission.CAMERA)
                },
                onError = { error ->
                    showMetaError("DAT camera permission", error)
                },
            )
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

            requestWifiP2pPermission(this, object : OnPermissionCallback {
                override fun onGranted(permissions: MutableList<String>, all: Boolean) {
                    if (all) {
                        onGranted()
                    } else {
                        Toast.makeText(
                            this@MainActivity,
                            "Nearby devices or Location permission is required for $feature",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }

                override fun onDenied(permissions: MutableList<String>, never: Boolean) {
                    super.onDenied(permissions, never)
                    Toast.makeText(
                        this@MainActivity,
                        "Nearby devices or Location permission is required for $feature",
                        Toast.LENGTH_LONG,
                    ).show()
                    if (never) {
                        XXPermissions.startPermissionActivity(this@MainActivity, permissions)
                    }
                }
            })
        }
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
                if (!isHeyCyanOrEyevueSelected() && !isTuneBudsSelected()) return
                val quality = ImageQuestionPreferences.setThumbnailQuality(this, action.sdkValue)
                pendingImageThumbnailQuality = quality
                updateDashboardState { state ->
                    state.copy(
                        imageThumbnailQualitySdkValue = quality.sdkValue,
                        imageThumbnailQualityLabel = quality.label,
                    )
                }
            }
            GlassesDashboardAction.TestVoiceQuestion -> binding.btnTestHijackVoice.performClick()
            GlassesDashboardAction.TestImageQuestion -> binding.btnTestHijackImage.performClick()
            GlassesDashboardAction.OpenExternalImageAutomationDiagnostics -> {
                startActivity(Intent(this, ExternalAssistantAutomationSetupActivity::class.java))
            }
            GlassesDashboardAction.CapturePhoto -> if (isEyevueSelected()) {
                getOrCreateEyevueManager().takePhoto()
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
                getOrCreateEyevueManager().requestMediaCount()
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
                getOrCreateEyevueManager().requestBattery()
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
            GlassesDashboardAction.CancelOta -> {
                val managerWasActive = otaManager.isActive
                otaPreparationJob?.cancel()
                otaPreparationJob = null
                pendingPersonalFirmwareTarget = null
                stagedPersonalWifiFirmware?.takeIf { it.exists() }?.delete()
                stagedPersonalWifiFirmware = null
                otaManager.cancel()
                if (!managerWasActive) {
                    releaseExclusiveGlassesSession(otaSessionLease)
                    resetOtaDashboardToIdle()
                }
            }
            GlassesDashboardAction.StartLivePreview -> if (isEyevueSelected()) {
                startEyevueLivePreview()
            } else if (isHeyCyanSelected()) {
                startLivePreview()
            }
            GlassesDashboardAction.StopLivePreview -> {
                Log.i("LivePreview", "BUTTON TAP: Stop Live Preview")
                if (isEyevueSelected()) {
                    getOrCreateEyevueLivePreviewManager().stop()
                    releaseExclusiveGlassesSession(livePreviewSessionLease)
                    livePreviewSessionLease = null
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
            start()
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
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                })
            }
            AppDestination.MEDIA -> startActivity(Intent(this, RecordingsListActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            })
            AppDestination.PLUGINS -> startActivity(Intent(this, CommunityPluginsActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            })
            AppDestination.SETTINGS -> startActivity(Intent(this, SettingsActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            })
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

    private fun dumpOtaServerInfo() {
        if (rejectHeyCyanOnlyFeature("HeyCyan OTA")) return
        if (!BleOperateManager.getInstance().isConnected) {
            Log.e("OTAProbe", "Bluetooth not connected. Please connect to glasses first.")
            Toast.makeText(
                this,
                "Bluetooth not connected. Please connect to glasses first.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        LargeDataHandler.getInstance().syncDeviceInfo { _, response ->
            if (response == null) {
                Log.e("OTAProbe", "syncDeviceInfo returned null response")
                runOnUiThread {
                    Toast.makeText(
                        this,
                        "Failed to read device info for OTA",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                return@syncDeviceInfo
            }

            val wifiHw = response.wifiHardwareVersion ?: ""
            val wifiFw = response.wifiFirmwareVersion ?: ""
            val btFw = response.firmwareVersion ?: ""
            val hw = response.hardwareVersion ?: ""

            // OTA binary URL used by the official app's debug/down path.
            val otaBinaryUrl =
                "https://qcwxfactory.oss-cn-beijing.aliyuncs.com/bin/glasses/${wifiHw}.swu"

            // Try to download the OTA file directly into the app's files dir
            // so you can pull it with `adb` for inspection.
            val otaDir = File(getExternalFilesDir(null), "ota")
            if (!otaDir.exists()) {
                otaDir.mkdirs()
            }
            val outFile = File(otaDir, "${wifiHw}.swu")

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    Log.i(
                        "OTAProbe",
                        "Attempting OTA binary download to: ${outFile.absolutePath}"
                    )
                    val url = URL(otaBinaryUrl)
                    val conn = url.openConnection() as HttpURLConnection
                    conn.requestMethod = "GET"
                    conn.connectTimeout = 15000
                    conn.readTimeout = 60000

                    if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                        conn.inputStream.use { input ->
                            FileOutputStream(outFile).use { output ->
                                val buffer = ByteArray(8 * 1024)
                                while (true) {
                                    val read = input.read(buffer)
                                    if (read <= 0) break
                                    output.write(buffer, 0, read)
                                }
                                output.flush()
                            }
                        }
                        Log.i(
                            "OTAProbe",
                            "OTA binary download completed: ${outFile.absolutePath} (size=${outFile.length()} bytes)"
                        )
                    } else {
                        Log.e(
                            "OTAProbe",
                            "OTA binary download failed, HTTP ${conn.responseCode}"
                        )
                    }
                    conn.disconnect()
                } catch (e: Exception) {
                    Log.e(
                        "OTAProbe",
                        "Exception while downloading OTA binary: ${e.message}",
                        e
                    )
                }
            }

            Log.i("OTAProbe", "==== OTA SERVER INFO START ====")
            Log.i("OTAProbe", "Device hardware version     : $hw")
            Log.i("OTAProbe", "WiFi hardware version       : $wifiHw")
            Log.i("OTAProbe", "WiFi firmware version       : $wifiFw")
            Log.i("OTAProbe", "Bluetooth firmware version  : $btFw")
            Log.i(
                "OTAProbe",
                "OTA metadata API (global)   : https://www.qlifesnap.com/glasses/app-update/last-ota"
            )
            Log.i(
                "OTAProbe",
                "OTA metadata API (China)    : https://www.qlifesnap.com/glasses/app-update/last-ota/china"
            )
            Log.i("OTAProbe", "OTA binary URL candidate    : $otaBinaryUrl")

            val lastOtaJsonTemplate = """
                {
                  "appId": <APP_ID>,
                  "uid": <USER_ID>,
                  "hardwareVersion": "$wifiHw",
                  "romVersion": "$wifiFw",
                  "os": 1,
                  "mac": "<PHONE_OR_BT_MAC>",
                  "country": "<COUNTRY_CODE>",
                  "dev": 2
                }
            """.trimIndent()

            Log.i("OTAProbe", "Sample LastOtaRequest JSON (fill in placeholders):")
            Log.i("OTAProbe", lastOtaJsonTemplate)
            Log.i(
                "OTAProbe",
                "Sample curl (metadata): curl -X POST 'https://www.qlifesnap.com/glasses/app-update/last-ota' -H 'Content-Type: application/json' -d '<JSON_ABOVE>'"
            )
            Log.i(
                "OTAProbe",
                "Sample curl (binary)  : curl -o '${wifiHw}.swu' '$otaBinaryUrl'"
            )
            Log.i("OTAProbe", "==== OTA SERVER INFO END ====")

            runOnUiThread {
                Toast.makeText(
                    this,
                    "OTA server info dumped to logcat (tag: OTAProbe)",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /**
     * Minimal wrapper around LargeDataHandler.writeIpToSoc so we can observe
     * how the glasses behave when asked to fetch an OTA image from an HTTP
     * server under our control.
     *
     * This does not start any HTTP server on the phone; you must run one
     * yourself and point TEST_PULL_OTA_URL at it.
     */
    private fun testPullModeOta() {
        if (rejectHeyCyanOnlyFeature("HeyCyan pull-mode OTA")) return
        if (!BuildConfig.DEBUG) {
            Log.w("PullOtaTest", "Pull-mode OTA testing is disabled outside debug builds")
            Toast.makeText(this, "Pull-mode OTA testing is available only in debug builds.", Toast.LENGTH_LONG).show()
            return
        }
        if (isGlassesCommandBlocked("pull-mode OTA test")) return
        if (!BleOperateManager.getInstance().isConnected) {
            Log.e("PullOtaTest", "Bluetooth not connected. Please connect to glasses first.")
            Toast.makeText(
                this,
                "Bluetooth not connected. Please connect to glasses first.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val url = TEST_PULL_OTA_URL
        if (url.isBlank()) {
            Log.e("PullOtaTest", "TEST_PULL_OTA_URL is blank; edit MainActivity to set it.")
            Toast.makeText(
                this,
                "TEST_PULL_OTA_URL is blank. Edit MainActivity first.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val pullOtaLease = acquireExclusiveGlassesSession(GlassesSession.OTA) ?: return
        Log.i("PullOtaTest", "Calling writeIpToSoc with URL: $url")
        try {
            LargeDataHandler.getInstance().writeIpToSoc(url) { cmdType, response ->
                Log.i(
                    "PullOtaTest",
                    "writeIpToSoc callback: cmdType=$cmdType, response=$response"
                )
            }
            // Keep the exclusive BLE slot for the response window. The callback is diagnostic
            // only, so a late vendor response cannot release a later OTA session.
            glassesTeardownScope.launch {
                delay(PULL_OTA_TEST_LEASE_MS)
                releaseExclusiveGlassesSession(pullOtaLease)
            }
        } catch (e: Exception) {
            releaseExclusiveGlassesSession(pullOtaLease)
            Log.e("PullOtaTest", "writeIpToSoc failed", e)
        }
    }

    private fun observeOtaState() {
        lifecycleScope.launch {
            otaManager.uiState.collect { ota ->
                val preparationActive = otaPreparationJob?.isActive == true
                val sessionActive = otaManager.isActive || otaSessionLease != null
                val terminal = ota.state == OtaState.IDLE ||
                    ota.state == OtaState.COMPLETE ||
                    ota.state == OtaState.FAILED
                dashboardState = dashboardState.copy(
                    ota = com.fersaiyan.cyanbridge.shared.glasses.OtaSectionUiState(
                        stateLabel = ota.state.name.replace("_", " ").lowercase()
                            .replaceFirstChar { it.uppercase() },
                        detail = ota.detail.ifBlank { ota.error.orEmpty() },
                        progress = ota.progress,
                        canStart = terminal && !preparationActive && !sessionActive,
                        canCancel = (preparationActive || sessionActive) &&
                            ota.state !in setOf(OtaState.COMPLETE, OtaState.CANCELLING),
                    ),
                )
            }
        }
    }

    private fun requestOtaFirmware(source: OtaFirmwareSource) {
        if (rejectHeyCyanOnlyFeature("HeyCyan OTA")) return
        if (!hasBluetooth(this) || !hasWifiP2pPermission(this)) {
            ensureGlassesTransportPermissions("Wi-Fi OTA") {
                requestOtaFirmware(source)
            }
            return
        }
        if (otaManager.isActive || otaPreparationJob?.isActive == true || otaSessionLease != null) {
            Log.w("Ota", "OTA is already preparing or running")
            return
        }
        if (AutoAudioCaptureService.isRunning()) {
            Toast.makeText(this, "Stop auto audio capture before starting a firmware update.", Toast.LENGTH_LONG).show()
            return
        }

        when (source) {
            OtaFirmwareSource.PERSONAL_FILE -> {
                val otaLease = acquireExclusiveGlassesSession(GlassesSession.OTA) ?: return
                otaSessionLease = otaLease
                stagedPersonalWifiFirmware = null
                pendingPersonalFirmwareTarget = OtaTarget.V821_WIFI
                dashboardState = dashboardState.copy(
                    ota = dashboardState.ota.copy(
                        stateLabel = "Selecting firmware",
                        detail = "Select the Wi-Fi .swu file first; the BLE .bin picker follows.",
                        progress = null,
                        canStart = false,
                        canCancel = true,
                    ),
                )
                try {
                    personalFirmwarePicker.launch(arrayOf("*/*"))
                } catch (error: Exception) {
                    finishPersonalFirmwareSelection("Could not open the Wi-Fi firmware picker: ${error.message}")
                }
            }
            OtaFirmwareSource.STEALTH_CATALOG,
            OtaFirmwareSource.DEBUG_CATALOG,
            -> startCatalogOta(source)
        }
    }

    private fun stagePersonalFirmware(uri: Uri, target: OtaTarget) {
        if (otaManager.isActive || otaSessionLease == null) return
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        otaPreparationJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) {
                    dashboardState = dashboardState.copy(
                        ota = dashboardState.ota.copy(
                            stateLabel = "Staging firmware",
                            detail = "Copying the selected ${target.expectedFirmwareExtension()} into private app storage...",
                            canStart = false,
                            canCancel = true,
                        ),
                    )
                }
                val firmwareFile = copyPersonalFirmware(uri, target)
                withContext(Dispatchers.Main) {
                    if (target == OtaTarget.V821_WIFI) {
                        stagedPersonalWifiFirmware = firmwareFile
                        pendingPersonalFirmwareTarget = OtaTarget.JIELI_BLE
                        dashboardState = dashboardState.copy(
                            ota = dashboardState.ota.copy(
                                stateLabel = "Selecting firmware",
                                detail = "Wi-Fi SWU staged. Select the companion Bluetooth/JieLi .bin file.",
                                canStart = false,
                                canCancel = true,
                            ),
                        )
                        try {
                            personalFirmwarePicker.launch(arrayOf("*/*"))
                        } catch (error: Exception) {
                            finishPersonalFirmwareSelection("Could not open the BLE firmware picker: ${error.message}")
                        }
                    } else {
                        val wifiFile = stagedPersonalWifiFirmware
                            ?: throw IllegalStateException("The Wi-Fi firmware file was not staged")
                        stagedPersonalWifiFirmware = null
                        startCombinedOtaWithLease(
                            wifiFile = wifiFile,
                            bleFile = firmwareFile,
                            source = OtaFirmwareSource.PERSONAL_FILE,
                            otaLease = requireNotNull(otaSessionLease),
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("Ota", "Could not import personal firmware", e)
                withContext(Dispatchers.Main) {
                    finishPersonalFirmwareSelection(
                        "Could not stage the selected firmware: ${e.message ?: "unknown error"}",
                    )
                }
            }
        }
    }

    private fun finishPersonalFirmwareSelection(message: String? = null) {
        pendingPersonalFirmwareTarget = null
        stagedPersonalWifiFirmware?.takeIf { it.exists() }?.delete()
        stagedPersonalWifiFirmware = null
        if (!otaManager.isActive) {
            releaseExclusiveGlassesSession(otaSessionLease)
        }
        resetOtaDashboardToIdle()
        message?.let {
            Toast.makeText(this, it, Toast.LENGTH_LONG).show()
        }
    }

    private fun abortPersonalFirmwareSelection(message: String) {
        finishPersonalFirmwareSelection(message)
    }

    private fun copyPersonalFirmware(uri: Uri, target: OtaTarget): File {
        val displayName = personalFirmwareDisplayName(uri)
            ?: throw IllegalArgumentException("The selected document has no filename")
        if (!target.isExpectedFirmwareFilename(displayName)) {
            throw IllegalArgumentException("Select a ${target.expectedFirmwareExtension()} file for this target")
        }

        val otaDir = File(filesDir, "ota/personal")
        if (!otaDir.exists() && !otaDir.mkdirs()) {
            throw IllegalStateException("Could not create private OTA storage")
        }
        val targetName = target.name.lowercase()
        val outputFile = File(
            otaDir,
            "personal_${targetName}_${System.currentTimeMillis()}${target.expectedFirmwareExtension()}",
        )
        val stagingFile = File(otaDir, ".${outputFile.name}.partial")
        try {
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(stagingFile).use { output -> input.copyTo(output) }
            } ?: throw IllegalArgumentException("The selected document cannot be read")

            if (stagingFile.length() <= 0L) {
                throw IllegalArgumentException("The selected firmware file is empty")
            }
            if (!stagingFile.renameTo(outputFile)) {
                throw IllegalStateException("Could not finalize the selected firmware file")
            }
            return outputFile
        } finally {
            if (stagingFile.exists()) stagingFile.delete()
        }
    }

    private fun personalFirmwareDisplayName(uri: Uri): String? {
        val displayName = runCatching {
            contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
            }
        }.getOrNull()
        return displayName?.trim()?.takeIf { it.isNotEmpty() }
            ?: uri.lastPathSegment?.substringAfterLast('/')?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun resetOtaDashboardToIdle() {
        dashboardState = dashboardState.copy(
            ota = dashboardState.ota.copy(
                stateLabel = "Idle",
                detail = "",
                progress = null,
                canStart = true,
                canCancel = false,
            ),
        )
    }

    private fun startCombinedOtaWithLease(
        wifiFile: File,
        bleFile: File,
        source: OtaFirmwareSource,
        otaLease: GlassesSessionLease,
    ) {
        if (AutoAudioCaptureService.isRunning()) {
            Toast.makeText(this, "Stop auto audio capture before starting a firmware update.", Toast.LENGTH_LONG).show()
            releaseExclusiveGlassesSession(otaLease)
            resetOtaDashboardToIdle()
            return
        }
        Log.i(
            "Ota",
            "Starting combined ${source.name} OTA: wifi=${wifiFile.name} (${wifiFile.length()} bytes), " +
                "ble=${bleFile.name} (${bleFile.length()} bytes)",
        )
        otaExpectedDeviceAddress = runCatching { DeviceManager.getInstance().deviceAddress }
            .getOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        otaManager.startCombinedOta(
            wifiFirmwareFile = wifiFile,
            bleFirmwareFile = bleFile,
            awaitFreshBleReadiness = ::awaitFreshBleReadiness,
        ) {
            releaseExclusiveGlassesSession(otaLease)
            otaExpectedDeviceAddress = null
            runOnUiThread {
                dashboardState = dashboardState.copy(
                    ota = dashboardState.ota.copy(
                        canStart = true,
                        canCancel = false,
                    ),
                )
            }
        }
    }

    /** Downloads a catalog artifact only after the shared OTA session lease is held. */
    private fun startCatalogOta(source: OtaFirmwareSource) {
        val otaLease = acquireExclusiveGlassesSession(GlassesSession.OTA) ?: return
        otaSessionLease = otaLease
        otaPreparationJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) {
                    dashboardState = dashboardState.copy(
                        ota = dashboardState.ota.copy(
                            stateLabel = "Checking firmware versions",
                            detail = "Reading Wi-Fi and Bluetooth identifiers once before resolving both artifacts...",
                            canStart = false,
                            canCancel = true,
                        ),
                    )
                }

                val deviceInfo = readGlassesDeviceInfo()
                if (deviceInfo == null) {
                    throw IllegalStateException("Could not read glasses info. Is Bluetooth connected?")
                }

                val wifiHardwareVersion = deviceInfo.wifiHardwareVersion.orEmpty().trim()
                val wifiFirmwareVersion = deviceInfo.wifiFirmwareVersion.orEmpty().trim()
                val bleHardwareVersion = deviceInfo.hardwareVersion.orEmpty().trim()
                val bleFirmwareVersion = deviceInfo.firmwareVersion.orEmpty().trim()
                val installedFirmwareVersions = InstalledFirmwareVersions(
                    wifiHardwareVersion = wifiHardwareVersion,
                    wifiFirmwareVersion = wifiFirmwareVersion,
                    bleHardwareVersion = bleHardwareVersion,
                    bleFirmwareVersion = bleFirmwareVersion,
                )
                if (!installedFirmwareVersions.isComplete()) {
                    throw IllegalStateException("Could not read all Wi-Fi and Bluetooth firmware identifiers")
                }

                val client = FirmwareClient(this@MainActivity)
                withContext(Dispatchers.Main) {
                    dashboardState = dashboardState.copy(
                        ota = dashboardState.ota.copy(
                            stateLabel = "Resolving Wi-Fi artifact",
                            detail = "Requesting the exact-base Wi-Fi .swu artifact (1/2)...",
                        ),
                    )
                }
                val otaDir = File(filesDir, "ota/catalog")
                val wifiResult = try {
                    client.fetchAndDownload(
                        deviceVersions = installedFirmwareVersions,
                        outputDir = otaDir,
                        target = OtaTarget.V821_WIFI,
                        source = source,
                    )
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    FirmwareResult.Error("Wi-Fi artifact request failed: ${error.message}")
                }

                if (wifiResult !is FirmwareResult.Ready) {
                    showCatalogResolutionFailure(
                        result = wifiResult,
                        source = source,
                        target = OtaTarget.V821_WIFI,
                        deviceInfo = deviceInfo,
                    )
                    return@launch
                }

                withContext(Dispatchers.Main) {
                    dashboardState = dashboardState.copy(
                        ota = dashboardState.ota.copy(
                            stateLabel = "Resolving BLE artifact",
                            detail = "Requesting the exact-base Bluetooth/JieLi .bin artifact (2/2)...",
                        ),
                    )
                }
                val bleResult = try {
                    client.fetchAndDownload(
                        deviceVersions = installedFirmwareVersions,
                        outputDir = otaDir,
                        target = OtaTarget.JIELI_BLE,
                        source = source,
                    )
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    FirmwareResult.Error("BLE artifact request failed: ${error.message}")
                }

                if (bleResult !is FirmwareResult.Ready) {
                    showCatalogResolutionFailure(
                        result = bleResult,
                        source = source,
                        target = OtaTarget.JIELI_BLE,
                        deviceInfo = deviceInfo,
                    )
                    return@launch
                }

                withContext(Dispatchers.Main) {
                    startCombinedOtaWithLease(
                        wifiFile = wifiResult.file,
                        bleFile = bleResult.file,
                        source = source,
                        otaLease = otaLease,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("Ota", "Catalog OTA preparation failed", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        "Firmware preparation failed: ${e.message}",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            } finally {
                if (!otaManager.isActive) {
                    withContext(Dispatchers.Main) {
                        releaseExclusiveGlassesSession(otaLease)
                        dashboardState = dashboardState.copy(
                            ota = dashboardState.ota.copy(
                                stateLabel = "Idle",
                                detail = "",
                                progress = null,
                                canStart = true,
                                canCancel = false,
                            ),
                        )
                    }
                }
            }
        }
    }

    private suspend fun showCatalogResolutionFailure(
        result: FirmwareResult,
        source: OtaFirmwareSource,
        target: OtaTarget,
        deviceInfo: com.oudmon.ble.base.communication.bigData.resp.DeviceInfoResponse,
    ) {
        withContext(Dispatchers.Main) {
            when (result) {
                is FirmwareResult.NotAvailable -> {
                    val targetHardwareVersion = when (target) {
                        OtaTarget.V821_WIFI -> deviceInfo.wifiHardwareVersion.orEmpty()
                        OtaTarget.JIELI_BLE -> deviceInfo.hardwareVersion.orEmpty()
                    }
                    val targetFirmwareVersion = when (target) {
                        OtaTarget.V821_WIFI -> deviceInfo.wifiFirmwareVersion.orEmpty()
                        OtaTarget.JIELI_BLE -> deviceInfo.firmwareVersion.orEmpty()
                    }
                    dashboardState = dashboardState.copy(
                        ota = dashboardState.ota.copy(
                            stateLabel = "Firmware set unavailable",
                            detail = "The ${target.expectedFirmwareExtension()} artifact was not resolved; no component will be flashed.",
                        ),
                        firmwarePatchRequest = FirmwarePatchRequestUiState(
                            source = source,
                            target = target.toOtaTargetSelection(),
                            targetHardwareVersion = targetHardwareVersion.ifBlank { "unknown" },
                            targetFirmwareVersion = targetFirmwareVersion.ifBlank { "unknown" },
                            wifiHardwareVersion = deviceInfo.wifiHardwareVersion.orEmpty().ifBlank { "unknown" },
                            wifiFirmwareVersion = deviceInfo.wifiFirmwareVersion.orEmpty().ifBlank { "unknown" },
                            bleHardwareVersion = deviceInfo.hardwareVersion.orEmpty().ifBlank { "unknown" },
                            bleFirmwareVersion = deviceInfo.firmwareVersion.orEmpty().ifBlank { "unknown" },
                            relayMessage = result.message,
                            suggestedContactEmail = ProSubscriptionServerPrefs.getAccountEmail(this@MainActivity),
                        ),
                    )
                }

                is FirmwareResult.DebugAccessRequired -> {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Debug Firmware Access Required")
                        .setMessage(result.message)
                        .setPositiveButton("OK", null)
                        .show()
                }

                is FirmwareResult.SubscriptionRequired -> {
                    val copy = firmwareSubscriptionGateCopy(result.currentPlan)
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle(copy.title)
                        .setMessage(copy.message)
                        .setNegativeButton("Not now", null)
                        .setPositiveButton(copy.actionLabel) { _, _ ->
                            startActivity(
                                Intent(this@MainActivity, ProSubscriptionActivity::class.java).apply {
                                    putExtra(ProSubscriptionActivity.EXTRA_INITIAL_PLAN, "standard")
                                    putExtra(ProSubscriptionActivity.EXTRA_CHANGE_PLAN, true)
                                },
                            )
                        }
                        .show()
                }

                is FirmwareResult.Error -> {
                    Toast.makeText(
                        this@MainActivity,
                        "Firmware resolution failed: ${result.message}",
                        Toast.LENGTH_LONG,
                    ).show()
                }

                is FirmwareResult.Ready -> Unit
            }
        }
    }

    private fun submitFirmwarePatchRequest(
        request: FirmwarePatchRequestUiState,
        contactEmail: String,
    ) {
        Toast.makeText(this, "Collecting firmware diagnostics...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch(Dispatchers.IO) {
            val result = DebugLogSupport.sendFirmwarePatchRequest(
                context = this@MainActivity,
                contactEmail = contactEmail,
                request = DebugLogSupport.FirmwarePatchRequest(
                    source = request.source.label,
                    target = request.target.label,
                    targetHardwareVersion = request.targetHardwareVersion,
                    targetFirmwareVersion = request.targetFirmwareVersion,
                    wifiHardwareVersion = request.wifiHardwareVersion,
                    wifiFirmwareVersion = request.wifiFirmwareVersion,
                    bleHardwareVersion = request.bleHardwareVersion,
                    bleFirmwareVersion = request.bleFirmwareVersion,
                    relayMessage = request.relayMessage,
                ),
                relayBaseUrl = firmwareRelayBaseUrl(this@MainActivity),
            )
            withContext(Dispatchers.Main) {
                result.onSuccess { logId ->
                    dashboardState = dashboardState.copy(firmwarePatchRequest = null)
                    Toast.makeText(
                        this@MainActivity,
                        "Patch request sent. Reference: $logId",
                        Toast.LENGTH_LONG,
                    ).show()
                }.onFailure { error ->
                    Log.e("Ota", "Could not send firmware patch request", error)
                    dashboardState.firmwarePatchRequest?.let { activeRequest ->
                        dashboardState = dashboardState.copy(
                            firmwarePatchRequest = activeRequest.copy(
                                isSubmitting = false,
                                submissionError = "Could not send the request. Check your connection and try again.",
                            ),
                        )
                    }
                    Toast.makeText(
                        this@MainActivity,
                        "Could not send patch request: ${error.message}",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    private fun OtaTarget.toOtaTargetSelection(): com.fersaiyan.cyanbridge.shared.glasses.OtaTargetSelection = when (this) {
        OtaTarget.V821_WIFI -> com.fersaiyan.cyanbridge.shared.glasses.OtaTargetSelection.V821_WIFI
        OtaTarget.JIELI_BLE -> com.fersaiyan.cyanbridge.shared.glasses.OtaTargetSelection.JIELI_BLE
    }

    /**
     * Get the glasses' Wi-Fi hardware version via BLE syncDeviceInfo.
     * Returns empty string if BLE is not connected.
     */
    private suspend fun getGlassesWifiHardwareVersion(): String {
        if (!BleOperateManager.getInstance().isConnected) return ""
        return kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            LargeDataHandler.getInstance().syncDeviceInfo { _, response ->
                if (cont.isActive) {
                    cont.resume(response?.wifiHardwareVersion ?: "") {}
                }
            }
            // Timeout after 5 seconds
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                kotlinx.coroutines.delay(5000)
                if (cont.isActive) cont.resume("") {}
            }
        }
    }

    /**
     * Get the glasses' Wi-Fi firmware version via BLE syncDeviceInfo.
     */
    private suspend fun getGlassesWifiFirmwareVersion(): String {
        if (!BleOperateManager.getInstance().isConnected) return ""
        return kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            LargeDataHandler.getInstance().syncDeviceInfo { _, response ->
                if (cont.isActive) {
                    cont.resume(response?.wifiFirmwareVersion ?: "") {}
                }
            }
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                kotlinx.coroutines.delay(5000)
                if (cont.isActive) cont.resume("") {}
            }
        }
    }

    /**
     * Read the full [DeviceInfoResponse] from the glasses via BLE syncDeviceInfo.
     * Returns null if BLE is not connected or the request times out.
     *
     * The response contains both BLE identifiers (hardwareVersion, firmwareVersion)
     * and Wi-Fi identifiers (wifiHardwareVersion, wifiFirmwareVersion).
     */
    private suspend fun readGlassesDeviceInfo(): com.oudmon.ble.base.communication.bigData.resp.DeviceInfoResponse? {
        if (!BleOperateManager.getInstance().isConnected) return null
        return withTimeoutOrNull(5_000) {
            suspendCancellableCoroutine { cont ->
                try {
                    LargeDataHandler.getInstance().syncDeviceInfo { _, response ->
                        if (cont.isActive) {
                            cont.resume(response) {}
                        }
                    }
                } catch (error: Exception) {
                    Log.w("DebugOta", "syncDeviceInfo failed while preparing OTA", error)
                    if (cont.isActive) cont.resume(null) {}
                }
            }
        }
    }

    /** Require a live BLE link and a new four-field device-info response between OTA stages. */
    private suspend fun awaitFreshBleReadiness(stage: OtaReadinessStage): Boolean {
        val bleManager = BleOperateManager.getInstance()
        val reportedDeviceAddress = runCatching { DeviceManager.getInstance().deviceAddress }
            .getOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        val deviceAddress = reportedDeviceAddress ?: otaExpectedDeviceAddress
            ?: run {
                Log.e("Ota", "Cannot perform $stage readiness check without the glasses address")
                return false
            }
        otaExpectedDeviceAddress = otaExpectedDeviceAddress ?: deviceAddress
        if (reportedDeviceAddress != null &&
            !reportedDeviceAddress.equals(otaExpectedDeviceAddress, ignoreCase = true)
        ) {
            Log.e(
                "Ota",
                "Refusing $stage readiness for a different BLE device: " +
                    "expected=$otaExpectedDeviceAddress, reported=$reportedDeviceAddress",
            )
            return false
        }

        if (stage == OtaReadinessStage.AFTER_BLE) {
            // Match OTAActivity$dfuOpResult$1: invalidate the cached BLE version before
            // disconnecting, then repopulate it only from the fresh post-update read.
            val application = MyApplication.getInstance()
            application.firmwareVersion = ""
            application.hardwareVersion = ""
            delay(1_000)
            if (bleManager.isConnected) {
                try {
                    bleManager.disconnect()
                } catch (error: Exception) {
                    Log.e("Ota", "Could not disconnect for post-DFU readiness", error)
                    return false
                }
                val disconnected = withTimeoutOrNull(10_000) {
                    while (bleManager.isConnected) delay(100)
                    true
                } ?: false
                if (!disconnected) {
                    Log.e("Ota", "BLE did not disconnect for post-DFU readiness")
                    return false
                }
            }
        }

        if (!bleManager.isConnected) {
            try {
                bleManager.reConnectMac = deviceAddress
            } catch (_: Throwable) {
                // Optional SDK property; connectDirectly still uses the explicit address.
            }
            try {
                bleManager.connectDirectly(deviceAddress)
            } catch (error: Exception) {
                Log.e("Ota", "Could not reconnect for $stage readiness", error)
                return false
            }
        }

        val connected = withTimeoutOrNull(30_000) {
            while (!bleManager.isConnected) delay(250)
            true
        } ?: false
        if (!connected) {
            Log.e("Ota", "BLE did not reconnect for $stage readiness")
            return false
        }

        val reconnectedDeviceAddress = runCatching { DeviceManager.getInstance().deviceAddress }
            .getOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        if (reconnectedDeviceAddress != null &&
            !reconnectedDeviceAddress.equals(otaExpectedDeviceAddress, ignoreCase = true)
        ) {
            Log.e(
                "Ota",
                "Refusing $stage readiness after reconnect for a different BLE device: " +
                    "expected=$otaExpectedDeviceAddress, reported=$reconnectedDeviceAddress",
            )
            return false
        }

        val deviceInfo = readGlassesDeviceInfo() ?: run {
            Log.e("Ota", "Fresh syncDeviceInfo failed for $stage")
            return false
        }
        val ready = listOf(
            deviceInfo.hardwareVersion,
            deviceInfo.firmwareVersion,
            deviceInfo.wifiHardwareVersion,
            deviceInfo.wifiFirmwareVersion,
        ).all { !it.isNullOrBlank() }
        if (!ready) {
            Log.e("Ota", "Fresh syncDeviceInfo was incomplete for $stage")
            return false
        }
        MyApplication.getInstance().hardwareVersion = deviceInfo.hardwareVersion.orEmpty()
        MyApplication.getInstance().firmwareVersion = deviceInfo.firmwareVersion.orEmpty()
        Log.i(
            "Ota",
            "Fresh $stage readiness passed: ble=${deviceInfo.hardwareVersion}/${deviceInfo.firmwareVersion}, " +
                "wifi=${deviceInfo.wifiHardwareVersion}/${deviceInfo.wifiFirmwareVersion}",
        )
        return true
    }

    private fun startLivePreview() {
        Log.i("LivePreview", "========================================")

        if (rejectHeyCyanOnlyFeature("Live preview")) return

        if (!BuildConfig.DEBUG) {
            Log.w("LivePreview", "Passive live preview is unavailable outside debug builds")
            return
        }
        if (!hasBluetooth(this) || !hasWifiP2pPermission(this)) {
            ensureGlassesTransportPermissions("live preview") {
                startLivePreview()
            }
            return
        }
        Log.i("LivePreview", "  BUTTON TAP: Start Live Preview")
        Log.i("LivePreview", "  BLE connected: ${BleOperateManager.getInstance().isConnected}")
        Log.i("LivePreview", "  Manager active: ${livePreviewManager.isActive}")
        Log.i("LivePreview", "========================================")

        if (livePreviewManager.isActive) {
            Log.w("LivePreview", "Manager already active, ignoring tap")
            return
        }

        if (downloadInProgress || downloadAttemptJob?.isActive == true || downloadP2pConnected) {
            Log.w("LivePreview", "Refusing to start while a media-sync P2P session owns the connection")
            Toast.makeText(
                this,
                "Stop the current P2P sync before starting live preview.",
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        if (AutoAudioCaptureService.isRunning()) {
            Toast.makeText(this, "Stop auto audio capture before starting live preview.", Toast.LENGTH_LONG).show()
            return
        }

        val livePreviewLease = acquireExclusiveGlassesSession(GlassesSession.LIVE_PREVIEW) ?: return
        livePreviewSessionLease = livePreviewLease

        livePreviewManager.start {
            releaseExclusiveGlassesSession(livePreviewLease)
        }
    }

    private fun startEyevueLivePreview() {
        if (!EyevueLivePreviewPolicy.isSupported(Build.VERSION.SDK_INT)) {
            Toast.makeText(this, "Eyevue live preview requires Android 10 or newer.", Toast.LENGTH_LONG).show()
            return
        }
        if (!hasBluetooth(this) || !hasWifiP2pPermission(this)) {
            ensureGlassesTransportPermissions("Eyevue live preview") {
                startEyevueLivePreview()
            }
            return
        }
        val manager = getOrCreateEyevueManager()
        if (!manager.isConnected()) {
            Toast.makeText(this, "Connect to Eyevue over Bluetooth first.", Toast.LENGTH_LONG).show()
            return
        }
        val liveManager = getOrCreateEyevueLivePreviewManager()
        if (liveManager.isActive) return
        if (eyevueMediaJob?.isActive == true) {
            Toast.makeText(this, "Stop Eyevue media sync before starting live preview.", Toast.LENGTH_LONG).show()
            return
        }
        if (AutoAudioCaptureService.isRunning()) {
            Toast.makeText(this, "Stop auto audio capture before starting live preview.", Toast.LENGTH_LONG).show()
            return
        }

        val lease = acquireExclusiveGlassesSession(GlassesSession.LIVE_PREVIEW) ?: return
        livePreviewSessionLease = lease
        liveManager.start(
            onSessionFinished = {
                releaseExclusiveGlassesSession(lease)
                if (livePreviewSessionLease === lease) livePreviewSessionLease = null
            },
        )
    }

    private fun observeLivePreviewState() {
        lifecycleScope.launch {
            var lastLabel = ""
            livePreviewManager.uiState.collect { lp ->
                if (lp.stateLabel != lastLabel) {
                    Log.i("LivePreview", "Dashboard state: '${lp.stateLabel}' | scanning=${lp.isScanning} | playing=${lp.isPlaying} | url=${lp.streamUrl}")
                    lastLabel = lp.stateLabel
                }
                dashboardState = dashboardState.copy(
                    livePreview = com.fersaiyan.cyanbridge.shared.glasses.LivePreviewUiState(
                        isAvailable = BuildConfig.DEBUG && !isEyevueSelected() && !isTuneBudsSelected(),
                        stateLabel = lp.stateLabel,
                        detail = lp.detail,
                        isScanning = lp.isScanning,
                        isPlaying = lp.isPlaying,
                        streamUrl = lp.streamUrl,
                        canStart = lp.canStart,
                        canStop = lp.canStop,
                    ),
                )

                if (lp.isPlaying && lp.streamUrl != null) {
                    Log.i("LivePreview", "Stream playing, showing ExoPlayer dialog: ${lp.streamUrl}")
                    showRtspPlayerDialog(lp.streamUrl)
                } else {
                    livePreviewDialog?.let { dialog ->
                        livePreviewDialog = null
                        dialog.dismiss()
                    }
                }
            }
        }
    }

    private fun stopLivePreview() {
        livePreviewManager.stop()
        livePreviewDialog?.let { dialog ->
            livePreviewDialog = null
            dialog.dismiss()
        }
    }

    private fun startWifiAdbDebug() {
        if (!isHeyCyanSelected()) return
        if (!BuildConfig.DEBUG || wifiAdbDebugController.isActive) return
        if (!hasBluetooth(this) || !hasWifiP2pPermission(this)) {
            ensureGlassesTransportPermissions("Wi-Fi ADB debug") {
                startWifiAdbDebug()
            }
            return
        }
        if (!BleOperateManager.getInstance().isConnected) {
            Toast.makeText(this, "Connect the glasses over Bluetooth first.", Toast.LENGTH_LONG).show()
            return
        }
        if (AutoAudioCaptureService.isRunning()) {
            Toast.makeText(this, "Stop automatic capture before starting Wi-Fi ADB.", Toast.LENGTH_LONG).show()
            return
        }
        if (MeetingCapturePrefs.getState(this).isRecording) {
            Toast.makeText(this, "Stop recording before starting Wi-Fi ADB.", Toast.LENGTH_LONG).show()
            return
        }
        val lease = acquireExclusiveGlassesSession(GlassesSession.WIFI_ADB_DEBUG) ?: return
        wifiAdbDebugSessionLease = lease
        wifiAdbDebugController.start {
            releaseExclusiveGlassesSession(lease)
        }
    }

    private fun observeWifiAdbDebugState() {
        lifecycleScope.launch {
            wifiAdbDebugController.state.collect { runtime ->
                dashboardState = dashboardState.copy(
                    wifiAdbDebug = WifiAdbDebugUiState(
                        isAvailable = BuildConfig.DEBUG && isHeyCyanSelected() && runtime.isAvailable,
                        stateLabel = runtime.stateLabel,
                        detail = runtime.detail,
                        glassesIp = runtime.glassesIp,
                        relayEndpoints = runtime.relayEndpoints,
                        preferredCommand = runtime.preferredCommand,
                        canStart = runtime.canStart,
                        canStop = runtime.canStop,
                    ),
                )
            }
        }
    }

    private fun showRtspPlayerDialog(
        streamUrl: String,
        player: androidx.media3.exoplayer.ExoPlayer? = livePreviewManager.getPlayer(),
        onClose: () -> Unit = livePreviewManager::stop,
    ) {
        Log.i("LivePreview", "showRtspPlayerDialog: $streamUrl")
        if (livePreviewDialog?.isShowing == true) {
            Log.d("LivePreview", "showRtspPlayerDialog: dialog is already visible")
            return
        }
        val activePlayer = player
        if (activePlayer == null) {
            Log.e("LivePreview", "showRtspPlayerDialog: player is null!")
            return
        }

        val dialogView = android.widget.FrameLayout(this).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
            )
        }

        val playerView = androidx.media3.ui.PlayerView(this).apply {
            this.player = activePlayer
            useController = true
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                600,
            )
        }
        dialogView.addView(playerView)

        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Live Preview")
            .setMessage(streamUrl)
            .setView(dialogView)
            .setPositiveButton("Close", null)
            .create()
        dialog.setOnDismissListener {
            if (livePreviewDialog === dialog) {
                Log.i("LivePreview", "Dialog: dismissed")
                livePreviewDialog = null
                onClose()
            }
        }
        livePreviewDialog = dialog
        dialog.show()
        Log.i("LivePreview", "Dialog: shown")
    }
    
    private fun controlVideoRecording(start: Boolean) {
        if (rejectHeyCyanOnlyFeature("Video recording")) return
        if (isGlassesCommandBlocked("video recording command")) return
        val permit = acquireBackgroundGlassesCommand("video recording command") ?: return
        val value = if (start) 0x02 else 0x03

        // While video is recording, pause the auto audio loop.
        if (start) {
            AutoAudioCapturePrefs.setPausedForVideo(this, true)
            GlassesMediaPrefs.setVideoRecording(this, true) // optimistic
        }

        try {
            LargeDataHandler.getInstance().glassesControl(
                byteArrayOf(0x02, 0x01, value.toByte())
            ) { _, rsp ->
                try {
                    if (rsp.dataType == 1) {
                        if (rsp.errorCode == 0) {
                            when (rsp.workTypeIng) {
                                2 -> {
                                    // Glasses are recording video
                                    GlassesMediaPrefs.setVideoRecording(this, true)
                                    AutoAudioCapturePrefs.setPausedForVideo(this, true)
                                }
                                else -> {
                                    // Anything other than 2 means not actively recording video.
                                    GlassesMediaPrefs.setVideoRecording(this, false)
                                    AutoAudioCapturePrefs.setPausedForVideo(this, false)
                                }
                            }
                        } else {
                            // Command failed; revert optimistic state.
                            if (start) {
                                GlassesMediaPrefs.setVideoRecording(this, false)
                                AutoAudioCapturePrefs.setPausedForVideo(this, false)
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
            Log.e("VideoRecording", "Failed to send video recording command", e)
        }
    }
    
    private fun controlAudioRecording(start: Boolean) {
        if (rejectHeyCyanOnlyFeature("On-glasses audio recording")) return
        if (isGlassesCommandBlocked("audio recording command")) return
        val permit = acquireBackgroundGlassesCommand("audio recording command") ?: return
        val value = if (start) 0x08 else 0x0c
        try {
            LargeDataHandler.getInstance().glassesControl(
                byteArrayOf(0x02, 0x01, value.toByte())
            ) { _, it ->
                try {
                    if (it.dataType == 1) {
                        if (it.errorCode == 0) {
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
                    } else {
                        //Execute start and end
                    }
                } finally {
                    GlassesSessionCoordinator.releaseBackgroundCommand(permit)
                }
            }
            warnIfBackgroundGlassesCommandTimesOut(permit)
        } catch (e: Exception) {
            GlassesSessionCoordinator.releaseBackgroundCommand(permit)
            Log.e("AudioRecording", "Failed to send audio recording command", e)
        }
    }

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
                    GlassesSessionCoordinator.clearForDisconnectedDevice()
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
                CliRelayClient.chat(
                    context = this,
                    chatId = "glasses_${System.currentTimeMillis()}",
                    prompt = userPrompt,
                    messages = messages,
                    modelOverride = ProSubscriptionAiPrefs.getRequestsModel(this),
                ).getOrElse {
                    "Pro endpoint error: ${it.message ?: "unknown error"}"
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
                ).getOrElse { "Endpoint unavailable: ${it.message ?: "unknown error"}" }
            }
        }.trim()
    }

    private fun validateSelectedLocalMediaModel(mediaRequested: Boolean): String? {
        if (RemoteOpenAiPrefs.isActive(this)) return null

        val selected = LocalModelStorageRepository.resolveSelectedModel(this)
            ?: return "No local model selected. Install or select a local model in Settings."
        val settings = LocalModelSettingsRepository.getForModel(this, selected.id)
        if (mediaRequested && settings.modelRuntime != LocalModelRuntime.LITERT) {
            return "The selected local model cannot process media. Select a multimodal LiteRT model."
        }
        return null
    }

    private fun resolveImageQuestionPrompt(userQuestion: String?): ResolvedImageQuestionPrompt {
        return ImageQuestionPromptResolver.resolve(
            settings = ImageQuestionPreferences.get(this),
            userQuestion = userQuestion,
        )
    }

    private fun triggerChosenProviderImageQuery(
        imagePath: String,
        providerType: AgentProviderType,
        resolvedPrompt: ResolvedImageQuestionPrompt,
        onReplySpoken: (() -> Unit)? = null,
    ) {
        Log.i("AIHijack", "Running image query with RAG disabled for chosen provider $providerType: $imagePath")

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
                        val visionResult = CliRelayClient.imageQuery(
                            context = this@MainActivity,
                            imagePath = imagePath,
                            prompt = resolvedPrompt.forRoute(ImageQuestionRoute.PRO_RELAY),
                            modelOverride = ProSubscriptionAiPrefs.getQuestionsModel(this@MainActivity),
                        )

                        if (visionResult.isFailure) {
                            val errorMsg = visionResult.exceptionOrNull()?.message ?: "unknown error"
                            Log.e("AIHijack", "Image query failed: $errorMsg")
                            runOnUiThread {
                                Toast.makeText(this@MainActivity, "Vision error: ${errorMsg.take(80)}", Toast.LENGTH_LONG).show()
                            }
                            "I couldn't analyze the image. Please try again."
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
                            userPrompt = resolvedPrompt.forRoute(ImageQuestionRoute.LOCAL_GEMMA),
                            providerType = AgentProviderType.LOCAL_AGENT,
                            ragProfile = RagProfile.NONE,
                            imagePaths = listOf(imagePath),
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
                            val errorMsg = visionResult.exceptionOrNull()?.message ?: "unknown error"
                            Log.e("AIHijack", "Image query failed: $errorMsg")
                            runOnUiThread {
                                Toast.makeText(this@MainActivity, "Vision error: ${errorMsg.take(80)}", Toast.LENGTH_LONG).show()
                            }
                            "I couldn't analyze the image. Please try again."
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
                    // Match the vendor AI-chat path so the selected clarity controls the
                    // generated thumbnail instead of forcing the Home quick-preview mode.
                    LargeDataHandler.getInstance().glassesControl(
                        byteArrayOf(0x02, 0x01, 0x06, thumbnailSize, thumbnailSize),
                    ) { _, response ->
                        Log.i(
                            "ImageQuestionTransfer",
                            "[$sourceTag] AI capture command response dataType=${response.dataType} " +
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
                val imageBytes = manager.capturePhotoForAi()
                    ?: throw IOException("Eyevue photo transfer timed out")
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
                    ?: throw IOException("TuneBuds photo transfer timed out")
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
            var hotspot: TuneBudsLocalHotspot? = null
            var tempDir: File? = null
            try {
                Log.i("AIHijack", "[$sourceTag] TuneBuds Detailed requested – using Wi-Fi high-quality path")
                // 1. Trigger glasses camera in recording mode (0) – saves JPEG to flash
                withContext(Dispatchers.IO) { manager.takePhotoBlocking() }
                Log.i("ImageQuestionTransfer", "[$sourceTag] TuneBuds takePhoto (0) sent, waiting for save")
                kotlinx.coroutines.delay(2500)

                hotspot = TuneBudsLocalHotspot(this@MainActivity)
                tempDir = File(cacheDir, "tunebuds_ai_high_${System.currentTimeMillis()}").apply { mkdirs() }
                val credentials = withContext(Dispatchers.IO) { hotspot.start() }
                Log.i("ImageQuestionTransfer", "[$sourceTag] TuneBuds hotspot started ssid=${credentials.ssid}")

                val baseUrl = withContext(Dispatchers.IO) {
                    manager.startFileManager(credentials.ssid, credentials.password, credentials.channel)
                } ?: throw IOException("TuneBuds did not report its media server address")
                Log.i("ImageQuestionTransfer", "[$sourceTag] TuneBuds media endpoint $baseUrl")

                val sync = TuneBudsMediaSync(manager, hotspot, tempDir)
                // Poll manifest for a fresh photo (glasses may need a moment to index the new file)
                var photoItem: com.fersaiyan.cyanbridge.devices.tunebuds.TuneBudsMediaItem? = null
                for (attempt in 0 until 12) {
                    val items = withContext(Dispatchers.IO) { sync.fetchManifest(baseUrl) }
                    val photos = items.filter { it.type == TuneBudsMediaType.PHOTO }
                    Log.i("ImageQuestionTransfer", "[$sourceTag] TuneBuds manifest attempt ${attempt + 1}: ${photos.size} photos")
                    if (photos.isNotEmpty()) {
                        // Pick the most recently created photo – manifest is typically newest-last;
                        // sorting by remoteName descending is a best-effort for timestamped filenames.
                        photoItem = photos.maxByOrNull { it.remoteName } ?: photos.last()
                        break
                    }
                    if (attempt < 11) kotlinx.coroutines.delay(1000)
                }
                val item = photoItem ?: throw IOException("No photos found on TuneBuds after capture")

                Log.i("ImageQuestionTransfer", "[$sourceTag] TuneBuds downloading ${item.remoteName} via Wi-Fi")
                val downloaded = withContext(Dispatchers.IO) { sync.downloadSingle(baseUrl, item) }
                val imageFile = File(cacheDir, "TuneBuds_AI_HQ_${System.currentTimeMillis()}.jpg")
                withContext(Dispatchers.IO) { downloaded.copyTo(imageFile, overwrite = true) }

                withContext(Dispatchers.Main) {
                    onImageReadyForQuestion(
                        imagePath = imageFile.absolutePath,
                        source = ImageQuestionSource.HIGH_QUALITY,
                        transferDurationMs = System.currentTimeMillis() - startedAt,
                    )
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
                withContext(Dispatchers.IO) {
                    runCatching { manager.finishTransfer() }
                    runCatching { hotspot?.stop() }
                    tempDir?.let { runCatching { it.deleteRecursively() } }
                }
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
            var thumbnailTransferStarted = false
            try {
                runCatching {
                    FileOutputStream(file, false).use { }
                }.onFailure { error ->
                    Log.e("AIHijack", "[$sourceTag] Failed to prepare thumbnail file: ${error.message}", error)
                }

                thumbnailTransferStarted = true
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
            } finally {
                imageThumbnailRequestInProgress.set(false)
                if (!thumbnailTransferStarted) {
                    GlassesSessionCoordinator.releaseBackgroundCommand(permit)
                }
            }
        }
    }

    private suspend fun receivePictureThumbnail(
        file: File,
        sourceTag: String,
        permit: BackgroundGlassesCommandPermit,
    ): Boolean {
        val gotChunk = java.util.concurrent.atomic.AtomicBoolean(false)
        val completed = java.util.concurrent.atomic.AtomicBoolean(false)
        val callbackCount = java.util.concurrent.atomic.AtomicInteger(0)
        val totalBytes = java.util.concurrent.atomic.AtomicLong(0L)
        val transferResult = CompletableDeferred<Boolean>()
        val writeLock = Any()
        val startedAtMs = android.os.SystemClock.elapsedRealtime()

        val thumbCallback: (Int, Boolean, ByteArray?) -> Unit = { packetType, isComplete, data ->
            val callbackIndex = callbackCount.incrementAndGet()
            val dataBytes = data?.size ?: 0
            if (data != null && data.isNotEmpty()) {
                runCatching {
                    synchronized(writeLock) {
                        FileOutputStream(file, true).use { it.write(data) }
                    }
                    gotChunk.set(true)
                    totalBytes.addAndGet(dataBytes.toLong())
                }.onFailure { error ->
                    Log.e("AIHijack", "[$sourceTag] Failed to write thumbnail chunk: ${error.message}", error)
                }
            }
            if (callbackIndex == 1 || callbackIndex % 32 == 0 || isComplete) {
                Log.i(
                    "ImageQuestionTransfer",
                    "[$sourceTag] Thumbnail callback #$callbackIndex packetType=$packetType complete=$isComplete " +
                        "payloadBytes=$dataBytes totalBytes=${totalBytes.get()} " +
                        "elapsedMs=${android.os.SystemClock.elapsedRealtime() - startedAtMs}",
                )
            }

            if (isComplete && completed.compareAndSet(false, true)) {
                Log.i(
                    "ImageQuestionTransfer",
                    "[$sourceTag] Thumbnail completion received chunks=${callbackCount.get()} " +
                        "bytes=${totalBytes.get()} hasData=${gotChunk.get()}",
                )
                transferResult.complete(gotChunk.get())
                GlassesSessionCoordinator.releaseBackgroundCommand(permit)
            }
        }

        Log.i("AIHijack", "[$sourceTag] Requesting BLE thumbnail")
        if (isGlassesCommandBlocked("AI thumbnail request")) return false
        try {
            LargeDataHandler.getInstance().getPictureThumbnails(thumbCallback)
            Log.i("ImageQuestionTransfer", "[$sourceTag] getPictureThumbnails request submitted")
        } catch (e: Exception) {
            GlassesSessionCoordinator.releaseBackgroundCommand(permit)
            throw e
        }
        val receivedAnyData = withTimeoutOrNull(IMAGE_THUMBNAIL_TRANSFER_TIMEOUT_MS) {
            transferResult.await()
        } ?: false
        if (!receivedAnyData) {
            Log.w(
                "ImageQuestionTransfer",
                "[$sourceTag] BLE thumbnail request timed out chunks=${callbackCount.get()} bytes=${totalBytes.get()} " +
                    "completed=${completed.get()} connected=${BleOperateManager.getInstance().isConnected} " +
                    "activeSession=${GlassesSessionCoordinator.currentSession()}; keeping SDK response slot isolated",
            )
            return false
        }

        return isDecodableImageFile(file).also { valid ->
            if (!valid) {
                Log.w(
                    "AIHijack",
                    "[$sourceTag] Thumbnail is not a decodable image (${file.length()} bytes)",
                )
            }
        }
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

        CoroutineScope(Dispatchers.IO).launch {
            var initialQuestion = pendingVoiceImageQuestion
            pendingVoiceImageQuestion = null
            if (initialQuestion.isNullOrBlank()) {
                val parallelDeferred = activeParallelAudioQuestionDeferred
                activeParallelAudioQuestionDeferred = null
                activeParallelAudioQuestionJob = null
                if (parallelDeferred != null) {
                    Log.i("ImageQuestion", "Awaiting parallel audio question recording...")
                    initialQuestion = parallelDeferred.await()
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
                    initialQuestion = captureOptionalImageQuestionFromBluetoothMic(
                        timeoutMs = IMAGE_QUESTION_INITIAL_LISTENING_TIMEOUT_MS,
                    )
                }
            } else {
                cancelParallelAudioQuestion()
            }
            val externalAutomation = usesExternalImageAutomation()
            fun offerFollowUp() {
                lifecycleScope.launch {
                    delay(500L)
                    val spokenQuestion = captureOptionalImageQuestionFromBluetoothMic(
                        timeoutMs = IMAGE_QUESTION_INITIAL_LISTENING_TIMEOUT_MS,
                    )
                    if (!spokenQuestion.isNullOrBlank()) {
                        triggerAssistantImageQuery(
                            imagePath = imagePath,
                            userQuestion = spokenQuestion,
                            source = source,
                            onReplySpoken = ::offerFollowUp,
                        )
                    }
                }
            }
            triggerAssistantImageQuery(
                imagePath = imagePath,
                userQuestion = initialQuestion,
                source = source,
                onReplySpoken = if (externalAutomation) null else ::offerFollowUp,
            )

            // The default assistant owns external response playback; CyanBridge follow-ups are
            // only offered for Local and Pro responses that CyanBridge itself speaks.
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

    private fun cancelParallelAudioQuestion() {
        val job = activeParallelAudioQuestionJob
        activeParallelAudioQuestionJob = null
        job?.cancel()
        val deferred = activeParallelAudioQuestionDeferred
        activeParallelAudioQuestionDeferred = null
        if (deferred?.isCompleted == false) {
            deferred.complete(null)
        }
    }

    private fun startParallelAudioQuestionIfEligible(offerSpokenQuestion: Boolean) {
        cancelParallelAudioQuestion()
        if (
            offerSpokenQuestion &&
            pendingVoiceImageQuestion.isNullOrBlank() &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        ) {
            val deferred = kotlinx.coroutines.CompletableDeferred<String?>()
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
                    deferred.complete(spokenQuestion)
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

    private suspend fun captureOptionalImageQuestionFromBluetoothMic(timeoutMs: Long): String? {
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val audioManager = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                Log.i(
                    "ImageQuestionAudio",
                    "Starting image-question microphone timeoutMs=$timeoutMs route=${audioRouteSummary(audioManager)}",
                )
                var recognizer: SpeechRecognizer? = null
                var timeoutJob: Job? = null
                var finished = false
                var heardSpeech = false

                fun cleanup() {
                    runCatching {
                        recognizer?.destroy()
                    }
                    recognizer = null

                    runCatching {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            audioManager.clearCommunicationDevice()
                        }
                        audioManager.isBluetoothScoOn = false
                        audioManager.stopBluetoothSco()
                        audioManager.mode = android.media.AudioManager.MODE_NORMAL
                    }
                    Log.i("ImageQuestionAudio", "Image-question microphone route cleared: ${audioRouteSummary(audioManager)}")
                }

                fun finish(result: String?) {
                    if (finished) return
                    finished = true
                    timeoutJob?.cancel()
                    timeoutJob = null
                    val cleaned = result?.trim()?.takeIf { it.isNotBlank() }
                    Log.i(
                        "ImageQuestionAudio",
                        "Image-question microphone finished heardSpeech=$heardSpeech resultLength=${cleaned?.length ?: 0}",
                    )

                    lifecycleScope.launch {
                        playImageQuestionTone(android.media.ToneGenerator.TONE_PROP_BEEP2)
                        cleanup()
                        if (cont.isActive) {
                            cont.resume(cleaned)
                        }
                    }
                }

                lifecycleScope.launch {
                    val routeReady = startBluetoothMicRouteAndAwait(audioManager)
                    Log.i(
                        "ImageQuestionAudio",
                        "Image-question Bluetooth route ready=$routeReady route=${audioRouteSummary(audioManager)}",
                    )
                    playImageQuestionTone(android.media.ToneGenerator.TONE_PROP_BEEP)
                    speakImageQuestionCue()
                    if (finished || !cont.isActive) return@launch

                    Log.i("ImageQuestionAudio", "Cue complete; creating speech recognizer")
                    recognizer = SpeechRecognizer.createSpeechRecognizer(this@MainActivity)
                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, recognitionLanguageTag())
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, recognitionLanguageTag())
                        // Once speech begins, wait for Android's end-of-speech signal rather
                        // than imposing a fixed recording deadline.
                        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2_000L)
                        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2_000L)
                        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 500L)
                    }

                    recognizer?.setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) {
                            Log.i("ImageQuestionAudio", "Image-question recognizer ready")
                        }
                        override fun onBeginningOfSpeech() {
                            heardSpeech = true
                            Log.i("ImageQuestionAudio", "Image-question speech detected")
                            timeoutJob?.cancel()
                            timeoutJob = null
                        }
                        override fun onRmsChanged(rmsdB: Float) {}
                        override fun onBufferReceived(buffer: ByteArray?) {}
                        override fun onEndOfSpeech() {}

                        override fun onError(error: Int) {
                            Log.i("AIHijack", "Image question listener ended with error code=$error")
                            finish(null)
                        }

                        override fun onResults(results: Bundle?) {
                            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            Log.i("ImageQuestionAudio", "Image-question recognizer resultCount=${matches?.size ?: 0}")
                            finish(matches?.firstOrNull())
                        }

                        override fun onPartialResults(partialResults: Bundle?) {}
                        override fun onEvent(eventType: Int, params: Bundle?) {}
                    })

                    timeoutJob = CoroutineScope(Dispatchers.Main).launch {
                        delay(timeoutMs)
                        if (!heardSpeech) {
                            finish(null)
                        }
                    }

                    recognizer?.startListening(intent)
                }

                cont.invokeOnCancellation {
                    // lifecycleScope is already cancelled when the Activity is destroyed, so
                    // finish() cannot rely on launching its asynchronous cleanup in that case.
                    finished = true
                    timeoutJob?.cancel()
                    timeoutJob = null
                    cleanup()
                }
            }
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

    private suspend fun speakImageQuestionCue() {
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
            val cue = ImageQuestionDefaults.questionCueForLanguage(questionSettings.appLanguageTag)
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
                val device = audioManager.availableCommunicationDevices.firstOrNull {
                    it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                        it.type == android.media.AudioDeviceInfo.TYPE_BLE_HEADSET
                }
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
        val recognizer = try {
            SpeechRecognizer.createSpeechRecognizer(this)
        } catch (error: Exception) {
            Log.e("AIHijack", "Could not create voice recognizer", error)
            if (voiceQueryInProgress.compareAndSet(voiceQueryToken, null)) {
                finishAiQuestionForegroundWork()
            }
            Toast.makeText(this, "Speech recognition is unavailable", Toast.LENGTH_SHORT).show()
            return
        }
        val audioRouteOwner = VoiceAudioRouteOwner(voiceQueryToken, audioManager)
        activeVoiceAudioRoute.set(audioRouteOwner)
        activeVoiceRecognizer.set(recognizer)

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

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, recognitionLanguageTag())
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, recognitionLanguageTag())
        }
        var recognitionAttempts = 0

        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Log.i("ImageQuestionAudio", "Voice-query recognizer ready after listening cue")
            }

            override fun onBeginningOfSpeech() {
                Log.i("ImageQuestionAudio", "Voice-query speech detected attempt=$recognitionAttempts")
            }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}

            override fun onError(error: Int) {
                val isTransientNoSpeech = error == SpeechRecognizer.ERROR_NO_MATCH ||
                    error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                if (isTransientNoSpeech && recognitionAttempts == 1) {
                    recognitionAttempts += 1
                    Log.w(
                        "AIHijack",
                        "Voice query recognition returned transient error code=$error; " +
                            "retrying attempt=$recognitionAttempts route=${audioRouteSummary(audioManager)}",
                    )
                    Toast.makeText(this@MainActivity, "I didn't catch that. Listening again…", Toast.LENGTH_SHORT).show()
                    lifecycleScope.launch {
                        var retryStarted = false
                        try {
                            playImageQuestionTone(android.media.ToneGenerator.TONE_PROP_BEEP)
                            delay(VOICE_RECOGNITION_RETRY_DELAY_MS)
                            if (isFinishing || isDestroyed) return@launch
                            val routeReady = startBluetoothMicRouteAndAwait(audioManager)
                            Log.i(
                                "ImageQuestionAudio",
                                "Restarting voice recognizer attempt=$recognitionAttempts routeReady=$routeReady",
                            )
                            runCatching { recognizer.startListening(intent) }
                                .onSuccess { retryStarted = true }
                                .onFailure { retryError ->
                                    Log.e("AIHijack", "Voice query recognition retry could not start", retryError)
                                }
                        } finally {
                            // lifecycleScope can be cancelled while the retry cue/delay is active.
                            // Do not leave the recognizer, SCO route, or foreground work alive when
                            // no second listening session was actually started.
                            if (!retryStarted) {
                                recognizer.destroy()
                                activeVoiceRecognizer.compareAndSet(recognizer, null)
                                stopSco()
                                finishVoiceQueryWork()
                            }
                        }
                    }
                    return
                }
                val message = if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                    "Microphone permission is required for voice questions"
                } else if (isTransientNoSpeech) {
                    "I couldn't hear a voice question. Please try again."
                } else {
                    "Voice query failed: $error"
                }
                Log.w(
                    "AIHijack",
                    "Voice query recognition failed with error code=$error " +
                        "attempt=$recognitionAttempts route=${audioRouteSummary(audioManager)}",
                )
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
                recognizer.destroy()
                activeVoiceRecognizer.compareAndSet(recognizer, null)
                stopSco()
                finishVoiceQueryWork()
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val prompt = matches?.firstOrNull()?.trim().orEmpty()

                if (prompt.isBlank()) {
                    recognizer.destroy()
                    activeVoiceRecognizer.compareAndSet(recognizer, null)
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
                                    ).getOrElse { "Relay unavailable: ${it.message ?: "unknown error"}" }
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
                        activeVoiceRecognizer.compareAndSet(recognizer, null)
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

                recognizer.destroy()
                activeVoiceRecognizer.compareAndSet(recognizer, null)
            }

            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val cueUtteranceId = "voice_listening_${System.nanoTime()}"
        val listeningStarted = AtomicBoolean(false)
        val setupAborted = AtomicBoolean(false)
        fun abortVoiceRecognitionSetup(reason: String, error: Throwable? = null) {
            if (!setupAborted.compareAndSet(false, true)) return
            Log.e("AIHijack", "Voice recognizer setup aborted: $reason", error)
            ttsDoneCallbacks.remove(cueUtteranceId)
            recognizer.destroy()
            activeVoiceRecognizer.compareAndSet(recognizer, null)
            stopSco()
            finishVoiceQueryWork()
        }
        fun startListeningAfterCue(reason: String) {
            if (setupAborted.get() || !listeningStarted.compareAndSet(false, true)) return
            if (setupAborted.get()) return
            val startJob = lifecycleScope.launch {
                var started = false
                try {
                    // Keep only a minimal route-transition gap so speech immediately after the cue
                    // is not clipped.
                    delay(VOICE_CUE_BLUETOOTH_TAIL_MS)
                    Log.i("ImageQuestionAudio", "Starting voice recognizer after listening cue reason=$reason")
                    recognitionAttempts = 1
                    recognizer.startListening(intent)
                    started = true
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    abortVoiceRecognitionSetup("startListening failed", error)
                } finally {
                    if (!started) abortVoiceRecognitionSetup("cancelled before startListening completed")
                }
            }
            // A lifecycleScope launch can already be cancelled before its block is entered.
            startJob.invokeOnCompletion { cause ->
                if (cause is CancellationException) {
                    abortVoiceRecognitionSetup("cancelled before recognizer startup ran")
                }
            }
        }
        val cueJob = lifecycleScope.launch {
            try {
                val routeReady = startBluetoothMicRouteAndAwait(audioManager)
                Log.i(
                    "ImageQuestionAudio",
                    "Voice-query Bluetooth route ready=$routeReady route=${audioRouteSummary(audioManager)}",
                )
                configureTtsForVoiceCommunication("voice-query listening cue")
                val languageTag = recognitionLanguageTag()
                speak(
                    text = ImageQuestionDefaults.listeningCueForLanguage(languageTag),
                    languageTag = languageTag,
                    utteranceId = cueUtteranceId,
                    onDone = { startListeningAfterCue("tts callback") },
                    streamType = android.media.AudioManager.STREAM_VOICE_CALL,
                )
                // Do not leave Test Voice unresponsive if a TTS engine never reports completion.
                delay(VOICE_CUE_CALLBACK_TIMEOUT_MS)
                ttsDoneCallbacks.remove(cueUtteranceId)
                startListeningAfterCue("tts callback timeout")
            } finally {
                if (!listeningStarted.get()) {
                    ttsDoneCallbacks.remove(cueUtteranceId)
                    abortVoiceRecognitionSetup("cancelled before listening was scheduled")
                }
            }
        }
        cueJob.invokeOnCompletion { cause ->
            if (cause is CancellationException && !listeningStarted.get()) {
                abortVoiceRecognitionSetup("cancelled before voice cue setup ran")
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
        source: ImageQuestionSource = ImageQuestionSourcePolicy.defaultSource(),
        onReplySpoken: (() -> Unit)? = null,
    ) {
        // Debounce: prevent duplicate requests within 5 seconds
        val now = System.currentTimeMillis()
        if (now - lastImageQueryAtMs < 5000) {
            Log.w("AIHijack", "Image query debounced (last was ${now - lastImageQueryAtMs}ms ago)")
            return
        }
        
        // Guard against concurrent requests
        if (!imageQueryInProgress.compareAndSet(false, true)) {
            Log.w("AIHijack", "Image query already in progress; treating duplicate action as barge-in")
            cancelLocalStreamingSpeech("duplicate image-query action")
            imageQueryInProgress.set(false)
            return
        }
        beginAiQuestionForegroundWork("Analyzing glasses image")
        
        lastImageQueryAtMs = now
        val resolvedPrompt = resolveImageQuestionPrompt(userQuestion)

        val externalReason = externalImageAutomationUnsupportedReason()
        if (externalReason != null) {
            Toast.makeText(this, externalReason, Toast.LENGTH_LONG).show()
            if (usesExternalAssistantUi() && ExternalAssistantAutomationInspector.inspect(this).phoneLocked) {
                speak(externalReason)
            }
            imageQueryInProgress.set(false)
            finishAiQuestionForegroundWork()
            return
        }
        
        val internalProvider = when (currentAssistantRoute()) {
            GlassesAssistantRoute.LOCAL,
            GlassesAssistantRoute.PRO,
            GlassesAssistantRoute.TASKER_EXTERNAL_UI -> MediaInferenceRoutingPolicy.resolve(this)
            GlassesAssistantRoute.PHONE_ASSISTANT -> null
        }
        if (internalProvider != null) {
            triggerChosenProviderImageQuery(
                imagePath = imagePath,
                providerType = internalProvider,
                resolvedPrompt = resolvedPrompt,
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
            getOrCreateTuneBudsManager().takeIf { it.isConnected() }?.refreshStatus()
        }
        if (isMoyoungW620Selected()) {
            getOrCreateMoyoungW620Manager().takeIf { it.isConnected() }?.requestMediaCount()
        }
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
        val manager = getOrCreateMetaRaybanManager()
        val detail = manager.lastError.value?.takeIf { it.isNotBlank() } ?: message
        Log.e(
            "MainActivity",
            "$operation failed: $detail\n${manager.diagnosticsSnapshot()}",
        )
        updateMetaRaybanUiState()
        Toast.makeText(this, "$operation: $detail", Toast.LENGTH_LONG).show()
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

    private fun currentBleMacNoColonUpper(): String? {
        return try {
            DeviceManager.getInstance().deviceAddress
                ?.replace(":", "")
                ?.uppercase(Locale.US)
                ?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    private fun likelyGlassesPeerStrength(device: WifiP2pDevice, bleMacNoColon: String?): Int {
        val name = (device.deviceName ?: "").uppercase(Locale.US)
        if (name.isBlank()) return -1

        if (!bleMacNoColon.isNullOrBlank() && name.contains(bleMacNoColon)) {
            return 100
        }

        if (
            name.contains("HEYCYAN") ||
            name.contains("CYAN") ||
            name.startsWith("O_") ||
            name.startsWith("Q_")
        ) {
            return 80
        }

        // Known glasses model prefixes/brands from glasses_models.txt
        val glassesPrefixes = arrayOf(
            "AIM", "CR-", "SG", "GL-", "ST-AG", "BW-AG", "ABG-", "RG-",
            "ZIC-GLA", "QK-SG", "TF-GL", "HY-G", "NLB", "DM-", "ES-",
            "GS", "BV", "XC", "CG", "WL-", "ID-", "AZBV", "PW-", "GV3",
            "AX01", "ER0S", "VEU", "FIRELENS", "VIBELENS", "AIWR", "ROLBATCH",
            "DPVR", "VIZO", "SMARTVIEW", "KSIX", "VISO", "MD02", "WAGA",
            "BROOKLYN", "KATLOS", "FASTRACK", "HUGUR", "NILOX", "VEYRA",
            "AHENOD", "BOMANLON", "TRUSMI", "FABRIKA", "MICROWEAR",
            "WANDERTH", "PANGBOLIN", "SEEVA", "ASTR", "LENYES", "BLISBOND",
            "MEEEGOU", "NEOSEE", "SOBAST"
        )

        if (glassesPrefixes.any { name.startsWith(it) || name.contains(it) }) {
            return 70
        }

        if (name.contains("AIMB-") || name.contains("GLASS")) {
            return 70
        }

        // Weak fallback only when nothing else looks like the glasses.
        if (Regex("[A-F0-9]{12}").containsMatchIn(name)) {
            return 30
        }

        return -1
    }

    private fun selectBestLikelyGlassesPeer(peers: Collection<WifiP2pDevice>): WifiP2pDevice? {
        if (peers.isEmpty()) return null

        val bleMacNoColon = currentBleMacNoColonUpper()
        val scored = peers
            .map { peer -> peer to likelyGlassesPeerStrength(peer, bleMacNoColon) }
            .filter { (_, score) -> score >= 0 }
        if (scored.isEmpty()) return null

        val bestScore = scored.maxOf { it.second }
        val bestPeers = scored.filter { it.second == bestScore }.map { it.first }

        // Do not guess among multiple weak hex-only matches; keep waiting for a stronger signal.
        if (bestScore <= 30 && bestPeers.size > 1) {
            Log.i(
                "DataDownload",
                "Ambiguous weak glasses peer candidates; waiting for a stronger match: ${bestPeers.map { "${it.deviceName}/${it.deviceAddress}" }}"
            )
            return null
        }

        return bestPeers.firstOrNull { it.status == WifiP2pDevice.AVAILABLE }
            ?: bestPeers.firstOrNull()
    }

    private fun selectOfficialLikelyGlassesPeer(peers: Collection<WifiP2pDevice>): WifiP2pDevice? {
        if (peers.isEmpty()) return null

        val pairedName = try {
            DeviceManager.getInstance().deviceName
        } catch (_: Exception) {
            null
        }
        val pairedAddress = try {
            DeviceManager.getInstance().deviceAddress
        } catch (_: Exception) {
            null
        }

        fun matches(peer: WifiP2pDevice): Boolean {
            return HeyCyanP2pPolicy.matchesOfficialPeer(peer.deviceName, pairedName, pairedAddress)
        }

        return peers.firstOrNull { matches(it) && it.status == WifiP2pDevice.AVAILABLE }
            ?: peers.firstOrNull(::matches)
    }

    private fun expectedOfficialP2pName(): String {
        return try {
            HeyCyanP2pPolicy.officialWifiDirectName(
                DeviceManager.getInstance().deviceName,
                DeviceManager.getInstance().deviceAddress,
            ).orEmpty()
        } catch (_: Exception) {
            ""
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

        val suppressCheckbox = android.widget.CheckBox(this).apply {
            text = "Don't remind me again"
            val horizontalPadding = (24 * resources.displayMetrics.density).toInt()
            setPadding(horizontalPadding, 0, horizontalPadding, 0)
        }
        AlertDialog.Builder(this)
            .setTitle("HeyCyan may interrupt sync")
            .setMessage(
                "The official HeyCyan app is installed. Android cannot tell CyanBridge reliably " +
                    "whether HeyCyan is currently using the glasses. If it is, the two apps may " +
                    "compete for Bluetooth or Wi-Fi and interrupt media sync.\n\n" +
                    "For the most reliable sync, open App info, force-stop HeyCyan, then return " +
                    "to CyanBridge. CyanBridge cannot force-stop another app.",
            )
            .setView(suppressCheckbox)
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Open App info") { _, _ ->
                runCatching { startActivity(OfficialHeyCyanApp.appInfoIntent()) }
                    .onFailure {
                        Toast.makeText(this, "Could not open HeyCyan App info.", Toast.LENGTH_LONG).show()
                    }
            }
            .setPositiveButton("Continue") { _, _ ->
                if (suppressCheckbox.isChecked) OfficialHeyCyanApp.suppressWarning(this)
                showDownloadFlowPicker()
            }
            .show()
    }

    private enum class MediaDownloadPurpose {
        FULL_SYNC,
        IMAGE_QUESTION,
    }

    private data class HighQualityImageRequest(
        val sourceTag: String,
        val captureStartedAtMs: Long,
    )

    private fun isHighQualityImageTransfer(): Boolean =
        mediaDownloadPurpose == MediaDownloadPurpose.IMAGE_QUESTION && highQualityImageRequest != null

    private fun requestHighQualityImageForQuestion(sourceTag: String) {
        if (highQualityImageRequest == null) {
            highQualityImageRequest = HighQualityImageRequest(
                sourceTag = sourceTag,
                captureStartedAtMs = pendingImageCaptureStartedAtMs.takeIf { it > 0L }
                    ?: System.currentTimeMillis(),
            )
        }
        mediaDownloadPurpose = MediaDownloadPurpose.IMAGE_QUESTION
        lifecycleScope.launch {
            // The camera command owns the vendor SDK response slot until its callback returns.
            delay(300)
            if (!isHighQualityImageTransfer()) return@launch
            startDataDownload(
                mode = GlassesSyncFlow.CUSTOM,
                purpose = MediaDownloadPurpose.IMAGE_QUESTION,
            )
        }
    }

    private fun startEyevueMediaSync() {
        if (eyevueMediaJob?.isActive == true) return
        if (!hasBluetooth(this) || !hasWifiP2pPermission(this)) {
            ensureGlassesTransportPermissions("Eyevue media sync") {
                startEyevueMediaSync()
            }
            return
        }

        val manager = getOrCreateEyevueManager()
        if (!manager.isConnected()) {
            Toast.makeText(this, "Connect to Eyevue over Bluetooth first.", Toast.LENGTH_LONG).show()
            return
        }
        if (GlassesSessionCoordinator.currentSession() != null) {
            Toast.makeText(this, "Another glasses session is already active.", Toast.LENGTH_SHORT).show()
            return
        }

        val lease = acquireExclusiveGlassesSession(GlassesSession.MEDIA_SYNC) ?: return
        mediaSessionLease = lease
        eyevueMediaCancelled = false
        setTransferUiVisible(true)
        setTransferFlowLabel(GlassesSyncFlow.CUSTOM)
        setTransferDetail("Starting Eyevue media sync...")
        resetTransferUiState()
        setTransferUiVisible(true)

        val transport = EyevueWifiTransport(this)
        eyevueMediaTransport = transport
        val temporaryDirectory = File(cacheDir, "eyevue_media_${System.currentTimeMillis()}")
        eyevueMediaJob = lifecycleScope.launch(Dispatchers.IO) {
            var result: Result<Int>? = null
            try {
                val project = manager.awaitProject()
                val profile = EyevueMediaProfile.fromProject(project)
                val sync = EyevueMediaSync(manager, transport, temporaryDirectory)
                result = sync.sync(
                    profile = profile,
                    onState = { syncState ->
                        withContext(Dispatchers.Main) {
                            if (eyevueMediaCancelled) return@withContext
                            if (syncState.total > 0) {
                                transferTotalJpg = syncState.total
                                transferTotalMp4 = 0
                                transferTotalOpus = 0
                                transferDoneJpg = syncState.completed
                                transferDoneMp4 = 0
                                transferDoneOpus = 0
                            }
                            renderTransferProgress()
                            setTransferDetail("${syncState.detail} (${profile.mode.name})")
                        }
                    },
                    onProgress = { item, downloaded, total ->
                        if (total > 0L) {
                            withContext(Dispatchers.Main) {
                                if (!eyevueMediaCancelled) {
                                    setTransferDetail(
                                        "Downloading ${item.fileName}: " +
                                            "${downloaded / 1024} / ${total / 1024} KB",
                                    )
                                }
                            }
                        }
                    },
                    onFile = { item, file ->
                        val vendorItem = VendorMediaItem(
                            fileName = item.fileName,
                            type = when (item.type) {
                                EyevueMediaType.PHOTO -> VendorMediaType.PHOTO
                                EyevueMediaType.VIDEO -> VendorMediaType.VIDEO
                                EyevueMediaType.AUDIO -> VendorMediaType.AUDIO
                            },
                        )
                        importVendorMediaFile(file, vendorItem)
                    },
                )
            } finally {
                withContext(Dispatchers.Main) {
                    val completed = result?.getOrNull()
                    val cancelled = eyevueMediaCancelled
                    eyevueMediaJob = null
                    eyevueMediaTransport = null
                    setTransferUiVisible(false)
                    releaseExclusiveGlassesSession(lease)
                    if (!cancelled) {
                        if (completed != null) {
                            Toast.makeText(
                                this@MainActivity,
                                "Eyevue sync complete: $completed files",
                                Toast.LENGTH_LONG,
                            ).show()
                        } else {
                            Toast.makeText(
                                this@MainActivity,
                                "Eyevue media sync failed. Check the transfer details and retry.",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    }
                }
            }
        }
    }

    private fun stopEyevueMediaSync() {
        if (eyevueMediaJob?.isActive != true) return
        eyevueMediaCancelled = true
        eyevueMediaTransport?.disconnect()
        getOrCreateEyevueManager().finishTransfer()
        eyevueMediaJob?.cancel()
        setTransferUiVisible(false)
        releaseExclusiveGlassesSession(mediaSessionLease)
        mediaSessionLease = null
    }

    private fun startTuneBudsMediaSync() {
        if (tuneBudsMediaJob?.isActive == true) return
        if (!hasBluetooth(this) || !hasWifiP2pPermission(this)) {
            ensureGlassesTransportPermissions("TuneBuds media sync") {
                startTuneBudsMediaSync()
            }
            return
        }

        val manager = getOrCreateTuneBudsManager()
        if (!manager.isConnected()) {
            Toast.makeText(this, "Connect TuneBuds glasses first.", Toast.LENGTH_LONG).show()
            return
        }
        if (GlassesSessionCoordinator.currentSession() != null) {
            Toast.makeText(this, "Another glasses session is already active.", Toast.LENGTH_SHORT).show()
            return
        }

        val lease = acquireExclusiveGlassesSession(GlassesSession.MEDIA_SYNC) ?: return
        mediaSessionLease = lease
        tuneBudsMediaCancelled = false
        resetTransferUiState()
        setTransferUiVisible(true)
        setTransferFlowLabel(GlassesSyncFlow.CUSTOM)
        setTransferDetail("Starting TuneBuds media sync...")

        val hotspot = TuneBudsLocalHotspot(this)
        tuneBudsMediaHotspot = hotspot
        val temporaryDirectory = File(cacheDir, "tunebuds_media_${System.currentTimeMillis()}")
        tuneBudsMediaJob = lifecycleScope.launch(Dispatchers.IO) {
            var result: Result<Int>? = null
            try {
                val sync = TuneBudsMediaSync(manager, hotspot, temporaryDirectory)
                result = sync.sync(
                    onState = { syncState ->
                        withContext(Dispatchers.Main) {
                            if (tuneBudsMediaCancelled) return@withContext
                            if (syncState.total > 0) {
                                transferTotalJpg = syncState.total
                                transferTotalMp4 = 0
                                transferTotalOpus = 0
                                transferDoneJpg = syncState.completed
                                transferDoneMp4 = 0
                                transferDoneOpus = 0
                            }
                            renderTransferProgress()
                            setTransferDetail(
                                syncState.lastError?.let { "${syncState.detail}: $it" }
                                    ?: syncState.detail,
                            )
                        }
                    },
                    onProgress = { item, downloaded, total ->
                        if (total > 0L) {
                            withContext(Dispatchers.Main) {
                                if (!tuneBudsMediaCancelled) {
                                    setTransferDetail(
                                        "Downloading ${item.fileName}: ${downloaded / 1024} / ${total / 1024} KB",
                                    )
                                }
                            }
                        }
                    },
                    onFile = { item, file ->
                        importVendorMediaFile(
                            file,
                            VendorMediaItem(
                                fileName = item.fileName,
                                type = when (item.type) {
                                    TuneBudsMediaType.PHOTO -> VendorMediaType.PHOTO
                                    TuneBudsMediaType.VIDEO -> VendorMediaType.VIDEO
                                    TuneBudsMediaType.AUDIO -> VendorMediaType.AUDIO
                                },
                            ),
                        )
                    },
                )
            } finally {
                withContext(Dispatchers.Main) {
                    val completed = result?.getOrNull()
                    val cancelled = tuneBudsMediaCancelled
                    tuneBudsMediaJob = null
                    tuneBudsMediaHotspot = null
                    setTransferUiVisible(false)
                    releaseExclusiveGlassesSession(lease)
                    mediaSessionLease = null
                    if (!cancelled) {
                        Toast.makeText(
                            this@MainActivity,
                            completed?.let { "TuneBuds sync complete: $it files" }
                                ?: "TuneBuds media sync failed. Check the transfer details and retry.",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            }
        }
    }

    private fun stopTuneBudsMediaSync() {
        if (tuneBudsMediaJob?.isActive != true) return
        tuneBudsMediaCancelled = true
        tuneBudsMediaHotspot?.stop()
        getOrCreateTuneBudsManager().finishTransfer()
        tuneBudsMediaJob?.cancel()
        setTransferUiVisible(false)
        releaseExclusiveGlassesSession(mediaSessionLease)
        mediaSessionLease = null
    }

    private fun startMoyoungW620MediaSync() {
        if (moyoungMediaJob?.isActive == true) return
        if (!hasBluetooth(this) || !hasWifiP2pPermission(this)) {
            ensureGlassesTransportPermissions("MoYoung media sync") {
                startMoyoungW620MediaSync()
            }
            return
        }

        val manager = getOrCreateMoyoungW620Manager()
        if (!manager.isConnected()) {
            Toast.makeText(this, "Connect MoYoung / W620 glasses first.", Toast.LENGTH_LONG).show()
            return
        }
        if (GlassesSessionCoordinator.currentSession() != null) {
            Toast.makeText(this, "Another glasses session is already active.", Toast.LENGTH_SHORT).show()
            return
        }

        showMoyoungWifiCredentialsDialog(manager)
    }

    private fun showMoyoungWifiCredentialsDialog(manager: MoyoungW620Manager) {
        val prefs = getSharedPreferences("moyoung_w620", Context.MODE_PRIVATE)
        val ssidInput = android.widget.EditText(this).apply {
            hint = "Glasses Wi-Fi name (SSID)"
            setText(prefs.getString("file_wifi_ssid", "Glass-01"))
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        val passwordInput = android.widget.EditText(this).apply {
            hint = "Glasses Wi-Fi password"
            setText(prefs.getString("file_wifi_password", "12345678"))
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val padding = (24 * resources.displayMetrics.density).toInt()
        val fields = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(padding, 0, padding, 0)
            addView(ssidInput)
            addView(passwordInput)
        }
        AlertDialog.Builder(this)
            .setTitle("W620 Wi-Fi credentials")
            .setMessage(
                "Confirm the access-point details for these glasses. Glass-01 / 12345678 " +
                    "are upstream example defaults and may not match every W620.",
            )
            .setView(fields)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Start sync") { _, _ ->
                val credentials = MoyoungWifiCredentials(
                    ssid = ssidInput.text.toString().trim(),
                    password = passwordInput.text.toString(),
                )
                if (credentials.ssid.isBlank()) {
                    Toast.makeText(this, "Enter the glasses Wi-Fi name.", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                prefs.edit()
                    .putString("file_wifi_ssid", credentials.ssid)
                    .putString("file_wifi_password", credentials.password)
                    .apply()
                beginMoyoungW620MediaSync(manager, credentials)
            }
            .show()
    }

    private fun beginMoyoungW620MediaSync(
        manager: MoyoungW620Manager,
        wifiCredentials: MoyoungWifiCredentials,
    ) {
        if (moyoungMediaJob?.isActive == true) return

        val lease = acquireExclusiveGlassesSession(GlassesSession.MEDIA_SYNC) ?: return
        mediaSessionLease = lease
        moyoungMediaCancelled = false
        resetTransferUiState()
        setTransferUiVisible(true)
        setTransferFlowLabel(GlassesSyncFlow.CUSTOM)
        setTransferDetail("Starting MoYoung / W620 media sync...")

        val temporaryDirectory = File(cacheDir, "moyoung_media_${System.currentTimeMillis()}")
        moyoungMediaJob = lifecycleScope.launch(Dispatchers.IO) {
            var result: Result<Int>? = null
            try {
                result = runCatching {
                    val files = manager.downloadMedia(
                        targetDirectory = temporaryDirectory,
                        wifiCredentials = wifiCredentials,
                    ) { current, total ->
                        runOnUiThread {
                            if (!moyoungMediaCancelled) {
                                setTransferDetail(
                                    if (total > 0) "Downloading from W620: $current / $total"
                                    else "Downloading from W620...",
                                )
                            }
                        }
                    }
                    val items = files.mapNotNull { file ->
                        val type = when (file.extension.lowercase()) {
                            "jpg", "jpeg", "png", "heic" -> VendorMediaType.PHOTO
                            "mp4", "mov", "m4v" -> VendorMediaType.VIDEO
                            "opus", "ogg", "wav" -> VendorMediaType.AUDIO
                            else -> null
                        } ?: return@mapNotNull null
                        file to VendorMediaItem(file.name, type)
                    }
                    withContext(Dispatchers.Main) {
                        transferTotalJpg = items.count { it.second.type == VendorMediaType.PHOTO }
                        transferTotalMp4 = items.count { it.second.type == VendorMediaType.VIDEO }
                        transferTotalOpus = items.count { it.second.type == VendorMediaType.AUDIO }
                        renderTransferProgress()
                    }
                    var imported = 0
                    items.forEach { (file, item) ->
                        if (importVendorMediaFile(file, item)) imported++
                        withContext(Dispatchers.Main) {
                            when (item.type) {
                                VendorMediaType.PHOTO -> transferDoneJpg++
                                VendorMediaType.VIDEO -> transferDoneMp4++
                                VendorMediaType.AUDIO -> transferDoneOpus++
                            }
                            renderTransferProgress()
                        }
                    }
                    imported
                }
            } finally {
                manager.stopMediaSync()
                temporaryDirectory.deleteRecursively()
                withContext(Dispatchers.Main) {
                    val completed = result?.getOrNull()
                    val failure = result?.exceptionOrNull()
                    val cancelled = moyoungMediaCancelled
                    moyoungMediaJob = null
                    setTransferUiVisible(false)
                    releaseExclusiveGlassesSession(lease)
                    mediaSessionLease = null
                    if (!cancelled) {
                        if (failure != null) {
                            Log.e("MoyoungW620", "Media sync failed", failure)
                        }
                        Toast.makeText(
                            this@MainActivity,
                            completed?.let { "MoYoung / W620 sync complete: $it files" }
                                ?: "MoYoung / W620 sync failed: ${failure?.message ?: "unknown error"}",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            }
        }
    }

    private fun stopMoyoungW620MediaSync() {
        if (moyoungMediaJob?.isActive != true) return
        moyoungMediaCancelled = true
        getOrCreateMoyoungW620Manager().stopMediaSync()
        moyoungMediaJob?.cancel()
        setTransferUiVisible(false)
        releaseExclusiveGlassesSession(mediaSessionLease)
        mediaSessionLease = null
    }

    private fun startDataDownload(
        mode: GlassesSyncFlow = GlassesSyncFlow.CUSTOM,
        retryCount: Int = 0,
        isRetry: Boolean = false,
        afterP2pTeardown: Boolean = false,
        purpose: MediaDownloadPurpose = MediaDownloadPurpose.FULL_SYNC,
    ) {
        if (rejectHeyCyanOnlyFeature("Wi-Fi media sync")) return

        if (!hasBluetooth(this) || !hasWifiP2pPermission(this)) {
            ensureGlassesTransportPermissions("Wi-Fi media sync") {
                startDataDownload(
                    mode = mode,
                    retryCount = retryCount,
                    isRetry = isRetry,
                    afterP2pTeardown = afterP2pTeardown,
                    purpose = purpose,
                )
            }
            return
        }

        downloadFlowMode = mode
        mediaDownloadPurpose = purpose
        officialFlowRetryCount = retryCount

        if (!afterP2pTeardown) {
        Log.i("DataDownload", "Starting BLE+WiFi P2P data download using ${mode.label}...")
        Log.i("DataDownload", "Sync session flow=${mode.label}")
        Toast.makeText(this, "Starting sync using ${mode.label}… Please do not exit the app during transfer.", Toast.LENGTH_LONG).show()

        // Check Bluetooth connection status
        if (!BleOperateManager.getInstance().isConnected) {
            Log.e("DataDownload", "Bluetooth not connected. Please connect to glasses first.")
            if (isHighQualityImageTransfer()) {
                finishHighQualityImageFailure("Bluetooth disconnected before full-resolution image transfer could start.")
                return
            }
            Toast.makeText(
                this,
                "Bluetooth not connected. Please connect to glasses first.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        // Check WiFi is enabled (required for WiFi Direct / P2P)
        val wifiManager = getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
        if (!wifiManager.isWifiEnabled) {
            Log.e("DataDownload", "WiFi is disabled. WiFi must be on for P2P sync.")
            if (isHighQualityImageTransfer()) {
                finishHighQualityImageFailure("Wi-Fi must be enabled to retrieve the full-resolution image.")
                return
            }
            Toast.makeText(
                this,
                "Please enable WiFi to sync with glasses.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

            if (GlassesSessionCoordinator.isOwnedBy(GlassesSession.MEDIA_SYNC)) {
                if (!isRetry || mediaSessionLease?.let(GlassesSessionCoordinator::isActive) != true) {
                    Log.w("DataDownload", "Media sync is already active or still tearing down")
                    Toast.makeText(this, "Media sync is already using the glasses connection.", Toast.LENGTH_SHORT).show()
                    return
                }
            } else {
                mediaSessionLease = acquireExclusiveGlassesSession(GlassesSession.MEDIA_SYNC) ?: return
            }

            downloadCancelledByUser = false
            val teardownLease = mediaSessionLease
            // P2P removal is asynchronous. Start a retry only after the prior group is gone,
            // otherwise its delayed removal can tear down the newly formed group.
            teardownDownloadP2pSession(
                sendExitTransfer = false,
                hideTransferUi = false,
                releaseExclusiveSession = false,
                onTeardownComplete = {
                    if (downloadCancelledByUser) {
                        Log.i("DataDownload", "Sync was cancelled while waiting for P2P teardown")
                        releaseExclusiveGlassesSession(teardownLease)
                    } else {
                        startDataDownload(
                            mode = mode,
                            retryCount = retryCount,
                            isRetry = true,
                            afterP2pTeardown = true,
                            purpose = purpose,
                        )
                    }
                },
            )
            return
        }

        downloadCancelledByUser = false

        // Reset state for a fresh run
        downloadP2pConnected = false
        downloadBleIp = null
        downloadWifiIp = null
        downloadPhoneIsGroupOwner = null
        downloadInProgress = false
        downloadResolvedHttpIp = null
        lastDownloadBleIpAtMs = 0L
        officialDisconnectRecoveryJob?.cancel()
        officialDisconnectRecoveryJob = null
        officialMediaErrorCount = 0
        resetDownloadSupportState()
        resetOfficialFlowState()
        createDownloadSession()

        resetTransferUiState()
        setTransferUiVisible(true)
        setTransferFlowLabel(mode)
        setTransferDetail("Starting sync (${mode.label})...")
        startDownloadInitialPhaseWatchdog()

        if (!downloadNotifyListenerRegistered) {
            try {
                LargeDataHandler.getInstance().addOutDeviceListener(2, downloadNotifyListener)
                downloadNotifyListenerRegistered = true
                Log.i("DataDownload", "Registered download notify listener (cmdType=2)")
            } catch (e: Exception) {
                Log.e("DataDownload", "Failed to register download notify listener", e)
            }
        }

        val wifiP2pManager = WifiP2pManagerSingleton.getInstance(this)
        downloadWifiP2pManager = wifiP2pManager

        // Mirror vendor flow: clear internal retry state.
        wifiP2pManager.resetFailCount()

        // Register receiver and listen for P2P state/peer changes
        wifiP2pManager.registerReceiver()

        val callback = object : WifiP2pManagerSingleton.WifiP2pCallback {
            override fun onWifiP2pEnabled() {
                Log.i("DataDownload", "WiFi P2P enabled")
            }

            override fun onWifiP2pDisabled() {
                Log.e("DataDownload", "WiFi P2P disabled")
            }

            override fun onPeersChanged(peers: Collection<WifiP2pDevice>) {
                Log.i("DataDownload", "Found ${peers.size} P2P devices")
                if (peers.isEmpty()) return

                // Debounce: Android often broadcasts PEERS_CHANGED multiple times
                // for the same peer list. Only process when the set actually changes.
                val currentHash = peers.map { it.deviceAddress }.toSet().hashCode()
                if (currentHash == lastPeerSetHash) return
                lastPeerSetHash = currentHash

                // Guard against redundant connection attempts (official app uses isP2PConnecting).
                if (downloadWifiP2pManager?.isConnecting() == true || downloadWifiP2pManager?.isConnected() == true) {
                    Log.i("DataDownload", "Already connecting/connected, skipping peer re-evaluation")
                    return
                }

                if (downloadFlowMode == GlassesSyncFlow.OFFICIAL_HEYCYAN) {
                    val target = selectOfficialLikelyGlassesPeer(peers)
                    if (target == null) {
                        val pairedName = try { DeviceManager.getInstance().deviceName } catch (_: Exception) { "?" }
                        val pairedMac = try { DeviceManager.getInstance().deviceAddress } catch (_: Exception) { "?" }
                        peers.forEach { seenP2pPeers.add("${it.deviceName}/${it.deviceAddress}") }
                        Log.i(
                            "DataDownload",
                            "Official flow: no exact glasses peer yet; paired=$pairedName/$pairedMac; discovered=${peers.map { "${it.deviceName}/${it.deviceAddress}" }}"
                        )
                        setTransferDetail("Waiting for exact glasses P2P peer...")
                        return
                    }

                    Log.i(
                        "DataDownload",
                        "Official flow connecting to peer: ${target.deviceName} / ${target.deviceAddress}"
                    )
                    wifiP2pManager.connectToDevice(target)
                    return
                }

                val target = selectBestLikelyGlassesPeer(peers)
                if (target == null) {
                    noMatchPeerCount++
                    val pairedName = try { DeviceManager.getInstance().deviceName } catch (_: Exception) { "?" }
                    val pairedMac = try { DeviceManager.getInstance().deviceAddress } catch (_: Exception) { "?" }
                    peers.forEach { seenP2pPeers.add("${it.deviceName}/${it.deviceAddress}") }
                    Log.i(
                        "DataDownload",
                        "No likely glasses peer yet (x$noMatchPeerCount); paired=$pairedName/$pairedMac; ignoring discovered peers: ${peers.map { "${it.deviceName}/${it.deviceAddress}" }}"
                    )
                    setTransferDetail("Waiting for glasses P2P peer...")

                    // Restart discovery + re-send BLE transfer command after 2 consecutive
                    // no-match batches — the glasses may have missed the initial command.
                    if (noMatchPeerCount >= 2) {
                        noMatchPeerCount = 0
                        downloadP2pRestartCount++
                        Log.i("DataDownload", "P2P restart attempt $downloadP2pRestartCount/$maxP2pRestarts")
                        setTransferDetail("Retrying P2P discovery ($downloadP2pRestartCount/$maxP2pRestarts)...")

                        if (downloadP2pRestartCount >= maxP2pRestarts) {
                            Log.w("DataDownload", "P2P sync failed after $maxP2pRestarts restart attempts")
                            finishDownloadInitialPhase("retries exhausted")
                            showP2pPeerConflictDialog(
                                seenPeers = seenP2pPeers.toList(),
                                pairedDevice = "$pairedName/$pairedMac",
                            )
                            return
                        }

                        downloadWifiP2pManager?.restartPeerDiscovery()
                        sendTransferModeCommandWithRetry(
                            sessionId = downloadSessionId,
                            attempt = 1,
                            maxAttempts = 2,
                            delayMs = 1500L,
                        )
                    }
                    return
                }

                noMatchPeerCount = 0
                Log.i(
                    "DataDownload",
                    "Connecting to peer: ${target.deviceName} / ${target.deviceAddress}"
                )
                wifiP2pManager.connectToDevice(target)
            }

            override fun onThisDeviceChanged(device: WifiP2pDevice) {
                Log.i(
                    "DataDownload",
                    "This device changed: ${device.deviceName} - ${device.status}"
                )
            }

            override fun onConnected(info: WifiP2pInfo) {
                Log.i(
                    "DataDownload",
                    "P2P connected: groupFormed=${info.groupFormed}, isGroupOwner=${info.isGroupOwner}"
                )
                onDownloadP2pConnected(info)
            }

            override fun onDisconnected() {
                Log.i("DataDownload", "P2P disconnected")
                downloadP2pConnected = false
                downloadP2pNetwork = null
                unbindProcessFromNetwork()

                val shouldRecover = when (downloadFlowMode) {
                    GlassesSyncFlow.OFFICIAL_HEYCYAN -> {
                        !downloadCancelledByUser &&
                            (!downloadInitialPhaseCompleted || downloadAttemptJob?.isActive == true || downloadInProgress)
                    }

                    GlassesSyncFlow.CUSTOM -> {
                        !downloadCancelledByUser &&
                            (downloadAttemptJob?.isActive == true || downloadInProgress)
                    }
                }
                if (shouldRecover) {
                    if (downloadFlowMode == GlassesSyncFlow.OFFICIAL_HEYCYAN) {
                        Log.i("DataDownload", "Official flow: P2P disconnected during sync; restarting discovery in 2000ms")
                        setTransferDetail("P2P disconnected; retrying official flow...")
                        downloadWifiP2pManager?.discoverPeersStable()
                        officialDisconnectRecoveryJob?.cancel()
                        officialDisconnectRecoveryJob = CoroutineScope(Dispatchers.Main).launch {
                            delay(2000)
                            if (downloadCancelledByUser) return@launch
                            if (downloadP2pConnected) return@launch
                            downloadWifiP2pManager?.startPeerDiscovery()
                        }
                    } else {
                        Log.i("DataDownload", "P2P disconnected during sync; restarting peer discovery")
                        setTransferDetail("P2P disconnected; retrying discovery...")
                        downloadWifiP2pManager?.discoverPeersStable()
                        downloadWifiP2pManager?.startPeerDiscovery()
                    }
                }
            }

            override fun onPeerDiscoveryStarted() {
                Log.i("DataDownload", "Peer discovery started")
            }

            override fun onPeerDiscoveryFailed(reason: Int) {
                Log.e("DataDownload", "Peer discovery failed: $reason")
            }

            override fun onConnectRequestSent() {
                Log.i("DataDownload", "Connect request sent")
            }

            override fun onConnectRequestFailed(reason: Int) {
                Log.e("DataDownload", "Connect request failed: $reason")
            }

            override fun connecting() {
                Log.i("DataDownload", "Connecting to P2P device...")
            }

            override fun cancelConnect() {
                Log.i("DataDownload", "P2P connection cancelled")
            }

            override fun cancelConnectFail(reason: Int) {
                Log.e("DataDownload", "Cancel connect failed: $reason")
            }

            override fun retryAlsoFailed() {
                if (isHighQualityImageTransfer()) {
                    finishHighQualityImageFailure(
                        "Wi-Fi Direct could not connect to the glasses for the full-resolution image.",
                    )
                    return
                }
                if (downloadFlowMode == GlassesSyncFlow.OFFICIAL_HEYCYAN && officialFlowRetryCount < officialFlowRetryLimit) {
                    restartOfficialWholeFlow("P2P connection retry failed")
                    return
                }
                Log.e("DataDownload", "P2P connection retry failed")
            }
        }

        downloadWifiP2pCallback = callback
        wifiP2pManager.addCallback(callback)

        // Start scanning for the glasses over WiFi Direct
        wifiP2pManager.startPeerDiscovery()

        setTransferDetail(
            when (mode) {
                GlassesSyncFlow.OFFICIAL_HEYCYAN -> "Waiting for P2P + BLE IP (HeyCyan flow)..."
                GlassesSyncFlow.CUSTOM -> "Waiting for glasses IP and HTTP server..."
            }
        )

        // Ask the glasses (over BLE) to bring up WiFi/P2P and report their IP,
        // mirroring the official app's importAlbum() flow.
        sendTransferModeCommandWithRetry(sessionId = downloadSessionId)
    }

    /**
     * Send the transfer-mode command [0x02,0x01,0x04] with retry on error=-1.
     * Many logs show the glasses refusing the first attempt. A reset command
     * [0x02,0x01,0x0F] is sent before each retry to clear stale state.
     */
    private fun sendTransferModeCommandWithRetry(
        sessionId: Long,
        attempt: Int = 1,
        maxAttempts: Int = 3,
        delayMs: Long = 2000L,
    ) {
        if (!isDownloadControlActive(sessionId)) {
            Log.i("DataDownload", "Skipping transfer-mode command for inactive session=$sessionId")
            return
        }
        transferModeCommandTimeoutJob?.cancel()
        transferModeCommandAttempt = attempt
        transferModeCommandSentAtMs = System.currentTimeMillis()
        transferModeCommandCallbackReceived = false
        transferModeCommandEvidenceReceived = false
        Log.i(
            "DataDownload",
            "Sending glassesControl[0x02,0x01,0x04] (attempt $attempt/$maxAttempts); " +
                "official callback watchdog=${TRANSFER_MODE_COMMAND_TIMEOUT_MS}ms",
        )
        transferModeCommandTimeoutJob = launchDownloadSession { watchdogSessionId ->
            delay(TRANSFER_MODE_COMMAND_TIMEOUT_MS)
            if (watchdogSessionId != sessionId || !isDownloadControlActive(sessionId)) return@launchDownloadSession
            if (!HeyCyanP2pPolicy.transferCommandTimedOut(
                    transferModeCommandCallbackReceived,
                    transferModeCommandEvidenceReceived,
                )
            ) return@launchDownloadSession

            Log.w(
                "DataDownload",
                "Transfer mode command did not return within ${TRANSFER_MODE_COMMAND_TIMEOUT_MS}ms " +
                    "and no P2P/BLE-IP evidence arrived (attempt $attempt/$maxAttempts)",
            )
            withContext(Dispatchers.Main) {
                setTransferDetail("Glasses did not acknowledge transfer mode")
                if (downloadFlowMode == GlassesSyncFlow.OFFICIAL_HEYCYAN) {
                    stopOfficialFlowForRetry(
                        "The glasses did not acknowledge transfer mode within 10 seconds.",
                        resetDeviceP2p = false,
                    )
                }
            }
        }
        LargeDataHandler.getInstance().glassesControl(
            byteArrayOf(0x02, 0x01, 0x04)
        ) { _, resp ->
            transferModeCommandCallbackReceived = true
            transferModeCommandTimeoutJob?.cancel()
            transferModeCommandTimeoutJob = null
            val callbackLatencyMs = System.currentTimeMillis() - transferModeCommandSentAtMs
            transferModeCommandCallbackLatencyMs = callbackLatencyMs
            Log.i(
                "DataDownload",
                "glassesControl[0x02,0x01,0x04] (attempt $attempt/$maxAttempts) -> " +
                    "dataType=${resp.dataType}, error=${resp.errorCode}, latency=${callbackLatencyMs}ms"
            )
            if (!isDownloadControlActive(sessionId)) {
                Log.i("DataDownload", "Ignoring transfer-mode response for inactive session=$sessionId")
            } else if (resp.errorCode == -1 && attempt < maxAttempts) {
                Log.w("DataDownload", "Transfer mode command refused (error=-1); retrying after ${delayMs}ms")
                launchDownloadSession { retrySessionId ->
                    if (retrySessionId != sessionId || !isDownloadControlActive(sessionId)) {
                        return@launchDownloadSession
                    }
                    delay(delayMs)
                    if (!isDownloadControlActive(sessionId)) {
                        return@launchDownloadSession
                    }
                    // Reset glasses P2P state before retrying
                    if (!resetTransferP2pForRetry(sessionId)) {
                        Log.w("DataDownload", "Pre-retry P2P reset did not complete; not sending another transfer command")
                        return@launchDownloadSession
                    }
                    delay(500)
                    if (isDownloadControlActive(sessionId)) {
                        sendTransferModeCommandWithRetry(
                            sessionId = sessionId,
                            attempt = attempt + 1,
                            maxAttempts = maxAttempts,
                            delayMs = delayMs,
                        )
                    }
                }
            }
        }
    }

    private suspend fun resetTransferP2pForRetry(sessionId: Long): Boolean {
        if (!isDownloadControlActive(sessionId)) return false
        val resetAccepted = withTimeoutOrNull(5_000) {
            suspendCancellableCoroutine<Boolean> { continuation ->
                try {
                    LargeDataHandler.getInstance().glassesControl(
                        byteArrayOf(0x02, 0x01, 0x0F),
                    ) { _, response ->
                        Log.d(
                            "DataDownload",
                            "Pre-retry reset [0x02,0x01,0x0F] -> error=${response.errorCode}",
                        )
                        if (continuation.isActive) {
                            // The vendor parser leaves work type 0x0F at its default errorCode=1.
                            // HeyCyan ignores these response fields; callback arrival is completion.
                            continuation.resume(
                                HeyCyanP2pPolicy.resetCallbackAllowsRetry(
                                    callbackReceived = true,
                                    parsedErrorCode = response.errorCode,
                                ),
                            ) {}
                        }
                    }
                } catch (e: Exception) {
                    Log.w("DataDownload", "Failed to send pre-retry P2P reset", e)
                    if (continuation.isActive) continuation.resume(false) {}
                }
            }
        }
        if (resetAccepted == null) {
            Log.w("DataDownload", "Pre-retry P2P reset timed out; retaining the current media session")
            return false
        }
        return resetAccepted && isDownloadControlActive(sessionId)
    }

    private fun setTransferUiVisible(visible: Boolean) {
        binding.cardTransferProgress.visibility = if (visible) View.VISIBLE else View.GONE
        updateDashboardState { state ->
            state.copy(transfer = state.transfer.copy(isVisible = visible))
        }
    }

    private fun resetTransferUiState() {
        transferTotalJpg = 0
        transferTotalMp4 = 0
        transferTotalOpus = 0
        transferDoneJpg = 0
        transferDoneMp4 = 0
        transferDoneOpus = 0
        lastFileProgressUiAtMs = 0L

        binding.tvTransferFlow.text = "Flow: --"
        binding.tvTransferCounts.text = "Photos: --  Videos: --  Audio: --"
        binding.progressTransfer.isIndeterminate = true
        binding.progressTransfer.max = 100
        binding.progressTransfer.progress = 0
        binding.tvTransferDetail.text = "Idle"
        updateDashboardState { state ->
            state.copy(
                transfer = GlassesTransferUiState(
                    isVisible = state.transfer.isVisible,
                ),
            )
        }
    }

    private fun setTransferPlan(jpg: Int, mp4: Int, opus: Int) {
        finishDownloadInitialPhase("media list resolved")
        transferTotalJpg = jpg
        transferTotalMp4 = mp4
        transferTotalOpus = opus
        transferDoneJpg = 0
        transferDoneMp4 = 0
        transferDoneOpus = 0
        renderTransferProgress()
    }

    private fun onTransferItemDone(type: String) {
        when (type) {
            "jpg" -> transferDoneJpg++
            "mp4" -> transferDoneMp4++
            "opus" -> transferDoneOpus++
        }
        renderTransferProgress()
    }

    private fun transferItemSummary(): String {
        val done = transferDoneJpg + transferDoneMp4 + transferDoneOpus
        val total = transferTotalJpg + transferTotalMp4 + transferTotalOpus
        return "$done/$total"
    }

    private fun renderTransferProgress() {
        val total = transferTotalJpg + transferTotalMp4 + transferTotalOpus
        val done = transferDoneJpg + transferDoneMp4 + transferDoneOpus

        binding.tvTransferCounts.text =
            "Photos: ${transferDoneJpg}/${transferTotalJpg}  Videos: ${transferDoneMp4}/${transferTotalMp4}  Audio: ${transferDoneOpus}/${transferTotalOpus}"

        if (total <= 0) {
            binding.progressTransfer.isIndeterminate = true
            binding.progressTransfer.max = 100
            binding.progressTransfer.progress = 0
        } else {
            binding.progressTransfer.isIndeterminate = false
            binding.progressTransfer.max = total * 1000
            binding.progressTransfer.progress = (done * 1000).coerceAtMost(binding.progressTransfer.max)
        }
        updateDashboardState { state ->
            state.copy(
                transfer = state.transfer.copy(
                    countsLabel = "Photos: $transferDoneJpg/$transferTotalJpg  Videos: $transferDoneMp4/$transferTotalMp4  Audio: $transferDoneOpus/$transferTotalOpus",
                    progress = if (total > 0) done.toFloat() / total.toFloat() else null,
                ),
            )
        }
    }

    private fun setTransferDetail(text: String) {
        binding.tvTransferDetail.text = text
        updateDashboardState { state ->
            state.copy(transfer = state.transfer.copy(detail = text))
        }
    }

    private fun setTransferFlowLabel(mode: GlassesSyncFlow) {
        binding.tvTransferFlow.text = "Flow: ${mode.label}"
        updateDashboardState { state ->
            state.copy(transfer = state.transfer.copy(flowLabel = mode.label))
        }
    }

    private fun createDownloadSession() {
        downloadSessionJob?.cancel()
        val job = SupervisorJob()
        downloadSessionJob = job
        downloadSessionScope = CoroutineScope(job + Dispatchers.IO)
        downloadSessionId++
        vendorAlbumDownloader = if (downloadFlowMode == GlassesSyncFlow.OFFICIAL_HEYCYAN) {
            VendorAlbumDownloader(
                destinationDir = File(cacheDir, "heycyan_album/$downloadSessionId"),
                requestTag = "cyanbridge_heycyan_album_$downloadSessionId",
            )
        } else {
            null
        }
    }

    private fun cancelDownloadSession() {
        vendorAlbumDownloader?.cancel()
        vendorAlbumDownloader?.clear()
        vendorAlbumDownloader = null
        downloadSessionJob?.cancel()
        downloadSessionJob = null
        downloadSessionScope = null
    }

    private fun isDownloadSessionActive(sessionId: Long): Boolean {
        return sessionId == downloadSessionId &&
            downloadSessionJob?.isActive == true &&
            !downloadCancelledByUser
    }

    private fun isDownloadControlActive(sessionId: Long): Boolean {
        return isDownloadSessionActive(sessionId) &&
            !downloadP2pTeardownInProgress &&
            GlassesSessionCoordinator.currentSession() == GlassesSession.MEDIA_SYNC
    }

    private fun launchDownloadSession(block: suspend CoroutineScope.(Long) -> Unit): Job? {
        val scope = downloadSessionScope ?: return null
        val sessionId = downloadSessionId
        return scope.launch {
            block(sessionId)
        }
    }

    private fun setTransferDetailForSession(sessionId: Long, text: String) {
        if (!isDownloadSessionActive(sessionId)) return
        runOnUiThread {
            if (isDownloadSessionActive(sessionId)) {
                setTransferDetail(text)
            }
        }
    }

    private fun formatTransferBytes(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        var value = bytes.toDouble()
        var unitIndex = 0
        while (value >= 1024.0 && unitIndex < units.lastIndex) {
            value /= 1024.0
            unitIndex++
        }
        val pattern = if (value >= 100 || unitIndex == 0) "%.0f" else "%.1f"
        return pattern.format(Locale.US, value) + " " + units[unitIndex]
    }

    private fun maybeReportFileProgress(
        sessionId: Long,
        mediaType: String,
        fileName: String,
        bytesCopied: Long,
        totalBytes: Long,
        startedAtMs: Long,
    ) {
        val now = System.currentTimeMillis()
        if (now - lastFileProgressUiAtMs < 750L && bytesCopied < totalBytes) return
        lastFileProgressUiAtMs = now

        val speedBps = if (startedAtMs > 0L) {
            val elapsedMs = (now - startedAtMs).coerceAtLeast(1L)
            (bytesCopied * 1000L) / elapsedMs
        } else {
            0L
        }
        val percent = if (totalBytes > 0L) ((bytesCopied * 100L) / totalBytes).coerceIn(0L, 100L) else -1L
        val (typeDone, typeTotal) = when (mediaType) {
            "photo" -> transferDoneJpg to transferTotalJpg
            "video" -> transferDoneMp4 to transferTotalMp4
            else -> transferDoneOpus to transferTotalOpus
        }
        val detail = if (percent >= 0L) {
            "Downloading $mediaType ${typeDone + 1}/$typeTotal: ${percent}% (${formatTransferBytes(bytesCopied)}/${formatTransferBytes(totalBytes)} at ${formatTransferBytes(speedBps)}/s)"
        } else {
            "Downloading $mediaType ${typeDone + 1}/$typeTotal: ${formatTransferBytes(bytesCopied)} at ${formatTransferBytes(speedBps)}/s"
        }
        if (!isDownloadSessionActive(sessionId)) return
        runOnUiThread {
            if (!isDownloadSessionActive(sessionId)) return@runOnUiThread
            setTransferDetail(detail)
            val totalItems = transferTotalJpg + transferTotalMp4 + transferTotalOpus
            val completedItems = transferDoneJpg + transferDoneMp4 + transferDoneOpus
            if (totalItems > 0) {
                val fraction = if (totalBytes > 0L) {
                    (bytesCopied.toDouble() / totalBytes.toDouble()).coerceIn(0.0, 1.0)
                } else {
                    0.0
                }
                binding.progressTransfer.isIndeterminate = false
                binding.progressTransfer.max = totalItems * 1000
                binding.progressTransfer.progress = (completedItems * 1000 + fraction * 1000).toInt()
                    .coerceAtMost(binding.progressTransfer.max)
                updateDashboardState { state ->
                    state.copy(
                        transfer = state.transfer.copy(
                            progress = ((completedItems + fraction) / totalItems).toFloat(),
                        ),
                    )
                }
            }
        }
        Log.i(
            "DataDownload",
            "Media progress: type=$mediaType, file=$fileName, bytes=$bytesCopied/$totalBytes, speed=${speedBps}B/s, flow=${downloadFlowMode.label}"
        )
    }

    private fun startDownloadInitialPhaseWatchdog() {
        downloadInitialPhaseTimeoutJob?.cancel()
        downloadInitialPhaseTimeoutJob = CoroutineScope(Dispatchers.Main).launch {
            delay(downloadInitialPhaseTimeoutMs)
            val sessionStillStuck = !downloadInitialPhaseCompleted &&
                !downloadCancelledByUser &&
                !downloadInProgress &&
                dashboardState.transfer.isVisible

            if (!sessionStillStuck) return@launch

            val waitedSeconds = ((System.currentTimeMillis() - downloadStartedAtMs) / 1000L).coerceAtLeast(1L)
            Log.w("DataDownload", "Initial P2P sync phase timed out after ${waitedSeconds}s (flow=${downloadFlowMode.label})")

            if (isHighQualityImageTransfer()) {
                finishHighQualityImageFailure(
                    "Timed out waiting for the glasses Wi-Fi connection for the full-resolution image.",
                )
                return@launch
            }

            if (downloadFlowMode == GlassesSyncFlow.OFFICIAL_HEYCYAN && officialFlowRetryCount < officialFlowRetryLimit) {
                restartOfficialWholeFlow("initial sync timeout after ${waitedSeconds}s")
                return@launch
            }

            setTransferDetail("Sync is taking longer than expected")
            maybeShowP2pSyncLogHelp(
                reason = "CyanBridge got stuck before media transfer started. The sync button was pressed ${waitedSeconds}s ago and the transfer counters never advanced.",
            )
        }
    }

    private fun finishDownloadInitialPhase(reason: String) {
        if (downloadInitialPhaseCompleted) return
        downloadInitialPhaseCompleted = true
        downloadInitialPhaseTimeoutJob?.cancel()
        downloadInitialPhaseTimeoutJob = null
        Log.i("DataDownload", "Initial sync phase completed: $reason (flow=${downloadFlowMode.label})")
    }

    private fun resetDownloadSupportState() {
        downloadInitialPhaseTimeoutJob?.cancel()
        downloadInitialPhaseTimeoutJob = null
        downloadInitialPhaseCompleted = false
        downloadSupportDialogShown = false
        downloadStartedAtMs = System.currentTimeMillis()
        noMatchPeerCount = 0
        downloadP2pRestartCount = 0
        seenP2pPeers.clear()
        lastPeerSetHash = 0
        transferModeCommandTimeoutJob?.cancel()
        transferModeCommandTimeoutJob = null
        transferModeCommandAttempt = 0
        transferModeCommandSentAtMs = 0L
        transferModeCommandCallbackLatencyMs = null
        transferModeCommandCallbackReceived = false
        transferModeCommandEvidenceReceived = false
        selectedDownloadNetworkSummary = "none"
        officialFlowRetryRequired = false
    }

    private fun resetOfficialFlowState() {
        officialSystemSuccess = false
        officialBleCallbackSuccess = false
        officialMediaErrorCount = 0
    }

    private fun isOfficialSyncActive(): Boolean {
        return downloadFlowMode == GlassesSyncFlow.OFFICIAL_HEYCYAN &&
            !downloadCancelledByUser &&
            dashboardState.transfer.isVisible
    }

    private fun restartOfficialWholeFlow(reason: String) {
        val nextRetry = officialFlowRetryCount + 1
        Log.w(
            "DataDownload",
            "Official flow retrying whole import sequence ($nextRetry/$officialFlowRetryLimit) because $reason"
        )
        setTransferDetail("Official flow retrying sync...")
        startDataDownload(
            mode = GlassesSyncFlow.OFFICIAL_HEYCYAN,
            retryCount = nextRetry,
            isRetry = true,
        )
    }

    private fun maybeShowP2pSyncLogHelp(
        reason: String,
        title: String = "P2P sync is stuck",
        dismissButtonLabel: String = "Later",
    ) {
        if (downloadSupportDialogShown || isFinishing || isDestroyed) return
        downloadSupportDialogShown = true
        DebugLogSupport.showSupportOptionsDialog(
            activity = this,
            title = "$title (${downloadFlowMode.label})",
            issueType = "P2P/WiFi sync issue",
            description = "Flow: ${downloadFlowMode.label}\n\n$reason",
            extraInfo = linkedMapOf(
                "transfer_detail" to dashboardState.transfer.detail,
                "transfer_counts" to dashboardState.transfer.countsLabel,
                "download_flow_mode" to downloadFlowMode.label,
                "download_ble_ip" to (downloadBleIp ?: ""),
                "download_wifi_ip" to (downloadWifiIp ?: ""),
                "download_http_ip" to (downloadResolvedHttpIp ?: ""),
                "download_p2p_connected" to downloadP2pConnected.toString(),
                "download_in_progress" to downloadInProgress.toString(),
                "download_phone_is_group_owner" to (downloadPhoneIsGroupOwner?.toString() ?: "unknown"),
                "transfer_mode_command_attempt" to transferModeCommandAttempt.toString(),
                "transfer_mode_callback_received" to transferModeCommandCallbackReceived.toString(),
                "transfer_mode_callback_latency_ms" to (transferModeCommandCallbackLatencyMs?.toString() ?: ""),
                "transfer_mode_evidence_received" to transferModeCommandEvidenceReceived.toString(),
                "expected_official_p2p_name" to expectedOfficialP2pName(),
                "selected_download_network" to selectedDownloadNetworkSummary,
                "seen_p2p_peers" to seenP2pPeers.joinToString(", "),
                "active_glasses_session" to (GlassesSessionCoordinator.currentSession()?.name ?: "none"),
                "ota_active" to otaManager.isActive.toString(),
            ),
            dismissButtonLabel = dismissButtonLabel,
        )
    }
    
    private fun showP2pPeerConflictDialog(
        seenPeers: List<String>,
        pairedDevice: String,
    ) {
        if (isHighQualityImageTransfer()) {
            finishHighQualityImageFailure(
                "Could not find the glasses Wi-Fi Direct peer for the full-resolution image.",
            )
            return
        }
        if (downloadSupportDialogShown || isFinishing || isDestroyed) return
        downloadSupportDialogShown = true

        val reason = buildString {
            appendLine("CyanBridge found other Wi‑Fi Direct devices but could not find the glasses ($pairedDevice) among them.")
            appendLine()
            appendLine("IMPORTANT: If the official HeyCyan app is installed, force-stop it now (Settings → Apps → HeyCyan → Force Stop). It may be holding the P2P connection and preventing CyanBridge from discovering the glasses.")
            appendLine()
            appendLine("Also try turning OFF the following devices or moving away from them, then tap Try Again:")
            appendLine()
            for (peer in seenPeers) {
                appendLine("  • $peer")
            }
            appendLine()
            appendLine("If the problem persists, send the logs to the CyanBridge server.")
        }

        AlertDialog.Builder(this)
            .setTitle("P2P sync failed")
            .setMessage(reason)
            .setNegativeButton("Later", null)
            .setNeutralButton("Send to server") { _, _ ->
                DebugLogSupport.showSupportOptionsDialog(
                    activity = this,
                    title = "P2P sync failed (${downloadFlowMode.label})",
                    issueType = "P2P/WiFi sync issue",
                    description = "Flow: ${downloadFlowMode.label}\n\nP2P sync could not find glasses after $maxP2pRestarts attempts. Bluetooth is connected but glasses did not appear as a Wi‑Fi Direct peer. Seen P2P peers: ${seenPeers.joinToString(", ")}",
                    extraInfo = linkedMapOf(
                        "transfer_detail" to dashboardState.transfer.detail,
                        "transfer_counts" to dashboardState.transfer.countsLabel,
                        "download_flow_mode" to downloadFlowMode.label,
                        "download_ble_ip" to (downloadBleIp ?: ""),
                        "download_wifi_ip" to (downloadWifiIp ?: ""),
                        "download_http_ip" to (downloadResolvedHttpIp ?: ""),
                        "download_p2p_connected" to downloadP2pConnected.toString(),
                        "download_in_progress" to downloadInProgress.toString(),
                        "download_phone_is_group_owner" to (downloadPhoneIsGroupOwner?.toString() ?: "unknown"),
                        "transfer_mode_command_attempt" to transferModeCommandAttempt.toString(),
                        "transfer_mode_callback_received" to transferModeCommandCallbackReceived.toString(),
                        "transfer_mode_callback_latency_ms" to (transferModeCommandCallbackLatencyMs?.toString() ?: ""),
                        "transfer_mode_evidence_received" to transferModeCommandEvidenceReceived.toString(),
                        "expected_official_p2p_name" to expectedOfficialP2pName(),
                        "selected_download_network" to selectedDownloadNetworkSummary,
                        "seen_p2p_peers" to seenPeers.joinToString(", "),
                        "active_glasses_session" to (GlassesSessionCoordinator.currentSession()?.name ?: "none"),
                        "ota_active" to otaManager.isActive.toString(),
                    ),
                )
            }
            .setPositiveButton("Try Again") { _, _ ->
                downloadSupportDialogShown = false
                startDataDownload()
            }
            .show()
    }

    private fun getDeviceIpFromBLE(): String? {
        // Prefer IP detected from BLE notifications, fall back to the
        // known sample IP if we have not seen one yet.
        val ipFromBle = bleIpBridge.ip.value
        if (!ipFromBle.isNullOrEmpty()) {
            Log.i("DataDownload", "Device IP from BleIpBridge: $ipFromBle")
            return ipFromBle
        }
        // No safe fallback: the glasses IP varies per session.
        return null
    }

    private enum class VendorMediaType(val progressLabel: String) {
        PHOTO("photo"),
        VIDEO("video"),
        AUDIO("audio"),
    }

    private data class VendorMediaItem(
        val fileName: String,
        val type: VendorMediaType,
    )
    
    private suspend fun downloadMediaList(deviceIp: String, sessionId: Long) {
        if (!isDownloadSessionActive(sessionId)) return
        if (downloadFlowMode == GlassesSyncFlow.OFFICIAL_HEYCYAN) {
            downloadVendorMediaList(deviceIp, sessionId)
            return
        }
        try {
            coroutineContext.ensureActive()
            // Lock the device IP for the whole transfer session.
            downloadResolvedHttpIp = deviceIp
            val url = "http://$deviceIp/files/media.config"
            Log.i("DataDownload", "Downloading media list from: $url")

            withContext(Dispatchers.Main) {
                if (!isDownloadSessionActive(sessionId)) return@withContext
                binding.progressTransfer.isIndeterminate = true
                updateDashboardState { state ->
                    state.copy(transfer = state.transfer.copy(progress = null))
                }
                setTransferDetail("Fetching media list...")
            }

            var content: String? = null
            httpGet(URL(url), 10000, 30000) { stream, _ ->
                content = stream.bufferedReader().use { it.readText() }
            }

            coroutineContext.ensureActive()
            if (!isDownloadSessionActive(sessionId)) return

            content?.let { mediaConfig ->
                Log.i("DataDownload", "=== MEDIA CONFIG CONTENT ===")
                Log.i("DataDownload", mediaConfig)
                Log.i("DataDownload", "=== END MEDIA CONFIG ===")
                parseMediaList(mediaConfig, deviceIp, sessionId)
            } ?: run {
                Log.e("DataDownload", "Failed to download media list.")
                withContext(Dispatchers.Main) {
                    if (isDownloadSessionActive(sessionId)) {
                        showDownloadError("Failed to download media list.")
                    }
                }
            }
        } catch (e: CancellationException) {
            Log.i("DataDownload", "Media list download cancelled for session=$sessionId")
            throw e
        } catch (e: Exception) {
            if (!isDownloadSessionActive(sessionId)) return
            Log.e("DataDownload", "Error downloading media list: ${e.message}", e)
            withContext(Dispatchers.Main) {
                if (!isDownloadSessionActive(sessionId)) return@withContext
                when (e) {
                    is java.io.IOException -> {
                        if (e.message?.contains("Cleartext HTTP traffic") == true) {
                            showDownloadError("Network security blocked HTTP connection. Please check app settings.")
                        } else if (e.message?.contains("Failed to connect") == true) {
                            showDownloadError("Cannot connect to glasses device. Please ensure P2P connection is established.")
                        } else {
                            showDownloadError("Network error: ${e.message}")
                        }
                    }
                    else -> showDownloadError("Download failed: ${e.message}")
                }
            }
        }
    }

    private suspend fun downloadVendorMediaList(deviceIp: String, sessionId: Long) {
        val downloader = vendorAlbumDownloader
        if (downloader == null) {
            withContext(Dispatchers.Main) {
                if (isDownloadSessionActive(sessionId)) {
                    showDownloadError("HeyCyan downloader was not initialized.")
                }
            }
            return
        }

        val url = "http://$deviceIp/files/media.config"
        Log.i("DataDownload", "HeyCyan vendor downloader fetching media list: $url")
        withContext(Dispatchers.Main) {
            if (isDownloadSessionActive(sessionId)) {
                binding.progressTransfer.isIndeterminate = true
                updateDashboardState { state ->
                    state.copy(transfer = state.transfer.copy(progress = null))
                }
                setTransferDetail("Fetching media list with HeyCyan downloader...")
            }
        }

        var lastError = "unknown error"
        repeat(2) { attemptIndex ->
            coroutineContext.ensureActive()
            if (!isDownloadSessionActive(sessionId)) return
            val attempt = attemptIndex + 1
            val result = downloader.download(url, "media.config")
            if (result.isSuccess) {
                val configFile = result.file ?: return
                val content = runCatching { configFile.readText() }.getOrNull()
                configFile.delete()
                if (content != null) {
                    Log.i("DataDownload", "HeyCyan vendor downloader fetched media.config on attempt $attempt/2")
                    parseMediaList(content, deviceIp, sessionId)
                    return
                }
                lastError = "downloaded media.config could not be read"
            } else {
                lastError = "code=${result.errorCode}, detail=${result.errorDetail}"
                Log.w(
                    "DataDownload",
                    "HeyCyan media.config download failed (attempt $attempt/2): $lastError"
                )
            }
        }

        withContext(Dispatchers.Main) {
            if (isDownloadSessionActive(sessionId)) {
                showDownloadError("Failed to download media list with HeyCyan downloader: $lastError")
            }
        }
    }
    
        private suspend fun parseMediaList(content: String, deviceIp: String, sessionId: Long) {
            // Parse the media configuration file content - this is a text file containing media file names.
            Log.i("DataDownload", "Parsing media list content...")
            
            try {
                coroutineContext.ensureActive()
                if (!isDownloadSessionActive(sessionId)) return
                // Split by line, each line should be a file name
                val lines = content.trim().lines()
                val jpgFiles = mutableListOf<String>()
                val mp4Files = mutableListOf<String>()
                val opusFiles = mutableListOf<String>()
                val vendorQueue = mutableListOf<VendorMediaItem>()
                var otherFiles = 0
                
                lines.forEach { line ->
                    val trimmedLine = line.trim()
                    if (trimmedLine.isNotEmpty()) {
                        when {
                            trimmedLine.endsWith(".jpg", ignoreCase = true) ||
                                trimmedLine.endsWith(".jpeg", ignoreCase = true) -> {
                                jpgFiles.add(trimmedLine)
                                vendorQueue.add(VendorMediaItem(trimmedLine, VendorMediaType.PHOTO))
                                Log.i("DataDownload", "Found JPG file: $trimmedLine")
                            }

                            trimmedLine.endsWith(".mp4", ignoreCase = true) -> {
                                mp4Files.add(trimmedLine)
                                vendorQueue.add(VendorMediaItem(trimmedLine, VendorMediaType.VIDEO))
                                Log.i("DataDownload", "Found MP4 file: $trimmedLine")
                            }

                            trimmedLine.endsWith(".opus", ignoreCase = true) -> {
                                opusFiles.add(trimmedLine)
                                vendorQueue.add(VendorMediaItem(trimmedLine, VendorMediaType.AUDIO))
                                Log.i("DataDownload", "Found OPUS file: $trimmedLine")
                            }

                            else -> {
                                otherFiles++
                                Log.i("DataDownload", "Found other file: $trimmedLine")
                            }
                        }
                    }
                }

                Log.i(
                    "DataDownload",
                    "Media list parsed: jpg=${jpgFiles.size}, mp4=${mp4Files.size}, opus=${opusFiles.size}, other=$otherFiles"
                )

                if (isHighQualityImageTransfer()) {
                    if (jpgFiles.isEmpty()) {
                        finishHighQualityImageFailure(
                            "The glasses did not report a full-resolution JPG in media.config.",
                        )
                    } else {
                        downloadLatestHighQualityImage(jpgFiles, deviceIp, sessionId)
                    }
                    return
                }

                withContext(Dispatchers.Main) {
                    if (!isDownloadSessionActive(sessionId)) return@withContext
                    setTransferPlan(jpgFiles.size, mp4Files.size, opusFiles.size)
                    val total = jpgFiles.size + mp4Files.size + opusFiles.size
                    setTransferDetail("Preparing downloads (0/$total)...")
                }

                if (jpgFiles.isEmpty() && mp4Files.isEmpty() && opusFiles.isEmpty()) {
                    Log.w("DataDownload", "No JPG/MP4/OPUS files found in media.config")
                    withContext(Dispatchers.Main) {
                        if (isDownloadSessionActive(sessionId)) {
                            showDownloadError("No JPG/MP4/OPUS files found in media.config")
                        }
                    }
                    return
                }

                withContext(Dispatchers.Main) {
                    if (isDownloadSessionActive(sessionId)) {
                        Toast.makeText(
                            this@MainActivity,
                            "Transferring media files. Please do not close or exit the app during transfer.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }

                // Download everything we understand. Keep P2P bound until all downloads finish.
                if (downloadFlowMode == GlassesSyncFlow.OFFICIAL_HEYCYAN) {
                    downloadAllMediaFilesVendor(vendorQueue, deviceIp, sessionId)
                } else {
                    downloadAllMediaFiles(jpgFiles, mp4Files, opusFiles, deviceIp, sessionId)
                }
                
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!isDownloadSessionActive(sessionId)) return
                Log.e("DataDownload", "Error parsing media list: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    if (isDownloadSessionActive(sessionId)) {
                        showDownloadError("Failed to parse media list: ${e.message}")
                    }
                }
            }
        }

    private suspend fun downloadLatestHighQualityImage(
        jpgFiles: List<String>,
        deviceIp: String,
        sessionId: Long,
    ) {
        val request = highQualityImageRequest ?: return
        val latestFileName = jpgFiles.maxWithOrNull(
            compareBy<String> { parseTakenTimeMillisFromFilename(it) ?: Long.MIN_VALUE }
                .thenBy { it },
        ) ?: run {
            finishHighQualityImageFailure("No full-resolution JPG was available for this image question.")
            return
        }

        val outputDir = (getExternalFilesDir("image-questions") ?: File(filesDir, "image-questions"))
            .apply { mkdirs() }
        val safeName = File(latestFileName).name
        val output = File(outputDir, "AI_Full_${System.currentTimeMillis()}_$safeName")
        val partial = File(output.parentFile, "${output.name}.part")
        val url = URL("http://$deviceIp/files/$latestFileName")
        val downloadStartedAtMs = System.currentTimeMillis()

        withContext(Dispatchers.Main) {
            if (isDownloadSessionActive(sessionId)) {
                setTransferDetail("Downloading latest full-resolution photo...")
            }
        }
        Log.i("ImageQuestion", "Requesting full-resolution HeyCyan photo: $url")

        val downloaded = runCatching {
            partial.delete()
            httpGet(url, connectTimeoutMs = 15_000, readTimeoutMs = 60_000) { input, _ ->
                partial.outputStream().buffered(128 * 1024).use { outputStream ->
                    input.copyTo(outputStream, bufferSize = 128 * 1024)
                }
            }
        }.getOrElse { error ->
            Log.e("ImageQuestion", "Full-resolution image download failed", error)
            false
        }

        if (!downloaded || !partial.exists() || partial.length() <= 0L) {
            partial.delete()
            finishHighQualityImageFailure("Could not download the latest full-resolution photo from the glasses.")
            return
        }
        if (!partial.renameTo(output)) {
            partial.delete()
            finishHighQualityImageFailure("Could not prepare the full-resolution photo for the AI provider.")
            return
        }
        if (!isDownloadSessionActive(sessionId) || !isDecodableImageFile(output)) {
            output.delete()
            finishHighQualityImageFailure("The full-resolution photo transfer was incomplete or invalid.")
            return
        }

        val transferDurationMs = System.currentTimeMillis() - request.captureStartedAtMs
        completeHighQualityImageTransfer(output, transferDurationMs)
    }

    private fun completeHighQualityImageTransfer(file: File, transferDurationMs: Long) {
        if (!isHighQualityImageTransfer()) {
            file.delete()
            return
        }
        highQualityImageRequest = null
        finishDownloadInitialPhase("full-resolution image downloaded")
        teardownDownloadP2pSession(
            sendExitTransfer = true,
            hideTransferUi = true,
            onTeardownComplete = {
                mediaDownloadPurpose = MediaDownloadPurpose.FULL_SYNC
                runOnUiThread {
                    onImageReadyForQuestion(
                        imagePath = file.absolutePath,
                        source = ImageQuestionSource.HIGH_QUALITY,
                        transferDurationMs = transferDurationMs,
                    )
                }
            },
        )
    }

    private fun finishHighQualityImageFailure(reason: String) {
        val request = highQualityImageRequest ?: return
        cancelParallelAudioQuestion()
        runOnUiThread {
            if (!isHighQualityImageTransfer()) return@runOnUiThread
            Log.e("ImageQuestion", "Full-resolution retrieval failed: $reason")
            finishDownloadInitialPhase("full-resolution image failed")
            teardownDownloadP2pSession(
                sendExitTransfer = true,
                hideTransferUi = true,
                onTeardownComplete = {
                    mediaDownloadPurpose = MediaDownloadPurpose.FULL_SYNC
                    runOnUiThread { showHighQualityImageFailureDialog(request, reason) }
                },
            )
        }
    }

    private fun showHighQualityImageFailureDialog(
        request: HighQualityImageRequest,
        reason: String,
    ) {
        if (isFinishing || isDestroyed) return
        check(
            ImageQuestionSourcePolicy.onHighQualityFailure() ==
                com.fersaiyan.cyanbridge.ai.image.ImageSourceResolution.AWAITING_EXPLICIT_FALLBACK_CHOICE,
        )
        AlertDialog.Builder(this)
            .setTitle("High-quality image unavailable")
            .setMessage("$reason\n\nCyanBridge has not sent a preview automatically.")
            .setPositiveButton("Retry high quality") { _, _ ->
                when (ImageQuestionSourcePolicy.resolveHighQualityFailure(HighQualityFailureChoice.RETRY_HIGH_QUALITY)) {
                    com.fersaiyan.cyanbridge.ai.image.ImageSourceResolution.HIGH_QUALITY -> {
                        highQualityImageRequest = request
                        pendingImageQuestionSource = ImageQuestionSource.HIGH_QUALITY
                        requestHighQualityImageForQuestion(request.sourceTag)
                    }
                    else -> Unit
                }
            }
            .setNegativeButton("Use fast preview") { _, _ ->
                if (
                    ImageQuestionSourcePolicy.resolveHighQualityFailure(HighQualityFailureChoice.USE_FAST_PREVIEW) ==
                    com.fersaiyan.cyanbridge.ai.image.ImageSourceResolution.FAST_PREVIEW
                ) {
                    highQualityImageRequest = null
                    pendingImageQuestionSource = ImageQuestionSource.FAST_PREVIEW
                    requestImageThumbnailForQuestion(request.sourceTag)
                }
            }
            .setNeutralButton("Cancel") { _, _ ->
                if (
                    ImageQuestionSourcePolicy.resolveHighQualityFailure(HighQualityFailureChoice.CANCEL) ==
                    com.fersaiyan.cyanbridge.ai.image.ImageSourceResolution.CANCELLED
                ) {
                    highQualityImageRequest = null
                    clearPendingVoiceImageQuestion(request.sourceTag)
                }
            }
            .show()
    }

    private suspend fun downloadAllMediaFilesVendor(
        files: List<VendorMediaItem>,
        deviceIp: String,
        sessionId: Long,
    ) {
        val downloader = vendorAlbumDownloader ?: return
        Log.i("DataDownload", "Starting HeyCyan vendor media queue: files=${files.size}")
        officialMediaErrorCount = 0

        for (item in files) {
            coroutineContext.ensureActive()
            if (!isDownloadSessionActive(sessionId)) return

            var imported = false
            for (attempt in 1..2) {
                if (imported || !isDownloadSessionActive(sessionId) || officialMediaErrorCount > 1) break
                coroutineContext.ensureActive()

                val url = "http://$deviceIp/files/${item.fileName}"
                val startedAtMs = System.currentTimeMillis()
                withContext(Dispatchers.Main) {
                    if (isDownloadSessionActive(sessionId)) {
                        val (done, total) = vendorTypeProgress(item.type)
                        setTransferDetail(
                            "Downloading ${item.type.progressLabel} ${done + 1}/$total with HeyCyan downloader..."
                        )
                    }
                }
                Log.i(
                    "DataDownload",
                    "HeyCyan queue downloading ${item.fileName} (attempt $attempt/2): $url"
                )

                val result = downloader.download(url, item.fileName) { downloaded, total ->
                    maybeReportFileProgress(
                        sessionId = sessionId,
                        mediaType = item.type.progressLabel,
                        fileName = item.fileName,
                        bytesCopied = downloaded,
                        totalBytes = total,
                        startedAtMs = startedAtMs,
                    )
                }

                if (result.isSuccess) {
                    val file = result.file
                    imported = file != null && importVendorMediaFile(file, item)
                    file?.delete()
                    if (imported) {
                        Log.i("DataDownload", "HeyCyan queue imported: ${item.fileName}")
                    } else {
                        officialMediaErrorCount++
                        Log.e(
                            "DataDownload",
                            "HeyCyan queue could not import ${item.fileName}; errors=$officialMediaErrorCount"
                        )
                    }
                } else {
                    officialMediaErrorCount++
                    Log.w(
                        "DataDownload",
                        "HeyCyan queue download failed for ${item.fileName} (attempt $attempt/2, errors=$officialMediaErrorCount): code=${result.errorCode}, detail=${result.errorDetail}"
                    )
                }

                if (!imported && officialMediaErrorCount <= 1) {
                    withContext(Dispatchers.Main) {
                        if (isDownloadSessionActive(sessionId)) {
                            setTransferDetail("Download failed; retrying ${item.fileName}...")
                        }
                    }
                }
            }

            if (!imported) {
                withContext(Dispatchers.Main) {
                    if (isDownloadSessionActive(sessionId)) {
                        showDownloadError(
                            "HeyCyan flow stopped after repeated media download failures. Please retry sync."
                        )
                    }
                }
                return
            }

            withContext(Dispatchers.Main) {
                if (!isDownloadSessionActive(sessionId)) return@withContext
                onTransferItemDone(
                    when (item.type) {
                        VendorMediaType.PHOTO -> "jpg"
                        VendorMediaType.VIDEO -> "mp4"
                        VendorMediaType.AUDIO -> "opus"
                    }
                )
                setTransferDetail("Downloaded ${transferItemSummary()}")
            }
        }

        withContext(Dispatchers.Main) {
            if (isDownloadSessionActive(sessionId)) {
                showDownloadSuccess("All ${files.size} files downloaded successfully!")
            }
        }
    }

    private fun vendorTypeProgress(type: VendorMediaType): Pair<Int, Int> {
        return when (type) {
            VendorMediaType.PHOTO -> transferDoneJpg to transferTotalJpg
            VendorMediaType.VIDEO -> transferDoneMp4 to transferTotalMp4
            VendorMediaType.AUDIO -> transferDoneOpus to transferTotalOpus
        }
    }

    private suspend fun importVendorMediaFile(file: File, item: VendorMediaItem): Boolean {
        val takenMs = parseTakenTimeMillisFromFilename(item.fileName) ?: System.currentTimeMillis()
        return when (item.type) {
            VendorMediaType.PHOTO -> file.inputStream().use { input ->
                saveJpegToGallery(input, item.fileName, takenMs).success
            }

            VendorMediaType.VIDEO -> file.inputStream().use { input ->
                saveMp4ToGallery(
                    input = input,
                    displayName = item.fileName,
                    takenTimeMs = takenMs,
                    contentLength = file.length(),
                ).success
            }

            VendorMediaType.AUDIO -> {
                val rawBytes = runCatching { file.readBytes() }.getOrNull() ?: return false
                val wrapped = wrapOpusIfNeeded(rawBytes)
                val saved = saveOpusToLibrary(
                    payloadBytes = wrapped.first,
                    rawBytesSize = rawBytes.size,
                    payloadNote = wrapped.second,
                    displayName = item.fileName,
                    takenTimeMs = takenMs,
                )
                if (saved.success) {
                    runCatching {
                        GlassesSyncedAudioIngestor.persistDownloadedAudio(
                            context = applicationContext,
                            displayName = item.fileName,
                            payloadBytes = wrapped.first,
                            takenTimeMs = takenMs,
                        )
                    }.onFailure {
                        Log.e("DataDownload", "Failed to persist synced audio session for ${item.fileName}", it)
                    }
                }
                saved.success
            }
        }
    }

    private suspend fun downloadAllMediaFiles(
        jpgFiles: List<String>,
        mp4Files: List<String>,
        opusFiles: List<String>,
        deviceIp: String,
        sessionId: Long,
    ) {
            Log.i(
                "DataDownload",
                "Starting download: jpg=${jpgFiles.size}, mp4=${mp4Files.size}, opus=${opusFiles.size}"
            )

            val totalAll = jpgFiles.size + mp4Files.size + opusFiles.size
            withContext(Dispatchers.Main) {
                if (!isDownloadSessionActive(sessionId)) return@withContext
                if (totalAll > 0) {
                    binding.progressTransfer.isIndeterminate = false
                    updateDashboardState { state ->
                        state.copy(transfer = state.transfer.copy(progress = 0f))
                    }
                }
                setTransferDetail("Downloading 0/$totalAll...")
            }
            
            var jpgSuccess = 0
            var jpgFail = 0
            var mp4Success = 0
            var mp4Fail = 0
            var opusSuccess = 0
            var opusFail = 0
            
            for ((index, fileName) in jpgFiles.withIndex()) {
                try {
                    coroutineContext.ensureActive()
                    if (!isDownloadSessionActive(sessionId)) return
                    withContext(Dispatchers.Main) {
                        if (!isDownloadSessionActive(sessionId)) return@withContext
                        setTransferDetail("Downloading photo ${index + 1}/${jpgFiles.size}...")
                    }
                    Log.i("DataDownload", "Downloading file ${index + 1}/${jpgFiles.size}: $fileName")

                    val success = downloadSingleJpgFile(fileName, deviceIp)
                    if (success) {
                        jpgSuccess++
                        Log.i("DataDownload", "✓ Successfully downloaded: $fileName")
                    } else {
                        jpgFail++
                        Log.e("DataDownload", "✗ Failed to download: $fileName")
                    }

                    withContext(Dispatchers.Main) {
                        if (!isDownloadSessionActive(sessionId)) return@withContext
                        onTransferItemDone("jpg")
                        setTransferDetail("Downloaded ${transferItemSummary()}")
                    }
                    
                    // Add a small delay to avoid excessively fast requests
                    delay(500)
                    
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (!isDownloadSessionActive(sessionId)) return
                    jpgFail++
                    Log.e("DataDownload", "Error downloading $fileName: ${e.message}", e)

                    withContext(Dispatchers.Main) {
                        if (!isDownloadSessionActive(sessionId)) return@withContext
                        onTransferItemDone("jpg")
                        setTransferDetail("Downloaded ${transferItemSummary()} (with errors)")
                    }
                }
            }

            for ((index, fileName) in mp4Files.withIndex()) {
                try {
                    coroutineContext.ensureActive()
                    if (!isDownloadSessionActive(sessionId)) return
                    withContext(Dispatchers.Main) {
                        if (!isDownloadSessionActive(sessionId)) return@withContext
                        setTransferDetail("Downloading video ${index + 1}/${mp4Files.size}...")
                    }
                    Log.i("DataDownload", "Downloading video ${index + 1}/${mp4Files.size}: $fileName")

                    val success = downloadSingleMp4File(fileName, deviceIp, sessionId)
                    if (success) {
                        mp4Success++
                        Log.i("DataDownload", "✓ Successfully downloaded: $fileName")
                    } else {
                        mp4Fail++
                        Log.e("DataDownload", "✗ Failed to download: $fileName")
                        if (shouldAbortOfficialMediaTransfer(fileName)) {
                            withContext(Dispatchers.Main) {
                                if (isDownloadSessionActive(sessionId)) {
                                    showDownloadError("Official flow stopped after repeated media download failures. Please retry sync.")
                                }
                            }
                            return
                        }
                    }

                    withContext(Dispatchers.Main) {
                        if (!isDownloadSessionActive(sessionId)) return@withContext
                        onTransferItemDone("mp4")
                        setTransferDetail("Downloaded ${transferItemSummary()}")
                    }

                    // Videos are larger; be gentler.
                    delay(800)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (!isDownloadSessionActive(sessionId)) return
                    mp4Fail++
                    Log.e("DataDownload", "Error downloading $fileName: ${e.message}", e)
                    if (shouldAbortOfficialMediaTransfer(fileName)) {
                        withContext(Dispatchers.Main) {
                            if (isDownloadSessionActive(sessionId)) {
                                showDownloadError("Official flow stopped after repeated media download failures. Please retry sync.")
                            }
                        }
                        return
                    }

                    withContext(Dispatchers.Main) {
                        if (!isDownloadSessionActive(sessionId)) return@withContext
                        onTransferItemDone("mp4")
                        setTransferDetail("Downloaded ${transferItemSummary()} (with errors)")
                    }
                }
            }

            for ((index, fileName) in opusFiles.withIndex()) {
                try {
                    coroutineContext.ensureActive()
                    if (!isDownloadSessionActive(sessionId)) return
                    withContext(Dispatchers.Main) {
                        if (!isDownloadSessionActive(sessionId)) return@withContext
                        setTransferDetail("Downloading audio ${index + 1}/${opusFiles.size}...")
                    }
                    Log.i("DataDownload", "Downloading audio ${index + 1}/${opusFiles.size}: $fileName")

                    val success = downloadSingleOpusFile(fileName, deviceIp)
                    if (success) {
                        opusSuccess++
                        Log.i("DataDownload", "✓ Successfully downloaded: $fileName")
                    } else {
                        opusFail++
                        Log.e("DataDownload", "✗ Failed to download: $fileName")
                    }

                    withContext(Dispatchers.Main) {
                        if (!isDownloadSessionActive(sessionId)) return@withContext
                        onTransferItemDone("opus")
                        setTransferDetail("Downloaded ${transferItemSummary()}")
                    }

                    delay(500)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (!isDownloadSessionActive(sessionId)) return
                    opusFail++
                    Log.e("DataDownload", "Error downloading $fileName: ${e.message}", e)

                    withContext(Dispatchers.Main) {
                        if (!isDownloadSessionActive(sessionId)) return@withContext
                        onTransferItemDone("opus")
                        setTransferDetail("Downloaded ${transferItemSummary()} (with errors)")
                    }
                }
            }
            
            // Show final result
            val totalSuccess = jpgSuccess + mp4Success + opusSuccess
            val totalFail = jpgFail + mp4Fail + opusFail
            Log.i(
                "DataDownload",
                "Download completed: jpg=$jpgSuccess/${jpgFiles.size} ok, mp4=$mp4Success/${mp4Files.size} ok, opus=$opusSuccess/${opusFiles.size} ok, failed=$totalFail"
            )
            
            withContext(Dispatchers.Main) {
                if (!isDownloadSessionActive(sessionId)) return@withContext
                if (totalFail == 0) {
                    showDownloadSuccess("All $totalSuccess files downloaded successfully!")
                } else {
                    showDownloadError("Download completed with errors: $totalSuccess successful, $totalFail failed")
                }
            }
    }

    private fun shouldAbortOfficialMediaTransfer(fileName: String): Boolean {
        if (downloadFlowMode != GlassesSyncFlow.OFFICIAL_HEYCYAN) return false
        officialMediaErrorCount++
        Log.w(
            "DataDownload",
            "Official flow media error $officialMediaErrorCount/2 for $fileName; aborting=${officialMediaErrorCount > 1}"
        )
        return officialMediaErrorCount > 1
    }
    
    private suspend fun downloadSingleJpgFile(fileName: String, deviceIp: String): Boolean {
        return try {
            val url = "http://$deviceIp/files/$fileName"
            Log.i("DataDownload", "Downloading: $url")

            var saved: GallerySaveResult? = null
            httpGet(URL(url), 10000, 30000) { stream, _ ->
                val takenMs = parseTakenTimeMillisFromFilename(fileName) ?: System.currentTimeMillis()
                saved = saveJpegToGallery(stream, fileName, takenMs)
            }

            val savedResult = saved
            if (savedResult != null && savedResult.bytes > 0) {
                Log.i("DataDownload", "File downloaded: $fileName (${savedResult.bytes} bytes)")
            }
            if (savedResult?.success == true) {
                Log.i("DataDownload", "Saved to gallery: name=$fileName uri=${savedResult.uri}")
                true
            } else {
                Log.e("DataDownload", "Failed to download/save: $fileName")
                false
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e("DataDownload", "Error downloading $fileName: ${e.message}", e)
            false
        }
    }

    private suspend fun downloadSingleMp4File(fileName: String, deviceIp: String, sessionId: Long): Boolean {
        return try {
            val url = "http://$deviceIp/files/$fileName"
            Log.i("DataDownload", "Downloading: $url")

            var saved: GallerySaveResult? = null
            val startedAtMs = System.currentTimeMillis()
            httpGet(URL(url), 15000, 180000) { stream, contentLength ->
                val takenMs = parseTakenTimeMillisFromFilename(fileName) ?: System.currentTimeMillis()
                saved = saveMp4ToGallery(stream, fileName, takenMs, contentLength) { bytesCopied, totalBytes ->
                    maybeReportFileProgress(
                        sessionId = sessionId,
                        mediaType = "video",
                        fileName = fileName,
                        bytesCopied = bytesCopied,
                        totalBytes = totalBytes,
                        startedAtMs = startedAtMs,
                    )
                }
            }

            val savedResult = saved
            if (savedResult != null && savedResult.bytes > 0) {
                Log.i("DataDownload", "File downloaded: $fileName (${savedResult.bytes} bytes)")
            }
            if (savedResult?.success == true) {
                Log.i("DataDownload", "Saved to gallery: name=$fileName uri=${savedResult.uri}")
                true
            } else {
                Log.e("DataDownload", "Failed to download/save: $fileName")
                false
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e("DataDownload", "Error downloading $fileName: ${e.message}", e)
            false
        }
    }

    private suspend fun downloadSingleOpusFile(fileName: String, deviceIp: String): Boolean {
        return try {
            val url = "http://$deviceIp/files/$fileName"
            Log.i("DataDownload", "Downloading: $url")

            var saved: GallerySaveResult? = null
            var payloadBytes: ByteArray? = null
            var rawBytesSize = 0
            var payloadNote = "raw"
            val takenMs = parseTakenTimeMillisFromFilename(fileName) ?: System.currentTimeMillis()
            httpGet(URL(url), 15000, 120000) { stream, _ ->
                val rawBytes = readAllBytes(stream)
                rawBytesSize = rawBytes.size
                val wrapped = wrapOpusIfNeeded(rawBytes)
                payloadBytes = wrapped.first
                payloadNote = wrapped.second
                saved = saveOpusToLibrary(
                    payloadBytes = wrapped.first,
                    rawBytesSize = rawBytes.size,
                    payloadNote = wrapped.second,
                    displayName = fileName,
                    takenTimeMs = takenMs,
                )
            }

            val savedResult = saved
            if (savedResult != null && savedResult.bytes > 0) {
                Log.i("DataDownload", "File downloaded: $fileName (${savedResult.bytes} bytes)")
            }
            if (savedResult?.success == true) {
                payloadBytes?.let { bytes ->
                    runCatching {
                        val persisted = GlassesSyncedAudioIngestor.persistDownloadedAudio(
                            context = applicationContext,
                            displayName = fileName,
                            payloadBytes = bytes,
                            takenTimeMs = takenMs,
                        )
                        if (persisted.createdSessionId != null) {
                            Log.i(
                                "DataDownload",
                                "Synced audio persisted for recordings/transcription: sessionId=${persisted.createdSessionId} path=${persisted.localPath}"
                            )
                        }
                    }.onFailure {
                        Log.e("DataDownload", "Failed to persist synced audio session for $fileName: ${it.message}", it)
                    }
                }
                Log.i("DataDownload", "Saved to library: name=$fileName uri=${savedResult.uri}")
                true
            } else {
                Log.e("DataDownload", "Failed to download/save: $fileName (raw=$rawBytesSize mode=$payloadNote)")
                false
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e("DataDownload", "Error downloading $fileName: ${e.message}", e)
            false
        }
    }
    
    private data class GallerySaveResult(
        val success: Boolean,
        val uri: String?,
        val bytes: Long,
    )

    private fun parseTakenTimeMillisFromFilename(fileName: String): Long? {
        // The glasses filenames look like: yyyyMMddHHmmssSSS?.jpg
        // Example: 20260127095159018.jpg
        val digits = fileName.takeWhile { it.isDigit() }
        if (digits.length < 14) return null

        return try {
            val base = digits.substring(0, 14)
            val sdf = SimpleDateFormat("yyyyMMddHHmmss", Locale.US)
            val baseDate = sdf.parse(base) ?: return null
            val msPart = digits.substring(14).take(3)
            val extraMs = msPart.toIntOrNull() ?: 0
            baseDate.time + extraMs
        } catch (_: Exception) {
            null
        }
    }

    private fun saveJpegToGallery(input: InputStream, displayName: String, takenTimeMs: Long): GallerySaveResult {
        return try {
            val resolver = contentResolver

            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.DATE_TAKEN, takenTimeMs)
                put(MediaStore.Images.Media.DATE_ADDED, takenTimeMs / 1000)
                put(MediaStore.Images.Media.DATE_MODIFIED, takenTimeMs / 1000)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, SyncedMediaFolder.relativePath)
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }

            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return GallerySaveResult(false, null, 0)

            var bytes = 0L
            try {
                resolver.openOutputStream(uri, "w")?.use { out ->
                    val buffer = ByteArray(8 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                        bytes += read
                    }
                    out.flush()
                } ?: run {
                    resolver.delete(uri, null, null)
                    return GallerySaveResult(false, null, bytes)
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val done = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
                    resolver.update(uri, done, null, null)
                } else {
                    // Pre-Android 10: some galleries need an explicit media scan.
                    MediaScannerConnection.scanFile(
                        this,
                        arrayOf(uri.toString()),
                        arrayOf("image/jpeg"),
                        null
                    )
                }

                GallerySaveResult(true, uri.toString(), bytes)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                Log.e("DataDownload", "Gallery write failed for $displayName: ${e.message}", e)
                GallerySaveResult(false, uri.toString(), bytes)
            }
        } catch (e: Exception) {
            Log.e("DataDownload", "saveJpegToGallery failed for $displayName: ${e.message}", e)
            GallerySaveResult(false, null, 0)
        }
    }

    private fun saveMp4ToGallery(
        input: InputStream,
        displayName: String,
        takenTimeMs: Long,
        contentLength: Long,
        onBytesCopied: ((Long, Long) -> Unit)? = null,
    ): GallerySaveResult {
        return try {
            val resolver = contentResolver

            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.DATE_TAKEN, takenTimeMs)
                put(MediaStore.Video.Media.DATE_ADDED, takenTimeMs / 1000)
                put(MediaStore.Video.Media.DATE_MODIFIED, takenTimeMs / 1000)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // Keep videos in the same DCIM/CyanBridge folder as photos.
                    put(MediaStore.Video.Media.RELATIVE_PATH, SyncedMediaFolder.relativePath)
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
            }

            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: return GallerySaveResult(false, null, 0)

            var bytes = 0L
            try {
                resolver.openOutputStream(uri, "w")?.use { out ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                        bytes += read
                        onBytesCopied?.invoke(bytes, contentLength)
                    }
                    out.flush()
                } ?: run {
                    resolver.delete(uri, null, null)
                    return GallerySaveResult(false, null, bytes)
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val done = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                    resolver.update(uri, done, null, null)
                }

                GallerySaveResult(true, uri.toString(), bytes)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                Log.e("DataDownload", "Gallery video write failed for $displayName: ${e.message}", e)
                GallerySaveResult(false, uri.toString(), bytes)
            }
        } catch (e: Exception) {
            Log.e("DataDownload", "saveMp4ToGallery failed for $displayName: ${e.message}", e)
            GallerySaveResult(false, null, 0)
        }
    }

    private fun saveOpusToLibrary(
        payloadBytes: ByteArray,
        rawBytesSize: Int,
        payloadNote: String,
        displayName: String,
        takenTimeMs: Long,
    ): GallerySaveResult {
        return try {
            val resolver = contentResolver

            val headHex = bytesToHex(payloadBytes, 24)
            Log.i(
                "DataDownload",
                "OPUS save: name=$displayName, raw=$rawBytesSize bytes, out=${payloadBytes.size} bytes, mode=$payloadNote, head=$headHex"
            )

            val title = displayName.substringBeforeLast('.', displayName)
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
                // Use Ogg/Opus container when possible.
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/ogg")
                put(MediaStore.Audio.Media.TITLE, title)
                put(MediaStore.Audio.Media.IS_MUSIC, 0)
                put(MediaStore.MediaColumns.DATE_ADDED, takenTimeMs / 1000)
                put(MediaStore.MediaColumns.DATE_MODIFIED, takenTimeMs / 1000)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // Keep alongside photos/videos per your preference (DCIM/CyanBridge).
                    put(MediaStore.MediaColumns.RELATIVE_PATH, SyncedMediaFolder.relativePath)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }

            val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                ?: return GallerySaveResult(false, null, 0)

            var bytes = 0L
            try {
                resolver.openOutputStream(uri, "w")?.use { out ->
                    out.write(payloadBytes)
                    bytes = payloadBytes.size.toLong()
                    out.flush()
                } ?: run {
                    resolver.delete(uri, null, null)
                    return GallerySaveResult(false, null, bytes)
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                    resolver.update(uri, done, null, null)
                }

                GallerySaveResult(true, uri.toString(), bytes)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                Log.e("DataDownload", "Gallery audio write failed for $displayName: ${e.message}", e)
                GallerySaveResult(false, uri.toString(), bytes)
            }
        } catch (e: Exception) {
            Log.e("DataDownload", "saveOpusToLibrary failed for $displayName: ${e.message}", e)
            GallerySaveResult(false, null, 0)
        }
    }

    private fun readAllBytes(input: InputStream): ByteArray {
        val bos = ByteArrayOutputStream()
        val buffer = ByteArray(32 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            bos.write(buffer, 0, read)
        }
        return bos.toByteArray()
    }

    private fun bytesToHex(bytes: ByteArray, max: Int): String {
        val n = minOf(bytes.size, max)
        val sb = StringBuilder(n * 2)
        for (i in 0 until n) {
            sb.append(String.format("%02x", bytes[i]))
        }
        if (bytes.size > max) sb.append("...")
        return sb.toString()
    }

    private fun wrapOpusIfNeeded(raw: ByteArray): Pair<ByteArray, String> {
        if (raw.size >= 4 && raw[0].toInt() == 'O'.code && raw[1].toInt() == 'g'.code && raw[2].toInt() == 'g'.code && raw[3].toInt() == 'S'.code) {
            return raw to "ogg-already"
        }

        // Try to interpret the file as a sequence of length-prefixed Opus packets and wrap
        // them into a proper Ogg/Opus container so standard players (VLC) can open it.
        val packets = parseLengthPrefixedPackets(raw, littleEndian = true)
            ?: parseLengthPrefixedPackets(raw, littleEndian = false)
            ?: parseLengthPrefixedPackets1B(raw)
            ?: guessFixedSizePackets(raw)

        if (packets == null || packets.isEmpty()) {
            // Unknown/proprietary layout (the official app decodes these with jl_opus).
            return raw to "raw-unwrapped"
        }

        return try {
            val ogg = buildOggOpusFromPackets(packets, packetDurationMs = 40)
            ogg to "wrapped packets=${packets.size}"
        } catch (e: Exception) {
            Log.w("DataDownload", "Failed to wrap opus into ogg: ${e.message}")
            raw to "raw-unwrapped"
        }
    }

    private fun parseLengthPrefixedPackets(raw: ByteArray, littleEndian: Boolean): List<ByteArray>? {
        // Heuristic: repeated [u16 len][len bytes]...
        var i = 0
        val out = ArrayList<ByteArray>()
        while (i + 2 <= raw.size) {
            val b0 = raw[i].toInt() and 0xFF
            val b1 = raw[i + 1].toInt() and 0xFF
            val len = if (littleEndian) (b0 or (b1 shl 8)) else ((b0 shl 8) or b1)
            i += 2
            if (len <= 0 || len > 2000) return null
            if (i + len > raw.size) return null
            out.add(raw.copyOfRange(i, i + len))
            i += len
        }
        if (i != raw.size) return null
        // Require a few packets so we don't mis-detect.
        return if (out.size >= 3) out else null
    }

    private fun parseLengthPrefixedPackets1B(raw: ByteArray): List<ByteArray>? {
        // Heuristic: repeated [u8 len][len bytes]...
        var i = 0
        val out = ArrayList<ByteArray>()
        while (i + 1 <= raw.size) {
            val len = raw[i].toInt() and 0xFF
            i += 1
            if (len <= 0 || len > 255) return null
            if (i + len > raw.size) return null
            out.add(raw.copyOfRange(i, i + len))
            i += len
        }
        if (i != raw.size) return null
        return if (out.size >= 3) out else null
    }

    private fun guessFixedSizePackets(raw: ByteArray): List<ByteArray>? {
        // Last-resort heuristic: some devices store raw Opus packets back-to-back with a
        // fixed packet byte size. Try a few common sizes.
        if (raw.isEmpty()) return null
        // 40 bytes is especially common for these glasses (official app uses packetSize=40).
        val candidates = intArrayOf(40, 60, 80, 100, 120, 160, 200, 240, 320)
        for (size in candidates) {
            if (size <= 0) continue
            if (raw.size % size != 0) continue
            val count = raw.size / size
            if (count < 5) continue
            val out = ArrayList<ByteArray>(count)
            var i = 0
            while (i < raw.size) {
                out.add(raw.copyOfRange(i, i + size))
                i += size
            }
            return out
        }
        return null
    }

    private fun buildOggOpusFromPackets(packets: List<ByteArray>, packetDurationMs: Int): ByteArray {
        // Ogg/Opus expects OpusHead + OpusTags packets before audio packets.
        val serial = SecureRandom().nextInt()
        var seq = 0
        var granulePos: Long = 0

        val out = ByteArrayOutputStream()

        val opusHead = buildOpusHead(channels = 1, preSkip = 0)
        val opusTags = buildOpusTags(vendor = "CyanBridge")

        // Header pages
        writeOggPage(out, serial, seq++, granulePosition = 0, headerType = 0x02, packets = listOf(opusHead))
        writeOggPage(out, serial, seq++, granulePosition = 0, headerType = 0x00, packets = listOf(opusTags))

        // Audio pages
        val samplesPerPacket48k = (packetDurationMs * 48_000L) / 1000L
        val maxSegments = 255
        var idx = 0
        while (idx < packets.size) {
            val pagePackets = ArrayList<ByteArray>()
            var segCount = 0
            var localGranule = granulePos

            while (idx < packets.size) {
                val p = packets[idx]
                var neededSeg = (p.size + 254) / 255
                if (p.size % 255 == 0) neededSeg += 1
                if (segCount + neededSeg > maxSegments) break
                pagePackets.add(p)
                segCount += neededSeg
                localGranule += samplesPerPacket48k
                idx++
            }

            granulePos = localGranule
            val isLast = idx >= packets.size
            val headerType = if (isLast) 0x04 else 0x00
            writeOggPage(out, serial, seq++, granulePosition = granulePos, headerType = headerType, packets = pagePackets)
        }

        return out.toByteArray()
    }

    private fun buildOpusHead(channels: Int, preSkip: Int): ByteArray {
        // OpusHead (19 bytes)
        val b = ByteArrayOutputStream()
        b.write("OpusHead".toByteArray(Charsets.US_ASCII))
        b.write(1) // version
        b.write(channels and 0xFF)
        // pre-skip LE16
        b.write(preSkip and 0xFF)
        b.write((preSkip shr 8) and 0xFF)
        // input sample rate LE32 (Opus is coded at 48k internally)
        val sr = 48_000
        b.write(sr and 0xFF)
        b.write((sr shr 8) and 0xFF)
        b.write((sr shr 16) and 0xFF)
        b.write((sr shr 24) and 0xFF)
        // output gain LE16
        b.write(0)
        b.write(0)
        // channel mapping family (0 = mono/stereo)
        b.write(0)
        return b.toByteArray()
    }

    private fun buildOpusTags(vendor: String): ByteArray {
        val vendorBytes = vendor.toByteArray(Charsets.UTF_8)
        val b = ByteArrayOutputStream()
        b.write("OpusTags".toByteArray(Charsets.US_ASCII))
        writeLe32(b, vendorBytes.size)
        b.write(vendorBytes)
        // user comment list length = 0
        writeLe32(b, 0)
        return b.toByteArray()
    }

    private fun writeLe32(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write((v shr 8) and 0xFF)
        out.write((v shr 16) and 0xFF)
        out.write((v shr 24) and 0xFF)
    }

    private fun writeOggPage(
        out: ByteArrayOutputStream,
        serial: Int,
        seq: Int,
        granulePosition: Long,
        headerType: Int,
        packets: List<ByteArray>,
    ) {
        val segmentTable = ByteArrayOutputStream()
        val payload = ByteArrayOutputStream()

        for (p in packets) {
            var remaining = p.size
            var offset = 0
            while (remaining > 0) {
                val seg = minOf(255, remaining)
                segmentTable.write(seg)
                payload.write(p, offset, seg)
                offset += seg
                remaining -= seg
            }
            if (p.size % 255 == 0) {
                // Lacing: 255 indicates continuation; add 0 to terminate packet exactly on boundary.
                segmentTable.write(0)
            }
        }

        val segBytes = segmentTable.toByteArray()
        if (segBytes.size > 255) {
            throw IllegalStateException("Ogg page has too many segments: ${segBytes.size}")
        }
        val payloadBytes = payload.toByteArray()

        val header = ByteArrayOutputStream()
        header.write("OggS".toByteArray(Charsets.US_ASCII))
        header.write(0) // version
        header.write(headerType and 0xFF)
        writeLe64(header, granulePosition)
        writeLe32(header, serial)
        writeLe32(header, seq)
        // checksum placeholder
        writeLe32(header, 0)
        header.write(segBytes.size)
        header.write(segBytes)

        val pageBytes = header.toByteArray() + payloadBytes
        val crc = oggCrc(pageBytes)

        // Patch checksum at byte offset 22 (from start of OggS)
        pageBytes[22] = (crc and 0xFF).toByte()
        pageBytes[23] = ((crc shr 8) and 0xFF).toByte()
        pageBytes[24] = ((crc shr 16) and 0xFF).toByte()
        pageBytes[25] = ((crc shr 24) and 0xFF).toByte()

        out.write(pageBytes)
    }

    private fun writeLe64(out: ByteArrayOutputStream, v: Long) {
        out.write((v and 0xFF).toInt())
        out.write(((v shr 8) and 0xFF).toInt())
        out.write(((v shr 16) and 0xFF).toInt())
        out.write(((v shr 24) and 0xFF).toInt())
        out.write(((v shr 32) and 0xFF).toInt())
        out.write(((v shr 40) and 0xFF).toInt())
        out.write(((v shr 48) and 0xFF).toInt())
        out.write(((v shr 56) and 0xFF).toInt())
    }

    private val oggCrcTable: IntArray = run {
        val table = IntArray(256)
        for (i in 0 until 256) {
            var r = i shl 24
            for (j in 0 until 8) {
                r = if ((r and 0x80000000.toInt()) != 0) {
                    (r shl 1) xor 0x04C11DB7
                } else {
                    r shl 1
                }
            }
            table[i] = r
        }
        table
    }

    private fun oggCrc(data: ByteArray): Int {
        var crc = 0
        for (b in data) {
            val idx = ((crc ushr 24) xor (b.toInt() and 0xFF)) and 0xFF
            crc = (crc shl 8) xor oggCrcTable[idx]
        }
        return crc
    }

    private fun sendExitTransferModeIfRequested() {
        if (!downloadExitTransferRequested) return
        downloadExitTransferRequested = false
        // Tell the glasses to exit transfer mode (official app does this after downloads finish).
        try {
            LargeDataHandler.getInstance().glassesControl(
                byteArrayOf(0x02, 0x01, 0x09)
            ) { _, resp ->
                Log.i(
                    "DataDownload",
                    "glassesControl[0x02,0x01,0x09] -> dataType=${resp.dataType}, error=${resp.errorCode}",
                )
            }
        } catch (e: Exception) {
            Log.w("DataDownload", "Failed to send exit-transfer command [0x02,0x01,0x09]", e)
        }
    }

    private fun abandonDownloadP2pForBluetoothDisconnect() {
        val lease = mediaSessionLease
        if (
            lease == null &&
            downloadWifiP2pManager == null &&
            !downloadNotifyListenerRegistered &&
            !downloadP2pTeardownInProgress
        ) {
            return
        }

        Log.i("DataDownload", "Bluetooth disconnected; abandoning media-sync P2P resources")
        downloadCancelledByUser = true
        downloadAttemptJob?.cancel()
        downloadAttemptJob = null
        cancelDownloadSession()
        transferModeCommandTimeoutJob?.cancel()
        transferModeCommandTimeoutJob = null
        downloadInitialPhaseTimeoutJob?.cancel()
        downloadInitialPhaseTimeoutJob = null
        officialDisconnectRecoveryJob?.cancel()
        officialDisconnectRecoveryJob = null
        unbindProcessFromNetwork()

        if (downloadNotifyListenerRegistered) {
            runCatching { LargeDataHandler.getInstance().removeOutDeviceListener(2) }
            downloadNotifyListenerRegistered = false
        }

        val manager = downloadWifiP2pManager
        downloadWifiP2pCallback?.let { callback -> manager?.removeCallback(callback) }
        manager?.stopP2pOperations()
        manager?.cancelP2pConnection()
        manager?.unregisterReceiver()
        downloadWifiP2pManager = null
        downloadWifiP2pCallback = null
        downloadP2pConnected = false
        downloadInProgress = false
        downloadP2pNetwork = null
        downloadResolvedHttpIp = null
        downloadP2pTeardownInProgress = false
        downloadExitTransferRequested = false
        releaseExclusiveGlassesSession(lease)
        setTransferUiVisible(false)
        resetTransferUiState()
    }
    
    private fun teardownDownloadP2pSession(
        sendExitTransfer: Boolean,
        hideTransferUi: Boolean,
        releaseExclusiveSession: Boolean = true,
        onTeardownComplete: (() -> Unit)? = null,
    ) {
        val teardownLease = mediaSessionLease
        if (sendExitTransfer) {
            downloadExitTransferRequested = true
        }
        if (downloadP2pTeardownInProgress) {
            sendExitTransferModeIfRequested()
            Log.w("DataDownload", "P2P teardown is already in progress; waiting for its result")
            return
        }
        downloadAttemptJob?.cancel()
        downloadAttemptJob = null
        cancelDownloadSession()
        transferModeCommandTimeoutJob?.cancel()
        transferModeCommandTimeoutJob = null
        downloadInitialPhaseTimeoutJob?.cancel()
        downloadInitialPhaseTimeoutJob = null
        unbindProcessFromNetwork()

        if (hideTransferUi) {
            runOnUiThread {
                setTransferUiVisible(false)
                resetTransferUiState()
            }
        }

        // Stop receiving download-mode notify frames.
        if (downloadNotifyListenerRegistered) {
            try {
                LargeDataHandler.getInstance().removeOutDeviceListener(2)
                downloadNotifyListenerRegistered = false
                Log.i("DataDownload", "Unregistered download notify listener (cmdType=2)")
            } catch (e: Exception) {
                Log.w("DataDownload", "Failed to unregister download notify listener", e)
            }
        }

        sendExitTransferModeIfRequested()

        val manager = downloadWifiP2pManager
        val callback = downloadWifiP2pCallback
        if (manager != null && callback != null) {
            manager.removeCallback(callback)
        }

        // Mirror official app: cancel the P2P connection as part of cleanup.
        manager?.stopP2pOperations()
        manager?.cancelP2pConnection()

        val finishTeardown = {
            manager?.unregisterReceiver()
            downloadWifiP2pManager = null
            downloadWifiP2pCallback = null
            downloadP2pConnected = false
            downloadInProgress = false
            downloadP2pNetwork = null
            downloadResolvedHttpIp = null
            downloadP2pTeardownInProgress = false
            if (releaseExclusiveSession) {
                releaseExclusiveGlassesSession(teardownLease)
            }
            onTeardownComplete?.invoke()
            Unit
        }

        if (manager == null) {
            finishTeardown()
            return
        }

        downloadP2pTeardownInProgress = true
        removeDownloadP2pGroup(
            manager = manager,
            attempt = 1,
            onRemoved = finishTeardown,
        )
    }

    private fun removeDownloadP2pGroup(
        manager: WifiP2pManagerSingleton,
        attempt: Int,
        onRemoved: () -> Unit,
    ) {
        val resultHandled = java.util.concurrent.atomic.AtomicBoolean(false)
        val handleResult: (Boolean) -> Unit = { success ->
            if (success) {
                Log.i("DataDownload", "P2P group removal accepted on teardown attempt $attempt; waiting for disconnect")
            } else {
                Log.w(
                    "DataDownload",
                    "P2P group removal failed on attempt $attempt; checking whether the group is already gone",
                )
            }
            awaitDownloadP2pDisconnect(manager, attempt, onRemoved)
        }
        glassesTeardownScope.launch {
            delay(P2P_GROUP_REMOVE_ACTION_TIMEOUT_MS)
            if (downloadP2pTeardownInProgress && resultHandled.compareAndSet(false, true)) {
                Log.w("DataDownload", "P2P group removal gave no callback on attempt $attempt; checking group state")
                awaitDownloadP2pDisconnect(manager, attempt, onRemoved)
            }
        }
        try {
            manager.removeGroup { success ->
                if (resultHandled.compareAndSet(false, true)) {
                    handleResult(success)
                } else {
                    Log.d("DataDownload", "Ignoring late P2P group removal callback for attempt $attempt")
                }
            }
        } catch (e: Exception) {
            if (resultHandled.compareAndSet(false, true)) {
                Log.w("DataDownload", "P2P group removal threw on attempt $attempt; checking group state", e)
                awaitDownloadP2pDisconnect(manager, attempt, onRemoved)
            }
        }
    }

    private fun awaitDownloadP2pDisconnect(
        manager: WifiP2pManagerSingleton,
        attempt: Int,
        onDisconnected: () -> Unit,
    ) {
        glassesTeardownScope.launch {
            val deadline = System.currentTimeMillis() + P2P_GROUP_DISCONNECT_TIMEOUT_MS
            while (downloadP2pTeardownInProgress && System.currentTimeMillis() < deadline) {
                manager.requestConnectionInfo()
                delay(250)
                if (!manager.isConnecting() && !manager.isConnected()) {
                    Log.i("DataDownload", "Confirmed P2P group is gone after teardown attempt $attempt")
                    onDisconnected()
                    return@launch
                }
            }

            if (downloadP2pTeardownInProgress) {
                if (!manager.canUseP2p() || attempt >= P2P_GROUP_REMOVAL_MAX_ATTEMPTS) {
                    Log.e(
                        "DataDownload",
                        "P2P teardown could not be confirmed; keeping the media-sync lease quarantined until Bluetooth reconnect",
                    )
                } else {
                    Log.w("DataDownload", "P2P group still present after teardown attempt $attempt; retaining the media-sync lease and retrying")
                    scheduleDownloadP2pRemovalRetry(manager, attempt + 1, onDisconnected)
                }
            }
        }
    }

    private fun scheduleDownloadP2pRemovalRetry(
        manager: WifiP2pManagerSingleton,
        attempt: Int,
        onRemoved: () -> Unit,
    ) {
        glassesTeardownScope.launch {
            delay(P2P_GROUP_REMOVAL_RETRY_MS)
            if (downloadP2pTeardownInProgress) {
                removeDownloadP2pGroup(manager, attempt, onRemoved)
            }
        }
    }

    private fun cleanupP2pAfterDownload() {
        teardownDownloadP2pSession(
            sendExitTransfer = true,
            hideTransferUi = true,
        )
    }

    private fun cancelDataDownloadAttempt(reason: String, showToast: Boolean) {
        Log.i("DataDownload", "$reason (flow=${downloadFlowMode.label})")
        maybeShowP2pSyncLogHelp(
            reason = "The user stopped the P2P sync before media transfer completed. Sharing logs can help diagnose why the sync needed to be cancelled.",
            title = "Sync stopped",
            dismissButtonLabel = "Close",
        )
        downloadCancelledByUser = true
        finishDownloadInitialPhase("cancelled by user")
        setTransferDetail("Stopping sync...")
        if (downloadP2pTeardownInProgress) {
            // A pre-start retry owns the teardown callback. Keep its confirmed-disconnect
            // barrier, but make the cancellation visible immediately.
            setTransferUiVisible(false)
            resetTransferUiState()
        }
        teardownDownloadP2pSession(
            sendExitTransfer = true,
            hideTransferUi = true,
        )
        if (showToast) {
            Toast.makeText(this, reason, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showDownloadSuccess(message: String) {
        finishDownloadInitialPhase("download completed")
        cleanupP2pAfterDownload()
        Log.i("DataDownload", "SUCCESS: $message (flow=${downloadFlowMode.label})")
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
    
    private fun showDownloadError(message: String, cleanup: Boolean = true) {
        if (isHighQualityImageTransfer()) {
            finishHighQualityImageFailure(message)
            return
        }
        if (!downloadInitialPhaseCompleted) {
            maybeShowP2pSyncLogHelp(
                reason = "CyanBridge failed during the initial P2P sync steps before any media transfer progress was shown. Error: $message",
            )
        }
        finishDownloadInitialPhase("error: $message")
        if (cleanup) {
            cleanupP2pAfterDownload()
        }
        Log.e("DataDownload", "ERROR: $message (flow=${downloadFlowMode.label})")
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun isProbablyGroupOwnerIp(ip: String?): Boolean {
        if (ip.isNullOrBlank()) return false

        // If the phone is not the group owner, then we shouldn't block the group owner IP (.1)
        // because it belongs to the glasses.
        if (downloadPhoneIsGroupOwner != true) return false

        // Typical Wi‑Fi Direct GO address when phone is GO.
        return ip == "192.168.49.1"
    }

    private fun ipv4Prefix24(ip: String?): String? {
        if (ip.isNullOrBlank()) return null
        val parts = ip.split(".")
        if (parts.size != 4) return null
        return "${parts[0]}.${parts[1]}.${parts[2]}."
    }

    private fun guessDownloadSubnetPrefix(): String? {
        // Prefer authoritative device IPs when available; otherwise fall back to
        // the group owner's subnet and finally the active Wi‑Fi/P2P interface subnet.
        ipv4Prefix24(downloadBleIp)?.let { return it }
        ipv4Prefix24(bleIpBridge.ip.value)?.let { return it }
        ipv4Prefix24(downloadWifiIp)?.let { return it }

        val network = downloadP2pNetwork ?: findLikelyP2pNetwork()
        if (network != null) {
            try {
                val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val lp = cm.getLinkProperties(network)
                val addr = lp?.linkAddresses
                    ?.mapNotNull { it.address.hostAddress }
                    ?.firstOrNull { it.count { ch -> ch == '.' } == 3 }
                ipv4Prefix24(addr)?.let { return it }
            } catch (_: Exception) {
                // ignore
            }
        }
        return null
    }

    private fun buildCandidateIps(): List<String> {
        val set = LinkedHashSet<String>()

        downloadBleIp?.let { set.add(it) }
        bleIpBridge.ip.value?.let { set.add(it) }

        if (downloadPhoneIsGroupOwner == false && downloadWifiIp != null) {
            set.add(downloadWifiIp!!)
        } else {
            downloadWifiIp?.let { set.add(it) }
        }

        guessDownloadSubnetPrefix()?.let { prefix ->
            set.add("${prefix}1") // Glasses might be the group owner
            set.add("${prefix}79")
            set.add("${prefix}2")
            set.add("${prefix}3")
        }

        return set.toList()
    }

    private fun isPortOpen(ip: String, port: Int, timeoutMs: Int): Boolean {
        // Standard path: use P2P network's socket factory.
        try {
            val factory = downloadP2pNetwork?.socketFactory ?: javax.net.SocketFactory.getDefault()
            factory.createSocket().use { s ->
                s.connect(InetSocketAddress(ip, port), timeoutMs)
                return true
            }
        } catch (_: Exception) {}

        // VPN fallback: bind socket to P2P local address to bypass VPN routing.
        val p2pAddr = p2pLocalAddress() ?: return false
        return try {
            Socket().use { s ->
                s.bind(InetSocketAddress(p2pAddr, 0))
                s.connect(InetSocketAddress(ip, port), timeoutMs)
                true
            }
        } catch (_: Exception) { false }
    }

    private fun mediaConfigOk(ip: String, timeoutMs: Int, logFailures: Boolean = false): Boolean {
        if (!isPortOpen(ip, 80, (timeoutMs / 2).coerceAtLeast(400))) {
            if (logFailures) {
                Log.w("DataDownload", "media.config probe skipped for $ip (port 80 closed/unreachable)")
            }
            return false
        }
        val url = URL("http://$ip/files/media.config")
        val ok = httpGet(url, timeoutMs, timeoutMs)
        if (!ok && logFailures) {
            Log.w("DataDownload", "media.config probe failed for $ip")
        }
        return ok
    }

    /**
     * Probe media.config with retry. The glasses' HTTP server may not be
     * immediately ready after P2P connects — it can take a few seconds
     * for the firmware to start serving.
     */
    private suspend fun mediaConfigOkWithRetry(
        ip: String,
        timeoutMs: Int = 2000,
        maxRetries: Int = 3,
        delayMs: Long = 2000L,
    ): Boolean {
        for (attempt in 1..maxRetries) {
            if (mediaConfigOk(ip, timeoutMs, logFailures = (attempt == maxRetries))) {
                return true
            }
            if (attempt < maxRetries) {
                Log.i("DataDownload", "media.config probe attempt $attempt/$maxRetries failed for $ip, retrying in ${delayMs}ms")
                delay(delayMs)
            }
        }
        return false
    }

    private suspend fun discoverGlassesIpByScan(prefix: String = "192.168.49."): String? {
        // Fast scan for an HTTP server on port 80 in the P2P subnet.
        // Concurrency is limited to avoid overwhelming the device/network stack.
        return supervisorScope {
            val sem = Semaphore(32)
            val connectTimeoutMs = 300
            val verifyTimeoutMs = 1200
            val found = CompletableDeferred<String?>()
            val firstOpenPortIp = AtomicReference<String?>(null)

            for (host in 1..254) {
                val ip = "$prefix$host"
                if (downloadPhoneIsGroupOwner == true && ip == "192.168.49.1") continue
                launch(Dispatchers.IO) {
                    sem.withPermit {
                        if (found.isCompleted) return@withPermit
                        if (isPortOpen(ip, 80, connectTimeoutMs)) {
                            firstOpenPortIp.compareAndSet(null, ip)
                            // Prefer an IP that actually serves media.config.
                            if (mediaConfigOk(ip, verifyTimeoutMs)) {
                                found.complete(ip)
                            }
                        }
                    }
                }
            }

            val res = withTimeoutOrNull(20_000L) { found.await() } ?: firstOpenPortIp.get()
            coroutineContext.cancelChildren()
            res
        }
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

    private fun testConnection(deviceIp: String): Boolean {
        Log.i("DataDownload", "Testing connection to $deviceIp...")
        val url = URL("http://$deviceIp/files/media.config")
        var bytesRead = 0
        val ok = httpGet(url, 5000, 5000) { stream, _ ->
            val buffer = ByteArray(1024)
            bytesRead = stream.read(buffer)
            stream.close()
        }
        if (ok) {
            Log.i("DataDownload", "Connection test successful - read $bytesRead bytes")
        } else {
            Log.e("DataDownload", "Connection test failed for $deviceIp")
        }
        return ok
    }

    private fun onDownloadBleIp(ip: String) {
        val now = System.currentTimeMillis()
        if (ip == downloadBleIp && (now - lastDownloadBleIpAtMs) < 1200L) {
            Log.i("DataDownload", "Ignoring duplicate BLE IP report: $ip")
            return
        }
        lastDownloadBleIpAtMs = now
        Log.i("DataDownload", "BLE reported device WiFi IP: $ip")
        markTransferModeEvidence("BLE 0x08 IP")
        downloadBleIp = ip
        if (downloadFlowMode == GlassesSyncFlow.OFFICIAL_HEYCYAN) {
            officialBleCallbackSuccess = true
            Log.i("DataDownload", "Official flow BLE readiness satisfied")
        }

        // If we're stuck scanning/probing without a good route, restart the resolver now that
        // we have the authoritative device IP from BLE.
        if (downloadAttemptJob?.isActive == true && !downloadInProgress) {
            Log.i("DataDownload", "New BLE IP arrived; restarting HTTP resolver")
            downloadAttemptJob?.cancel()
            downloadAttemptJob = null
        }
        maybeStartHttpDownload("BLE")
    }

    private fun onDownloadP2pConnected(info: WifiP2pInfo) {
        if (info.groupFormed) markTransferModeEvidence("P2P group formed")
        downloadP2pConnected = info.groupFormed
        downloadWifiIp = info.groupOwnerAddress?.hostAddress
        downloadPhoneIsGroupOwner = info.isGroupOwner
        if (downloadFlowMode == GlassesSyncFlow.OFFICIAL_HEYCYAN) {
            officialSystemSuccess = info.groupFormed
            officialDisconnectRecoveryJob?.cancel()
            officialDisconnectRecoveryJob = null
            downloadWifiP2pManager?.resetPeerDiscovery()
            Log.i(
                "DataDownload",
                "Official flow P2P readiness satisfied: systemSuccess=$officialSystemSuccess, phoneIsGroupOwner=${info.isGroupOwner}"
            )
            downloadP2pNetwork = null
            selectedDownloadNetworkSummary = "plain system routing (HeyCyan parity)"
            Log.i("DataDownload", "Official flow: skipping explicit P2P network binding to mirror vendor app")
        } else {
            downloadP2pNetwork = findLikelyP2pNetwork()
            bindProcessToNetwork(downloadP2pNetwork)
        }
        Log.i(
            "DataDownload",
            "onDownloadP2pConnected: flow=${downloadFlowMode.label}, p2pConnected=$downloadP2pConnected, isGroupOwner=${info.isGroupOwner}, groupOwnerIp=$downloadWifiIp"
        )
        maybeStartHttpDownload("P2P")
    }

    private fun markTransferModeEvidence(source: String) {
        if (transferModeCommandEvidenceReceived) return
        transferModeCommandEvidenceReceived = true
        transferModeCommandTimeoutJob?.cancel()
        transferModeCommandTimeoutJob = null
        Log.i("DataDownload", "Transfer mode independently confirmed by $source")
    }

    private fun maybeStartHttpDownload(source: String) {
        if (downloadCancelledByUser) {
            Log.i("DataDownload", "Ignoring HTTP start trigger from $source after user stop")
            return
        }
        if (downloadInProgress || downloadAttemptJob?.isActive == true) {
            Log.i("DataDownload", "Download already in progress, ignoring trigger from $source")
            return
        }

        if (!downloadP2pConnected) {
            Log.i("DataDownload", "Ignoring HTTP start trigger from $source; P2P not connected yet")
            return
        }

        when (downloadFlowMode) {
            GlassesSyncFlow.OFFICIAL_HEYCYAN -> maybeStartOfficialHttpDownload(source)
            GlassesSyncFlow.CUSTOM -> maybeStartCustomHttpDownload(source)
        }
    }

    private fun maybeStartOfficialHttpDownload(source: String) {
        if (!officialSystemSuccess) {
            Log.i(
                "DataDownload",
                "Ignoring HTTP start trigger from $source; HeyCyan flow is waiting for P2P system success"
            )
            return
        }

        val bleIp = downloadBleIp
        if (!officialBleCallbackSuccess || bleIp.isNullOrBlank()) {
            setTransferDetail("Waiting for BLE-reported glasses IP...")
            Log.i(
                "DataDownload",
                "Ignoring HTTP start trigger from $source; HeyCyan flow is waiting for BLE 0x08 IP notify"
            )
            return
        }

        val targetIp = if (downloadPhoneIsGroupOwner == false && !downloadWifiIp.isNullOrBlank()) {
            downloadWifiIp!!
        } else {
            bleIp
        }

        Log.i(
            "DataDownload",
            "Official flow HTTP start trigger from $source. flow=${downloadFlowMode.label}, phoneIsGroupOwner=$downloadPhoneIsGroupOwner, bleIp=$downloadBleIp, groupOwnerIp=$downloadWifiIp, targetIp=$targetIp"
        )

        downloadAttemptJob = launchDownloadSession { sessionId ->
            // PictureFragment waits before handing media.config to AlbumDepository.
            delay(1000)
            if (!isActive || !downloadP2pConnected || downloadCancelledByUser || !isDownloadSessionActive(sessionId)) return@launchDownloadSession
            officialDisconnectRecoveryJob?.cancel()
            officialDisconnectRecoveryJob = null
            downloadWifiP2pManager?.resetPeerDiscovery()
            resetOfficialFlowState()
            downloadResolvedHttpIp = targetIp
            downloadInProgress = true
            Log.i("DataDownload", "Official flow resolved glasses HTTP IP: $targetIp")
            downloadMediaList(targetIp, sessionId)
        }
    }

    private fun maybeStartCustomHttpDownload(source: String) {

        val hasDeviceIp = !downloadBleIp.isNullOrBlank() || !bleIpBridge.ip.value.isNullOrBlank()
        if (!hasDeviceIp) {
            setTransferDetail("Waiting for BLE-reported glasses IP...")
            Log.i("DataDownload", "Ignoring HTTP start trigger from $source; waiting for device IP notify")
            return
        }

        val bridgeIp = bleIpBridge.ip.value
        Log.i(
            "DataDownload",
            "HTTP start trigger from $source. flow=${downloadFlowMode.label}, p2p=$downloadP2pConnected, bleIp=$downloadBleIp, groupOwnerIp=$downloadWifiIp, bleBridgeIp=$bridgeIp"
        )

        downloadAttemptJob = launchDownloadSession { sessionId ->
            // Official app waits briefly after both P2P+BLE-IP signals before fetching media.config.
            delay(1000)

            val startMs = System.currentTimeMillis()
            val overallTimeoutMs = 45_000L
            var lastStatusLogMs = 0L
            var didSubnetScan = false

            while (isActive && System.currentTimeMillis() - startMs < overallTimeoutMs) {
                if (!isDownloadSessionActive(sessionId)) return@launchDownloadSession
                val now = System.currentTimeMillis()
                if (now - lastStatusLogMs > 5000) {
                    lastStatusLogMs = now
                    Log.i(
                        "DataDownload",
                        "Resolving glasses HTTP IP... p2p=$downloadP2pConnected, bleIp=$downloadBleIp, groupOwnerIp=$downloadWifiIp"
                    )
                }

                // 1) Try known candidates first.
                for (candidate in buildCandidateIps()) {
                    if (!isActive || !isDownloadSessionActive(sessionId)) return@launchDownloadSession
                    if (candidate.isBlank()) continue
                    if (isProbablyGroupOwnerIp(candidate)) {
                        // The phone typically has nothing on port 80.
                        continue
                    }
                    val isBleCandidate = candidate == downloadBleIp
                    // Use retry logic for the BLE-reported IP — the glasses' HTTP
                    // server may need a few seconds to start after P2P connects.
                    val ok = if (isBleCandidate) {
                        mediaConfigOkWithRetry(candidate, timeoutMs = 2000, maxRetries = 3, delayMs = 2000L)
                    } else {
                        mediaConfigOk(candidate, 2000, logFailures = false)
                    }
                    if (ok) {
                        downloadResolvedHttpIp = candidate
                        downloadInProgress = true
                        Log.i("DataDownload", "Resolved glasses HTTP IP via candidate list: $candidate")
                        downloadMediaList(candidate, sessionId)
                        return@launchDownloadSession
                    }
                }

                // 2) If we still don't have a device IP, scan the local /24 derived from
                // the best available hint (BLE IP, bridge IP, GO subnet, or interface subnet).
                if (!didSubnetScan &&
                    downloadP2pConnected &&
                    downloadResolvedHttpIp == null &&
                    downloadBleIp == null &&
                    bleIpBridge.ip.value == null
                ) {
                    val prefix = guessDownloadSubnetPrefix()
                    if (!prefix.isNullOrBlank()) {
                        didSubnetScan = true
                        Log.i("DataDownload", "Candidate IPs failed; scanning ${prefix}0/24 for HTTP server...")
                        val found = discoverGlassesIpByScan(prefix)
                        if (!found.isNullOrBlank()) {
                            downloadResolvedHttpIp = found
                            downloadInProgress = true
                            Log.i("DataDownload", "Resolved glasses HTTP IP via scan: $found")
                            downloadMediaList(found, sessionId)
                            return@launchDownloadSession
                        }
                    }
                }

                delay(1500)
            }

            withContext(Dispatchers.Main) {
                val hint = " If the official HeyCyan app is installed, force-stop it (Settings → Apps → HeyCyan → Force Stop) and try again."
                showDownloadError(
                    "Could not resolve glasses HTTP IP (bleIp=$downloadBleIp, groupOwnerIp=$downloadWifiIp, p2p=$downloadP2pConnected).$hint",
                    cleanup = true
                )
            }
        }
    }

    private fun isDownloadInitialPhaseActive(): Boolean {
        return !downloadInitialPhaseCompleted &&
            !downloadCancelledByUser &&
            dashboardState.transfer.isVisible
    }

    private inner class DownloadNotifyListener : GlassesDeviceNotifyListener() {
        @RequiresApi(Build.VERSION_CODES.O)
        override fun parseData(cmdType: Int, response: GlassesDeviceNotifyRsp) {
            // Only handle download-relevant notifications here to avoid duplicating
            // other flows already handled by MyDeviceNotifyListener.
            val load = response.loadData
            if (load.size < 7) return
            when (load[6].toInt()) {
                0x08 -> {
                    if (load.size >= 11) {
                        val ip = "${ByteUtil.byteToInt(load[7])}." +
                                "${ByteUtil.byteToInt(load[8])}." +
                                "${ByteUtil.byteToInt(load[9])}." +
                                "${ByteUtil.byteToInt(load[10])}"
                        Log.i("DeviceNotify", "(download) BLE reported WiFi IP: $ip")
                        onDownloadBleIp(ip)
                    }
                }

                0x09 -> {
                    val raw = load.getOrNull(7) ?: 0
                    val errorCode = ByteUtil.byteToInt(raw)
                    Log.e("DeviceNotify", "(download) P2P/WiFi error from device: $errorCode (raw=$raw)")
                    if (errorCode == 255) {
                        maybeResetP2pAfterError255("download")
                    }
                }
            }
        }
    }

    private fun openHttpConnection(url: URL): HttpURLConnection? {
        val network = downloadP2pNetwork ?: findLikelyP2pNetwork()?.also { downloadP2pNetwork = it }
        if (network != null) {
            try {
                val conn = network.openConnection(url) as HttpURLConnection
                conn.instanceFollowRedirects = true
                return conn
            } catch (_: Exception) {}
        }
        return try {
            val conn = url.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            conn
        } catch (_: Exception) { null }
    }

    private fun openPlainHttpConnection(url: URL): HttpURLConnection? {
        return try {
            val conn = url.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            conn
        } catch (_: Exception) { null }
    }

    /** Build an OkHttp client whose sockets bind to the P2P local address (VPN-proof). */
    private fun vpnSafeHttpClient(connectTimeoutMs: Int, readTimeoutMs: Int): okhttp3.OkHttpClient? {
        val p2pAddr = p2pLocalAddress() ?: return null
        val factory = object : javax.net.SocketFactory() {
            override fun createSocket(): Socket {
                val s = Socket()
                s.bind(InetSocketAddress(p2pAddr, 0))
                return s
            }
            override fun createSocket(host: String, port: Int) = throw UnsupportedOperationException()
            override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int) = throw UnsupportedOperationException()
            override fun createSocket(host: InetAddress, port: Int) = throw UnsupportedOperationException()
            override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int) = throw UnsupportedOperationException()
        }
        return try {
            okhttp3.OkHttpClient.Builder()
                .socketFactory(factory)
                .connectTimeout(connectTimeoutMs.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
                .readTimeout(readTimeoutMs.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
        } catch (_: Exception) { null }
    }

    /**
     * HTTP GET using P2P-bound sockets (VPN-safe).
     * Tries Network.openConnection() first, then OkHttp with P2P local-address binding.
     */
    private fun httpGet(
        url: URL,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        onStream: ((InputStream, Long) -> Unit)? = null
    ): Boolean {
        if (downloadFlowMode == GlassesSyncFlow.OFFICIAL_HEYCYAN) {
            return try {
                val conn = openPlainHttpConnection(url) ?: return false
                conn.requestMethod = "GET"
                conn.connectTimeout = connectTimeoutMs
                conn.readTimeout = readTimeoutMs
                if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                    onStream?.invoke(conn.inputStream, conn.contentLengthLong)
                    conn.disconnect()
                    true
                } else {
                    conn.disconnect()
                    false
                }
            } catch (e: Exception) {
                Log.w("DataDownload", "Official flow plain httpGet failed for $url: ${e.message}")
                false
            }
        }

        try {
            val conn = openHttpConnection(url) ?: return false
            conn.requestMethod = "GET"
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                onStream?.invoke(conn.inputStream, conn.contentLengthLong)
                conn.disconnect()
                return true
            }
            conn.disconnect()
        } catch (e: Exception) {
            Log.w("DataDownload", "httpGet default path failed for $url: ${e.message}")
        }

        val client = vpnSafeHttpClient(connectTimeoutMs, readTimeoutMs) ?: return false
        return try {
            val request = okhttp3.Request.Builder().url(url).build()
            client.newCall(request).execute().use { resp ->
                if (resp.isSuccessful && resp.body != null) {
                    onStream?.invoke(resp.body!!.byteStream(), resp.body!!.contentLength())
                    true
                } else false
            }
        } catch (e: Exception) {
            Log.w("DataDownload", "P2P-bound httpGet fallback failed for $url: ${e.message}")
            false
        }
    }

    private fun findLikelyP2pNetwork(): Network? {
        // We want a network whose sockets route to the Wi‑Fi Direct group even when a VPN is active.
        // Wi‑Fi Direct networks still show up as TRANSPORT_WIFI; the VPN itself shows up as TRANSPORT_VPN.
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

            val prefixHints = listOfNotNull(
                ipv4Prefix24(downloadBleIp),
                ipv4Prefix24(bleIpBridge.ip.value),
                ipv4Prefix24(downloadWifiIp)
            ).distinct()

            for (n in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(n) ?: continue
                if (!caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) continue
                if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)) continue

                val lp = cm.getLinkProperties(n)
                val ifName = lp?.interfaceName ?: ""
                val addrs = lp?.linkAddresses?.mapNotNull { it.address.hostAddress } ?: emptyList()

                if (HeyCyanP2pPolicy.isVerifiedP2pNetwork(ifName, addrs, prefixHints)) {
                    selectedDownloadNetworkSummary = "if=$ifName addrs=$addrs"
                    Log.i("DataDownload", "Selected verified P2P/WFD network: $selectedDownloadNetworkSummary")
                    return n
                }
            }

            selectedDownloadNetworkSummary = "none (ordinary Wi-Fi fallback rejected)"
            Log.i("DataDownload", "No verified P2P/WFD Network exposed; leaving process routing unchanged")
            null
        } catch (e: Exception) {
            selectedDownloadNetworkSummary = "lookup failed: ${e.message.orEmpty()}"
            Log.w("DataDownload", "Failed to locate P2P network: ${e.message}")
            null
        }
    }

    private fun bindProcessToNetwork(network: Network?) {
        if (network == null) return
        if (boundNetwork == network) return

        // When a VPN is active, Android blocks bindProcessToNetwork (EPERM).
        // We skip it and rely on per-socket binding via socket.bind(p2pLocalAddress) instead.
        if (isVpnActive()) {
            Log.i("DataDownload", "VPN active — skipping bindProcessToNetwork, will bind sockets to P2P local address")
            return
        }

        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val ok = cm.bindProcessToNetwork(network)
            if (ok) {
                boundNetwork = network
                Log.i("DataDownload", "Bound process to P2P network")
            } else {
                Log.w("DataDownload", "bindProcessToNetwork returned false")
            }
        } catch (e: Exception) {
            Log.w("DataDownload", "bindProcessToNetwork failed: ${e.message}")
        }
    }

    private fun unbindProcessFromNetwork() {
        if (boundNetwork == null) return
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.bindProcessToNetwork(null)
        } catch (_: Exception) {
            // ignore
        } finally {
            boundNetwork = null
        }
    }

    private fun isVpnActive(): Boolean {
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.allNetworks.any { n ->
                cm.getNetworkCapabilities(n)
                    ?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true
            }
        } catch (_: Exception) { false }
    }

    /** Return the P2P network's first IPv4 local address (e.g. "192.168.49.1"). */
    private fun p2pLocalAddress(): InetAddress? {
        val network = downloadP2pNetwork ?: return null
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val lp = cm.getLinkProperties(network)
            lp?.linkAddresses
                ?.mapNotNull { it.address }
                ?.firstOrNull { it is java.net.Inet4Address }
        } catch (_: Exception) { null }
    }

    private fun maybeResetP2pAfterError255(source: String) {
        val now = System.currentTimeMillis()

        if (downloadFlowMode == GlassesSyncFlow.OFFICIAL_HEYCYAN) {
            val sessionActive = isOfficialSyncActive() || downloadP2pConnected || downloadInProgress || downloadAttemptJob?.isActive == true
            if (!sessionActive) {
                Log.i("DataDownload", "Ignoring error=255 reset (source=$source) outside download session")
                return
            }
            lastP2pResetAtMs = now
            stopOfficialFlowForRetry(
                "The glasses reported Wi-Fi Direct error 255.",
                resetDeviceP2p = true,
            )
            return
        }

        // Only attempt P2P resets when we're actually in (or attempting) a download session.
        // Otherwise these resets can interfere with normal camera/recording usage.
        val sessionActive = when (downloadFlowMode) {
            GlassesSyncFlow.OFFICIAL_HEYCYAN -> {
                downloadInProgress || downloadAttemptJob?.isActive == true || downloadP2pConnected || isDownloadInitialPhaseActive()
            }

            GlassesSyncFlow.CUSTOM -> {
                downloadInProgress || downloadAttemptJob?.isActive == true || downloadP2pConnected
            }
        }
        if (!sessionActive) {
            Log.i("DataDownload", "Ignoring error=255 reset (source=$source) outside download session")
            return
        }

        // Once the media list has been resolved, suppress resets because they can kill an
        // otherwise healthy HTTP transfer on some devices. During the initial unresolved phase,
        // the vendor app recovers by resetting P2P and continuing to wait for the glasses peer.
        if (downloadInProgress || downloadInitialPhaseCompleted || !downloadResolvedHttpIp.isNullOrBlank()) {
            Log.i("DataDownload", "Suppressing resetDeviceP2p on error=255 (source=$source) after HTTP/media resolution")
            return
        }

        if (now - lastP2pResetAtMs < 10_000) {
            return
        }
        lastP2pResetAtMs = now
        WifiP2pManagerSingleton.getInstance(this).resetDeviceP2p()
    }

    private fun stopOfficialFlowForRetry(message: String, resetDeviceP2p: Boolean) {
        if (downloadFlowMode != GlassesSyncFlow.OFFICIAL_HEYCYAN || downloadCancelledByUser) return
        if (officialFlowRetryRequired) return
        officialFlowRetryRequired = true
        Log.w("DataDownload", "HeyCyan flow stopped for manual retry: $message")
        if (!downloadInitialPhaseCompleted) {
            maybeShowP2pSyncLogHelp(
                reason = "HeyCyan-compatible sync stopped before media transfer. Error: $message",
            )
        }
        finishDownloadInitialPhase("official flow retry required: $message")
        setTransferDetail("$message Retry sync.")
        if (resetDeviceP2p) {
            WifiP2pManagerSingleton.getInstance(this).resetDeviceP2p()
        }
        teardownDownloadP2pSession(
            sendExitTransfer = false,
            hideTransferUi = true,
        )
        Toast.makeText(this, "$message Please retry sync.", Toast.LENGTH_LONG).show()
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
                    if (WalkingAidImageCapture.isAwaitingPhotoReady()) {
                        Log.i("DeviceNotify", "Walking Aid consumed its requested photo-ready notification")
                        return
                    }
                    val appRequestedCapture = imageCaptureAwaitingNotification.get()
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

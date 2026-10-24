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
import com.fersaiyan.cyanbridge.devices.meizumyvu.MeizuMyvuFailure
import com.fersaiyan.cyanbridge.devices.moyoung.MoyoungW620Manager
import com.fersaiyan.cyanbridge.devices.moyoung.MoyoungWifiCredentials
import com.fersaiyan.cyanbridge.devices.tunebuds.TuneBudsManager
import com.fersaiyan.cyanbridge.devices.tunebuds.TuneBudsLivePreviewManager
import com.fersaiyan.cyanbridge.devices.tunebuds.TuneBudsLiveForegroundService
import com.fersaiyan.cyanbridge.devices.tunebuds.TuneBudsLocalHotspot
import com.fersaiyan.cyanbridge.devices.tunebuds.TuneBudsMediaSync
import com.fersaiyan.cyanbridge.devices.tunebuds.TuneBudsMediaType
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
import com.fersaiyan.cyanbridge.shared.ai.AssistantSpeechPolicy
import com.fersaiyan.cyanbridge.shared.ai.GlassesAssistantRoute
import com.fersaiyan.cyanbridge.shared.ai.GlassesAssistantRoutingPolicy
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
import com.fersaiyan.cyanbridge.ui.main.AgentControlsUiController
import com.fersaiyan.cyanbridge.ui.main.AppDestinationNavigator
import com.fersaiyan.cyanbridge.ui.main.AssistantSetupGate
import com.fersaiyan.cyanbridge.ui.main.DashboardActionHost
import com.fersaiyan.cyanbridge.ui.main.DashboardController
import com.fersaiyan.cyanbridge.ui.main.DeviceConnectionCoordinator
import com.fersaiyan.cyanbridge.ui.main.DeviceConnectionHost
import com.fersaiyan.cyanbridge.ui.main.DeviceManagerRegistry
import com.fersaiyan.cyanbridge.ui.main.glasses.AndroidGlassesDevices
import com.fersaiyan.cyanbridge.ui.main.glasses.GlassesDeviceContext
import com.fersaiyan.cyanbridge.ui.main.glasses.GlassesDeviceHost
import com.fersaiyan.cyanbridge.ui.main.GlassesSessionGuard
import com.fersaiyan.cyanbridge.ui.main.GlassesTransportPermissions
import com.fersaiyan.cyanbridge.ui.main.HeyCyanSettingsController
import com.fersaiyan.cyanbridge.ui.main.LegacyControlsHost
import com.fersaiyan.cyanbridge.ui.main.LegacyGlassesControlsBinder
import com.fersaiyan.cyanbridge.ui.main.MainScreenHost
import com.fersaiyan.cyanbridge.ui.main.MeetingCaptureUiController
import com.fersaiyan.cyanbridge.ui.main.MeizuMyvuUiController
import com.fersaiyan.cyanbridge.ui.main.MetaRaybanUiController
import com.fersaiyan.cyanbridge.ui.main.NativePluginShortcutController
import com.fersaiyan.cyanbridge.ui.main.TaskerCommandHandler
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
        private const val IMAGE_THUMBNAIL_TRANSFER_TIMEOUT_MS = 20_000L
        private const val VOICE_CUE_ROUTE_SETTLE_MS = 500L
        private const val VOICE_BLUETOOTH_ROUTE_TIMEOUT_MS = 3_000L
        private const val VOICE_QUESTION_ONSET_TIMEOUT_MS = 5_000L
        private const val IMAGE_QUESTION_CUE_BLUETOOTH_TAIL_MS = 50L
        private const val IMAGE_QUESTION_INITIAL_LISTENING_TIMEOUT_MS = 3_300L

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
    private val eyevueLivePreviewManager: EyevueLivePreviewManager?
        get() = deviceManagers.eyevueLivePreviewManager
    private val tuneBudsLivePreviewManager: TuneBudsLivePreviewManager?
        get() = deviceManagers.tuneBudsLivePreviewManager
    private var tuneBudsLivePlayer by mutableStateOf<org.videolan.libvlc.MediaPlayer?>(null)
    private var tuneBudsLiveAudioMuted by mutableStateOf(false)
    private var tuneBudsLiveStopReceiver: android.content.BroadcastReceiver?
        get() = deviceManagers.tuneBudsLiveStopReceiver
        set(value) {
            deviceManagers.tuneBudsLiveStopReceiver = value
        }
    // Inline EyeVue video: exposed to Compose so the dashboard shows video
    // in-screen (EyeVue only, after live starts). No popup dialog.
    private var eyevueLivePlayer by mutableStateOf<org.videolan.libvlc.MediaPlayer?>(null)
    private var eyevueLiveStopReceiver: android.content.BroadcastReceiver?
        get() = deviceManagers.eyevueLiveStopReceiver
        set(value) {
            deviceManagers.eyevueLiveStopReceiver = value
        }
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
    private var batteryCallbackRegistered: Boolean
        get() = deviceConnection.batteryCallbackRegistered
        set(value) {
            deviceConnection.batteryCallbackRegistered = value
        }
    private var enabledFeaturePermissionRequestActive = false
    private var enabledMetaCameraCheckActive: Boolean
        get() = metaRaybanUi.enabledMetaCameraCheckActive
        set(value) {
            metaRaybanUi.enabledMetaCameraCheckActive = value
        }

    private val eyevueAiPhotoInProgress = AtomicBoolean(false)
    private val tuneBudsAiPhotoInProgress = AtomicBoolean(false)

    // Glasses dashboard: device managers and controllers live in ui.main.
    private val mainScreenHost: MainScreenHost = object : MainScreenHost {
        override val activity: AppCompatActivity
            get() = this@MainActivity
        override val binding: AcitivytMainBinding
            get() = this@MainActivity.binding
        override var dashboardState: GlassesDashboardUiState
            get() = this@MainActivity.dashboardState
            set(value) {
                this@MainActivity.dashboardState = value
            }

        override fun updateDashboardState(
            transform: (GlassesDashboardUiState) -> GlassesDashboardUiState,
        ) {
            this@MainActivity.updateDashboardState(transform)
        }
    }
    private val deviceConnectionHost: DeviceConnectionHost = object : DeviceConnectionHost {
        override val isAiHijackEnabled: Boolean
            get() = this@MainActivity.isAiHijackEnabled

        override fun handleAiWakeWordActivation(source: String) {
            this@MainActivity.handleAiWakeWordActivation(source)
        }

        override fun startBatteryPolling() {
            this@MainActivity.startBatteryPolling()
        }

        override fun stopBatteryPolling() {
            this@MainActivity.stopBatteryPolling()
        }

        override fun showEyevueLivePlayer(player: org.videolan.libvlc.MediaPlayer?) {
            eyevueLivePlayer = player
        }

        override fun showTuneBudsLivePlayer(player: org.videolan.libvlc.MediaPlayer?) {
            tuneBudsLivePlayer = player
        }

        override fun dismissLegacyLivePreviewDialog() {
            livePreviewDialog?.let { dialog ->
                livePreviewDialog = null
                runCatching { dialog.dismiss() }
            }
        }
    }
    private val deviceManagers: DeviceManagerRegistry by lazy {
        DeviceManagerRegistry(this) { deviceConnection }
    }
    private val glassesDeviceHost: GlassesDeviceHost = object : GlassesDeviceHost {
        override val heyCyanLivePreviewManager: com.fersaiyan.cyanbridge.ota.LivePreviewManager
            get() = this@MainActivity.livePreviewManager

        override fun stopHeyCyanMediaSyncForDestroy() {
            mediaSync.stopForActivityDestroy()
        }

        override fun stopVendorMediaSyncForDestroy(stop: () -> Unit) {
            stop()
            releaseExclusiveGlassesSession(mediaSessionLease)
            mediaSessionLease = null
        }

        override fun disconnectEyevueMediaTransport() {
            vendorMediaSync.disconnectEyevueTransport()
        }
    }
    private val glassesDevices: AndroidGlassesDevices by lazy {
        AndroidGlassesDevices(
            GlassesDeviceContext(
                screen = mainScreenHost,
                managers = deviceManagers,
                actionsProvider = { dashboardActionHost },
                connectionProvider = { deviceConnection },
                heyCyanSettingsProvider = { heyCyanSettings },
                metaRaybanUiProvider = { metaRaybanUi },
                meizuMyvuUiProvider = { meizuMyvuUi },
                hostProvider = { glassesDeviceHost },
            ),
        )
    }
    private val glassesSessionGuard: GlassesSessionGuard by lazy {
        GlassesSessionGuard(this, deviceManagers, glassesTeardownScope)
    }
    private val transportPermissions: GlassesTransportPermissions by lazy {
        GlassesTransportPermissions(mainScreenHost)
    }
    private val heyCyanSettings: HeyCyanSettingsController by lazy {
        HeyCyanSettingsController(mainScreenHost)
    }
    private val metaRaybanUi: MetaRaybanUiController by lazy {
        MetaRaybanUiController(
            mainScreenHost,
            deviceManagers,
            metaAndroidPermissionLauncher,
            metaWearablePermissionLauncher,
        )
    }
    private val meizuMyvuUi: MeizuMyvuUiController by lazy {
        MeizuMyvuUiController(mainScreenHost, deviceManagers)
    }
    private val deviceConnection: DeviceConnectionCoordinator by lazy {
        DeviceConnectionCoordinator(
            mainScreenHost,
            deviceConnectionHost,
            glassesDevices,
            glassesSessionGuard,
            metaRaybanUi,
            meizuMyvuUi,
        )
    }
    private val eyevueManager: EyevueManager?
        get() = deviceManagers.eyevueManager
    private val tuneBudsManager: TuneBudsManager?
        get() = deviceManagers.tuneBudsManager
    private val moyoungW620Manager: MoyoungW620Manager?
        get() = deviceManagers.moyoungW620Manager
    private val meetingCaptureUi: MeetingCaptureUiController by lazy {
        MeetingCaptureUiController(mainScreenHost) { refreshNativePluginShortcutState() }
    }
    private val agentControlsUi: AgentControlsUiController by lazy {
        AgentControlsUiController(mainScreenHost)
    }
    private val nativePluginShortcuts: NativePluginShortcutController by lazy {
        NativePluginShortcutController(
            mainScreenHost,
            glassesDevices,
            meetingCaptureUi,
            transportPermissions,
        )
    }
    private val dashboardActionHost: DashboardActionHost = object : DashboardActionHost {
        override fun selectPhoneAssistant() {
            this@MainActivity.selectPhoneAssistant()
        }

        override fun selectCustomAiProvider() {
            this@MainActivity.selectCustomAiProvider()
        }

        override fun onImageThumbnailQualitySelected(quality: ImageThumbnailQuality) {
            pendingImageThumbnailQuality = quality
        }

        override fun startEyevueMediaSync() {
            this@MainActivity.startEyevueMediaSync()
        }

        override fun startTuneBudsMediaSync() {
            this@MainActivity.startTuneBudsMediaSync()
        }

        override fun startMoyoungW620MediaSync() {
            this@MainActivity.startMoyoungW620MediaSync()
        }

        override fun stopEyevueMediaSync() {
            this@MainActivity.stopEyevueMediaSync()
        }

        override fun stopTuneBudsMediaSync() {
            this@MainActivity.stopTuneBudsMediaSync()
        }

        override fun stopMoyoungW620MediaSync() {
            this@MainActivity.stopMoyoungW620MediaSync()
        }

        override fun requestOtaFirmware(source: OtaFirmwareSource) {
            this@MainActivity.requestOtaFirmware(source)
        }

        override fun submitFirmwarePatchRequest(
            request: FirmwarePatchRequestUiState,
            contactEmail: String,
        ) {
            this@MainActivity.submitFirmwarePatchRequest(request, contactEmail)
        }

        override fun cancelOta() {
            otaCoordinator.cancelFromDashboard()
        }

        override fun startEyevueLivePreview() {
            this@MainActivity.startEyevueLivePreview()
        }

        override fun startTuneBudsLivePreview() {
            this@MainActivity.startTuneBudsLivePreview()
        }

        override fun startLivePreview() {
            this@MainActivity.startLivePreview()
        }

        override fun stopLivePreview() {
            this@MainActivity.stopLivePreview()
        }

        override fun releaseLivePreviewSession() {
            releaseExclusiveGlassesSession(livePreviewSessionLease)
            livePreviewSessionLease = null
        }

        override fun startWifiAdbDebug() {
            this@MainActivity.startWifiAdbDebug()
        }

        override fun stopWifiAdbDebug() {
            wifiAdbDebugController.stop()
        }
    }
    private val appDestinationNavigator: AppDestinationNavigator by lazy {
        AppDestinationNavigator(this)
    }
    private val dashboardController: DashboardController by lazy {
        DashboardController(
            mainScreenHost,
            dashboardActionHost,
            deviceManagers,
            glassesDevices,
            transportPermissions,
            nativePluginShortcuts,
            meetingCaptureUi,
            metaRaybanUi,
            appDestinationNavigator,
        )
    }
    private val legacyControlsHost: LegacyControlsHost = object : LegacyControlsHost {
        override var isAiHijackEnabled: Boolean
            get() = this@MainActivity.isAiHijackEnabled
            set(value) {
                this@MainActivity.isAiHijackEnabled = value
            }
        override var isImageAssistantMode: Boolean
            get() = this@MainActivity.isImageAssistantMode
            set(value) {
                this@MainActivity.isImageAssistantMode = value
            }
        override val deviceNotifyListener: GlassesDeviceNotifyListener
            get() = this@MainActivity.deviceNotifyListener

        override fun controlAudioRecording(start: Boolean) {
            this@MainActivity.controlAudioRecording(start)
        }

        override fun controlVideoRecording(start: Boolean) {
            this@MainActivity.controlVideoRecording(start)
        }

        override fun startVoiceQuestionFromUi() {
            this@MainActivity.startVoiceQuestionFromUi()
        }

        override fun startImageQuestionFromUi() {
            this@MainActivity.startImageQuestionFromUi()
        }

        override fun selectPhoneAssistant() {
            this@MainActivity.selectPhoneAssistant()
        }

        override fun selectCustomAiProvider() {
            this@MainActivity.selectCustomAiProvider()
        }

        override fun refreshAiModeButtons() {
            this@MainActivity.refreshAiModeButtons()
        }

        override fun showOfficialHeyCyanWarningOrContinue() {
            this@MainActivity.showOfficialHeyCyanWarningOrContinue()
        }

        override fun cancelDataDownloadAttempt(reason: String, showToast: Boolean) {
            this@MainActivity.cancelDataDownloadAttempt(reason, showToast)
        }

        override fun dumpOtaServerInfo() {
            this@MainActivity.dumpOtaServerInfo()
        }

        override fun testPullModeOta() {
            this@MainActivity.testPullModeOta()
        }
    }
    private val legacyGlassesControls: LegacyGlassesControlsBinder by lazy {
        LegacyGlassesControlsBinder(
            mainScreenHost,
            legacyControlsHost,
            glassesDevices,
            deviceConnection,
            heyCyanSettings,
            glassesSessionGuard,
            transportPermissions,
            meetingCaptureUi,
        )
    }
    private val taskerCommands: TaskerCommandHandler by lazy {
        TaskerCommandHandler(mainScreenHost, legacyControlsHost)
    }
    private val assistantSetupGate: AssistantSetupGate by lazy {
        AssistantSetupGate(this) { currentAssistantRoute() }
    }

    private val metaAndroidPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            metaRaybanUi.onAndroidPermissionsResult(result)
        }

    private val metaWearablePermissionLauncher =
        registerForActivityResult(Wearables.RequestPermissionContract()) { result ->
            metaRaybanUi.onWearablePermissionResult(result)
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
        if (GlassesSessionCoordinator.currentSession() == GlassesSession.MEDIA_SYNC) {
            glassesDevices.selected().stopMediaSyncForActivityDestroy()
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

    override fun onResume() {
        super.onResume()
        glassesDevices.selected().onActivityResumed()
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
                glassesDevices.selected().runsForegroundConnectionService
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
        glassesDevices.selected().onBackgroundFeaturesRestarted()

        // Mic plugins (Live Caption Relay, Hands-Free Translator, Errand Brain) are user-controlled via
        // the glasses-tab shortcut START/STOP and must not auto-start when merely enabled in Plugins.
        // See fix for Meeting Spark Notes (afef09b) - same pattern keeps microphone off until explicit START.
        if (com.fersaiyan.cyanbridge.localmodels.remote.RemoteOpenAiPrefs.isBridgeConfigured(this)) {
            (application as? MyApplication)?.startStudioBridge()
        }
    }

    private fun ensureEnabledMetaCameraFeature() {
        if (glassesDevices.selected().cameraPluginGate == null || !hasNotificationPermission(this)) return
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
        if (glassesDevices.selected().cameraPluginGate != null) {
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

    private fun showAssistantSetupIfNeeded(kind: AssistantTestKind): Boolean =
        assistantSetupGate.showAssistantSetupIfNeeded(kind)

    private fun getOrCreateMetaRaybanManager(): com.fersaiyan.cyanbridge.devices.metarayban.MetaRaybanManager =
        deviceManagers.getOrCreateMetaRaybanManager()

    private fun showPendingMeizuMyvuFailureIfNeeded() {
        meizuMyvuUi.showPendingMeizuMyvuFailureIfNeeded()
    }

    private fun getOrCreateEyevueManager(): EyevueManager = deviceManagers.getOrCreateEyevueManager()

    private fun getOrCreateTuneBudsManager(): TuneBudsManager = deviceManagers.getOrCreateTuneBudsManager()

    private fun getOrCreateTuneBudsLivePreviewManager(): TuneBudsLivePreviewManager =
        deviceManagers.getOrCreateTuneBudsLivePreviewManager()

    private fun getOrCreateMoyoungW620Manager(): MoyoungW620Manager = deviceManagers.getOrCreateMoyoungW620Manager()

    private fun getOrCreateEyevueLivePreviewManager(): EyevueLivePreviewManager =
        deviceManagers.getOrCreateEyevueLivePreviewManager()

    private fun ensureMetaCameraReady(action: () -> Unit) {
        metaRaybanUi.ensureMetaCameraReady(action)
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

    private fun ensureGlassesTransportPermissions(feature: String, onGranted: () -> Unit) {
        transportPermissions.ensureGlassesTransportPermissions(feature, onGranted)
    }

    private fun updateDashboardState(
        transform: (GlassesDashboardUiState) -> GlassesDashboardUiState,
    ) {
        runOnUiThread {
            dashboardState = transform(dashboardState)
        }
    }

    private fun acquireExclusiveGlassesSession(session: GlassesSession): GlassesSessionLease? =
        glassesSessionGuard.acquireExclusiveGlassesSession(session)

    private fun releaseExclusiveGlassesSession(lease: GlassesSessionLease?) {
        if (lease != null && GlassesSessionCoordinator.release(lease)) {
            if (mediaSessionLease === lease) mediaSessionLease = null
            if (otaSessionLease === lease) otaSessionLease = null
            if (livePreviewSessionLease === lease) livePreviewSessionLease = null
            if (wifiAdbDebugSessionLease === lease) wifiAdbDebugSessionLease = null
            Log.i("GlassesSession", "Released ${lease.session.label} session")
        }
    }

    private fun isGlassesCommandBlocked(source: String): Boolean =
        glassesSessionGuard.isGlassesCommandBlocked(source)

    private fun acquireBackgroundGlassesCommand(source: String): BackgroundGlassesCommandPermit? =
        glassesSessionGuard.acquireBackgroundGlassesCommand(source)

    private fun warnIfBackgroundGlassesCommandTimesOut(
        permit: BackgroundGlassesCommandPermit,
        timeoutMs: Long = GlassesSessionGuard.ONE_SHOT_BLE_COMMAND_TIMEOUT_MS,
    ) {
        glassesSessionGuard.warnIfBackgroundGlassesCommandTimesOut(permit, timeoutMs)
    }

    private fun handleDashboardAction(action: GlassesDashboardAction) {
        dashboardController.handleDashboardAction(action)
    }

    private fun refreshNativePluginShortcutState() {
        nativePluginShortcuts.refreshNativePluginShortcutState()
    }

    private fun navigateToDestination(destination: AppDestination) {
        appDestinationNavigator.navigateToDestination(destination)
    }

    private fun initView() {
        legacyGlassesControls.bind()
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
        deviceConnection.updateConnectionStatus(connected)
    }

    private fun prepareAiQuestionForLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
    }

    private fun isMetaRaybanSelected(): Boolean = deviceManagers.isMetaRaybanSelected()

    private fun isEyevueSelected(): Boolean = deviceManagers.isEyevueSelected()

    private fun isTuneBudsSelected(): Boolean = deviceManagers.isTuneBudsSelected()

    private fun isHeyCyanSelected(): Boolean = deviceManagers.isHeyCyanSelected()

    private fun rejectHeyCyanOnlyFeature(feature: String): Boolean =
        deviceConnection.rejectHeyCyanOnlyFeature(feature)

    // --- Chapter 5: Meeting capture pipeline (start/stop, timer, indicator) ---

    private fun setupMeetingCaptureUi() {
        meetingCaptureUi.setupMeetingCaptureUi()
    }

    private fun setupAgentControlsUi() {
        agentControlsUi.setupAgentControlsUi()
    }

    private fun refreshAgentStatusUi() {
        agentControlsUi.refreshAgentStatusUi()
    }

    // --- Meta Ray-Ban UI setup ---

    private fun setupMetaRaybanUi() {
        metaRaybanUi.setupMetaRaybanUi()
    }

    private fun showMetaError(operation: String, message: String) {
        metaRaybanUi.showMetaError(operation, message)
    }

    private fun updateMetaRaybanUiState() {
        metaRaybanUi.updateMetaRaybanUiState()
    }

    private fun registerMeetingCaptureReceiver() {
        meetingCaptureUi.registerMeetingCaptureReceiver()
    }

    private fun unregisterMeetingCaptureReceiver() {
        meetingCaptureUi.unregisterMeetingCaptureReceiver()
    }

    private fun syncMeetingCaptureUiFromPrefs() {
        meetingCaptureUi.syncMeetingCaptureUiFromPrefs()
    }

    // --- end Chapter 5 meeting capture ---

    // --- Transcription UI moved to RecordingsListActivity (per-item) ---



    private fun updateBatteryText(battery: Int?) {
        deviceConnection.updateBatteryText(battery)
    }

    private fun requestBatteryStatus(showToast: Boolean) {
        deviceConnection.requestBatteryStatus(showToast)
    }

    private fun handleBatteryReport(battery: Int, charging: Boolean) {
        deviceConnection.handleBatteryReport(battery, charging)
    }

    private fun handleTaskerCommand(startIntent: Intent?) {
        taskerCommands.handleTaskerCommand(startIntent)
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

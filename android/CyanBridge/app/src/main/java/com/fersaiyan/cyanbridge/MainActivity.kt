package com.fersaiyan.cyanbridge

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.widget.ArrayAdapter
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.fersaiyan.cyanbridge.agent.AgentProviderType
import com.fersaiyan.cyanbridge.agent.LocalAgentPrefs as AutomationPrefs
import com.fersaiyan.cyanbridge.ui.VersionUpdateChecker
import com.fersaiyan.cyanbridge.localagent.LocalAgentController
import com.fersaiyan.cyanbridge.localagent.LocalAgentIntents
import com.fersaiyan.cyanbridge.localagent.LocalAgentPrefs
import com.fersaiyan.cyanbridge.audio.CaptureSource
import com.fersaiyan.cyanbridge.audio.MeetingCapturePrefs
import com.fersaiyan.cyanbridge.audio.MeetingCaptureService
import com.fersaiyan.cyanbridge.media.GlassesMediaPrefs
import com.fersaiyan.cyanbridge.media.SyncedMediaFolder
import com.fersaiyan.cyanbridge.media.autocapture.AutoAudioCapturePrefs
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
import com.hjq.permissions.OnPermissionCallback
import com.hjq.permissions.XXPermissions
import com.oudmon.ble.base.communication.utils.ByteUtil
import com.oudmon.ble.base.bluetooth.BleOperateManager
import com.oudmon.ble.base.bluetooth.DeviceManager
import com.oudmon.ble.base.communication.LargeDataHandler
import com.oudmon.ble.base.communication.bigData.resp.GlassesDeviceNotifyListener
import com.oudmon.ble.base.communication.bigData.resp.GlassesDeviceNotifyRsp
import com.fersaiyan.cyanbridge.databinding.AcitivytMainBinding
import com.fersaiyan.cyanbridge.ui.DeviceBindActivity
import com.fersaiyan.cyanbridge.ui.ChatListActivity
import com.fersaiyan.cyanbridge.ui.ChatThreadActivity
import com.fersaiyan.cyanbridge.ui.CommunityPluginPrefs
import com.fersaiyan.cyanbridge.ui.CommunityPluginsActivity
import com.fersaiyan.cyanbridge.ui.SettingsActivity
// import com.fersaiyan.cyanbridge.ui.notes.NotesListActivity
import com.fersaiyan.cyanbridge.ui.recordings.RecordingsListActivity
import com.fersaiyan.cyanbridge.ui.BluetoothUtils
import com.fersaiyan.cyanbridge.ui.BluetoothEvent
import com.fersaiyan.cyanbridge.ui.AutoPairManager
import com.fersaiyan.cyanbridge.chat.ChatStore
import com.fersaiyan.cyanbridge.devices.DeviceProfileStore
import com.fersaiyan.cyanbridge.devices.GlassesManagerGating
import com.fersaiyan.cyanbridge.ai.transcription.DefaultTranscriptionService
import com.fersaiyan.cyanbridge.ai.transcription.Mp4AudioChunker
import com.fersaiyan.cyanbridge.ai.transcription.NoOpAudioChunker
import com.fersaiyan.cyanbridge.ai.transcription.OpenAIWhisperTranscriptionProvider
import com.fersaiyan.cyanbridge.ai.transcription.RetryPolicy
import com.fersaiyan.cyanbridge.ai.transcription.RetryingTranscriptionProvider
import com.fersaiyan.cyanbridge.ai.transcription.vosk.VoskModelManager
import com.fersaiyan.cyanbridge.ai.transcription.vosk.VoskTranscriptionProvider
import com.fersaiyan.cyanbridge.ai.transcription.TranscriptionProgress
import com.fersaiyan.cyanbridge.ai.transcription.TranscriptionResult
import com.fersaiyan.cyanbridge.ai.transcription.TranscriptionService
import com.fersaiyan.cyanbridge.privacy.PrivacyPrefs
import com.fersaiyan.cyanbridge.ui.MyApplication
import com.fersaiyan.cyanbridge.ui.bleIpBridge
import com.fersaiyan.cyanbridge.ui.hasBluetooth
import com.fersaiyan.cyanbridge.ui.requestAllPermission
import com.fersaiyan.cyanbridge.ui.requestBluetoothPermission
import com.fersaiyan.cyanbridge.ui.requestLocationPermission
import com.fersaiyan.cyanbridge.ui.requestNearbyWifiDevicesPermission
import com.fersaiyan.cyanbridge.ui.setOnClickListener
import com.fersaiyan.cyanbridge.ui.startKtxActivity
import com.fersaiyan.cyanbridge.ui.debug.DebugLogSupport
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.ByteArrayOutputStream
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
import com.fersaiyan.cyanbridge.agent.ProSubscriptionAiPrefs
import com.fersaiyan.cyanbridge.ai.router.AiProviderPrefs
import com.fersaiyan.cyanbridge.ai.router.AiProviderType as RelayProviderType
import com.fersaiyan.cyanbridge.ai.router.CliRelayClient
import com.fersaiyan.cyanbridge.localagent.context.LocalAgentContextBuilder
import com.fersaiyan.cyanbridge.localagent.dailyfacts.DailyFactsStorage
import com.fersaiyan.cyanbridge.localagent.memory.LocalAgentMemorySearch
import com.fersaiyan.cyanbridge.localagent.memory.LocalAgentMemoryStore
import com.fersaiyan.cyanbridge.localagent.userfacts.CandidateUserFactsStorage
import com.fersaiyan.cyanbridge.localmodels.provider.LocalModelsProvider
import com.fersaiyan.cyanbridge.localmodels.settings.LocalModelRuntime
import com.fersaiyan.cyanbridge.localmodels.settings.LocalModelSettingsRepository
import com.fersaiyan.cyanbridge.localmodels.storage.LocalModelStorageRepository
import com.fersaiyan.cyanbridge.memoryvault.MemoryPolicyService
import android.content.ClipboardManager
import android.content.ClipData


class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = null
    private val ttsDoneCallbacks = ConcurrentHashMap<String, () -> Unit>()

    // Optional Local Agent UI status
    private var agentReceiverRegistered = false
    private val agentStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return

            val status = intent.getStringExtra(LocalAgentIntents.EXTRA_STATUS)
            val lastError = intent.getStringExtra(LocalAgentIntents.EXTRA_LAST_ERROR)

            if (!status.isNullOrBlank()) {
                LocalAgentPrefs.setStatus(this@MainActivity, status)
            }
            if (!lastError.isNullOrBlank()) {
                LocalAgentPrefs.setLastError(this@MainActivity, lastError)
            }

            refreshAgentStatusUi()
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US
        }
    }

    private fun speak(text: String) {
        speak(text, utteranceId = null, onDone = null)
    }

    private fun speak(
        text: String,
        utteranceId: String?,
        onDone: (() -> Unit)?,
    ) {
        val id = utteranceId ?: "utt_${System.currentTimeMillis()}"
        if (onDone != null) {
            ttsDoneCallbacks[id] = onDone
        }

        val bundle = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, id)
        }

        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, bundle, id)
    }
    companion object {
        const val EXTRA_TASKER_COMMAND = "tasker_command"
        private var loggedLargeDataHandlerMethods = false
        private const val AI_MODE_GEMINI = "Gemini"
        private const val AI_MODE_CHATGPT = "ChatGPT"
        private const val AI_MODE_TASKER = "Tasker"
        private const val AI_MODE_CHOSEN_PROVIDER = "ChosenProvider"
        private const val TASKER_PACKAGE_NAME = "net.dinglisch.android.taskerm"
        private const val QUERY_MAX_AGENT_PERSONA_CHARS = 1200
        private const val QUERY_MAX_USER_FACTS_CHARS = 1400
        private const val QUERY_MAX_CONFIRMED_FACTS_CHARS = 1800
        private const val QUERY_MAX_DAILY_SUMMARY_CHARS = 2200
        private const val QUERY_MAX_TOTAL_CONTEXT_CHARS = 6500

        // Max age for a fallback image to be considered "recent enough" for AI analysis.
        private const val IMAGE_FALLBACK_MAX_AGE_MS = 3L * 60L * 1000L

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

    private lateinit var binding: AcitivytMainBinding
    private val deviceNotifyListener by lazy { MyDeviceNotifyListener() }

    // AI Hijack settings
    private var isAiHijackEnabled = true // Default to enabled
    private var isImageAssistantMode = true // Use assistant vs share intent
    private var aiAssistantMode = AI_MODE_GEMINI

    // State used by the BLE+WiFi P2P data-download flow
    private var downloadP2pConnected = false
    private var downloadBleIp: String? = null
    private var downloadWifiIp: String? = null
    private var downloadPhoneIsGroupOwner: Boolean = true
    private var downloadInProgress = false
    private var downloadAttemptJob: Job? = null
    private var downloadResolvedHttpIp: String? = null
    private var downloadP2pNetwork: Network? = null
    private var boundNetwork: Network? = null
    private var lastP2pResetAtMs: Long = 0L
    private var downloadWifiP2pManager: WifiP2pManagerSingleton? = null
    private var downloadWifiP2pCallback: WifiP2pManagerSingleton.WifiP2pCallback? = null
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

    // Guard against concurrent/duplicate image queries
    private val imageQueryInProgress = java.util.concurrent.atomic.AtomicBoolean(false)
    private var lastImageQueryAtMs: Long = 0L

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
    private var batteryPollJob: Job? = null
    private val batteryPollIntervalMs = 60_000L
    private val downloadInitialPhaseTimeoutMs = 45_000L
    private var pendingBatteryToast = false
    private var batteryCallbackRegistered = false

    // Chapter 5: meeting capture UI + state
    private val meetingTimerOptions: List<Pair<Long?, String>> = listOf(
        null to "No timer",
        15L * 60L to "15 min",
        60L * 60L to "1 hour",
        3L * 60L * 60L to "3 hours",
    )
    private var meetingCaptureStateReceiver: BroadcastReceiver? = null

    // Transcription UI moved to the "Transcriptions & recordings" section

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = AcitivytMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupBottomNavigation()
        initView()
        setupMeetingCaptureUi()
        setupAgentControlsUi()
        // Transcription UI moved to the "Transcriptions & recordings" section
        logLargeDataHandlerMethodsOnce()
        // Check for app updates
        VersionUpdateChecker.checkForUpdates(this)
        // Initialize TTS
        tts = TextToSpeech(this, this)
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
            }

            override fun onDone(utteranceId: String?) {
                utteranceId?.let { ttsDoneCallbacks.remove(it)?.invoke() }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                utteranceId?.let { ttsDoneCallbacks.remove(it)?.invoke() }
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                utteranceId?.let { ttsDoneCallbacks.remove(it)?.invoke() }
            }
        })

        // Ensure we always listen for glasses reports (battery, AI, volume, etc.)
        LargeDataHandler.getInstance().addOutDeviceListener(100, deviceNotifyListener)

        // Lazily register the import/download notify listener the first time we need it.
        handleTaskerCommand(intent)

        BatteryOptimizationGuideActivity.launchIfNeeded(this)
    }

    override fun onStart() {
        super.onStart()
        if (!EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().register(this)
        }
        updateConnectionStatus(BleOperateManager.getInstance().isConnected)
        registerMeetingCaptureReceiver()
        syncMeetingCaptureUiFromPrefs()

        if (!agentReceiverRegistered) {
            LocalBroadcastManager.getInstance(this)
                .registerReceiver(agentStatusReceiver, IntentFilter(LocalAgentIntents.ACTION_STATUS_CHANGED))
            agentReceiverRegistered = true
        }
        LocalAgentController.requestStatus(this)
        refreshAgentStatusUi()
    }

    override fun onStop() {
        super.onStop()
        stopBatteryPolling()
        unregisterMeetingCaptureReceiver()

        if (agentReceiverRegistered) {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(agentStatusReceiver)
            agentReceiverRegistered = false
        }

        if (EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().unregister(this)
        }
    }

    override fun onDestroy() {
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
            if(never){
                XXPermissions.startPermissionActivity(this@MainActivity, permissions);
            }
        }

    }


    override fun onResume() {
        super.onResume()
        try {
            if (!BluetoothUtils.isEnabledBluetooth(this)) {
                val intent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (ActivityCompat.checkSelfPermission(
                            this,
                            Manifest.permission.BLUETOOTH_CONNECT
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        return
                    }
                }
                startActivityForResult(intent, 300)
            }
        } catch (e: Exception) {
        }
        if (!hasBluetooth(this)) {
            requestBluetoothPermission(this, BluetoothPermissionCallback())
        }

        requestAllPermission(this, OnPermissionCallback { permissions, all ->  })

        // Check for Overlay permission needed for background launch
        if (isAiHijackEnabled && !Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivityForResult(intent, 1234)
            Toast.makeText(this, "Please enable Overlay permission for background AI", Toast.LENGTH_LONG).show()
        }

        // Ensure correct nav highlight when returning via CLEAR_TOP/SINGLE_TOP.
        binding.bottomNavigation.post {
            binding.bottomNavigation.menu.findItem(R.id.nav_glasses).isChecked = true
        }
        refreshAiQueryButtonsState()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleTaskerCommand(intent)
    }

    inner class BluetoothPermissionCallback : OnPermissionCallback {
        override fun onGranted(permissions: MutableList<String>, all: Boolean) {
            if (!all) {

            }
        }

        override fun onDenied(permissions: MutableList<String>, never: Boolean) {
            super.onDenied(permissions, never)
            if (never) {
                XXPermissions.startPermissionActivity(this@MainActivity, permissions)
            }
        }

    }

    private fun setupBottomNavigation() {
        binding.bottomNavigation.selectedItemId = R.id.nav_glasses
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_glasses -> true
                R.id.nav_chats -> {
                    binding.bottomNavigation.post {
                        val last = ChatStore.listNonEmptyThreads().firstOrNull()
                        val now = System.currentTimeMillis()

                        fun lastUserMessageAtMs(chatId: String): Long? {
                            val msgs = ChatStore.listMessages(chatId)
                            return msgs.lastOrNull { it.role == com.fersaiyan.cyanbridge.chat.ChatRole.USER }?.createdAt
                        }

                        val openChatId = if (last != null) {
                            val lastUserAt = lastUserMessageAtMs(last.id) ?: 0L
                            if (lastUserAt > 0L && (now - lastUserAt) < 30 * 60 * 1000) last.id else null
                        } else null

                        val intent = Intent(this, ChatThreadActivity::class.java)
                        if (openChatId != null) {
                            intent.putExtra(ChatThreadActivity.EXTRA_CHAT_ID, openChatId)
                        }
                        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        startActivity(intent)
                    }
                    true
                }
                R.id.nav_transcriptions_recordings -> {
                    binding.bottomNavigation.post {
                        startActivity(Intent(this, RecordingsListActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        })
                    }
                    true
                }
                R.id.nav_settings -> {
                    binding.bottomNavigation.post {
                        startActivity(Intent(this, SettingsActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        })
                    }
                    true
                }
                R.id.nav_community_plugins -> {
                    binding.bottomNavigation.post {
                        startActivity(Intent(this, CommunityPluginsActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        })
                    }
                    true
                }
                else -> false
            }
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
            // Safety: stop glasses audio recording before most actions.
            // Users often press camera/video/etc while audio is running.
            val shouldStopGlassesAudio = this != binding.btnScan && this != binding.btnConnect && this != binding.btnTransferStop
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
                    triggerAssistantVoiceQuery()
                }

                binding.btnTestHijackImage -> {
                    val unsupportedReason = imageQueryUnsupportedReasonForCurrentSelection()
                    if (unsupportedReason != null) {
                        Toast.makeText(
                            this@MainActivity,
                            unsupportedReason,
                            Toast.LENGTH_SHORT,
                        ).show()
                        return@setOnClickListener
                    }

                    if (maybeShowGeminiChatGptImageRequirementsWarning()) {
                        return@setOnClickListener
                    }

                    triggerCliRelayImageCaptureAndQuery()
                }

                binding.btnModeGemini -> {
                    aiAssistantMode = AI_MODE_GEMINI
                    refreshAiModeButtons()
                    Toast.makeText(this@MainActivity, "AI Mode: Google Gemini", Toast.LENGTH_SHORT).show()
                }

                binding.btnModeChatgpt -> {
                    aiAssistantMode = AI_MODE_CHATGPT
                    refreshAiModeButtons()
                    Toast.makeText(this@MainActivity, "AI Mode: ChatGPT", Toast.LENGTH_SHORT).show()
                }

                binding.btnModeTasker -> {
                    aiAssistantMode = AI_MODE_CHOSEN_PROVIDER
                    refreshAiModeButtons()

                    val msg = when (AutomationPrefs.getProviderType(this@MainActivity)) {
                        AgentProviderType.TASKER -> "AI Mode: Chosen Provider (Tasker Broadcast)"
                        AgentProviderType.PRO_SUBSCRIPTION -> "AI Mode: Chosen Provider (Pro Subscription)"
                        AgentProviderType.LOCAL_AGENT -> "AI Mode: Chosen Provider (Local Agent)"
                    }
                    Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                }

                // Notes & Summaries entry removed (moved to Transcriptions & recordings section)

                binding.btnMeetingStart -> {
                    startMeetingCaptureFromUi()
                }

                binding.btnMeetingStop, binding.btnMeetingBannerStop -> {
                    stopMeetingCaptureFromUi()
                }

                binding.btnScan -> {
                    requestLocationPermission(this@MainActivity, PermissionCallback())
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
                    LargeDataHandler.getInstance().glassesControl(
                        byteArrayOf(0x02, 0x01, 0x01)
                    ) { _, it ->
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
                        } else {
                            //Execute start and end
                        }
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
                    LargeDataHandler.getInstance().glassesControl(byteArrayOf(0x02, 0x04)) { _, it ->
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
                    }
                }
                binding.btnDataDownload -> {
                    Toast.makeText(this@MainActivity, "Starting data download…", Toast.LENGTH_SHORT).show()
                    // Check and request necessary permissions
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        // Android 13+ requires NEARBY_WIFI_DEVICES permission
                        requestNearbyWifiDevicesPermission(this@MainActivity, object : OnPermissionCallback {
                            override fun onGranted(permissions: MutableList<String>, all: Boolean) {
                                if (all) {
                                    // Start BLE+WiFi P2P data download
                                    startDataDownload()
                                }
                            }

                            override fun onDenied(permissions: MutableList<String>, never: Boolean) {
                                super.onDenied(permissions, never)
                                if (never) {
                                    XXPermissions.startPermissionActivity(this@MainActivity, permissions)
                                }
                            }
                        })
                    } else {
                        // Android 12 and below start download directly
                        startDataDownload()
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

        Log.i("PullOtaTest", "Calling writeIpToSoc with URL: $url")
        LargeDataHandler.getInstance().writeIpToSoc(url) { cmdType, response ->
            Log.i(
                "PullOtaTest",
                "writeIpToSoc callback: cmdType=$cmdType, response=$response"
            )
        }
    }
    
    private fun controlVideoRecording(start: Boolean) {
        val value = if (start) 0x02 else 0x03

        // While video is recording, pause the auto audio loop.
        if (start) {
            AutoAudioCapturePrefs.setPausedForVideo(this, true)
            GlassesMediaPrefs.setVideoRecording(this, true) // optimistic
        }

        LargeDataHandler.getInstance().glassesControl(
            byteArrayOf(0x02, 0x01, value.toByte())
        ) { _, rsp ->
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
        }
    }
    
    private fun controlAudioRecording(start: Boolean) {
        val value = if (start) 0x08 else 0x0c
        LargeDataHandler.getInstance().glassesControl(
            byteArrayOf(0x02, 0x01, value.toByte())
        ) { _, it ->
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
                } else {
                    //Execute start and end
                }
            }
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onBluetoothEvent(event: BluetoothEvent) {
        updateConnectionStatus(event.connect)
        if (event.connect) {
            requestBatteryStatus(showToast = false)
        } else {
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
        if (aiAssistantMode != AI_MODE_CHOSEN_PROVIDER) {
            return aiAssistantMode
        }
        return when (AutomationPrefs.getProviderType(this)) {
            AgentProviderType.TASKER -> AI_MODE_TASKER
            AgentProviderType.PRO_SUBSCRIPTION -> AI_MODE_CHOSEN_PROVIDER
            AgentProviderType.LOCAL_AGENT -> AI_MODE_CHOSEN_PROVIDER
        }
    }

    private fun isChosenProviderMode(): Boolean = aiAssistantMode == AI_MODE_CHOSEN_PROVIDER

    private fun isChosenProviderCloudEndpoint(): Boolean {
        if (!isChosenProviderMode()) return false
        return when (AutomationPrefs.getProviderType(this)) {
            AgentProviderType.PRO_SUBSCRIPTION -> true
            AgentProviderType.LOCAL_AGENT,
            AgentProviderType.TASKER -> false
        }
    }

    private fun imageQueryUnsupportedReasonForCurrentSelection(): String? {
        if (!isChosenProviderMode()) return null
        if (AutomationPrefs.getProviderType(this) != AgentProviderType.LOCAL_AGENT) return null

        val selected = LocalModelStorageRepository.resolveSelectedModel(this)
            ?: return "No local model selected. Install/select Gemma 4 LiteRT first."
        val settings = LocalModelSettingsRepository.getForModel(this, selected.id)
        if (settings.modelRuntime != LocalModelRuntime.LITERT) {
            return "Image questions require Local Runtime = LiteRT for the selected model."
        }

        val modelHint = "${selected.displayName} ${selected.catalogId.orEmpty()} ${selected.fileName}".lowercase(Locale.US)
        if (!modelHint.contains("gemma")) {
            return "Select a Gemma LiteRT model for local image questions."
        }
        return null
    }

    private fun isGeminiOrChatGptModeSelected(): Boolean {
        return aiAssistantMode == AI_MODE_GEMINI || aiAssistantMode == AI_MODE_CHATGPT
    }

    private fun requiresTaskerAutomationForImageQuestions(): Boolean {
        if (!isGeminiOrChatGptModeSelected()) return false
        return AiProviderPrefs.getProvider(this) != RelayProviderType.CLI_RELAY
    }

    private fun isTaskerInstalled(): Boolean {
        return runCatching {
            packageManager.getPackageInfo(TASKER_PACKAGE_NAME, 0)
            true
        }.getOrDefault(false)
    }

    private fun maybeShowGeminiChatGptImageRequirementsWarning(): Boolean {
        if (!requiresTaskerAutomationForImageQuestions()) return false

        val taskerInstalled = isTaskerInstalled()
        val pluginEnabled = CommunityPluginPrefs.isGeminiChatGptImageAutomationEnabled(this)
        if (taskerInstalled && pluginEnabled) return false

        val msg = when {
            !taskerInstalled && !pluginEnabled ->
                "AI image questions won't work until Tasker and the Gemini/ChatGPT Image Questions automation plugin are enabled."
            !taskerInstalled ->
                "AI image questions won't work until Tasker is installed and enabled."
            else ->
                "AI image questions won't work until the Gemini/ChatGPT app automation plugin for Image Questions is downloaded from Community Plugins and enabled."
        }

        AlertDialog.Builder(this)
            .setTitle("AI image setup required")
            .setMessage(msg)
            .setNegativeButton("Not now", null)
            .setPositiveButton("Open Plugins") { _, _ ->
                startActivity(Intent(this, CommunityPluginsActivity::class.java))
            }
            .show()

        return true
    }

    private fun refreshAiQueryButtonsState() {
        val unsupportedReason = imageQueryUnsupportedReasonForCurrentSelection()
        val imageSupported = unsupportedReason == null
        binding.btnTestHijackImage.isEnabled = imageSupported
        binding.btnTestHijackImage.alpha = if (imageSupported) 1f else 0.45f

        if (!imageSupported) {
            binding.btnTestHijackImage.text = "Image query unavailable"
        } else {
            binding.btnTestHijackImage.text = "Test Image AI description"
        }
    }

    private fun refreshAiModeButtons() {
        val activeColor = ContextCompat.getColor(this, R.color.cyan_accent)
        val inactiveColor = ContextCompat.getColor(this, R.color.text_secondary)

        binding.btnModeGemini.setTextColor(if (aiAssistantMode == AI_MODE_GEMINI) activeColor else inactiveColor)
        binding.btnModeChatgpt.setTextColor(if (aiAssistantMode == AI_MODE_CHATGPT) activeColor else inactiveColor)
        val chosenProviderSelected = aiAssistantMode == AI_MODE_CHOSEN_PROVIDER
        binding.btnModeTasker.setTextColor(if (chosenProviderSelected) activeColor else inactiveColor)
        refreshAiQueryButtonsState()
    }

    private fun sendAiBroadcast(type: String, path: String? = null, assistantMode: String = resolveEffectiveAiAssistantMode()) {
        val intent = Intent(aiEventAction(packageName)).apply {
            putExtra("type", type)
            path?.let { putExtra("path", it) }
            putExtra("assistant", assistantMode)
            addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
        }
        sendBroadcast(intent)
        Log.i("AIHijack", "Sent Broadcast to Tasker: $type")
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

    private fun buildCompactMemoryAwareSystemPrompt(queryText: String, date: String): String {
        val extraSections = mutableListOf<LocalAgentContextBuilder.Section>()

        val retrieval = LocalAgentMemorySearch.buildRelevantMemoryBlock(
            context = this,
            queryText = queryText,
            date = date,
            lookbackDaysFacts = 5,
            topFacts = 4,
            topSummaryLines = 3,
            maxChars = 900,
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
            selectRelevantMemoryItems(draftFacts, queryText, maxItems = 4)
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
            selectRelevantMemoryItems(candidateFacts, queryText, maxItems = 3)
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
            maxAgentPersonaChars = QUERY_MAX_AGENT_PERSONA_CHARS,
            maxUserFactsChars = QUERY_MAX_USER_FACTS_CHARS,
            maxConfirmedDailyFactsChars = QUERY_MAX_CONFIRMED_FACTS_CHARS,
            maxDailySummaryChars = QUERY_MAX_DAILY_SUMMARY_CHARS,
            maxTotalChars = QUERY_MAX_TOTAL_CONTEXT_CHARS,
        )

        return builder.buildSystemMessage(
            context = this,
            date = date,
            extraSections = extraSections,
        )
    }

    private suspend fun runMemoryAwareChosenProviderQuery(
        userPrompt: String,
        providerType: AgentProviderType,
        imagePaths: List<String> = emptyList(),
        audioPath: String? = null,
    ): String {
        val date = todayDateString()
        val systemPrompt = buildCompactMemoryAwareSystemPrompt(queryText = userPrompt, date = date)

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
                    val modelIssue = validateSelectedGemmaForChosenProvider(imageRequested = imagePaths.isNotEmpty())
                    if (modelIssue != null) {
                        return@runCatching modelIssue
                    }
                    LocalModelsProvider().streamChat(
                        context = this,
                        messages = messages,
                        imagePaths = imagePaths,
                        audioPath = audioPath,
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

    private fun validateSelectedGemmaForChosenProvider(imageRequested: Boolean): String? {
        val selected = LocalModelStorageRepository.resolveSelectedModel(this)
            ?: return "No local model selected. Install/select Gemma 4 LiteRT in Settings."
        val settings = LocalModelSettingsRepository.getForModel(this, selected.id)
        if (settings.modelRuntime != LocalModelRuntime.LITERT) {
            return "Selected local model runtime is not LiteRT. Switch runtime to LiteRT for Gemma 4 flows."
        }

        val modelHint = "${selected.displayName} ${selected.catalogId.orEmpty()} ${selected.fileName}".lowercase(Locale.US)
        if (!modelHint.contains("gemma")) {
            return "Selected local model is not Gemma. Please select a Gemma 4 LiteRT model."
        }

        if (imageRequested && !modelHint.contains("gemma-4") && !modelHint.contains("gemma4")) {
            return "Image questions on glasses are configured for Gemma 4 LiteRT. Please select Gemma 4 E2B/E4B."
        }
        return null
    }

    private fun triggerMemoryAwareImageQuery(
        imagePath: String,
        providerType: AgentProviderType,
        userQuestion: String?,
    ) {
        Log.i("AIHijack", "Running memory-aware image query for chosen provider $providerType: $imagePath")

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val finalReply = when (providerType) {
                    AgentProviderType.PRO_SUBSCRIPTION -> {
                        val visionResult = CliRelayClient.imageQuery(
                            context = this@MainActivity,
                            imagePath = imagePath,
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
                                val leadPrompt = userQuestion?.trim().takeUnless { it.isNullOrBlank() }
                                    ?: "Describe and translate to English the following picture if it isn't in English."
                                val followUpPrompt = buildString {
                                    appendLine(leadPrompt)
                                    appendLine("Use this vision observation:")
                                    appendLine(visionReply.take(1400))
                                    appendLine()
                                    appendLine("Keep the final answer concise (1-3 short sentences).")
                                }
                                runMemoryAwareChosenProviderQuery(
                                    userPrompt = followUpPrompt,
                                    providerType = AgentProviderType.PRO_SUBSCRIPTION,
                                )
                                null // Don't speak here - follow-up query will handle it
                            }
                        }
                    }

                    AgentProviderType.LOCAL_AGENT -> {
                        val multimodalPrompt = userQuestion?.trim().takeUnless { it.isNullOrBlank() }
                            ?: "Describe this image clearly, and translate any visible non-English text to English. Keep it concise."
                        runMemoryAwareChosenProviderQuery(
                            userPrompt = multimodalPrompt,
                            providerType = AgentProviderType.LOCAL_AGENT,
                            imagePaths = listOf(imagePath),
                        )
                    }

                    AgentProviderType.TASKER -> {
                        val visionResult = CliRelayClient.imageQuery(this@MainActivity, imagePath)
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

                if (finalReply != null) {
                    runOnUiThread {
                        speak(finalReply)
                    }
                }
            } finally {
                imageQueryInProgress.set(false)
            }
        }
    }

    private fun triggerCliRelayImageCaptureAndQuery() {
        handleGlassesImageButtonPressed(triggerCapture = true, sourceTag = "test_button")
    }

    private fun handleGlassesImageButtonPressed(triggerCapture: Boolean, sourceTag: String) {
        if (!BleOperateManager.getInstance().isConnected) {
            runOnUiThread {
                Toast.makeText(
                    this@MainActivity,
                    "Glasses are not connected. Connect first to use image query.",
                    Toast.LENGTH_SHORT,
                ).show()
            }
            return
        }

        if (triggerCapture) {
            Toast.makeText(this, "Triggering glasses camera…", Toast.LENGTH_SHORT).show()
        }

        val outDir = getExternalFilesDir("DCIM") ?: filesDir
        val fileName = "AI_Thumb_${sourceTag}_${System.currentTimeMillis()}.jpg"
        val file = File(outDir, fileName)
        runCatching {
            file.parentFile?.mkdirs()
            if (file.exists()) file.delete()
        }

        val gotChunk = java.util.concurrent.atomic.AtomicBoolean(false)
        val completed = java.util.concurrent.atomic.AtomicBoolean(false)
        val imageProcessed = java.util.concurrent.atomic.AtomicBoolean(false)

        val thumbCallback: (Int, Boolean, ByteArray?) -> Unit = { _, isComplete, data ->
            if (data != null && data.isNotEmpty()) {
                gotChunk.set(true)
                runCatching {
                    FileOutputStream(file, true).use { it.write(data) }
                }.onFailure {
                    Log.e("AIHijack", "Failed to write thumbnail chunk: ${it.message}")
                }
            }

            if (isComplete && completed.compareAndSet(false, true)) {
                Log.i("AIHijack", "[$sourceTag] Thumbnail transfer complete: ${file.absolutePath} (${file.length()} bytes)")
                if (imageProcessed.compareAndSet(false, true)) {
                    onImageThumbnailReadyForQuestion(file.absolutePath)
                }
            }
        }

        CoroutineScope(Dispatchers.IO).launch {
            if (triggerCapture) {
                runCatching {
                    LargeDataHandler.getInstance().glassesControl(
                        byteArrayOf(0x02, 0x01, 0x06, 0x02, 0x02)
                    ) { _, _ -> }
                }
                delay(250)
                LargeDataHandler.getInstance().glassesControl(byteArrayOf(0x02, 0x01, 0x01)) { _, _ -> }
                delay(3000)
            }

            LargeDataHandler.getInstance().getPictureThumbnails(thumbCallback)

            // Wait for BLE transfer to complete. Total: 5s + 8s = 13s.
            delay(5000)
            if (!gotChunk.get() && !completed.get()) {
                Log.w("AIHijack", "[$sourceTag] No thumbnail chunks yet; retrying getPictureThumbnails()…")
                LargeDataHandler.getInstance().getPictureThumbnails(thumbCallback)
            }

            delay(8000)
            if (!completed.get() && imageProcessed.compareAndSet(false, true)) {
                Log.w("AIHijack", "[$sourceTag] BLE thumbnail timed out, falling back to latest image")
                useLatestImageFallback(sourceTag)
            }
        }
    }

    /**
     * Use the most recent Glasses_AI_*.jpg already on the phone.
     */
    private suspend fun useLatestImageFallback(sourceTag: String) {
        val fallbackImage = findLatestGlassesAiImage()
        if (fallbackImage != null) {
            val fallbackFile = File(fallbackImage)
            val ageMs = System.currentTimeMillis() - fallbackFile.lastModified()
            if (ageMs > IMAGE_FALLBACK_MAX_AGE_MS || ageMs < 0) {
                Log.w("AIHijack", "[$sourceTag] Image too old: age=${ageMs / 1000}s")
                runOnUiThread {
                    Toast.makeText(
                        this@MainActivity,
                        "Last image is ${ageMs / 60000} min old — too old to use.",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            } else {
                Log.i("AIHijack", "[$sourceTag] Using latest captured image (age=${ageMs / 1000}s)")
                onImageThumbnailReadyForQuestion(fallbackImage)
            }
        } else {
            runOnUiThread {
                Toast.makeText(
                    this@MainActivity,
                    "No image found. Take a photo with the glasses first.",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun onImageThumbnailReadyForQuestion(imagePath: String) {
        val imageFile = File(imagePath)
        if (!imageFile.exists() || imageFile.length() < 1000) {
            Log.e("AIHijack", "Image file missing or too small: $imagePath (${imageFile.length()} bytes)")
            runOnUiThread {
                Toast.makeText(this, "Image transfer incomplete. Please try again.", Toast.LENGTH_LONG).show()
            }
            return
        }

        val ageMs = System.currentTimeMillis() - imageFile.lastModified()
        if (ageMs > IMAGE_FALLBACK_MAX_AGE_MS || ageMs < 0) {
            Log.w("AIHijack", "Thumbnail too old: age=${ageMs / 1000}s, path=$imagePath")
            runOnUiThread {
                Toast.makeText(
                    this,
                    "Thumbnail is ${ageMs / 60000} min old — too old to use.",
                    Toast.LENGTH_LONG,
                ).show()
            }
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            val publicPath = copyImageToPublicCamera(imagePath)

            Log.i("AIHijack", "Image ready for AI query: $imagePath (size=${imageFile.length()} bytes, age=${ageMs / 1000}s)")

            // Process the image query first (model inference + TTS reply).
            // triggerAssistantImageQuery launches a background coroutine and returns immediately,
            // so we must wait for TTS to finish before opening the follow-up voice window.
            triggerAssistantImageQuery(imagePath, userQuestion = null)

            // Wait for the model's TTS reply to finish (polls tts?.isSpeaking every 500ms).
            waitForTtsToFinish(timeoutMs = 90_000L)

            // Brief pause after TTS so the user knows it's their turn.
            delay(500)

            // Now open the voice window for a follow-up question.
            withContext(Dispatchers.Main) {
                val spokenQuestion = captureOptionalImageQuestionFromBluetoothMic(timeoutMs = 3_000L)
                if (!spokenQuestion.isNullOrBlank()) {
                    triggerAssistantImageQuery(imagePath, spokenQuestion)
                }
            }
        }
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

    /** Find the most recent Glasses_AI_*.jpg in DCIM/Camera/. */
    private fun findLatestGlassesAiImage(): String? {
        return try {
            val cameraDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
                "Camera"
            )
            if (!cameraDir.isDirectory) return null
            cameraDir.listFiles { f ->
                f.isFile && f.name.startsWith("Glasses_AI_") && f.name.endsWith(".jpg", ignoreCase = true)
            }
                ?.filter { it.length() > 0 }
                ?.maxByOrNull { it.lastModified() }
                ?.absolutePath
        } catch (_: Exception) { null }
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
                        audioManager.isBluetoothScoOn = false
                        audioManager.stopBluetoothSco()
                        audioManager.mode = android.media.AudioManager.MODE_NORMAL
                    }
                }

                fun finish(result: String?) {
                    if (finished) return
                    finished = true
                    timeoutJob?.cancel()
                    timeoutJob = null
                    val cleaned = result?.trim()?.takeIf { it.isNotBlank() }

                    runCatching {
                        val tone = android.media.ToneGenerator(android.media.AudioManager.STREAM_VOICE_CALL, 90)
                        tone.startTone(android.media.ToneGenerator.TONE_PROP_BEEP2, 170)
                        CoroutineScope(Dispatchers.Main).launch {
                            delay(190)
                            runCatching { tone.release() }
                            cleanup()
                            if (cont.isActive) {
                                cont.resume(cleaned)
                            }
                        }
                    }.onFailure {
                        cleanup()
                        if (cont.isActive) {
                            cont.resume(cleaned)
                        }
                    }
                }

                runCatching {
                    audioManager.mode = android.media.AudioManager.MODE_IN_COMMUNICATION
                    audioManager.startBluetoothSco()
                    audioManager.isBluetoothScoOn = true
                }

                runCatching {
                    val tone = android.media.ToneGenerator(android.media.AudioManager.STREAM_VOICE_CALL, 90)
                    tone.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 180)
                    CoroutineScope(Dispatchers.Main).launch {
                        delay(250)
                        runCatching { tone.release() }
                    }
                }

                recognizer = SpeechRecognizer.createSpeechRecognizer(this@MainActivity)
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 500L)
                }

                recognizer?.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {
                        heardSpeech = true
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

                cont.invokeOnCancellation {
                    finish(null)
                }
            }
        }
    }

    private fun triggerCliRelayVoiceQuery(
        memoryAwareChosenProvider: Boolean = false,
        chosenProviderType: AgentProviderType? = null,
    ) {
        // Wake up screen if locked
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val keyguardManager = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
            keyguardManager.requestDismissKeyguard(this, null)
        }

        // Tell glasses to stop proprietary AI audio stream
        LargeDataHandler.getInstance().glassesControl(byteArrayOf(0x02, 0x01, 0x0b)) { _, _ -> }

        val audioManager = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager

        fun stopSco() {
            runCatching {
                audioManager.isBluetoothScoOn = false
                audioManager.stopBluetoothSco()
                audioManager.mode = android.media.AudioManager.MODE_NORMAL
            }
        }

        runCatching {
            audioManager.mode = android.media.AudioManager.MODE_IN_COMMUNICATION
            audioManager.startBluetoothSco()
            audioManager.isBluetoothScoOn = true
        }

        Toast.makeText(this, "Listening for voice query…", Toast.LENGTH_SHORT).show()

        val recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                speak("I am listening")
            }

            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}

            override fun onError(error: Int) {
                Toast.makeText(this@MainActivity, "Voice query failed: $error", Toast.LENGTH_SHORT).show()
                recognizer.destroy()
                stopSco()
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val prompt = matches?.firstOrNull()?.trim().orEmpty()

                if (prompt.isBlank()) {
                    recognizer.destroy()
                    stopSco()
                    return
                }

                Toast.makeText(this@MainActivity, "Asking: $prompt", Toast.LENGTH_SHORT).show()

                CoroutineScope(Dispatchers.IO).launch {
                    val reply = if (memoryAwareChosenProvider) {
                        runMemoryAwareChosenProviderQuery(
                            userPrompt = prompt,
                            providerType = chosenProviderType ?: AgentProviderType.PRO_SUBSCRIPTION,
                        )
                    } else {
                        val selectedProvider = AutomationPrefs.getProviderType(this@MainActivity)
                        val modelOverride = if (selectedProvider == AgentProviderType.PRO_SUBSCRIPTION) {
                            ProSubscriptionAiPrefs.getQuestionsModel(this@MainActivity)
                        } else {
                            null
                        }

                        CliRelayClient.voiceQuery(
                            context = this@MainActivity,
                            prompt = prompt,
                            modelOverride = modelOverride,
                        )
                            .getOrElse { "Relay unavailable: ${it.message ?: "unknown error"}" }
                    }

                    runOnUiThread {
                        speak(reply, utteranceId = "AI_REPLY") {
                            stopSco()
                        }
                    }
                }

                recognizer.destroy()
            }

            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        recognizer.startListening(intent)
    }

    private fun triggerAssistantVoiceQuery() {
        val effectiveMode = resolveEffectiveAiAssistantMode()
        Log.i("AIHijack", "Triggering Voice Query for $effectiveMode")

        val selectedProvider = AutomationPrefs.getProviderType(this)
        val useChosenProviderMemoryAware =
            aiAssistantMode == AI_MODE_CHOSEN_PROVIDER &&
                (selectedProvider == AgentProviderType.PRO_SUBSCRIPTION ||
                    selectedProvider == AgentProviderType.LOCAL_AGENT)
        if (useChosenProviderMemoryAware) {
            triggerCliRelayVoiceQuery(
                memoryAwareChosenProvider = true,
                chosenProviderType = selectedProvider,
            )
            return
        }

        // Spike branch feature: CLI Relay AI provider (hosted Gemini/Codex via HTTP).
        // Only route through CLI relay when NOT in Gemini/ChatGPT mode (those use native apps).
        if (effectiveMode != AI_MODE_GEMINI && effectiveMode != AI_MODE_CHATGPT) {
            val relayProvider = AiProviderPrefs.getProvider(this)
            if (relayProvider == RelayProviderType.CLI_RELAY) {
                triggerCliRelayVoiceQuery()
                return
            }
        }

        if (effectiveMode == AI_MODE_TASKER) {
            sendAiBroadcast(type = "voice", assistantMode = AI_MODE_TASKER)
            return
        }

        // Wake up screen if locked

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val keyguardManager = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
            keyguardManager.requestDismissKeyguard(this, null)
        }

        // Tell glasses to stop proprietary AI audio stream
        LargeDataHandler.getInstance().glassesControl(byteArrayOf(0x02, 0x01, 0x0b)) { _, _ -> }

        try {
            val intent = Intent(Intent.ACTION_VOICE_COMMAND).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                if (effectiveMode == AI_MODE_CHATGPT) {
                    setPackage("com.openai.chatgpt")
                }
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e("AIHijack", "Failed to trigger assistant: ${e.message}")
            runOnUiThread {
                Toast.makeText(this, "Assistant not found or failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun triggerAssistantImageQuery(imagePath: String, userQuestion: String? = null) {
        // Debounce: prevent duplicate requests within 5 seconds
        val now = System.currentTimeMillis()
        if (now - lastImageQueryAtMs < 5000) {
            Log.w("AIHijack", "Image query debounced (last was ${now - lastImageQueryAtMs}ms ago)")
            return
        }
        
        // Guard against concurrent requests
        if (!imageQueryInProgress.compareAndSet(false, true)) {
            Log.w("AIHijack", "Image query already in progress, skipping")
            return
        }
        
        lastImageQueryAtMs = now
        
        val selectedProvider = AutomationPrefs.getProviderType(this)
        val isChosenProviderMode = aiAssistantMode == AI_MODE_CHOSEN_PROVIDER

        // Route ChosenProvider with memory-aware providers
        val useChosenProviderMemoryAware =
            isChosenProviderMode &&
                (selectedProvider == AgentProviderType.PRO_SUBSCRIPTION ||
                    selectedProvider == AgentProviderType.LOCAL_AGENT)
        if (useChosenProviderMemoryAware) {
            triggerMemoryAwareImageQuery(imagePath, selectedProvider, userQuestion)
            return
        }

        val relayProvider = AiProviderPrefs.getProvider(this)
        if (relayProvider == RelayProviderType.CLI_RELAY) {
            Log.i("AIHijack", "Sending image query to CLI relay: $imagePath")

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val modelOverride = if (AutomationPrefs.getProviderType(this@MainActivity) == AgentProviderType.PRO_SUBSCRIPTION) {
                        ProSubscriptionAiPrefs.getQuestionsModel(this@MainActivity)
                    } else {
                        null
                    }

                    val result = CliRelayClient.imageQuery(
                        context = this@MainActivity,
                        imagePath = imagePath,
                        modelOverride = modelOverride,
                    )

                    runOnUiThread {
                        if (result.isFailure) {
                            val errorMsg = result.exceptionOrNull()?.message ?: "unknown error"
                            Log.e("AIHijack", "Image query failed: $errorMsg")
                            Toast.makeText(this@MainActivity, "Vision error: ${errorMsg.take(80)}", Toast.LENGTH_LONG).show()
                            speak("I couldn't analyze the image. Please try again.")
                        } else {
                            val reply = result.getOrNull() ?: ""
                            if (looksLikeVisionFailed(reply)) {
                                Toast.makeText(this@MainActivity, "Vision model couldn't process image", Toast.LENGTH_LONG).show()
                                speak("I couldn't analyze the image. Please try again.")
                            } else {
                                speak(reply)
                            }
                        }
                    }
                } finally {
                    imageQueryInProgress.set(false)
                }
            }
            return
        }

        Log.i("AIHijack", "Redirecting Image Query to Tasker logic with $imagePath")

        try {
            val keyguardManager = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
            val isLocked = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                keyguardManager.isDeviceLocked
            } else {
                keyguardManager.isKeyguardLocked
            }

            // Wake and dismiss keyguard only when needed
            if (isLocked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                setShowWhenLocked(true)
                setTurnScreenOn(true)
                keyguardManager.requestDismissKeyguard(this, null)
            }

            if (isLocked) {
                speak("Unlock your phone to answer the image query")
            }

            // Stop glasses AI mode
            LargeDataHandler.getInstance().glassesControl(byteArrayOf(0x02, 0x01, 0x0b)) { _, _ -> }

            val file = File(imagePath)
            if (!file.exists()) {
                Log.e("AIHijack", "Image file does not exist: $imagePath")
                return
            }

            // Copy file to public DCIM folder so it shows up in Gallery/Recents
            val publicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
            val cameraDir = File(publicDir, "Camera")
            if (!cameraDir.exists()) cameraDir.mkdirs()
            
            val publicFile = File(cameraDir, "Glasses_AI_${System.currentTimeMillis()}.jpg")
            file.copyTo(publicFile, overwrite = true)
            
            // Scan the file so MediaStore/Gallery sees it immediately
            MediaScannerConnection.scanFile(this, arrayOf(publicFile.absolutePath), arrayOf("image/jpeg")) { path, uri ->
                Log.i("AIHijack", "Scanned to Gallery: $path")
                // Once scanned, trigger the Tasker broadcast
                runOnUiThread {
                    sendAiBroadcast("image", path)
                }
            }
        } catch (e: Exception) {
            Log.e("AIHijack", "Failed to process image for Tasker: ${e.message}")
        } finally {
            imageQueryInProgress.set(false)
        }
    }


    private fun updateConnectionStatus(connected: Boolean) {
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
        updateDeviceClassText()
        if (!connected) {
            updateBatteryText(null)
        }
    }

    private fun updateDeviceClassText() {
        val profile = DeviceProfileStore.loadLastSelected(this)
        val classLabel = profile?.selectedClass?.displayName() ?: "Unknown"
        binding.tvDeviceClass.text = "Class: $classLabel"

        applyGlassesManagerGating(profile)
    }

    /**
     * Chapter 4: Capability gating for the Glasses Manager screen.
     *
     * - HEY_CYAN: show extra controls + battery/storage placeholders.
     * - Other classes: show meeting capture only (plus basic connection UI).
     */
    private fun applyGlassesManagerGating(profile: com.fersaiyan.cyanbridge.devices.DeviceProfile?) {
        val model = GlassesManagerGating.uiModel(profile)

        // Expanded controls panel (HeyCyan-only in MVP baseline)
        binding.layoutHeycyanExtras.visibility =
            if (model.isVisible(GlassesManagerGating.Action.HEY_CYAN_EXTRAS)) android.view.View.VISIBLE else android.view.View.GONE

        // Status placeholders
        val showBattery = model.isVisible(GlassesManagerGating.Action.STATUS_BATTERY)
        val showStorage = model.isVisible(GlassesManagerGating.Action.STATUS_STORAGE)

        binding.layoutBattery.visibility = if (showBattery) android.view.View.VISIBLE else android.view.View.GONE
        binding.layoutStorage.visibility = if (showStorage) android.view.View.VISIBLE else android.view.View.GONE
        binding.layoutStatusMetrics.visibility =
            if (showBattery || showStorage) android.view.View.VISIBLE else android.view.View.GONE

        // Only poll battery for profiles that claim to support it.
        if (showBattery) {
            startBatteryPolling()
        } else {
            stopBatteryPolling()
            updateBatteryText(null)
        }

        if (!showStorage) {
            binding.storageText.text = "--"
        }
    }

    // --- Chapter 5: Meeting capture pipeline (start/stop, timer, indicator) ---

    private fun setupMeetingCaptureUi() {
        val labels = meetingTimerOptions.map { it.second }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        binding.spinnerMeetingTimer.adapter = adapter
        syncMeetingCaptureUiFromPrefs()
    }

    private fun setupAgentControlsUi() {
        binding.btnAgentStart.setOnClickListener {
            val res = LocalAgentController.start(this)
            if (res.ok) {
                LocalAgentPrefs.setStatus(this, "Starting…")
                LocalAgentPrefs.clearLastError(this)
            } else {
                LocalAgentPrefs.setStatus(this, "Error")
                LocalAgentPrefs.setLastError(this, res.error ?: res.userMessage)
            }
            refreshAgentStatusUi()
            Toast.makeText(this, res.userMessage, Toast.LENGTH_SHORT).show()
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
    }

    private fun selectedMeetingTimerDurationSec(): Long? { 
        val idx = binding.spinnerMeetingTimer.selectedItemPosition
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

            MeetingCaptureService.start(this, timerDurationSec = durationSec, deviceClass = deviceClass)
        }
    }

    private fun stopMeetingCaptureFromUi() {
        binding.tvMeetingBanner.text = "Stopping…"
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
        } else {
            binding.meetingRecordingBanner.visibility = android.view.View.GONE
            binding.tvMeetingSource.text = "Source: (not recording)"
        }
    }

    // --- end Chapter 5 meeting capture ---

    // --- Transcription UI moved to RecordingsListActivity (per-item) ---



    private fun updateBatteryText(battery: Int?) {
        binding.batteryText.text = battery?.let { "$it%" } ?: "--%"
    }

    private fun requestBatteryStatus(showToast: Boolean) {
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

    private fun startDataDownload() {
        Log.i("DataDownload", "Starting BLE+WiFi P2P data download...")

        // Check Bluetooth connection status
        if (!BleOperateManager.getInstance().isConnected) {
            Log.e("DataDownload", "Bluetooth not connected. Please connect to glasses first.")
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
            Toast.makeText(
                this,
                "Please enable WiFi to sync with glasses.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        // Check NEARBY_WIFI_DEVICES on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !XXPermissions.isGranted(this, "android.permission.NEARBY_WIFI_DEVICES")
        ) {
            Log.e("DataDownload", "NEARBY_WIFI_DEVICES permission not granted")
            Toast.makeText(
                this,
                "NEARBY_WIFI_DEVICES permission not granted.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        // Tear down any stale session first so retries do not stack callbacks/jobs.
        teardownDownloadP2pSession(sendExitTransfer = false, hideTransferUi = false)
        downloadCancelledByUser = false

        // Reset state for a fresh run
        downloadP2pConnected = false
        downloadBleIp = null
        downloadWifiIp = null
        downloadInProgress = false
        downloadResolvedHttpIp = null
        lastDownloadBleIpAtMs = 0L
        resetDownloadSupportState()

        resetTransferUiState()
        setTransferUiVisible(true)
        setTransferDetail("Starting sync...")
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
                        sendTransferModeCommandWithRetry(attempt = 1, maxAttempts = 2, delayMs = 1500L)
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

                val shouldRecover = !downloadCancelledByUser &&
                    (downloadAttemptJob?.isActive == true || downloadInProgress)
                if (shouldRecover) {
                    Log.i("DataDownload", "P2P disconnected during sync; restarting peer discovery")
                    setTransferDetail("P2P disconnected; retrying discovery...")
                    downloadWifiP2pManager?.discoverPeersStable()
                    downloadWifiP2pManager?.startPeerDiscovery()
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
                Log.e("DataDownload", "P2P connection retry failed")
            }
        }

        downloadWifiP2pCallback = callback
        wifiP2pManager.addCallback(callback)

        // Start scanning for the glasses over WiFi Direct
        wifiP2pManager.startPeerDiscovery()

        setTransferDetail("Waiting for glasses IP and HTTP server...")

        // Ask the glasses (over BLE) to bring up WiFi/P2P and report their IP,
        // mirroring the official app's importAlbum() flow.
        sendTransferModeCommandWithRetry()
    }

    /**
     * Send the transfer-mode command [0x02,0x01,0x04] with retry on error=-1.
     * Many logs show the glasses refusing the first attempt. A reset command
     * [0x02,0x01,0x0F] is sent before each retry to clear stale state.
     */
    private fun sendTransferModeCommandWithRetry(
        attempt: Int = 1,
        maxAttempts: Int = 3,
        delayMs: Long = 2000L,
    ) {
        LargeDataHandler.getInstance().glassesControl(
            byteArrayOf(0x02, 0x01, 0x04)
        ) { _, resp ->
            Log.i(
                "DataDownload",
                "glassesControl[0x02,0x01,0x04] (attempt $attempt/$maxAttempts) -> dataType=${resp.dataType}, error=${resp.errorCode}"
            )
            if (resp.errorCode == -1 && attempt < maxAttempts) {
                Log.w("DataDownload", "Transfer mode command refused (error=-1); retrying after ${delayMs}ms")
                CoroutineScope(Dispatchers.IO).launch {
                    delay(delayMs)
                    // Reset glasses P2P state before retrying
                    LargeDataHandler.getInstance().glassesControl(
                        byteArrayOf(0x02, 0x01, 0x0F)
                    ) { _, resetResp ->
                        Log.d("DataDownload", "Pre-retry reset [0x02,0x01,0x0F] -> error=${resetResp.errorCode}")
                    }
                    delay(500)
                    sendTransferModeCommandWithRetry(attempt + 1, maxAttempts, delayMs)
                }
            }
        }
    }

    private fun setTransferUiVisible(visible: Boolean) {
        binding.cardTransferProgress.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun resetTransferUiState() {
        transferTotalJpg = 0
        transferTotalMp4 = 0
        transferTotalOpus = 0
        transferDoneJpg = 0
        transferDoneMp4 = 0
        transferDoneOpus = 0

        binding.tvTransferCounts.text = "Photos: --  Videos: --  Audio: --"
        binding.progressTransfer.isIndeterminate = true
        binding.progressTransfer.max = 100
        binding.progressTransfer.progress = 0
        binding.tvTransferDetail.text = "Idle"
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
            binding.progressTransfer.max = total
            binding.progressTransfer.progress = done.coerceAtMost(total)
        }
    }

    private fun setTransferDetail(text: String) {
        binding.tvTransferDetail.text = text
    }

    private fun startDownloadInitialPhaseWatchdog() {
        downloadInitialPhaseTimeoutJob?.cancel()
        downloadInitialPhaseTimeoutJob = CoroutineScope(Dispatchers.Main).launch {
            delay(downloadInitialPhaseTimeoutMs)
            val sessionStillStuck = !downloadInitialPhaseCompleted &&
                !downloadCancelledByUser &&
                !downloadInProgress &&
                binding.cardTransferProgress.visibility == View.VISIBLE

            if (!sessionStillStuck) return@launch

            val waitedSeconds = ((System.currentTimeMillis() - downloadStartedAtMs) / 1000L).coerceAtLeast(1L)
            Log.w("DataDownload", "Initial P2P sync phase timed out after ${waitedSeconds}s")
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
        Log.i("DataDownload", "Initial sync phase completed: $reason")
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
            title = title,
            issueType = "P2P/WiFi sync issue",
            description = reason,
            extraInfo = linkedMapOf(
                "transfer_detail" to binding.tvTransferDetail.text.toString(),
                "transfer_counts" to binding.tvTransferCounts.text.toString(),
                "download_ble_ip" to (downloadBleIp ?: ""),
                "download_wifi_ip" to (downloadWifiIp ?: ""),
                "download_http_ip" to (downloadResolvedHttpIp ?: ""),
                "download_p2p_connected" to downloadP2pConnected.toString(),
                "download_in_progress" to downloadInProgress.toString(),
                "download_phone_is_group_owner" to downloadPhoneIsGroupOwner.toString(),
            ),
            dismissButtonLabel = dismissButtonLabel,
        )
    }
    
    private fun showP2pPeerConflictDialog(
        seenPeers: List<String>,
        pairedDevice: String,
    ) {
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
                    title = "P2P sync failed",
                    issueType = "P2P/WiFi sync issue",
                    description = "P2P sync could not find glasses after $maxP2pRestarts attempts. Bluetooth is connected but glasses did not appear as a Wi‑Fi Direct peer. Seen P2P peers: ${seenPeers.joinToString(", ")}",
                    extraInfo = linkedMapOf(
                        "transfer_detail" to binding.tvTransferDetail.text.toString(),
                        "transfer_counts" to binding.tvTransferCounts.text.toString(),
                        "download_ble_ip" to (downloadBleIp ?: ""),
                        "download_wifi_ip" to (downloadWifiIp ?: ""),
                        "download_http_ip" to (downloadResolvedHttpIp ?: ""),
                        "download_p2p_connected" to downloadP2pConnected.toString(),
                        "download_in_progress" to downloadInProgress.toString(),
                        "download_phone_is_group_owner" to downloadPhoneIsGroupOwner.toString(),
                        "seen_p2p_peers" to seenPeers.joinToString(", "),
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
    
    private fun downloadMediaList(deviceIp: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Lock the device IP for the whole transfer session.
                downloadResolvedHttpIp = deviceIp
                val url = "http://$deviceIp/files/media.config"
                Log.i("DataDownload", "Downloading media list from: $url")

                withContext(Dispatchers.Main) {
                    binding.progressTransfer.isIndeterminate = true
                    setTransferDetail("Fetching media list...")
                }

                var content: String? = null
                httpGet(URL(url), 10000, 30000) { stream, _ ->
                    content = stream.bufferedReader().use { it.readText() }
                }

                if (content != null) {
                    Log.i("DataDownload", "=== MEDIA CONFIG CONTENT ===")
                    Log.i("DataDownload", content!!)
                    Log.i("DataDownload", "=== END MEDIA CONFIG ===")
                    parseMediaList(content!!, deviceIp)
                } else {
                    Log.e("DataDownload", "Failed to download media list.")
                    withContext(Dispatchers.Main) {
                        showDownloadError("Failed to download media list.")
                    }
                }
            } catch (e: Exception) {
                    Log.e("DataDownload", "Error downloading media list: ${e.message}", e)
                    CoroutineScope(Dispatchers.Main).launch {
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
        }
    
        private fun parseMediaList(content: String, deviceIp: String) {
            // Parse the media configuration file content - this is a text file containing media file names.
            Log.i("DataDownload", "Parsing media list content...")
            
            try {
                // Split by line, each line should be a file name
                val lines = content.trim().lines()
                val jpgFiles = mutableListOf<String>()
                val mp4Files = mutableListOf<String>()
                val opusFiles = mutableListOf<String>()
                var otherFiles = 0
                
                lines.forEach { line ->
                    val trimmedLine = line.trim()
                    if (trimmedLine.isNotEmpty()) {
                        when {
                            trimmedLine.endsWith(".jpg", ignoreCase = true) ||
                                trimmedLine.endsWith(".jpeg", ignoreCase = true) -> {
                                jpgFiles.add(trimmedLine)
                                Log.i("DataDownload", "Found JPG file: $trimmedLine")
                            }

                            trimmedLine.endsWith(".mp4", ignoreCase = true) -> {
                                mp4Files.add(trimmedLine)
                                Log.i("DataDownload", "Found MP4 file: $trimmedLine")
                            }

                            trimmedLine.endsWith(".opus", ignoreCase = true) -> {
                                opusFiles.add(trimmedLine)
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

                CoroutineScope(Dispatchers.Main).launch {
                    setTransferPlan(jpgFiles.size, mp4Files.size, opusFiles.size)
                    val total = jpgFiles.size + mp4Files.size + opusFiles.size
                    setTransferDetail("Preparing downloads (0/$total)...")
                }

                if (jpgFiles.isEmpty() && mp4Files.isEmpty() && opusFiles.isEmpty()) {
                    Log.w("DataDownload", "No JPG/MP4/OPUS files found in media.config")
                    CoroutineScope(Dispatchers.Main).launch {
                        showDownloadError("No JPG/MP4/OPUS files found in media.config")
                    }
                    return
                }

                // Download everything we understand. Keep P2P bound until all downloads finish.
                downloadAllMediaFiles(jpgFiles, mp4Files, opusFiles, deviceIp)
                
            } catch (e: Exception) {
                Log.e("DataDownload", "Error parsing media list: ${e.message}", e)
                CoroutineScope(Dispatchers.Main).launch {
                    showDownloadError("Failed to parse media list: ${e.message}")
                }
            }
        }

    private fun downloadAllMediaFiles(
        jpgFiles: List<String>,
        mp4Files: List<String>,
        opusFiles: List<String>,
        deviceIp: String,
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            Log.i(
                "DataDownload",
                "Starting download: jpg=${jpgFiles.size}, mp4=${mp4Files.size}, opus=${opusFiles.size}"
            )

            val totalAll = jpgFiles.size + mp4Files.size + opusFiles.size
            withContext(Dispatchers.Main) {
                if (totalAll > 0) {
                    binding.progressTransfer.isIndeterminate = false
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
                    withContext(Dispatchers.Main) {
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
                        onTransferItemDone("jpg")
                        setTransferDetail("Downloaded ${binding.progressTransfer.progress}/${binding.progressTransfer.max}")
                    }
                    
                    // Add a small delay to avoid excessively fast requests
                    delay(500)
                    
                } catch (e: Exception) {
                    jpgFail++
                    Log.e("DataDownload", "Error downloading $fileName: ${e.message}", e)

                    withContext(Dispatchers.Main) {
                        onTransferItemDone("jpg")
                        setTransferDetail("Downloaded ${binding.progressTransfer.progress}/${binding.progressTransfer.max} (with errors)")
                    }
                }
            }

            for ((index, fileName) in mp4Files.withIndex()) {
                try {
                    withContext(Dispatchers.Main) {
                        setTransferDetail("Downloading video ${index + 1}/${mp4Files.size}...")
                    }
                    Log.i("DataDownload", "Downloading video ${index + 1}/${mp4Files.size}: $fileName")

                    val success = downloadSingleMp4File(fileName, deviceIp)
                    if (success) {
                        mp4Success++
                        Log.i("DataDownload", "✓ Successfully downloaded: $fileName")
                    } else {
                        mp4Fail++
                        Log.e("DataDownload", "✗ Failed to download: $fileName")
                    }

                    withContext(Dispatchers.Main) {
                        onTransferItemDone("mp4")
                        setTransferDetail("Downloaded ${binding.progressTransfer.progress}/${binding.progressTransfer.max}")
                    }

                    // Videos are larger; be gentler.
                    delay(800)
                } catch (e: Exception) {
                    mp4Fail++
                    Log.e("DataDownload", "Error downloading $fileName: ${e.message}", e)

                    withContext(Dispatchers.Main) {
                        onTransferItemDone("mp4")
                        setTransferDetail("Downloaded ${binding.progressTransfer.progress}/${binding.progressTransfer.max} (with errors)")
                    }
                }
            }

            for ((index, fileName) in opusFiles.withIndex()) {
                try {
                    withContext(Dispatchers.Main) {
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
                        onTransferItemDone("opus")
                        setTransferDetail("Downloaded ${binding.progressTransfer.progress}/${binding.progressTransfer.max}")
                    }

                    delay(500)
                } catch (e: Exception) {
                    opusFail++
                    Log.e("DataDownload", "Error downloading $fileName: ${e.message}", e)

                    withContext(Dispatchers.Main) {
                        onTransferItemDone("opus")
                        setTransferDetail("Downloaded ${binding.progressTransfer.progress}/${binding.progressTransfer.max} (with errors)")
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
                if (totalFail == 0) {
                    showDownloadSuccess("All $totalSuccess files downloaded successfully!")
                } else {
                    showDownloadError("Download completed with errors: $totalSuccess successful, $totalFail failed")
                }
            }
        }
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

            if (saved != null && saved!!.bytes > 0) {
                Log.i("DataDownload", "File downloaded: $fileName (${saved!!.bytes} bytes)")
            }
            if (saved != null && saved!!.success) {
                Log.i("DataDownload", "Saved to gallery: name=$fileName uri=${saved!!.uri}")
                true
            } else {
                Log.e("DataDownload", "Failed to download/save: $fileName")
                false
            }
        } catch (e: Exception) {
            Log.e("DataDownload", "Error downloading $fileName: ${e.message}", e)
            false
        }
    }

    private suspend fun downloadSingleMp4File(fileName: String, deviceIp: String): Boolean {
        return try {
            val url = "http://$deviceIp/files/$fileName"
            Log.i("DataDownload", "Downloading: $url")

            var saved: GallerySaveResult? = null
            httpGet(URL(url), 15000, 180000) { stream, _ ->
                val takenMs = parseTakenTimeMillisFromFilename(fileName) ?: System.currentTimeMillis()
                saved = saveMp4ToGallery(stream, fileName, takenMs)
            }

            if (saved != null && saved!!.bytes > 0) {
                Log.i("DataDownload", "File downloaded: $fileName (${saved!!.bytes} bytes)")
            }
            if (saved != null && saved!!.success) {
                Log.i("DataDownload", "Saved to gallery: name=$fileName uri=${saved!!.uri}")
                true
            } else {
                Log.e("DataDownload", "Failed to download/save: $fileName")
                false
            }
        } catch (e: Exception) {
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

            if (saved != null && saved!!.bytes > 0) {
                Log.i("DataDownload", "File downloaded: $fileName (${saved!!.bytes} bytes)")
            }
            if (saved != null && saved!!.success) {
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
                Log.i("DataDownload", "Saved to library: name=$fileName uri=${saved!!.uri}")
                true
            } else {
                Log.e("DataDownload", "Failed to download/save: $fileName (raw=$rawBytesSize mode=$payloadNote)")
                false
            }
        } catch (e: Exception) {
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

    private fun saveMp4ToGallery(input: InputStream, displayName: String, takenTimeMs: Long): GallerySaveResult {
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
    
    private fun teardownDownloadP2pSession(sendExitTransfer: Boolean, hideTransferUi: Boolean) {
        downloadAttemptJob?.cancel()
        downloadAttemptJob = null
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

        if (sendExitTransfer) {
            // Tell the glasses to exit transfer mode (official app does this after downloads finish).
            try {
                LargeDataHandler.getInstance().glassesControl(
                    byteArrayOf(0x02, 0x01, 0x09)
                ) { _, resp ->
                    Log.i(
                        "DataDownload",
                        "glassesControl[0x02,0x01,0x09] -> dataType=${resp.dataType}, error=${resp.errorCode}"
                    )
                }
            } catch (e: Exception) {
                Log.w("DataDownload", "Failed to send exit-transfer command [0x02,0x01,0x09]", e)
            }
        }

        val manager = downloadWifiP2pManager
        val callback = downloadWifiP2pCallback
        if (manager != null && callback != null) {
            manager.removeCallback(callback)
        }

        // Mirror official app: cancel the P2P connection as part of cleanup.
        manager?.cancelP2pConnection()

        manager?.removeGroup { success ->
            Log.i("DataDownload", "P2P group removed: $success")
        }
        manager?.unregisterReceiver()
        downloadWifiP2pManager = null
        downloadWifiP2pCallback = null
        downloadP2pConnected = false
        downloadInProgress = false
        downloadP2pNetwork = null
        downloadResolvedHttpIp = null
    }

    private fun cleanupP2pAfterDownload() {
        teardownDownloadP2pSession(
            sendExitTransfer = true,
            hideTransferUi = true,
        )
    }

    private fun cancelDataDownloadAttempt(reason: String, showToast: Boolean) {
        Log.i("DataDownload", reason)
        maybeShowP2pSyncLogHelp(
            reason = "The user stopped the P2P sync before media transfer completed. Sharing logs can help diagnose why the sync needed to be cancelled.",
            title = "Sync stopped",
            dismissButtonLabel = "Close",
        )
        downloadCancelledByUser = true
        finishDownloadInitialPhase("cancelled by user")
        setTransferDetail("Stopping sync...")
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
        Log.i("DataDownload", "SUCCESS: $message")
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
    
    private fun showDownloadError(message: String, cleanup: Boolean = true) {
        if (!downloadInitialPhaseCompleted) {
            maybeShowP2pSyncLogHelp(
                reason = "CyanBridge failed during the initial P2P sync steps before any media transfer progress was shown. Error: $message",
            )
        }
        finishDownloadInitialPhase("error: $message")
        if (cleanup) {
            cleanupP2pAfterDownload()
        }
        Log.e("DataDownload", "ERROR: $message")
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun isProbablyGroupOwnerIp(ip: String?): Boolean {
        if (ip.isNullOrBlank()) return false

        // If the phone is not the group owner, then we shouldn't block the group owner IP (.1)
        // because it belongs to the glasses.
        if (!downloadPhoneIsGroupOwner) return false

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

        if (!downloadPhoneIsGroupOwner && downloadWifiIp != null) {
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
                if (downloadPhoneIsGroupOwner && ip == "192.168.49.1") continue
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
        downloadBleIp = ip

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
        downloadP2pConnected = info.groupFormed
        downloadWifiIp = info.groupOwnerAddress?.hostAddress
        downloadPhoneIsGroupOwner = info.isGroupOwner
        downloadP2pNetwork = findLikelyP2pNetwork()
        bindProcessToNetwork(downloadP2pNetwork)
        Log.i(
            "DataDownload",
            "onDownloadP2pConnected: p2pConnected=$downloadP2pConnected, isGroupOwner=${info.isGroupOwner}, groupOwnerIp=$downloadWifiIp"
        )
        maybeStartHttpDownload("P2P")
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

        val hasDeviceIp = !downloadBleIp.isNullOrBlank() || !bleIpBridge.ip.value.isNullOrBlank()
        if (!hasDeviceIp) {
            setTransferDetail("Waiting for BLE-reported glasses IP...")
            Log.i("DataDownload", "Ignoring HTTP start trigger from $source; waiting for device IP notify")
            return
        }

        val bridgeIp = bleIpBridge.ip.value
        Log.i(
            "DataDownload",
            "HTTP start trigger from $source. p2p=$downloadP2pConnected, bleIp=$downloadBleIp, groupOwnerIp=$downloadWifiIp, bleBridgeIp=$bridgeIp"
        )

        downloadAttemptJob = CoroutineScope(Dispatchers.IO).launch {
            // Official app waits briefly after both P2P+BLE-IP signals before fetching media.config.
            delay(1000)

            val startMs = System.currentTimeMillis()
            val overallTimeoutMs = 45_000L
            var lastStatusLogMs = 0L
            var didSubnetScan = false

            while (isActive && System.currentTimeMillis() - startMs < overallTimeoutMs) {
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
                    if (!isActive) return@launch
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
                        downloadMediaList(candidate)
                        return@launch
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
                            downloadMediaList(found)
                            return@launch
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

            var p2pCandidate: Network? = null
            var fallbackWifi: Network? = null

            for (n in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(n) ?: continue
                if (!caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) continue
                if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)) continue

                val lp = cm.getLinkProperties(n)
                val ifName = lp?.interfaceName ?: ""
                val addrs = lp?.linkAddresses?.mapNotNull { it.address.hostAddress } ?: emptyList()

                val matchesHint = prefixHints.any { p -> addrs.any { it.startsWith(p) } }
                val looksLikeP2p = ifName.contains("p2p", ignoreCase = true) ||
                    ifName.contains("wfd", ignoreCase = true) ||
                    addrs.any { it.startsWith("192.168.49.") } ||
                    matchesHint

                if (looksLikeP2p) {
                    Log.i("DataDownload", "Selected P2P/WFD network candidate: if=$ifName addrs=$addrs (matchesHint=$matchesHint)")
                    p2pCandidate = n
                    // Strong match -> return early.
                    if (ifName.contains("p2p", ignoreCase = true) || ifName.contains("wfd", ignoreCase = true) || matchesHint) {
                        return n
                    }
                }

                // Keep a Wi‑Fi fallback so VPN doesn't steal routing if we fail to detect P2P.
                if (fallbackWifi == null) {
                    Log.i("DataDownload", "Keeping Wi‑Fi fallback network: if=$ifName addrs=$addrs")
                    fallbackWifi = n
                }
            }

            p2pCandidate ?: fallbackWifi
        } catch (e: Exception) {
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

        // Only attempt P2P resets when we're actually in (or attempting) a download session.
        // Otherwise these resets can interfere with normal camera/recording usage.
        val sessionActive = downloadInProgress || downloadAttemptJob?.isActive == true || downloadP2pConnected
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

    inner class MyDeviceNotifyListener : GlassesDeviceNotifyListener() {

        @RequiresApi(Build.VERSION_CODES.O)
        override fun parseData(cmdType: Int, response: GlassesDeviceNotifyRsp) {
            Log.i(
                "DeviceNotify",
                "cmdType=$cmdType, loadData=${response.loadData.joinToString(separator = ",") { it.toInt().toString() }}"
            )
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
                    Log.i("DeviceNotify", "AI Photo Button Pressed")
                    if (isAiHijackEnabled) {
                        runOnUiThread {
                            val unsupportedReason = imageQueryUnsupportedReasonForCurrentSelection()
                            if (unsupportedReason != null) {
                                Toast.makeText(this@MainActivity, unsupportedReason, Toast.LENGTH_SHORT).show()
                                speak(unsupportedReason)
                                return@runOnUiThread
                            }
                            if (maybeShowGeminiChatGptImageRequirementsWarning()) {
                                return@runOnUiThread
                            }
                            handleGlassesImageButtonPressed(
                                triggerCapture = false,
                                sourceTag = "glasses_signal",
                            )
                        }
                    }
                }

                //Glasses activate microphone / AI button
                0x03 -> {
                    if (response.loadData[7].toInt() == 1) {
                        Log.i("DeviceNotify", "AI Button Pressed - Hijacking to Phone Assistant")
                        if (isAiHijackEnabled) {
                            triggerAssistantVoiceQuery()
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

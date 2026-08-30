package com.fersaiyan.cyanbridge.ui

import android.Manifest
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.activity.compose.setContent
import androidx.core.content.ContextCompat
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.fersaiyan.cyanbridge.MainActivity
import com.fersaiyan.cyanbridge.agent.LocalAgentPrefs as AutomationPrefs
import com.fersaiyan.cyanbridge.agent.LocalModelsConfigureActivity
import com.fersaiyan.cyanbridge.agent.ProSubscriptionAiPrefs
import com.fersaiyan.cyanbridge.agent.ProSubscriptionRelayClient
import com.fersaiyan.cyanbridge.ai.router.AiProviderPrefs
import com.fersaiyan.cyanbridge.ai.router.AiProviderType
import com.fersaiyan.cyanbridge.ai.router.AiAssistantRouter as RelayAiAssistantRouter
import com.fersaiyan.cyanbridge.shared.chat.ChatRole
import com.fersaiyan.cyanbridge.chat.ChatStore
import com.fersaiyan.cyanbridge.chat.ChatStoreRepository
import com.fersaiyan.cyanbridge.localagent.LocalAgentPrefs
import com.fersaiyan.cyanbridge.localagent.context.LocalAgentContextBuilder
import com.fersaiyan.cyanbridge.localagent.dailyfacts.DailyFactsReviewProtocol
import com.fersaiyan.cyanbridge.localagent.dailyfacts.DailyFactsReviewProtocol.ReviewBatchItem
import com.fersaiyan.cyanbridge.localagent.dailyfacts.DailyFactsStorage
import com.fersaiyan.cyanbridge.localagent.dailyfacts.DailyFactsReviewThreadStore
import com.fersaiyan.cyanbridge.localagent.dailyfacts.OcrDailyFactsSeeder
import com.fersaiyan.cyanbridge.localagent.daily.NightlyEnrichmentPrefs
import com.fersaiyan.cyanbridge.localagent.userfacts.CandidateUserFactsStorage
import com.fersaiyan.cyanbridge.localagent.userfacts.UserFactsStorage
import com.fersaiyan.cyanbridge.localagent.userfacts.ChatMemoryAutoUpdater
import com.fersaiyan.cyanbridge.localagent.dailysummary.DailySummaryRegenerateWorker
import com.fersaiyan.cyanbridge.localagent.memory.LocalAgentMemorySearch
import com.fersaiyan.cyanbridge.localagent.memory.RagProfile
import com.fersaiyan.cyanbridge.localagent.memory.LocalAgentMemoryStore
import com.fersaiyan.cyanbridge.localmodels.provider.LocalModelRequestPriority
import com.fersaiyan.cyanbridge.localmodels.provider.LocalModelsProvider
import com.fersaiyan.cyanbridge.localmodels.remote.RemoteOpenAiPrefs
import com.fersaiyan.cyanbridge.localmodels.settings.LocalGenerationSettings
import com.fersaiyan.cyanbridge.localmodels.settings.LocalModelRuntime
import com.fersaiyan.cyanbridge.localmodels.settings.LocalModelSettingsRepository
import com.fersaiyan.cyanbridge.localmodels.storage.LocalModelStorageRepository
import com.fersaiyan.cyanbridge.memoryvault.MemoryModeManager
import com.fersaiyan.cyanbridge.shared.chat.ChatAppearanceMenuAction
import com.fersaiyan.cyanbridge.shared.chat.ChatAttachmentsUiState
import com.fersaiyan.cyanbridge.shared.chat.ChatComposerPrimaryAction
import com.fersaiyan.cyanbridge.shared.chat.ChatComposerUiState
import com.fersaiyan.cyanbridge.shared.chat.DailySummaryProgressUiState
import com.fersaiyan.cyanbridge.shared.chat.ChatThreadEvent
import com.fersaiyan.cyanbridge.ui.chat.ChatAppearancePrefs
import com.fersaiyan.cyanbridge.shared.ui.chat.ChatAppearanceMenuDialog
import com.fersaiyan.cyanbridge.shared.ui.chat.ChatThreadScreen
import com.fersaiyan.cyanbridge.shared.chat.ChatThreadStateReducer
import com.fersaiyan.cyanbridge.shared.chat.ChatThreadUiState
import com.fersaiyan.cyanbridge.shared.navigation.AppDestination
import com.fersaiyan.cyanbridge.shared.settings.AgentProviderType
import com.fersaiyan.cyanbridge.ui.appearance.AppearancePreferences
import com.fersaiyan.cyanbridge.ui.appearance.rememberAppearanceSettings
import com.fersaiyan.cyanbridge.ui.debug.DebugLogSupport
import com.fersaiyan.cyanbridge.ui.theme.CyanBridgeTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.LinkedHashMap
import java.util.LinkedHashSet

class ChatThreadActivity : AppCompatActivity() {

    private val chatRepository = ChatStoreRepository()
    private val chatThreadMutex = Mutex()
    private val chatMessageWriteMutex = Mutex()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var chatThreadUiState by mutableStateOf(ChatThreadUiState())
    private var composerUiState by mutableStateOf(ChatComposerUiState())
    private var attachmentsUiState by mutableStateOf(ChatAttachmentsUiState())
    private var modelBadge by mutableStateOf<String?>(null)
    private var dailySummaryProgress by mutableStateOf<DailySummaryProgressUiState?>(null)
    private var dailyReviewQueueStatus by mutableStateOf<String?>(null)
    private var chatWallpaper by mutableStateOf<ImageBitmap?>(null)
    private var userBubbleColor by mutableStateOf<Int?>(null)
    private var assistantBubbleColor by mutableStateOf<Int?>(null)
    private var isThinking by mutableStateOf(false)
    private var chatId by mutableStateOf<String?>(null)
    private var chatAppearanceMenuVisible by mutableStateOf(false)
    private var refreshRequestId = 0L

    private var isDailyFactsReview: Boolean = false
    private var dailyFactsDate: String? = null
    private var dailyFactsLookbackDays: Int = 1
    private var dailyFactsSeededFromOcr: Boolean = false
    private var pendingAssistantRequests: Int = 0
    private var localGenerationRunning by mutableStateOf(false)
    private var localTitleGenerationInProgress: Boolean = false
    private val queuedLocalPrompts = java.util.ArrayDeque<QueuedLocalPrompt>()
    private val pendingImagePaths = mutableListOf<String>()
    private var pendingAudioPath: String? = null
    private var audioRecorder: AudioRecord? = null
    private var audioRecordingThread: Thread? = null
    private var mediaRecordingFilePath: String? = null
    @Volatile
    private var isMediaRecording = false
    private var recordStopRunnable: Runnable? = null

    private data class QueuedLocalPrompt(
        val chatId: String,
        val userPrompt: String,
        val imagePaths: List<String> = emptyList(),
        val audioPath: String? = null,
    )

    private val pickChatImageLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        runCatching {
            val copied = copyUriToChatAttachment(uri, "image")
            pendingImagePaths += copied.absolutePath
            updatePendingAttachmentsUi()
        }.onFailure {
            android.widget.Toast.makeText(this, "Failed to attach image", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private val requestAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            startAudioRecording()
        } else {
            android.widget.Toast.makeText(this, "Microphone permission is required to record audio", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private val pickWallpaperLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            ChatAppearancePrefs.setWallpaperUri(this, uri.toString())
            applyChatAppearance()
            android.widget.Toast.makeText(this, "Chat wallpaper updated", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureThreadFromIntent(intent)

        val appearancePreferences = AppearancePreferences(this)
        setContent {
            val appearance by rememberAppearanceSettings(appearancePreferences)
            CyanBridgeTheme(appearance) {
                val visibleMessages = ChatThreadStateReducer.visibleMessages(
                    messages = chatThreadUiState.messages,
                    chatId = chatId.orEmpty(),
                    streamingAssistantText = chatThreadUiState.streamingAssistantText,
                    nowMs = System.currentTimeMillis(),
                )
                ChatThreadScreen(
                    state = chatThreadUiState,
                    messages = visibleMessages,
                    composer = composerUiState,
                    attachments = attachmentsUiState,
                    modelBadge = modelBadge,
                    dailySummaryProgress = dailySummaryProgress,
                    dailyReviewQueueStatus = dailyReviewQueueStatus,
                    userBubbleColor = userBubbleColor,
                    assistantBubbleColor = assistantBubbleColor,
                    wallpaper = chatWallpaper,
                    isThinking = isThinking,
                    onOpenChatList = ::openChatList,
                    onChatAppearance = ::showChatAppearanceMenu,
                    onComposerTextChanged = ::updateComposerText,
                    onPrimaryAction = ::handlePrimaryComposerAction,
                    onAttachImage = ::attachImage,
                    onRecordAudio = ::toggleAudioRecording,
                    onClearAttachments = ::clearPendingAttachments,
                    onDestinationSelected = ::navigateTo,
                )
                if (chatAppearanceMenuVisible) {
                    ChatAppearanceMenuDialog(
                        modelOptionLabel = chatAppearanceModelOptionLabel(),
                        onDismissRequest = { chatAppearanceMenuVisible = false },
                        onAction = ::handleChatAppearanceMenuAction,
                    )
                }
            }
        }

        refreshModelBadge("Ready")
        updateComposerForGenerationState()
        applyChatAppearance()
        observeDailySummaryQueueProgress()
        refreshMessages()
        refreshDailyReviewQueueStatusAsync()
        updatePendingAttachmentsUi()

        // Auto-kickoff daily facts review.
        val cid = chatId
        if (isDailyFactsReview && cid != null && ChatStore.listMessages(cid).isEmpty()) {
            kickoffDailyFactsReview()
        }
    }

    override fun onResume() {
        super.onResume()
        applyChatAppearance()
        refreshModelBadge("Ready")
        updateComposerForGenerationState()
        refreshMessages()
        refreshDailyReviewQueueStatusAsync()
    }

    override fun onDestroy() {
        recordStopRunnable?.let(mainHandler::removeCallbacks)
        if (isMediaRecording) {
            stopAudioRecording(saveAsAttachment = true)
        }
        if (localGenerationRunning) {
            lifecycleScope.launch {
                RelayAiAssistantRouter.cancelCurrentGeneration(this@ChatThreadActivity)
            }
        }
        super.onDestroy()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        configureThreadFromIntent(intent)
        refreshMessages()
        refreshDailyReviewQueueStatusAsync()
        applyChatAppearance()
        refreshModelBadge("Ready")
        updateComposerForGenerationState()
    }

    private fun configureThreadFromIntent(source: Intent) {
        isDailyFactsReview = source.getBooleanExtra(EXTRA_DAILY_FACTS_REVIEW, false)
        dailyFactsDate = source.getStringExtra(EXTRA_DAILY_FACTS_DATE)
        val configuredRetention = MemoryModeManager.getScreenOcrRetentionDays(this)
        dailyFactsLookbackDays = source
            .getIntExtra(EXTRA_DAILY_FACTS_LOOKBACK_DAYS, configuredRetention)
            .coerceIn(1, configuredRetention.coerceAtLeast(1))

        chatId = source.getStringExtra(EXTRA_CHAT_ID)
        if (chatId == null) {
            source.getStringExtra(EXTRA_CREATE_THREAD_TITLE)
                ?.takeIf { it.isNotBlank() }
                ?.let { title -> chatId = ChatStore.createThread(title = title).id }
        }

        restoreDailyReviewConfigForCurrentThread()
        registerDailyReviewThreadIfNeeded()

        if (chatId?.let(ChatStore::getThread) == null) {
            chatId = null
        }
        updateChatThreadState(ChatThreadEvent.Loaded(thread = null, messages = emptyList()))
        dailyReviewQueueStatus = null
        source.getStringExtra(EXTRA_PREFILL_MESSAGE)
            ?.takeIf { it.isNotBlank() }
            ?.let(::updateComposerText)
    }

    private fun openChatList() {
        startActivity(
            Intent(this, ChatListActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
            ),
        )
        finish()
    }

    private fun updateComposerText(value: String) {
        updateChatThreadState(ChatThreadEvent.ComposerTextChanged(value))
    }

    private fun handlePrimaryComposerAction() {
        if (localGenerationRunning) {
            lifecycleScope.launch {
                RelayAiAssistantRouter.cancelCurrentGeneration(this@ChatThreadActivity)
            }
            return
        }

        val hasMedia = pendingImagePaths.isNotEmpty() || !pendingAudioPath.isNullOrBlank()
        if (isLocalModelsProviderSelected() && !hasLocalModelAvailable() && !hasMedia) {
            promptLocalModelSetup()
            return
        }

        val text = chatThreadUiState.composerText.trim()
        if (isMediaRecording) {
            stopAudioRecording(saveAsAttachment = true)
        }
        if (text.isNotEmpty() || hasMedia) {
            if (isLocalModelsProviderSelected() && localTitleGenerationInProgress && !isDailyFactsReview) {
                enqueueLocalPrompt(text)
            } else {
                sendMessage(text)
            }
            updateComposerText("")
        }
    }

    private fun attachImage() {
        pickChatImageLauncher.launch(arrayOf("image/*"))
    }

    private fun toggleAudioRecording() {
        if (isMediaRecording) {
            stopAudioRecording(saveAsAttachment = true)
        } else {
            ensureAudioPermissionAndStartRecording()
        }
    }

    private fun restoreDailyReviewConfigForCurrentThread() {
        val cid = chatId ?: return
        if (!isDailyFactsReview) {
            val stored = DailyFactsReviewThreadStore.load(this, cid)
            if (stored != null) {
                isDailyFactsReview = true
                dailyFactsDate = stored.date
                dailyFactsLookbackDays = stored.lookbackDays.coerceAtLeast(1)
                return
            }
        }

        if (isDailyFactsReview && dailyFactsDate.isNullOrBlank()) {
            val title = ChatStore.getThread(cid)?.title.orEmpty()
            val m = Regex("Daily review \\((\\d{4}-\\d{2}-\\d{2})\\)").find(title)
            val parsedDate = m?.groupValues?.getOrNull(1)
            if (!parsedDate.isNullOrBlank()) {
                dailyFactsDate = parsedDate
            }
        }
    }

    private fun registerDailyReviewThreadIfNeeded() {
        if (!isDailyFactsReview) return
        val cid = chatId ?: return
        val date = dailyFactsDate?.trim().orEmpty()
        if (date.isBlank()) return
        DailyFactsReviewThreadStore.save(
            context = this,
            chatId = cid,
            date = date,
            lookbackDays = dailyFactsLookbackDays,
        )
    }

    private fun applyChatAppearance() {
        val userColor = ChatAppearancePrefs.getUserBubbleColor(this)
        val assistantColor = ChatAppearancePrefs.getAssistantBubbleColor(this)
        userBubbleColor = userColor
        assistantBubbleColor = assistantColor

        val wallpaper = ChatAppearancePrefs.getWallpaperUri(this)
        if (wallpaper.isBlank()) {
            chatWallpaper = null
            return
        }

        val uri = android.net.Uri.parse(wallpaper)
        chatWallpaper = runCatching {
            contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input)
            }
        }.getOrNull()?.asImageBitmap()
    }

    private fun showChatAppearanceMenu() {
        chatAppearanceMenuVisible = true
    }

    private fun chatAppearanceModelOptionLabel(): String = when {
        isRemoteOpenAiBackendActive() -> "Configure remote model"
        isLocalModelsProviderSelected() -> "Change local model"
        isRelayProviderSelected() -> "Change relay AI model"
        else -> "Change AI model"
    }

    private fun handleChatAppearanceMenuAction(action: ChatAppearanceMenuAction) {
        chatAppearanceMenuVisible = false
        when (action) {
            ChatAppearanceMenuAction.CHANGE_USER_BUBBLE_COLOR -> showColorPicker(
                title = "User bubble color",
                current = ChatAppearancePrefs.getUserBubbleColor(this),
            ) { selected ->
                ChatAppearancePrefs.setUserBubbleColor(this, selected)
                applyChatAppearance()
            }

            ChatAppearanceMenuAction.CHANGE_ASSISTANT_BUBBLE_COLOR -> showColorPicker(
                title = "Assistant bubble color",
                current = ChatAppearancePrefs.getAssistantBubbleColor(this),
            ) { selected ->
                ChatAppearancePrefs.setAssistantBubbleColor(this, selected)
                applyChatAppearance()
            }

            ChatAppearanceMenuAction.CHOOSE_WALLPAPER -> {
                pickWallpaperLauncher.launch(arrayOf("image/*"))
            }

            ChatAppearanceMenuAction.REMOVE_WALLPAPER -> {
                ChatAppearancePrefs.clearWallpaper(this)
                applyChatAppearance()
                android.widget.Toast.makeText(this, "Wallpaper removed", android.widget.Toast.LENGTH_SHORT).show()
            }

            ChatAppearanceMenuAction.RESET_APPEARANCE -> {
                ChatAppearancePrefs.reset(this)
                applyChatAppearance()
                android.widget.Toast.makeText(this, "Chat appearance reset", android.widget.Toast.LENGTH_SHORT).show()
            }

            ChatAppearanceMenuAction.CHANGE_MODEL -> showModelPickerForProvider()
        }
    }

    private fun showModelPickerForProvider() {
        when {
            isRemoteOpenAiBackendActive() -> startActivity(Intent(this, LocalModelsConfigureActivity::class.java))
            isLocalModelsProviderSelected() -> showLocalModelPicker()
            isRelayProviderSelected() -> showRelayModelPicker()
            else -> android.widget.Toast.makeText(this, "Current provider does not support model selection", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun showLocalModelPicker() {
        val installed = LocalModelStorageRepository.listInstalled(this)
        if (installed.isEmpty()) {
            promptLocalModelSetup()
            return
        }

        val selectedId = LocalModelStorageRepository.getSelectedModelId(this)
        val labels = installed.map { model ->
            if (model.id == selectedId) {
                "${model.displayName} (Current)"
            } else {
                model.displayName
            }
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Select local model")
            .setItems(labels) { _, which ->
                val picked = installed.getOrNull(which) ?: return@setItems
                LocalModelStorageRepository.setSelectedModelId(this, picked.id)
                refreshModelBadge("Ready")
                updateComposerForGenerationState()
                android.widget.Toast.makeText(this, "Using ${picked.displayName}", android.widget.Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showRelayModelPicker() {
        val current = ProSubscriptionAiPrefs.getRequestsModel(this)
        lifecycleScope.launch {
            val options = withContext(Dispatchers.IO) {
                val fetched = ProSubscriptionRelayClient.fetchAvailableModels(this@ChatThreadActivity).getOrElse { emptyList() }
                val merged = linkedMapOf<String, String>()
                merged["auto"] = "auto"
                fetched.forEach { option ->
                    val id = option.id.trim()
                    if (id.isBlank()) return@forEach
                    val label = option.label.trim().ifBlank { id }
                    if (!merged.containsKey(id)) {
                        merged[id] = label
                    }
                }
                if (current.isNotBlank() && !merged.containsKey(current)) {
                    merged[current] = current
                }
                merged.entries.toList()
            }

            if (options.isEmpty()) {
                android.widget.Toast.makeText(
                    this@ChatThreadActivity,
                    "No relay models available",
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
                return@launch
            }

            val labels = options.map { option ->
                val id = option.key
                val label = option.value
                if (id.equals(current, ignoreCase = true)) "$label (Current)" else label
            }.toTypedArray()

            AlertDialog.Builder(this@ChatThreadActivity)
                .setTitle("Select relay model")
                .setItems(labels) { _, which ->
                    val picked = options.getOrNull(which) ?: return@setItems
                    val pickedId = picked.key
                    val pickedLabel = picked.value
                    ProSubscriptionAiPrefs.setRequestsModel(this@ChatThreadActivity, pickedId)
                    refreshModelBadge("Ready")
                    android.widget.Toast.makeText(
                        this@ChatThreadActivity,
                        "Chat model set to $pickedLabel",
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun showColorPicker(title: String, current: Int, onPick: (Int) -> Unit) {
        val options = listOf(
            "Cyan" to 0xFF00E5FF.toInt(),
            "Ocean" to 0xFF1F8AFA.toInt(),
            "Forest" to 0xFF2E7D32.toInt(),
            "Amber" to 0xFFFFB300.toInt(),
            "Coral" to 0xFFFF6F61.toInt(),
            "Slate" to 0xFF455A64.toInt(),
            "Rose" to 0xFFE91E63.toInt(),
            "Purple" to 0xFF7E57C2.toInt(),
        )

        val labels = options.map { (name, color) ->
            if (color == current) "$name (Current)" else name
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(labels) { _, which ->
                onPick(options[which].second)
            }
            .show()
    }

    private fun isLocalModelsProviderSelected(): Boolean {
        return when (AutomationPrefs.getProviderType(this)) {
            AgentProviderType.LOCAL_AGENT -> true
            AgentProviderType.PRO_SUBSCRIPTION -> false
            AgentProviderType.TASKER -> AiProviderPrefs.getProvider(this) == AiProviderType.LOCAL_MODELS
        }
    }

    private fun isRemoteOpenAiBackendActive(): Boolean {
        return isLocalModelsProviderSelected() && RemoteOpenAiPrefs.isActive(this)
    }

    private fun isRelayProviderSelected(): Boolean {
        return when (AutomationPrefs.getProviderType(this)) {
            AgentProviderType.PRO_SUBSCRIPTION -> true
            AgentProviderType.LOCAL_AGENT -> false
            AgentProviderType.TASKER -> AiProviderPrefs.getProvider(this) == AiProviderType.CLI_RELAY
        }
    }

    private fun refreshModelBadge(status: String) {
        modelBadge = when {
            isRemoteOpenAiBackendActive() -> {
                "Remote model: ${RemoteOpenAiPrefs.getModel(this)} ($status)"
            }

            isLocalModelsProviderSelected() -> {
                val selected = LocalModelStorageRepository.resolveSelectedModel(this)
                if (selected == null) {
                    "Local model: none installed"
                } else {
                    "Local model: ${selected.displayName} ($status)"
                }
            }

            isRelayProviderSelected() -> {
                val selected = ProSubscriptionAiPrefs.getRequestsModel(this).ifBlank { "auto" }
                "Relay model: $selected"
            }

            else -> null
        }
    }

    private fun observeDailySummaryQueueProgress() {
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .format(java.util.Date(System.currentTimeMillis()))
        WorkManager.getInstance(this)
            .getWorkInfosForUniqueWorkLiveData(DailySummaryRegenerateWorker.uniqueWorkName(today))
            .observe(this) { infos ->
                val info = infos.firstOrNull()
                renderDailySummaryQueueProgress(info)
            }
    }

    private fun renderDailySummaryQueueProgress(info: WorkInfo?) {
        if (info == null || (info.state != WorkInfo.State.ENQUEUED && info.state != WorkInfo.State.RUNNING)) {
            dailySummaryProgress = null
            return
        }

        val bulletTotal = info.progress.getInt(DailySummaryRegenerateWorker.KEY_BULLET_TOTAL, 0)
        if (bulletTotal <= 0) {
            dailySummaryProgress = null
            return
        }

        val bulletDone = info.progress.getInt(DailySummaryRegenerateWorker.KEY_BULLET_DONE, 0)
            .coerceIn(0, bulletTotal)
        val stage = info.progress.getString(DailySummaryRegenerateWorker.KEY_STAGE)
            ?.trim()
            .orEmpty()
            .ifBlank { "Queued" }
        val etaMs = info.progress.getLong(DailySummaryRegenerateWorker.KEY_ETA_MS, 0L)
            .coerceAtLeast(0L)
        val etaSeconds = (etaMs / 1000L).coerceAtLeast(0L)
        val etaText = if (etaSeconds >= 60L) {
            val m = etaSeconds / 60L
            val s = etaSeconds % 60L
            "~${m}m ${s}s"
        } else {
            "~${etaSeconds}s"
        }

        val percent = ((bulletDone.toDouble() / bulletTotal.toDouble()) * 100.0)
            .toInt()
            .coerceIn(0, 100)

        dailySummaryProgress = DailySummaryProgressUiState(
            label = "Daily summary bullets $bulletDone/$bulletTotal · $stage · ETA $etaText",
            progress = percent / 100f,
        )
    }

    private fun showRelayDownToastIfNeeded(message: String?) {
        val hint = ProSubscriptionRelayClient.relayUnavailableHintFromText(message.orEmpty()) ?: return
        android.widget.Toast.makeText(this, hint, android.widget.Toast.LENGTH_LONG).show()
    }

    private fun hasLocalModelAvailable(): Boolean {
        if (!isLocalModelsProviderSelected()) return true
        if (isRemoteOpenAiBackendActive()) return true
        return LocalModelStorageRepository.resolveSelectedModel(this) != null
    }

    private fun updateComposerForGenerationState() {
        if (localGenerationRunning) {
            composerUiState = ChatComposerUiState(
                isTextInputEnabled = false,
                isMediaEnabled = false,
                primaryAction = ChatComposerPrimaryAction.STOP_GENERATION,
            )
        } else {
            val localModelMissing = isLocalModelsProviderSelected() && !hasLocalModelAvailable()
            if (localModelMissing) {
                composerUiState = ChatComposerUiState(
                    hint = "Install/select a local model to start chatting",
                    isTextInputEnabled = true,
                    isMediaEnabled = false,
                    primaryAction = ChatComposerPrimaryAction.CONFIGURE_LOCAL_MODEL,
                )
            } else {
                composerUiState = ChatComposerUiState(
                    isTextInputEnabled = true,
                    // Keep media controls actionable so unsupported providers can explain
                    // their runtime requirement instead of showing inert controls.
                    isMediaEnabled = true,
                    primaryAction = ChatComposerPrimaryAction.SEND,
                )
            }
        }
    }

    private fun promptLocalModelSetup() {
        AlertDialog.Builder(this)
            .setTitle("Local model required")
            .setMessage("Local Models is selected, but no local model is installed. Configure one to start chatting.")
            .setNegativeButton("Not now", null)
            .setPositiveButton("Configure") { _, _ ->
                startActivity(Intent(this, LocalModelsConfigureActivity::class.java))
            }
            .show()
    }

    private fun formatUserMessageWithMedia(
        text: String,
        imagePaths: List<String>,
        audioPath: String?,
    ): String {
        val suffix = buildString {
            if (imagePaths.isNotEmpty()) {
                append("\n[Attached ${imagePaths.size} image(s)]")
            }
            if (!audioPath.isNullOrBlank()) {
                append("\n[Attached voice note]")
            }
        }
        return (text + suffix).trim()
    }

    private fun clearPendingAttachments() {
        pendingImagePaths.clear()
        pendingAudioPath = null
        updatePendingAttachmentsUi()
    }

    private fun updatePendingAttachmentsUi() {
        val imageCount = pendingImagePaths.size
        val hasAudio = !pendingAudioPath.isNullOrBlank()
        val label = if (imageCount == 0 && !hasAudio && !isMediaRecording) {
            null
        } else {
            buildString {
                if (imageCount > 0) append("$imageCount image(s)")
                if (hasAudio) {
                    if (isNotBlank()) append(" · ")
                    append("voice note")
                }
                if (isMediaRecording) {
                    if (isNotBlank()) append(" · ")
                    append("recording voice note")
                }
                append(" attached")
            }
        }
        attachmentsUiState = ChatAttachmentsUiState(
            label = label,
            isRecording = isMediaRecording,
        )
    }

    private fun ensureAudioPermissionAndStartRecording() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) {
            startAudioRecording()
        } else {
            requestAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startAudioRecording() {
        if (isMediaRecording) return
        if (
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            ensureAudioPermissionAndStartRecording()
            return
        }
        stopAudioRecording(saveAsAttachment = false)

        val outFile = File(chatAttachmentDir(), "voice_${System.currentTimeMillis()}.wav")
        mediaRecordingFilePath = outFile.absolutePath

        try {
            val minBuffer = AudioRecord.getMinBufferSize(
                AUDIO_SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBuffer <= 0) {
                throw IllegalStateException("AudioRecord buffer init failed: $minBuffer")
            }

            val recorder = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                AUDIO_SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuffer.coerceAtLeast(AUDIO_SAMPLE_RATE_HZ),
            )
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                runCatching { recorder.release() }
                throw IllegalStateException("AudioRecord not initialized")
            }

            audioRecorder = recorder
            isMediaRecording = true
            updatePendingAttachmentsUi()
            android.widget.Toast.makeText(this, "Recording... up to 30 seconds", android.widget.Toast.LENGTH_SHORT).show()

            val worker = Thread {
                var pcmBytesWritten = 0L
                val ioBuffer = ByteArray(minBuffer.coerceAtLeast(2048))
                runCatching {
                    FileOutputStream(outFile).use { output ->
                        output.write(ByteArray(WAV_HEADER_BYTES))
                        recorder.startRecording()
                        while (isMediaRecording) {
                            val read = recorder.read(ioBuffer, 0, ioBuffer.size)
                            if (read > 0) {
                                output.write(ioBuffer, 0, read)
                                pcmBytesWritten += read.toLong()
                            } else if (read == AudioRecord.ERROR_BAD_VALUE || read == AudioRecord.ERROR_INVALID_OPERATION) {
                                throw IllegalStateException("Audio read failed: $read")
                            }
                        }
                    }
                }.onFailure {
                    runCatching { outFile.delete() }
                }

                runCatching { recorder.stop() }
                runCatching { recorder.release() }

                if (outFile.exists() && pcmBytesWritten > 0L) {
                    runCatching {
                        writeWavHeader(
                            file = outFile,
                            pcmDataBytes = pcmBytesWritten,
                            sampleRateHz = AUDIO_SAMPLE_RATE_HZ,
                            channelCount = 1,
                            bitsPerSample = 16,
                        )
                    }.onFailure {
                        runCatching { outFile.delete() }
                    }
                }
            }.apply {
                name = "liteRt-audio-recorder"
                isDaemon = true
            }
            audioRecordingThread = worker
            worker.start()

            recordStopRunnable?.let(mainHandler::removeCallbacks)
            recordStopRunnable = Runnable {
                stopAudioRecording(saveAsAttachment = true)
            }.also { mainHandler.postDelayed(it, 30_000L) }
        } catch (t: Throwable) {
            runCatching { audioRecorder?.release() }
            audioRecorder = null
            audioRecordingThread = null
            isMediaRecording = false
            mediaRecordingFilePath = null
            updatePendingAttachmentsUi()
            android.widget.Toast.makeText(this, "Unable to start recording", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopAudioRecording(saveAsAttachment: Boolean) {
        recordStopRunnable?.let(mainHandler::removeCallbacks)
        recordStopRunnable = null

        val recorder = audioRecorder
        audioRecorder = null
        val worker = audioRecordingThread
        audioRecordingThread = null
        val path = mediaRecordingFilePath
        mediaRecordingFilePath = null

        isMediaRecording = false

        if (worker != null && worker.isAlive) {
            runCatching { worker.join(1200L) }
        }
        if (recorder != null && recorder.state == AudioRecord.STATE_INITIALIZED) {
            runCatching { recorder.release() }
        }

        val audioFile = path?.let { File(it) }
        if (saveAsAttachment && !path.isNullOrBlank()) {
            if (audioFile?.exists() == true && audioFile.length() > WAV_HEADER_BYTES) {
                pendingAudioPath = path
                android.widget.Toast.makeText(this, "Voice note attached", android.widget.Toast.LENGTH_SHORT).show()
            } else {
                runCatching { audioFile?.delete() }
                android.widget.Toast.makeText(this, "Unable to attach voice note", android.widget.Toast.LENGTH_SHORT).show()
            }
        } else if (!path.isNullOrBlank()) {
            runCatching { audioFile?.delete() }
        }
        updatePendingAttachmentsUi()
    }

    private fun writeWavHeader(
        file: File,
        pcmDataBytes: Long,
        sampleRateHz: Int,
        channelCount: Int,
        bitsPerSample: Int,
    ) {
        val safeDataBytes = pcmDataBytes.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        val riffChunkSize = (36L + safeDataBytes).coerceIn(36L, Int.MAX_VALUE.toLong()).toInt()
        val byteRate = sampleRateHz * channelCount * bitsPerSample / 8
        val blockAlign = channelCount * bitsPerSample / 8

        val header = ByteBuffer.allocate(WAV_HEADER_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply {
                put("RIFF".toByteArray(Charsets.US_ASCII))
                putInt(riffChunkSize)
                put("WAVE".toByteArray(Charsets.US_ASCII))
                put("fmt ".toByteArray(Charsets.US_ASCII))
                putInt(16)
                putShort(1)
                putShort(channelCount.toShort())
                putInt(sampleRateHz)
                putInt(byteRate)
                putShort(blockAlign.toShort())
                putShort(bitsPerSample.toShort())
                put("data".toByteArray(Charsets.US_ASCII))
                putInt(safeDataBytes)
            }

        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(0)
            raf.write(header.array())
        }
    }

    private fun copyUriToChatAttachment(uri: Uri, kind: String): File {
        if (kind == "image") {
            val decodedBitmap = runCatching {
                contentResolver.openInputStream(uri)?.use { input ->
                    BitmapFactory.decodeStream(input)
                }
            }.getOrNull()

            if (decodedBitmap != null) {
                val out = File(chatAttachmentDir(), "image_${System.currentTimeMillis()}.jpg")
                FileOutputStream(out).use { output ->
                    decodedBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, output)
                }
                decodedBitmap.recycle()
                return out
            }
        }

        val ext = resolveAttachmentExtension(uri, fallback = if (kind == "image") "jpg" else "bin")
        val out = File(chatAttachmentDir(), "${kind}_${System.currentTimeMillis()}.$ext")
        contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(out).use { output ->
                input.copyTo(output)
            }
        } ?: throw IllegalStateException("Cannot open attachment")
        return out
    }

    private fun resolveAttachmentExtension(uri: Uri, fallback: String): String {
        val mimeExt = contentResolver.getType(uri)
            ?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
            ?.lowercase(java.util.Locale.US)
            .orEmpty()
        val pathExt = uri.lastPathSegment
            ?.substringAfterLast('.', "")
            ?.lowercase(java.util.Locale.US)
            .orEmpty()
        val chosen = when {
            mimeExt.isNotBlank() -> mimeExt
            pathExt.isNotBlank() -> pathExt
            else -> fallback
        }
        val cleaned = chosen.replace(Regex("[^a-z0-9]"), "")
        return cleaned.ifBlank { fallback }.take(8)
    }

    private fun chatAttachmentDir(): File {
        val dir = File(cacheDir, "chat_attachments")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun sendMessage(text: String) {
        val images = pendingImagePaths.toList()
        val audio = pendingAudioPath
        val promptText = text.ifBlank {
            if (images.isNotEmpty() || !audio.isNullOrBlank()) {
                "Please analyze the attached media."
            } else {
                ""
            }
        }
        if (promptText.isBlank()) return

        clearPendingAttachments()
        lifecycleScope.launch {
            val (cid, shouldGenerateSmartTitle) = chatMessageWriteMutex.withLock {
                val cid = ensureChatThread()
                val shouldGenerateSmartTitle = chatRepository.listMessages(cid).isEmpty()
                chatRepository.addMessage(
                    chatId = cid,
                    role = ChatRole.USER,
                    content = formatUserMessageWithMedia(promptText, images, audio),
                    nowMs = System.currentTimeMillis(),
                )
                cid to shouldGenerateSmartTitle
            }
            refreshMessages()

            startAssistantGeneration(
                chatId = cid,
                userPrompt = promptText,
                imagePaths = images,
                audioPath = audio,
                requestSmartTitleAfterReply = shouldGenerateSmartTitle,
            )
        }
    }

    private fun enqueueLocalPrompt(text: String) {
        val images = pendingImagePaths.toList()
        val audio = pendingAudioPath
        val promptText = text.ifBlank {
            if (images.isNotEmpty() || !audio.isNullOrBlank()) {
                "Please analyze the attached media."
            } else {
                ""
            }
        }
        if (promptText.isBlank()) return

        clearPendingAttachments()
        lifecycleScope.launch {
            val cid = chatMessageWriteMutex.withLock {
                val cid = ensureChatThread()
                chatRepository.addMessage(
                    chatId = cid,
                    role = ChatRole.USER,
                    content = formatUserMessageWithMedia(promptText, images, audio),
                    nowMs = System.currentTimeMillis(),
                )
                cid
            }
            refreshMessages()
            queuedLocalPrompts.addLast(
                QueuedLocalPrompt(
                    chatId = cid,
                    userPrompt = promptText,
                    imagePaths = images,
                    audioPath = audio,
                ),
            )
            refreshModelBadge("Queued")
            android.widget.Toast.makeText(
                this@ChatThreadActivity,
                "Queued. Sending right after title update...",
                android.widget.Toast.LENGTH_SHORT,
            ).show()
        }
    }

    private suspend fun ensureChatThread(): String = chatThreadMutex.withLock {
        chatId?.let { return@withLock it }

        val created = chatRepository.createThread(title = null, nowMs = System.currentTimeMillis())
        chatId = created.id
        registerDailyReviewThreadIfNeeded()
        updateChatThreadState(ChatThreadEvent.ThreadChanged(created))
        created.id
    }

    private fun startAssistantGeneration(
        chatId: String,
        userPrompt: String,
        imagePaths: List<String> = emptyList(),
        audioPath: String? = null,
        requestSmartTitleAfterReply: Boolean,
        onFinished: (() -> Unit)? = null,
    ) {
        lifecycleScope.launch {
            onAssistantRequestStarted()
            val useLocalStreaming = isLocalModelsProviderSelected()
            var maxTokensReached = false
            if (useLocalStreaming) {
                localGenerationRunning = true
                updateChatThreadState(ChatThreadEvent.GenerationStarted())
                updateComposerForGenerationState()
                refreshModelBadge("Loading")
                refreshMessages()
            }
            try {
                val assistantReply = withContext(Dispatchers.IO) {
                    val messages = buildRelayMessages(chatId = chatId, lastUserPrompt = userPrompt)
                    RelayAiAssistantRouter.chatReplyStreaming(
                        context = this@ChatThreadActivity,
                        chatId = chatId,
                        userPrompt = userPrompt,
                        messages = messages,
                        imagePaths = imagePaths,
                        audioPath = audioPath,
                        callbacks = if (useLocalStreaming) {
                            object : RelayAiAssistantRouter.ChatStreamCallbacks {
                                override fun onStatus(status: String) {
                                    runOnUiThread {
                                        if (status == LocalModelsProvider.STATUS_MAX_TOKENS_REACHED) {
                                            maxTokensReached = true
                                            android.widget.Toast.makeText(
                                                this@ChatThreadActivity,
                                                "Reply capped at your max output tokens setting.",
                                                android.widget.Toast.LENGTH_SHORT,
                                            ).show()
                                            return@runOnUiThread
                                        }
                                        refreshModelBadge(status)
                                    }
                                }

                                override fun onToken(token: String) {
                                    runOnUiThread {
                                        val current = chatThreadUiState.streamingAssistantText.orEmpty()
                                        updateChatThreadState(ChatThreadEvent.StreamUpdated(current + token))
                                    }
                                }
                            }
                        } else {
                            null
                        },
                    )
                }
                showRelayDownToastIfNeeded(assistantReply)

                if (isDailyFactsReview) {
                    handleDailyFactsAiReply(chatId, userPrompt, assistantReply)
                } else {
                    val streamed = chatThreadUiState.streamingAssistantText.orEmpty().trim()
                    val finalReply = when {
                        useLocalStreaming && streamed.isNotBlank() -> streamed
                        assistantReply.isNotBlank() -> assistantReply
                        streamed.isNotBlank() -> streamed
                        useLocalStreaming -> "I couldn't generate a reply yet. Loading the model and retrying can take a moment; please try again."
                        else -> "I could not generate a reply."
                    }
                    val finalReplyWithCapNotice = if (maxTokensReached && finalReply.isNotBlank()) {
                        finalReply.trimEnd() + "\n\n[Stopped at max output tokens configured in Local Models.]"
                    } else {
                        finalReply
                    }
                    chatRepository.addMessage(
                        chatId = chatId,
                        role = ChatRole.ASSISTANT,
                        content = finalReplyWithCapNotice,
                        nowMs = System.currentTimeMillis(),
                    )
                    updateChatThreadState(ChatThreadEvent.GenerationFinished())

                    if (requestSmartTitleAfterReply) {
                        maybeGenerateSmartThreadTitle(chatId = chatId, firstUserPrompt = userPrompt)
                    }

                    // Proactive memory: extract candidate facts from normal chats.
                    lifecycleScope.launch {
                        withContext(Dispatchers.IO) {
                            ChatMemoryAutoUpdater.extractAndStore(
                                context = this@ChatThreadActivity,
                                userMessage = userPrompt,
                                assistantReply = finalReplyWithCapNotice,
                            )
                        }

                        // Silent background memory updates for normal chats to avoid noisy toasts.
                    }
                }

                refreshMessages()
            } catch (_: kotlinx.coroutines.CancellationException) {
                val partial = chatThreadUiState.streamingAssistantText.orEmpty().trim()
                if (partial.isNotBlank()) {
                    ChatStore.addMessage(chatId, ChatRole.ASSISTANT, partial)
                    updateChatThreadState(ChatThreadEvent.GenerationFinished())
                    refreshMessages()
                }
                android.widget.Toast.makeText(
                    this@ChatThreadActivity,
                    "Generation stopped",
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
            } catch (t: Throwable) {
                Log.e("ChatThreadActivity", "Assistant generation failed", t)
                if (useLocalStreaming && DebugLogSupport.isLocalRuntimeIssue(t.message, t)) {
                    DebugLogSupport.showSupportOptionsDialog(
                        activity = this@ChatThreadActivity,
                        title = "Local runtime issue",
                        issueType = "Local runtime issue",
                        description = "CyanBridge hit a local model runtime error while generating this reply.",
                        extraInfo = linkedMapOf(
                            "screen" to "chat_thread",
                            "image_attachments" to imagePaths.size.toString(),
                            "has_audio_attachment" to (!audioPath.isNullOrBlank()).toString(),
                        ),
                    )
                }
                showRelayDownToastIfNeeded(t.message)
                android.widget.Toast.makeText(
                    this@ChatThreadActivity,
                    t.message ?: "Failed to get response",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            } finally {
                localGenerationRunning = false
                updateChatThreadState(ChatThreadEvent.GenerationFinished())
                updateComposerForGenerationState()
                refreshModelBadge("Ready")
                refreshMessages()
                onAssistantRequestFinished()
                onFinished?.invoke()
            }
        }
    }

    private fun kickoffDailyFactsReview() {
        val cid = chatId ?: return
        val date = dailyFactsDate ?: java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .format(java.util.Date(System.currentTimeMillis()))

        lifecycleScope.launch {
            onAssistantRequestStarted()
            val useLocalStreaming = isLocalModelsProviderSelected()
            if (useLocalStreaming) {
                localGenerationRunning = true
                updateChatThreadState(ChatThreadEvent.GenerationStarted())
                updateComposerForGenerationState()
                refreshModelBadge("Loading")
                refreshMessages()
            }
            try {
                val assistantReply = withContext(Dispatchers.IO) {
                    ensureDailyFactsDraftHydratedFromOcrIfNeeded(date)
                    val state = DailyFactsStorage.load(this@ChatThreadActivity, date)
                    LocalAgentMemoryStore.ensureSeedFiles(this@ChatThreadActivity)
                    val userFactsMd = LocalAgentMemoryStore.readText(LocalAgentMemoryStore.userFactsFile(this@ChatThreadActivity))
                    val candidateUserFacts = pruneKnownUserFactCandidates(
                        date = date,
                        candidateUserFacts = CandidateUserFactsStorage.load(this@ChatThreadActivity, date),
                        persistPrune = true,
                    )
                    val batch = buildDailyFactsReviewBatch(state, candidateUserFacts)
                    val system = DailyFactsReviewProtocol.buildSystemMessage(
                        state = state,
                        userFactsMd = userFactsMd,
                        candidateUserFacts = candidateUserFacts,
                        dailyFactsBatch = batch.daily,
                        userFactsBatch = batch.user,
                        outputMode = dailyReviewOutputMode(),
                    )

                    val msgs = listOf(
                        mapOf("role" to "System", "content" to system),
                        mapOf(
                            "role" to "User",
                            "content" to "Start the daily facts review from the current batch. Ask me about up to 3 items.",
                        ),
                    )

                    RelayAiAssistantRouter.chatReplyStreaming(
                        context = this@ChatThreadActivity,
                        chatId = cid,
                        userPrompt = "Start daily facts review",
                        messages = msgs,
                        imagePaths = emptyList(),
                        audioPath = null,
                        callbacks = if (useLocalStreaming) {
                            object : RelayAiAssistantRouter.ChatStreamCallbacks {
                                override fun onStatus(status: String) {
                                    runOnUiThread {
                                        refreshModelBadge(status)
                                    }
                                }

                                override fun onToken(token: String) {
                                    runOnUiThread {
                                        val current = chatThreadUiState.streamingAssistantText.orEmpty()
                                        updateChatThreadState(ChatThreadEvent.StreamUpdated(current + token))
                                    }
                                }
                            }
                        } else {
                            null
                        },
                    )
                }
                showRelayDownToastIfNeeded(assistantReply)

                handleDailyFactsAiReply(
                    chatId = cid,
                    userPrompt = "Start daily facts review",
                    raw = assistantReply,
                )
                refreshMessages()
            } catch (t: Throwable) {
                showRelayDownToastIfNeeded(t.message)
                android.widget.Toast.makeText(
                    this@ChatThreadActivity,
                    t.message ?: "Failed to start daily facts review",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            } finally {
                localGenerationRunning = false
                updateChatThreadState(ChatThreadEvent.GenerationFinished())
                updateComposerForGenerationState()
                refreshModelBadge("Ready")
                refreshMessages()
                onAssistantRequestFinished()
            }
        }
    }

    private fun onAssistantRequestStarted() {
        pendingAssistantRequests += 1
        isThinking = pendingAssistantRequests > 0
    }

    private fun onAssistantRequestFinished() {
        pendingAssistantRequests = (pendingAssistantRequests - 1).coerceAtLeast(0)
        isThinking = pendingAssistantRequests > 0
    }

    private fun maybeGenerateSmartThreadTitle(chatId: String, firstUserPrompt: String) {
        val usesLocalModels = isLocalModelsProviderSelected()
        if (usesLocalModels) {
            localTitleGenerationInProgress = true
            refreshModelBadge("Choosing title...")
        }
        onAssistantRequestStarted()

        lifecycleScope.launch {
            try {
                val suggested = withContext(Dispatchers.IO) {
                    runCatching {
                        val namingPrompt = buildString {
                            appendLine("Generate a concise chat title from the user request.")
                            appendLine("Rules:")
                            appendLine("- Return only the title text")
                            appendLine("- 2 to 6 words")
                            appendLine("- No quotation marks")
                            appendLine("- No punctuation at the end")
                            appendLine()
                            appendLine("User request:")
                            appendLine(firstUserPrompt.trim())
                        }

                        RelayAiAssistantRouter.textReply(
                            context = this@ChatThreadActivity,
                            prompt = namingPrompt,
                        )
                    }.getOrNull()
                }

                val title = sanitizeThreadTitle(
                    candidate = suggested,
                    fallbackPrompt = firstUserPrompt,
                )

                if (chatRepository.updateThreadTitle(chatId, title, System.currentTimeMillis())) {
                    refreshMessages()
                }
            } finally {
                if (usesLocalModels) {
                    localTitleGenerationInProgress = false
                    refreshModelBadge("Ready")
                    drainQueuedLocalPrompts()
                }
                onAssistantRequestFinished()
            }
        }
    }

    private fun drainQueuedLocalPrompts() {
        if (localGenerationRunning || localTitleGenerationInProgress) return
        val next = queuedLocalPrompts.pollFirst() ?: return
        startAssistantGeneration(
            chatId = next.chatId,
            userPrompt = next.userPrompt,
            imagePaths = next.imagePaths,
            audioPath = next.audioPath,
            requestSmartTitleAfterReply = false,
            onFinished = { drainQueuedLocalPrompts() },
        )
    }

    private fun sanitizeThreadTitle(candidate: String?, fallbackPrompt: String): String {
        val raw = candidate
            ?.lineSequence()
            ?.map { it.trim() }
            ?.firstOrNull { it.isNotBlank() }
            .orEmpty()
            .removePrefix("\"")
            .removeSuffix("\"")
            .removePrefix("'")
            .removeSuffix("'")
            .removePrefix("Title:")
            .removePrefix("title:")
            .trim()

        val normalized = raw
            .replace(Regex("\\s+"), " ")
            .replace(Regex("[\\r\\n\\t]"), " ")
            .trim()

        val looksLikeDemo = normalized.startsWith("Demo mode reply:", ignoreCase = true)
        val clean = if (normalized.isBlank() || looksLikeDemo) {
            fallbackPrompt
        } else {
            normalized
        }

        val withoutTrailingPunctuation = clean.trim().trimEnd('.', '!', '?', ':', ';', ',')
        val words = withoutTrailingPunctuation
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .take(6)

        val compact = words.joinToString(" ").trim().ifBlank { "New chat" }
        return compact.take(48)
    }

    private suspend fun buildRelayMessages(chatId: String, lastUserPrompt: String): List<Map<String, String>> {
        if (!isDailyFactsReview) {
            val selectedLocalModel = if (isLocalModelsProviderSelected()) {
                LocalModelStorageRepository.resolveSelectedModel(this)
            } else {
                null
            }
            val localContextSize = if (selectedLocalModel != null) {
                LocalModelSettingsRepository.getForModel(this, selectedLocalModel.id).contextSize
            } else {
                4096
            }
            val contextBudgets = computeContextBudgets(
                isDailyReview = false,
                selectedModel = selectedLocalModel,
                contextSize = localContextSize,
            )

            val historyRaw = ChatStore.listMessages(chatId).map { m ->
                mapOf(
                    "role" to if (m.role == ChatRole.USER) "User" else "Assistant",
                    "content" to m.content,
                )
            }

            val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                .format(java.util.Date(System.currentTimeMillis()))

            val relevantMemory = LocalAgentMemorySearch.buildRelevantMemoryBlock(
                context = this,
                queryText = lastUserPrompt,
                date = today,
                ragProfile = RagProfile.FULL,
            )

            val extra = if (relevantMemory.isBlank()) {
                emptyList()
            } else {
                listOf(
                    LocalAgentContextBuilder.Section(
                        title = "Relevant memory (search hits)",
                        content = relevantMemory,
                    )
                )
            }

            val contextBuilder = buildLocalAgentContextBuilder(contextBudgets.systemChars)
            val ctx = contextBuilder.buildSystemMessageWithDebug(
                context = this,
                date = today,
                extraSections = extra,
            )

            LocalAgentPrefs.setLastContextInjectionDebug(
                context = this,
                debugText = ctx.debug.toMultilineString(),
            )

            val system = ctx.systemMessage
            val trimmedHistory = trimHistoryForContext(
                history = historyRaw,
                maxApproxTokens = contextBudgets.historyTokens,
            )
            if (system.isBlank()) return trimmedHistory

            return listOf(mapOf("role" to "System", "content" to system)) + trimmedHistory
        }

        val date = dailyFactsDate ?: java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .format(java.util.Date(System.currentTimeMillis()))

        ensureDailyFactsDraftHydratedFromOcrIfNeeded(date)
        val state = DailyFactsStorage.load(this, date)
        LocalAgentMemoryStore.ensureSeedFiles(this)
        val userFactsMd = LocalAgentMemoryStore.readText(LocalAgentMemoryStore.userFactsFile(this))
        val candidateUserFacts = CandidateUserFactsStorage.load(this, date)
        val batch = buildDailyFactsReviewBatch(state, candidateUserFacts)
        val system = DailyFactsReviewProtocol.buildSystemMessage(
            state = state,
            userFactsMd = userFactsMd,
            candidateUserFacts = candidateUserFacts,
            dailyFactsBatch = batch.daily,
            userFactsBatch = batch.user,
            outputMode = dailyReviewOutputMode(),
        )

        val historyRaw = ChatStore.listMessages(chatId)
            .filterNot { message ->
                message.role == ChatRole.ASSISTANT &&
                    message.content.startsWith(DAILY_REVIEW_DEBUG_PREFIX)
            }
            .map { m ->
                mapOf(
                    "role" to if (m.role == ChatRole.USER) "User" else "Assistant",
                    "content" to m.content,
                )
            }

        val history = trimHistoryForContext(
            history = historyRaw,
            maxApproxTokens = computeContextBudgets(
                isDailyReview = true,
                selectedModel = if (isLocalModelsProviderSelected()) LocalModelStorageRepository.resolveSelectedModel(this) else null,
                contextSize = if (isLocalModelsProviderSelected()) {
                    LocalModelStorageRepository.resolveSelectedModel(this)
                        ?.let { LocalModelSettingsRepository.getForModel(this, it.id).contextSize }
                        ?: 2048
                } else {
                    4096
                },
            ).historyTokens,
        )

        return listOf(mapOf("role" to "System", "content" to system)) + history
    }

    private suspend fun ensureDailyFactsDraftHydratedFromOcrIfNeeded(date: String) {
        if (!isDailyFactsReview || dailyFactsSeededFromOcr) return
        dailyFactsSeededFromOcr = true
        // READY batches already contain processed bullets. Do not contaminate them with raw OCR.
        if (NightlyEnrichmentPrefs.isReady(this, date)) return

        val retentionDays = MemoryModeManager.getScreenOcrRetentionDays(this)
        val lookback = dailyFactsLookbackDays.coerceIn(1, retentionDays.coerceAtLeast(1))
        runCatching {
            OcrDailyFactsSeeder.seedDraftFactsFromScreenCaptures(
                context = this,
                targetDate = date,
                lookbackDays = lookback,
            )
        }
    }

    private data class ReviewBatch(
        val daily: List<ReviewBatchItem>,
        val user: List<ReviewBatchItem>,
    )

    private fun buildDailyFactsReviewBatch(
        state: DailyFactsStorage.State,
        candidateUserFacts: List<String>,
    ): ReviewBatch {
        val maxBatch = 3

        val confirmedNorm = state.confirmed
            .map { normalizeFactForMatching(it) }
            .filter { it.isNotBlank() }
            .toSet()
        val filteredDraft = state.draft.filterNot {
            confirmedNorm.contains(normalizeFactForMatching(it))
        }

        val effectiveUserCandidates = pruneKnownUserFactCandidates(
            date = state.date,
            candidateUserFacts = candidateUserFacts,
            persistPrune = true,
        )

        val dailyTake = filteredDraft.take(maxBatch)
        val userTake = if (dailyTake.isEmpty()) {
            effectiveUserCandidates.take(maxBatch)
        } else {
            emptyList()
        }

        val dailyItems = dailyTake.mapIndexed { idx, text ->
            ReviewBatchItem(id = "D${idx + 1}", text = text)
        }
        val userItems = userTake.mapIndexed { idx, text ->
            ReviewBatchItem(id = "U${idx + 1}", text = text)
        }

        return ReviewBatch(
            daily = dailyItems,
            user = userItems,
        )
    }

    private fun pruneKnownUserFactCandidates(
        date: String,
        candidateUserFacts: List<String>,
        persistPrune: Boolean,
    ): List<String> {
        if (candidateUserFacts.isEmpty()) return emptyList()

        val known = UserFactsStorage.normalizedFacts(this)
        if (known.isEmpty()) return candidateUserFacts

        val kept = ArrayList<String>(candidateUserFacts.size)
        val removed = ArrayList<String>()
        candidateUserFacts.forEach { fact ->
            val norm = normalizeFactForMatching(fact)
            if (norm.isBlank() || !known.contains(norm)) {
                kept += fact
            } else {
                removed += fact
            }
        }

        if (persistPrune && removed.isNotEmpty()) {
            CandidateUserFactsStorage.remove(this, date, removed)
        }

        return kept
    }

    private data class ContextBudgets(
        val systemChars: Int,
        val historyTokens: Int,
    )

    private fun computeContextBudgets(
        isDailyReview: Boolean,
        selectedModel: com.fersaiyan.cyanbridge.localmodels.storage.InstalledLocalModel?,
        contextSize: Int,
    ): ContextBudgets {
        val normalizedCtx = contextSize.coerceIn(
            LocalGenerationSettings.MIN_CONTEXT_SIZE,
            LocalGenerationSettings.MAX_CONTEXT_SIZE,
        )
        if (!isLocalModelsProviderSelected()) {
            return if (isDailyReview) {
                ContextBudgets(systemChars = 3000, historyTokens = 2200)
            } else {
                ContextBudgets(systemChars = 6000, historyTokens = 5000)
            }
        }

        val modelName = selectedModel?.displayName.orEmpty().lowercase()
        val smallModel = modelName.contains("1b") || (selectedModel?.sizeBytes ?: Long.MAX_VALUE) < 2_500_000_000L

        val systemFraction = when {
            isDailyReview -> 0.32
            smallModel -> 0.16
            else -> 0.30
        }
        val historyFraction = when {
            isDailyReview -> 0.30
            smallModel -> 0.34
            else -> 0.52
        }

        val systemTokens = (normalizedCtx * systemFraction).toInt().coerceAtLeast(220)
        val historyTokens = (normalizedCtx * historyFraction).toInt().coerceAtLeast(260)
        val systemChars = (systemTokens * 4).coerceIn(900, 7000)

        return ContextBudgets(systemChars = systemChars, historyTokens = historyTokens)
    }

    private fun dailyReviewOutputMode(): DailyFactsReviewProtocol.OutputMode {
        return DailyFactsReviewProtocol.OutputMode.JSON
    }

    private fun buildLocalAgentContextBuilder(maxTotalChars: Int): LocalAgentContextBuilder {
        val ratio = (maxTotalChars.toDouble() / 14_000.0).coerceIn(0.12, 1.0)
        fun scaled(base: Int, min: Int): Int = (base * ratio).toInt().coerceAtLeast(min)

        return LocalAgentContextBuilder(
            maxAgentPersonaChars = scaled(base = 3_000, min = 280),
            maxUserFactsChars = scaled(base = 3_000, min = 320),
            maxConfirmedDailyFactsChars = scaled(base = 4_000, min = 420),
            maxDailySummaryChars = scaled(base = 5_000, min = 520),
            maxTotalChars = maxTotalChars,
        )
    }

    private fun trimHistoryForContext(
        history: List<Map<String, String>>,
        maxApproxTokens: Int,
    ): List<Map<String, String>> {
        if (history.isEmpty()) return history
        var budget = maxApproxTokens.coerceAtLeast(120)
        val kept = ArrayList<Map<String, String>>()

        for (i in history.indices.reversed()) {
            val msg = history[i]
            val content = msg["content"].orEmpty()
            val role = msg["role"].orEmpty()
            val approxTokens = ((content.length + role.length) / 4).coerceAtLeast(10)
            if (kept.isNotEmpty() && budget - approxTokens < 0) break
            kept.add(msg)
            budget -= approxTokens
        }

        return kept.asReversed()
    }

    private suspend fun handleDailyFactsAiReply(
        chatId: String,
        userPrompt: String,
        raw: String,
    ) {
        appendDailyReviewDebugPayload(chatId, stage = "raw_model_output", payload = raw)

        val date = dailyFactsDate ?: java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .format(java.util.Date(System.currentTimeMillis()))

        val preState = DailyFactsStorage.load(this, date)
        val preCandidates = pruneKnownUserFactCandidates(
            date = date,
            candidateUserFacts = CandidateUserFactsStorage.load(this, date),
            persistPrune = true,
        )
        val currentBatch = buildDailyFactsReviewBatch(preState, preCandidates)

        if (!shouldApplyStructuredDailyReviewUpdate(userPrompt)) {
            appendDailyReviewDebugPayload(
                chatId,
                stage = "update_suppressed",
                payload = "User prompt interpreted as navigation/non-confirmation. Queue update skipped.",
            )
            val followUp = buildQueueContinuationPrompt(preState, preCandidates)
            val assistantText = if (followUp.isNotBlank()) {
                "Got it — here is the current next batch.\n\n$followUp"
            } else {
                "You're all caught up. There are no pending daily facts to review right now."
            }
            ChatStore.addMessage(chatId, ChatRole.ASSISTANT, assistantText)
            refreshDailyReviewQueueStatusAsync()
            return
        }

        val parsedFromRaw = runCatching { DailyFactsReviewProtocol.parseUpdate(raw) }.getOrNull()
            ?: attemptRepairDailyFactsUpdate(date = date, raw = raw)

        if (parsedFromRaw != null) {
            appendDailyReviewDebugPayload(
                chatId,
                stage = "parsed_update",
                payload = renderAiUpdateAsJson(parsedFromRaw),
            )
            applyDailyFactsUpdate(
                chatId = chatId,
                date = date,
                update = parsedFromRaw,
                assistantTextOverride = parsedFromRaw.assistantMessage,
                batch = currentBatch,
            )
            return
        }

        val parsedFromUserHeuristic = if (isLocalModelsProviderSelected()) {
            inferUpdateFromUserReplyHeuristic(
                userReply = userPrompt,
                batch = currentBatch,
            )
        } else {
            null
        }
        if (parsedFromUserHeuristic != null) {
            appendDailyReviewDebugPayload(
                chatId,
                stage = "heuristic_update",
                payload = renderAiUpdateAsJson(parsedFromUserHeuristic),
            )
            applyDailyFactsUpdate(
                chatId = chatId,
                date = date,
                update = parsedFromUserHeuristic,
                assistantTextOverride = extractUserFacingAssistantText(raw),
                batch = currentBatch,
            )
            return
        }

        val parsedFromUserReply = if (isLocalModelsProviderSelected()) {
            attemptInferDailyFactsUpdateFromUserReply(
                date = date,
                userReply = userPrompt,
            )
        } else {
            null
        }

        if (parsedFromUserReply != null) {
            appendDailyReviewDebugPayload(
                chatId,
                stage = "inferred_update",
                payload = renderAiUpdateAsJson(parsedFromUserReply),
            )
            applyDailyFactsUpdate(
                chatId = chatId,
                date = date,
                update = parsedFromUserReply,
                assistantTextOverride = extractUserFacingAssistantText(raw),
                batch = currentBatch,
            )
            return
        }

        if (extractUserFacingAssistantText(raw).isNotBlank()) {
            ChatStore.addMessage(chatId, ChatRole.ASSISTANT, extractUserFacingAssistantText(raw))
            refreshDailyReviewQueueStatusAsync()
            return
        }

        runCatching {
            val dailyBatch = currentBatch.daily
            val userBatch = currentBatch.user
            val brief = buildString {
                append("I couldn't parse the structured update, so your queue is unchanged. ")
                append("Please confirm/reject by ID (example: D1 yes, D2 no, U1 yes). ")
                if (dailyBatch.isNotEmpty()) {
                    append("Current daily batch: ")
                    dailyBatch.forEachIndexed { idx, item ->
                        if (idx > 0) append(" | ")
                        append("${item.id}) ${item.text.take(90)}")
                    }
                }
                if (userBatch.isNotEmpty()) {
                    append(". User-fact candidates: ")
                    userBatch.forEachIndexed { idx, item ->
                        if (idx > 0) append(" | ")
                        append("${item.id}) ${item.text.take(90)}")
                    }
                }
            }
            ChatStore.addMessage(chatId, ChatRole.ASSISTANT, brief)
        }.onFailure {
            ChatStore.addMessage(
                chatId,
                ChatRole.ASSISTANT,
                "I couldn't parse that review update safely, so I kept your queue unchanged. Please answer once more.",
            )
        }
        refreshDailyReviewQueueStatusAsync()
    }

    private fun applyDailyFactsUpdate(
        chatId: String,
        date: String,
        update: DailyFactsReviewProtocol.AiUpdate,
        assistantTextOverride: String? = null,
        batch: ReviewBatch,
    ) {
        val confirmedDaily = resolveFactsAgainstBatch(update.confirmedFacts, batch.daily)
        val rejectedDaily = resolveFactsAgainstBatch(update.rejectedFacts, batch.daily)
        val confirmedUser = resolveFactsAgainstBatch(update.confirmedUserFacts, batch.user)
        val rejectedUser = resolveFactsAgainstBatch(update.rejectedUserFacts, batch.user)

        val filteredNewDailyFacts = update.newFacts
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .take(8)

        val filteredNewUserFactCandidates = update.newUserFactsCandidates
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .take(8)

        if (filteredNewDailyFacts.isNotEmpty()) {
            val cur = DailyFactsStorage.load(this, date)
            DailyFactsStorage.writeDraft(this, date, cur.draft + filteredNewDailyFacts)
        }

        val toRemove = confirmedDaily + rejectedDaily
        if (toRemove.isNotEmpty()) {
            DailyFactsStorage.removeFromDraft(this, date, toRemove)
        }

        if (confirmedDaily.isNotEmpty()) {
            DailyFactsStorage.appendConfirmed(this, date, confirmedDaily)
        }

        if (filteredNewUserFactCandidates.isNotEmpty()) {
            CandidateUserFactsStorage.append(this, date, filteredNewUserFactCandidates)
        }

        val resolvedUserFacts = confirmedUser + rejectedUser
        if (resolvedUserFacts.isNotEmpty()) {
            CandidateUserFactsStorage.remove(this, date, resolvedUserFacts)
        }

        if (confirmedUser.isNotEmpty()) {
            UserFactsStorage.appendUniqueFacts(this, confirmedUser)
        }

        val postState = DailyFactsStorage.load(this, date)
        val postCandidates = pruneKnownUserFactCandidates(
            date = date,
            candidateUserFacts = CandidateUserFactsStorage.load(this, date),
            persistPrune = true,
        )
        val followUp = buildQueueContinuationPrompt(postState, postCandidates)

        refreshDailyReviewQueueStatusAsync()
        val baseAssistantText = assistantTextOverride
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: update.assistantMessage.trim().ifBlank {
            "Got it. I updated the queue and prepared the next facts to review."
        }
        val assistantText = if (followUp.isNotBlank()) {
            "$baseAssistantText\n\n$followUp"
        } else {
            baseAssistantText
        }
        ChatStore.addMessage(chatId, ChatRole.ASSISTANT, assistantText)
    }

    private fun shouldApplyStructuredDailyReviewUpdate(userPrompt: String): Boolean {
        val text = userPrompt.trim().lowercase(java.util.Locale.US)
        if (text.isBlank()) return false

        val hasExplicitId = Regex("\\b[du]\\s*\\d{1,3}\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)
        val hasConfirmWord = listOf(
            "yes", "yep", "yeah", "confirm", "confirmed", "correct", "right", "approve", "approved", "true",
        ).any { text.contains(it) }
        val hasRejectWord = listOf(
            "no", "nope", "reject", "rejected", "wrong", "false", "deny", "denied", "not correct",
        ).any { text.contains(it) }
        val hasDecisionWord = hasConfirmWord || hasRejectWord

        val hasCollectiveDecision = hasDecisionWord && listOf(
            "all", "both", "these", "those", "them", "all three", "all 3",
        ).any { text.contains(it) }

        val isNavigationOnly = listOf(
            "next batch", "show next", "next", "continue", "move on", "skip", "more", "start daily facts review", "start review",
        ).any { text.contains(it) } && !hasDecisionWord

        if (isNavigationOnly) return false
        if (hasExplicitId && hasDecisionWord) return true
        if (hasCollectiveDecision) return true
        return false
    }

    private fun appendDailyReviewDebugPayload(
        chatId: String,
        stage: String,
        payload: String,
    ) {
        if (!isDailyFactsReview || !DAILY_REVIEW_DEBUG_VISIBLE) return
        val trimmed = payload.trim()
        if (trimmed.isBlank()) return
        val clipped = trimmed.take(7000)
        val message = buildString {
            append(DAILY_REVIEW_DEBUG_PREFIX)
            append(" ")
            append(stage)
            appendLine()
            appendLine(clipped)
            if (trimmed.length > clipped.length) {
                appendLine("[debug payload truncated]")
            }
        }.trim()
        ChatStore.addMessage(chatId, ChatRole.ASSISTANT, message)
    }

    private fun renderAiUpdateAsJson(update: DailyFactsReviewProtocol.AiUpdate): String {
        val obj = JSONObject()
        obj.put("assistant_message", update.assistantMessage)
        obj.put("confirmed_facts", JSONArray(update.confirmedFacts))
        obj.put("rejected_facts", JSONArray(update.rejectedFacts))
        obj.put("new_facts", JSONArray(update.newFacts))
        obj.put("confirmed_user_facts", JSONArray(update.confirmedUserFacts))
        obj.put("rejected_user_facts", JSONArray(update.rejectedUserFacts))
        obj.put("new_user_facts_candidates", JSONArray(update.newUserFactsCandidates))
        return obj.toString(2)
    }

    private fun buildQueueContinuationPrompt(
        state: DailyFactsStorage.State,
        candidateUserFacts: List<String>,
    ): String {
        val batch = buildDailyFactsReviewBatch(state, candidateUserFacts)
        val nextDaily = batch.daily
        val nextUser = batch.user
        if (nextDaily.isEmpty() && nextUser.isEmpty()) return ""

        val sb = StringBuilder()
        sb.appendLine("Next batch to review:")
        nextDaily.forEach { item ->
            sb.appendLine("${item.id}) ${item.text.take(180)}")
        }
        if (nextUser.isNotEmpty()) {
            sb.appendLine("User-fact candidates:")
            nextUser.forEach { item ->
                sb.appendLine("${item.id}) ${item.text.take(180)}")
            }
        }
        sb.append("Please confirm or reject by ID (e.g., 'D1 yes, D2 no, U1 yes').")
        return sb.toString().trim()
    }

    private suspend fun attemptRepairDailyFactsUpdate(
        date: String,
        raw: String,
    ): DailyFactsReviewProtocol.AiUpdate? {
        return runCatching {
            val state = DailyFactsStorage.load(this, date)
            val candidateUserFacts = pruneKnownUserFactCandidates(
                date = date,
                candidateUserFacts = CandidateUserFactsStorage.load(this, date),
                persistPrune = true,
            )
            val batch = buildDailyFactsReviewBatch(state, candidateUserFacts)
            val mode = dailyReviewOutputMode()

            val prompt = buildString {
                if (mode == DailyFactsReviewProtocol.OutputMode.LINE_IDS) {
                    appendLine("Convert the following daily-review assistant output into the strict <UPDATE> line protocol.")
                    appendLine("Format:")
                    appendLine("<UPDATE>")
                    appendLine("CONFIRM_DAILY: D1,D2")
                    appendLine("REJECT_DAILY: D3")
                    appendLine("ADD_DAILY: text A | text B")
                    appendLine("CONFIRM_USER: U1")
                    appendLine("REJECT_USER: U2")
                    appendLine("ADD_USER: text C")
                    appendLine("</UPDATE>")
                    appendLine("Use only IDs from the provided batches for confirm/reject lines.")
                } else {
                    appendLine("Convert the following daily-review assistant output into strict JSON only.")
                    appendLine("Return one JSON object with keys:")
                    appendLine("assistant_message, confirmed_facts, rejected_facts, new_facts, confirmed_user_facts, rejected_user_facts, new_user_facts_candidates")
                    appendLine("Use only IDs for confirmed/rejected arrays (D# for daily, U# for user).")
                    appendLine("Use text content only for new_facts and new_user_facts_candidates.")
                }
                appendLine()
                appendLine("DATE: $date")
                appendLine("DAILY_BATCH:")
                batch.daily.forEach { item -> appendLine("${item.id}. ${item.text}") }
                appendLine("USER_BATCH:")
                batch.user.forEach { item -> appendLine("${item.id}. ${item.text}") }
                appendLine()
                appendLine("OUTPUT_TO_CONVERT:")
                appendLine(raw.take(4000))
            }

            val repairedRaw = withContext(Dispatchers.IO) {
                RelayAiAssistantRouter.textReply(this@ChatThreadActivity, prompt)
            }
            DailyFactsReviewProtocol.parseUpdate(repairedRaw)
        }.getOrNull()
    }

    private suspend fun attemptInferDailyFactsUpdateFromUserReply(
        date: String,
        userReply: String,
    ): DailyFactsReviewProtocol.AiUpdate? {
        if (userReply.isBlank()) return null

        return runCatching {
            val state = DailyFactsStorage.load(this, date)
            val candidateUserFacts = pruneKnownUserFactCandidates(
                date = date,
                candidateUserFacts = CandidateUserFactsStorage.load(this, date),
                persistPrune = true,
            )
            val batch = buildDailyFactsReviewBatch(state, candidateUserFacts)
            val mode = dailyReviewOutputMode()

            val prompt = buildString {
                appendLine("Infer structured queue updates from this USER reply.")
                if (mode == DailyFactsReviewProtocol.OutputMode.LINE_IDS) {
                    appendLine("Return a short assistant message, then strict <UPDATE> line protocol:")
                    appendLine("<UPDATE>")
                    appendLine("CONFIRM_DAILY: D1")
                    appendLine("REJECT_DAILY: D2")
                    appendLine("ADD_DAILY: text")
                    appendLine("CONFIRM_USER: U1")
                    appendLine("REJECT_USER: U2")
                    appendLine("ADD_USER: text")
                    appendLine("</UPDATE>")
                    appendLine("Use only provided IDs for CONFIRM/REJECT lines.")
                } else {
                    appendLine("Return strict JSON only with keys:")
                    appendLine("assistant_message, confirmed_facts, rejected_facts, new_facts, confirmed_user_facts, rejected_user_facts, new_user_facts_candidates")
                    appendLine("Use only IDs for confirmed/rejected arrays (D# for daily, U# for user).")
                    appendLine("Use text content only for new_facts and new_user_facts_candidates.")
                }
                appendLine()
                appendLine("CURRENT_DAILY_BATCH:")
                if (batch.daily.isEmpty()) appendLine("(none)") else batch.daily.forEach { item -> appendLine("${item.id}. ${item.text}") }
                appendLine("CURRENT_USER_BATCH:")
                if (batch.user.isEmpty()) appendLine("(none)") else batch.user.forEach { item -> appendLine("${item.id}. ${item.text}") }
                appendLine()
                appendLine("USER_REPLY:")
                appendLine(userReply.take(2000))
            }

            val inferenceRaw = if (isLocalModelsProviderSelected()) {
                LocalModelsProvider().streamChat(
                    context = this,
                    messages = listOf(mapOf("role" to "User", "content" to prompt)),
                    requestPriority = LocalModelRequestPriority.HIGH,
                )
            } else {
                withContext(Dispatchers.IO) {
                    RelayAiAssistantRouter.textReply(this@ChatThreadActivity, prompt)
                }
            }

            DailyFactsReviewProtocol.parseUpdate(inferenceRaw)
        }.getOrNull()
    }

    private fun extractUserFacingAssistantText(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return ""

        val looksLikeRawJson = (trimmed.startsWith("{") && trimmed.endsWith("}")) ||
            trimmed.startsWith("```json") ||
            trimmed.contains("\"confirmed_facts\"") ||
            trimmed.contains("\"rejected_facts\"")
        if (looksLikeRawJson) return ""

        return trimmed.take(1500)
    }

    private fun resolveFactsAgainstBatch(
        returnedFacts: List<String>,
        batchFacts: List<ReviewBatchItem>,
    ): List<String> {
        if (returnedFacts.isEmpty() || batchFacts.isEmpty()) return emptyList()

        val out = LinkedHashSet<String>()
        val canonicalByNorm = LinkedHashMap<String, String>()
        val byIndex = LinkedHashMap<Int, String>()
        val byId = LinkedHashMap<String, String>()
        batchFacts.forEach { fact ->
            val norm = normalizeFactForMatching(fact.text)
            if (norm.isNotBlank() && !canonicalByNorm.containsKey(norm)) {
                canonicalByNorm[norm] = fact.text
            }
            byId[fact.id.uppercase()] = fact.text
        }
        batchFacts.forEachIndexed { idx, fact -> byIndex[idx + 1] = fact.text }

        for (raw in returnedFacts) {
            val candidate = raw.trim()
            if (candidate.isBlank()) continue

            Regex("\\b([DU]\\s*\\d{1,3})\\b", RegexOption.IGNORE_CASE)
                .findAll(candidate)
                .map { it.groupValues[1].replace(" ", "").uppercase() }
                .forEach { id -> byId[id]?.let { out += it } }
            if (out.isNotEmpty() && candidate.length <= 14) continue

            val explicitIndexes = Regex("\\b(\\d{1,3})\\b")
                .findAll(candidate)
                .mapNotNull { it.groupValues.getOrNull(1)?.toIntOrNull() }
                .toList()
            if (explicitIndexes.isNotEmpty()) {
                explicitIndexes.forEach { idx -> byIndex[idx]?.let { out += it } }
                if (out.isNotEmpty()) continue
            }

            val norm = normalizeFactForMatching(candidate)
            if (norm.isBlank()) continue

            val exact = canonicalByNorm[norm]
            if (exact != null) {
                out += exact
                continue
            }

            val fuzzy = canonicalByNorm.entries.firstOrNull { (batchNorm, _) ->
                val a = batchNorm.split(' ').filter { it.length > 2 }.toSet()
                val b = norm.split(' ').filter { it.length > 2 }.toSet()
                val overlap = a.intersect(b).size
                (batchNorm.contains(norm) || norm.contains(batchNorm)) ||
                    (overlap >= 3 && overlap >= (a.size.coerceAtMost(b.size) / 2))
            }?.value
            if (fuzzy != null) out += fuzzy
        }

        return out.toList()
    }

    private fun inferUpdateFromUserReplyHeuristic(
        userReply: String,
        batch: ReviewBatch,
    ): DailyFactsReviewProtocol.AiUpdate? {
        if (userReply.isBlank()) return null
        if (batch.daily.isEmpty() && batch.user.isEmpty()) return null

        val lower = userReply.lowercase(java.util.Locale.US)
        val confirmWords = listOf("yes", "yep", "yeah", "correct", "right", "true", "confirm", "approved", "ok")
        val rejectWords = listOf("no", "nope", "wrong", "false", "reject", "not", "nah", "skip")

        val confirmDaily = LinkedHashSet<String>()
        val rejectDaily = LinkedHashSet<String>()
        val confirmUser = LinkedHashSet<String>()
        val rejectUser = LinkedHashSet<String>()
        val dailyIds = batch.daily.map { it.id.uppercase() }.toSet()
        val userIds = batch.user.map { it.id.uppercase() }.toSet()

        fun applyForIds(ids: List<String>, confirm: Boolean) {
            ids.forEach { id ->
                val normalized = id.uppercase()
                when {
                    normalized.startsWith("D") -> {
                        if (!dailyIds.contains(normalized)) return@forEach
                        if (confirm) confirmDaily += normalized else rejectDaily += normalized
                    }
                    normalized.startsWith("U") -> {
                        if (!userIds.contains(normalized)) return@forEach
                        if (confirm) confirmUser += normalized else rejectUser += normalized
                    }
                }
            }
        }

        val segments = userReply.split(Regex("[\\n,;]+|\\band\\b", RegexOption.IGNORE_CASE))
            .map { it.trim() }
            .filter { it.isNotBlank() }

        segments.forEach { segment ->
            val segLower = segment.lowercase(java.util.Locale.US)
            val hasConfirm = confirmWords.any { segLower.contains(it) }
            val hasReject = rejectWords.any { segLower.contains(it) }
            if (!hasConfirm && !hasReject) return@forEach

            val explicitIds = Regex("\\b([DU]\\s*\\d{1,3})\\b", RegexOption.IGNORE_CASE)
                .findAll(segment)
                .map { it.groupValues[1].replace(" ", "").uppercase() }
                .toList()

            val bareDaily = if (explicitIds.isEmpty()) {
                Regex("\\b(\\d{1,3})\\b")
                    .findAll(segment)
                    .mapNotNull { it.groupValues.getOrNull(1)?.toIntOrNull() }
                    .map { "D$it" }
                    .toList()
            } else {
                emptyList()
            }

            val ids = (explicitIds + bareDaily)
                .distinct()

            if (ids.isNotEmpty()) {
                applyForIds(ids, confirm = hasConfirm && !hasReject)
                if (hasReject && !hasConfirm) applyForIds(ids, confirm = false)
            }
        }

        if (confirmDaily.isEmpty() && rejectDaily.isEmpty() && confirmUser.isEmpty() && rejectUser.isEmpty()) {
            if (batch.daily.size == 1 || batch.user.size == 1) {
                val hasConfirm = confirmWords.any { lower.contains(it) }
                val hasReject = rejectWords.any { lower.contains(it) }
                if (hasConfirm xor hasReject) {
                    if (batch.daily.size == 1) {
                        val onlyDailyId = batch.daily.first().id
                        if (hasConfirm) confirmDaily += onlyDailyId else rejectDaily += onlyDailyId
                    }
                    if (batch.user.size == 1) {
                        val onlyUserId = batch.user.first().id
                        if (hasConfirm) confirmUser += onlyUserId else rejectUser += onlyUserId
                    }
                }
            }
        }

        val anyUpdates = confirmDaily.isNotEmpty() || rejectDaily.isNotEmpty() ||
            confirmUser.isNotEmpty() || rejectUser.isNotEmpty()
        if (!anyUpdates) return null

        return DailyFactsReviewProtocol.AiUpdate(
            assistantMessage = "Got it — updated. Continuing with the next batch.",
            confirmedFacts = confirmDaily.toList(),
            rejectedFacts = rejectDaily.toList(),
            newFacts = emptyList(),
            confirmedUserFacts = confirmUser.toList(),
            rejectedUserFacts = rejectUser.toList(),
            newUserFactsCandidates = emptyList(),
        )
    }

    private fun normalizeFactForMatching(value: String): String {
        return value
            .lowercase(java.util.Locale.US)
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(260)
    }

    private fun updateChatThreadState(event: ChatThreadEvent) {
        chatThreadUiState = ChatThreadStateReducer.reduce(chatThreadUiState, event)
    }

    private fun refreshMessages() {
        val cid = chatId
        val requestId = ++refreshRequestId
        if (cid == null) {
            updateChatThreadState(ChatThreadEvent.Loaded(thread = null, messages = emptyList()))
            dailyReviewQueueStatus = null
            return
        }
        lifecycleScope.launch {
            val thread = chatRepository.getThread(cid)
            val storedRaw = chatRepository.listMessages(cid)
            if (requestId != refreshRequestId || cid != chatId) return@launch

            val stored = if (DAILY_REVIEW_DEBUG_VISIBLE) {
                storedRaw
            } else {
                storedRaw.filterNot { message ->
                    message.role == ChatRole.ASSISTANT &&
                        message.content.startsWith(DAILY_REVIEW_DEBUG_PREFIX)
                }
            }
            updateChatThreadState(ChatThreadEvent.ThreadChanged(thread))
            updateChatThreadState(ChatThreadEvent.MessagesChanged(stored))
        }
    }

    private fun refreshDailyReviewQueueStatusAsync() {
        if (!isDailyFactsReview) {
            dailyReviewQueueStatus = null
            return
        }

        val date = dailyFactsDate?.trim().orEmpty()
        if (date.isBlank()) {
            dailyReviewQueueStatus = null
            return
        }

        val expectedChatId = chatId
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) {
                val state = runCatching { DailyFactsStorage.load(this@ChatThreadActivity, date) }
                    .getOrNull()
                val candidateUserFactsRaw = runCatching { CandidateUserFactsStorage.load(this@ChatThreadActivity, date) }
                    .getOrElse { emptyList() }
                val candidateUserFacts = pruneKnownUserFactCandidates(
                    date = date,
                    candidateUserFacts = candidateUserFactsRaw,
                    persistPrune = true,
                )

                val draftCount = state?.draft?.size ?: 0
                val confirmedCount = state?.confirmed?.size ?: 0
                val candidateCount = candidateUserFacts.size
                if (draftCount == 0 && candidateCount == 0) {
                    NightlyEnrichmentPrefs.markReviewed(this@ChatThreadActivity, date)
                }
                if (draftCount >= 0 && confirmedCount >= 0 && candidateCount >= 0) {
                    "Queue integrity: ok · pending $draftCount · confirmed $confirmedCount · user candidates $candidateCount"
                } else {
                    "Queue integrity: check needed"
                }
            }
            if (isDailyFactsReview && expectedChatId == chatId && date == dailyFactsDate?.trim()) {
                dailyReviewQueueStatus = text
            }
        }
    }

    private fun navigateTo(destination: AppDestination) {
        val target = when (destination) {
            AppDestination.CHATS -> {
                val cid = chatId
                val lastUserAt = cid
                    ?.let(ChatStore::listMessages)
                    ?.lastOrNull { it.role == ChatRole.USER }
                    ?.createdAt
                if (lastUserAt != null && System.currentTimeMillis() - lastUserAt < 30 * 60 * 1000) {
                    return
                }
                Intent(this, ChatThreadActivity::class.java)
            }

            AppDestination.GLASSES -> Intent(this, MainActivity::class.java)
            AppDestination.MEDIA -> Intent(this, com.fersaiyan.cyanbridge.ui.recordings.RecordingsListActivity::class.java)
            AppDestination.PLUGINS -> Intent(this, CommunityPluginsActivity::class.java)
            AppDestination.SETTINGS -> Intent(this, SettingsActivity::class.java)
        }
        target.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        startActivity(target)
    }

    companion object {
        private const val DAILY_REVIEW_DEBUG_PREFIX = "[DEBUG_DAILY_REVIEW]"
        private const val DAILY_REVIEW_DEBUG_VISIBLE = false
        private const val AUDIO_SAMPLE_RATE_HZ = 16_000
        private const val WAV_HEADER_BYTES = 44

        const val EXTRA_CHAT_ID = "chat_id"

        // If EXTRA_CHAT_ID is missing, ChatThreadActivity can create a new thread.
        const val EXTRA_CREATE_THREAD_TITLE = "create_thread_title"

        // Optional: prefill the composer input box.
        const val EXTRA_PREFILL_MESSAGE = "prefill_message"

        // Daily facts review mode
        const val EXTRA_DAILY_FACTS_REVIEW = "daily_facts_review"
        const val EXTRA_DAILY_FACTS_DATE = "daily_facts_date"
        const val EXTRA_DAILY_FACTS_LOOKBACK_DAYS = "daily_facts_lookback_days"
    }
}

package com.fersaiyan.cyanbridge.shared.ui

import com.fersaiyan.cyanbridge.shared.platform.platformLanguageTag
import com.fersaiyan.cyanbridge.shared.settings.LocalizedPromptDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.fersaiyan.cyanbridge.shared.ai.ChatMessage as AiChatMessage
import com.fersaiyan.cyanbridge.shared.chat.ChatMessage
import com.fersaiyan.cyanbridge.shared.chat.ChatRole
import com.fersaiyan.cyanbridge.shared.chat.ChatThread
import com.fersaiyan.cyanbridge.shared.chat.ChatThreadSummary
import com.fersaiyan.cyanbridge.shared.chat.ChatThreadUiState
import com.fersaiyan.cyanbridge.shared.chat.ChatAttachmentsUiState
import com.fersaiyan.cyanbridge.shared.chat.ChatComposerUiState
import com.fersaiyan.cyanbridge.shared.billing.ProSubscriptionAction
import com.fersaiyan.cyanbridge.shared.billing.ProSubscriptionUiState
import com.fersaiyan.cyanbridge.shared.billing.unavailableProSubscriptionStatus
import com.fersaiyan.cyanbridge.shared.navigation.AppDestination
import com.fersaiyan.cyanbridge.shared.navigation.SharedSubscriptionRoute
import com.fersaiyan.cyanbridge.shared.navigation.closeSubscription
import com.fersaiyan.cyanbridge.shared.navigation.openSubscription
import com.fersaiyan.cyanbridge.shared.persistence.ChatEntity
import com.fersaiyan.cyanbridge.shared.persistence.ChatMessageEntity
import com.fersaiyan.cyanbridge.shared.plugins.NativePluginCardData
import com.fersaiyan.cyanbridge.shared.plugins.NativePluginIds
import com.fersaiyan.cyanbridge.shared.plugins.PluginTimeWindow
import com.fersaiyan.cyanbridge.shared.recordings.MeetingRecordingUiState
import com.fersaiyan.cyanbridge.shared.recordings.RecordingItem
import com.fersaiyan.cyanbridge.shared.recordings.SyncedMediaItem
import com.fersaiyan.cyanbridge.shared.settings.AgentProviderType
import com.fersaiyan.cyanbridge.shared.settings.SettingsSection
import com.fersaiyan.cyanbridge.shared.settings.MemoryPrivacyMode
import com.fersaiyan.cyanbridge.shared.settings.MemorySourceType
import com.fersaiyan.cyanbridge.shared.platform.CyanBridgeServices
import com.fersaiyan.cyanbridge.shared.platform.SharedMediaHooks
import com.fersaiyan.cyanbridge.shared.ui.plugins.PublishPluginScreen
import com.fersaiyan.cyanbridge.shared.plugins.PublishPluginUiState
import com.fersaiyan.cyanbridge.shared.plugins.CommunityPluginCardData
import com.fersaiyan.cyanbridge.shared.platform.SharedPluginsHooks
import com.fersaiyan.cyanbridge.shared.platform.SharedSettingsHooks
import androidx.compose.runtime.collectAsState
import com.fersaiyan.cyanbridge.shared.recordings.TranscriptDialogUiState
import com.fersaiyan.cyanbridge.shared.recordings.TranscriptionProgressUiState
import com.fersaiyan.cyanbridge.shared.platform.SharedRecordingsHooks
import com.fersaiyan.cyanbridge.shared.platform.PlatformPreferences
import com.fersaiyan.cyanbridge.shared.notes.NoteSummary
import com.fersaiyan.cyanbridge.shared.platform.createPlatformPreferences
import com.fersaiyan.cyanbridge.shared.platform.platformCurrentTimeMillis
import com.fersaiyan.cyanbridge.shared.ui.chat.ChatThreadScreen
import com.fersaiyan.cyanbridge.shared.ui.chat.NotesChatsScreen
import com.fersaiyan.cyanbridge.shared.ui.chat.NotesChatsTab
import com.fersaiyan.cyanbridge.shared.ui.plugins.CommunityPluginsScreen
import com.fersaiyan.cyanbridge.shared.ui.pro.ProSubscriptionScreen
import com.fersaiyan.cyanbridge.shared.ui.recordings.RecordingsScreen
import com.fersaiyan.cyanbridge.shared.ui.recordings.SyncedMediaGalleryScreen
import com.fersaiyan.cyanbridge.shared.ui.settings.SettingsScreenActions
import com.fersaiyan.cyanbridge.shared.ui.settings.SettingsUiState
import com.fersaiyan.cyanbridge.shared.ui.settings.SettingsScreen
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import com.fersaiyan.cyanbridge.shared.generated.resources.*
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.stringResource

/**
 * Shared top-level destinations used by the iOS KMP host.
 *
 * Android keeps its mature Activity presenters for now. The iOS host uses this
 * route so the shared screens are real destinations rather than placeholders.
 * Platform repositories and AI services remain behind CyanBridgeServices.
 */
@Composable
fun SharedDestinationScreen(
    destination: AppDestination,
    onDestinationSelected: (AppDestination) -> Unit,
    onOpenAppearance: () -> Unit = {},
    proSubscriptionState: ProSubscriptionUiState = ProSubscriptionUiState(),
    onProSubscriptionAction: (ProSubscriptionAction) -> String = ::unavailableProSubscriptionStatus,
) {
    var subscriptionRoute by remember(destination) {
        mutableStateOf(SharedSubscriptionRoute.SETTINGS)
    }

    when (destination) {
        AppDestination.CHATS -> SharedChatsDestination(onDestinationSelected)
        AppDestination.MEDIA -> SharedMediaDestination(onDestinationSelected)
        AppDestination.PLUGINS -> SharedPluginsDestination(onDestinationSelected)
        AppDestination.SETTINGS -> when (subscriptionRoute) {
            SharedSubscriptionRoute.SETTINGS -> SharedSettingsDestination(
                onDestinationSelected = onDestinationSelected,
                onOpenAppearance = onOpenAppearance,
                onOpenSubscription = {
                    subscriptionRoute = subscriptionRoute.openSubscription()
                },
            )
            SharedSubscriptionRoute.PRO_SUBSCRIPTION -> SharedProSubscriptionDestination(
                initialState = proSubscriptionState,
                onSubscriptionAction = onProSubscriptionAction,
                onBack = { subscriptionRoute = subscriptionRoute.closeSubscription() },
            )
        }
        AppDestination.GLASSES -> Unit
    }
}

@OptIn(ExperimentalResourceApi::class)
@Composable
private fun SharedMediaDestination(onDestinationSelected: (AppDestination) -> Unit) {
    val scope = rememberCoroutineScope()
    val formatTimestamp = sharedTimestampFormatter()
    var mediaItems by remember { mutableStateOf<List<SyncedMediaItem>>(emptyList()) }
    var showGallery by remember { mutableStateOf(false) }

    fun refresh() {
        scope.launch {
            if (CyanBridgeServices.isInitialized()) {
                mediaItems = CyanBridgeServices.mediaRecordRepository.getAll()
                    .sortedByDescending { it.downloadedAt }
                    .map { record ->
                    SyncedMediaItem(
                        id = record.id.hashCode().toLong(),
                        displayName = record.filename,
                        contentUriString = record.filePath,
                        isVideo = record.mimeType.startsWith("video/"),
                    )
                }
            }
        }
    }

    val recordings = SharedRecordingsHooks.provider
    var sessions by remember { mutableStateOf<List<RecordingItem>>(emptyList()) }
    var transcribingId by remember { mutableStateOf<Long?>(null) }
    var transcriptionProgress by remember { mutableStateOf<TranscriptionProgressUiState?>(null) }
    var transcriptDialog by remember { mutableStateOf<TranscriptDialogUiState?>(null) }
    val meetingState = recordings?.meetingState?.collectAsState()?.value ?: MeetingRecordingUiState()
    val playingId = recordings?.playingId?.collectAsState()?.value

    fun refreshRecordings() {
        scope.launch { sessions = recordings?.recordings().orEmpty() }
    }

    LaunchedEffect(Unit) { refresh() }
    LaunchedEffect(meetingState.isRecording) { refreshRecordings() }

    if (showGallery) {
        SyncedMediaGalleryScreen(
            mediaItems = mediaItems,
            isLoading = false,
             folderHint = stringResource(Res.string.media_folder_hint),
            loadThumbnail = { path: String -> SharedMediaHooks.loadThumbnail?.invoke(path) },
            onNavigateBack = { showGallery = false },
            onRefresh = ::refresh,
            onOpenMedia = { item -> SharedMediaHooks.openMedia?.invoke(item.contentUriString) },
            onShareItems = { items -> SharedMediaHooks.shareMedia?.invoke(items.map { it.contentUriString }) },
            onDeleteItems = { items ->
                scope.launch {
                    val paths = items.map { it.contentUriString }
                    SharedMediaHooks.deleteMediaFiles?.invoke(paths)
                    val repository = CyanBridgeServices.mediaRecordRepository
                    repository.getAll()
                        .filter { it.filePath in paths }
                        .forEach { repository.delete(it.id) }
                    refresh()
                }
            },
        )
    } else {
        RecordingsScreen(
            sessions = sessions,
            isLoading = false,
            recentSyncedMedia = mediaItems.take(4),
            playingSessionId = playingId,
            transcribingSessionId = transcribingId,
            meetingRecording = meetingState,
            transcriptionProgress = transcriptionProgress,
            transcriptDialog = transcriptDialog,
             formatTimestamp = formatTimestamp,
            loadThumbnail = { path: String -> SharedMediaHooks.loadThumbnail?.invoke(path) },
            onOpenSyncedMedia = { showGallery = true },
            onOpenSyncedMediaItem = { item ->
                SharedMediaHooks.openMedia?.invoke(item.contentUriString) ?: run { showGallery = true }
            },
            onPlay = { item -> recordings?.togglePlayback(item.id) },
            onTranscribe = { item ->
                val provider = recordings ?: return@RecordingsScreen
                if (transcribingId != null) return@RecordingsScreen
                transcribingId = item.id
                scope.launch {
                    runCatching {
                        provider.transcribe(item.id) { message ->
                            transcriptionProgress = TranscriptionProgressUiState(title = item.title, message = message)
                        }
                    }.onSuccess { text ->
                        transcriptDialog = TranscriptDialogUiState(title = item.title, text = text)
                    }.onFailure { error ->
                        transcriptDialog = TranscriptDialogUiState(
                            title = item.title,
                            text = error.message ?: "Transcription failed",
                        )
                    }
                    transcriptionProgress = null
                    transcribingId = null
                    refreshRecordings()
                }
            },
            onViewTranscript = { item ->
                scope.launch {
                    recordings?.transcript(item.id)?.let { text ->
                        transcriptDialog = TranscriptDialogUiState(title = item.title, text = text)
                    }
                }
            },
            onStopMeetingCapture = {
                recordings?.stopMeetingCapture()
                refreshRecordings()
            },
            onDeleteItems = { items ->
                val deleted = recordings?.delete(items.map { it.id }).orEmpty()
                refreshRecordings()
                deleted
            },
            onDismissTranscript = { transcriptDialog = null },
            onDestinationSelected = onDestinationSelected,
            showNavigationBar = false,
        )
    }
}

@OptIn(ExperimentalResourceApi::class)
@Composable
private fun SharedPluginsDestination(onDestinationSelected: (AppDestination) -> Unit) {
    val scope = rememberCoroutineScope()
    val pluginsPlatform = SharedPluginsHooks.platform
    val enabledIds = pluginsPlatform?.enabledPluginIds?.collectAsState()?.value.orEmpty()
    val available = pluginsPlatform?.availablePluginIds.orEmpty()
    var communityPlugins by remember { mutableStateOf<List<CommunityPluginCardData>>(emptyList()) }
    var isRefreshing by remember { mutableStateOf(false) }
    var selectedWindow by remember { mutableStateOf(PluginTimeWindow.ALL_TIME) }
    var publishState by remember { mutableStateOf<PublishPluginUiState?>(null) }
    val iosBadge = stringResource(Res.string.native_ios_badge)

    fun refresh() {
        val hooks = pluginsPlatform ?: return
        if (isRefreshing) return
        isRefreshing = true
        scope.launch {
            communityPlugins = runCatching { hooks.fetchCommunityPlugins() }.getOrDefault(communityPlugins)
            isRefreshing = false
        }
    }

    LaunchedEffect(Unit) { refresh() }

    publishState?.let { state ->
        SharedPublishPluginDestination(
            state = state,
            onStateChange = { publishState = it },
            onClose = { publishState = null },
        )
        return
    }

    fun runnable(id: String, title: String, description: String) = NativePluginCardData(
        id = id,
        title = title,
        description = description,
        badge = iosBadge,
        enabled = id in enabledIds,
        hasSettings = false,
        isAvailable = id in available,
    )

    val nativePlugins = listOf(
        runnable(
            NativePluginIds.LIVE_CAPTION_RELAY,
            stringResource(Res.string.native_live_caption_relay_title),
            stringResource(Res.string.native_live_caption_relay_description),
        ),
        runnable(
            NativePluginIds.HANDS_FREE_TRANSLATOR,
            stringResource(Res.string.native_hands_free_translator_title),
            stringResource(Res.string.native_hands_free_translator_description),
        ),
        runnable(
            NativePluginIds.MEETING_SPARK_NOTES,
            stringResource(Res.string.native_meeting_spark_notes_title),
            stringResource(Res.string.native_meeting_spark_notes_description),
        ),
        runnable(
            NativePluginIds.ERRAND_BRAIN,
            stringResource(Res.string.native_errand_brain_title),
            stringResource(Res.string.native_errand_brain_description),
        ),
        runnable(
            NativePluginIds.WALKING_AID,
            stringResource(Res.string.native_walking_aid_title),
            stringResource(Res.string.native_walking_aid_description),
        ),
        NativePluginCardData(
            id = NativePluginIds.LOCAL_AGENT,
            title = stringResource(Res.string.native_local_agent_title),
            description = stringResource(Res.string.native_local_agent_description),
            badge = stringResource(Res.string.native_android_only),
            enabled = false,
            hasSettings = false,
            isAvailable = false,
        ),
        NativePluginCardData(
            id = NativePluginIds.AUTO_DIARY,
            title = stringResource(Res.string.native_auto_diary_title),
            description = stringResource(Res.string.native_auto_diary_description),
            badge = stringResource(Res.string.native_android_only),
            enabled = false,
            hasSettings = false,
            isAvailable = false,
        ),
    )

    CommunityPluginsScreen(
        plugins = communityPlugins,
        selectedWindow = selectedWindow,
        isRefreshing = isRefreshing,
        onWindowSelected = { selectedWindow = it },
        onRefresh = ::refresh,
        onOpenCommunityPlugin = { plugin ->
            (plugin.taskerNetLink ?: plugin.downloadUrl)?.let { link -> pluginsPlatform?.openLink(link) }
        },
        onPublishPlugin = { publishState = PublishPluginUiState() },
        onDestinationSelected = onDestinationSelected,
        nativePlugins = nativePlugins,
        onToggleNativePlugin = { id, enabled -> pluginsPlatform?.setPluginEnabled(id, enabled) },
        showNavigationBar = false,
    )
}

@Composable
private fun SharedPublishPluginDestination(
    state: PublishPluginUiState,
    onStateChange: (PublishPluginUiState) -> Unit,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val categories = listOf("Productivity", "Accessibility", "Memory", "Media", "Other")
    PublishPluginScreen(
        state = state,
        categories = categories,
        onTitleChanged = { onStateChange(state.copy(title = it, titleError = null)) },
        onAuthorChanged = { onStateChange(state.copy(author = it, authorError = null)) },
        onDescriptionChanged = { onStateChange(state.copy(description = it, descriptionError = null)) },
        onCategorySelected = { onStateChange(state.copy(category = it)) },
        onTaskerNetLinkChanged = { onStateChange(state.copy(taskerNetLink = it, taskerNetLinkError = null)) },
        onSubmit = {
            val checked = state.copy(
                titleError = "Required".takeIf { state.title.isBlank() },
                authorError = "Required".takeIf { state.author.isBlank() },
                descriptionError = "Required".takeIf { state.description.isBlank() },
                taskerNetLinkError = "Use an https:// link".takeIf { !state.taskerNetLink.trim().startsWith("https://") },
            )
            if (listOf(checked.titleError, checked.authorError, checked.descriptionError, checked.taskerNetLinkError).any { it != null }) {
                onStateChange(checked)
                return@PublishPluginScreen
            }
            val hooks = SharedPluginsHooks.platform ?: return@PublishPluginScreen
            onStateChange(checked.copy(isSubmitting = true))
            scope.launch {
                val sent = hooks.publishPlugin(
                    title = state.title.trim(),
                    author = state.author.trim(),
                    description = state.description.trim(),
                    category = state.category.ifBlank { categories.last() },
                    link = state.taskerNetLink.trim(),
                )
                if (sent) onClose() else onStateChange(checked.copy(isSubmitting = false, taskerNetLinkError = "Server unavailable. Try again later."))
            }
        },
        onNavigateBack = onClose,
    )
}

@Composable
private fun SharedSettingsDestination(
    onDestinationSelected: (AppDestination) -> Unit,
    onOpenAppearance: () -> Unit,
    onOpenSubscription: () -> Unit,
) {
    var expandedSections by remember { mutableStateOf<Set<SettingsSection>>(emptySet()) }
    val preferences = remember { createPlatformPreferences(SHARED_SETTINGS_PREFS) }
    var settingsState by remember {
        mutableStateOf(loadSharedSettings(preferences))
    }
    val actions = remember(onDestinationSelected, onOpenAppearance, onOpenSubscription) {
        SharedSettingsScreenActions(
            onDestinationSelected = onDestinationSelected,
            onOpenAppearance = onOpenAppearance,
            onOpenSubscription = onOpenSubscription,
            currentState = { settingsState },
            updateState = { next ->
                settingsState = next
                saveSharedSettings(preferences, next)
            },
        )
    }

    SettingsScreen(
        state = settingsState,
        expandedSections = expandedSections,
        onToggleSection = { section ->
            expandedSections = if (section in expandedSections) {
                expandedSections - section
            } else {
                expandedSections + section
            }
        },
        actions = actions,
        showNavigationBar = false,
    )
}

@OptIn(ExperimentalResourceApi::class)
@Composable
private fun SharedProSubscriptionDestination(
    initialState: ProSubscriptionUiState,
    onSubscriptionAction: (ProSubscriptionAction) -> String,
    onBack: () -> Unit,
) {
    var state by remember(initialState) { mutableStateOf(initialState) }
    val unavailableSubscriptionStatus = stringResource(Res.string.shared_chat_unavailable)

    fun reportUnavailableAction(action: ProSubscriptionAction) {
        state = state.copy(
            status = onSubscriptionAction(action),
        )
    }

    ProSubscriptionScreen(
        state = state,
        restoreNotFoundEmail = null,
        restoreLogsSending = false,
        onPlanSelected = { plan -> state = state.copy(selectedPlan = plan) },
        onStartFreeTrial = { reportUnavailableAction(ProSubscriptionAction.SUBSCRIBE) },
        onSubscribeWithGooglePlay = { reportUnavailableAction(ProSubscriptionAction.SUBSCRIBE) },
        onSubscribeOnWebsite = { reportUnavailableAction(ProSubscriptionAction.SUBSCRIBE) },
        onCheckoutUnavailable = { reportUnavailableAction(ProSubscriptionAction.SUBSCRIBE) },
        onRestoreExistingSubscription = { reportUnavailableAction(ProSubscriptionAction.SUBSCRIBE) },
        onDismissRestoreNotFound = {},
        onSendRestoreFailureLogs = {},
        onDonate = { reportUnavailableAction(ProSubscriptionAction.DONATE) },
        onCancelSubscription = {
            state = state.copy(
                 status = unavailableSubscriptionStatus,
            )
        },
        onBack = onBack,
    )
}

private class SharedSettingsScreenActions(
    private val onDestinationSelected: (AppDestination) -> Unit,
    private val onOpenAppearance: () -> Unit,
    private val onOpenSubscription: () -> Unit,
    private val currentState: () -> SettingsUiState,
    private val updateState: (SettingsUiState) -> Unit,
) : SettingsScreenActions {
    private val platform get() = SharedSettingsHooks.platform
    private val scope = MainScope()

    private fun update(transform: (SettingsUiState) -> SettingsUiState) {
        updateState(transform(currentState()))
    }

    override fun onDestinationSelected(destination: AppDestination) = onDestinationSelected.invoke(destination)
    override fun openAppearance() = onOpenAppearance.invoke()
    override fun openAppLanguageSelection() { platform?.openAppLanguageSettings() }
    override fun openSubscription() = onOpenSubscription.invoke()
    override fun setDefaultImageQuestion(question: String) = update { it.copy(defaultImageQuestion = question) }
    override fun resetDefaultImageQuestion() = update {
        it.copy(defaultImageQuestion = LocalizedPromptDefaults.imageQuestion(platformLanguageTag()))
    }
    override fun setMemoryMode(mode: MemoryPrivacyMode) = update { it.copy(memoryMode = mode) }
    override fun setMemorySync(source: MemorySourceType, enabled: Boolean) = update { state ->
        when (source) {
            MemorySourceType.EXPLICIT_USER_FACT -> state.copy(syncExplicit = enabled)
            MemorySourceType.AUTO_DAILY_FACT -> state.copy(syncDaily = enabled)
            MemorySourceType.SCREEN_OCR -> state.copy(syncOcr = enabled)
            MemorySourceType.DERIVED_SUMMARY -> state.copy(syncDerived = enabled)
            else -> state
        }
    }
    override fun deletePassiveCapture() { platform?.stopMeetingCapture() }
    override fun lockVault() = update { it.copy(vaultLocked = true) }
    override fun unlockVault() {
        val hooks = platform ?: return update { it.copy(vaultLocked = false) }
        scope.launch { if (hooks.unlockVault()) update { it.copy(vaultLocked = false) } }
    }
    override fun setVaultPassphrase() {
        val hooks = platform ?: return
        scope.launch { if (hooks.setVaultPassphrase()) update { it.copy(vaultRequiresPassphrase = true) } }
    }
    override fun clearVaultPassphrase() {
        val hooks = platform ?: return
        scope.launch { if (hooks.clearVaultPassphrase()) update { it.copy(vaultRequiresPassphrase = false) } }
    }
    override fun resetVault() = update { it.copy(vaultLocked = false, vaultRequiresPassphrase = false) }
    override fun setTranscriptStorageEnabled(enabled: Boolean) = update { it.copy(transcriptStorageEnabled = enabled) }
    override fun setRedactNamesEnabled(enabled: Boolean) = update { it.copy(redactNamesEnabled = enabled) }
    override fun setIncludeFullTranscriptionEnabled(enabled: Boolean) = update { it.copy(includeFullTranscriptionInExports = enabled) }
    override fun exportLocalData() { platform?.exportLocalData() }
    override fun importLocalData() { platform?.importLocalData() }
    override fun importChatGptData() { platform?.importChatGptData() }
    override fun importClaudeData() { platform?.importClaudeData() }
    override fun clearLocalData() { platform?.clearLocalData() }
    override fun sendDebugLogs() { platform?.sendDebugLogs() }
    override fun stopMeetingCapture() { platform?.stopMeetingCapture() }
    override fun setProviderType(type: AgentProviderType) = update { it.copy(providerType = type) }
    override fun openTaskerIntegrations() = Unit
    override fun openLocalModels() { platform?.openLocalModels() }
}

internal const val SHARED_SETTINGS_PREFS = "cyanbridge_shared_settings"

private fun loadSharedSettings(preferences: PlatformPreferences): SettingsUiState = SettingsUiState(
    appLanguageLabel = SharedSettingsHooks.platform?.appLanguageLabel() ?: SettingsUiState().appLanguageLabel,
    providerType = AgentProviderType.valueOf(preferences.getString("provider_type", AgentProviderType.PRO_SUBSCRIPTION.name)),
    memoryMode = MemoryPrivacyMode.fromRaw(preferences.getString("memory_mode", MemoryPrivacyMode.PRIVATE_LOCAL.name)),
    syncExplicit = preferences.getBoolean("sync_explicit", true),
    syncDaily = preferences.getBoolean("sync_daily", true),
    syncOcr = preferences.getBoolean("sync_ocr", false),
    syncDerived = preferences.getBoolean("sync_derived", false),
    vaultLocked = preferences.getBoolean("vault_locked", false),
    vaultRequiresPassphrase = SharedSettingsHooks.platform?.vaultHasPassphrase() ?: false,
    transcriptStorageEnabled = preferences.getBoolean("transcript_storage", true),
    redactNamesEnabled = preferences.getBoolean("redact_names", true),
    includeFullTranscriptionInExports = preferences.getBoolean("full_transcript_exports", false),
    defaultImageQuestion = sharedDefaultImageQuestion(),
)

/** The image-question prompt from Settings, shared by chat attachments and glasses image questions. */
internal fun sharedDefaultImageQuestion(): String =
    createPlatformPreferences(SHARED_SETTINGS_PREFS)
        .getString("default_image_question", "")
        .takeUnless(LocalizedPromptDefaults::isDefaultImageQuestion)
        // Untouched defaults follow the app language (English system → English text, and so on).
        ?: LocalizedPromptDefaults.imageQuestion(platformLanguageTag())

private fun saveSharedSettings(preferences: PlatformPreferences, state: SettingsUiState) {
    preferences.putString("provider_type", state.providerType.name)
    preferences.putString("memory_mode", state.memoryMode.name)
    preferences.putBoolean("sync_explicit", state.syncExplicit)
    preferences.putBoolean("sync_daily", state.syncDaily)
    preferences.putBoolean("sync_ocr", state.syncOcr)
    preferences.putBoolean("sync_derived", state.syncDerived)
    preferences.putBoolean("vault_locked", state.vaultLocked)
    preferences.putBoolean("transcript_storage", state.transcriptStorageEnabled)
    preferences.putBoolean("redact_names", state.redactNamesEnabled)
    preferences.putBoolean("full_transcript_exports", state.includeFullTranscriptionInExports)
    preferences.putString("default_image_question", state.defaultImageQuestion)
}

internal fun ChatMessageEntity.toSharedMessage(): ChatMessage = ChatMessage(
    id = id,
    chatId = chatId,
    role = if (role.equals("user", ignoreCase = true)) ChatRole.USER else ChatRole.ASSISTANT,
    content = content,
    createdAt = timestamp,
)

@OptIn(ExperimentalResourceApi::class)
@Composable
internal fun sharedTimestampFormatter(): (Long) -> String {
    val unknown = stringResource(Res.string.time_unknown)
    val justNow = stringResource(Res.string.time_just_now)
    val minutesAgo = stringResource(Res.string.time_minutes_ago)
    val hoursAgo = stringResource(Res.string.time_hours_ago)
    val daysAgo = stringResource(Res.string.time_days_ago)
    return { timestamp ->
        formatSharedTimestamp(timestamp, unknown, justNow, minutesAgo, hoursAgo, daysAgo)
    }
}

private fun formatSharedTimestamp(
    timestamp: Long,
    unknown: String,
    justNow: String,
    minutesAgo: String,
    hoursAgo: String,
    daysAgo: String,
): String {
    if (timestamp <= 0L) return unknown
    val ageSeconds = ((platformCurrentTimeMillis() - timestamp) / 1000L).coerceAtLeast(0L)
    return when {
        ageSeconds < 60L -> justNow
        ageSeconds < 60L * 60L -> minutesAgo.replace("%1\$d", (ageSeconds / 60L).toString())
        ageSeconds < 24L * 60L * 60L -> hoursAgo.replace("%1\$d", (ageSeconds / (60L * 60L)).toString())
        else -> daysAgo.replace("%1\$d", (ageSeconds / (24L * 60L * 60L)).toString())
    }
}

internal fun sharedNowMillis(): Long = platformCurrentTimeMillis()

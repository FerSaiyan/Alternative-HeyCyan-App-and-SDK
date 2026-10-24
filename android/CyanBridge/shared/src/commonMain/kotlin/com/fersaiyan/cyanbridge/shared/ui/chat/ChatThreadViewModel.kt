package com.fersaiyan.cyanbridge.shared.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fersaiyan.cyanbridge.shared.chat.ChatAttachmentsUiState
import com.fersaiyan.cyanbridge.shared.chat.ChatComposerPrimaryAction
import com.fersaiyan.cyanbridge.shared.chat.ChatComposerUiState
import com.fersaiyan.cyanbridge.shared.chat.ChatMessage
import com.fersaiyan.cyanbridge.shared.chat.ChatModelInfo
import com.fersaiyan.cyanbridge.shared.chat.ChatRepository
import com.fersaiyan.cyanbridge.shared.chat.ChatRole
import com.fersaiyan.cyanbridge.shared.chat.ChatThreadEvent
import com.fersaiyan.cyanbridge.shared.chat.ChatThreadStateReducer
import com.fersaiyan.cyanbridge.shared.chat.ChatThreadUiState
import com.fersaiyan.cyanbridge.shared.chat.DailySummaryProgressUiState
import com.fersaiyan.cyanbridge.shared.platform.PlatformLogger
import com.fersaiyan.cyanbridge.shared.platform.platformCurrentTimeMillis
import com.fersaiyan.cyanbridge.shared.ui.UiMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** What a chat screen was opened with (Android: the Activity extras). */
data class ChatThreadArgs(
    val chatId: String? = null,
    /** Creates a thread with this title when [chatId] is null. */
    val createThreadTitle: String? = null,
    val prefill: String? = null,
    val dailyReview: Boolean = false,
    val dailyReviewDate: String? = null,
    val dailyReviewLookbackDays: Int? = null,
)

data class DailyReviewConfig(val date: String?, val lookbackDays: Int)

data class ChatReplyRequest(
    val chatId: String,
    val prompt: String,
    val imagePaths: List<String>,
    val audioPath: String?,
    /** Set in a daily review chat. */
    val review: DailyReviewConfig?,
)

/** Streaming callbacks for local models, called on the main thread. */
interface ChatReplyStream {
    fun onStatus(status: String)
    fun onToken(token: String)
    fun onMaxTokensReached() {}
}

/** Reply generation, appearance and platform details behind the chat screen. */
interface ChatThreadBackend {
    fun modelInfo(): ChatModelInfo

    /** User and assistant bubble colors (ARGB); null keeps the theme color. */
    fun bubbleColors(): Pair<Int?, Int?>
    suspend fun wallpaper(): ImageBitmap?

    /** The assistant reply to the newest user message of [ChatReplyRequest.chatId]. */
    suspend fun reply(request: ChatReplyRequest, stream: ChatReplyStream?): String

    /** A one-off completion without chat history (thread titles). */
    suspend fun textReply(prompt: String): String
    suspend fun cancelGeneration()

    /** Runs after a normal reply was stored (Android: memory extraction). */
    suspend fun onReplyStored(prompt: String, reply: String) {}
    fun relayUnavailableHint(text: String): String? = null

    /** The question sent when only media is attached. */
    val mediaPrompt: String get() = "Please analyze the attached media."

    fun formatUserMessage(text: String, imagePaths: List<String>, audioPath: String?): String {
        val suffix = buildString {
            if (imagePaths.isNotEmpty()) append("\n[Attached ${imagePaths.size} image(s)]")
            if (audioPath != null) append("\n[Attached voice note]")
        }
        return (text + suffix).trim()
    }

    /** Daily facts review chats; null where the platform has none. */
    val dailyReview: DailyReviewBackend? get() = null
}

/** The daily facts review protocol run inside a chat (Android). */
interface DailyReviewBackend {
    fun retentionDays(): Int
    fun savedConfig(chatId: String): DailyReviewConfig?
    fun save(chatId: String, date: String, lookbackDays: Int)

    /** The model's opening message for the review. */
    suspend fun kickoff(chatId: String, review: DailyReviewConfig, stream: ChatReplyStream?): String

    /** Applies the model's [raw] answer to the queue and stores the assistant messages. */
    suspend fun handleReply(chatId: String, review: DailyReviewConfig, userPrompt: String, raw: String)
    suspend fun queueStatus(date: String): String
}

/** Microphone capture of a voice note attached to the next message. */
interface ChatVoiceRecorder {
    /** False when the host has to ask for microphone permission first. */
    fun hasPermission(): Boolean

    /** Starts capturing; false when the microphone could not be opened. */
    suspend fun start(): Boolean

    /** Stops capturing; returns the recorded file when [keep] is set and audio was captured. */
    suspend fun stop(keep: Boolean): String?

    /** Stops and discards a running capture (the screen is gone). */
    fun release()
}

sealed interface ChatThreadEffect {
    data class Message(val message: UiMessage) : ChatThreadEffect
    data object PromptLocalModelSetup : ChatThreadEffect
    data object RequestMicrophonePermission : ChatThreadEffect

    /** A local model failed; the host may offer to send logs. */
    data class LocalGenerationFailed(val error: Throwable, val imageCount: Int, val hasAudio: Boolean) : ChatThreadEffect
}

class ChatThreadViewModel(
    private val repository: ChatRepository,
    private val backend: ChatThreadBackend,
    private val recorder: ChatVoiceRecorder,
    private val now: () -> Long = ::platformCurrentTimeMillis,
) : ViewModel() {
    var state by mutableStateOf(ChatThreadUiState())
        private set
    var composer by mutableStateOf(ChatComposerUiState())
        private set
    var modelBadge by mutableStateOf<String?>(null)
        private set
    var dailySummaryProgress by mutableStateOf<DailySummaryProgressUiState?>(null)
        private set
    var dailyReviewQueueStatus by mutableStateOf<String?>(null)
        private set
    var userBubbleColor by mutableStateOf<Int?>(null)
        private set
    var assistantBubbleColor by mutableStateOf<Int?>(null)
        private set
    var wallpaper by mutableStateOf<ImageBitmap?>(null)
        private set
    var isThinking by mutableStateOf(false)
        private set
    var chatId by mutableStateOf<String?>(null)
        private set
    var appearanceMenuVisible by mutableStateOf(false)
    var isRecording by mutableStateOf(false)
        private set
    private var pendingImages by mutableStateOf<List<String>>(emptyList())
    private var pendingAudio by mutableStateOf<String?>(null)

    val modelInfo: ChatModelInfo get() = backend.modelInfo()

    val visibleMessages: List<ChatMessage>
        get() = ChatThreadStateReducer.visibleMessages(state.messages, chatId.orEmpty(), state.streamingAssistantText, now())

    val attachments: ChatAttachmentsUiState
        get() {
            val imageCount = pendingImages.size
            val hasAudio = pendingAudio != null
            val label = if (imageCount == 0 && !hasAudio && !isRecording) {
                null
            } else {
                buildString {
                    if (imageCount > 0) append("$imageCount image(s)")
                    if (hasAudio) {
                        if (isNotBlank()) append(" · ")
                        append("voice note")
                    }
                    if (isRecording) {
                        if (isNotBlank()) append(" · ")
                        append("recording voice note")
                    }
                    append(" attached")
                }
            }
            return ChatAttachmentsUiState(label = label, isRecording = isRecording)
        }

    private val effectChannel = Channel<ChatThreadEffect>(Channel.BUFFERED)
    val effects = effectChannel.receiveAsFlow()

    private val review = backend.dailyReview
    private var isReview = false
    private var reviewDate: String? = null
    private var reviewLookbackDays = 1
    private val reviewConfig get() = DailyReviewConfig(reviewDate, reviewLookbackDays)

    private val threadMutex = Mutex()
    private val messageWriteMutex = Mutex()
    private var opened = false
    private var refreshRequestId = 0L
    private var pendingRequests = 0
    private var localGenerationRunning = false
    private var titleInProgress = false
    private val queuedLocalPrompts = ArrayDeque<ChatReplyRequest>()
    private var recordingTimeout: Job? = null

    /** Opens the screen's thread once; later calls (a recreated host) keep the current state. */
    fun openOnce(args: ChatThreadArgs) {
        if (opened) return
        open(args, kickoff = true)
    }

    /** Switches to the thread in [args]; with [kickoff] an empty daily review chat starts the review. */
    fun open(args: ChatThreadArgs, kickoff: Boolean) {
        opened = true
        isReview = args.dailyReview
        reviewDate = args.dailyReviewDate
        val retention = review?.retentionDays() ?: 1
        reviewLookbackDays = (args.dailyReviewLookbackDays ?: retention).coerceIn(1, retention.coerceAtLeast(1))
        chatId = args.chatId
        reduce(ChatThreadEvent.Loaded(thread = null, messages = emptyList()))
        dailyReviewQueueStatus = null
        args.prefill?.takeIf { it.isNotBlank() }?.let(::updateComposerText)
        refreshModelBadge("Ready")
        updateComposer()
        refreshAppearance()
        viewModelScope.launch {
            if (chatId == null) {
                args.createThreadTitle?.takeIf { it.isNotBlank() }
                    ?.let { title -> chatId = repository.createThread(title, now()).id }
            }
            restoreReviewConfig()
            registerReviewThread()
            if (chatId?.let { repository.getThread(it) } == null) chatId = null
            refreshMessages()
            refreshQueueStatus()
            val cid = chatId
            if (kickoff && isReview && cid != null && repository.listMessages(cid).isEmpty()) kickoffReview(cid)
        }
    }

    fun onResume() {
        refreshAppearance()
        refreshModelBadge("Ready")
        updateComposer()
        refreshMessages()
        refreshQueueStatus()
    }

    /** The provider or model changed (e.g. picked from the appearance menu). */
    fun refreshModel() {
        refreshModelBadge("Ready")
        updateComposer()
    }

    fun refreshAppearance() {
        val (user, assistant) = backend.bubbleColors()
        userBubbleColor = user
        assistantBubbleColor = assistant
        viewModelScope.launch { wallpaper = backend.wallpaper() }
    }

    fun updateComposerText(value: String) = reduce(ChatThreadEvent.ComposerTextChanged(value))

    fun addImageAttachment(path: String) {
        pendingImages = pendingImages + path
    }

    fun clearAttachments() {
        pendingImages = emptyList()
        pendingAudio = null
    }

    /** Shows the daily summary worker's progress; [bulletTotal] 0 hides it. */
    fun showDailySummaryProgress(bulletTotal: Int, bulletDone: Int = 0, stage: String? = null, etaMs: Long = 0L) {
        if (bulletTotal <= 0) {
            dailySummaryProgress = null
            return
        }
        val done = bulletDone.coerceIn(0, bulletTotal)
        val etaSeconds = (etaMs.coerceAtLeast(0L) / 1000L).coerceAtLeast(0L)
        val etaText = if (etaSeconds >= 60L) "~${etaSeconds / 60L}m ${etaSeconds % 60L}s" else "~${etaSeconds}s"
        val percent = ((done.toDouble() / bulletTotal.toDouble()) * 100.0).toInt().coerceIn(0, 100)
        dailySummaryProgress = DailySummaryProgressUiState(
            label = "Daily summary bullets $done/$bulletTotal · ${stage?.trim().orEmpty().ifBlank { "Queued" }} · ETA $etaText",
            progress = percent / 100f,
        )
    }

    fun onPrimaryAction() {
        if (localGenerationRunning) {
            viewModelScope.launch { backend.cancelGeneration() }
            return
        }
        val hasMedia = pendingImages.isNotEmpty() || pendingAudio != null
        val model = backend.modelInfo()
        if (model.localModelMissing && !hasMedia) {
            effectChannel.trySend(ChatThreadEffect.PromptLocalModelSetup)
            return
        }
        val text = state.composerText.trim()
        viewModelScope.launch {
            if (isRecording) stopRecording(keep = true)
            if (text.isNotEmpty() || hasMedia) {
                send(text, queue = model.usesLocalModels && titleInProgress && !isReview)
                updateComposerText("")
            }
        }
    }

    fun onRecordAudio() {
        when {
            isRecording -> viewModelScope.launch { stopRecording(keep = true) }
            recorder.hasPermission() -> startRecording()
            else -> effectChannel.trySend(ChatThreadEffect.RequestMicrophonePermission)
        }
    }

    /** Starts a voice note once microphone permission is granted. */
    fun startRecording() {
        if (isRecording) return
        if (!recorder.hasPermission()) {
            effectChannel.trySend(ChatThreadEffect.RequestMicrophonePermission)
            return
        }
        viewModelScope.launch {
            if (!recorder.start()) return@launch notify("Unable to start recording")
            isRecording = true
            notify("Recording... up to 30 seconds")
            recordingTimeout = viewModelScope.launch {
                delay(MAX_VOICE_NOTE_MILLIS)
                stopRecording(keep = true)
            }
        }
    }

    private suspend fun stopRecording(keep: Boolean) {
        recordingTimeout?.cancel()
        recordingTimeout = null
        if (!isRecording) return
        isRecording = false
        val path = recorder.stop(keep)
        if (!keep) return
        if (path != null) {
            pendingAudio = path
            notify("Voice note attached")
        } else {
            notify("Unable to attach voice note")
        }
    }

    private fun send(text: String, queue: Boolean) {
        val images = pendingImages
        val audio = pendingAudio
        val prompt = text.ifBlank { if (images.isNotEmpty() || audio != null) backend.mediaPrompt else "" }
        if (prompt.isBlank()) return
        clearAttachments()
        viewModelScope.launch {
            val (cid, isFirstMessage) = messageWriteMutex.withLock {
                val cid = ensureChatThread()
                val isFirst = repository.listMessages(cid).isEmpty()
                repository.addMessage(cid, ChatRole.USER, backend.formatUserMessage(prompt, images, audio), now())
                cid to isFirst
            }
            refreshMessages()
            val request = ChatReplyRequest(cid, prompt, images, audio, reviewConfig.takeIf { isReview })
            if (queue) {
                queuedLocalPrompts.addLast(request)
                refreshModelBadge("Queued")
                notify("Queued. Sending right after title update...")
            } else {
                startGeneration(request, requestTitle = isFirstMessage)
            }
        }
    }

    private suspend fun ensureChatThread(): String = threadMutex.withLock {
        chatId?.let { return@withLock it }
        val created = repository.createThread(title = null, nowMs = now())
        chatId = created.id
        registerReviewThread()
        reduce(ChatThreadEvent.ThreadChanged(created))
        created.id
    }

    private fun startGeneration(request: ChatReplyRequest, requestTitle: Boolean, onFinished: (() -> Unit)? = null) {
        viewModelScope.launch {
            requestStarted()
            val useLocalStreaming = backend.modelInfo().usesLocalModels
            var maxTokensReached = false
            if (useLocalStreaming) beginLocalGeneration()
            try {
                val stream = if (useLocalStreaming) {
                    tokenStream(onMaxTokens = {
                        maxTokensReached = true
                        notify("Reply capped at your max output tokens setting.")
                    })
                } else {
                    null
                }
                val reply = backend.reply(request, stream)
                showRelayHint(reply)

                if (request.review != null) {
                    review?.handleReply(request.chatId, request.review, request.prompt, reply)
                    refreshQueueStatus()
                } else {
                    val streamed = state.streamingAssistantText.orEmpty().trim()
                    val finalReply = when {
                        useLocalStreaming && streamed.isNotBlank() -> streamed
                        reply.isNotBlank() -> reply
                        streamed.isNotBlank() -> streamed
                        useLocalStreaming -> "I couldn't generate a reply yet. Loading the model and retrying can take a moment; please try again."
                        else -> "I could not generate a reply."
                    }
                    val stored = if (maxTokensReached && finalReply.isNotBlank()) {
                        finalReply.trimEnd() + "\n\n[Stopped at max output tokens configured in Local Models.]"
                    } else {
                        finalReply
                    }
                    repository.addMessage(request.chatId, ChatRole.ASSISTANT, stored, now())
                    reduce(ChatThreadEvent.GenerationFinished())
                    if (requestTitle) generateTitle(request.chatId, request.prompt)
                    viewModelScope.launch { backend.onReplyStored(request.prompt, stored) }
                }
                refreshMessages()
            } catch (_: CancellationException) {
                val partial = state.streamingAssistantText.orEmpty().trim()
                if (partial.isNotBlank()) {
                    withContext(NonCancellable) { repository.addMessage(request.chatId, ChatRole.ASSISTANT, partial, now()) }
                    reduce(ChatThreadEvent.GenerationFinished())
                    refreshMessages()
                }
                notify("Generation stopped")
            } catch (t: Throwable) {
                PlatformLogger.e(TAG, "Assistant generation failed", t)
                if (useLocalStreaming) {
                    effectChannel.send(ChatThreadEffect.LocalGenerationFailed(t, request.imagePaths.size, request.audioPath != null))
                }
                showRelayHint(t.message)
                notify(t.message ?: "Failed to get response")
            } finally {
                endGeneration()
                onFinished?.invoke()
            }
        }
    }

    private fun kickoffReview(cid: String) {
        val review = review ?: return
        val config = reviewConfig
        viewModelScope.launch {
            requestStarted()
            val useLocalStreaming = backend.modelInfo().usesLocalModels
            if (useLocalStreaming) beginLocalGeneration()
            try {
                val reply = review.kickoff(cid, config, if (useLocalStreaming) tokenStream(onMaxTokens = null) else null)
                showRelayHint(reply)
                review.handleReply(cid, config, "Start daily facts review", reply)
                refreshQueueStatus()
                refreshMessages()
            } catch (t: Throwable) {
                showRelayHint(t.message)
                notify(t.message ?: "Failed to start daily facts review")
            } finally {
                endGeneration()
            }
        }
    }

    private fun beginLocalGeneration() {
        localGenerationRunning = true
        reduce(ChatThreadEvent.GenerationStarted())
        updateComposer()
        refreshModelBadge("Loading")
        refreshMessages()
    }

    private fun endGeneration() {
        localGenerationRunning = false
        reduce(ChatThreadEvent.GenerationFinished())
        updateComposer()
        refreshModelBadge("Ready")
        refreshMessages()
        requestFinished()
    }

    /** Streams tokens into the pending bubble; without [onMaxTokens] every status goes to the badge. */
    private fun tokenStream(onMaxTokens: (() -> Unit)?) = object : ChatReplyStream {
        override fun onStatus(status: String) = refreshModelBadge(status)

        override fun onToken(token: String) =
            reduce(ChatThreadEvent.StreamUpdated(state.streamingAssistantText.orEmpty() + token))

        override fun onMaxTokensReached() = onMaxTokens?.invoke() ?: Unit
    }

    private fun generateTitle(chatId: String, firstUserPrompt: String) {
        val usesLocalModels = backend.modelInfo().usesLocalModels
        if (usesLocalModels) {
            titleInProgress = true
            refreshModelBadge("Choosing title...")
        }
        requestStarted()
        viewModelScope.launch {
            try {
                val suggested = runCatching { backend.textReply(titlePrompt(firstUserPrompt)) }.getOrNull()
                if (repository.updateThreadTitle(chatId, sanitizeThreadTitle(suggested, firstUserPrompt), now())) {
                    refreshMessages()
                }
            } finally {
                if (usesLocalModels) {
                    titleInProgress = false
                    refreshModelBadge("Ready")
                    drainQueuedPrompts()
                }
                requestFinished()
            }
        }
    }

    private fun drainQueuedPrompts() {
        if (localGenerationRunning || titleInProgress) return
        val next = queuedLocalPrompts.removeFirstOrNull() ?: return
        startGeneration(next, requestTitle = false, onFinished = ::drainQueuedPrompts)
    }

    private fun requestStarted() {
        pendingRequests += 1
        isThinking = pendingRequests > 0
    }

    private fun requestFinished() {
        pendingRequests = (pendingRequests - 1).coerceAtLeast(0)
        isThinking = pendingRequests > 0
    }

    private fun refreshModelBadge(status: String) {
        modelBadge = backend.modelInfo().badge(status)
    }

    private fun updateComposer() {
        composer = when {
            localGenerationRunning -> ChatComposerUiState(
                isTextInputEnabled = false,
                isMediaEnabled = false,
                primaryAction = ChatComposerPrimaryAction.STOP_GENERATION,
            )
            backend.modelInfo().localModelMissing -> ChatComposerUiState(
                hint = "Install/select a local model to start chatting",
                isTextInputEnabled = true,
                isMediaEnabled = false,
                primaryAction = ChatComposerPrimaryAction.CONFIGURE_LOCAL_MODEL,
            )
            // Media stays actionable so unsupported providers can explain their requirement.
            else -> ChatComposerUiState(isTextInputEnabled = true, isMediaEnabled = true)
        }
    }

    private suspend fun restoreReviewConfig() {
        val cid = chatId ?: return
        if (!isReview) {
            review?.savedConfig(cid)?.let { stored ->
                isReview = true
                reviewDate = stored.date
                reviewLookbackDays = stored.lookbackDays.coerceAtLeast(1)
                return
            }
        }
        if (isReview && reviewDate.isNullOrBlank()) {
            val title = repository.getThread(cid)?.title.orEmpty()
            Regex("Daily review \\((\\d{4}-\\d{2}-\\d{2})\\)").find(title)?.groupValues?.getOrNull(1)
                ?.takeIf { it.isNotBlank() }
                ?.let { reviewDate = it }
        }
    }

    private fun registerReviewThread() {
        if (!isReview) return
        val cid = chatId ?: return
        val date = reviewDate?.trim().orEmpty()
        if (date.isBlank()) return
        review?.save(cid, date, reviewLookbackDays)
    }

    fun refreshMessages() {
        val cid = chatId
        val requestId = ++refreshRequestId
        if (cid == null) {
            reduce(ChatThreadEvent.Loaded(thread = null, messages = emptyList()))
            dailyReviewQueueStatus = null
            return
        }
        viewModelScope.launch {
            val thread = repository.getThread(cid)
            val stored = repository.listMessages(cid)
            if (requestId != refreshRequestId || cid != chatId) return@launch
            reduce(ChatThreadEvent.ThreadChanged(thread))
            reduce(
                ChatThreadEvent.MessagesChanged(
                    stored.filterNot { it.role == ChatRole.ASSISTANT && it.content.startsWith(DAILY_REVIEW_DEBUG_PREFIX) },
                ),
            )
        }
    }

    private fun refreshQueueStatus() {
        val review = review
        val date = reviewDate?.trim().orEmpty()
        if (!isReview || review == null || date.isBlank()) {
            dailyReviewQueueStatus = null
            return
        }
        val expectedChatId = chatId
        viewModelScope.launch {
            val text = review.queueStatus(date)
            if (isReview && expectedChatId == chatId && date == reviewDate?.trim()) dailyReviewQueueStatus = text
        }
    }

    private fun showRelayHint(text: String?) {
        backend.relayUnavailableHint(text.orEmpty())?.let { notify(it, long = true) }
    }

    private fun reduce(event: ChatThreadEvent) {
        state = ChatThreadStateReducer.reduce(state, event)
    }

    private fun notify(text: String, long: Boolean = false) {
        effectChannel.trySend(ChatThreadEffect.Message(UiMessage(text, long)))
    }

    override fun onCleared() {
        recordingTimeout?.cancel()
        if (isRecording) recorder.release()
    }

    companion object {
        private const val TAG = "ChatThreadActivity"
        private const val MAX_VOICE_NOTE_MILLIS = 30_000L

        /** Prefix of the hidden protocol messages a daily review stores for debugging. */
        const val DAILY_REVIEW_DEBUG_PREFIX = "[DEBUG_DAILY_REVIEW]"

        private fun titlePrompt(firstUserPrompt: String) = buildString {
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

        /** First line of the model's suggestion, cleaned to at most six words, else the prompt's. */
        fun sanitizeThreadTitle(candidate: String?, fallbackPrompt: String): String {
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
            val normalized = raw.replace(Regex("\\s+"), " ").replace(Regex("[\\r\\n\\t]"), " ").trim()
            val looksLikeDemo = normalized.startsWith("Demo mode reply:", ignoreCase = true)
            val clean = if (normalized.isBlank() || looksLikeDemo) fallbackPrompt else normalized
            val words = clean.trim().trimEnd('.', '!', '?', ':', ';', ',')
                .split(Regex("\\s+"))
                .filter { it.isNotBlank() }
                .take(6)
            return words.joinToString(" ").trim().ifBlank { "New chat" }.take(48)
        }
    }
}

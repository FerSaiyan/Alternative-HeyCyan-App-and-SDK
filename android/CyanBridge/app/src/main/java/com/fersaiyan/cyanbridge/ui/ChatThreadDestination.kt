package com.fersaiyan.cyanbridge.ui

import com.fersaiyan.cyanbridge.ui.nav.NavScreen
import com.fersaiyan.cyanbridge.ui.nav.AppRoute
import android.Manifest
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import com.fersaiyan.cyanbridge.ui.nav.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.fersaiyan.cyanbridge.agent.LocalModelsConfigureActivity
import com.fersaiyan.cyanbridge.agent.ProSubscriptionAiPrefs
import com.fersaiyan.cyanbridge.agent.ProSubscriptionRelayClient
import com.fersaiyan.cyanbridge.chat.ChatStore
import com.fersaiyan.cyanbridge.chat.ChatStoreRepository
import com.fersaiyan.cyanbridge.localagent.dailysummary.DailySummaryRegenerateWorker
import com.fersaiyan.cyanbridge.localmodels.storage.LocalModelStorageRepository
import com.fersaiyan.cyanbridge.shared.chat.ChatProviderKind
import com.fersaiyan.cyanbridge.shared.chat.ChatRole
import com.fersaiyan.cyanbridge.shared.navigation.AppDestination
import com.fersaiyan.cyanbridge.shared.ui.chat.ChatAppearanceMenuDialog
import com.fersaiyan.cyanbridge.shared.ui.chat.ChatThreadArgs
import com.fersaiyan.cyanbridge.shared.ui.chat.ChatThreadEffect
import com.fersaiyan.cyanbridge.shared.ui.chat.ChatThreadScreen
import com.fersaiyan.cyanbridge.shared.ui.chat.ChatThreadViewModel
import com.fersaiyan.cyanbridge.ui.chat.AndroidChatThreadBackend
import com.fersaiyan.cyanbridge.ui.chat.ChatAppearanceMenu
import com.fersaiyan.cyanbridge.ui.chat.ChatVoiceNoteRecorder
import com.fersaiyan.cyanbridge.ui.chat.chatAttachmentDir
import com.fersaiyan.cyanbridge.ui.debug.DebugLogSupport
import com.fersaiyan.cyanbridge.ui.main.openAppDestination
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChatThreadDestination : NavScreen() {
    private val viewModel by viewModels<ChatThreadViewModel> {
        viewModelFactory {
            initializer {
                ChatThreadViewModel(
                    repository = ChatStoreRepository(),
                    backend = AndroidChatThreadBackend(applicationContext),
                    recorder = ChatVoiceNoteRecorder(applicationContext),
                )
            }
        }
    }
    private val appearanceMenu = ChatAppearanceMenu(this) { viewModel.refreshAppearance() }

    private val pickChatImageLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        runCatching { viewModel.addImageAttachment(copyImageToChatAttachment(uri).absolutePath) }
            .onFailure { Toast.makeText(this, "Failed to attach image", Toast.LENGTH_SHORT).show() }
    }

    private val requestAudioPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            viewModel.startRecording()
        } else {
            Toast.makeText(this, "Microphone permission is required to record audio", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.openOnce(intent.toChatArgs())
        lifecycleScope.launch { viewModel.effects.collect(::handleEffect) }
        setContent {
            val vm = viewModel
            ChatThreadScreen(
                state = vm.state,
                messages = vm.visibleMessages,
                composer = vm.composer,
                attachments = vm.attachments,
                modelBadge = vm.modelBadge,
                dailySummaryProgress = vm.dailySummaryProgress,
                dailyReviewQueueStatus = vm.dailyReviewQueueStatus,
                userBubbleColor = vm.userBubbleColor,
                assistantBubbleColor = vm.assistantBubbleColor,
                wallpaper = vm.wallpaper,
                isThinking = vm.isThinking,
                onOpenChatList = ::openChatList,
                onChatAppearance = { vm.appearanceMenuVisible = true },
                onComposerTextChanged = vm::updateComposerText,
                onPrimaryAction = vm::onPrimaryAction,
                onAttachImage = { pickChatImageLauncher.launch(arrayOf("image/*")) },
                onRecordAudio = vm::onRecordAudio,
                onClearAttachments = vm::clearAttachments,
                onDestinationSelected = { openAppDestination(it, ::chatsTabIntent) },
            )
            if (vm.appearanceMenuVisible) {
                ChatAppearanceMenuDialog(
                    modelOptionLabel = vm.modelInfo.changeModelLabel,
                    onDismissRequest = { vm.appearanceMenuVisible = false },
                    onAction = { action ->
                        vm.appearanceMenuVisible = false
                        appearanceMenu.handle(action, onChangeModel = ::showModelPickerForProvider)
                    },
                )
            }
        }
        observeDailySummaryQueueProgress()
    }

    override fun onResume() {
        super.onResume()
        viewModel.onResume()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        viewModel.open(intent.toChatArgs(), kickoff = false)
    }

    private fun Intent.toChatArgs() = ChatThreadArgs(
        chatId = getStringExtra(EXTRA_CHAT_ID),
        createThreadTitle = getStringExtra(EXTRA_CREATE_THREAD_TITLE),
        prefill = getStringExtra(EXTRA_PREFILL_MESSAGE),
        dailyReview = getBooleanExtra(EXTRA_DAILY_FACTS_REVIEW, false),
        dailyReviewDate = getStringExtra(EXTRA_DAILY_FACTS_DATE),
        dailyReviewLookbackDays = if (hasExtra(EXTRA_DAILY_FACTS_LOOKBACK_DAYS)) getIntExtra(EXTRA_DAILY_FACTS_LOOKBACK_DAYS, 1) else null,
    )

    private fun handleEffect(effect: ChatThreadEffect) {
        when (effect) {
            is ChatThreadEffect.Message -> showToast(effect.message)
            ChatThreadEffect.PromptLocalModelSetup -> promptLocalModelSetup()
            ChatThreadEffect.RequestMicrophonePermission -> requestAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            is ChatThreadEffect.LocalGenerationFailed -> if (DebugLogSupport.isLocalRuntimeIssue(effect.error.message, effect.error)) {
                DebugLogSupport.showSupportOptionsDialog(
                    activity = activity,
                    title = "Local runtime issue",
                    issueType = "Local runtime issue",
                    description = "CyanBridge hit a local model runtime error while generating this reply.",
                    extraInfo = linkedMapOf(
                        "screen" to "chat_thread",
                        "image_attachments" to effect.imageCount.toString(),
                        "has_audio_attachment" to effect.hasAudio.toString(),
                    ),
                )
            }
        }
    }

    private fun openChatList() {
        startActivity(AppRoute.CHAT_LIST.intent(this).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }

    /** The Chats tab stays on this chat while its user wrote in the last 30 minutes, else starts a new one. */
    private fun chatsTabIntent(): Intent? {
        val lastUserAt = viewModel.chatId?.let(ChatStore::listMessages)?.lastOrNull { it.role == ChatRole.USER }?.createdAt
        if (lastUserAt != null && System.currentTimeMillis() - lastUserAt < 30 * 60 * 1000) return null
        return Intent(this, ChatThreadDestination::class.java)
    }

    private fun showModelPickerForProvider() {
        when (viewModel.modelInfo.kind) {
            ChatProviderKind.REMOTE_OPENAI -> startActivity(Intent(this, LocalModelsConfigureActivity::class.java))
            ChatProviderKind.LOCAL -> showLocalModelPicker()
            ChatProviderKind.RELAY -> showRelayModelPicker()
            ChatProviderKind.OTHER ->
                Toast.makeText(this, "Current provider does not support model selection", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showLocalModelPicker() {
        val installed = LocalModelStorageRepository.listInstalled(this)
        if (installed.isEmpty()) {
            promptLocalModelSetup()
            return
        }
        val selectedId = LocalModelStorageRepository.getSelectedModelId(this)
        val labels = installed.map { if (it.id == selectedId) "${it.displayName} (Current)" else it.displayName }
        AlertDialog.Builder(this)
            .setTitle("Select local model")
            .setItems(labels.toTypedArray()) { _, which ->
                val picked = installed.getOrNull(which) ?: return@setItems
                LocalModelStorageRepository.setSelectedModelId(this, picked.id)
                viewModel.refreshModel()
                Toast.makeText(this, "Using ${picked.displayName}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showRelayModelPicker() {
        val current = ProSubscriptionAiPrefs.getRequestsModel(this)
        lifecycleScope.launch {
            val options = withContext(Dispatchers.IO) {
                val fetched = ProSubscriptionRelayClient.fetchAvailableModels(this@ChatThreadDestination).getOrElse { emptyList() }
                val merged = linkedMapOf("auto" to "auto")
                fetched.forEach { option ->
                    val id = option.id.trim()
                    if (id.isNotBlank() && !merged.containsKey(id)) merged[id] = option.label.trim().ifBlank { id }
                }
                if (current.isNotBlank() && !merged.containsKey(current)) merged[current] = current
                merged.entries.toList()
            }
            val labels = options.map { (id, label) -> if (id.equals(current, ignoreCase = true)) "$label (Current)" else label }
            AlertDialog.Builder(this@ChatThreadDestination)
                .setTitle("Select relay model")
                .setItems(labels.toTypedArray()) { _, which ->
                    val (pickedId, pickedLabel) = options.getOrNull(which) ?: return@setItems
                    ProSubscriptionAiPrefs.setRequestsModel(this@ChatThreadDestination, pickedId)
                    viewModel.refreshModel()
                    Toast.makeText(this@ChatThreadDestination, "Chat model set to $pickedLabel", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
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

    private fun observeDailySummaryQueueProgress() {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(System.currentTimeMillis()))
        WorkManager.getInstance(this)
            .getWorkInfosForUniqueWorkLiveData(DailySummaryRegenerateWorker.uniqueWorkName(today))
            .observe(this) { infos ->
                val info = infos.firstOrNull()
                if (info == null || (info.state != WorkInfo.State.ENQUEUED && info.state != WorkInfo.State.RUNNING)) {
                    viewModel.showDailySummaryProgress(bulletTotal = 0)
                } else {
                    viewModel.showDailySummaryProgress(
                        bulletTotal = info.progress.getInt(DailySummaryRegenerateWorker.KEY_BULLET_TOTAL, 0),
                        bulletDone = info.progress.getInt(DailySummaryRegenerateWorker.KEY_BULLET_DONE, 0),
                        stage = info.progress.getString(DailySummaryRegenerateWorker.KEY_STAGE),
                        etaMs = info.progress.getLong(DailySummaryRegenerateWorker.KEY_ETA_MS, 0L),
                    )
                }
            }
    }

    /** Re-encodes a picked image as JPEG in the attachment folder (or copies it when it can't be decoded). */
    private fun copyImageToChatAttachment(uri: Uri): File {
        val decodedBitmap = runCatching {
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
        }.getOrNull()
        if (decodedBitmap != null) {
            val out = File(chatAttachmentDir(this), "image_${System.currentTimeMillis()}.jpg")
            FileOutputStream(out).use { decodedBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, it) }
            decodedBitmap.recycle()
            return out
        }
        val out = File(chatAttachmentDir(this), "image_${System.currentTimeMillis()}.${attachmentExtension(uri)}")
        contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(out).use { input.copyTo(it) }
        } ?: throw IllegalStateException("Cannot open attachment")
        return out
    }

    private fun attachmentExtension(uri: Uri, fallback: String = "jpg"): String {
        val mimeExt = contentResolver.getType(uri)
            ?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
            ?.lowercase(Locale.US)
            .orEmpty()
        val pathExt = uri.lastPathSegment?.substringAfterLast('.', "")?.lowercase(Locale.US).orEmpty()
        val chosen = mimeExt.ifBlank { pathExt.ifBlank { fallback } }
        return chosen.replace(Regex("[^a-z0-9]"), "").ifBlank { fallback }.take(8)
    }

    companion object {
        const val EXTRA_CHAT_ID = "chat_id"

        // If EXTRA_CHAT_ID is missing, ChatThreadDestination can create a new thread.
        const val EXTRA_CREATE_THREAD_TITLE = "create_thread_title"

        // Optional: prefill the composer input box.
        const val EXTRA_PREFILL_MESSAGE = "prefill_message"

        // Daily facts review mode
        const val EXTRA_DAILY_FACTS_REVIEW = "daily_facts_review"
        const val EXTRA_DAILY_FACTS_DATE = "daily_facts_date"
        const val EXTRA_DAILY_FACTS_LOOKBACK_DAYS = "daily_facts_lookback_days"
    }
}

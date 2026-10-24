package com.fersaiyan.cyanbridge.shared.platform

import com.fersaiyan.cyanbridge.shared.chat.ExternalChatExportParser
import com.fersaiyan.cyanbridge.shared.memoryvault.crypto.VaultCrypto
import com.fersaiyan.cyanbridge.shared.persistence.ChatEntity
import com.fersaiyan.cyanbridge.shared.persistence.ChatMessageEntity
import com.fersaiyan.cyanbridge.shared.persistence.ChatRepository
import com.fersaiyan.cyanbridge.shared.persistence.MediaRecordRepository
import com.fersaiyan.cyanbridge.shared.persistence.NoteEntity
import com.fersaiyan.cyanbridge.shared.persistence.NotesRepository
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import platform.Foundation.NSData
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.dataWithContentsOfURL
import platform.UIKit.UIAlertAction
import platform.UIKit.UIAlertActionStyleCancel
import platform.UIKit.UIAlertActionStyleDefault
import platform.UIKit.UIAlertController
import platform.UIKit.UIAlertControllerStyleAlert
import platform.UIKit.UIDevice
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UITextField
import platform.UniformTypeIdentifiers.UTTypeJSON
import platform.darwin.NSObject
import kotlin.coroutines.resume

/** Settings actions for the iOS host (Android: SettingsActivity and its helpers). */
@OptIn(ExperimentalForeignApi::class)
class IosSettingsPlatform(
    private val chatRepository: ChatRepository,
    private val notesRepository: NotesRepository,
    private val mediaRecordRepository: MediaRecordRepository,
    private val meetingRecorder: IosMeetingRecorder,
    private val relayBaseUrl: String,
) : SharedSettingsPlatform {
    @Serializable
    private data class BackupChat(val chat: BackupChatInfo, val messages: List<BackupMessage>)

    @Serializable
    private data class BackupChatInfo(val id: String, val title: String, val createdAt: Long, val updatedAt: Long)

    @Serializable
    private data class BackupMessage(val id: String, val role: String, val content: String, val timestamp: Long)

    @Serializable
    private data class BackupNote(
        val id: String,
        val title: String,
        val content: String,
        val createdAt: Long,
        val updatedAt: Long,
        val source: String? = null,
    )

    @Serializable
    private data class Backup(
        val format: String = BACKUP_FORMAT,
        val exportedAt: Long,
        val chats: List<BackupChat>,
        val notes: List<BackupNote>,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private var pickerDelegate: DocumentPickerDelegate? = null

    override fun openAppLanguageSettings() {
        val presenter = IosMediaPlatform.topViewController() ?: return
        val alert = UIAlertController.alertControllerWithTitle("Language", null, UIAlertControllerStyleAlert)
        IosAppLanguage.options.forEach { option ->
            val title = if (option.id == IosAppLanguage.selectedId) "✓ ${option.label}" else option.label
            alert.addAction(
                UIAlertAction.actionWithTitle(title, UIAlertActionStyleDefault) { _ -> IosAppLanguage.select(option.id) },
            )
        }
        alert.addAction(UIAlertAction.actionWithTitle("Cancel", UIAlertActionStyleCancel, handler = null))
        presenter.presentViewController(alert, animated = true, completion = null)
    }

    override fun appLanguageLabel(): String = IosAppLanguage.selectedLabel

    override fun openLocalModels() = IosLocalModels.open()

    override fun exportLocalData() {
        scope.launch {
            val chats = chatRepository.getAllChats().map { chat ->
                BackupChat(
                    chat = BackupChatInfo(chat.id, chat.title, chat.createdAt, chat.updatedAt),
                    messages = chatRepository.getMessages(chat.id).map { BackupMessage(it.id, it.role, it.content, it.timestamp) },
                )
            }
            val notes = notesRepository.getAllNotes().map {
                BackupNote(it.id, it.title, it.content, it.createdAt, it.updatedAt, it.source)
            }
            val backup = Backup(exportedAt = platformCurrentTimeMillis(), chats = chats, notes = notes)
            val path = NSTemporaryDirectory() + "cyanbridge-backup-${platformCurrentTimeMillis()}.json"
            if (IosMediaPlatform.writeFile(path, json.encodeToString(backup).encodeToByteArray())) {
                IosMediaPlatform.shareMedia(listOf(path))
            } else {
                showMessage("Export failed", "Could not write the backup file.")
            }
        }
    }

    override fun importLocalData() {
        scope.launch {
            val text = pickJsonFile() ?: return@launch
            val backup = runCatching { json.decodeFromString<Backup>(text) }.getOrNull()
            if (backup == null || backup.format != BACKUP_FORMAT) {
                showMessage("Import failed", "This file is not a CyanBridge backup.")
                return@launch
            }
            val existingChats = chatRepository.getAllChats().map { it.id }.toSet()
            var importedChats = 0
            backup.chats.filter { it.chat.id !in existingChats }.forEach { item ->
                chatRepository.insertChat(ChatEntity(item.chat.id, item.chat.title, item.chat.createdAt, item.chat.updatedAt))
                item.messages.forEach { message ->
                    chatRepository.insertMessage(
                        ChatMessageEntity(message.id, item.chat.id, message.role, message.content, message.timestamp),
                    )
                }
                importedChats++
            }
            val existingNotes = notesRepository.getAllNotes().map { it.id }.toSet()
            val newNotes = backup.notes.filter { it.id !in existingNotes }
            newNotes.forEach { note ->
                notesRepository.insertNote(NoteEntity(note.id, note.title, note.content, note.createdAt, note.updatedAt, note.source))
            }
            showMessage("Import complete", "$importedChats chats and ${newNotes.size} notes imported.")
        }
    }

    override fun importChatGptData() = importExternal(ExternalChatExportParser.Provider.CHATGPT)

    override fun importClaudeData() = importExternal(ExternalChatExportParser.Provider.CLAUDE)

    private fun importExternal(provider: ExternalChatExportParser.Provider) {
        scope.launch {
            val text = pickJsonFile() ?: return@launch
            val conversations = runCatching { ExternalChatExportParser.parse(text, provider) }.getOrElse {
                showMessage("Import failed", "Choose the conversations.json file from your ${provider.label} export.")
                return@launch
            }
            val existing = chatRepository.getAllChats().map { it.id }.toSet()
            val prefix = provider.name.lowercase()
            var imported = 0
            conversations.forEach { conversation ->
                val chatId = "$prefix-${conversation.id}"
                if (chatId in existing) return@forEach
                val createdAt = conversation.turns.firstOrNull { it.timestampMs > 0 }?.timestampMs ?: platformCurrentTimeMillis()
                val updatedAt = conversation.turns.maxOf { it.timestampMs }.takeIf { it > 0 } ?: createdAt
                chatRepository.insertChat(ChatEntity(chatId, conversation.title, createdAt, updatedAt))
                conversation.turns.forEachIndexed { index, turn ->
                    chatRepository.insertMessage(
                        ChatMessageEntity(
                            id = "$chatId-$index",
                            chatId = chatId,
                            role = turn.role,
                            content = turn.text,
                            timestamp = turn.timestampMs.takeIf { it > 0 } ?: (createdAt + index),
                        ),
                    )
                }
                imported++
            }
            showMessage("Import complete", "$imported ${provider.label} conversations imported.")
        }
    }

    override fun clearLocalData() {
        scope.launch {
            val confirmed = IosMediaPlatform.confirm(
                title = "Clear all local data?",
                message = "Chats, notes, synced media and meeting recordings on this iPhone will be deleted.",
                confirmTitle = "Delete",
                cancelTitle = "Cancel",
            )
            if (!confirmed) return@launch
            chatRepository.getAllChats().forEach { chat ->
                chatRepository.deleteMessagesForChat(chat.id)
                chatRepository.deleteChat(chat.id)
            }
            notesRepository.getAllNotes().forEach { notesRepository.deleteNote(it.id) }
            mediaRecordRepository.getAll().forEach { IosMediaPlatform.deleteFile(it.filePath) }
            mediaRecordRepository.deleteAll()
            meetingRecorder.deleteAll()
            showMessage("Local data cleared", "All local chats, notes, media and recordings were deleted.")
        }
    }

    override fun sendDebugLogs() {
        scope.launch {
            val description = promptText(
                title = "Send debug logs",
                message = "Describe the problem (Bluetooth, sync, voice, app).",
                placeholder = "What happened?",
                secure = false,
            ) ?: return@launch
            val device = UIDevice.currentDevice
            val payload = buildJsonObject {
                put("issue_type", "ios")
                put("description", description)
                put("logs", PlatformLogger.recentLogs())
                put("device_info", "${device.model} · ${device.systemName} ${device.systemVersion}")
                put("app_version", appVersion())
            }.toString()
            val sent = runCatching {
                PlatformHttpClient().post(
                    "$relayBaseUrl/logs/submit",
                    payload,
                    mapOf("Content-Type" to "application/json; charset=utf-8"),
                ).isSuccessful
            }.getOrDefault(false)
            showMessage(
                if (sent) "Logs sent" else "Could not send logs",
                if (sent) "Thanks! The developer received your report." else "Check your internet connection and try again.",
            )
        }
    }

    override fun stopMeetingCapture() = meetingRecorder.stopMeetingCapture()

    // ── Vault passphrase (PBKDF2 verifier in the Keychain) ──

    override fun vaultHasPassphrase(): Boolean = IosSecurityRegistry.bridge?.keychainGet(KEY_VERIFIER) != null

    override suspend fun setVaultPassphrase(): Boolean {
        val first = promptText("Set vault passphrase", "Use at least 8 characters.", "Passphrase", secure = true)
            ?: return false
        if (first.length < 8) {
            showMessage("Passphrase too short", "Use at least 8 characters.")
            return false
        }
        val second = promptText("Confirm passphrase", null, "Passphrase", secure = true) ?: return false
        if (first != second) {
            showMessage("Passphrases do not match", "Try again.")
            return false
        }
        val bridge = IosSecurityRegistry.bridge ?: return false
        val salt = VaultCrypto.randomBytes(16)
        val verifier = VaultCrypto.derivePassphraseKey(first.toCharArray(), salt)
        return bridge.keychainSet(KEY_SALT, salt.toNSData()) && bridge.keychainSet(KEY_VERIFIER, verifier.toNSData())
    }

    override suspend fun clearVaultPassphrase(): Boolean {
        if (!verifyPassphrase("Remove vault passphrase")) return false
        IosSecurityRegistry.bridge?.let {
            it.keychainDelete(KEY_SALT)
            it.keychainDelete(KEY_VERIFIER)
        }
        return true
    }

    override suspend fun unlockVault(): Boolean = !vaultHasPassphrase() || verifyPassphrase("Unlock vault")

    private suspend fun verifyPassphrase(title: String): Boolean {
        val bridge = IosSecurityRegistry.bridge ?: return false
        val salt = bridge.keychainGet(KEY_SALT)?.toKotlinBytes() ?: return true
        val expected = bridge.keychainGet(KEY_VERIFIER)?.toKotlinBytes() ?: return true
        val entered = promptText(title, "Enter the vault passphrase.", "Passphrase", secure = true) ?: return false
        val matches = VaultCrypto.derivePassphraseKey(entered.toCharArray(), salt).contentEquals(expected)
        if (!matches) showMessage("Wrong passphrase", "The vault stays locked.")
        return matches
    }

    // ── UIKit helpers ──

    private suspend fun pickJsonFile(): String? = suspendCancellableCoroutine { continuation ->
        val presenter = IosMediaPlatform.topViewController()
        if (presenter == null) {
            continuation.resume(null)
            return@suspendCancellableCoroutine
        }
        val picker = UIDocumentPickerViewController(forOpeningContentTypes = listOf(UTTypeJSON), asCopy = true)
        val delegate = DocumentPickerDelegate { url ->
            pickerDelegate = null
            val text = url?.let { NSData.dataWithContentsOfURL(it)?.toKotlinBytes()?.decodeToString() }
            if (continuation.isActive) continuation.resume(text)
        }
        pickerDelegate = delegate // UIDocumentPickerViewController holds its delegate weakly.
        picker.delegate = delegate
        presenter.presentViewController(picker, animated = true, completion = null)
    }

    private suspend fun promptText(title: String, message: String?, placeholder: String, secure: Boolean): String? =
        suspendCancellableCoroutine { continuation ->
            val presenter = IosMediaPlatform.topViewController()
            if (presenter == null) {
                continuation.resume(null)
                return@suspendCancellableCoroutine
            }
            val alert = UIAlertController.alertControllerWithTitle(title, message, UIAlertControllerStyleAlert)
            alert.addTextFieldWithConfigurationHandler { field ->
                field?.placeholder = placeholder
                field?.secureTextEntry = secure
            }
            alert.addAction(
                UIAlertAction.actionWithTitle("Cancel", UIAlertActionStyleCancel) { _ ->
                    if (continuation.isActive) continuation.resume(null)
                },
            )
            alert.addAction(
                UIAlertAction.actionWithTitle("OK", UIAlertActionStyleDefault) { _ ->
                    val text = (alert.textFields?.firstOrNull() as? UITextField)?.text?.trim()
                    if (continuation.isActive) continuation.resume(text?.takeIf { it.isNotEmpty() })
                },
            )
            presenter.presentViewController(alert, animated = true, completion = null)
        }

    private fun showMessage(title: String, message: String) {
        val presenter = IosMediaPlatform.topViewController() ?: return
        val alert = UIAlertController.alertControllerWithTitle(title, message, UIAlertControllerStyleAlert)
        alert.addAction(UIAlertAction.actionWithTitle("OK", UIAlertActionStyleDefault, handler = null))
        presenter.presentViewController(alert, animated = true, completion = null)
    }

    private fun appVersion(): String =
        platform.Foundation.NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String
            ?: "unknown"

    private companion object {
        const val BACKUP_FORMAT = "cyanbridge-ios-backup-v1"
        const val KEY_SALT = "vault_passphrase_salt"
        const val KEY_VERIFIER = "vault_passphrase_verifier"
    }
}

private class DocumentPickerDelegate(
    private val onPicked: (NSURL?) -> Unit,
) : NSObject(), UIDocumentPickerDelegateProtocol {
    override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
        onPicked(didPickDocumentsAtURLs.firstOrNull() as? NSURL)
    }

    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) = onPicked(null)
}

package com.fersaiyan.cyanbridge.ui.notes

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.TextFieldValue
import com.fersaiyan.cyanbridge.integrations.knowledge.KnowledgeImportCoordinator
import com.fersaiyan.cyanbridge.integrations.knowledge.KnowledgeIntegrationPrefs
import com.fersaiyan.cyanbridge.integrations.knowledge.ObsidianManagedDraft
import com.fersaiyan.cyanbridge.integrations.knowledge.ObsidianMarkdownCodec
import com.fersaiyan.cyanbridge.integrations.knowledge.SafKnowledgeRepository
import com.fersaiyan.cyanbridge.shared.ui.notes.MarkdownNoteEditorScreen
import com.fersaiyan.cyanbridge.ui.MyApplication
import com.fersaiyan.cyanbridge.ui.appearance.AppearancePreferences
import com.fersaiyan.cyanbridge.ui.appearance.rememberAppearanceSettings
import com.fersaiyan.cyanbridge.ui.theme.CyanBridgeTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class NoteEditorActivity : AppCompatActivity() {
    private val uiScope = MainScope()
    private var noteId: Long? = null
    private var obsidianUri: Uri? = null
    private var obsidianCreatedAt: String? = null
    private var title by mutableStateOf("")
    private var tags by mutableStateOf("")
    private var body by mutableStateOf(TextFieldValue(""))
    private var sourceLabel by mutableStateOf("Created in CyanBridge")
    private var isSaving by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        noteId = intent.getLongExtra(EXTRA_NOTE_ID, -1L).takeIf { it > 0L }
        obsidianUri = intent.getStringExtra(EXTRA_OBSIDIAN_URI)?.let(Uri::parse)

        val appearancePreferences = AppearancePreferences(this)
        setContent {
            val appearance by rememberAppearanceSettings(appearancePreferences)
            CyanBridgeTheme(appearance) {
                MarkdownNoteEditorScreen(
                    screenTitle = if (noteId == null && obsidianUri == null) "New note" else "Edit note",
                    title = title,
                    tags = tags,
                    body = body,
                    sourceLabel = sourceLabel,
                    isSaving = isSaving,
                    onTitleChange = { title = it },
                    onTagsChange = { tags = it },
                    onBodyChange = { body = it },
                    onSave = ::saveNote,
                    onCopy = { copyToClipboard(body.text) },
                    onShare = { shareText(body.text) },
                    onBack = ::finish,
                )
            }
        }

        when {
            obsidianUri != null -> loadObsidianNote()
            noteId != null -> loadCyanBridgeNote()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        uiScope.cancel()
    }

    private fun loadCyanBridgeNote() {
        val id = noteId ?: return
        uiScope.launch {
            val note = MyApplication.notesRepository.getNoteById(id)
            if (note == null) {
                Toast.makeText(this@NoteEditorActivity, "Note not found", Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }
            title = note.title
            tags = note.tags.orEmpty()
            body = TextFieldValue(note.summary)
            sourceLabel = if (note.transcript != null || note.durationSec != null || note.deviceClass != null) {
                "Meeting or transcript summary"
            } else {
                "Created in CyanBridge"
            }
        }
    }

    private fun loadObsidianNote() {
        val uri = obsidianUri ?: return
        val fallbackName = intent.getStringExtra(EXTRA_OBSIDIAN_NAME).orEmpty().ifBlank { "Obsidian note.md" }
        uiScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    ObsidianMarkdownCodec.parse(
                        fallbackName,
                        SafKnowledgeRepository.readObsidianNote(this@NoteEditorActivity, uri),
                    )
                }
            }
            result.onSuccess { draft ->
                title = draft.title
                tags = draft.tags
                body = TextFieldValue(draft.body)
                obsidianCreatedAt = draft.createdAt
                sourceLabel = "Stored in Obsidian"
            }.onFailure {
                Toast.makeText(this@NoteEditorActivity, "Could not open note: ${it.message}", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private fun saveNote() {
        if (body.text.isBlank() || isSaving) return
        isSaving = true
        if (obsidianUri != null) saveObsidianNote() else saveCyanBridgeNote()
    }

    private fun saveCyanBridgeNote() {
        uiScope.launch {
            runCatching {
                MyApplication.notesRepository.saveMarkdownNote(
                    id = noteId,
                    title = title,
                    markdown = body.text,
                    tagsCsv = tags,
                )
            }.onSuccess { savedId ->
                noteId = savedId
                if (title.isBlank()) title = "Untitled note"
                Toast.makeText(this@NoteEditorActivity, "Note saved", Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(this@NoteEditorActivity, "Could not save note: ${it.message}", Toast.LENGTH_LONG).show()
            }
            isSaving = false
        }
    }

    private fun saveObsidianNote() {
        val uri = obsidianUri ?: return
        val vault = KnowledgeIntegrationPrefs.obsidianVault(this)
        if (vault == null) {
            isSaving = false
            Toast.makeText(this, "Reconnect the Obsidian vault before saving", Toast.LENGTH_LONG).show()
            return
        }
        uiScope.launch {
            val now = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
            val cleanTitle = title.trim().ifBlank { "Untitled note" }
            val markdown = ObsidianMarkdownCodec.render(
                ObsidianManagedDraft(
                    title = cleanTitle,
                    tags = tags,
                    body = body.text,
                    createdAt = obsidianCreatedAt,
                ),
                now = now,
            )
            runCatching {
                withContext(Dispatchers.IO) {
                    SafKnowledgeRepository.saveObsidianNote(
                        context = this@NoteEditorActivity,
                        treeUri = vault.permissionTreeUri,
                        rootDocumentId = vault.rootDocumentId,
                        title = cleanTitle,
                        markdown = markdown,
                        existingUri = uri,
                    )
                    KnowledgeImportCoordinator.syncObsidian(this@NoteEditorActivity)
                }
            }.onSuccess {
                title = cleanTitle
                tags = ObsidianMarkdownCodec.normalizeTags(tags).joinToString(", ")
                obsidianCreatedAt = obsidianCreatedAt ?: now
                Toast.makeText(this@NoteEditorActivity, "Obsidian note saved", Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(this@NoteEditorActivity, "Could not save note: ${it.message}", Toast.LENGTH_LONG).show()
            }
            isSaving = false
        }
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("note", text))
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
    }

    private fun shareText(text: String) {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(sendIntent, "Share note"))
    }

    companion object {
        const val EXTRA_NOTE_ID = "note_id"
        const val EXTRA_OBSIDIAN_URI = "obsidian_uri"
        const val EXTRA_OBSIDIAN_NAME = "obsidian_name"
    }
}

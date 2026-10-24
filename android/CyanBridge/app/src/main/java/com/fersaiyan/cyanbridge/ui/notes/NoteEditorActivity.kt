package com.fersaiyan.cyanbridge.ui.notes

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.fersaiyan.cyanbridge.integrations.knowledge.KnowledgeImportCoordinator
import com.fersaiyan.cyanbridge.integrations.knowledge.KnowledgeIntegrationPrefs
import com.fersaiyan.cyanbridge.integrations.knowledge.ObsidianManagedDraft
import com.fersaiyan.cyanbridge.integrations.knowledge.ObsidianMarkdownCodec
import com.fersaiyan.cyanbridge.integrations.knowledge.SafKnowledgeRepository
import com.fersaiyan.cyanbridge.shared.notes.NoteSource
import com.fersaiyan.cyanbridge.shared.ui.notes.MarkdownNoteEditorScreen
import com.fersaiyan.cyanbridge.shared.ui.notes.NoteDraft
import com.fersaiyan.cyanbridge.shared.ui.notes.NoteEditorEvent
import com.fersaiyan.cyanbridge.shared.ui.notes.NoteEditorStore
import com.fersaiyan.cyanbridge.shared.ui.notes.NoteEditorViewModel
import com.fersaiyan.cyanbridge.shared.ui.notes.NoteStoreUnavailable
import com.fersaiyan.cyanbridge.ui.MyApplication
import com.fersaiyan.cyanbridge.ui.setThemedComposeScreen
import com.fersaiyan.cyanbridge.ui.showToast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class NoteEditorActivity : AppCompatActivity() {
    private val viewModel by viewModels<NoteEditorViewModel> {
        viewModelFactory {
            initializer {
                val obsidianUri = intent.getStringExtra(EXTRA_OBSIDIAN_URI)?.let(Uri::parse)
                NoteEditorViewModel(
                    if (obsidianUri != null) {
                        val name = intent.getStringExtra(EXTRA_OBSIDIAN_NAME).orEmpty().ifBlank { "Obsidian note.md" }
                        ObsidianNoteStore(applicationContext, obsidianUri, name)
                    } else {
                        AppNoteStore(intent.getLongExtra(EXTRA_NOTE_ID, -1L).takeIf { it > 0L })
                    },
                )
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            viewModel.events.collect { event ->
                when (event) {
                    is NoteEditorEvent.Message -> showToast(event.message)
                    NoteEditorEvent.Close -> finish()
                    NoteEditorEvent.Saved -> Unit
                }
            }
        }
        setThemedComposeScreen {
            val vm = viewModel
            MarkdownNoteEditorScreen(
                screenTitle = if (vm.isNew) "New note" else "Edit note",
                title = vm.title,
                tags = vm.tags,
                body = vm.body,
                sourceLabel = when (vm.source) {
                    NoteSource.APP -> "Created in CyanBridge"
                    NoteSource.MEETING -> "Meeting or transcript summary"
                    NoteSource.OBSIDIAN -> "Stored in Obsidian"
                },
                isSaving = vm.isSaving,
                onTitleChange = { vm.title = it },
                onTagsChange = { vm.tags = it },
                onBodyChange = { vm.body = it },
                onSave = vm::save,
                onCopy = { copyToClipboard(vm.body.text) },
                onShare = { shareText(vm.body.text) },
                onBack = ::finish,
            )
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

/** A note in the app database; [noteId] is null until a new note is first saved. */
private class AppNoteStore(private var noteId: Long?) : NoteEditorStore {
    override val isNew = noteId == null

    override suspend fun load(): NoteDraft? = MyApplication.notesRepository.getNoteById(noteId ?: return null)?.let { note ->
        val meeting = note.transcript != null || note.durationSec != null || note.deviceClass != null
        NoteDraft(note.title, note.tags.orEmpty(), note.summary, if (meeting) NoteSource.MEETING else NoteSource.APP)
    }

    override suspend fun save(draft: NoteDraft): NoteDraft {
        noteId = MyApplication.notesRepository.saveMarkdownNote(
            id = noteId,
            title = draft.title,
            markdown = draft.body,
            tagsCsv = draft.tags,
        )
        return draft.copy(title = draft.title.ifBlank { "Untitled note" })
    }
}

/** A Markdown file in the connected Obsidian vault. */
private class ObsidianNoteStore(
    private val context: Context,
    private val uri: Uri,
    private val fallbackName: String,
) : NoteEditorStore {
    private var createdAt: String? = null
    override val isNew = false
    override val savedMessage = "Obsidian note saved"

    override suspend fun load(): NoteDraft {
        val draft = withContext(Dispatchers.IO) {
            ObsidianMarkdownCodec.parse(fallbackName, SafKnowledgeRepository.readObsidianNote(context, uri))
        }
        createdAt = draft.createdAt
        return NoteDraft(draft.title, draft.tags, draft.body, NoteSource.OBSIDIAN)
    }

    override suspend fun save(draft: NoteDraft): NoteDraft {
        val vault = KnowledgeIntegrationPrefs.obsidianVault(context)
            ?: throw NoteStoreUnavailable("Reconnect the Obsidian vault before saving")
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
        val cleanTitle = draft.title.trim().ifBlank { "Untitled note" }
        val markdown = ObsidianMarkdownCodec.render(
            ObsidianManagedDraft(title = cleanTitle, tags = draft.tags, body = draft.body, createdAt = createdAt),
            now = now,
        )
        withContext(Dispatchers.IO) {
            SafKnowledgeRepository.saveObsidianNote(
                context = context,
                treeUri = vault.permissionTreeUri,
                rootDocumentId = vault.rootDocumentId,
                title = cleanTitle,
                markdown = markdown,
                existingUri = uri,
            )
            KnowledgeImportCoordinator.syncObsidian(context)
        }
        createdAt = createdAt ?: now
        return draft.copy(title = cleanTitle, tags = ObsidianMarkdownCodec.normalizeTags(draft.tags).joinToString(", "))
    }
}

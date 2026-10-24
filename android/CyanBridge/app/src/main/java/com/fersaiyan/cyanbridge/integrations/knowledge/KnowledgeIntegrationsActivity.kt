package com.fersaiyan.cyanbridge.integrations.knowledge

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.fersaiyan.cyanbridge.shared.ui.knowledge.KnowledgeIntegrationsScreen
import com.fersaiyan.cyanbridge.shared.ui.knowledge.KnowledgeIntegrationsViewModel
import com.fersaiyan.cyanbridge.shared.ui.knowledge.KnowledgeVaultBackend
import com.fersaiyan.cyanbridge.ui.setThemedComposeScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class KnowledgeIntegrationsActivity : AppCompatActivity() {
    private val viewModel by viewModels<KnowledgeIntegrationsViewModel> {
        viewModelFactory { initializer { KnowledgeIntegrationsViewModel(SafKnowledgeVault(applicationContext)) } }
    }
    private val existingVaultPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { viewModel.connectExisting(it.toString()) }
    }
    private val createVaultParentPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { viewModel.createUnder(it.toString()) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setThemedComposeScreen {
            KnowledgeIntegrationsScreen(
                viewModel = viewModel,
                onChooseExisting = { existingVaultPicker.launch(viewModel.vault?.treeUri?.let(Uri::parse)) },
                onCreateNew = { createVaultParentPicker.launch(null) },
                onBack = ::finish,
            )
        }
    }
}

private class SafKnowledgeVault(private val context: Context) : KnowledgeVaultBackend {
    override fun hasWritableAccess(treeUri: String) =
        SafKnowledgeRepository.hasPersistedTreePermission(context, Uri.parse(treeUri), writable = true)

    override fun persistAccess(treeUri: String) =
        SafKnowledgeRepository.persistTreePermission(context, Uri.parse(treeUri), writable = true)

    override fun displayNameOf(treeUri: String) =
        Uri.parse(treeUri).lastPathSegment?.substringAfterLast(':')?.takeIf { it.isNotBlank() }

    override suspend fun createVault(parentTreeUri: String, name: String): String {
        val created = withContext(Dispatchers.IO) {
            SafKnowledgeRepository.createObsidianVault(context, parentTreeUri = Uri.parse(parentTreeUri), requestedName = name)
        }
        KnowledgeIntegrationPrefs.setObsidianVault(context, created.permissionTreeUri, created.rootDocumentId, created.displayName)
        return created.displayName
    }

    override suspend fun sync(): String? = KnowledgeImportCoordinator.syncObsidian(context)?.summary()

    override fun scheduleSync() = KnowledgeSyncWorker.schedule(context)
}

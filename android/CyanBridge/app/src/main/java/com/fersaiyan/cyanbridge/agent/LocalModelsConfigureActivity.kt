package com.fersaiyan.cyanbridge.agent

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.view.View
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.card.MaterialCardView
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.fersaiyan.cyanbridge.R
import com.fersaiyan.cyanbridge.localmodels.catalog.LocalModelCatalogEntry
import com.fersaiyan.cyanbridge.localmodels.catalog.LocalModelCatalogRepository
import com.fersaiyan.cyanbridge.localmodels.device.DeviceCapabilityService
import com.fersaiyan.cyanbridge.localmodels.download.LocalModelDownloadManager
import com.fersaiyan.cyanbridge.localmodels.download.LocalModelDownloadProgress
import com.fersaiyan.cyanbridge.localmodels.session.LocalChatSessionManager
import com.fersaiyan.cyanbridge.localmodels.settings.LocalComputeBackend
import com.fersaiyan.cyanbridge.localmodels.settings.LocalGenerationSettings
import com.fersaiyan.cyanbridge.localmodels.settings.LocalModelPerformanceProfile
import com.fersaiyan.cyanbridge.localmodels.settings.LocalModelRuntime
import com.fersaiyan.cyanbridge.localmodels.settings.LocalModelSettingsRepository
import com.fersaiyan.cyanbridge.localmodels.storage.InstalledLocalModel
import com.fersaiyan.cyanbridge.localmodels.storage.LocalModelFileUtils
import com.fersaiyan.cyanbridge.localmodels.storage.LocalModelStorageRepository
import com.fersaiyan.cyanbridge.ui.debug.DebugLogSupport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class LocalModelsConfigureActivity : AppCompatActivity() {
    private val downloadManager = LocalModelDownloadManager()
    private val downloadCancelled = AtomicBoolean(false)
    private var downloadJob: Job? = null
    private var warmupJob: Job? = null

    private lateinit var tvEngineStatus: TextView
    private lateinit var tvDeviceSummary: TextView
    private lateinit var tvSelectedModelStatus: TextView
    private lateinit var tvEmptyState: TextView
    private lateinit var tvDownloadProgress: TextView
    private lateinit var tvWarmupResult: TextView
    private lateinit var progressDownload: LinearProgressIndicator
    private lateinit var layoutCatalogContainer: LinearLayout
    private lateinit var cardCuratedCatalog: MaterialCardView
    private lateinit var headerCuratedCatalog: View
    private lateinit var contentCuratedCatalog: View
    private lateinit var iconExpandCuratedCatalog: ImageView
    private lateinit var cardGenerationSettings: MaterialCardView
    private lateinit var headerGenerationSettings: View
    private lateinit var contentGenerationSettings: View
    private lateinit var iconExpandGenerationSettings: ImageView

    private lateinit var spinnerInstalled: Spinner
    private lateinit var spinnerProfile: Spinner
    private lateinit var spinnerModelRuntime: Spinner
    private lateinit var spinnerComputeBackend: Spinner
    private lateinit var spinnerTemplateOverride: Spinner

    private lateinit var editTemperature: TextInputEditText
    private lateinit var editCpuThreads: TextInputEditText
    private lateinit var editGpuLayers: TextInputEditText
    private lateinit var editTopP: TextInputEditText
    private lateinit var editTopK: TextInputEditText
    private lateinit var editMaxTokens: TextInputEditText
    private lateinit var editRepPenalty: TextInputEditText
    private lateinit var editContextSize: TextInputEditText
    private lateinit var editSeed: TextInputEditText
    private lateinit var editSystemPrompt: TextInputEditText
    private lateinit var editHfToken: TextInputEditText
    private lateinit var tvModelRuntimeNote: TextView
    private lateinit var tvComputeBackendNote: TextView

    private lateinit var switchExperimentalJson: SwitchMaterial
    private lateinit var btnDownloadStarter: MaterialButton
    private lateinit var btnCancelDownload: MaterialButton

    private var installedModels: List<InstalledLocalModel> = emptyList()
    private var suppressProfileSelection = false
    private var isDownloadInFlight = false
    private val catalogDownloadButtons = mutableListOf<MaterialButton>()
    private val sectionPrefs by lazy {
        getSharedPreferences("local_models_sections", MODE_PRIVATE)
    }

    private val importModelLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            importModel(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_local_models_configure)

        supportActionBar?.setDisplayHomeAsUpEnabled(false)
        findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)?.setNavigationOnClickListener {
            finish()
        }

        bindViews()
        bindActions()
        setupCollapsibleSections()
        initSpinners()
        refreshAllUi()
    }

    override fun onDestroy() {
        super.onDestroy()
        downloadCancelled.set(true)
        downloadJob?.cancel()
        warmupJob?.cancel()
    }

    private fun bindViews() {
        tvEngineStatus = findViewById(R.id.tv_engine_status)
        tvDeviceSummary = findViewById(R.id.tv_device_summary)
        tvSelectedModelStatus = findViewById(R.id.tv_selected_model_status)
        tvEmptyState = findViewById(R.id.tv_empty_state)
        tvDownloadProgress = findViewById(R.id.tv_download_progress)
        tvWarmupResult = findViewById(R.id.tv_warmup_result)
        progressDownload = findViewById(R.id.progress_download)
        layoutCatalogContainer = findViewById(R.id.layout_catalog_container)
        cardCuratedCatalog = findViewById(R.id.card_curated_catalog)
        headerCuratedCatalog = findViewById(R.id.header_curated_catalog)
        contentCuratedCatalog = findViewById(R.id.content_curated_catalog)
        iconExpandCuratedCatalog = findViewById(R.id.icon_expand_curated_catalog)
        cardGenerationSettings = findViewById(R.id.card_generation_settings)
        headerGenerationSettings = findViewById(R.id.header_generation_settings)
        contentGenerationSettings = findViewById(R.id.content_generation_settings)
        iconExpandGenerationSettings = findViewById(R.id.icon_expand_generation_settings)

        spinnerInstalled = findViewById(R.id.spinner_installed_models)
        spinnerProfile = findViewById(R.id.spinner_profile)
        spinnerModelRuntime = findViewById(R.id.spinner_model_runtime)
        spinnerComputeBackend = findViewById(R.id.spinner_compute_backend)
        spinnerTemplateOverride = findViewById(R.id.spinner_template_override)

        editTemperature = findViewById(R.id.edit_temperature)
        editCpuThreads = findViewById(R.id.edit_cpu_threads)
        editGpuLayers = findViewById(R.id.edit_gpu_layers)
        editTopP = findViewById(R.id.edit_top_p)
        editTopK = findViewById(R.id.edit_top_k)
        editMaxTokens = findViewById(R.id.edit_max_tokens)
        editRepPenalty = findViewById(R.id.edit_repetition_penalty)
        editContextSize = findViewById(R.id.edit_context_size)
        editSeed = findViewById(R.id.edit_seed)
        editSystemPrompt = findViewById(R.id.edit_system_prompt)
        editHfToken = findViewById(R.id.edit_hf_token)
        tvModelRuntimeNote = findViewById(R.id.tv_model_runtime_note)
        tvComputeBackendNote = findViewById(R.id.tv_compute_backend_note)

        switchExperimentalJson = findViewById(R.id.switch_experimental_json)
        btnDownloadStarter = findViewById(R.id.btn_download_starter)
        btnCancelDownload = findViewById(R.id.btn_cancel_download)
    }

    private fun bindActions() {
        findViewById<MaterialButton>(R.id.btn_close).setOnClickListener { finish() }
        findViewById<MaterialButton>(R.id.btn_refresh_state).setOnClickListener { refreshAllUi() }

        findViewById<MaterialButton>(R.id.btn_import_model).setOnClickListener {
            importModelLauncher.launch(arrayOf("application/octet-stream", "*/*"))
        }

        btnDownloadStarter.setOnClickListener {
            val starter = LocalModelCatalogRepository.curatedModels
                .firstOrNull { it.id == "qwen2.5-0.5b-instruct-q4" }
            if (starter == null) {
                Toast.makeText(this, "Starter model missing from catalog", Toast.LENGTH_SHORT).show()
            } else {
                requestDownload(starter)
            }
        }

        findViewById<MaterialButton>(R.id.btn_model_info).setOnClickListener {
            showSelectedModelInfo()
        }

        findViewById<MaterialButton>(R.id.btn_unload_model).setOnClickListener {
            lifecycleScope.launch {
                runCatching { LocalChatSessionManager.unload() }
                Toast.makeText(this@LocalModelsConfigureActivity, "Local model unloaded", Toast.LENGTH_SHORT).show()
                refreshAllUi()
            }
        }

        findViewById<MaterialButton>(R.id.btn_remove_model).setOnClickListener {
            val selected = selectedInstalledModel()
            if (selected == null) {
                Toast.makeText(this, "No model selected", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            AlertDialog.Builder(this)
                .setTitle("Remove model?")
                .setMessage("Delete ${selected.displayName} from local storage?")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Remove") { _, _ ->
                    LocalModelStorageRepository.removeInstalled(this, selected.id)
                    LocalModelSettingsRepository.clearForModel(this, selected.id)
                    lifecycleScope.launch { runCatching { LocalChatSessionManager.unload() } }
                    refreshAllUi()
                }
                .show()
        }

        findViewById<MaterialButton>(R.id.btn_save).setOnClickListener {
            saveCurrentSettings()
        }

        findViewById<MaterialButton>(R.id.btn_run_warmup).setOnClickListener {
            runWarmupProbe()
        }

        btnCancelDownload.setOnClickListener {
            downloadCancelled.set(true)
            btnCancelDownload.isEnabled = false
            downloadJob?.cancel()
            tvDownloadProgress.text = "Cancelling download..."
        }

        spinnerInstalled.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long,
            ) {
                val selected = installedModels.getOrNull(position)
                LocalModelsPrefs.setSelectedModelId(this@LocalModelsConfigureActivity, selected?.id)
                loadSettingsForSelectedModel()
                refreshSelectedModelStatus()
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }

        spinnerProfile.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long,
            ) {
                if (suppressProfileSelection) return
                val model = selectedInstalledModel() ?: return
                val profile = LocalModelPerformanceProfile.entries[position]
                val catalog = LocalModelCatalogRepository.findById(model.catalogId)
                val defaults = LocalGenerationSettings.defaultsFor(catalog, profile)
                applySettingsToInputs(
                    defaults.copy(
                        computeBackend = selectedComputeBackendFromUi(),
                        cpuThreads = parseCpuThreadsInput(fallback = defaults.cpuThreads),
                        gpuLayers = parseGpuLayersInput(fallback = defaults.gpuLayers),
                        modelRuntime = defaults.modelRuntime,
                    ),
                )
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }

        spinnerComputeBackend.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long,
            ) {
                updateComputeBackendUi()
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }

        spinnerModelRuntime.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long,
            ) {
                updateRuntimeUi()
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
    }

    private fun setupCollapsibleSections() {
        setupCollapsibleSection(
            card = cardCuratedCatalog,
            header = headerCuratedCatalog,
            content = contentCuratedCatalog,
            icon = iconExpandCuratedCatalog,
            sectionName = "CURATED CATALOG",
            defaultExpanded = false,
        )
        setupCollapsibleSection(
            card = cardGenerationSettings,
            header = headerGenerationSettings,
            content = contentGenerationSettings,
            icon = iconExpandGenerationSettings,
            sectionName = "GENERATION SETTINGS",
            defaultExpanded = false,
        )
    }

    private fun setupCollapsibleSection(
        card: MaterialCardView,
        header: View,
        content: View,
        icon: ImageView,
        sectionName: String,
        defaultExpanded: Boolean,
    ) {
        val prefKey = "section_expanded_${resources.getResourceEntryName(card.id)}"
        header.isClickable = true
        header.isFocusable = true

        val expanded = sectionPrefs.getBoolean(prefKey, defaultExpanded)
        applySectionState(content = content, icon = icon, expanded = expanded, sectionName = sectionName)

        header.setOnClickListener {
            val nextExpanded = !sectionPrefs.getBoolean(prefKey, defaultExpanded)
            sectionPrefs.edit().putBoolean(prefKey, nextExpanded).apply()
            applySectionState(content = content, icon = icon, expanded = nextExpanded, sectionName = sectionName)
        }
    }

    private fun applySectionState(content: View, icon: ImageView, expanded: Boolean, sectionName: String) {
        content.visibility = if (expanded) View.VISIBLE else View.GONE
        icon.setImageResource(if (expanded) R.drawable.ic_expand_more else R.drawable.ic_chevron_right)
        icon.contentDescription = if (expanded) {
            "$sectionName expanded. Double tap to collapse"
        } else {
            "$sectionName collapsed. Double tap to expand"
        }
    }

    private fun initSpinners() {
        val profiles = LocalModelPerformanceProfile.entries.map { it.label }
        spinnerProfile.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, profiles)

        val runtimes = LocalModelRuntime.entries.map { it.label }
        spinnerModelRuntime.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, runtimes)

        val backends = LocalComputeBackend.entries.map { it.label }
        spinnerComputeBackend.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, backends)

        val templateItems = mutableListOf("Auto (catalog default)")
        templateItems += com.fersaiyan.cyanbridge.localmodels.templates.PromptTemplateRegistry.templates.map {
            "${it.label} (${it.id})"
        }
        spinnerTemplateOverride.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, templateItems)
        updateRuntimeUi()
        updateComputeBackendUi()
    }

    private fun refreshAllUi() {
        LocalModelStorageRepository.cleanupMissingModels(this)

        val snapshot = DeviceCapabilityService.snapshot(this)
        val ramGb = snapshot.totalRamBytes / (1024.0 * 1024.0 * 1024.0)
        val freeGb = snapshot.freeStorageBytes / (1024.0 * 1024.0 * 1024.0)
        tvEngineStatus.text = "Runtimes available: llama.cpp + LiteRT"
        tvDeviceSummary.text =
            "ABI: ${snapshot.primaryAbi} | RAM: ${String.format("%.1f", ramGb)} GB | Free storage: ${String.format("%.2f", freeGb)} GB"

        refreshInstalledModelsSpinner()
        refreshSelectedModelStatus()
        refreshCatalogUi()
        loadSettingsForSelectedModel()

        editHfToken.setText(LocalModelsPrefs.getHuggingFaceToken(this))
        tvEmptyState.visibility = if (installedModels.isEmpty()) View.VISIBLE else View.GONE
        btnDownloadStarter.visibility = if (installedModels.isEmpty()) View.VISIBLE else View.GONE
        syncDownloadButtonsState()
    }

    private fun refreshInstalledModelsSpinner() {
        installedModels = LocalModelStorageRepository.listInstalled(this)
        val labels = if (installedModels.isEmpty()) {
            listOf("No installed models")
        } else {
            installedModels.map { "${it.displayName} (${humanSize(it.sizeBytes)})" }
        }
        spinnerInstalled.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)

        val selectedId = LocalModelsPrefs.getSelectedModelId(this)
        val idx = installedModels.indexOfFirst { it.id == selectedId }
        if (idx >= 0) {
            spinnerInstalled.setSelection(idx)
        } else if (installedModels.isNotEmpty()) {
            spinnerInstalled.setSelection(0)
            LocalModelsPrefs.setSelectedModelId(this, installedModels[0].id)
        }
    }

    private fun selectedInstalledModel(): InstalledLocalModel? {
        if (installedModels.isEmpty()) return null
        val idx = spinnerInstalled.selectedItemPosition
        if (idx < 0) return null
        return installedModels.getOrNull(idx)
    }

    private fun refreshSelectedModelStatus() {
        val selected = selectedInstalledModel()
        if (selected == null) {
            tvSelectedModelStatus.text = "Status: not downloaded"
            return
        }

        val exists = File(selected.absolutePath).exists()
        val status = if (!exists) "failed (missing file)" else "ready"
        tvSelectedModelStatus.text = "Status: $status | ${selected.displayName}"
    }

    private fun refreshCatalogUi() {
        layoutCatalogContainer.removeAllViews()
        catalogDownloadButtons.clear()
        val installedByCatalogId = installedModels.associateBy { it.catalogId }

        LocalModelCatalogRepository.curatedModels.forEach { entry ->
            val modelLine = TextView(this).apply {
                text = buildString {
                    append(entry.displayName)
                    append("\n")
                    append("${entry.quantization} · ${humanSize(entry.sizeBytes)} · tags: ${entry.tags.joinToString(", ")}")
                    append("\n")
                    append(statusText(entry, installedByCatalogId[entry.id]))
                }
                setTextColor(resources.getColor(R.color.text_primary, theme))
                textSize = 13f
                setPadding(0, 12, 0, 4)
            }
            layoutCatalogContainer.addView(modelLine)

            val buttonRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            val canDownload = !entry.sourceUrl.isNullOrBlank() && !installedByCatalogId.containsKey(entry.id)
            val btnDownload = MaterialButton(this).apply {
                text = when {
                    installedByCatalogId.containsKey(entry.id) -> "Installed"
                    entry.sourceUrl.isNullOrBlank() -> "Manual Import"
                    entry.gatedDownload -> "Download (Token)"
                    else -> "Download"
                }
                tag = canDownload
                isEnabled = canDownload && !isDownloadInFlight
                setOnClickListener { requestDownload(entry) }
            }
            catalogDownloadButtons += btnDownload
            val btnInfo = MaterialButton(this).apply {
                text = "Info"
                setOnClickListener { showCatalogInfo(entry, installedByCatalogId[entry.id] != null) }
            }

            buttonRow.addView(btnDownload, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            buttonRow.addView(btnInfo, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = 8
            })
            layoutCatalogContainer.addView(buttonRow)
        }

        syncDownloadButtonsState()
    }

    private fun statusText(entry: LocalModelCatalogEntry, installed: InstalledLocalModel?): String {
        if (installed != null) return "Status: ready"
        if (entry.sourceUrl.isNullOrBlank()) return "Status: manual import recommended"
        if (entry.gatedDownload) return "Status: downloadable (requires token + accepted terms)"
        return "Status: not downloaded"
    }

    private fun requestDownload(entry: LocalModelCatalogEntry) {
        if (isDownloadInFlight) {
            Toast.makeText(this, "A model download is already in progress", Toast.LENGTH_SHORT).show()
            return
        }

        if (entry.sourceUrl.isNullOrBlank()) {
            Toast.makeText(this, "No direct source URL for this entry. Use manual import.", Toast.LENGTH_LONG).show()
            return
        }

        val hfToken = LocalModelsPrefs.getHuggingFaceToken(this).trim().ifBlank { null }
        if (entry.gatedDownload && hfToken == null) {
            Toast.makeText(
                this,
                "This model is gated. Add a Hugging Face token below after accepting model terms.",
                Toast.LENGTH_LONG,
            ).show()
            return
        }

        val assessment = DeviceCapabilityService.assess(
            snapshot = DeviceCapabilityService.snapshot(this),
            entry = entry,
            requireDownloadHeadroom = true,
        )
        if (!assessment.supported) {
            Toast.makeText(this, assessment.blockers.joinToString(" "), Toast.LENGTH_LONG).show()
            return
        }

        val continueDownload = {
            showDownloadStarted(entry)
            downloadJob?.cancel()
            downloadJob = lifecycleScope.launch {
                runCatching {
                    downloadManager.downloadCatalogModel(
                        context = this@LocalModelsConfigureActivity,
                        entry = entry,
                        authToken = hfToken,
                        cancelled = downloadCancelled,
                        onProgress = { p -> onDownloadProgress(p) },
                    )
                }.onSuccess {
                    showDownloadFinished("Download complete: ${it.displayName}", success = true)
                    Toast.makeText(this@LocalModelsConfigureActivity, "Model ready", Toast.LENGTH_SHORT).show()
                    refreshAllUi()
                }.onFailure { err ->
                    val cancelled = err is CancellationException || downloadCancelled.get()
                    if (cancelled) {
                        showDownloadFinished("Download cancelled", success = false)
                    } else {
                        showDownloadFinished("Download failed: ${err.message}", success = false)
                        Toast.makeText(this@LocalModelsConfigureActivity, err.message ?: "Download failed", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        if (assessment.warnings.isNotEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Device warning")
                .setMessage(assessment.warnings.joinToString("\n"))
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Continue") { _, _ -> continueDownload() }
                .show()
        } else {
            continueDownload()
        }
    }

    private fun showDownloadStarted(entry: LocalModelCatalogEntry) {
        downloadCancelled.set(false)
        isDownloadInFlight = true
        progressDownload.visibility = View.VISIBLE
        progressDownload.isIndeterminate = true
        progressDownload.progress = 0
        tvDownloadProgress.text = "Starting download: ${entry.displayName}"
        btnCancelDownload.isEnabled = true
        syncDownloadButtonsState()
    }

    private fun showDownloadFinished(message: String, success: Boolean) {
        isDownloadInFlight = false
        if (success) {
            progressDownload.visibility = View.VISIBLE
            progressDownload.isIndeterminate = false
            progressDownload.setProgressCompat(100, false)
        } else {
            progressDownload.visibility = View.GONE
            progressDownload.progress = 0
        }
        tvDownloadProgress.text = message
        syncDownloadButtonsState()
    }

    private fun syncDownloadButtonsState() {
        val shouldShowStarter = installedModels.isEmpty()
        btnDownloadStarter.visibility = if (shouldShowStarter) View.VISIBLE else View.GONE
        btnDownloadStarter.isEnabled = !isDownloadInFlight && shouldShowStarter

        catalogDownloadButtons.forEach { button ->
            val canDownloadWhenIdle = button.tag as? Boolean ?: false
            button.isEnabled = canDownloadWhenIdle && !isDownloadInFlight
        }

        btnCancelDownload.visibility = if (isDownloadInFlight) View.VISIBLE else View.GONE
    }

    private fun onDownloadProgress(progress: LocalModelDownloadProgress) {
        runOnUiThread {
            val done = humanSize(progress.downloadedBytes)
            val total = if (progress.totalBytes > 0) humanSize(progress.totalBytes) else "?"
            progressDownload.visibility = View.VISIBLE
            progressDownload.isIndeterminate = progress.totalBytes <= 0L
            if (progress.totalBytes > 0) {
                progressDownload.setProgressCompat(progress.percent, true)
            }
            tvDownloadProgress.text = "Downloading ${progress.modelId}: ${progress.percent}% ($done / $total)"
        }
    }

    private fun importModel(uri: Uri) {
        lifecycleScope.launch {
            tvDownloadProgress.text = "Importing model..."
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val name = guessDisplayName(uri)
                    val file = LocalModelStorageRepository.copyUriToManagedModelFile(
                        context = this@LocalModelsConfigureActivity,
                        uri = uri,
                        preferredName = name,
                    )
                    if (!LocalModelFileUtils.isSupportedModelFile(file)) {
                        file.delete()
                        throw IllegalStateException("Imported file must be GGUF or LiteRT (.litertlm/.task)")
                    }
                    LocalModelStorageRepository.registerImportedModel(
                        context = this@LocalModelsConfigureActivity,
                        displayName = file.nameWithoutExtension,
                        file = file,
                    )
                }
            }

            result.onSuccess {
                tvDownloadProgress.text = "Import complete: ${it.displayName}"
                Toast.makeText(this@LocalModelsConfigureActivity, "Imported ${it.displayName}", Toast.LENGTH_SHORT).show()
                refreshAllUi()
            }.onFailure { err ->
                tvDownloadProgress.text = "Import failed: ${err.message}"
                Toast.makeText(this@LocalModelsConfigureActivity, err.message ?: "Import failed", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun guessDisplayName(uri: Uri): String {
        val defaultName = "imported-model.gguf"
        if (uri.scheme == "file") {
            return File(uri.path.orEmpty()).name.ifBlank { defaultName }
        }

        val cursor = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val idx = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) {
                    val name = it.getString(idx)
                    if (!name.isNullOrBlank()) return name
                }
            }
        }
        return defaultName
    }

    private fun loadSettingsForSelectedModel() {
        val model = selectedInstalledModel() ?: run {
            clearSettingsInputs()
            return
        }
        val settings = LocalModelSettingsRepository.getForModel(this, model.id)
        suppressProfileSelection = true
        spinnerProfile.setSelection(settings.profile.ordinal)
        suppressProfileSelection = false
        applySettingsToInputs(settings)

        val templates = com.fersaiyan.cyanbridge.localmodels.templates.PromptTemplateRegistry.templates
        val idx = templates.indexOfFirst { it.id == settings.templateOverrideId }
        spinnerTemplateOverride.setSelection(if (idx >= 0) idx + 1 else 0)
    }

    private fun applySettingsToInputs(settings: LocalGenerationSettings) {
        editTemperature.setText(settings.temperature.toString())
        spinnerModelRuntime.setSelection(settings.modelRuntime.ordinal)
        spinnerComputeBackend.setSelection(settings.computeBackend.ordinal)
        editCpuThreads.setText(settings.cpuThreads.toString())
        editGpuLayers.setText(settings.gpuLayers.toString())
        editTopP.setText(settings.topP.toString())
        editTopK.setText(settings.topK.toString())
        editMaxTokens.setText(settings.maxTokens.toString())
        editRepPenalty.setText(settings.repetitionPenalty.toString())
        editContextSize.setText(settings.contextSize.toString())
        editSeed.setText(settings.seed.toString())
        editSystemPrompt.setText(settings.systemPromptOverride)
        switchExperimentalJson.isChecked = settings.experimentalStructuredJson
        updateRuntimeUi()
        updateComputeBackendUi()
    }

    private fun clearSettingsInputs() {
        editTemperature.setText("")
        spinnerModelRuntime.setSelection(LocalModelRuntime.LLAMA_CPP.ordinal)
        spinnerComputeBackend.setSelection(LocalComputeBackend.CPU.ordinal)
        editCpuThreads.setText(LocalGenerationSettings.defaultCpuThreads().toString())
        editGpuLayers.setText("35")
        editTopP.setText("")
        editTopK.setText("")
        editMaxTokens.setText("")
        editRepPenalty.setText("")
        editContextSize.setText("")
        editSeed.setText("")
        editSystemPrompt.setText("")
        switchExperimentalJson.isChecked = false
        updateRuntimeUi()
        updateComputeBackendUi()
    }

    private fun saveCurrentSettings() {
        val model = selectedInstalledModel()
        if (model == null) {
            LocalModelsPrefs.setHuggingFaceToken(this, editHfToken.text?.toString().orEmpty())
            Toast.makeText(this, "Saved token. Install a model to save generation settings.", Toast.LENGTH_SHORT).show()
            return
        }

        val existing = LocalModelSettingsRepository.getForModel(this, model.id)
        val profile = LocalModelPerformanceProfile.entries[spinnerProfile.selectedItemPosition]
        val parsedMaxTokens = parseBoundedIntInput(
            raw = editMaxTokens.text?.toString(),
            min = LocalGenerationSettings.MIN_MAX_TOKENS,
            max = LocalGenerationSettings.MAX_MAX_TOKENS,
        )
        val parsedContextSize = parseBoundedIntInput(
            raw = editContextSize.text?.toString(),
            min = LocalGenerationSettings.MIN_CONTEXT_SIZE,
            max = LocalGenerationSettings.MAX_CONTEXT_SIZE,
        )
        val settings = LocalGenerationSettings(
            profile = profile,
            temperature = editTemperature.text.toString().toDoubleOrNull()?.coerceIn(0.0, 2.0)
                ?: existing.temperature,
            topP = editTopP.text.toString().toDoubleOrNull()?.coerceIn(0.0, 1.0)
                ?: existing.topP,
            topK = editTopK.text.toString().toIntOrNull()?.coerceIn(0, 200)
                ?: existing.topK,
            maxTokens = parsedMaxTokens ?: existing.maxTokens,
            repetitionPenalty = editRepPenalty.text.toString().toDoubleOrNull()?.coerceIn(0.8, 2.0)
                ?: existing.repetitionPenalty,
            contextSize = parsedContextSize ?: existing.contextSize,
            seed = editSeed.text.toString().toIntOrNull() ?: existing.seed,
            systemPromptOverride = editSystemPrompt.text?.toString().orEmpty().trim(),
            templateOverrideId = selectedTemplateOverrideId(),
            experimentalStructuredJson = switchExperimentalJson.isChecked,
            modelRuntime = selectedModelRuntimeFromUi(),
            computeBackend = selectedComputeBackendFromUi(),
            cpuThreads = parseCpuThreadsInput(fallback = existing.cpuThreads),
            gpuLayers = parseGpuLayersInput(fallback = existing.gpuLayers),
        )

        LocalModelSettingsRepository.saveForModel(this, model.id, settings)
        LocalModelsPrefs.setHuggingFaceToken(this, editHfToken.text?.toString().orEmpty())
        applySettingsToInputs(settings)
        Toast.makeText(
            this,
            "Saved: max output ${settings.maxTokens}, context ${settings.contextSize}",
            Toast.LENGTH_SHORT,
        ).show()
        setResult(RESULT_OK)
    }

    private fun selectedTemplateOverrideId(): String? {
        val pos = spinnerTemplateOverride.selectedItemPosition
        if (pos <= 0) return null
        return com.fersaiyan.cyanbridge.localmodels.templates.PromptTemplateRegistry.templates
            .getOrNull(pos - 1)
            ?.id
    }

    private fun runWarmupProbe() {
        val model = selectedInstalledModel()
        if (model == null) {
            Toast.makeText(this, "Install or select a model first", Toast.LENGTH_SHORT).show()
            return
        }

        val settings = LocalModelSettingsRepository.getForModel(this, model.id)
        val entry = LocalModelCatalogRepository.findById(model.catalogId)

        tvWarmupResult.text = "Running warm-up..."
        warmupJob?.cancel()
        warmupJob = lifecycleScope.launch {
            val outcome = runCatching {
                withContext(Dispatchers.IO) {
                    val loadDetails = LocalChatSessionManager.ensureModelLoaded(
                        context = this@LocalModelsConfigureActivity,
                        model = model,
                        catalogEntry = entry,
                        settings = settings,
                    )
                    val warmup = LocalChatSessionManager.runWarmupProbe(
                        settings = settings,
                        onToken = {},
                    )
                    loadDetails to warmup
                }
            }

            if (!isFinishing && !isDestroyed) {
                outcome.fold(
                    onSuccess = { (loadDetails, result) ->
                        runCatching {
                            val genTps = (result.generatedTokens * 1000.0 / result.elapsedMs).coerceAtLeast(0.1)
                            val totalTps = (result.totalTokens * 1000.0 / result.elapsedMs).coerceAtLeast(0.1)
                            val backend = if (result.backend == LocalComputeBackend.GPU_EXPERIMENTAL) {
                                "GPU"
                            } else {
                                "CPU"
                            }
                            val gpuLayersSuffix = if (result.backend == LocalComputeBackend.GPU_EXPERIMENTAL) {
                                val layers = if (loadDetails.activeGpuLayers == -1) "auto(-1)" else loadDetails.activeGpuLayers.toString()
                                ", n_gpu_layers=$layers"
                            } else {
                                ""
                            }
                            val fallbackSuffix = if (loadDetails.fallbackReason.isNullOrBlank() || result.backend == LocalComputeBackend.GPU_EXPERIMENTAL) {
                                ""
                            } else {
                                " | fallback: CPU"
                            }
                            val msg = "Warm-up complete: ${String.format("%.2f", genTps)} gen tok/s, ${String.format("%.2f", totalTps)} total tok/s, ${result.elapsedMs}ms, backend=$backend$gpuLayersSuffix$fallbackSuffix"
                            tvWarmupResult.text = msg
                            LocalModelsPrefs.setLastBenchmark(this@LocalModelsConfigureActivity, msg)
                            if (!loadDetails.fallbackReason.isNullOrBlank() && result.backend != LocalComputeBackend.GPU_EXPERIMENTAL) {
                                Toast.makeText(this@LocalModelsConfigureActivity, loadDetails.fallbackReason, Toast.LENGTH_LONG).show()
                            }
                        }.onFailure { uiErr ->
                            tvWarmupResult.text = "Warm-up failed: ${uiErr.message ?: "unexpected UI error"}"
                        }
                    },
                    onFailure = { err ->
                        if (err is CancellationException) {
                            tvWarmupResult.text = "Warm-up cancelled"
                        } else {
                            Log.e("LocalModelsConfigureActivity", "Warm-up failed", err)
                            tvWarmupResult.text = "Warm-up failed: ${err.message ?: "unknown error"}"
                            val shouldOfferLogs =
                                selectedModelRuntimeFromUi() == LocalModelRuntime.LITERT ||
                                    selectedComputeBackendFromUi() == LocalComputeBackend.GPU_EXPERIMENTAL ||
                                    DebugLogSupport.isLocalRuntimeIssue(err.message, err)
                            if (shouldOfferLogs) {
                                DebugLogSupport.showSupportOptionsDialog(
                                    activity = this@LocalModelsConfigureActivity,
                                    title = "Local runtime issue",
                                    issueType = "Local runtime issue",
                                    description = "The local model warm-up failed. This can help diagnose LiteRT, Vulkan, or GPU initialization issues.",
                                    extraInfo = linkedMapOf(
                                        "screen" to "local_models_configure",
                                        "selected_runtime" to selectedModelRuntimeFromUi().name,
                                        "selected_backend" to selectedComputeBackendFromUi().name,
                                    ),
                                )
                            }
                        }
                    },
                )
            }
        }
    }

    private fun selectedModelRuntimeFromUi(): LocalModelRuntime {
        return LocalModelRuntime.entries.getOrElse(spinnerModelRuntime.selectedItemPosition) {
            LocalModelRuntime.LLAMA_CPP
        }
    }

    private fun selectedComputeBackendFromUi(): LocalComputeBackend {
        return LocalComputeBackend.entries.getOrElse(spinnerComputeBackend.selectedItemPosition) {
            LocalComputeBackend.CPU
        }
    }

    private fun parseCpuThreadsInput(fallback: Int): Int {
        return editCpuThreads.text?.toString()?.toIntOrNull()?.coerceIn(1, 16)
            ?: fallback.coerceIn(1, 16)
    }

    private fun parseGpuLayersInput(fallback: Int): Int {
        return editGpuLayers.text?.toString()?.toIntOrNull()?.coerceIn(-1, 999)
            ?: fallback.coerceIn(-1, 999)
    }

    private fun parseBoundedIntInput(raw: String?, min: Int, max: Int): Int? {
        val cleaned = raw
            ?.trim()
            ?.replace(",", "")
            ?.replace("_", "")
            ?.replace(" ", "")
            .orEmpty()
        if (cleaned.isBlank()) return null
        return cleaned.toIntOrNull()?.coerceIn(min, max)
    }

    private fun updateComputeBackendUi() {
        val runtime = selectedModelRuntimeFromUi()
        val backend = selectedComputeBackendFromUi()
        val gpuSelected = backend == LocalComputeBackend.GPU_EXPERIMENTAL
        editGpuLayers.isEnabled = gpuSelected
        tvComputeBackendNote.text = if (gpuSelected) {
            if (runtime == LocalModelRuntime.LITERT) {
                "LiteRT GPU backend is device-dependent. If GPU init fails, CyanBridge falls back to CPU."
            } else {
                "GPU is experimental. Use -1 for auto layer offload. If GPU init fails, the app retries lower layer counts and then falls back to CPU."
            }
        } else {
            if (runtime == LocalModelRuntime.LITERT) {
                "LiteRT CPU mode is safest for first runs. Move to GPU after a successful warm-up."
            } else {
                "CPU mode is the most compatible option. Increase CPU threads for speed if your device remains responsive."
            }
        }
    }

    private fun updateRuntimeUi() {
        val runtime = selectedModelRuntimeFromUi()
        tvModelRuntimeNote.text = when (runtime) {
            LocalModelRuntime.LLAMA_CPP -> "Use llama.cpp for GGUF models."
            LocalModelRuntime.LITERT -> "Use LiteRT for Google LiteRT-LM packages (.litertlm/.task)."
        }
        tvEngineStatus.text = "Selected runtime: ${runtime.label}"
        updateComputeBackendUi()
    }

    private fun showSelectedModelInfo() {
        val selected = selectedInstalledModel()
        if (selected == null) {
            Toast.makeText(this, "No model selected", Toast.LENGTH_SHORT).show()
            return
        }
        val exists = File(selected.absolutePath).exists()
        AlertDialog.Builder(this)
            .setTitle(selected.displayName)
            .setMessage(
                buildString {
                    appendLine("Family: ${LocalModelCatalogRepository.findById(selected.catalogId)?.family ?: "custom"}")
                    appendLine("Quantization: ${selected.quantization ?: "unknown"}")
                    appendLine("Size: ${humanSize(selected.sizeBytes)}")
                    appendLine("Template: ${selected.promptTemplateId ?: "auto"}")
                    appendLine("Location: ${selected.absolutePath}")
                    appendLine("Status: ${if (exists) "ready" else "failed (missing file)"}")
                    appendLine("SHA-256: ${selected.sha256 ?: "n/a"}")
                    appendLine("License: ${selected.licenseTermsNote ?: "Check source model card"}")
                }
            )
            .setPositiveButton("Close", null)
            .show()
    }

    private fun showCatalogInfo(entry: LocalModelCatalogEntry, installed: Boolean) {
        AlertDialog.Builder(this)
            .setTitle(entry.displayName)
            .setMessage(
                buildString {
                    appendLine("Family: ${entry.family}")
                    appendLine("Runtime: ${entry.engine}")
                    appendLine("Format: ${entry.format}")
                    appendLine("Quantization: ${entry.quantization}")
                    appendLine("File size: ${humanSize(entry.sizeBytes)}")
                    appendLine("Prompt template: ${entry.promptTemplateId}")
                    appendLine("RAM tier: ${entry.minRamGb} GB+")
                    appendLine("Storage tier: ${entry.minStorageGb} GB+")
                    appendLine("Source: ${entry.sourcePageUrl ?: entry.sourceUrl ?: "manual import"}")
                    appendLine("Status: ${if (installed) "ready" else statusText(entry, null)}")
                    appendLine("License/terms: ${entry.licenseTermsNote}")
                }
            )
            .setNegativeButton("Close", null)
            .setPositiveButton("Open Source") { _, _ ->
                val url = entry.sourcePageUrl ?: entry.sourceUrl
                if (url.isNullOrBlank()) return@setPositiveButton
                runCatching {
                    startActivity(
                        android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            Uri.parse(url),
                        ),
                    )
                }
            }
            .show()
    }

    private fun humanSize(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        val kb = 1024.0
        val mb = kb * 1024.0
        val gb = mb * 1024.0
        val b = bytes.toDouble()
        return when {
            b >= gb -> String.format("%.2f GB", b / gb)
            b >= mb -> String.format("%.1f MB", b / mb)
            b >= kb -> String.format("%.1f KB", b / kb)
            else -> "$bytes B"
        }
    }
}

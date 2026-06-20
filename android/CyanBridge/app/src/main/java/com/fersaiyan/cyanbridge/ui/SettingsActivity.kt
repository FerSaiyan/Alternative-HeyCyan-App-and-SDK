package com.fersaiyan.cyanbridge.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.fersaiyan.cyanbridge.MainActivity
import com.fersaiyan.cyanbridge.R
import com.fersaiyan.cyanbridge.agent.AgentProviderType
import com.fersaiyan.cyanbridge.agent.ProSubscriptionActivity
import com.fersaiyan.cyanbridge.agent.ProSubscriptionPrefs
import com.fersaiyan.cyanbridge.agent.ProSubscriptionSettingsActivity
import com.fersaiyan.cyanbridge.agent.ProSubscriptionVerifier
import com.fersaiyan.cyanbridge.agent.LocalAgentPrefs as AutomationPrefs
import com.fersaiyan.cyanbridge.ai.router.AiProviderPrefs
import com.fersaiyan.cyanbridge.ai.router.AiProviderType as RelayProviderType
import com.fersaiyan.cyanbridge.ai.router.CliRelayBackend
import com.fersaiyan.cyanbridge.ai.router.CliRelayClient
import com.fersaiyan.cyanbridge.chat.ChatStore
import com.fersaiyan.cyanbridge.databinding.ActivitySettingsBinding
import com.fersaiyan.cyanbridge.localagent.LocalAgentController
import com.fersaiyan.cyanbridge.localagent.LocalAgentIntents
import com.fersaiyan.cyanbridge.localagent.LocalAgentPrefs as AgentRuntimePrefs
import com.fersaiyan.cyanbridge.localmodels.session.LocalChatSessionManager
import com.fersaiyan.cyanbridge.localagent.accessibility.LocalAgentAccessibilityService
import com.fersaiyan.cyanbridge.localagent.daily.DailyFactsReminderScheduler
import com.fersaiyan.cyanbridge.localagent.memory.LocalAgentMemoryStore
import com.fersaiyan.cyanbridge.localagent.userfacts.ChatMemoryPrefs
import com.fersaiyan.cyanbridge.memoryvault.MemoryModeManager
import com.fersaiyan.cyanbridge.memoryvault.MemoryPrivacyMode
import com.fersaiyan.cyanbridge.memoryvault.MemorySourceType
import com.fersaiyan.cyanbridge.memoryvault.MemorySyncPreparationService
import com.fersaiyan.cyanbridge.memoryvault.MemoryVaultBootstrap
import com.fersaiyan.cyanbridge.memoryvault.MemoryVaultService
import com.fersaiyan.cyanbridge.memoryvault.VaultLockStateManager
import com.hjq.permissions.XXPermissions
import com.fersaiyan.cyanbridge.media.autocapture.AutoAudioCapturePrefs
import com.fersaiyan.cyanbridge.media.autocapture.AutoAudioCaptureService
import com.fersaiyan.cyanbridge.privacy.LocalDataClearer
import com.fersaiyan.cyanbridge.privacy.LocalDataBackupManager
import com.fersaiyan.cyanbridge.privacy.PrivacyPrefs
import com.fersaiyan.cyanbridge.ui.debug.DebugLogSupport
import com.fersaiyan.cyanbridge.ui.localagent.AppBlacklistActivity
import com.fersaiyan.cyanbridge.ui.localagent.DailyFactsActivity
import com.fersaiyan.cyanbridge.ui.localagent.DailySummaryActivity
import com.fersaiyan.cyanbridge.ui.localagent.ScreenCapturesActivity
import com.fersaiyan.cyanbridge.ui.localagent.PendingActionsActivity
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.concurrent.thread

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding
    private var recordingBannerController: RecordingBannerController? = null
    private var suppressAutoAudioToggle = false
    private var suppressMemoryModeToggle = false
    private var agentReceiverRegistered = false
    private val sectionPrefs by lazy {
        getSharedPreferences("settings_sections", MODE_PRIVATE)
    }

    companion object {
        private val DEFAULT_BULLET_PROMPT = """You summarize one mobile screen OCR event into exactly one bullet.

The app package is provided below, and the app name may also appear inside the OCR text.

APP_PACKAGE: ${'$'}{event.packageName}
EVENT_TIME: ${'$'}{event.time}
OCR_TEXT: ${'$'}{event.text}

Return JSON only: {"skip": false, "bullet": "...", "confidence": 0.0}

Rules:
- Keep bullet factual and concise (max 26 words)
- Preserve concrete details like person names, contact names, topics, or action context when visible
- If OCR is too noisy or meaningless, set skip=true
- Do not invent details outside OCR
""".trimIndent()
    }

    private val exportDataLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        uri?.let { exportLocalDataToUri(it) }
    }

    private val importDataLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { importLocalDataFromUri(it) }
    }

    private val agentStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            if (intent == null) return
            val status = intent.getStringExtra(LocalAgentIntents.EXTRA_STATUS)
            val lastError = intent.getStringExtra(LocalAgentIntents.EXTRA_LAST_ERROR)
            if (!status.isNullOrBlank()) {
                AgentRuntimePrefs.setStatus(this@SettingsActivity, status)
            }
            if (!lastError.isNullOrBlank()) {
                AgentRuntimePrefs.setLastError(this@SettingsActivity, lastError)
            }
            refreshAgentStatusUi()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Use findViewById directly to ensure access to banner views, bypassing potential ViewBinding resolution issues.
        recordingBannerController = RecordingBannerController(
            context = this,
            bannerView = findViewById(R.id.meeting_recording_banner)!!,
            bannerText = findViewById(R.id.tv_meeting_banner)!!,
            stopButton = findViewById(R.id.btn_meeting_banner_stop)!!,
        )

        bindProviderTypeAndLocalAgentSettings()
        bindMemoryVaultSettings()
        refreshProSubscriptionBanner()
        bindPrivacyToggles()
        bindAutoAudioCapture()
        bindClearData()
        bindAgentControls()
        setupCollapsibleSections()
        refreshAgentStatusUi()
        setupBottomNavigation()
    }

    private fun setupCollapsibleSections() {
        setupCollapsibleSection(
            card = binding.cardAgentProvider,
            header = binding.headerAiAutomation,
            content = binding.contentAiAutomation,
            icon = binding.iconExpandAi,
            sectionName = "AI / AUTOMATION",
        )
        setupCollapsibleSection(
            card = binding.cardLocalAgentSettings,
            header = binding.headerLocalAgent,
            content = binding.contentLocalAgent,
            icon = binding.iconExpandLocalAgent,
            sectionName = "LOCAL AGENT",
        )
        setupCollapsibleSection(
            card = binding.cardMemoryPrivacy,
            header = binding.headerMemoryPrivacy,
            content = binding.contentMemoryPrivacy,
            icon = binding.iconExpandMemoryPrivacy,
            sectionName = "MEMORY PRIVACY",
        )
        setupCollapsibleSection(
            card = binding.cardTranscripts,
            header = binding.headerTranscripts,
            content = binding.contentTranscripts,
            icon = binding.iconExpandTranscripts,
            sectionName = "TRANSCRIPTS",
        )
        setupCollapsibleSection(
            card = binding.cardData,
            header = binding.headerData,
            content = binding.contentData,
            icon = binding.iconExpandData,
            sectionName = "DATA",
        )
        setupCollapsibleSection(
            card = binding.cardAgent,
            header = binding.headerAgent,
            content = binding.contentAgent,
            icon = binding.iconExpandAgent,
            sectionName = "AGENT",
        )
        setupCollapsibleSection(
            card = binding.cardFaq,
            header = binding.headerFaq,
            content = binding.contentFaq,
            icon = binding.iconExpandFaq,
            sectionName = "FAQ",
        )
    }

    private fun setupCollapsibleSection(
        card: MaterialCardView,
        header: View,
        content: View,
        icon: ImageView,
        sectionName: String,
    ) {
        val prefKey = "section_expanded_${resources.getResourceEntryName(card.id)}"

        header.isClickable = true
        header.isFocusable = true

        val expanded = sectionPrefs.getBoolean(prefKey, true)
        applySectionState(content = content, icon = icon, expanded = expanded, sectionName = sectionName)

        header.setOnClickListener {
            val nextExpanded = !sectionPrefs.getBoolean(prefKey, true)
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

    override fun onResume() {
        super.onResume()
        // Ensure correct nav highlight when returning via CLEAR_TOP/SINGLE_TOP.
        binding.bottomNavigation.post {
            binding.bottomNavigation.menu.findItem(R.id.nav_settings).isChecked = true
        }
        syncLocalAgentAccessibilityStatus()
        refreshAutoAudioDebugUi()
        refreshProSubscriptionBanner()

        if (ProSubscriptionPrefs.isSubscribed(this)) {
            thread {
                ProSubscriptionVerifier.verifyNow(this)
                runOnUiThread { refreshProSubscriptionBanner() }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        recordingBannerController?.onStart()
        if (!agentReceiverRegistered) {
            LocalBroadcastManager.getInstance(this).registerReceiver(
                agentStatusReceiver,
                IntentFilter(LocalAgentIntents.ACTION_STATUS_CHANGED)
            )
            agentReceiverRegistered = true
        }
        // Ask the service (if present) to emit current status.
        LocalAgentController.requestStatus(this)
        refreshAgentStatusUi()
    }

    override fun onStop() {
        super.onStop()
        recordingBannerController?.onStop()
        if (agentReceiverRegistered) {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(agentStatusReceiver)
            agentReceiverRegistered = false
        }
    }

    /**
     * Sync the agent provider type to the AI router provider type.
     * When user selects PRO_SUBSCRIPTION, chat should use CLI_RELAY.
     * When user selects LOCAL_AGENT, chat uses LOCAL_MODELS.
     * When user selects TASKER, chat uses MOCK (Tasker automation path).
     */
    private fun syncAgentProviderToAiProvider(agentType: AgentProviderType) {
        val aiProvider = when (agentType) {
            AgentProviderType.PRO_SUBSCRIPTION -> RelayProviderType.CLI_RELAY
            AgentProviderType.LOCAL_AGENT -> RelayProviderType.LOCAL_MODELS
            AgentProviderType.TASKER -> RelayProviderType.MOCK
        }
        AiProviderPrefs.setProvider(this, aiProvider)
    }

    private fun bindProviderTypeAndLocalAgentSettings() {
        // Provider type - clear all first, then set only the correct one to avoid multi-select bug
        val initial = AutomationPrefs.getProviderType(this)
        binding.rgProviderType.clearCheck()
        when (initial) {
            AgentProviderType.TASKER -> binding.rbProviderTasker.isChecked = true
            AgentProviderType.LOCAL_AGENT -> binding.rbProviderLocalAgent.isChecked = true
            AgentProviderType.PRO_SUBSCRIPTION -> binding.rbProviderProSubscription.isChecked = true
        }
        // Sync AI provider on initial load
        syncAgentProviderToAiProvider(initial)

        // Subscribe button for Pro Subscription
        binding.btnConfigureProSubscription.setOnClickListener {
            if (ProSubscriptionPrefs.isActiveLocally(this)) {
                startActivity(Intent(this, ProSubscriptionSettingsActivity::class.java))
            } else {
                startActivity(Intent(this, ProSubscriptionActivity::class.java))
            }
        }

        // Configure button for Local Models
        binding.btnConfigureLocalModels.setOnClickListener {
            startActivity(Intent(this, com.fersaiyan.cyanbridge.agent.LocalModelsConfigureActivity::class.java))
        }

        binding.rgProviderType.setOnCheckedChangeListener { _, checkedId ->
            val newType = when (checkedId) {
                R.id.rb_provider_local_agent -> AgentProviderType.LOCAL_AGENT
                R.id.rb_provider_pro_subscription -> AgentProviderType.PRO_SUBSCRIPTION
                else -> AgentProviderType.TASKER
            }
            AutomationPrefs.setProviderType(this, newType)
            // Sync to AI router so chat uses the correct backend
            syncAgentProviderToAiProvider(newType)

            if (newType != AgentProviderType.LOCAL_AGENT) {
                CoroutineScope(Dispatchers.IO).launch {
                    runCatching { LocalChatSessionManager.unload() }
                }
            }
        }

        // Local Agent settings (always visible now)
        bindLocalAgentMemorySettings()

        binding.btnOpenAccessibilitySettings.setOnClickListener {
            runCatching {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }.onFailure {
                Toast.makeText(this, "Unable to open accessibility settings", Toast.LENGTH_SHORT)
                    .show()
            }
        }

        binding.switchLocalAgentRequireConfirmation.isChecked = AutomationPrefs.isRequireConfirmationEnabled(this)
        binding.switchLocalAgentRequireConfirmation.setOnCheckedChangeListener { _, isChecked ->
            AutomationPrefs.setRequireConfirmationEnabled(this, isChecked)
        }

        binding.editLocalAgentMaxSteps.setText(AutomationPrefs.getMaxSteps(this).toString())
        binding.editLocalAgentMaxSteps.doAfterTextChanged {
            val parsed = it?.toString()?.trim()?.toIntOrNull()
            if (parsed != null) {
                AutomationPrefs.setMaxSteps(this, parsed)
                binding.tilLocalAgentMaxSteps.error = null
            } else if (!it.isNullOrBlank()) {
                binding.tilLocalAgentMaxSteps.error = "Enter a number"
            } else {
                binding.tilLocalAgentMaxSteps.error = null
            }
        }

        syncLocalAgentAccessibilityStatus()
    }

    private fun showLogSubmissionDialog() {
        val issueTypes = arrayOf(
            "P2P/WiFi sync issue",
            "Image query failed",
            "Voice command not working",
            "BLE connection issue",
            "App crash/ANR",
            "Other/General"
        )
        var selectedType = issueTypes[0]

        val input = android.widget.EditText(this).apply {
            hint = "Describe what happened (optional)"
            minLines = 3
            setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.text_primary))
            setHintTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.text_secondary))
        }

        AlertDialog.Builder(this)
            .setTitle("Send Debug Logs")
            .setSingleChoiceItems(issueTypes, 0) { _, which ->
                selectedType = issueTypes[which]
            }
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Send") { _, _ ->
                val description = input.text?.toString()?.trim()?.take(2000) ?: "No description"
                submitDebugLogs(issueType = selectedType, description = description)
            }
            .show()
    }

    private fun submitDebugLogs(issueType: String, description: String) {
        Toast.makeText(this, "Collecting logs…", Toast.LENGTH_SHORT).show()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val logs = DebugLogSupport.collectLogcat()
                val deviceInfo = DebugLogSupport.buildDeviceInfo(this@SettingsActivity)
                val result = DebugLogSupport.sendLogsToServer(
                    context = this@SettingsActivity,
                    issueType = issueType,
                    description = description,
                    logs = logs,
                    deviceInfo = deviceInfo
                )

                withContext(Dispatchers.Main) {
                    if (result.isSuccess) {
                        Toast.makeText(
                            this@SettingsActivity,
                            "Logs sent successfully! Thank you for helping debug.",
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        Toast.makeText(
                            this@SettingsActivity,
                            "Failed to send logs: ${result.exceptionOrNull()?.message}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@SettingsActivity,
                        "Error collecting logs: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun refreshProSubscriptionBanner() {
        val subscribed = ProSubscriptionPrefs.isActiveLocally(this)
        val planRaw = ProSubscriptionPrefs.getPlan(this)
        val plan = when (planRaw.lowercase()) {
            "monthly" -> "Monthly"
            "yearly" -> "Yearly"
            else -> "Pro"
        }

        if (subscribed) {
            binding.tvProBannerTitle.text = "Pro Subscription Settings"
            binding.tvProBannerSubtitle.text = "Current plan: $plan. Manage premium features and perks."
            binding.tvProBannerBadge.text = "SETTINGS"
            binding.tvProBannerBadge.setTextColor(ContextCompat.getColor(this, R.color.cyan_accent))
            binding.btnConfigureProSubscription.setStrokeColor(
                ContextCompat.getColor(this, R.color.cyan_accent)
            )
            binding.btnConfigureProSubscription.setCardBackgroundColor(
                ContextCompat.getColor(this, R.color.cyan_dim)
            )
        } else {
            binding.tvProBannerTitle.text = "Pro Subscription"
            binding.tvProBannerSubtitle.text =
                "Unlock premium features and help fund new smartglasses support."
            binding.tvProBannerBadge.text = "OPEN"
            binding.tvProBannerBadge.setTextColor(ContextCompat.getColor(this, R.color.cyan_accent))
            binding.btnConfigureProSubscription.setStrokeColor(
                ContextCompat.getColor(this, R.color.text_secondary)
            )
            binding.btnConfigureProSubscription.setCardBackgroundColor(
                ContextCompat.getColor(this, R.color.card_bg)
            )
        }
    }

    private fun bindLocalAgentMemorySettings() {
        MemoryVaultBootstrap.ensureInitialized(this)

        // Auto capture toggle
        val autoCaptureEnabled = AutomationPrefs.isAutoCaptureEnabled(this) && MemoryModeManager.isScreenOcrCaptureEnabled(this)
        binding.switchLocalAgentAutoCapture.isChecked = autoCaptureEnabled
        binding.switchLocalAgentAutoCapture.setOnCheckedChangeListener { _, isChecked ->
            AutomationPrefs.setAutoCaptureEnabled(this, isChecked)
            MemoryModeManager.setScreenOcrCaptureEnabled(this, isChecked)
            Toast.makeText(
                this,
                if (isChecked) "Auto-capture enabled" else "Auto-capture disabled",
                Toast.LENGTH_SHORT
            ).show()
        }

        // Interval minutes
        binding.editLocalAgentCaptureIntervalMin.setText(AutomationPrefs.getCaptureIntervalMin(this).toString())
        binding.editLocalAgentCaptureIntervalMin.doAfterTextChanged {
            val parsed = it?.toString()?.trim()?.toIntOrNull()
            if (parsed != null) {
                AutomationPrefs.setCaptureIntervalMin(this, parsed)
                binding.tilLocalAgentCaptureIntervalMin.error = null
            } else if (!it.isNullOrBlank()) {
                binding.tilLocalAgentCaptureIntervalMin.error = "Enter a number"
            } else {
                binding.tilLocalAgentCaptureIntervalMin.error = null
            }
        }

        binding.btnLocalAgentBlacklistApps.setOnClickListener {
            startActivity(Intent(this, AppBlacklistActivity::class.java))
        }

        binding.btnLocalAgentViewScreenCaptures.setOnClickListener {
            startActivity(Intent(this, ScreenCapturesActivity::class.java))
        }

        binding.switchLocalAgentDailyFactsReminder.isChecked = AutomationPrefs.isDailyFactsReminderEnabled(this)
        binding.switchLocalAgentDailyFactsReminder.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    !XXPermissions.isGranted(this, Manifest.permission.POST_NOTIFICATIONS)
                ) {
                    XXPermissions.with(this)
                        .permission(Manifest.permission.POST_NOTIFICATIONS)
                        .request { _, allGranted ->
                            if (allGranted) {
                                binding.switchLocalAgentDailyFactsReminder.isChecked = true
                                AutomationPrefs.setDailyFactsReminderEnabled(this, true)
                                DailyFactsReminderScheduler.scheduleIfEnabled(this, enabled = true)
                                Toast.makeText(this, "Daily facts reminder enabled", Toast.LENGTH_SHORT).show()
                            } else {
                                binding.switchLocalAgentDailyFactsReminder.isChecked = false
                                AutomationPrefs.setDailyFactsReminderEnabled(this, false)
                                DailyFactsReminderScheduler.scheduleIfEnabled(this, enabled = false)
                                Toast.makeText(
                                    this,
                                    "Notifications permission denied.",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    return@setOnCheckedChangeListener
                }
            }
            AutomationPrefs.setDailyFactsReminderEnabled(this, isChecked)
            DailyFactsReminderScheduler.scheduleIfEnabled(this, enabled = isChecked)
            Toast.makeText(
                this,
                if (isChecked) "Daily facts reminder enabled" else "Daily facts reminder disabled",
                Toast.LENGTH_SHORT
            ).show()
        }

        binding.btnLocalAgentEditDailyFacts.setOnClickListener {
            startActivity(
                Intent(this, DailyFactsActivity::class.java)
                    .putExtra(DailyFactsActivity.EXTRA_MODE, DailyFactsActivity.MODE_DRAFT)
            )
        }

        binding.btnLocalAgentViewConfirmedDailyFacts.setOnClickListener {
            startActivity(
                Intent(this, DailyFactsActivity::class.java)
                    .putExtra(DailyFactsActivity.EXTRA_MODE, DailyFactsActivity.MODE_CONFIRMED)
            )
        }

        binding.btnLocalAgentViewDailySummary.setOnClickListener {
            startActivity(Intent(this, DailySummaryActivity::class.java))
        }

        binding.editLocalAgentDailySummaryRefreshHours.setText(
            AutomationPrefs.getDailySummaryAutoRefreshHours(this).toString(),
        )
        binding.editLocalAgentDailySummaryRefreshHours.doAfterTextChanged {
            val parsed = it?.toString()?.trim()?.toIntOrNull()
            if (parsed != null && parsed in 1..24) {
                AutomationPrefs.setDailySummaryAutoRefreshHours(this, parsed)
                binding.tilLocalAgentDailySummaryRefreshHours.error = null
            } else if (!it.isNullOrBlank()) {
                binding.tilLocalAgentDailySummaryRefreshHours.error = "Enter 1-24"
            } else {
                binding.tilLocalAgentDailySummaryRefreshHours.error = null
            }
        }

        binding.switchLocalAgentAutoSaveDailyFacts.isChecked = ChatMemoryPrefs.isAutoSaveDailyFactsEnabled(this)
        binding.switchLocalAgentAutoSaveDailyFacts.setOnCheckedChangeListener { _, isChecked ->
            ChatMemoryPrefs.setAutoSaveDailyFactsEnabled(this, isChecked)
            Toast.makeText(
                this,
                if (isChecked) "Auto-save daily facts enabled" else "Auto-save daily facts disabled",
                Toast.LENGTH_SHORT
            ).show()
        }

        binding.switchLocalAgentExtractUserFactCandidates.isChecked = ChatMemoryPrefs.isExtractUserFactCandidatesEnabled(this)
        binding.switchLocalAgentExtractUserFactCandidates.setOnCheckedChangeListener { _, isChecked ->
            ChatMemoryPrefs.setExtractUserFactCandidatesEnabled(this, isChecked)
            Toast.makeText(
                this,
                if (isChecked) "Candidate user facts extraction enabled" else "Candidate user facts extraction disabled",
                Toast.LENGTH_SHORT
            ).show()
        }

        // Custom bullet prompt settings
        binding.btnLocalAgentEditBulletPrompt.setOnClickListener {
            val currentPrompt = com.fersaiyan.cyanbridge.localagent.dailyfacts.DailyBulletsSettings.getCustomBulletPrompt(this)
                .ifBlank { DEFAULT_BULLET_PROMPT }
            showTextEditorDialog(
                title = "Bullet Generation Prompt",
                initial = currentPrompt,
                hint = "Leave empty to use default prompt"
            ) { updated ->
                com.fersaiyan.cyanbridge.localagent.dailyfacts.DailyBulletsSettings.setCustomBulletPrompt(this, updated)
                Toast.makeText(this, "Custom bullet prompt saved", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnLocalAgentResetBulletPrompt.setOnClickListener {
            com.fersaiyan.cyanbridge.localagent.dailyfacts.DailyBulletsSettings.setCustomBulletPrompt(this, "")
            Toast.makeText(this, "Bullet prompt reset to default", Toast.LENGTH_SHORT).show()
        }

        // Max tokens per bullet setting
        binding.etMaxTokensPerBullet.setText(
            com.fersaiyan.cyanbridge.localagent.dailyfacts.DailyBulletsSettings.getMaxTokensPerBullet(this).toString()
        )
        binding.etMaxTokensPerBullet.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val tokens = binding.etMaxTokensPerBullet.text.toString().toIntOrNull() ?: 0
                com.fersaiyan.cyanbridge.localagent.dailyfacts.DailyBulletsSettings.setMaxTokensPerBullet(this, tokens)
            }
        }

        binding.btnLocalAgentEditPersona.setOnClickListener {
            LocalAgentMemoryStore.ensureSeedFiles(this)
            val f = LocalAgentMemoryStore.agentPersonaFile(this)
            showTextEditorDialog(
                title = "Agent personality",
                initial = LocalAgentMemoryStore.readText(f),
            ) { updated ->
                LocalAgentMemoryStore.writeText(f, updated)
                Toast.makeText(this, "Saved agent personality", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnLocalAgentEditUserFacts.setOnClickListener {
            LocalAgentMemoryStore.ensureSeedFiles(this)
            val f = LocalAgentMemoryStore.userFactsFile(this)
            showTextEditorDialog(
                title = "User facts",
                initial = LocalAgentMemoryStore.readText(f),
            ) { updated ->
                LocalAgentMemoryStore.writeText(f, updated)
                Toast.makeText(this, "Saved user facts", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnLocalAgentViewContextDebug.setOnClickListener {
            val debugText = AgentRuntimePrefs.getLastContextInjectionDebug(this)
            val atMs = AgentRuntimePrefs.getLastContextInjectionAtMs(this)
            if (debugText.isBlank()) {
                Toast.makeText(this, "No context injection recorded yet.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val timeStr = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                .format(java.util.Date(atMs))
            val msg = "Last injected: $timeStr\n\n$debugText"
            AlertDialog.Builder(this)
                .setTitle("Context Injection Debug")
                .setMessage(msg)
                .setPositiveButton("Close", null)
                .show()
        }
    }

    private fun bindMemoryVaultSettings() {
        MemoryVaultBootstrap.ensureInitialized(this)

        val isProSubscribed = ProSubscriptionPrefs.isActiveLocally(this)

        fun refreshUi() {
            val mode = MemoryModeManager.getSelectedMode(this)
            binding.tvMemoryModeCurrent.text = "Current mode: ${mode.title}"
            binding.tvMemoryModeHint.text = MemoryModeManager.modeAvailabilityText(mode)
            binding.tvMemorySyncStatus.text = "Encrypted Sync: ${MemoryModeManager.modeAvailabilityText(MemoryPrivacyMode.ENCRYPTED_SYNC)}"
            binding.tvMemoryCloudStatus.text =
                "Cloud: ${MemoryModeManager.modeAvailabilityText(MemoryPrivacyMode.FAST_CLOUD_MEMORY)}\n" +
                    "Confidential: ${MemoryModeManager.modeAvailabilityText(MemoryPrivacyMode.CONFIDENTIAL_CLOUD_BETA)}"

            suppressMemoryModeToggle = true
            binding.rgMemoryMode.clearCheck()
            when (mode) {
                MemoryPrivacyMode.PRIVATE_LOCAL -> binding.rbMemoryModePrivateLocal.isChecked = true
                MemoryPrivacyMode.ENCRYPTED_SYNC -> binding.rbMemoryModeEncryptedSync.isChecked = true
                MemoryPrivacyMode.FAST_CLOUD_MEMORY -> binding.rbMemoryModeFastCloud.isChecked = true
                MemoryPrivacyMode.CONFIDENTIAL_CLOUD_BETA -> binding.rbMemoryModeConfidentialCloud.isChecked = true
            }
            suppressMemoryModeToggle = false

            binding.rbMemoryModeEncryptedSync.isEnabled = isProSubscribed
            binding.rbMemoryModeFastCloud.isEnabled = isProSubscribed
            binding.rbMemoryModeConfidentialCloud.isEnabled = isProSubscribed

            if (!isProSubscribed) {
                binding.rbMemoryModeEncryptedSync.alpha = 0.5f
                binding.rbMemoryModeFastCloud.alpha = 0.5f
                binding.rbMemoryModeConfidentialCloud.alpha = 0.5f
            } else {
                binding.rbMemoryModeEncryptedSync.alpha = 1.0f
                binding.rbMemoryModeFastCloud.alpha = 1.0f
                binding.rbMemoryModeConfidentialCloud.alpha = 1.0f
            }

            binding.switchMemorySyncExplicit.isChecked =
                MemoryModeManager.isSourceSyncEnabled(this, MemorySourceType.EXPLICIT_USER_FACT)
            binding.switchMemorySyncDaily.isChecked =
                MemoryModeManager.isSourceSyncEnabled(this, MemorySourceType.AUTO_DAILY_FACT)
            binding.switchMemorySyncOcr.isChecked =
                MemoryModeManager.isSourceSyncEnabled(this, MemorySourceType.SCREEN_OCR)
            binding.switchMemorySyncDerived.isChecked =
                MemoryModeManager.isSourceSyncEnabled(this, MemorySourceType.DERIVED_SUMMARY)

            val retention = MemoryModeManager.getScreenOcrRetentionDays(this)
            val currentRetentionText = binding.editMemoryOcrRetentionDays.text?.toString()?.trim()
            if (currentRetentionText != retention.toString()) {
                binding.editMemoryOcrRetentionDays.setText(retention.toString())
            }

            val isLocked = VaultLockStateManager.isLocked(this)
            val requiresPassphrase = VaultLockStateManager.requiresPassphrase(this)
            binding.tvMemoryVaultLockState.text = buildString {
                append("Vault is ")
                append(if (isLocked) "LOCKED" else "UNLOCKED")
                append(".")
                if (requiresPassphrase) append(" Passphrase required for unlock.")
            }
        }

        refreshUi()

        binding.rgMemoryMode.setOnCheckedChangeListener { _, checkedId ->
            if (suppressMemoryModeToggle) return@setOnCheckedChangeListener
            val mode = when (checkedId) {
                R.id.rb_memory_mode_encrypted_sync -> MemoryPrivacyMode.ENCRYPTED_SYNC
                R.id.rb_memory_mode_fast_cloud -> MemoryPrivacyMode.FAST_CLOUD_MEMORY
                R.id.rb_memory_mode_confidential_cloud -> MemoryPrivacyMode.CONFIDENTIAL_CLOUD_BETA
                else -> MemoryPrivacyMode.PRIVATE_LOCAL
            }
            MemoryModeManager.setSelectedMode(this, mode)
            if (mode != MemoryPrivacyMode.ENCRYPTED_SYNC) {
                CoroutineScope(Dispatchers.IO).launch {
                    MemorySyncPreparationService.cancelAllQueued("Mode switched away from Encrypted Sync")
                }
            }
            refreshUi()
        }

        binding.switchMemorySyncExplicit.setOnCheckedChangeListener { _, isChecked ->
            MemoryModeManager.setSourceSyncEnabled(this, MemorySourceType.EXPLICIT_USER_FACT, isChecked)
            if (!isChecked) {
                CoroutineScope(Dispatchers.IO).launch {
                    MemorySyncPreparationService.cancelAllQueued("Sync eligibility tightened for explicit facts")
                }
            }
        }
        binding.switchMemorySyncDaily.setOnCheckedChangeListener { _, isChecked ->
            MemoryModeManager.setSourceSyncEnabled(this, MemorySourceType.AUTO_DAILY_FACT, isChecked)
            if (!isChecked) {
                CoroutineScope(Dispatchers.IO).launch {
                    MemorySyncPreparationService.cancelAllQueued("Sync eligibility tightened for daily facts")
                }
            }
        }
        binding.switchMemorySyncOcr.setOnCheckedChangeListener { _, isChecked ->
            MemoryModeManager.setSourceSyncEnabled(this, MemorySourceType.SCREEN_OCR, isChecked)
            if (!isChecked) {
                CoroutineScope(Dispatchers.IO).launch {
                    MemorySyncPreparationService.cancelAllQueued("Sync eligibility tightened for screen OCR")
                }
            }
        }
        binding.switchMemorySyncDerived.setOnCheckedChangeListener { _, isChecked ->
            MemoryModeManager.setSourceSyncEnabled(this, MemorySourceType.DERIVED_SUMMARY, isChecked)
            if (!isChecked) {
                CoroutineScope(Dispatchers.IO).launch {
                    MemorySyncPreparationService.cancelAllQueued("Sync eligibility tightened for derived summaries")
                }
            }
        }

        binding.editMemoryOcrRetentionDays.doAfterTextChanged {
            val value = it?.toString()?.trim()?.toIntOrNull()
            if (value == null) {
                if (!it.isNullOrBlank()) {
                    binding.tilMemoryOcrRetentionDays.error = "Enter a valid number"
                } else {
                    binding.tilMemoryOcrRetentionDays.error = null
                }
                return@doAfterTextChanged
            }
            binding.tilMemoryOcrRetentionDays.error = null
            MemoryModeManager.setScreenOcrRetentionDays(this, value)
            CoroutineScope(Dispatchers.IO).launch {
                MemoryVaultService.enforceScreenOcrRetention(this@SettingsActivity)
            }
        }

        binding.btnMemoryDeletePassiveCapture.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Delete passive OCR capture?")
                .setMessage("This deletes local OCR snapshots and their search index artifacts. This cannot be undone.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete") { _, _ ->
                    CoroutineScope(Dispatchers.IO).launch {
                        LocalAgentMemoryStore.deleteAllPassiveCapture(this@SettingsActivity)
                        runOnUiThread {
                            Toast.makeText(this@SettingsActivity, "Passive OCR capture deleted", Toast.LENGTH_SHORT).show()
                            refreshUi()
                        }
                    }
                }
                .show()
        }

        binding.btnMemoryLock.setOnClickListener {
            VaultLockStateManager.lock(this)
            Toast.makeText(this, "Vault locked", Toast.LENGTH_SHORT).show()
            refreshUi()
        }

        binding.btnMemoryUnlock.setOnClickListener {
            if (VaultLockStateManager.requiresPassphrase(this)) {
                showPassphraseDialog(
                    title = "Unlock vault",
                    onSubmit = { passphrase ->
                        val ok = VaultLockStateManager.unlockWithPassphrase(this, passphrase.toCharArray())
                        Toast.makeText(
                            this,
                            if (ok) "Vault unlocked" else "Invalid passphrase",
                            Toast.LENGTH_SHORT,
                        ).show()
                        refreshUi()
                    }
                )
            } else {
                val ok = VaultLockStateManager.unlockWithDevice(this)
                Toast.makeText(this, if (ok) "Vault unlocked" else "Unable to unlock vault", Toast.LENGTH_SHORT).show()
                refreshUi()
            }
        }

        binding.btnMemorySetPassphrase.setOnClickListener {
            showPassphraseDialog(
                title = "Set vault passphrase",
                onSubmit = { passphrase ->
                    val ok = VaultLockStateManager.setPassphrase(this, passphrase.toCharArray())
                    Toast.makeText(
                        this,
                        if (ok) "Passphrase set. Vault locked." else "Could not set passphrase. Unlock vault first.",
                        Toast.LENGTH_LONG,
                    ).show()
                    refreshUi()
                }
            )
        }

        binding.btnMemoryClearPassphrase.setOnClickListener {
            VaultLockStateManager.clearPassphrase(this)
            Toast.makeText(this, "Passphrase requirement cleared", Toast.LENGTH_SHORT).show()
            refreshUi()
        }

        binding.btnMemoryResetVault.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Reset memory vault?")
                .setMessage("This removes encrypted memory payloads, policy metadata, sync queue state, and lock keys. Existing plain files remain. This cannot be undone.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Reset") { _, _ ->
                    CoroutineScope(Dispatchers.IO).launch {
                        LocalAgentMemoryStore.resetVault(this@SettingsActivity)
                        LocalAgentMemoryStore.ensureSeedFiles(this@SettingsActivity)
                        runOnUiThread {
                            Toast.makeText(this@SettingsActivity, "Memory vault reset", Toast.LENGTH_LONG).show()
                            refreshUi()
                        }
                    }
                }
                .show()
        }
    }

    private fun showPassphraseDialog(
        title: String,
        onSubmit: (String) -> Unit,
    ) {
        val input = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.text_primary))
            setHintTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.text_secondary))
            hint = "Passphrase"
        }

        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("OK") { _, _ ->
                val pass = input.text?.toString().orEmpty()
                if (pass.isBlank()) {
                    Toast.makeText(this, "Passphrase cannot be empty", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                onSubmit(pass)
            }
            .show()
    }

    private fun showTextEditorDialog(
        title: String,
        initial: String,
        hint: String? = null,
        onSave: (String) -> Unit,
    ) {
        val input = android.widget.EditText(this).apply {
            setText(initial)
            setSelection(text?.length ?: 0)
            setHint(hint)
            minLines = 8
            setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.text_primary))
            setHintTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.text_secondary))
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                onSave(input.text?.toString().orEmpty())
            }
            .show()
    }

    private fun isLocalAgentAccessibilityServiceEnabled(): Boolean {
        val enabled = Settings.Secure.getInt(
            contentResolver,
            Settings.Secure.ACCESSIBILITY_ENABLED,
            0
        ) == 1
        if (!enabled) return false
        val expected = ComponentName(this, LocalAgentAccessibilityService::class.java)
            .flattenToString()
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.split(':').any {
            it.equals(expected, ignoreCase = true)
        }
    }

    private fun syncLocalAgentAccessibilityStatus() {
        val on = isLocalAgentAccessibilityServiceEnabled()
        binding.tvLocalAgentAccessibilityStatus.text = if (on) "Enabled" else "Disabled"
        binding.tvLocalAgentAccessibilityStatus.setTextColor(
            ContextCompat.getColor(this, if (on) R.color.cyan_accent else R.color.danger)
        )
    }

    private fun bindPrivacyToggles() {
        binding.switchTranscriptStorage.isChecked = PrivacyPrefs.isTranscriptStorageEnabled(this)
        binding.switchRedactNames.isChecked = PrivacyPrefs.isRedactNamesEnabled(this)
        binding.switchIncludeFullTranscription.isChecked = PrivacyPrefs.isIncludeFullTranscriptionInExportsEnabled(this)

        binding.switchTranscriptStorage.setOnCheckedChangeListener { _, isChecked ->
            PrivacyPrefs.setTranscriptStorageEnabled(this, isChecked)
        }
        binding.switchRedactNames.setOnCheckedChangeListener { _, isChecked ->
            PrivacyPrefs.setRedactNamesEnabled(this, isChecked)
        }
        binding.switchIncludeFullTranscription.setOnCheckedChangeListener { _, isChecked ->
            PrivacyPrefs.setIncludeFullTranscriptionInExportsEnabled(this, isChecked)
        }
    }

    private fun hasPostNotificationsPermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                XXPermissions.isGranted(this, Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun setAutoAudioSwitchChecked(checked: Boolean) {
        suppressAutoAudioToggle = true
        binding.switchAutoAudioCapture.isChecked = checked
        suppressAutoAudioToggle = false
    }

    private fun requestPostNotificationsPermission(onResult: (Boolean) -> Unit) {
        XXPermissions.with(this)
            .permission(Manifest.permission.POST_NOTIFICATIONS)
            .request { _, allGranted ->
                onResult(allGranted)
            }
    }

    private fun bindAutoAudioCapture() {
        val enabled = AutoAudioCapturePrefs.isEnabled(this)
        setAutoAudioSwitchChecked(enabled)
        binding.switchAutoAudioVisualNotes.isChecked = AutoAudioCapturePrefs.isVisualNotesEnabled(this)
        binding.switchAutoAudioVisualNotes.setOnCheckedChangeListener { _, isChecked ->
            AutoAudioCapturePrefs.setVisualNotesEnabled(this, isChecked)
            refreshAutoAudioDebugUi()
        }

        binding.switchAutoAudioSpeechExtend.isChecked = AutoAudioCapturePrefs.isSpeechExtendEnabled(this)
        binding.switchAutoAudioSpeechExtend.setOnCheckedChangeListener { _, isChecked ->
            AutoAudioCapturePrefs.setSpeechExtendEnabled(this, isChecked)
            refreshAutoAudioDebugUi()
        }

        binding.editAutoAudioLoopsBeforeSync.setText(AutoAudioCapturePrefs.getLoopsPerSync(this).toString())
        binding.editAutoAudioLoopsBeforeSync.doAfterTextChanged {
            val raw = it?.toString()?.trim().orEmpty()
            binding.tilAutoAudioLoopsBeforeSync.error = when {
                raw.isBlank() -> null
                raw.toIntOrNull() == null -> "Enter a number"
                raw.toInt() !in 1..96 -> "Use 1 to 96"
                else -> null
            }
        }
        binding.editAutoAudioLoopsBeforeSync.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                applyAutoAudioLoopsBeforeSyncSetting()
                refreshAutoAudioDebugUi()
            }
        }
        binding.editAutoAudioLoopsBeforeSync.setOnEditorActionListener { _, _, _ ->
            applyAutoAudioLoopsBeforeSyncSetting()
            refreshAutoAudioDebugUi()
            false
        }

        binding.switchAutoAudioCapture.setOnCheckedChangeListener { _, isChecked ->
            if (suppressAutoAudioToggle) return@setOnCheckedChangeListener

            if (isChecked) {
                // Android 13+ requires POST_NOTIFICATIONS at runtime, otherwise foreground-service
                // notifications may not appear, and the OS may kill/restart the service.
                if (!hasPostNotificationsPermission()) {
                    requestPostNotificationsPermission { granted ->
                        if (granted) {
                            setAutoAudioSwitchChecked(true)
                            enableAutoAudioCapture()
                        } else {
                            Toast.makeText(
                                this,
                                "Notifications permission denied. Auto audio capture needs a foreground notification.",
                                Toast.LENGTH_LONG
                            ).show()
                            setAutoAudioSwitchChecked(false)
                            disableAutoAudioCapture()
                        }
                        refreshAutoAudioDebugUi()
                    }
                    return@setOnCheckedChangeListener
                }
                enableAutoAudioCapture()
            } else {
                disableAutoAudioCapture()
            }
            refreshAutoAudioDebugUi()
        }

        // If auto-audio is already enabled (e.g., from an older build), request notification permission
        // immediately so the foreground service can actually show its notification.
        if (enabled && !hasPostNotificationsPermission()) {
            requestPostNotificationsPermission { granted ->
                if (!granted) {
                    Toast.makeText(
                        this,
                        "Enable notifications to keep auto audio capture running in the background.",
                        Toast.LENGTH_LONG
                    ).show()
                    setAutoAudioSwitchChecked(false)
                    disableAutoAudioCapture()
                }
                refreshAutoAudioDebugUi()
            }
        } else {
            refreshAutoAudioDebugUi()
        }
    }

    private fun applyAutoAudioLoopsBeforeSyncSetting() {
        val raw = binding.editAutoAudioLoopsBeforeSync.text?.toString()?.trim().orEmpty()
        val parsed = raw.toIntOrNull()
        if (parsed == null || parsed !in 1..96) {
            if (raw.isNotBlank()) {
                binding.tilAutoAudioLoopsBeforeSync.error = "Use 1 to 96"
            }
            return
        }
        binding.tilAutoAudioLoopsBeforeSync.error = null
        AutoAudioCapturePrefs.setLoopsPerSync(this, parsed)
    }

    private fun enableAutoAudioCapture() {
        AutoAudioCapturePrefs.setEnabled(this, true)
        applyAutoAudioLoopsBeforeSyncSetting()
        val loopsPerSync = AutoAudioCapturePrefs.getLoopsPerSync(this)
        Toast.makeText(this, "Auto audio capture enabled (sync every $loopsPerSync loops)", Toast.LENGTH_SHORT).show()
        AutoAudioCaptureService.start(this)
    }

    private fun disableAutoAudioCapture() {
        AutoAudioCapturePrefs.setEnabled(this, false)
        Toast.makeText(this, "Auto audio capture disabled", Toast.LENGTH_SHORT).show()
        AutoAudioCaptureService.stop(this)
    }

    private fun refreshAutoAudioDebugUi() {
        val enabled = AutoAudioCapturePrefs.isEnabled(this)
        val lastReason = AutoAudioCapturePrefs.getLastPauseReason(this).ifBlank { "(none)" }
        val loopsPerSync = AutoAudioCapturePrefs.getLoopsPerSync(this)
        val visualNotes = AutoAudioCapturePrefs.isVisualNotesEnabled(this)
        val speechExtend = AutoAudioCapturePrefs.isSpeechExtendEnabled(this)

        val permOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            XXPermissions.isGranted(this, Manifest.permission.POST_NOTIFICATIONS)
        } else {
            true
        }

        val appNotifsEnabled = NotificationManagerCompat.from(this).areNotificationsEnabled()

        val channelText = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
            val ch = nm.getNotificationChannel("auto_audio_capture")
            val importance = ch?.importance
            val impText = when (importance) {
                null -> "missing"
                android.app.NotificationManager.IMPORTANCE_NONE -> "blocked"
                android.app.NotificationManager.IMPORTANCE_MIN -> "min"
                android.app.NotificationManager.IMPORTANCE_LOW -> "low"
                android.app.NotificationManager.IMPORTANCE_DEFAULT -> "default"
                android.app.NotificationManager.IMPORTANCE_HIGH -> "high"
                else -> importance.toString()
            }
            "channel=$impText"
        } else {
            ""
        }

        val stateText = if (enabled) "auto-audio: ON" else "auto-audio: OFF"
        val permText = if (permOk) "perm=ok" else "perm=blocked"
        val appText = if (appNotifsEnabled) "appNotifs=on" else "appNotifs=off"

        binding.tvAutoAudioDebug.text = listOf(
            stateText,
            "syncEvery=${loopsPerSync}x15m",
            "visualNotes=${if (visualNotes) "on" else "off"}",
            "speechExtend=${if (speechExtend) "on" else "off"}",
            permText,
            appText,
            channelText,
            "last=$lastReason"
        ).filter { it.isNotBlank() }.joinToString(" · ")
    }

    private fun bindClearData() {
        binding.btnExportLocalData.setOnClickListener {
            val fileName = "cyanbridge_backup_${java.text.SimpleDateFormat("yyyyMMdd_HHmm", java.util.Locale.US).format(java.util.Date())}.zip"
            exportDataLauncher.launch(fileName)
        }

        binding.btnImportLocalData.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Import local data?")
                .setMessage("This will overwrite current local chats, memory files, recordings, and settings from the selected backup ZIP.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Import") { _, _ ->
                    importDataLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
                }
                .show()
        }

        binding.btnClearLocalData.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Clear local data?")
                .setMessage(
                    "This will delete all chats, notes, capture sessions, and audio recordings stored on this device. This cannot be undone."
                )
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Clear") { _, _ ->
                    Toast.makeText(this, "Clearing data…", Toast.LENGTH_SHORT).show()
                    thread {
                        val result = LocalDataClearer.clearAll(this)
                        runOnUiThread {
                            if (result.errors.isEmpty()) {
                                Toast.makeText(
                                    this,
                                    "Local data cleared (deleted files: ${result.deletedFiles})",
                                    Toast.LENGTH_LONG
                                ).show()
                            } else {
                                Toast.makeText(
                                    this,
                                    "Cleared with warnings: ${result.errors.joinToString()}",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
                }
                .show()
        }
    }

    private fun exportLocalDataToUri(uri: Uri) {
        Toast.makeText(this, "Exporting data…", Toast.LENGTH_SHORT).show()
        thread {
            runCatching {
                LocalDataBackupManager.exportToZip(this, uri)
            }.onSuccess { result ->
                runOnUiThread {
                    Toast.makeText(
                        this,
                        "Export complete: ${result.threadCount} chats, ${result.messageCount} messages, ${result.memoryFileCount} memory files, ${result.recordingFileCount} recordings, ${result.vaultItemCount} vault items.",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }.onFailure { e ->
                runOnUiThread {
                    Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun importLocalDataFromUri(uri: Uri) {
        Toast.makeText(this, "Importing data…", Toast.LENGTH_SHORT).show()
        thread {
            runCatching {
                LocalDataBackupManager.importFromZip(this, uri)
            }.onSuccess { result ->
                runOnUiThread {
                    refreshProSubscriptionBanner()
                    refreshMessagesDependentUiAfterImport()
                    Toast.makeText(
                        this,
                        "Import complete: ${result.threadCount} chats, ${result.messageCount} messages, ${result.memoryFileCount} memory files, ${result.recordingFileCount} recordings, ${result.vaultItemCount} vault items.",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }.onFailure { e ->
                runOnUiThread {
                    Toast.makeText(this, "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun refreshMessagesDependentUiAfterImport() {
        // Keep this lightweight for now; major lists refresh when users reopen screens.
        syncLocalAgentAccessibilityStatus()
        refreshAutoAudioDebugUi()
    }

    private fun bindAgentControls() {
        // Unified action approval settings
        binding.switchRequireActionConfirmation.isChecked = AgentRuntimePrefs.isRequireActionConfirmationEnabled(this)
        binding.switchRequireActionConfirmation.setOnCheckedChangeListener { _, isChecked ->
            AgentRuntimePrefs.setRequireActionConfirmationEnabled(this, isChecked)
            binding.switchAutoExecuteLowRisk.isEnabled = isChecked
            binding.btnViewPendingActions.isEnabled = isChecked
        }

        binding.switchAutoExecuteLowRisk.isChecked = AgentRuntimePrefs.isAutoExecuteLowRiskEnabled(this)
        binding.switchAutoExecuteLowRisk.isEnabled = binding.switchRequireActionConfirmation.isChecked
        binding.switchAutoExecuteLowRisk.setOnCheckedChangeListener { _, isChecked ->
            AgentRuntimePrefs.setAutoExecuteLowRiskEnabled(this, isChecked)
        }

        binding.btnViewPendingActions.isEnabled = binding.switchRequireActionConfirmation.isChecked
        binding.btnViewPendingActions.setOnClickListener {
            startActivity(Intent(this, PendingActionsActivity::class.java))
        }

        binding.btnAgentStart.setOnClickListener {
            runAgentCommand(optimisticStatus = "Starting…") {
                LocalAgentController.start(this)
            }
        }
        binding.btnAgentStop.setOnClickListener {
            runAgentCommand(optimisticStatus = "Stopping…") {
                LocalAgentController.stop(this)
            }
        }
        binding.btnAgentDemo.setOnClickListener {
            Toast.makeText(
                this,
                "Demo: I will read the screen content through your glasses in 5 seconds…",
                Toast.LENGTH_LONG
            ).show()
            runAgentCommand(optimisticStatus = "Running demo…") {
                LocalAgentController.demo(this)
            }
        }
    }

    private fun runAgentCommand(
        optimisticStatus: String,
        block: () -> LocalAgentController.CommandResult,
    ) {
        val res = block()
        if (res.ok) {
            AgentRuntimePrefs.setStatus(this, optimisticStatus)
            AgentRuntimePrefs.clearLastError(this)
        } else {
            AgentRuntimePrefs.setStatus(this, "Error")
            AgentRuntimePrefs.setLastError(this, res.error ?: res.userMessage)
        }
        refreshAgentStatusUi()
        Toast.makeText(this, res.userMessage, Toast.LENGTH_SHORT).show()

        // Ask the service (if present) to emit a fresh status update.
        LocalAgentController.requestStatus(this)
    }

    private fun refreshAgentStatusUi() {
        // ViewBinding uses camelCase IDs derived from XML.
        binding.tvAgentStatus.text = "Status: ${AgentRuntimePrefs.getStatus(this)}"
        binding.tvAgentLastError.text = "Last error: ${AgentRuntimePrefs.getLastError(this)}"
    }

    private fun setupBottomNavigation() {
        binding.bottomNavigation.selectedItemId = R.id.nav_settings
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_settings -> true
                R.id.nav_glasses -> {
                    binding.bottomNavigation.post {
                        startActivity(Intent(this, MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        })
                    }
                    true
                }
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
                        startActivity(
                            Intent(
                                this,
                                com.fersaiyan.cyanbridge.ui.recordings.RecordingsListActivity::class.java
                            ).apply {
                                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            }
                        )
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
}

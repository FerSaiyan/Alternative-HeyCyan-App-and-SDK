package com.fersaiyan.cyanbridge.agent

import com.fersaiyan.cyanbridge.shared.billing.ProSubscriptionSettingsUiState
import kotlin.math.roundToInt

/**
 * Working values behind the Pro settings screen.
 *
 * Handlers mutate the draft freely; [ProSubscriptionSettingsActivity] publishes it to Compose only at
 * the points where it always refreshed the screen, so on-screen update timing is unchanged.
 * Initial values match what the screen showed before any handler ran.
 */
internal class ProSubscriptionSettingsDraft {
    var planStatus = ""
    var plan = ""
    var expires = ""
    var verified = ""
    var accountEmail = ""
    var accountToken = ""
    var accountSubscription = ""
    var quotaStatus = "Quota: unknown"
    var quotaBreakdown = ""
    var quotaProgressVisible = false
    var quotaProgressIndeterminate = false
    var quotaProgress = 0
    var betaStatus = "Not signed up yet."
    var cloudSync = false
    var prioritySupport = false
    var pluginRewards = false
    var earlyAccessDevices = false
    var backupFrequencyIndex = 0
    var supportChannelIndex = 0
    var requestsModelIndex = 0
    var questionsModelIndex = 0
    var tasksModelIndex = 0

    fun showQuotaLoading(model: String) {
        quotaStatus = "Quota: loading for model '$model'..."
        quotaProgressVisible = true
        quotaProgressIndeterminate = true
    }

    fun showQuotaError(message: String) {
        quotaStatus = message
        quotaProgressVisible = false
        quotaProgressIndeterminate = false
    }

    fun showQuotaInfo(quota: ProSubscriptionRelayClient.QuotaInfo) {
        val displayModel = quota.model.removeSuffix("/free").ifBlank { quota.model }
        val resetText = if (quota.resetAtMs > 0L) {
            "Resets ${ProSubscriptionSettingsFormat.resetTime(quota.resetAtMs)}"
        } else {
            ""
        }

        if (quota.limit > 0) {
            val usedPercent = ((quota.used.toDouble() / quota.limit.toDouble()) * 100.0)
                .coerceIn(0.0, 100.0)
                .roundToInt()
            quotaStatus = "Quota ($displayModel): $usedPercent% used"
            quotaBreakdown = buildString {
                append("${ProSubscriptionSettingsFormat.compactCount(quota.remaining)} left")
                append(
                    " · ${ProSubscriptionSettingsFormat.compactCount(quota.used)}/" +
                        "${ProSubscriptionSettingsFormat.compactCount(quota.limit)} used",
                )
                if (resetText.isNotBlank()) {
                    append(" · $resetText")
                }
            }
            quotaProgressVisible = true
            quotaProgressIndeterminate = false
            quotaProgress = usedPercent
        } else {
            quotaStatus = "Quota ($displayModel): ${ProSubscriptionSettingsFormat.compactCount(quota.remaining)} left"
            quotaBreakdown = buildString {
                append("${ProSubscriptionSettingsFormat.compactCount(quota.used)} used")
                if (resetText.isNotBlank()) {
                    append(" · $resetText")
                }
            }
            quotaProgressVisible = false
            quotaProgressIndeterminate = false
        }
    }

    fun toUiState(models: ProSubscriptionModelLabels, systemPrompt: String) = ProSubscriptionSettingsUiState(
        planStatus = planStatus,
        plan = plan,
        expires = expires,
        verified = verified,
        accountEmail = accountEmail,
        accountToken = accountToken,
        accountSubscription = accountSubscription,
        quotaStatus = quotaStatus,
        quotaBreakdown = quotaBreakdown,
        quotaProgress = if (quotaProgressVisible && !quotaProgressIndeterminate) quotaProgress else null,
        betaStatus = betaStatus,
        cloudSync = cloudSync,
        prioritySupport = prioritySupport,
        pluginRewards = pluginRewards,
        earlyAccessDevices = earlyAccessDevices,
        backupFrequencyIndex = backupFrequencyIndex,
        supportChannelIndex = supportChannelIndex,
        modelOptions = models.labels.toList(),
        requestsModel = models.labelAt(requestsModelIndex).orEmpty(),
        questionsModel = models.labelAt(questionsModelIndex).orEmpty(),
        tasksModel = models.labelAt(tasksModelIndex).orEmpty(),
        systemPrompt = systemPrompt,
    )
}

/** Model id → label catalog shared by the three model pickers; positions index into [labels]. */
internal class ProSubscriptionModelLabels(initial: Map<String, String> = DEFAULT_MODELS) {
    private val idToLabel = LinkedHashMap(initial)
    val labels = mutableListOf<String>()

    init {
        rebuild()
    }

    /** Adds saved model ids the catalog does not know yet, keeping them selectable. */
    fun includeSaved(saved: List<String>) {
        saved.forEach { id ->
            if (id.isNotBlank() && idToLabel.keys.none { it.equals(id, ignoreCase = true) }) {
                idToLabel[id] = id
            }
        }
        rebuild()
    }

    fun replaceAll(models: Map<String, String>) {
        idToLabel.clear()
        idToLabel.putAll(models)
        rebuild()
    }

    fun labelAt(position: Int): String? = labels.getOrNull(position)

    /** Position for a saved model id (or label), falling back to the first entry. */
    fun positionOf(value: String): Int {
        val canonical = idToLabel.keys.firstOrNull { it.equals(value, ignoreCase = true) } ?: value
        val label = idToLabel[canonical] ?: value
        val idx = labels.indexOfFirst { it.equals(label, ignoreCase = true) }
        return if (idx >= 0) idx else 0
    }

    /** Model id stored for the entry at [position]; unknown labels are stored as typed. */
    fun modelAt(position: Int): String {
        val selectedLabel = labelAt(position)?.trim().orEmpty().ifBlank { "auto" }
        val byLabel = idToLabel.entries.firstOrNull { it.value.equals(selectedLabel, ignoreCase = true) }
        return byLabel?.key ?: selectedLabel
    }

    private fun rebuild() {
        labels.clear()
        val seen = linkedSetOf<String>()
        val normalized = mutableListOf<Pair<String, String>>()
        idToLabel.forEach { (id, baseLabel) ->
            var label = baseLabel.trim().ifBlank { id }
            if (seen.contains(label)) {
                label = "$label · $id"
            }
            seen += label
            normalized += id to label
            labels += label
        }
        idToLabel.clear()
        normalized.forEach { (id, label) -> idToLabel[id] = label }
    }

    companion object {
        val DEFAULT_MODELS: Map<String, String> = linkedMapOf(
            "openrouter/free" to "Cheap models router (1x)",
            "google/gemini-3.1-flash-live-preview" to "Gemini 3.1 Flash Live (36x)",
            "google/gemma-4-26b-a4b-it" to "Gemma 4 26B Vision (2x)",
            "deepseek/deepseek-v4-flash" to "DeepSeek V4 Flash (2x)",
        )
    }
}

internal object ProSubscriptionSettingsFormat {
    fun resetTime(resetAtMs: Long): String {
        if (resetAtMs <= 0L) return "-"
        return java.text.SimpleDateFormat("MMM d, HH:mm 'UTC'", java.util.Locale.US)
            .format(java.util.Date(resetAtMs))
    }

    fun compactCount(value: Int): String {
        val absValue = kotlin.math.abs(value.toLong())
        return when {
            absValue >= 1_000_000_000L -> String.format(java.util.Locale.US, "%.1fB", value / 1_000_000_000.0)
            absValue >= 1_000_000L -> String.format(java.util.Locale.US, "%.1fM", value / 1_000_000.0)
            absValue >= 1_000L -> String.format(java.util.Locale.US, "%.1fk", value / 1_000.0)
            else -> value.toString()
        }.replace(".0", "")
    }

    fun maskToken(token: String): String {
        if (token.isBlank()) return "-"
        if (token.length <= 12) return token
        return token.take(8) + "..." + token.takeLast(4)
    }
}

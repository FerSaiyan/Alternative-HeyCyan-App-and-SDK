package com.fersaiyan.cyanbridge.shared.billing

import com.fersaiyan.cyanbridge.shared.format.formatFixed
import com.fersaiyan.cyanbridge.shared.format.formatLocalMonthDayTime
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Working values behind the Pro settings screen. Handlers mutate the draft freely; the ViewModel
 * publishes it only at the points where the screen always refreshed. Initial values match what
 * the screen showed before any handler ran.
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

    fun showQuotaInfo(quota: ProQuota) {
        val displayModel = quota.model.removeSuffix("/free").ifBlank { quota.model }
        val resetText = if (quota.resetAtMs > 0L) "Resets ${ProSubscriptionSettingsFormat.resetTime(quota.resetAtMs)}" else ""

        if (quota.limit > 0) {
            val usedPercent = ((quota.used.toDouble() / quota.limit.toDouble()) * 100.0).coerceIn(0.0, 100.0).roundToInt()
            quotaStatus = "Quota ($displayModel): $usedPercent% used"
            quotaBreakdown = buildString {
                append("${ProSubscriptionSettingsFormat.compactCount(quota.remaining)} left")
                append(" · ${ProSubscriptionSettingsFormat.compactCount(quota.used)}/${ProSubscriptionSettingsFormat.compactCount(quota.limit)} used")
                if (resetText.isNotBlank()) append(" · $resetText")
            }
            quotaProgressVisible = true
            quotaProgressIndeterminate = false
            quotaProgress = usedPercent
        } else {
            quotaStatus = "Quota ($displayModel): ${ProSubscriptionSettingsFormat.compactCount(quota.remaining)} left"
            quotaBreakdown = buildString {
                append("${ProSubscriptionSettingsFormat.compactCount(quota.used)} used")
                if (resetText.isNotBlank()) append(" · $resetText")
            }
            quotaProgressVisible = false
            quotaProgressIndeterminate = false
        }
    }

    fun toUiState(
        models: ProSubscriptionModelLabels,
        questionModels: ProSubscriptionModelLabels,
        systemPrompt: String,
    ) = ProSubscriptionSettingsUiState(
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
        questionsModelOptions = questionModels.labels.toList(),
        requestsModel = models.labelAt(requestsModelIndex).orEmpty(),
        questionsModel = questionModels.labelAt(questionsModelIndex).orEmpty(),
        tasksModel = models.labelAt(tasksModelIndex).orEmpty(),
        systemPrompt = systemPrompt,
    )
}

/**
 * Gemini Live is offered in the Questions picker once per Live mode, so picking an entry also picks
 * Economy (relay proxy, Google may use conversations) or Private (paid API, direct token). The
 * choice is still saved as before: the Live model id as the questions model plus the Live-mode flag.
 */
object ProLiveModelChoice {
    const val ECONOMY = "economy"
    const val PRIVATE = "private"
    private const val SEPARATOR = "#"
    private val MULTIPLIER = Regex("\\s*\\(\\s*\\d+\\s*x\\s*\\)", RegexOption.IGNORE_CASE)

    /** Any Gemini Live id: the server's (gemini-3.8-live), older builds' (gemini-3.1-flash-live-preview) or "live". */
    fun isLiveModel(id: String): Boolean {
        val clean = id.trim().lowercase().substringBefore(SEPARATOR)
        return clean == "live" || ("gemini" in clean && "-live" in clean)
    }

    fun entryId(liveModelId: String, mode: String): String = "$liveModelId$SEPARATOR$mode"

    /** The Live model id and whether the entry is Economy; null for an ordinary model id. */
    fun parse(entryId: String): Pair<String, Boolean>? {
        val mode = entryId.substringAfterLast(SEPARATOR, "")
        if (mode != ECONOMY && mode != PRIVATE) return null
        return entryId.substringBeforeLast(SEPARATOR) to (mode == ECONOMY)
    }

    /**
     * The Questions catalog: [models] with the first Live model replaced, in place, by one entry
     * per mode ("Gemini 3.8 Live · Economy (7x)"). Further Live ids are dropped.
     */
    fun expand(models: Map<String, String>, liveModes: List<ProLiveMode>): Map<String, String> {
        val out = linkedMapOf<String, String>()
        var liveAdded = false
        models.forEach { (id, label) ->
            if (!isLiveModel(id)) {
                out[id] = label
                return@forEach
            }
            if (liveAdded) return@forEach
            liveAdded = true
            // "Gemini 3.8 Live (36x) · Vision" → "Gemini 3.8 Live"; each mode carries its own multiplier.
            val base = label.substringBefore(" · ").replace(MULTIPLIER, "").trim().ifBlank { id }
            listOf(ECONOMY to DEFAULT_ECONOMY_MULTIPLIER, PRIVATE to DEFAULT_PRIVATE_MULTIPLIER).forEach { (mode, fallback) ->
                val multiplier = liveModes.firstOrNull { it.id == mode }?.quotaMultiplier?.takeIf { it > 0 } ?: fallback
                out[entryId(id, mode)] = "$base · ${mode.replaceFirstChar { it.uppercase() }} (${multiplier}x)"
            }
        }
        return out
    }

    private const val DEFAULT_ECONOMY_MULTIPLIER = 7
    private const val DEFAULT_PRIVATE_MULTIPLIER = 36
}

/** Model id → label catalog behind a model picker; positions index into [labels]. */
internal class ProSubscriptionModelLabels(initial: Map<String, String> = DEFAULT_MODELS) {
    private val idToLabel = LinkedHashMap(initial)
    val labels = mutableListOf<String>()

    init {
        rebuild()
    }

    /** Adds saved model ids the list does not show, keeping them selectable (labelled from [knownLabels]). */
    fun includeSaved(saved: List<String>, knownLabels: Map<String, String> = emptyMap()) {
        saved.forEach { id ->
            // "auto" is the cheap router, the first entry.
            if (id.isNotBlank() && !id.equals("auto", ignoreCase = true) && idToLabel.keys.none { it.equals(id, ignoreCase = true) }) {
                idToLabel[id] = knownLabels.entries.firstOrNull { it.key.equals(id, ignoreCase = true) }?.value ?: id
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

    val ids: Set<String> get() = idToLabel.keys

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
            if (seen.contains(label)) label = "$label · $id"
            seen += label
            normalized += id to label
            labels += label
        }
        idToLabel.clear()
        normalized.forEach { (id, label) -> idToLabel[id] = label }
    }

    companion object {
        /**
         * Shown until the server catalog loads. Pro offers only models that hear a recording
         * directly (speech-to-text first adds about 2 s): the cheap router answers audio through
         * its Gemini route, and MiMo V2.6 Flash replaced the text/image-only Gemma 4 26B A4B.
         */
        val DEFAULT_MODELS: Map<String, String> = linkedMapOf(
            "openrouter/free" to "Cheap models router (1x)",
            "xiaomi/mimo-v2.6-flash" to "MiMo V2.6 Flash (1x)",
            "google/gemini-3.7-flash" to "Gemini 3.7 Flash (6x)",
        )

        /** Live, offered only in the Questions picker (once per mode). */
        const val DEFAULT_LIVE_MODEL = "google/gemini-3.8-live"
        const val DEFAULT_LIVE_LABEL = "Gemini 3.8 Live (36x)"
    }
}

internal object ProSubscriptionSettingsFormat {
    /** The reset time in the device's time zone; the label has always said UTC. */
    fun resetTime(resetAtMs: Long): String =
        if (resetAtMs <= 0L) "-" else "${formatLocalMonthDayTime(resetAtMs)} UTC"

    fun compactCount(value: Int): String {
        val absValue = abs(value.toLong())
        return when {
            absValue >= 1_000_000_000L -> formatFixed(value / 1_000_000_000.0, 1) + "B"
            absValue >= 1_000_000L -> formatFixed(value / 1_000_000.0, 1) + "M"
            absValue >= 1_000L -> formatFixed(value / 1_000.0, 1) + "k"
            else -> value.toString()
        }.replace(".0", "")
    }

    fun maskToken(token: String): String {
        if (token.isBlank()) return "-"
        if (token.length <= 12) return token
        return token.take(8) + "..." + token.takeLast(4)
    }
}

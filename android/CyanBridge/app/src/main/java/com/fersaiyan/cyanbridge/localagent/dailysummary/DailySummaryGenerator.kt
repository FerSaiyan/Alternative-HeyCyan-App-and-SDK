package com.fersaiyan.cyanbridge.localagent.dailysummary

import android.content.Context
import com.fersaiyan.cyanbridge.shared.settings.AgentProviderType
import com.fersaiyan.cyanbridge.agent.LocalAgentPrefs as AutomationPrefs
import com.fersaiyan.cyanbridge.agent.ProSubscriptionAiPrefs
import com.fersaiyan.cyanbridge.agent.ProSubscriptionPrefs
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.fersaiyan.cyanbridge.ai.router.AiAssistantRouter
import com.fersaiyan.cyanbridge.ai.router.CliRelayClient
import com.fersaiyan.cyanbridge.localmodels.provider.LocalModelRequestPriority
import com.fersaiyan.cyanbridge.localmodels.provider.LocalModelsProvider
import com.fersaiyan.cyanbridge.localagent.memory.LocalAgentMemoryStore
import com.fersaiyan.cyanbridge.localagent.dailyfacts.DailyBulletsSettings
import com.fersaiyan.cyanbridge.localagent.dailyfacts.DailyFactsStorage
import com.fersaiyan.cyanbridge.notes.NightlyEnrichmentDeferredException

object DailySummaryGenerator {
    private val localModelsProvider = LocalModelsProvider()
    private const val MAX_LOCAL_EVENT_BULLETIZER_CALLS = 80
    private const val MAX_LOCAL_EVENT_BULLETS_RENDERED = 220
    private const val MAX_LOCAL_EVENT_BULLETS_CHARS = 52_000
    private const val MAX_INCREMENTAL_APPEND_BULLETS = 20
    private const val DEDUPE_EVENT_WINDOW_MS = 8 * 60 * 1000L

    private data class ProviderResponse(
        val text: String,
        val metrics: DailySummaryRunHistory.RunMetrics,
    )

    private data class ScreenCaptureEvent(
        val tsMs: Long,
        val packageName: String,
        val text: String,
    )

    private data class EventBullet(
        val tsMs: Long,
        val packageName: String,
        val bullet: String,
    )

    data class BulletProgress(
        val done: Int,
        val total: Int,
    )

    private data class Input(
        val date: String,
        val confirmedFacts: String,
        val previousSummary: String?,
        val newScreenSnippets: String,
        val screenEvents: List<ScreenCaptureEvent>,
        val processedCaptureMaxTsMs: Long,
        val isIncremental: Boolean,
        val outputFile: File,
    )

    private fun todayString(nowMs: Long = System.currentTimeMillis()): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(nowMs))
    }

    fun providerHint(context: Context): String {
        return when (AutomationPrefs.getProviderType(context)) {
            AgentProviderType.LOCAL_AGENT -> "local_models"
            AgentProviderType.PRO_SUBSCRIPTION -> "cli_relay"
            AgentProviderType.TASKER -> if (ProSubscriptionPrefs.isActiveLocally(context)) "cli_relay" else "ai_router"
        }
    }

    fun estimateInputTokensForDate(
        context: Context,
        date: String = todayString(),
    ): Int {
        val input = buildInputForDate(context = context, date = date)
        val prompt = buildPrompt(input)
        return DailySummaryRunHistory.estimateTokenCount(prompt)
    }

    fun estimateBulletEventsForDate(
        context: Context,
        date: String = todayString(),
    ): Int {
        if (AutomationPrefs.getProviderType(context) != AgentProviderType.LOCAL_AGENT) return 0
        val input = buildInputForDate(context = context, date = date)
        return input.screenEvents.size
    }

    private fun buildInputForDate(
        context: Context,
        date: String = todayString(),
        maxCaptureLines: Int = 220,
        maxCharsPerCapture: Int = 1_200,
        maxTotalChars: Int = 48_000,
        forceFullRebuild: Boolean = false,
    ): Input {
        LocalAgentMemoryStore.ensureSeedFiles(context)

        val confirmedFile = LocalAgentMemoryStore.confirmedDailyFactsFileForDate(context, date)
        if (!confirmedFile.exists()) {
            LocalAgentMemoryStore.writeText(
                confirmedFile,
                "# Confirmed daily facts ($date)\n\n- \n",
            )
        }
        val confirmedFacts = LocalAgentMemoryStore.readText(confirmedFile).trim()

        val lastProcessedAtMs = DailySummaryPrefs.getLastCaptureProcessedAtMs(context, date)
        val hasExistingSummary = DailySummaryPrefs.getLastGeneratedAtMs(context, date) > 0L
        
        val previousSummary = if (hasExistingSummary && !forceFullRebuild) {
            val summaryFile = LocalAgentMemoryStore.dailySummaryFileForDate(context, date)
            val existing = LocalAgentMemoryStore.readText(summaryFile).trim()
            if (existing.isNotBlank() && !existing.contains("(Generate from Settings")) existing else null
        } else {
            null
        }

        val allCaptureLines = LocalAgentMemoryStore.readScreenCaptureLines(context, date, maxCaptureLines * 3)
        val allEvents = parseScreenCaptureEvents(
            lines = allCaptureLines,
            maxCharsPerCapture = maxCharsPerCapture,
        )

        val newEvents = if (lastProcessedAtMs > 0L && allEvents.isNotEmpty() && previousSummary != null && !forceFullRebuild) {
            allEvents
                .filter { it.tsMs > lastProcessedAtMs }
                .takeLast(maxCaptureLines)
        } else {
            allEvents.takeLast(maxCaptureLines)
        }

        val snippets = if (newEvents.isNotEmpty()) {
            formatTailScreenCaptures(
                events = newEvents,
                maxTotalChars = maxTotalChars,
            )
        } else {
            "(no new screen captures since last summary)"
        }

        val isIncremental = lastProcessedAtMs > 0L && previousSummary != null && !forceFullRebuild

        val out = LocalAgentMemoryStore.dailySummaryFileForDate(context, date)

        return Input(
            date = date,
            confirmedFacts = confirmedFacts,
            previousSummary = previousSummary,
            newScreenSnippets = snippets,
            screenEvents = newEvents,
            processedCaptureMaxTsMs = newEvents.maxOfOrNull { it.tsMs } ?: 0L,
            isIncremental = isIncremental,
            outputFile = out,
        )
    }

    private fun parseScreenCaptureEvents(
        lines: List<String>,
        maxCharsPerCapture: Int,
    ): List<ScreenCaptureEvent> {
        if (lines.isEmpty()) return emptyList()

        val lastSeenTsByKey = HashMap<String, Long>()
        val out = ArrayList<ScreenCaptureEvent>(lines.size)

        for (line in lines) {
            val obj = runCatching { JSONObject(line) }.getOrNull() ?: continue
            val ts = obj.optLong("ts_ms", 0L)
            val pkg = obj.optString("package", "?").ifBlank { "?" }
            val rawText = obj.optString("text", "").trim()
            if (rawText.isBlank()) continue

            val text = rawText
                .replace(Regex("\\s+"), " ")
                .replace(Regex("[\\u0000-\\u001F]"), " ")
                .trim()
                .take(maxCharsPerCapture)
            if (!looksLikeUsefulCapture(text)) continue

            val dedupeKey = "${pkg.lowercase(Locale.US)}|" + text
                .lowercase(Locale.US)
                .replace(Regex("\\s+"), " ")
                .take(500)
            val previousTs = lastSeenTsByKey[dedupeKey]
            if (previousTs != null && ts > 0L && previousTs > 0L && (ts - previousTs) in 0 until DEDUPE_EVENT_WINDOW_MS) {
                continue
            }
            lastSeenTsByKey[dedupeKey] = ts

            out += ScreenCaptureEvent(
                tsMs = ts,
                packageName = pkg,
                text = text,
            )
        }

        return out
    }

    private fun formatTailScreenCaptures(
        events: List<ScreenCaptureEvent>,
        maxTotalChars: Int,
    ): String {
        if (events.isEmpty()) return "(screen captures file is empty)"

        val timeFmt = SimpleDateFormat("HH:mm", Locale.US)

        val out = StringBuilder()
        var remaining = maxTotalChars

        for (event in events) {
            if (remaining <= 0) break
            val time = if (event.tsMs > 0L) timeFmt.format(Date(event.tsMs)) else "??:??"
            val row = "- [$time] ${event.packageName}: ${event.text}\n"
            if (row.length > remaining) {
                val clipped = row.take(remaining.coerceAtLeast(0))
                out.append(clipped)
                break
            }
            out.append(row)
            remaining -= row.length
        }

        val rendered = out.toString().trimEnd()
        return if (rendered.isBlank()) {
            "(screen captures existed but were mostly noisy/duplicated UI text)"
        } else {
            rendered
        }
    }

    private suspend fun prepareInputForGeneration(
        context: Context,
        input: Input,
        onBulletProgress: ((BulletProgress) -> Unit)? = null,
        shouldContinue: () -> Boolean = { true },
    ): Input {
        val isLocalProvider = AutomationPrefs.getProviderType(context) == AgentProviderType.LOCAL_AGENT
        if (!isLocalProvider || input.screenEvents.isEmpty()) return input

        val mergedEventBullets = buildMergedEventBulletsForLocalModel(
            context = context,
            date = input.date,
            events = input.screenEvents,
            onBulletProgress = onBulletProgress,
            shouldContinue = shouldContinue,
        )

        if (mergedEventBullets.isBlank()) return input
        return input.copy(newScreenSnippets = mergedEventBullets)
    }

    private suspend fun buildMergedEventBulletsForLocalModel(
        context: Context,
        date: String,
        events: List<ScreenCaptureEvent>,
        onBulletProgress: ((BulletProgress) -> Unit)? = null,
        shouldContinue: () -> Boolean = { true },
    ): String {
        if (events.isEmpty()) return ""

        val started = System.currentTimeMillis()
        val bullets = ArrayList<EventBullet>(events.size)
        var modelCalls = 0
        var processed = 0
        val checkpoints = DailyBulletCheckpointStore.load(context, date)
        onBulletProgress?.invoke(BulletProgress(done = 0, total = events.size))

        for (event in events) {
            val eventKey = DailyBulletCheckpointStore.eventKey(event.tsMs, event.packageName, event.text)
            val cachedBullet = checkpoints[eventKey]?.bullet
            val mappedBullet = if (cachedBullet != null) {
                cachedBullet
            } else if (modelCalls < MAX_LOCAL_EVENT_BULLETIZER_CALLS) {
                if (!shouldContinue()) throw NightlyEnrichmentDeferredException()
                modelCalls += 1
                runCatching {
                    val raw = localModelsProvider.streamChat(
                        context = context,
                        messages = listOf(
                            mapOf(
                                "role" to "User",
                                "content" to buildSingleEventBulletPrompt(context, event),
                            ),
                        ),
                        requestPriority = LocalModelRequestPriority.LOW,
                        maxTokens = DailyBulletsSettings.getMaxTokensPerBullet(context)
                            .takeIf { it > 0 }
                            ?: 96,
                    )
                    parseSingleEventBullet(raw)
                }.getOrNull()
            } else {
                null
            }

            val fallbackBullet = heuristicEventBullet(event)
            val resolvedBullet = mappedBullet ?: fallbackBullet
            processed += 1
            onBulletProgress?.invoke(BulletProgress(done = processed, total = events.size))

            if (resolvedBullet.isBlank()) continue
            if (cachedBullet == null) {
                DailyBulletCheckpointStore.put(
                    context = context,
                    date = date,
                    record = DailyBulletCheckpointStore.Record(
                        key = eventKey,
                        tsMs = event.tsMs,
                        packageName = event.packageName,
                        bullet = resolvedBullet,
                    ),
                )
            }
            bullets += EventBullet(
                tsMs = event.tsMs,
                packageName = event.packageName,
                bullet = resolvedBullet,
            )
        }

        val elapsedMs = (System.currentTimeMillis() - started).coerceAtLeast(1L)
        DailySummaryBulletRunHistory.record(context, totalBullets = events.size, totalMs = elapsedMs)

        return mergeEventBullets(bullets)
    }

    private fun buildSingleEventBulletPrompt(context: Context, event: ScreenCaptureEvent): String {
        val time = if (event.tsMs > 0L) {
            SimpleDateFormat("HH:mm", Locale.US).format(Date(event.tsMs))
        } else {
            "unknown"
        }

        return DailyBulletsSettings.getBulletPrompt(context)
            .replace("\${event.packageName}", event.packageName)
            .replace("\${event.time}", time)
            .replace("\${event.text}", event.text)
    }

    private fun parseSingleEventBullet(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return null

        val jsonCandidate = extractFirstJsonObject(trimmed)
        val json = jsonCandidate?.let { runCatching { JSONObject(it) }.getOrNull() }
        if (json != null) {
            val skip = json.optBoolean("skip", false)
            if (skip) return null
            return sanitizeEventBullet(json.optString("bullet", ""))
        }

        val firstLine = trimmed.lineSequence().firstOrNull().orEmpty()
        return sanitizeEventBullet(firstLine)
    }

    private fun extractFirstJsonObject(raw: String): String? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return raw.substring(start, end + 1)
    }

    private fun sanitizeEventBullet(raw: String): String? {
        var text = raw.trim()
        if (text.isBlank()) return null

        text = text
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
            .removePrefix("-")
            .removePrefix("*")
            .trim()
            .removePrefix("\"")
            .removeSuffix("\"")
            .trim()

        if (text.isBlank()) return null
        if (text.length > 180) {
            text = text.take(177).trimEnd() + "..."
        }
        return text
    }

    private fun heuristicEventBullet(event: ScreenCaptureEvent): String {
        val compact = event.text
            .replace(Regex("\\s+"), " ")
            .trim()
        if (compact.isBlank()) return ""
        val snippet = if (compact.length > 120) compact.take(117).trimEnd() + "..." else compact
        return "Viewed ${event.packageName}: $snippet"
    }

    private fun mergeEventBullets(bullets: List<EventBullet>): String {
        if (bullets.isEmpty()) return ""

        val sorted = bullets.sortedBy { it.tsMs }
        val timeFmt = SimpleDateFormat("HH:mm", Locale.US)
        val seen = HashSet<String>()
        val out = StringBuilder()
        var remaining = MAX_LOCAL_EVENT_BULLETS_CHARS
        var rendered = 0

        for (item in sorted) {
            if (remaining <= 0 || rendered >= MAX_LOCAL_EVENT_BULLETS_RENDERED) break
            val normalizedBullet = item.bullet
                .lowercase(Locale.US)
                .replace(Regex("[^a-z0-9\\s]"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
            if (normalizedBullet.length < 8) continue

            val dedupeKey = "${item.packageName.lowercase(Locale.US)}|${normalizedBullet.take(260)}"
            if (!seen.add(dedupeKey)) continue

            val time = if (item.tsMs > 0L) timeFmt.format(Date(item.tsMs)) else "??:??"
            val row = "- [$time] ${item.packageName}: ${item.bullet}\n"
            if (row.length > remaining) {
                out.append(row.take(remaining.coerceAtLeast(0)))
                break
            }

            out.append(row)
            remaining -= row.length
            rendered += 1
        }

        return out.toString().trimEnd()
    }

    private fun mergeIncrementalSummaryWithoutModel(
        date: String,
        previousSummary: String,
        newScreenSnippets: String,
    ): String {
        val baseSummary = previousSummary.trim()
        if (baseSummary.isBlank()) return baseSummary

        val parsedNewBullets = extractSummaryBulletsFromSnippets(newScreenSnippets)
            .take(MAX_INCREMENTAL_APPEND_BULLETS)
        if (parsedNewBullets.isEmpty()) return baseSummary

        val existingDedupe = extractExistingSummaryBulletKeys(baseSummary).toMutableSet()
        val uniqueNewBullets = parsedNewBullets.filter { b ->
            existingDedupe.add(normalizeSummaryBulletForDedupe(b))
        }
        if (uniqueNewBullets.isEmpty()) return baseSummary

        val lines = baseSummary.lines().toMutableList()
        val highlightsIndex = lines.indexOfFirst { it.trim().equals("## Highlights", ignoreCase = true) }

        if (highlightsIndex < 0) {
            val sb = StringBuilder(baseSummary)
            if (!baseSummary.endsWith('\n')) sb.append('\n')
            sb.append("\n## Highlights\n")
            uniqueNewBullets.forEach { sb.append("- ").append(it).append('\n') }
            return ensureDailySummaryHeader(sb.toString().trimEnd(), date)
        }

        var insertAt = highlightsIndex + 1
        while (insertAt < lines.size) {
            val t = lines[insertAt].trim()
            if (t.startsWith("## ")) break
            insertAt += 1
        }

        val insertion = uniqueNewBullets.map { "- $it" }
        lines.addAll(insertAt, insertion)

        val merged = lines.joinToString("\n").trimEnd()
        return ensureDailySummaryHeader(merged, date)
    }

    private fun ensureDailySummaryHeader(summary: String, date: String): String {
        val trimmed = summary.trim()
        if (trimmed.startsWith("# Daily Summary", ignoreCase = true)) return trimmed
        return "# Daily Summary ($date)\n\n$trimmed".trim()
    }

    private fun extractSummaryBulletsFromSnippets(snippets: String): List<String> {
        if (snippets.isBlank()) return emptyList()
        val out = ArrayList<String>()
        val linePattern = Regex("^- \\[(.*?)\\]\\s+([^:]+):\\s*(.+)$")

        for (line in snippets.lineSequence()) {
            val trimmed = line.trim()
            if (!trimmed.startsWith("- ")) continue

            val normalized = linePattern.matchEntire(trimmed)?.let { m ->
                val time = m.groupValues[1].trim()
                val pkg = m.groupValues[2].trim()
                val detail = m.groupValues[3].trim()
                "[$time] $pkg: $detail"
            } ?: trimmed.removePrefix("-").trim()

            val cleaned = normalized
                .replace(Regex("\\s+"), " ")
                .trim()
                .take(260)
            if (cleaned.length < 10) continue
            out += cleaned
        }

        return out
    }

    private fun extractExistingSummaryBulletKeys(summary: String): Set<String> {
        if (summary.isBlank()) return emptySet()
        return summary.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("- ") }
            .map { it.removePrefix("-").trim() }
            .map { normalizeSummaryBulletForDedupe(it) }
            .filter { it.isNotBlank() }
            .toSet()
    }

    private fun normalizeSummaryBulletForDedupe(bullet: String): String {
        return bullet
            .lowercase(Locale.US)
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(260)
    }

    private fun looksLikeUsefulCapture(text: String): Boolean {
        val clean = text.trim()
        if (clean.length < 12) return false

        val alphaNum = clean.count { it.isLetterOrDigit() }
        if (alphaNum < 8) return false

        val symbolRatio = 1.0 - (alphaNum.toDouble() / clean.length.toDouble())
        if (symbolRatio > 0.60) return false

        return true
    }

    private fun buildPrompt(input: Input): String {
        return if (input.isIncremental && input.previousSummary != null) {
            buildIncrementalPrompt(input)
        } else {
            buildFullPrompt(input)
        }
    }

    private fun buildFullPrompt(input: Input): String {
        return """
You are my personal AI assistant. Create a daily summary from the information below.

FACTS I CONFIRMED TODAY:
${input.confirmedFacts.ifBlank { "(none)" }}

SCREEN ACTIVITY (OCR from my phone - may have UI noise but meaningful content is real):
${input.newScreenSnippets.ifBlank { "(no screen captures)" }}

OUTPUT FORMAT:
# Daily Summary (${input.date})

[Short first-person narrative - what you did today]

## Highlights
- [bullet 1]
- [bullet 2]
- [bullet 3]
- [bullet 4]
- [bullet 5]

## Open questions
[Only include if real uncertainties exist]

IMPORTANT:
- Do not invent facts
- Do not refuse - always produce a summary even if input is messy
- Do not apologize or say you can't help
- If uncertain, note it in "Open questions"
""".trim()
    }

    private fun buildIncrementalPrompt(input: Input): String {
        return """
You are my personal AI assistant. Your task is to UPDATE a daily summary.

CURRENT SUMMARY (keep this, just add to it):
${input.previousSummary}

NEW SCREEN ACTIVITY (add relevant items to the summary above):
${input.newScreenSnippets.ifBlank { "(no new captures since last summary)" }}

OUTPUT INSTRUCTIONS:
- Output Markdown
- Keep the "# Daily Summary (YYYY-MM-DD)" header  
- Keep existing narrative and bullets
- ADD new events from the new captures above
- If new captures contain nothing useful, just return the original summary unchanged
- NEVER refuse, NEVER say you can't, NEVER ask for more details
- If uncertain, note it briefly in "## Open questions"

Remember: You MUST output a valid summary. Do not refuse.
""".trim()
    }

    suspend fun generateAndStore(
        context: Context,
        date: String = todayString(),
        onBulletProgress: ((BulletProgress) -> Unit)? = null,
        shouldContinue: () -> Boolean = { true },
    ): Result<File> {
        val forceFullRebuild = false

        return runCatching {
            val input = buildInputForDate(context, date, forceFullRebuild = forceFullRebuild)
            val preparedInput = prepareInputForGeneration(
                context,
                input,
                onBulletProgress = onBulletProgress,
                shouldContinue = shouldContinue,
            )
            val agentType = AutomationPrefs.getProviderType(context)

            if (agentType == AgentProviderType.LOCAL_AGENT && preparedInput.screenEvents.isNotEmpty()) {
                DailyFactsStorage.appendDraft(
                    context,
                    date,
                    extractSummaryBulletsFromSnippets(preparedInput.newScreenSnippets),
                )
            }

            if (agentType == AgentProviderType.LOCAL_AGENT && preparedInput.isIncremental && preparedInput.previousSummary != null) {
                val mergedSummary = mergeIncrementalSummaryWithoutModel(
                    date = date,
                    previousSummary = preparedInput.previousSummary,
                    newScreenSnippets = preparedInput.newScreenSnippets,
                )
                require(mergedSummary.isNotBlank()) { "Empty summary returned" }

                LocalAgentMemoryStore.writeText(preparedInput.outputFile, mergedSummary + "\n")
                val generatedAtMs = System.currentTimeMillis()
                DailySummaryPrefs.setLastGeneratedAtMs(context, date, generatedAtMs)
                val processedAtMs = preparedInput.processedCaptureMaxTsMs
                    .takeIf { it > 0L }
                    ?: generatedAtMs
                DailySummaryPrefs.setLastCaptureProcessedAtMs(context, date, processedAtMs)

                val inputTokens = DailySummaryRunHistory.estimateTokenCount(preparedInput.newScreenSnippets)
                val outputTokens = DailySummaryRunHistory.estimateTokenCount(mergedSummary)
                DailySummaryRunHistory.record(
                    context,
                    DailySummaryRunHistory.RunMetrics(
                        provider = "local_models_merge",
                        inputTokens = inputTokens,
                        outputTokens = outputTokens,
                        promptTokensPerSec = inputTokens.coerceAtLeast(1).toDouble(),
                        generationTokensPerSec = outputTokens.coerceAtLeast(1).toDouble(),
                        totalMs = 1L,
                    ),
                )

                return@runCatching preparedInput.outputFile
            }

            val (usedInput, providerResult) = try {
                preparedInput to generateSummary(context, buildPrompt(preparedInput))
            } catch (_: Throwable) {
                val fallbackInput = buildInputForDate(
                    context = context,
                    date = date,
                    maxCaptureLines = 50,
                    maxCharsPerCapture = 400,
                    maxTotalChars = 8_000,
                    forceFullRebuild = true,
                )
                val preparedFallbackInput = prepareInputForGeneration(
                    context,
                    fallbackInput,
                    onBulletProgress = onBulletProgress,
                    shouldContinue = shouldContinue,
                )
                preparedFallbackInput to generateSummary(context, buildFullPrompt(preparedFallbackInput))
            }

            val summary = providerResult.text.trim()

            require(summary.isNotBlank()) { "Empty summary returned" }

            if (agentType != AgentProviderType.LOCAL_AGENT && usedInput.screenEvents.isNotEmpty()) {
                val summaryFacts = summary.lineSequence()
                    .map { it.trim() }
                    .filter { it.startsWith("- ") || it.startsWith("* ") }
                    .map { it.removePrefix("- ").removePrefix("* ").trim() }
                    .filter { it.isNotBlank() }
                    .take(MAX_INCREMENTAL_APPEND_BULLETS)
                    .toList()
                DailyFactsStorage.appendDraft(context, date, summaryFacts)
            }

            LocalAgentMemoryStore.writeText(usedInput.outputFile, summary + "\n")
            val generatedAtMs = System.currentTimeMillis()
            DailySummaryPrefs.setLastGeneratedAtMs(context, date, generatedAtMs)
            val processedAtMs = usedInput.processedCaptureMaxTsMs
                .takeIf { it > 0L }
                ?: generatedAtMs
            DailySummaryPrefs.setLastCaptureProcessedAtMs(context, date, processedAtMs)
            DailySummaryRunHistory.record(context, providerResult.metrics)

            usedInput.outputFile
        }
    }

    private suspend fun generateSummary(context: Context, prompt: String): ProviderResponse {
        val agentType = AutomationPrefs.getProviderType(context)
        val hasPro = ProSubscriptionPrefs.isActiveLocally(context)

        return when (agentType) {
            AgentProviderType.LOCAL_AGENT -> {
                runCatching { runLocalModels(context, prompt) }
                    .recoverCatching { localErr ->
                        if (!hasPro) {
                            throw IllegalStateException("Local model unavailable (${localErr.message}).")
                        }
                        runRelay(context, prompt)
                    }
                    .getOrThrow()
            }

            AgentProviderType.PRO_SUBSCRIPTION -> runRelay(context, prompt)

            AgentProviderType.TASKER -> {
                if (hasPro) {
                    runRelay(context, prompt)
                } else {
                    runRouterFallback(context, prompt)
                }
            }
        }
    }

    private suspend fun runRelay(context: Context, prompt: String): ProviderResponse {
        val modelOverride = ProSubscriptionAiPrefs.getTasksModel(context)
            .trim()
            .takeIf { it.isNotBlank() }

        val details = CliRelayClient.voiceQueryDetailed(
            context = context,
            prompt = prompt,
            modelOverride = modelOverride,
        ).getOrElse { err ->
            throw IllegalStateException("Pro relay unavailable (${err.message}).")
        }

        val reply = details.reply.trim()
        if (!isUsableSummaryReply(reply)) {
            throw IllegalStateException("Unable to generate daily summary from active provider.")
        }

        return ProviderResponse(
            text = reply,
            metrics = DailySummaryRunHistory.RunMetrics(
                provider = "cli_relay",
                inputTokens = details.telemetry.inputTokens,
                outputTokens = details.telemetry.outputTokens,
                promptTokensPerSec = details.telemetry.promptTokensPerSec,
                generationTokensPerSec = details.telemetry.generationTokensPerSec,
                totalMs = details.telemetry.totalMs,
            ),
        )
    }

    private suspend fun runLocalModels(context: Context, prompt: String): ProviderResponse {
        val inputTokens = DailySummaryRunHistory.estimateTokenCount(prompt)
        var tokenCount = 0
        var firstTokenAtMs = 0L
        val started = System.currentTimeMillis()

        val reply = localModelsProvider.streamChat(
            context = context,
            messages = listOf(mapOf("role" to "User", "content" to prompt)),
            onToken = {
                if (firstTokenAtMs <= 0L) {
                    firstTokenAtMs = System.currentTimeMillis()
                }
                tokenCount += 1
            },
            requestPriority = LocalModelRequestPriority.LOW,
        ).trim()

        if (!isUsableSummaryReply(reply)) {
            throw IllegalStateException("Local model returned an unusable response.")
        }

        val ended = System.currentTimeMillis()
        val totalMs = (ended - started).coerceAtLeast(1L)
        val outputTokens = tokenCount.coerceAtLeast(DailySummaryRunHistory.estimateTokenCount(reply))
        val promptMs = if (firstTokenAtMs > started) {
            (firstTokenAtMs - started).coerceAtLeast(1L)
        } else {
            (totalMs * 0.35).toLong().coerceAtLeast(1L)
        }
        val generationMs = (totalMs - promptMs).coerceAtLeast(1L)

        return ProviderResponse(
            text = reply,
            metrics = DailySummaryRunHistory.RunMetrics(
                provider = "local_models",
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                promptTokensPerSec = inputTokens / (promptMs / 1000.0),
                generationTokensPerSec = outputTokens / (generationMs / 1000.0),
                totalMs = totalMs,
            ),
        )
    }

    private suspend fun runRouterFallback(context: Context, prompt: String): ProviderResponse {
        val inputTokens = DailySummaryRunHistory.estimateTokenCount(prompt)
        val started = System.currentTimeMillis()
        val reply = AiAssistantRouter.textReply(context, prompt).trim()
        val totalMs = (System.currentTimeMillis() - started).coerceAtLeast(1L)

        if (!isUsableSummaryReply(reply)) {
            throw IllegalStateException(
                "AI provider returned a placeholder reply. Choose Pro Subscription or Local Models in Settings.",
            )
        }

        val outputTokens = DailySummaryRunHistory.estimateTokenCount(reply)
        val promptMs = (totalMs * 0.35).toLong().coerceAtLeast(1L)
        val generationMs = (totalMs - promptMs).coerceAtLeast(1L)

        return ProviderResponse(
            text = reply,
            metrics = DailySummaryRunHistory.RunMetrics(
                provider = "ai_router",
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                promptTokensPerSec = inputTokens / (promptMs / 1000.0),
                generationTokensPerSec = outputTokens / (generationMs / 1000.0),
                totalMs = totalMs,
            ),
        )
    }

    private fun isUsableSummaryReply(text: String): Boolean {
        val s = text.trim()
        val lower = s.lowercase(Locale.US)
        if (s.isBlank()) return false
        if (s.startsWith("Demo mode reply:", ignoreCase = true)) return false
        if (s.startsWith("Relay unavailable (", ignoreCase = true)) return false
        if (s.startsWith("Company backend is not configured", ignoreCase = true)) return false
        if (s.startsWith("I couldn't generate a reply yet.", ignoreCase = true)) return false
        if (s.startsWith("No local model is installed.", ignoreCase = true)) return false
        if (lower.startsWith("i apologize")) return false
        if (lower.startsWith("i'm sorry")) return false
        if (lower.startsWith("sorry")) return false
        if (lower.contains("i don't have any specific context")) return false
        if (lower.contains("i don't have enough information")) return false
        if (lower.contains("random lines of text")) return false
        if (lower.contains("cannot generate a summary")) return false
        if (lower.contains("please provide more details")) return false
        if (lower.contains("can't provide")) return false
        if (lower.contains("unable to provide")) return false
        if (lower.contains("don't have enough context")) return false
        if (lower.contains("not enough information")) return false
        if (lower.contains("provide more details")) return false
        if (lower.contains("what specific content")) return false
        if (lower.contains("what you're looking for")) return false
        if (lower.contains("clarify what you need")) return false
        if (lower.contains("mix of different apps")) return false
        return true
    }

    private fun shouldRetryWithCompactPrompt(error: Throwable): Boolean {
        val msg = error.message?.lowercase().orEmpty()
        return msg.contains("timed out") ||
            msg.contains("timeout") ||
            msg.contains("relay unavailable") ||
            msg.contains("http 5") ||
            msg.contains("unusable response") ||
            msg.contains("couldn't generate")
    }
}

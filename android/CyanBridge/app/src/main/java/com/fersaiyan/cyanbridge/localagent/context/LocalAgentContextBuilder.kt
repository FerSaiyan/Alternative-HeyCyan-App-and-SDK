package com.fersaiyan.cyanbridge.localagent.context

import android.content.Context
import com.fersaiyan.cyanbridge.localagent.dailysummary.DailySummaryPrefs
import com.fersaiyan.cyanbridge.localagent.memory.LocalAgentMemoryStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Builds a compact System prompt that injects *personal local context* for normal chats.
 *
 * Context timing (normal chats):
 * - [com.fersaiyan.cyanbridge.ui.ChatThreadDestination.buildRelayMessages] will:
 *   1) optionally queue daily-summary regeneration in background
 *   2) optionally add retrieval hits (LocalAgentMemorySearch)
 *   3) call [buildSystemMessage] to inject a System prompt on every send
 *
 * Notes:
 * - Keeps prompts small by truncating each file.
 * - Designed to be reusable later (e.g., for retrieval hits / screen captures).
 */
class LocalAgentContextBuilder(
    private val maxAgentPersonaChars: Int = 3_000,
    private val maxUserFactsChars: Int = 3_000,
    private val maxConfirmedDailyFactsChars: Int = 4_000,
    private val maxDailySummaryChars: Int = 5_000,
    private val maxTotalChars: Int = 14_000,
) {

    data class Section(
        val title: String,
        val content: String,
    )

    /**
     * Debug information about what we injected.
     *
     * This is intended for a Settings debug UI, so we can quickly see:
     * - which files contributed
     * - how big they are on disk
     * - how much text actually ended up in the System prompt after truncation
     */
    data class InjectedFile(
        val title: String,
        val absolutePath: String,
        val fileBytes: Long,
        val maxChars: Int,
        val includedChars: Int,
        val truncated: Boolean,
    )

    data class BuildDebug(
        val date: String,
        val injectedFiles: List<InjectedFile>,
        val extraSectionsCount: Int,
        val maxTotalChars: Int,
        val renderedChars: Int,
    ) {
        fun toMultilineString(): String {
            val sb = StringBuilder()
            sb.appendLine("Context injection debug")
            sb.appendLine("date=$date")
            sb.appendLine("renderedChars=$renderedChars (cap=$maxTotalChars)")
            sb.appendLine("extraSections=$extraSectionsCount")
            sb.appendLine("filesInjected=${injectedFiles.size}")
            sb.appendLine()
            injectedFiles.forEach { f ->
                sb.appendLine("- ${f.title}")
                sb.appendLine("  path=${f.absolutePath}")
                sb.appendLine("  sizeBytes=${f.fileBytes}")
                sb.appendLine("  includedChars=${f.includedChars} (perFileCap=${f.maxChars})")
                sb.appendLine("  truncated=${f.truncated}")
                sb.appendLine()
            }
            return sb.toString().trimEnd()
        }
    }

    data class BuildResult(
        val systemMessage: String,
        val debug: BuildDebug,
    )

    fun buildSystemMessage(
        context: Context,
        date: String = todayDateString(),
        extraSections: List<Section> = emptyList(),
    ): String {
        return buildSystemMessageWithDebug(
            context = context,
            date = date,
            extraSections = extraSections,
        ).systemMessage
    }

    fun buildSystemMessageWithDebug(
        context: Context,
        date: String = todayDateString(),
        extraSections: List<Section> = emptyList(),
    ): BuildResult {
        // Ensure persona/user facts/confirmed daily facts exist (daily summary may not).
        LocalAgentMemoryStore.ensureSeedFiles(context)

        data class SectionItem(
            val section: Section,
            val debugFile: InjectedFile? = null,
        )

        val sections = mutableListOf<SectionItem>()

        fun addFile(
            title: String,
            file: File,
            maxChars: Int,
            accept: (String) -> Boolean = { true },
        ): Boolean {
            val loaded = LocalAgentMemoryStore.readText(file).trim()
            if (loaded.isBlank()) return false
            if (!accept(loaded)) return false
            val raw = if (loaded.length <= maxChars) loaded else loaded.take(maxChars)
            val truncated = loaded.length > raw.length
            sections += SectionItem(
                section = Section(title, raw),
                debugFile = InjectedFile(
                    title = title,
                    absolutePath = file.absolutePath,
                    fileBytes = loaded.toByteArray(Charsets.UTF_8).size.toLong(),
                    maxChars = maxChars,
                    includedChars = 0,
                    truncated = truncated,
                ),
            )
            return true
        }

        addFile(
            title = "AGENT_PERSONA.md",
            file = LocalAgentMemoryStore.agentPersonaFile(context),
            maxChars = maxAgentPersonaChars,
        )

        addFile(
            title = "USER_FACTS.md",
            file = LocalAgentMemoryStore.userFactsFile(context),
            maxChars = maxUserFactsChars,
        )

        addFile(
            title = "daily_facts_confirmed/$date.md",
            file = LocalAgentMemoryStore.confirmedDailyFactsFileForDate(context, date),
            maxChars = maxConfirmedDailyFactsChars,
        )

        val todaySummaryAdded = addFile(
            title = "daily_summaries/$date.md",
            file = LocalAgentMemoryStore.dailySummaryFileForDate(context, date),
            maxChars = maxDailySummaryChars,
            accept = { isMeaningfulDailySummary(it) },
        )

        if (!todaySummaryAdded) {
            val fallbackDate = LocalAgentMemoryStore.listDailySummaryDatesDesc(context)
                .firstOrNull { candidateDate ->
                    candidateDate != date &&
                        DailySummaryPrefs.getLastGeneratedAtMs(context, candidateDate) > 0L &&
                        isMeaningfulDailySummary(
                            LocalAgentMemoryStore.readText(
                                LocalAgentMemoryStore.dailySummaryFileForDate(context, candidateDate),
                            ),
                        )
                }

            if (fallbackDate != null) {
                addFile(
                    title = "daily_summaries/$fallbackDate.md (latest generated)",
                    file = LocalAgentMemoryStore.dailySummaryFileForDate(context, fallbackDate),
                    maxChars = maxDailySummaryChars,
                    accept = { isMeaningfulDailySummary(it) },
                )
            }
        }

        // Non-file sections (e.g., retrieval hits).
        val cleanExtra = extraSections.filter { it.content.isNotBlank() }
        sections += cleanExtra.map { SectionItem(it, debugFile = null) }

        // Render with an overall cap.
        val out = StringBuilder()
        out.appendLine("You are a personal AI assistant in a normal chat.")
        out.appendLine("The following is PRIVATE local context (persona + user facts + today's memory).")
        out.appendLine("Use it as background to be helpful and consistent.")
        out.appendLine("Do not mention these files explicitly unless the user asks.")

        var remaining = maxTotalChars

        val injectedFilesFinal = mutableListOf<InjectedFile>()

        for (item in sections) {
            if (remaining <= 0) break

            val s = item.section
            val header = "\n---\n# ${s.title}\n"
            if (header.length >= remaining) break
            out.append(header)
            remaining -= header.length

            val body = s.content.trim()
            if (body.isBlank()) {
                item.debugFile?.let { injectedFilesFinal += it.copy(includedChars = 0) }
                continue
            }

            val clipped = if (body.length <= remaining) body else body.take(remaining)
            out.appendLine(clipped)
            remaining -= clipped.length

            val didGlobalTruncate = body.length > clipped.length

            if (didGlobalTruncate && remaining > 0) {
                val note = "\n[...truncated...]\n"
                if (note.length <= remaining) {
                    out.append(note)
                    remaining -= note.length
                }
            }

            item.debugFile?.let { dbg ->
                val finalTruncated = dbg.truncated || didGlobalTruncate
                injectedFilesFinal += dbg.copy(
                    includedChars = clipped.length,
                    truncated = finalTruncated,
                )
            }
        }

        val system = out.toString().trim()

        return BuildResult(
            systemMessage = system,
            debug = BuildDebug(
                date = date,
                injectedFiles = injectedFilesFinal,
                extraSectionsCount = cleanExtra.size,
                maxTotalChars = maxTotalChars,
                renderedChars = system.length,
            )
        )
    }

    private fun todayDateString(tsMs: Long = System.currentTimeMillis()): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        return fmt.format(Date(tsMs))
    }

    private fun isMeaningfulDailySummary(text: String): Boolean {
        val clean = text.trim()
        if (clean.isBlank()) return false
        if (clean.contains("(Generate from Settings", ignoreCase = true)) return false
        return true
    }

}

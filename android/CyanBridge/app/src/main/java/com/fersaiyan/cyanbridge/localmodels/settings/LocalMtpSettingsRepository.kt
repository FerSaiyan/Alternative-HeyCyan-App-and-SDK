package com.fersaiyan.cyanbridge.localmodels.settings

import android.content.Context
import com.fersaiyan.cyanbridge.shared.prefs.AgentPrefs.LocalModelMtp as Mtp
import com.fersaiyan.cyanbridge.shared.prefs.edit
import com.fersaiyan.cyanbridge.shared.prefs.get
import com.fersaiyan.cyanbridge.shared.prefs.set
import com.fersaiyan.cyanbridge.shared.prefs.sharedPreferences
import com.fersaiyan.cyanbridge.shared.prefs.store

enum class LocalMtpMode(val label: String) {
    AUTO("Automatic"),
    ON("On"),
    OFF("Off"),
}

data class LocalMtpBenchmarkRecord(
    val mtpOffOutputTokensPerSecond: Double,
    val mtpOnOutputTokensPerSecond: Double?,
    val mtpOffTimeToFirstTokenMs: Long?,
    val mtpOnTimeToFirstTokenMs: Long?,
    val recommendMtp: Boolean,
    val mtpOnFailure: String? = null,
)

/** Pure policy kept separate so it can be covered by ordinary JVM tests. */
object LocalMtpResolver {
    fun resolve(
        mode: LocalMtpMode,
        supported: Boolean,
        cachedRecommendation: Boolean?,
    ): Boolean {
        if (!supported) return false
        return when (mode) {
            LocalMtpMode.OFF -> false
            LocalMtpMode.ON -> true
            LocalMtpMode.AUTO -> cachedRecommendation ?: true
        }
    }
}

object LocalMtpSettingsRepository {
    fun getMode(context: Context, modelId: String): LocalMtpMode {
        val raw = Mtp.mode[sanitize(modelId)][context]
        return runCatching { LocalMtpMode.valueOf(raw.orEmpty()) }
            .getOrDefault(LocalMtpMode.AUTO)
    }

    fun setMode(context: Context, modelId: String, mode: LocalMtpMode) {
        Mtp.mode[sanitize(modelId)][context] = mode.name
    }

    fun getBenchmark(
        context: Context,
        modelId: String,
        backend: LocalComputeBackend,
        modelSignature: String,
    ): LocalMtpBenchmarkRecord? {
        val prefix = benchmarkPrefix(modelId, backend)
        val p = Mtp.store(context)
        if (p[Mtp.benchmarkSignature[prefix]] != modelSignature) return null
        if (!Mtp.benchmarkRecommend[prefix].isSet(p)) return null

        return LocalMtpBenchmarkRecord(
            mtpOffOutputTokensPerSecond = Double.fromBits(p[Mtp.benchmarkOffTps[prefix]]),
            mtpOnOutputTokensPerSecond = p[Mtp.benchmarkOnTps[prefix]]?.let(Double::fromBits),
            mtpOffTimeToFirstTokenMs = p[Mtp.benchmarkOffTtft[prefix]].takeIf { it >= 0L },
            mtpOnTimeToFirstTokenMs = p[Mtp.benchmarkOnTtft[prefix]].takeIf { it >= 0L },
            recommendMtp = p[Mtp.benchmarkRecommend[prefix]],
            mtpOnFailure = p[Mtp.benchmarkOnFailure[prefix]],
        )
    }

    fun saveBenchmark(
        context: Context,
        modelId: String,
        backend: LocalComputeBackend,
        modelSignature: String,
        record: LocalMtpBenchmarkRecord,
    ) {
        val prefix = benchmarkPrefix(modelId, backend)
        Mtp.edit(context) {
            this[Mtp.benchmarkSignature[prefix]] = modelSignature
            this[Mtp.benchmarkOffTps[prefix]] = record.mtpOffOutputTokensPerSecond.toBits()
            this[Mtp.benchmarkOffTtft[prefix]] = record.mtpOffTimeToFirstTokenMs ?: -1L
            this[Mtp.benchmarkRecommend[prefix]] = record.recommendMtp
            this[Mtp.benchmarkOnTps[prefix]] = record.mtpOnOutputTokensPerSecond?.toBits()
            val onTtft = Mtp.benchmarkOnTtft[prefix]
            val onTtftMs = record.mtpOnTimeToFirstTokenMs
            if (onTtftMs != null) this[onTtft] = onTtftMs else onTtft.remove(this)
            this[Mtp.benchmarkOnFailure[prefix]] = record.mtpOnFailure
        }
    }

    fun cachedRecommendation(
        context: Context,
        modelId: String,
        backend: LocalComputeBackend,
        modelSignature: String,
    ): Boolean? = getBenchmark(context, modelId, backend, modelSignature)?.recommendMtp

    fun clearForModel(context: Context, modelId: String) {
        val modeKey = Mtp.mode[sanitize(modelId)].key
        val prefs = Mtp.sharedPreferences(context)
        val editor = prefs.edit()
        prefs.all.keys
            .filter { it.startsWith("${sanitize(modelId)}_") || it == modeKey }
            .forEach(editor::remove)
        editor.apply()
    }

    fun modelSignature(path: String, sizeBytes: Long, lastModifiedMs: Long): String =
        "$path|$sizeBytes|$lastModifiedMs"

    private fun benchmarkPrefix(modelId: String, backend: LocalComputeBackend) =
        "${sanitize(modelId)}_${backend.name.lowercase()}_benchmark"

    private fun sanitize(value: String): String =
        value.replace(Regex("[^A-Za-z0-9_.-]"), "_")
}

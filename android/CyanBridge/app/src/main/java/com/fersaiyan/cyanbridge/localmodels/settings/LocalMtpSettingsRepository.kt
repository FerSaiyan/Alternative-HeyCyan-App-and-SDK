package com.fersaiyan.cyanbridge.localmodels.settings

import android.content.Context

enum class LocalMtpMode(val label: String) {
    AUTO("Automatic"),
    ON("On"),
    OFF("Off"),
}

data class LocalMtpBenchmarkRecord(
    val mtpOffOutputTokensPerSecond: Double,
    val mtpOnOutputTokensPerSecond: Double,
    val mtpOffTimeToFirstTokenMs: Long?,
    val mtpOnTimeToFirstTokenMs: Long?,
    val recommendMtp: Boolean,
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
    private const val PREFS_NAME = "local_model_mtp_settings"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getMode(context: Context, modelId: String): LocalMtpMode {
        val raw = prefs(context).getString(modeKey(modelId), null)
        return runCatching { LocalMtpMode.valueOf(raw.orEmpty()) }
            .getOrDefault(LocalMtpMode.AUTO)
    }

    fun setMode(context: Context, modelId: String, mode: LocalMtpMode) {
        prefs(context).edit().putString(modeKey(modelId), mode.name).apply()
    }

    fun getBenchmark(
        context: Context,
        modelId: String,
        backend: LocalComputeBackend,
        modelSignature: String,
    ): LocalMtpBenchmarkRecord? {
        val prefix = benchmarkPrefix(modelId, backend)
        val storedSignature = prefs(context).getString("${prefix}_signature", null)
        if (storedSignature != modelSignature) return null
        if (!prefs(context).contains("${prefix}_recommend")) return null

        return LocalMtpBenchmarkRecord(
            mtpOffOutputTokensPerSecond = Double.fromBits(
                prefs(context).getLong("${prefix}_off_tps", 0L),
            ),
            mtpOnOutputTokensPerSecond = Double.fromBits(
                prefs(context).getLong("${prefix}_on_tps", 0L),
            ),
            mtpOffTimeToFirstTokenMs = prefs(context)
                .getLong("${prefix}_off_ttft", -1L)
                .takeIf { it >= 0L },
            mtpOnTimeToFirstTokenMs = prefs(context)
                .getLong("${prefix}_on_ttft", -1L)
                .takeIf { it >= 0L },
            recommendMtp = prefs(context).getBoolean("${prefix}_recommend", true),
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
        prefs(context).edit()
            .putString("${prefix}_signature", modelSignature)
            .putLong("${prefix}_off_tps", record.mtpOffOutputTokensPerSecond.toBits())
            .putLong("${prefix}_on_tps", record.mtpOnOutputTokensPerSecond.toBits())
            .putLong("${prefix}_off_ttft", record.mtpOffTimeToFirstTokenMs ?: -1L)
            .putLong("${prefix}_on_ttft", record.mtpOnTimeToFirstTokenMs ?: -1L)
            .putBoolean("${prefix}_recommend", record.recommendMtp)
            .apply()
    }

    fun cachedRecommendation(
        context: Context,
        modelId: String,
        backend: LocalComputeBackend,
        modelSignature: String,
    ): Boolean? = getBenchmark(context, modelId, backend, modelSignature)?.recommendMtp

    fun clearForModel(context: Context, modelId: String) {
        val editor = prefs(context).edit()
        prefs(context).all.keys
            .filter { it.startsWith("${sanitize(modelId)}_") || it == modeKey(modelId) }
            .forEach(editor::remove)
        editor.apply()
    }

    fun modelSignature(path: String, sizeBytes: Long, lastModifiedMs: Long): String =
        "$path|$sizeBytes|$lastModifiedMs"

    private fun modeKey(modelId: String) = "${sanitize(modelId)}_mode"

    private fun benchmarkPrefix(modelId: String, backend: LocalComputeBackend) =
        "${sanitize(modelId)}_${backend.name.lowercase()}_benchmark"

    private fun sanitize(value: String): String =
        value.replace(Regex("[^A-Za-z0-9_.-]"), "_")
}

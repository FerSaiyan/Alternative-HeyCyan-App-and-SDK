package com.fersaiyan.cyanbridge.localmodels.benchmark

/** Steps of the MTP on/off comparison, reported while the performance test runs. */
sealed interface LocalModelBenchmarkProgress {
    data object InspectingModel : LocalModelBenchmarkProgress
    data class CapabilityReady(val mtpSupported: Boolean) : LocalModelBenchmarkProgress
    data class WarmingUp(val mtpEnabled: Boolean) : LocalModelBenchmarkProgress
    data class Measuring(val mtpEnabled: Boolean) : LocalModelBenchmarkProgress
    data class MeasurementReady(
        val mtpEnabled: Boolean,
        val measurement: LocalModelBenchmarkMeasurement,
    ) : LocalModelBenchmarkProgress
    data class Finished(val result: LocalModelBenchmarkResult) : LocalModelBenchmarkProgress
}

data class LocalModelBenchmarkMeasurement(
    val mtpEnabled: Boolean,
    val initTimeMs: Long,
    val timeToFirstTokenMs: Long,
    val prefillTokens: Int,
    val decodeTokens: Int,
    val prefillTokensPerSecond: Double,
    val decodeTokensPerSecond: Double,
)

data class LocalModelBenchmarkResult(
    val mtpSupported: Boolean,
    val mtpOff: LocalModelBenchmarkMeasurement,
    val mtpOn: LocalModelBenchmarkMeasurement?,
    val recommendMtp: Boolean,
    val mtpOnFailure: String? = null,
    val mtpOnTimedOut: Boolean = false,
) {
    val decodeSpeedChangePercent: Double?
        get() = mtpOn?.let { on ->
            if (mtpOff.decodeTokensPerSecond <= 0.0) null
            else ((on.decodeTokensPerSecond / mtpOff.decodeTokensPerSecond) - 1.0) * 100.0
        }
}

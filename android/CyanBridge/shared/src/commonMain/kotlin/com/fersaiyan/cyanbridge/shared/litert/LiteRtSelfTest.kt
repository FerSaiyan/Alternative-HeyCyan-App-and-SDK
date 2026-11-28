package com.fersaiyan.cyanbridge.shared.litert

import kotlin.math.sqrt
import kotlin.time.TimeSource

/** One opened model. Not thread-safe: use one session from one coroutine at a time. */
interface LiteRtSession {
    /** Where it really runs (a GPU open may have fallen back to the CPU). */
    val accelerator: LiteRtAccelerator

    /** One inference: float32 input 0 -> float32 outputs in signature order. */
    fun run(input: FloatArray): List<FloatArray>

    fun close()
}

/** LiteRT CompiledModel on this platform (iOS: Swift bridge over LiteRT-Swift; Android: the LiteRT Kotlin API). */
interface LiteRtModelRunner {
    val gpuAvailable: Boolean

    /**
     * Opens [path] on [accelerator]. A GPU open that fails falls back to the CPU (check [LiteRtSession.accelerator]).
     * [cacheDir]: GPU program cache directory, or null. Throws when even the CPU open fails.
     */
    fun open(path: String, accelerator: LiteRtAccelerator, precision: LiteRtGpuPrecision?, cacheDir: String?): LiteRtSession
}

/** Downloads catalog files with size + SHA-256 checks and keeps them across launches. */
interface LiteRtModelInstaller {
    /** Path of an already installed (size-checked) copy, or null. */
    fun installedPath(file: LiteRtModelFile): String?

    /** Installs [file] (no-op when installed) and returns its path; throws on network or checksum failure. */
    suspend fun install(file: LiteRtModelFile): String

    /** A directory for GPU program caches, or null to run without one. */
    fun programCacheDir(): String?
}

/** Result of [LiteRtSelfTest] for one file. */
data class LiteRtSelfTestRow(
    val path: String,
    val kind: LiteRtModelKind,
    val installed: Boolean,
    val installMs: Long? = null,
    /** Accelerator the hint asks for, and the one the session really used. */
    val wanted: LiteRtAccelerator? = null,
    val used: LiteRtAccelerator? = null,
    val cpuMs: Double? = null,
    val acceleratedMs: Double? = null,
    /** Lowest per-output cosine of the accelerated run vs the CPU run (same synthetic input). */
    val minCosVsCpu: Double? = null,
    val ok: Boolean,
    val error: String? = null,
) {
    fun summary(): String = buildString {
        append(if (ok) "OK   " else "FAIL ")
        append(path.substringAfterLast('/'))
        if (!installed) append(" not installed")
        installMs?.let { append(" install ${it} ms") }
        if (wanted != null) append(" wanted=$wanted used=$used")
        cpuMs?.let { append(" cpu ${fmt(it)} ms") }
        acceleratedMs?.let { append(" ${used?.name?.lowercase()} ${fmt(it)} ms") }
        minCosVsCpu?.let { append(" cos ${fmt(it, 4)}") }
        error?.let { append(" error: $it") }
    }

    private fun fmt(v: Double, digits: Int = 1): String {
        var scale = 1.0
        repeat(digits) { scale *= 10 }
        val r = kotlin.math.round(v * scale) / scale
        return r.toString()
    }
}

/**
 * Installs every file a platform needs and runs each model once on the CPU and once on its hinted accelerator with
 * the same deterministic input: checks output sizes against the catalog, finite values, GPU-vs-CPU cosine and timing.
 * Proves "the app can download and run the models"; feature accuracy is measured elsewhere (LiteRtGpuBench).
 */
class LiteRtSelfTest(
    private val installer: LiteRtModelInstaller,
    private val runner: LiteRtModelRunner,
    private val log: (String) -> Unit = {},
    private val timedRuns: Int = 5,
    /** Below this cosine (synthetic input) an accelerated run counts as wrong. */
    private val minCos: Double = 0.95,
) {
    suspend fun run(files: List<LiteRtModelFile> = LiteRtModelSelection.iosFiles()): List<LiteRtSelfTestRow> =
        files.map { file ->
            val row = runCatching { testOne(file) }.getOrElse {
                LiteRtSelfTestRow(file.path, file.kind, installed = installer.installedPath(file) != null, ok = false,
                    error = it.message ?: it.toString())
            }
            log(row.summary())
            row
        }

    private suspend fun testOne(file: LiteRtModelFile): LiteRtSelfTestRow {
        val started = TimeSource.Monotonic.markNow()
        val path = installer.install(file)
        val installMs = started.elapsedNow().inWholeMilliseconds
        if (!file.isModel) return LiteRtSelfTestRow(file.path, file.kind, installed = true, installMs = installMs, ok = true)
        val hint = file.ios
        val input = syntheticInput(file.inputs.firstOrNull()?.elements ?: error("no input spec"))

        val cpu = runner.open(path, LiteRtAccelerator.CPU, null, null)
        val (cpuOut, cpuMs) = try { timed(cpu, input) } finally { cpu.close() }
        checkOutputs(file, cpuOut)

        val wanted = hint?.accelerator ?: LiteRtAccelerator.CPU
        if (wanted == LiteRtAccelerator.CPU) {
            return LiteRtSelfTestRow(file.path, file.kind, true, installMs, wanted, LiteRtAccelerator.CPU, cpuMs, ok = true)
        }
        val cache = if (hint?.programCache == true) installer.programCacheDir() else null
        val gpu = runner.open(path, wanted, hint?.gpuPrecision, cache)
        val used = gpu.accelerator
        val (gpuOut, gpuMs) = try { timed(gpu, input) } finally { gpu.close() }
        checkOutputs(file, gpuOut)
        val cos = cpuOut.indices.minOf { cosine(cpuOut[it], gpuOut[it]) }
        val ok = used == wanted && cos >= minCos
        return LiteRtSelfTestRow(
            file.path, file.kind, true, installMs, wanted, used, cpuMs, gpuMs, cos, ok,
            error = when {
                used != wanted -> "fell back to $used"
                cos < minCos -> "accelerated output disagrees with the CPU (cos $cos)"
                else -> null
            },
        )
    }

    /** First run (warm-up) excluded; returns the outputs of the first run and the median of [timedRuns]. */
    private fun timed(session: LiteRtSession, input: FloatArray): Pair<List<FloatArray>, Double> {
        val outputs = session.run(input)
        val times = (1..timedRuns).map {
            val mark = TimeSource.Monotonic.markNow()
            session.run(input)
            mark.elapsedNow().inWholeMicroseconds / 1000.0
        }.sorted()
        return outputs to times[times.size / 2]
    }

    private fun checkOutputs(file: LiteRtModelFile, outputs: List<FloatArray>) {
        check(outputs.size == file.outputs.size) { "expected ${file.outputs.size} outputs, got ${outputs.size}" }
        outputs.forEachIndexed { i, values ->
            check(values.size == file.outputs[i].elements) {
                "output ${file.outputs[i].name}: expected ${file.outputs[i].elements} values, got ${values.size}"
            }
            check(values.all { it.isFinite() }) { "output ${file.outputs[i].name} has NaN/Inf" }
        }
    }

    companion object {
        /** Deterministic uniform [0, 1) values (same on every platform). */
        fun syntheticInput(n: Int): FloatArray {
            var state = 0x9E3779B97F4A7C15uL
            return FloatArray(n) {
                state = state * 6364136223846793005uL + 1442695040888963407uL
                (state shr 40).toFloat() / (1 shl 24).toFloat()
            }
        }

        fun cosine(a: FloatArray, b: FloatArray): Double {
            require(a.size == b.size) { "size mismatch ${a.size} vs ${b.size}" }
            var dot = 0.0
            var na = 0.0
            var nb = 0.0
            for (i in a.indices) {
                val x = a[i].toDouble()
                val y = b[i].toDouble()
                dot += x * y
                na += x * x
                nb += y * y
            }
            if (na == 0.0 && nb == 0.0) return 1.0
            if (na == 0.0 || nb == 0.0) return 0.0
            return dot / (sqrt(na) * sqrt(nb))
        }
    }
}

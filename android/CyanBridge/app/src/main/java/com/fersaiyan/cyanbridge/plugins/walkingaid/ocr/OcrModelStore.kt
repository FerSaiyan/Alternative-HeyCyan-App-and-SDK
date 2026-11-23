package com.fersaiyan.cyanbridge.plugins.walkingaid.ocr

import android.content.Context
import android.util.Log
import com.fersaiyan.cyanbridge.localmodels.engine.NpuArtifactDownloader
import com.fersaiyan.cyanbridge.localmodels.engine.NpuArtifacts
import com.fersaiyan.cyanbridge.localmodels.engine.NpuArtifactList
import com.fersaiyan.cyanbridge.localmodels.engine.NpuDType
import java.io.File

/**
 * Files of the Walking Aid reader in `filesDir/ocr/`: the CPU/GPU PP-OCRv6 small detector and recognizer (every phone),
 * both recognizer dictionaries, and this phone's NPU builds when cyanbridge-npu-models has them (Tensor phones get
 * the tiny pair, whose dictionary differs). Hosted in Fertroll10/cyanbridge-npu-models (cpu_gpu/ANY), pinned and
 * checked by size + SHA-256 like the NPU files.
 */
internal object OcrModelStore {
    private const val TAG = "OcrModelStore"
    private const val DIR = "ocr"

    /** CPU/GPU detector, recognizer and dictionaries ([NpuArtifacts.Artifact] reused for its download fields). */
    internal val cpuFiles: List<NpuArtifacts.Artifact> get() = NpuArtifactList.cpuOcr

    fun dir(context: Context) = File(context.filesDir, DIR)

    private fun file(context: Context, artifact: NpuArtifacts.Artifact) = File(dir(context), artifact.localName)

    /** Everything the reader needs on this phone is installed. */
    fun isInstalled(context: Context): Boolean =
        cpuFiles.isNotEmpty() && cpuFiles.all { NpuArtifactDownloader.isInstalled(file(context, it), it) }

    /** Downloads the CPU files, then this phone's NPU builds (not fatal). Blocking; [onProgress] gets 0..100. */
    fun install(context: Context, onProgress: (Int) -> Unit = {}) {
        val npu = listOfNotNull(
            NpuArtifacts.forDevice(context, NpuArtifacts.Model.OCR_DET),
            NpuArtifacts.forDevice(context, NpuArtifacts.Model.OCR_REC),
        )
        val all = cpuFiles + npu
        val total = all.sumOf { it.bytes }.coerceAtLeast(1)
        var done = 0L
        for (artifact in all) {
            val required = artifact in cpuFiles
            runCatching {
                NpuArtifactDownloader.install(context, artifact, file(context, artifact)) { percent ->
                    onProgress(((done + artifact.bytes * percent / 100) * 100 / total).toInt())
                }
            }.onFailure {
                if (required) throw it
                Log.w(TAG, "NPU build ${artifact.localName} not installed; the CPU model reads instead: ${it.message}")
            }
            done += artifact.bytes
        }
    }

    /** The reader for this phone (NPU where installed, else CPU), or null until [install] has run. */
    fun open(context: Context): PpOcrEngine? {
        if (!isInstalled(context)) return null
        val cpuDet = cpuFiles.first { it.model == NpuArtifacts.Model.OCR_DET }
        val cpuRec = cpuFiles.first { it.model == NpuArtifacts.Model.OCR_REC }
        val npuDet = NpuArtifacts.forDevice(context, NpuArtifacts.Model.OCR_DET)
        val npuRec = NpuArtifacts.forDevice(context, NpuArtifacts.Model.OCR_REC)
        val det = PpOcrEngine.npuNet(context, npuDet, npuDet?.let { file(context, it) }?.takeIf { NpuArtifactDownloader.isInstalled(it, npuDet) })
            ?: CpuOcrNet(file(context, cpuDet), cpuDet.outputs.single().elements)
        val recArtifact = npuRec?.takeIf { NpuArtifactDownloader.isInstalled(file(context, it), it) }
        val rec = PpOcrEngine.npuNet(context, recArtifact, recArtifact?.let { file(context, it) })
            ?: CpuOcrNet(file(context, cpuRec), cpuRec.outputs.single().elements)
        val classes = ((if (rec is NpuOcrNet) recArtifact else cpuRec)!!.outputs.single().shape.last())
        val dictionary = cpuFiles.firstOrNull { it.model == NpuArtifacts.Model.OCR_DICT && it.outputs.single().shape.last() == classes }
            ?: run {
                Log.w(TAG, "No dictionary for a $classes-class recognizer")
                det.close(); rec.close()
                return null
            }
        return PpOcrEngine(det, rec, PpOcrEngine.loadChars(file(context, dictionary), classes)).also {
            Log.i(TAG, "OCR ready: ${it.description}")
        }
    }

    /** A dictionary entry's "output" spec records the recognizer class count it belongs to. */
    internal fun dictionarySpec(classes: Int) = NpuArtifacts.TensorSpec("classes", intArrayOf(classes), NpuDType.FLOAT32)
}

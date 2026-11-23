package com.fersaiyan.cyanbridge.hil

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fersaiyan.cyanbridge.localmodels.engine.LiteRtNpuSupport
import com.fersaiyan.cyanbridge.localmodels.engine.NpuArtifactDownloader
import com.fersaiyan.cyanbridge.localmodels.engine.NpuArtifacts
import com.fersaiyan.cyanbridge.localmodels.engine.NpuFloatRunner
import com.fersaiyan.cyanbridge.plugins.walkingaid.ocr.OcrModelStore
import com.fersaiyan.cyanbridge.plugins.walkingaid.ocr.OcrText
import com.fersaiyan.cyanbridge.plugins.walkingaid.ocr.PpOcrEngine
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * The app's per-SoC NPU catalog on a real phone (Google Device Run): for each model with a build for this SoC, the
 * app's own downloader fetches it (size + SHA-256, NPU runtime alongside), NpuFloatRunner runs the host fixtures and
 * the outputs are compared with the CPU source; then the Walking Aid reader (OcrModelStore + PpOcrEngine) reads a
 * bus-sign and a plate crop end to end. Writes one JSON line per check to nc_out. Skips unless nc_dir has fixtures.
 */
@RunWith(AndroidJUnit4::class)
class NpuCatalogDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private lateinit var out: File

    @Test fun npuCatalog() {
        val args = InstrumentationRegistry.getArguments()
        val dir = stage(args.getString("nc_dir").orEmpty())
        assumeTrue("Requires nc_dir with fixtures.json", File(dir, "fixtures.json").isFile)
        out = File(args.getString("nc_out") ?: File(context.getExternalFilesDir(null), "litert-eval/npu_catalog.jsonl").path)
        out.parentFile?.mkdirs()
        val npu = LiteRtNpuSupport.status(context)
        log("device", "model" to android.os.Build.MODEL, "soc" to npu.socModel, "vendor" to npu.vendor?.name, "npu" to npu.reason)
        val fixtures = JSONObject(File(dir, "fixtures.json").readText()).getJSONObject("models")

        for (model in NpuArtifacts.Model.entries.filter { it != NpuArtifacts.Model.OCR_DICT }) {
            val artifact = NpuArtifacts.forDevice(context, model)
            if (artifact == null) { log("no_build", "model" to model.name); continue }
            runCatching {
                val t0 = System.nanoTime()
                val file = NpuArtifactDownloader.install(context, artifact, File(context.filesDir, "npucat/${artifact.localName}"))
                val downloadS = (System.nanoTime() - t0) / 1e9
                val t1 = System.nanoTime()
                NpuFloatRunner.open(context, artifact, file.path).use { runner ->
                    val loadS = (System.nanoTime() - t1) / 1e9
                    log("loaded", "model" to model.name, "file" to artifact.localName, "download_s" to downloadS, "load_s" to loadS)
                    when (model) {
                        NpuArtifacts.Model.YOLO11N -> checkYolo(runner, dir)
                        NpuArtifacts.Model.OCR_DET -> if ("small" in artifact.localName) checkDet(runner, dir, fixtures)
                        NpuArtifacts.Model.OCR_REC -> if ("small" in artifact.localName) checkRec(runner, dir, fixtures)
                        NpuArtifacts.Model.WESPEAKER -> checkSpeaker(runner, dir)
                        NpuArtifacts.Model.OCR_DICT -> Unit
                    }
                }
            }.onFailure { log("model_failed", "model" to model.name, "file" to artifact.localName, "error" to it.toString()) }
        }

        // The reader end to end: CPU files + this phone's NPU builds, as Walking Aid installs and opens them.
        runCatching {
            val t0 = System.nanoTime()
            OcrModelStore.install(context)
            val engine = checkNotNull(OcrModelStore.open(context)) { "reader did not open" }
            log("reader_ready", "engine" to engine.description, "install_s" to (System.nanoTime() - t0) / 1e9)
            engine.use {
                val sign = BitmapFactory.decodeFile(File(dir, "sign_especial.png").path)
                val t1 = System.nanoTime()
                val signText = it.read(sign).joinToString(" ") { line -> line.text }
                log("reader_sign", "text" to signText, "ok" to OcrText.busSignMatches(signText, "ESPECIAL"), "s" to (System.nanoTime() - t1) / 1e9)
                val plate = BitmapFactory.decodeFile(File(dir, "plate_klp5a21.png").path)
                val t2 = System.nanoTime()
                val lines = it.read(plate, plate = true).map { line -> line.text }
                val plateRead = (lines + lines.joinToString("")).map(OcrText::plateFormatFix).firstOrNull { p -> p.length == 7 }.orEmpty()
                log("reader_plate", "lines" to lines.joinToString("|"), "plate" to plateRead,
                    "ok" to OcrText.plateMatches(plateRead, "KLP5A21"), "s" to (System.nanoTime() - t2) / 1e9)
            }
        }.onFailure { log("reader_failed", "error" to it.toString()) }
    }

    /** bus.jpg: detections above 0.35 after class-aware NMS vs the CPU source's (bus 0.901, 4 people). */
    private fun checkYolo(runner: NpuFloatRunner, dir: File) {
        val outputs = runner.run(readFloats(File(dir, "yolo_00_in_image.raw")))
        val boxes = outputs.first { o -> outputs.any { it.size * 4 == o.size } }
        val n = boxes.size / 4
        val perBox = outputs.filter { it.size == n }
        val probs = outputs.firstOrNull { it.size > n && it !== boxes }
        val classes = IntArray(n)
        val scores = FloatArray(n)
        if (probs != null) {
            val c = probs.size / n
            for (i in 0 until n) {
                var bc = 0
                for (k in 1 until c) if (probs[i * c + k] > probs[i * c + bc]) bc = k
                classes[i] = bc; scores[i] = probs[i * c + bc]
            }
        } else {
            val (a, b) = perBox
            val (idx, sc) = if ((a.maxOrNull() ?: 0f) > 1.5f) a to b else b to a
            for (i in 0 until n) { classes[i] = idx[i].toInt(); scores[i] = sc[i] }
        }
        val kept = mutableListOf<Int>()
        for (i in (0 until n).filter { scores[it] > 0.35f }.sortedByDescending { scores[it] }) {
            if (kept.none { classes[it] == classes[i] && iou(boxes, it, i) > 0.45f }) kept += i
        }
        val found = kept.map { classes[it] to scores[it] }
        val expected = listOf(5 to 0.901f, 0 to 0.893f, 0 to 0.892f, 0 to 0.884f, 0 to 0.722f)
        val ok = found.count { it.first == 5 } == 1 && found.count { it.first == 0 } in 4..5
        log("yolo", "detections" to found.joinToString { "${it.first}:${"%.3f".format(it.second)}" },
            "expected" to expected.joinToString { "${it.first}:${it.second}" }, "ok" to ok)
    }

    private fun iou(b: FloatArray, i: Int, j: Int): Float {
        val ix = maxOf(0f, minOf(b[i * 4 + 2], b[j * 4 + 2]) - maxOf(b[i * 4], b[j * 4]))
        val iy = maxOf(0f, minOf(b[i * 4 + 3], b[j * 4 + 3]) - maxOf(b[i * 4 + 1], b[j * 4 + 1]))
        val inter = ix * iy
        val area = { k: Int -> (b[k * 4 + 2] - b[k * 4]) * (b[k * 4 + 3] - b[k * 4 + 1]) }
        return inter / (area(i) + area(j) - inter + 1e-6f)
    }

    /** Text map: agreement of the > 0.2 mask with the CPU source (fixtures suggest >= 0.995). */
    private fun checkDet(runner: NpuFloatRunner, dir: File, fixtures: JSONObject) {
        for (id in listOf("det_00", "det_06")) {
            val got = runner.run(readFloats(File(dir, "${id}_in_x.raw"))).single()
            val want = readFloats(File(dir, "${id}_out_Identity.raw"))
            val agree = got.indices.count { (got[it] > 0.2f) == (want[it] > 0.2f) }.toDouble() / got.size
            log("det", "case" to id, "mask_agree" to agree, "ok" to (agree >= 0.995))
        }
    }

    /** Recognizer: identical CTC string to the fixture's expected text, with the small dictionary. */
    private fun checkRec(runner: NpuFloatRunner, dir: File, fixtures: JSONObject) {
        OcrModelStore.install(context)
        val dict = File(OcrModelStore.dir(context), "ppocrv6_small_rec_dict.txt")
        val cases = fixtures.getJSONObject("ppocrv6-small-rec-48x320").getJSONArray("cases")
        for (k in 0 until cases.length()) {
            val case = cases.getJSONObject(k)
            val probs = runner.run(readFloats(File(dir, case.getJSONObject("input").getString("file")))).single()
            val classes = probs.size / PpOcrEngine.RECOGNIZER_STEPS
            val text = OcrText.ctcGreedy(probs, classes, PpOcrEngine.loadChars(dict, classes)).first
            log("rec", "case" to case.getString("id"), "text" to text, "expected" to case.getString("expected_text"),
                "ok" to (text == case.getString("expected_text")))
        }
    }

    /** WeSpeaker: cosine of the NPU embedding with the host CPU embedding of the same 2 s clip. */
    private fun checkSpeaker(runner: NpuFloatRunner, dir: File) {
        val clips = JSONObject(File(dir, "speaker_manifest.json").readText()).getJSONArray("clips")
        for (k in 0 until clips.length()) {
            val clip = clips.getJSONObject(k)
            val pcm = File(dir, clip.getString("file")).takeIf { it.isFile } ?: continue
            val bytes = ByteBuffer.wrap(pcm.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
            val samples = FloatArray(32_000) { if (bytes.remaining() >= 2) bytes.short / 32768f else 0f }
            val got = runner.run(samples).single()
            val hostArr = clip.getJSONArray("host_embedding")
            val host = FloatArray(hostArr.length()) { hostArr.getDouble(it).toFloat() }
            val cos = cosine(got, host)
            log("speaker", "clip" to clip.getString("name"), "cosine" to cos, "ok" to (cos >= 0.999))
        }
    }

    private fun cosine(a: FloatArray, b: FloatArray): Double {
        var dot = 0.0; var na = 0.0; var nb = 0.0
        for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        return dot / (sqrt(na) * sqrt(nb) + 1e-12)
    }

    private fun readFloats(file: File): FloatArray {
        val buffer = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(buffer.remaining()).also { buffer.get(it) }
    }

    /** Device Run clears app storage first: copy /data/local/tmp inputs in through the shell user. */
    private fun stage(path: String): File {
        if (!path.startsWith("/data/local/tmp/")) return File(path)
        val dst = File(context.getExternalFilesDir(null), "npucat-in").apply { mkdirs() }
        instrumentation.uiAutomation.executeShellCommand("cp -r $path/. ${dst.absolutePath}/").use { pfd ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
        }
        return dst
    }

    private fun log(event: String, vararg fields: Pair<String, Any?>) {
        val line = JSONObject(mapOf("event" to event) + fields.toMap()).toString()
        println("NC $line")
        out.appendText(line + "\n")
    }
}

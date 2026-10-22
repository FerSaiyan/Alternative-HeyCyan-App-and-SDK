package com.fersaiyan.cyanbridge.hil

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fersaiyan.cyanbridge.localmodels.engine.LiteRtNpuModelFactory
import com.fersaiyan.cyanbridge.localmodels.engine.LiteRtNpuSupport
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

/**
 * Opt-in probe for the Walking Aid detectors on the Qualcomm NPU (docs/NPU_ON_DEVICE.md):
 * YOLO11n (Qualcomm AI Hub style uint8 export: image -> boxes, scores, class_idx) and
 * YOLO-World (float image in [0, 1] -> anchor-distance boxes, scores [8400, 80]), as
 * LiteRtVisionBackend downloads them. The fixture comes from
 * tools/litert-npu/make_yolo_fixture.py (public COCO val2017 images, preprocessed like the app).
 *
 * - CPU: LiteRT CompiledModel on the plain model must give the Interpreter's exact outputs.
 *   For YOLO11n this proves the raw-byte TensorBuffer path for uint8 tensors that the NPU
 *   path uses (the Kotlin API has no UINT8 element type). Runs on any ABI.
 * - NPU: the app's LiteRtNpuModelFactory with the AOT model for this SoC must reproduce the
 *   host QNN HTP emulation's detections when the fixture has them. Skipped unless the NPU
 *   runtime and SoC are available. With -e yolo_dump_dir, the raw NPU outputs are written to
 *   the app's external files dir (<image>.out<k>.bin) for host scoring
 *   (yolo_htp_parity.py --npu-raws), which on a phone is much faster than host emulation.
 *
 * Arguments:
 *   -e yolo_fixture     <device dir with manifest.json and *.rgb>
 *   -e yolo_plain_model <device path to yolo11n.tflite or yolo_world.tflite>
 *   -e yolo_npu_model   <device path to its *_Qualcomm_<SoC>_apply_plugin.tflite>
 *   -e yolo_dump_dir    <optional directory name under the app's external files dir>
 *   -e yolo_soak_seconds <optional: run frames continuously for N s on the backend chosen by
 *                         -e yolo_soak_backend npu|cpu (default npu), logging latency per 10 s;
 *                         sample `dumpsys meminfo` and temperatures from the host meanwhile>
 */
@RunWith(AndroidJUnit4::class)
class WalkingAidNpuDetectorTest {
    private val args = InstrumentationRegistry.getArguments()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private data class Detection(val label: Int, val score: Float, val box: FloatArray)

    @Test
    fun yolo_compiledModelCpu_matchesInterpreterBytes() {
        val fixture = fixtureOrSkip()
        val plain = args.getString("yolo_plain_model").orEmpty()
        assumeTrue("pass -e yolo_plain_model", plain.isNotBlank())
        val interpreter = Interpreter(File(plain), Interpreter.Options().setNumThreads(4))
        val env = Environment.create()
        val model = CompiledModel.create(plain, CompiledModel.Options(Accelerator.CPU), env)
        val inputs = model.createInputBuffers()
        val outputs = model.createOutputBuffers()
        try {
            val floatInput = interpreter.getInputTensor(0).dataType() == DataType.FLOAT32
            val floatOutputs = (0 until interpreter.outputTensorCount).map { interpreter.getOutputTensor(it).dataType() == DataType.FLOAT32 }
            for (image in fixture.images) {
                val expected = runInterpreter(interpreter, image.pixels)
                if (floatInput) inputs[0].writeFloat(toFloats(image.pixels)) else inputs[0].writeInt8(image.pixels)
                model.run(inputs, outputs)
                outputs.forEachIndexed { i, out ->
                    val actual = if (floatOutputs[i]) floatBytes(out.readFloat()) else out.readInt8()
                    assertArrayEquals("output $i of ${image.file}", expected[i], actual)
                }
            }
            val (recall, precision) = agreement(fixture.images.map { decode(interpreter, runInterpreter(interpreter, it.pixels)) }, fixture.images.map { it.cpu })
            println("YOLO_NPU cpu compiledModel=interpreter bytes ok; vs host CPU recall=$recall precision=$precision")
        } finally {
            inputs.forEach { it.close() }
            outputs.forEach { it.close() }
            model.close()
            env.close()
            interpreter.close()
        }
    }

    @Test
    fun yolo_npu_reproducesEmulatedDetections() {
        val fixture = fixtureOrSkip()
        val plain = args.getString("yolo_plain_model").orEmpty()
        val aot = args.getString("yolo_npu_model").orEmpty()
        assumeTrue("pass -e yolo_plain_model and -e yolo_npu_model", plain.isNotBlank() && aot.isNotBlank())
        val npu = LiteRtNpuSupport.status(context)
        println("YOLO_NPU status available=${npu.available} soc=${npu.socModel} reason=${npu.reason}")
        assumeTrue(npu.reason, npu.available)
        LiteRtNpuSupport.compiledFor(aot)?.let { assumeTrue("model is for $it, phone is ${npu.socModel}", it == npu.socModel) }

        val interpreter = Interpreter(File(plain), Interpreter.Options().setNumThreads(4))
        val loadStart = System.nanoTime()
        val model = LiteRtNpuModelFactory.open(context, aot)
        val loadMs = (System.nanoTime() - loadStart) / 1e6
        try {
            val floats = (0 until interpreter.outputTensorCount).map { interpreter.getOutputTensor(it).dataType() == DataType.FLOAT32 }
            val floatInput = interpreter.getInputTensor(0).dataType() == DataType.FLOAT32
            fun runNpu(pixels: ByteArray): List<ByteArray> =
                model.run(inputBuffer(pixels, floatInput), inputIsFloat = floatInput, outputIsFloat = floats).map { buf ->
                    ByteArray(buf.capacity()).also { buf.rewind(); buf.get(it) }
                }
            val dumpDir = args.getString("yolo_dump_dir")?.let { File(context.getExternalFilesDir(null), it).apply { mkdirs() } }
            val npuDetections = fixture.images.map { image ->
                val raw = runNpu(image.pixels)
                dumpDir?.let { dir -> raw.forEachIndexed { k, bytes -> File(dir, "${image.file.removeSuffix(".rgb")}.out$k.bin").writeBytes(bytes) } }
                decode(interpreter, raw)
            }
            val emulated = fixture.images.map { it.htp }
            val (recall, precision) = if (emulated.all { it != null }) agreement(npuDetections, emulated.map { it!! }) else Double.NaN to Double.NaN
            val (cpuRecall, cpuPrecision) = agreement(npuDetections, fixture.images.map { it.cpu })

            val probe = fixture.images.first().pixels
            fun median(block: () -> Unit): Double {
                repeat(3) { block() }
                return (0 until 20).map { val t = System.nanoTime(); block(); (System.nanoTime() - t) / 1e6 }.sorted()[10]
            }
            val npuMs = median { runNpu(probe) }
            val cpuMs = median { runInterpreter(interpreter, probe) }
            println(
                "YOLO_NPU summary loadMs=$loadMs npuMedianMs=$npuMs cpuMedianMs=$cpuMs " +
                    "vsEmulated recall=$recall precision=$precision vsHostCpu recall=$cpuRecall precision=$cpuPrecision " +
                    "images=${fixture.images.size} dump=${dumpDir?.path}",
            )
            args.getString("yolo_soak_seconds")?.toInt()?.let { seconds ->
                val onCpu = args.getString("yolo_soak_backend") == "cpu"
                soak(seconds, if (onCpu) "cpu" else "npu", fixture) { pixels ->
                    if (onCpu) runInterpreter(interpreter, pixels) else runNpu(pixels)
                }
            }
            if (!recall.isNaN()) {
                assertTrue("NPU detections differ from host HTP emulation (recall $recall, precision $precision)", recall >= 0.9 && precision >= 0.9)
            }
        } finally {
            model.close()
            interpreter.close()
        }
    }

    /** Continuous inference over the fixture images; logs frames and latency per 10 s window. */
    private fun soak(seconds: Int, label: String, fixture: Fixture, run: (ByteArray) -> Unit) {
        val end = System.nanoTime() + seconds * 1_000_000_000L
        var window = mutableListOf<Double>()
        var windowStart = System.nanoTime()
        var frame = 0
        while (System.nanoTime() < end) {
            val t = System.nanoTime()
            run(fixture.images[frame++ % fixture.images.size].pixels)
            window += (System.nanoTime() - t) / 1e6
            if (System.nanoTime() - windowStart >= 10_000_000_000L) {
                val sorted = window.sorted()
                println("YOLO_SOAK backend=$label tS=${(System.nanoTime() - end) / 1e9 + seconds} frames=${sorted.size} " +
                    "p50Ms=${sorted[sorted.size / 2]} p95Ms=${sorted[(sorted.size * 95) / 100]}")
                window = mutableListOf()
                windowStart = System.nanoTime()
            }
        }
        println("YOLO_SOAK backend=$label done frames=$frame")
    }

    private class FixtureImage(val file: String, val pixels: ByteArray, val cpu: List<Detection>, val htp: List<Detection>?)
    private class Fixture(val images: List<FixtureImage>)

    private fun fixtureOrSkip(): Fixture {
        val dir = args.getString("yolo_fixture").orEmpty()
        assumeTrue("pass -e yolo_fixture", dir.isNotBlank())
        val manifest = JSONObject(File(dir, "manifest.json").readText())
        val images = manifest.getJSONArray("images")
        fun parse(array: org.json.JSONArray) = (0 until array.length()).map { i ->
            val d = array.getJSONObject(i)
            val box = d.getJSONArray("box")
            Detection(d.getInt("label"), d.getDouble("score").toFloat(), FloatArray(4) { box.getDouble(it).toFloat() })
        }
        return Fixture((0 until images.length()).map { i ->
            val row = images.getJSONObject(i)
            val htp = row.optJSONArray("htp")?.let(::parse)
            FixtureImage(row.getString("file"), File(dir, row.getString("file")).readBytes(), parse(row.getJSONArray("cpu")), htp)
        })
    }

    private fun toFloats(pixels: ByteArray) = FloatArray(pixels.size) { (pixels[it].toInt() and 0xFF) / 255f }

    private fun floatBytes(values: FloatArray): ByteArray =
        ByteBuffer.allocate(values.size * 4).order(ByteOrder.nativeOrder()).also { it.asFloatBuffer().put(values) }.array()

    /** App input: raw uint8 RGB, or RGB / 255 as native-order float32. */
    private fun inputBuffer(pixels: ByteArray, float: Boolean): ByteBuffer =
        if (!float) {
            ByteBuffer.allocateDirect(pixels.size).order(ByteOrder.nativeOrder()).put(pixels).also { it.rewind() }
        } else {
            ByteBuffer.allocateDirect(pixels.size * 4).order(ByteOrder.nativeOrder()).also {
                it.asFloatBuffer().put(toFloats(pixels))
                it.rewind()
            }
        }

    private fun runInterpreter(interpreter: Interpreter, pixels: ByteArray): List<ByteArray> {
        val input = inputBuffer(pixels, interpreter.getInputTensor(0).dataType() == DataType.FLOAT32)
        val outputs = (0 until interpreter.outputTensorCount).associateWith {
            ByteBuffer.allocateDirect(interpreter.getOutputTensor(it).numBytes()).order(ByteOrder.nativeOrder())
        }
        interpreter.runForMultipleInputsOutputs(arrayOf(input), outputs)
        return outputs.values.map { buf -> ByteArray(buf.capacity()).also { buf.rewind(); buf.get(it) } }
    }

    /**
     * Same decoding as LiteRtVisionBackend: score >= 0.35, class-aware NMS 0.45. YOLO11n has
     * decoded pixel corners plus class_idx; YOLO-World has anchor distances and 80 class scores.
     */
    private fun decode(interpreter: Interpreter, raw: List<ByteArray>): List<Detection> {
        val tensors = (0 until interpreter.outputTensorCount).map(interpreter::getOutputTensor)
        fun values(index: Int): FloatArray {
            val tensor = tensors[index]
            if (tensor.dataType() == DataType.FLOAT32) {
                return FloatArray(raw[index].size / 4).also { ByteBuffer.wrap(raw[index]).order(ByteOrder.nativeOrder()).asFloatBuffer().get(it) }
            }
            val q = tensor.quantizationParams()
            return FloatArray(raw[index].size) { i ->
                val v = raw[index][i].toInt() and 0xFF
                if (q.scale == 0f) v.toFloat() else (v - q.zeroPoint) * q.scale
            }
        }
        val boxIndex = tensors.indexOfFirst { it.shape().last() == 4 }
        val classIndex = tensors.indexOfFirst { it.name().contains("class") }
        val scoreIndex = tensors.indices.first { it != boxIndex && it != classIndex }
        var boxes = values(boxIndex)
        val rawScores = values(scoreIndex)
        val count = boxes.size / 4
        val classes: IntArray
        val scores: FloatArray
        if (classIndex >= 0) {
            classes = values(classIndex).map { it.toInt() }.toIntArray()
            scores = rawScores
        } else {
            val k = rawScores.size / count
            classes = IntArray(count) { i -> (0 until k).maxBy { rawScores[i * k + it] } }
            scores = FloatArray(count) { i -> rawScores[i * k + classes[i]] }
            boxes = decodeAnchorDistances(boxes)
        }
        val candidates = scores.indices.filter { scores[it] >= 0.35f }
            .map { i -> Detection(classes[i], scores[i], FloatArray(4) { (boxes[4 * i + it] / 640f).coerceIn(0f, 1f) }) }
            .sortedByDescending { it.score }.toMutableList()
        val selected = mutableListOf<Detection>()
        while (candidates.isNotEmpty()) {
            val best = candidates.removeAt(0)
            selected += best
            candidates.removeAll { it.label == best.label && iou(it.box, best.box) >= 0.45f }
        }
        return selected
    }

    /** Box-major [count, 4] ltrb distances on the stride 8/16/32 grid -> pixel corners (if they are distances). */
    private fun decodeAnchorDistances(boxes: FloatArray): FloatArray {
        val max = boxes.maxOrNull() ?: return boxes
        if (max <= 1.5f || max > 16.5f || boxes.any { it < 0f }) return boxes
        val out = FloatArray(boxes.size)
        var anchor = 0
        for (stride in intArrayOf(8, 16, 32)) {
            for (row in 0 until 640 / stride) for (column in 0 until 640 / stride) {
                val b = 4 * anchor
                out[b] = (column + 0.5f - boxes[b]) * stride
                out[b + 1] = (row + 0.5f - boxes[b + 1]) * stride
                out[b + 2] = (column + 0.5f + boxes[b + 2]) * stride
                out[b + 3] = (row + 0.5f + boxes[b + 3]) * stride
                anchor++
            }
        }
        return out
    }

    /** Recall and precision of [actual] against [expected] (same label, IoU >= 0.5). */
    private fun agreement(actual: List<List<Detection>>, expected: List<List<Detection>>): Pair<Double, Double> {
        var matched = 0
        for ((a, e) in actual.zip(expected)) {
            val used = mutableSetOf<Int>()
            for (d in e) {
                val j = a.indices.filter { it !in used && a[it].label == d.label }.maxByOrNull { iou(a[it].box, d.box) }
                if (j != null && iou(a[j].box, d.box) >= 0.5f) {
                    used += j
                    matched++
                }
            }
        }
        val expectedCount = expected.sumOf { it.size }.coerceAtLeast(1)
        val actualCount = actual.sumOf { it.size }.coerceAtLeast(1)
        return matched.toDouble() / expectedCount to matched.toDouble() / actualCount
    }

    private fun iou(a: FloatArray, b: FloatArray): Float {
        val w = max(0f, min(a[2], b[2]) - max(a[0], b[0]))
        val h = max(0f, min(a[3], b[3]) - max(a[1], b[1]))
        val inter = w * h
        val union = (a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - inter
        return if (union > 0f) inter / union else 0f
    }
}

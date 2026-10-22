package com.fersaiyan.cyanbridge.hil

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fersaiyan.cyanbridge.localmodels.engine.LiteRtNpuSupport
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Opt-in probe for the NPU runtime AAR (-PlocalLiteRtNpuAarPath, docs/NPU_ON_DEVICE.md) with
 * the NPU WeSpeaker ResNet34-LM (KaldiFbankNpu front end; float32[1, 32000] PCM in [-1, 1] ->
 * float32[1, 256]).
 *
 * - CPU: the plain `wespeaker_resnet34_lm_2s_npu.tflite` through LiteRT 2.x CompiledModel.
 *   Runs on any ABI, including the x86_64 AVD, and proves the AAR coexists with litert:1.4.2.
 * - NPU: the AOT `.tflite` compiled for this phone's SoC. Skipped unless
 *   LiteRtNpuSupport reports the runtime and SoC as available.
 *
 * Host embeddings come from fixture-npu (audio_speaker_export_wespeaker_npu.py); this
 * variant's embeddings differ from the CPU WeSpeaker export. Only scores and
 * latency are logged; embeddings stay on the device.
 *
 * Arguments:
 *   -e npu_speaker_fixture   <device dir with manifest.json and *.pcm16 (fixture-npu)>
 *   -e npu_speaker_cpu_model <device path to wespeaker_resnet34_lm_2s_npu.tflite>
 *   -e npu_speaker_npu_model <device path to the *_Qualcomm_<SoC>_apply_plugin.tflite>
 */
@RunWith(AndroidJUnit4::class)
class LiteRtNpuSpeakerTest {
    private val args = InstrumentationRegistry.getArguments()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun wespeakerNpu_compiledModelCpu() {
        val model = args.getString("npu_speaker_cpu_model").orEmpty()
        assumeTrue("pass -e npu_speaker_cpu_model and -e npu_speaker_fixture", model.isNotBlank() && fixtureArg().isNotBlank())
        run("cpu", model, CompiledModel.Options(Accelerator.CPU), Environment.create(), minParity = 0.995)
    }

    @Test
    fun wespeakerNpu_compiledModelNpu() {
        val model = args.getString("npu_speaker_npu_model").orEmpty()
        assumeTrue("pass -e npu_speaker_npu_model and -e npu_speaker_fixture", model.isNotBlank() && fixtureArg().isNotBlank())
        val npu = LiteRtNpuSupport.status(context)
        println("NPU_SPK status available=${npu.available} soc=${npu.socModel} reason=${npu.reason}")
        assumeTrue(npu.reason, npu.available)
        LiteRtNpuSupport.compiledFor(model)?.let { assumeTrue("model is for $it, phone is ${npu.socModel}", it == npu.socModel) }
        val env = Environment.create(mapOf(Environment.Option.DispatchLibraryDir to context.applicationInfo.nativeLibraryDir))
        // fp16 HTP numerics: parity vs the host fp32 run of the same graph, not bit-exact.
        run("npu", model, CompiledModel.Options(Accelerator.NPU), env, minParity = 0.99)
    }

    private fun fixtureArg() = args.getString("npu_speaker_fixture").orEmpty()

    private fun run(label: String, modelPath: String, options: CompiledModel.Options, env: Environment, minParity: Double) {
        val modelFile = File(modelPath)
        val fixtureDir = File(fixtureArg())
        assertTrue("model missing: $modelPath", modelFile.isFile)
        assertTrue("fixture missing: $fixtureDir", File(fixtureDir, "manifest.json").isFile)
        val manifest = JSONObject(File(fixtureDir, "manifest.json").readText())
        val samples = manifest.getInt("samples")
        val clips = manifest.getJSONArray("clips")

        val loadStart = System.nanoTime()
        val model = CompiledModel.create(modelFile.absolutePath, options, env)
        val loadMs = (System.nanoTime() - loadStart) / 1e6
        val inputs = model.createInputBuffers()
        val outputs = model.createOutputBuffers()
        try {
            println("NPU_SPK load backend=$label model=${modelFile.name} bytes=${modelFile.length()} loadMs=$loadMs")

            fun embed(pcm: ShortArray): FloatArray {
                inputs[0].writeFloat(FloatArray(samples) { pcm[it] / 32767f })
                model.run(inputs, outputs)
                return outputs[0].readFloat()
            }

            val embeddings = LinkedHashMap<String, FloatArray>()
            val roles = HashMap<String, String>()
            val speakers = HashMap<String, Int>()
            var worstParity = 1.0
            for (i in 0 until clips.length()) {
                val c = clips.getJSONObject(i)
                val name = c.getString("name")
                val e = embed(readPcm16(File(fixtureDir, c.getString("file")), samples))
                val host = c.getJSONArray("host_embedding").let { a -> FloatArray(a.length()) { a.getDouble(it).toFloat() } }
                assertTrue("non-finite embedding for $name", e.all { it.isFinite() })
                worstParity = minOf(worstParity, cosine(e, host))
                embeddings[name] = e
                roles[name] = c.getString("role")
                speakers[name] = c.getInt("speaker")
            }

            val probe = readPcm16(File(fixtureDir, clips.getJSONObject(0).getString("file")), samples)
            repeat(3) { embed(probe) }
            val times = (0 until 20).map {
                val t0 = System.nanoTime()
                embed(probe)
                (System.nanoTime() - t0) / 1e6
            }.sorted()
            println("NPU_SPK latency backend=$label medianMs=${times[times.size / 2]} p90Ms=${times[17]} minMs=${times.first()}")

            val spkIds = manifest.getJSONArray("speakers").let { a -> (0 until a.length()).map { a.getInt(it) } }
            var minTarget = 1.0
            var maxOther = -1.0
            for (spk in spkIds) {
                val enroll = embeddings.filterKeys { roles[it] == "enroll" && speakers[it] == spk }.values
                val template = normalize(enroll.map { normalize(it) }.reduce { acc, v -> FloatArray(acc.size) { acc[it] + v[it] } })
                for ((name, e) in embeddings) {
                    if (roles[name] == "enroll") continue
                    val s = cosine(e, template)
                    if (speakers[name] == spk && roles[name] == "target") minTarget = minOf(minTarget, s)
                    if (speakers[name] != spk) maxOther = maxOf(maxOther, s)
                }
            }
            println("NPU_SPK summary backend=$label minParityCos=$worstParity minTargetCos=$minTarget maxOtherCos=$maxOther")
            assertTrue("$label output diverges from host (min cos $worstParity)", worstParity >= minParity)
            assertTrue("clean target scores should exceed other-speaker/noise scores", minTarget > maxOther)
        } finally {
            inputs.forEach { it.close() }
            outputs.forEach { it.close() }
            model.close()
            env.close()
        }
    }

    private fun readPcm16(file: File, samples: Int): ShortArray {
        val bytes = file.readBytes()
        require(bytes.size == 2 * samples) { "${file.name}: expected ${2 * samples} bytes, got ${bytes.size}" }
        val sb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return ShortArray(samples).also { sb.get(it) }
    }

    private fun normalize(v: FloatArray): FloatArray {
        val n = sqrt(v.fold(0.0) { a, x -> a + x * x }).toFloat().coerceAtLeast(1e-12f)
        return FloatArray(v.size) { v[it] / n }
    }

    private fun cosine(a: FloatArray, b: FloatArray): Double {
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        return dot / (sqrt(na) * sqrt(nb) + 1e-12)
    }
}

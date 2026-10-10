package com.fersaiyan.cyanbridge.hil

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.tensorflow.lite.Interpreter
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Opt-in on-device probe for the dedicated speaker-verification model chosen in
 * docs/AUDIO_SPEAKER_FILTER.md: SpeechBrain ECAPA-TDNN exported to LiteRT with the
 * filterbank front-end inside the graph (input float32[1, 32000] = 2 s of 16 kHz mono PCM in
 * [-1, 1], output float32[1, 192] raw embedding).
 *
 * The model and a public-data fixture (LibriSpeech/MUSAN clips + host expected embeddings,
 * produced by tools/benchmarks/audio_speaker_make_fixture.py) are pushed by the host. The test
 * is skipped unless both instrumentation arguments are given. It only logs scores and
 * latency; embeddings stay on the device (speaker templates are biometric data).
 *
 * Arguments:
 *   -e audio_speaker_model   <device path to ecapa_spkrec_voxceleb_2s.tflite>
 *   -e audio_speaker_fixture <device dir with manifest.json and *.pcm16>
 *   -e audio_speaker_threads <CPU threads, default 4>
 */
@RunWith(AndroidJUnit4::class)
class AudioSpeakerLiteRtProbeTest {
    private val args = InstrumentationRegistry.getArguments()

    @Test
    fun ecapaLiteRt_parityLatencyAndScores_onCpu() {
        val modelPath = args.getString("audio_speaker_model").orEmpty()
        val fixturePath = args.getString("audio_speaker_fixture").orEmpty()
        assumeTrue("pass -e audio_speaker_model and -e audio_speaker_fixture", modelPath.isNotBlank() && fixturePath.isNotBlank())
        val modelFile = File(modelPath)
        val fixtureDir = File(fixturePath)
        assertTrue("model missing: $modelPath", modelFile.isFile)
        assertTrue("fixture missing: $fixturePath", File(fixtureDir, "manifest.json").isFile)
        val threads = args.getString("audio_speaker_threads")?.toIntOrNull() ?: 4

        val manifest = JSONObject(File(fixtureDir, "manifest.json").readText())
        val samples = manifest.getInt("samples")
        val clips = manifest.getJSONArray("clips")

        val loadStart = System.nanoTime()
        val interpreter = Interpreter(modelFile, Interpreter.Options().setNumThreads(threads))
        val loadMs = (System.nanoTime() - loadStart) / 1e6
        try {
            assertEquals(listOf(1, samples), interpreter.getInputTensor(0).shape().toList())
            val dim = interpreter.getOutputTensor(0).shape().last()
            println("AUDIO_SPK load model=${modelFile.name} bytes=${modelFile.length()} threads=$threads loadMs=$loadMs dim=$dim")

            val input = ByteBuffer.allocateDirect(4 * samples).order(ByteOrder.nativeOrder())
            val output = Array(1) { FloatArray(dim) }

            fun embed(pcm: ShortArray): FloatArray {
                input.rewind()
                for (s in pcm) input.putFloat(s / 32767f)
                input.rewind()
                interpreter.run(input, output)
                return output[0].copyOf()
            }

            val embeddings = LinkedHashMap<String, FloatArray>()
            val roles = HashMap<String, String>()
            val speakers = HashMap<String, Int>()
            var minParity = 1.0
            for (i in 0 until clips.length()) {
                val c = clips.getJSONObject(i)
                val name = c.getString("name")
                val pcm = readPcm16(File(fixtureDir, c.getString("file")), samples)
                val e = embed(pcm)
                val host = c.getJSONArray("host_embedding").let { a -> FloatArray(a.length()) { a.getDouble(it).toFloat() } }
                val parity = cosine(e, host)
                minParity = minOf(minParity, parity)
                assertTrue("non-finite embedding for $name", e.all { it.isFinite() })
                embeddings[name] = e
                roles[name] = c.getString("role")
                speakers[name] = c.getInt("speaker")
                println("AUDIO_SPK clip=$name role=${roles[name]} parityCos=${"%.6f".format(parity)}")
            }

            // Latency: 3 warm-up + 10 timed runs on one clip.
            val probe = readPcm16(File(fixtureDir, clips.getJSONObject(0).getString("file")), samples)
            repeat(3) { embed(probe) }
            val times = (0 until 10).map {
                val t0 = System.nanoTime()
                embed(probe)
                (System.nanoTime() - t0) / 1e6
            }.sorted()
            println("AUDIO_SPK latency segmentS=${samples / 16000.0} medianMs=${times[times.size / 2]} p90Ms=${times[8]} minMs=${times.first()}")

            // Enrollment templates and scores (no thresholds are tuned here; scores only).
            val spkIds = manifest.getJSONArray("speakers").let { a -> (0 until a.length()).map { a.getInt(it) } }
            var minTarget = 1.0
            var maxOther = -1.0
            for (spk in spkIds) {
                val enroll = embeddings.filterKeys { roles[it] == "enroll" && speakers[it] == spk }.values
                val template = normalize(enroll.map { normalize(it) }.reduce { acc, v -> FloatArray(acc.size) { acc[it] + v[it] } })
                for ((name, e) in embeddings) {
                    if (roles[name] == "enroll") continue
                    val s = cosine(e, template)
                    val isTarget = speakers[name] == spk && roles[name] == "target"
                    if (isTarget) minTarget = minOf(minTarget, s)
                    if (speakers[name] != spk) maxOther = maxOf(maxOther, s)
                    println("AUDIO_SPK score template=$spk clip=$name role=${roles[name]} cos=${"%.3f".format(s)}")
                }
            }
            println("AUDIO_SPK summary minParityCos=$minParity minTargetCos=$minTarget maxOtherCos=$maxOther")
            assertTrue("on-device output diverges from host LiteRT (min cos $minParity)", minParity >= 0.995)
            assertTrue("clean target scores should exceed other-speaker/noise scores", minTarget > maxOther)
        } finally {
            interpreter.close()
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

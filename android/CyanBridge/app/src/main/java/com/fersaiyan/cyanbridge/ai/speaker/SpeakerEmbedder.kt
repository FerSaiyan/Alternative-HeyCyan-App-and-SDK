package com.fersaiyan.cyanbridge.ai.speaker

import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/** Maps exactly one [SpeakerModelSpec.windowSamples] window of PCM in [-1, 1] to a raw embedding. */
interface SpeakerEmbedder : Closeable {
    val spec: SpeakerModelSpec
    fun embed(window: FloatArray): FloatArray
}

/**
 * CPU [Interpreter] runner, the same path as AudioSpeakerLiteRtProbeTest (about 230 ms per 2 s
 * window on the Pixel 9a AVD). Not thread-safe; callers serialize access.
 */
class LiteRtSpeakerEmbedder(
    override val spec: SpeakerModelSpec,
    modelFile: File,
    threads: Int = 4,
) : SpeakerEmbedder {
    private val interpreter: Interpreter
    private val input = ByteBuffer.allocateDirect(spec.windowSamples * 4).order(ByteOrder.nativeOrder())
    private val output = Array(1) { FloatArray(spec.dims) }

    init {
        val buffer = FileInputStream(modelFile).channel.use { it.map(FileChannel.MapMode.READ_ONLY, 0, it.size()) }
        interpreter = Interpreter(buffer, Interpreter.Options().setNumThreads(threads))
        val inShape = interpreter.getInputTensor(0).shape()
        val outShape = interpreter.getOutputTensor(0).shape()
        check(inShape.contentEquals(intArrayOf(1, spec.windowSamples))) {
            interpreter.close()
            "${spec.id} input ${inShape.contentToString()} != [1, ${spec.windowSamples}]"
        }
        check(outShape.contentEquals(intArrayOf(1, spec.dims))) {
            interpreter.close()
            "${spec.id} output ${outShape.contentToString()} != [1, ${spec.dims}]"
        }
    }

    override fun embed(window: FloatArray): FloatArray {
        require(window.size == spec.windowSamples) { "Window has ${window.size} samples, expected ${spec.windowSamples}" }
        input.rewind()
        input.asFloatBuffer().put(window)
        interpreter.run(input, output)
        return output[0].copyOf()
    }

    override fun close() = interpreter.close()
}

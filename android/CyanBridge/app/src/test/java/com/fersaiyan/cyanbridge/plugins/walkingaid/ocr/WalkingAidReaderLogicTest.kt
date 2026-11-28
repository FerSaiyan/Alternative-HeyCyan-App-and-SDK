package com.fersaiyan.cyanbridge.plugins.walkingaid.ocr

import com.fersaiyan.cyanbridge.localmodels.engine.NpuArtifacts
import com.fersaiyan.cyanbridge.localmodels.engine.NpuDType
import com.fersaiyan.cyanbridge.localmodels.engine.NpuFloatRunner
import com.fersaiyan.cyanbridge.localmodels.engine.NpuTensor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The JVM-testable parts of the Walking Aid ride/bus reader and the NPU float view (OcrText/DbPostprocess tests: shared commonTest). */
class WalkingAidReaderLogicTest {
    @Test fun goalParsing() {
        assertEquals(WalkingAidGoal.Ride("ABC1D23"), WalkingAidGoalParser.parseKeywords("meu uber é placa ABC 1D23"))
        assertEquals(WalkingAidGoal.Bus("175"), WalkingAidGoalParser.parseKeywords("estou esperando o ônibus 175"))
        assertEquals(WalkingAidGoal.Describe, WalkingAidGoalParser.parseKeywords("just tell me what's around"))
        assertEquals(WalkingAidGoal.Ride(null), WalkingAidGoalParser.parseModelReply("RIDE NONE"))
        assertEquals(WalkingAidGoal.Bus("Campina do Siqueira"), WalkingAidGoalParser.parseModelReply("BUS Campina do Siqueira\n"))
        assertNull(WalkingAidGoalParser.parseModelReply("I think they want a bus"))
    }

    @Test fun npuFloatViewRoundTripsQuantizedTensors() {
        val int8 = NpuArtifacts.TensorSpec("image", intArrayOf(1, 2), NpuDType.INT8, scale = 1f / 255f, zeroPoint = -128)
        val q = NpuFloatRunner.quantize(floatArrayOf(0f, 1f), int8) as NpuTensor.Bytes
        assertEquals(listOf<Byte>(-128, 127), q.values.toList())
        val back = NpuFloatRunner.dequantize(q, int8)
        assertEquals(0f, back[0], 1e-6f); assertEquals(1f, back[1], 1e-6f)
        val uint8 = int8.copy(dtype = NpuDType.UINT8, zeroPoint = 0)
        assertEquals(1f, NpuFloatRunner.dequantize(NpuTensor.Bytes(byteArrayOf(-1)), uint8)[0], 1e-6f) // 0xFF
        val int16 = NpuArtifacts.TensorSpec("pcm", intArrayOf(1), NpuDType.INT16, scale = 3.01e-5f)
        assertEquals(0.5f, NpuFloatRunner.dequantize(NpuFloatRunner.quantize(floatArrayOf(0.5f), int16), int16)[0], 1e-4f)
    }
}

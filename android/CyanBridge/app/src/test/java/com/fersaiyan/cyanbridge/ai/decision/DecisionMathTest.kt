package com.fersaiyan.cyanbridge.ai.decision

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DecisionMathTest {
    @Test fun `softmax sums to one`() {
        val probs = DecisionMath.softmax(floatArrayOf(2.4f, -0.7f, 8.9f, 0.4f))
        println("JEV_CI softmax probs=${probs.joinToString(",")}")
        assertEquals(1f, probs.sum(), 1e-5f)
        assertTrue(probs[2] > 0.99f)
    }

    @Test fun `temperature sharpens distribution`() {
        val cool = DecisionMath.softmax(floatArrayOf(2f, 1f), temperature = 0.5f)
        val hot = DecisionMath.softmax(floatArrayOf(2f, 1f), temperature = 2f)
        println("JEV_CI cool=${cool.joinToString(",")} hot=${hot.joinToString(",")}")
        assertTrue(cool[0] > hot[0])
    }

    @Test fun `pseudo probabilities peak at winner`() {
        val probs = DecisionMath.pseudoProbabilities(2, 4)
        println("JEV_CI pseudo=${probs.joinToString(",")}")
        assertEquals(1f, probs.sum(), 1e-5f)
        assertTrue(probs[2] > probs[0] && probs[2] > probs[1] && probs[2] > probs[3])
    }

    @Test fun `pseudo probabilities custom winner`() {
        val probs = DecisionMath.pseudoProbabilities(0, 3)
        assertArrayEquals(floatArrayOf(probs[0]), floatArrayOf(probs.maxOrNull()!!), 0f)
    }
}

package com.fersaiyan.cyanbridge.localmodels.benchmark

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalMtpBenchmarkPolicyTest {
    @Test
    fun recommendsMtpWhenDecodeImprovesWithoutLargeTtftPenalty() {
        assertTrue(
            LocalMtpBenchmarkPolicy.recommend(
                mtpOffDecodeTokensPerSecond = 10.0,
                mtpOnDecodeTokensPerSecond = 15.0,
                mtpOffTimeToFirstTokenMs = 500,
                mtpOnTimeToFirstTokenMs = 590,
            ),
        )
    }

    @Test
    fun rejectsMtpWhenDecodeDoesNotImproveEnough() {
        assertFalse(
            LocalMtpBenchmarkPolicy.recommend(
                mtpOffDecodeTokensPerSecond = 10.0,
                mtpOnDecodeTokensPerSecond = 10.3,
                mtpOffTimeToFirstTokenMs = 500,
                mtpOnTimeToFirstTokenMs = 500,
            ),
        )
    }

    @Test
    fun rejectsMtpWhenFirstTokenPenaltyIsTooLarge() {
        assertFalse(
            LocalMtpBenchmarkPolicy.recommend(
                mtpOffDecodeTokensPerSecond = 10.0,
                mtpOnDecodeTokensPerSecond = 16.0,
                mtpOffTimeToFirstTokenMs = 500,
                mtpOnTimeToFirstTokenMs = 900,
            ),
        )
    }
}

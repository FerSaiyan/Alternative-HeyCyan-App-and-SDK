package com.fersaiyan.cyanbridge.localmodels.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class LocalMtpSettingsRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun clearPreferences() {
        context.getSharedPreferences("local_model_mtp_settings", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun timeoutRecordKeepsBaselineAndDisablesAutomaticMtp() {
        LocalMtpSettingsRepository.saveBenchmark(
            context = context,
            modelId = "gemma-test",
            backend = LocalComputeBackend.GPU,
            modelSignature = "signature",
            record = LocalMtpBenchmarkRecord(
                mtpOffOutputTokensPerSecond = 11.5,
                mtpOnOutputTokensPerSecond = null,
                mtpOffTimeToFirstTokenMs = 480,
                mtpOnTimeToFirstTokenMs = null,
                recommendMtp = false,
                mtpOnFailure = "MTP on initialization timed out after 60 seconds.",
            ),
        )

        val record = LocalMtpSettingsRepository.getBenchmark(
            context,
            "gemma-test",
            LocalComputeBackend.GPU,
            "signature",
        )
        assertEquals(11.5, record?.mtpOffOutputTokensPerSecond ?: 0.0, 0.0)
        assertNull(record?.mtpOnOutputTokensPerSecond)
        assertFalse(record?.recommendMtp ?: true)
        assertEquals(
            "MTP on initialization timed out after 60 seconds.",
            record?.mtpOnFailure,
        )
    }

    @Test
    fun completedRecordRoundTripsBothMeasurements() {
        LocalMtpSettingsRepository.saveBenchmark(
            context = context,
            modelId = "gemma-complete",
            backend = LocalComputeBackend.GPU,
            modelSignature = "signature",
            record = LocalMtpBenchmarkRecord(
                mtpOffOutputTokensPerSecond = 10.0,
                mtpOnOutputTokensPerSecond = 14.0,
                mtpOffTimeToFirstTokenMs = 500,
                mtpOnTimeToFirstTokenMs = 550,
                recommendMtp = true,
            ),
        )

        val record = LocalMtpSettingsRepository.getBenchmark(
            context,
            "gemma-complete",
            LocalComputeBackend.GPU,
            "signature",
        )
        assertEquals(14.0, record?.mtpOnOutputTokensPerSecond ?: 0.0, 0.0)
        assertEquals(550L, record?.mtpOnTimeToFirstTokenMs)
        assertEquals(true, record?.recommendMtp)
        assertNull(record?.mtpOnFailure)
    }
}

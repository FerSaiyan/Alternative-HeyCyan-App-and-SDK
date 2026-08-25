package com.fersaiyan.cyanbridge.localmodels.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalMtpResolverTest {
    @Test
    fun unsupportedModelNeverEnablesMtp() {
        assertFalse(LocalMtpResolver.resolve(LocalMtpMode.AUTO, supported = false, cachedRecommendation = true))
        assertFalse(LocalMtpResolver.resolve(LocalMtpMode.ON, supported = false, cachedRecommendation = null))
    }

    @Test
    fun automaticEnablesCompatibleModelBeforeBenchmark() {
        assertTrue(LocalMtpResolver.resolve(LocalMtpMode.AUTO, supported = true, cachedRecommendation = null))
    }

    @Test
    fun automaticUsesCachedBenchmarkRecommendation() {
        assertFalse(LocalMtpResolver.resolve(LocalMtpMode.AUTO, supported = true, cachedRecommendation = false))
        assertTrue(LocalMtpResolver.resolve(LocalMtpMode.AUTO, supported = true, cachedRecommendation = true))
    }

    @Test
    fun explicitModesOverrideBenchmarkRecommendation() {
        assertTrue(LocalMtpResolver.resolve(LocalMtpMode.ON, supported = true, cachedRecommendation = false))
        assertFalse(LocalMtpResolver.resolve(LocalMtpMode.OFF, supported = true, cachedRecommendation = true))
    }
}

package com.fersaiyan.cyanbridge.shared.localmodels

import com.fersaiyan.cyanbridge.localmodels.benchmark.LocalModelBenchmarkMeasurement
import com.fersaiyan.cyanbridge.localmodels.benchmark.LocalModelBenchmarkProgress
import com.fersaiyan.cyanbridge.localmodels.benchmark.LocalModelBenchmarkResult
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class LocalModelTestViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun missingModelCannotStart() = runTest(dispatcher) {
        val vm = LocalModelTestViewModel(FakeRunner(target = null))
        vm.start()
        assertEquals("Unable to start", vm.state.value.phase)
        assertEquals("Select an installed model before running the test.", vm.state.value.error)
    }

    @Test
    fun liteRtComparisonEndsWithARecommendation() = runTest(dispatcher) {
        val runner = FakeRunner(LocalModelTestTarget("Gemma", "LiteRT", "GPU", liteRt = true))
        val vm = LocalModelTestViewModel(runner)
        vm.start()

        val state = vm.state.value
        assertEquals("Test complete", state.phase)
        assertEquals("Automatic will enable MTP for this model and backend on this device.", state.recommendation)
        assertEquals("MTP recommended (+50.0% decode speed)", runner.summary)
    }
}

private fun measurement(mtp: Boolean, decode: Double) = LocalModelBenchmarkMeasurement(mtp, 10, 20, 30, 40, 50.0, decode)

private class FakeRunner(private val target: LocalModelTestTarget?) : LocalModelTestRunner {
    var summary = ""
    override fun target() = target
    override suspend fun releaseActiveModel() = Unit
    override suspend fun runComparison(onProgress: (LocalModelBenchmarkProgress) -> Unit) {
        onProgress(LocalModelBenchmarkProgress.CapabilityReady(mtpSupported = true))
        onProgress(
            LocalModelBenchmarkProgress.Finished(
                LocalModelBenchmarkResult(true, measurement(false, 10.0), measurement(true, 15.0), recommendMtp = true),
            ),
        )
    }

    override suspend fun runBaseline() = LocalModelBaseline(10, 1000, null)
    override fun saveSummary(summary: String) {
        this.summary = summary
    }
}

package com.fersaiyan.cyanbridge.plugins.walkingaid

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.fersaiyan.cyanbridge.localmodels.settings.LocalComputeBackend
import com.fersaiyan.cyanbridge.plugins.walkingaid.vision.LiteRtVisionBackend
import com.fersaiyan.cyanbridge.plugins.walkingaid.vision.VisionFrame
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Runs the production Walking Aid LiteRT stack against real JPEG camera scenes on Android. */
@LargeTest
@RunWith(AndroidJUnit4::class)
class WalkingAidVisionStackIntegrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun realModel_runYolo11OnGlassesLikeJpegs() = runRealModelTest("yolo11") {
        val fixtures = FIXTURES.map(::loadFixture)
        try {
            verifyInstalledModel(WalkingAidModelCatalog.detectorFor(WalkingAidPreferences.MODEL_TYPE_YOLO11))
            runDetector(WalkingAidPreferences.MODEL_TYPE_YOLO11, "YOLO11", fixtures)
        } finally {
            fixtures.forEach { it.bitmap.recycle() }
        }
    }

    @Test
    fun realModel_runYoloWorldOnGlassesLikeJpegs() = runRealModelTest("yolo-world") {
        val fixtures = FIXTURES.map(::loadFixture)
        try {
            verifyInstalledModel(WalkingAidModelCatalog.detectorFor(WalkingAidPreferences.MODEL_TYPE_YOLO_WORLD))
            runDetector(WalkingAidPreferences.MODEL_TYPE_YOLO_WORLD, "YOLO-World", fixtures)
        } finally {
            fixtures.forEach { it.bitmap.recycle() }
        }
    }

    @Test
    fun realModel_runDepthAnythingOnGlassesLikeJpegs() = runRealModelTest("depth-anything") {
        val fixtures = FIXTURES.map(::loadFixture)
        try {
            verifyInstalledModel(WalkingAidModelCatalog.depth)
            runDepthAnything(fixtures)
        } finally {
            fixtures.forEach { it.bitmap.recycle() }
        }
    }

    private fun runRealModelTest(phaseName: String, block: suspend () -> Unit) = runBlocking {
        val enabled = InstrumentationRegistry.getArguments()
            .getString(ARG_RUN_REAL_MODELS)
            ?.toBooleanStrictOrNull() == true
        assumeTrue(
            "Run through tools/hil/run_walking_aid_model_ci.sh or opt in with $ARG_RUN_REAL_MODELS=true",
            enabled,
        )

        val resultDirectory = File(context.filesDir, "$FIXTURE_DIRECTORY/results").apply { mkdirs() }
        val successMarker = File(resultDirectory, "$phaseName.success")
        val failureMarker = File(resultDirectory, "$phaseName.failure")
        successMarker.delete()
        failureMarker.delete()
        val previousDetector = WalkingAidPreferences.getYoloModelType(context)
        try {
            block()
            successMarker.writeText("PASS\n")
        } catch (error: Throwable) {
            val trace = StringWriter().also { writer ->
                error.printStackTrace(PrintWriter(writer))
            }.toString()
            failureMarker.writeText(trace)
            throw error
        } finally {
            WalkingAidPreferences.setYoloModelType(context, previousDetector)
        }
    }

    private suspend fun runDetector(type: String, displayName: String, fixtures: List<LoadedFixture>) {
        WalkingAidPreferences.setYoloModelType(context, type)
        val backend = LiteRtVisionBackend(context, LocalComputeBackend.CPU)
        try {
            assertTrue(
                "$displayName failed to load: ${backend.detectorInitError}",
                backend.isDetectorModelLoaded,
            )
            fixtures.forEach { fixture ->
                val detection = backend.detect(fixture.frame())
                assertFalse("$displayName failed on ${fixture.spec.fileName}: ${detection.errorMessage}", detection.isError)
                assertTrue(
                    "$displayName returned no objects for ${fixture.spec.fileName}",
                    detection.objects.isNotEmpty(),
                )
                assertTrue(
                    "$displayName returned invalid boxes for ${fixture.spec.fileName}: ${detection.objects}",
                    detection.objects.all { detected ->
                        detected.boundingBox.left in 0f..1f &&
                            detected.boundingBox.top in 0f..1f &&
                            detected.boundingBox.right in 0f..1f &&
                            detected.boundingBox.bottom in 0f..1f &&
                            detected.boundingBox.width() > 0f &&
                            detected.boundingBox.height() > 0f &&
                            detected.confidence in 0f..1f
                    },
                )
                val labels = detection.objects.map { it.label }
                assertTrue(
                    "$displayName did not detect any expected class in ${fixture.spec.fileName}; labels=$labels",
                    labels.any(fixture.spec.expectedLabels::contains),
                )
                Log.i(
                    TAG,
                    "$displayName ${fixture.spec.fileName}: labels=$labels inferenceMs=${detection.inferenceTimeMs} " +
                        "accelerator=${backend.acceleratorInfo().type}",
                )
            }
        } finally {
            backend.close()
        }
    }

    private suspend fun runDepthAnything(fixtures: List<LoadedFixture>) {
        WalkingAidPreferences.setYoloModelType(context, WalkingAidPreferences.MODEL_TYPE_YOLO11)
        val backend = LiteRtVisionBackend(context, LocalComputeBackend.CPU)
        try {
            assertTrue(
                "Depth Anything failed to load: ${backend.depthInitError}",
                backend.isDepthModelLoaded,
            )
            fixtures.forEach { fixture ->
                val depth = backend.estimateDepth(fixture.frame())
                assertNotNull("Depth Anything returned no result for ${fixture.spec.fileName}", depth)
                assertTrue(depth!!.closestRegion in setOf("left", "center", "right"))
                assertTrue("Depth summary was empty for ${fixture.spec.fileName}", depth.relativeDepthSummary.isNotBlank())
                assertTrue("Depth inference time was invalid", depth.inferenceTimeMs >= 0L)
                Log.i(
                    TAG,
                    "Depth Anything ${fixture.spec.fileName}: region=${depth.closestRegion} " +
                        "discontinuity=${depth.groundDiscontinuityDetected} inferenceMs=${depth.inferenceTimeMs}",
                )
            }
        } finally {
            backend.close()
        }
    }

    private fun loadFixture(spec: FixtureSpec): LoadedFixture {
        val file = File(context.filesDir, "$FIXTURE_DIRECTORY/${spec.fileName}")
        check(file.isFile) { "Missing staged Walking Aid fixture ${file.absolutePath}" }
        check(file.length() == spec.sizeBytes) {
            "Fixture ${spec.fileName} has size ${file.length()}, expected ${spec.sizeBytes}"
        }
        check(sha256(file) == spec.sha256) { "Fixture ${spec.fileName} failed SHA-256 verification" }
        // Production receives a complete JPEG file from the glasses and decodes that file.
        val bitmap = checkNotNull(BitmapFactory.decodeFile(file.absolutePath)) {
            "Could not decode glasses-like JPEG fixture ${file.absolutePath}"
        }
        check(bitmap.width >= 640 && bitmap.height >= 480) {
            "Fixture ${spec.fileName} is unexpectedly small: ${bitmap.width}x${bitmap.height}"
        }
        return LoadedFixture(spec, bitmap, file.absolutePath)
    }

    private fun verifyInstalledModel(entry: WalkingAidModelCatalogEntry) {
        val file = WalkingAidModelInstaller.installedFile(context, entry)
        check(file.isFile) { "Missing staged model ${entry.displayName}: ${file.absolutePath}" }
        check(file.length() == entry.sizeBytes) {
            "${entry.displayName} has size ${file.length()}, expected ${entry.sizeBytes}"
        }
        check(sha256(file) == entry.sha256) { "${entry.displayName} failed SHA-256 verification" }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count <= 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private data class LoadedFixture(
        val spec: FixtureSpec,
        val bitmap: Bitmap,
        val sourcePath: String,
    ) {
        fun frame(): VisionFrame {
            val now = System.currentTimeMillis()
            return VisionFrame(
                bitmap = bitmap,
                timestampMs = now,
                captureCommandAtMs = now,
                estimatedExposureAtMs = now,
                receivedAtMs = now,
                sourcePath = sourcePath,
            )
        }
    }

    private data class FixtureSpec(
        val fileName: String,
        val sizeBytes: Long,
        val sha256: String,
        val expectedLabels: Set<String>,
    )

    companion object {
        private const val TAG = "WalkingAidModelCI"
        private const val ARG_RUN_REAL_MODELS = "walkingAidRealModels"
        private const val FIXTURE_DIRECTORY = "walking_aid_ci"
        private val FIXTURES = listOf(
            FixtureSpec(
                fileName = "bus.jpg",
                sizeBytes = 137_419L,
                sha256 = "c02019c4979c191eb739ddd944445ef408dad5679acab6fd520ef9d434bfbc63",
                expectedLabels = setOf("person", "bus"),
            ),
            FixtureSpec(
                fileName = "zidane.jpg",
                sizeBytes = 50_427L,
                sha256 = "16d73869e3267a7d4ed00de8e860833bd1657c1b252e94c0c348277adc7b6edb",
                expectedLabels = setOf("person", "tie"),
            ),
        )
    }
}

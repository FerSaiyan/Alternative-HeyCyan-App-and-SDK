package com.fersaiyan.cyanbridge.plugins.walkingaid

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.fersaiyan.cyanbridge.devices.eyevue.EyevueLiveConnection
import com.fersaiyan.cyanbridge.devices.eyevue.EyevueLivePreviewManager
import com.fersaiyan.cyanbridge.devices.metarayban.MetaDatPermissions
import com.fersaiyan.cyanbridge.devices.metarayban.MetaRaybanManager
import com.fersaiyan.cyanbridge.glasses.GlassesSessionCoordinator
import com.meta.wearable.dat.mockdevice.MockDeviceKit
import com.meta.wearable.dat.mockdevice.api.GlassesModel
import com.meta.wearable.dat.mockdevice.api.MockDeviceKitConfig
import com.fersaiyan.cyanbridge.localmodels.settings.LocalComputeBackend
import com.fersaiyan.cyanbridge.plugins.walkingaid.vision.LiteRtVisionBackend
import com.fersaiyan.cyanbridge.plugins.walkingaid.vision.VisionFrame
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.Rule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.runner.RunWith

/** Runs the production Walking Aid LiteRT stack against real JPEG camera scenes on Android. */
@LargeTest
@RunWith(AndroidJUnit4::class)
class WalkingAidVisionStackIntegrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    @get:Rule val permissions: GrantPermissionRule = GrantPermissionRule.grant(*MetaDatPermissions.required())

    @Test
    fun realModel_runYoloOnEyevueVideo() = runRealModelTest("eyevue-video") {
        val video = stageVideo("eyevue-scenes.mp4")
        var connectionClosed = false
        val connection = object : EyevueLiveConnection {
            override suspend fun open(onStatus: (String, String) -> Unit) = Uri.fromFile(video).toString()
            override suspend fun close() { connectionClosed = true }
        }
        // Only the BLE/Wi-Fi connection is substituted: production VLC output, rotation,
        // Walking Aid acquisition/queue, and real YOLO inference are exercised below.
        val capture = WalkingAidLiveVideoCapture(context) { EyevueLivePreviewManager(context, connection) }
        runVideoDetector(capture, WalkingAidVideoMode.EYEVUE_VIDEO)
        assertTrue("Eyevue transport must close on stop", connectionClosed)
        assertNull("Eyevue lease leaked", GlassesSessionCoordinator.currentSession())
    }

    @Test
    fun realModel_runYoloOnMetaDatVideo() = runRealModelTest("meta-video") {
        val kit = MockDeviceKit.getInstance(context)
        val manager = MetaRaybanManager.getInstance(context)
        try {
            val glasses = withContext(Dispatchers.Main) {
                kit.enable(MockDeviceKitConfig(initiallyRegistered = true, initialPermissionsGranted = true))
                val paired = kit.pairGlasses(GlassesModel.RAYBAN_META).fold(
                    onSuccess = { it }, onFailure = { error, _ -> throw AssertionError(error.description) },
                )
                paired.powerOn()
                paired.unfold()
                paired.don()
                manager.initialize()
                paired
            }
            assertFalse("Must use actual DAT, not UI mock", manager.isDebugMockEnabled())
            // DAT's mock transport negotiates HEVC; an AVC feed starts the stream
            // but its HEVC decoder produces no frames. Exercise the negotiated codec.
            glasses.services.camera.setCameraFeed(Uri.fromFile(stageVideo("meta-scenes.mp4")))
            runVideoDetector(WalkingAidLiveVideoCapture(context), WalkingAidVideoMode.META_VIDEO)
            assertFalse("Meta stream must stop", manager.isStreaming.value)
            assertEquals(MetaRaybanManager.DeviceSessionState.IDLE, manager.deviceSessionState.value)
            assertNull("Meta lease leaked", GlassesSessionCoordinator.currentSession())
        } finally {
            withContext(Dispatchers.Main) { manager.destroy(); kit.disable() }
        }
    }

    private fun stageVideo(name: String): File = File(context.cacheDir, "walking-$name").also { file ->
        InstrumentationRegistry.getInstrumentation().context.assets.open("walkingaid/$name").use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
    }

    private suspend fun runVideoDetector(capture: WalkingAidLiveVideoCapture, mode: WalkingAidVideoMode) = coroutineScope {
        verifyInstalledModel(WalkingAidModelCatalog.detectorFor(WalkingAidPreferences.MODEL_TYPE_YOLO11))
        WalkingAidPreferences.setYoloModelType(context, WalkingAidPreferences.MODEL_TYPE_YOLO11)
        val backend = LiteRtVisionBackend(context, LocalComputeBackend.CPU)
        val queue = WalkingAidFrameQueue()
        val captureJob = launch { capture.collect(mode, queue::offer) }
        try {
            assertTrue("YOLO load failed: ${backend.detectorInitError}", backend.isDetectorModelLoaded)
            var analyzed = 0
            var sawBus = false
            var sawPerson = false
            var sawTie = false
            withTimeout(60_000) {
                while (analyzed < 3 || !sawBus || !sawPerson || !sawTie) {
                    val frame = queue.frames.receive()
                    try {
                        val now = System.currentTimeMillis()
                        assertTrue("Live frame must be fresh", now - frame.timestampMs < 5_000)
                        if (mode == WalkingAidVideoMode.EYEVUE_VIDEO) {
                            assertEquals("Eyevue must be rotated upright", 480, frame.bitmap.width)
                            assertEquals(640, frame.bitmap.height)
                        }
                        val result = backend.detect(frame)
                        assertFalse("$mode YOLO error: ${result.errorMessage}", result.isError)
                        val labels = result.objects.map { it.label }
                        sawBus = sawBus || "bus" in labels
                        sawPerson = sawPerson || "person" in labels
                        sawTie = sawTie || "tie" in labels
                        analyzed++
                        Log.i(TAG, "$mode frame=${frame.captureIndex} labels=$labels inferenceMs=${result.inferenceTimeMs}")
                    } finally { frame.bitmap.recycle() }
                }
            }
        } finally {
            captureJob.cancelAndJoin()
            queue.clear()
            backend.close()
        }
    }

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
                    "$displayName did not detect expected classes ${fixture.spec.expectedLabels} in ${fixture.spec.fileName}; labels=$labels",
                    labels.containsAll(fixture.spec.expectedLabels),
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

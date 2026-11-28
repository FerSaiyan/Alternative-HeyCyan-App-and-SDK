package com.fersaiyan.cyanbridge.shared.litert

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LiteRtModelsTest {
    @Test
    fun catalogFilesArePinnedAndChecksummed() {
        val files = LiteRtModelCatalog.files
        assertTrue(files.isNotEmpty())
        for (f in files) {
            assertTrue(f.url.contains("/resolve/${LiteRtModelCatalog.REVISION}/"), f.url)
            assertTrue(f.url.endsWith(f.path), f.url)
            assertTrue(Regex("[0-9a-f]{64}").matches(f.sha256), f.sha256)
            assertTrue(f.bytes > 0)
            if (f.isModel) {
                assertTrue(f.inputs.isNotEmpty() && f.outputs.isNotEmpty(), f.path)
                assertTrue(f.inputs.all { it.dtype == "float32" }, "float I/O only: ${f.path}")
            }
        }
        assertEquals(files.size, files.map { it.localName }.toSet().size, "local names must be unique")
    }

    @Test
    fun iosSelectionHasOneModelPerKindAndTheSupportFiles() {
        val files = LiteRtModelSelection.iosFiles()
        for (kind in listOf(LiteRtModelKind.YOLO11N, LiteRtModelKind.OCR_DET, LiteRtModelKind.OCR_REC, LiteRtModelKind.WESPEAKER)) {
            assertEquals(1, files.count { it.kind == kind && it.isModel }, "$kind")
        }
        // Two YOLO files are listed; the fp16-weights one is preferred (half the download, same results).
        assertEquals("gpu/yolo11n_640_v4noargmax_fp16w.tflite", LiteRtModelSelection.iosModel(LiteRtModelKind.YOLO11N)?.path)
        assertNotNull(files.firstOrNull { it.kind == LiteRtModelKind.OCR_DICT })
        assertNotNull(files.firstOrNull { it.kind == LiteRtModelKind.OCR_SPEC })
        // The reader runs on the CPU on iOS (Metal output differs from the CPU, see the manifest note).
        assertEquals(LiteRtAccelerator.CPU, LiteRtModelSelection.iosModel(LiteRtModelKind.OCR_REC)?.ios?.accelerator)
        assertEquals(LiteRtAccelerator.GPU, LiteRtModelSelection.iosModel(LiteRtModelKind.YOLO11N)?.ios?.accelerator)
    }

    @Test
    fun syntheticInputIsDeterministicAndInRange() {
        val a = LiteRtSelfTest.syntheticInput(1000)
        val b = LiteRtSelfTest.syntheticInput(1000)
        assertTrue(a.contentEquals(b))
        assertTrue(a.all { it >= 0f && it < 1f })
        assertTrue(a.toSet().size > 900)
    }

    @Test
    fun selfTestReportsGpuAgreementFallbackAndDataFiles() = runTest {
        val yolo = LiteRtModelSelection.iosModel(LiteRtModelKind.YOLO11N)!!
        val rec = LiteRtModelSelection.iosModel(LiteRtModelKind.OCR_REC)!!
        val det = LiteRtModelSelection.iosModel(LiteRtModelKind.OCR_DET)!!
        val dict = LiteRtModelCatalog.files.first { it.kind == LiteRtModelKind.OCR_DICT }
        val installer = FakeInstaller()
        // det: the GPU open falls back to the CPU; yolo: GPU agrees; rec: CPU only.
        val runner = FakeRunner(fallBackFor = setOf(det.localName))
        val logged = mutableListOf<String>()
        val rows = LiteRtSelfTest(installer, runner, log = { logged += it }).run(listOf(yolo, rec, det, dict))

        val byPath = rows.associateBy { it.path }
        assertTrue(byPath.getValue(yolo.path).ok)
        assertEquals(LiteRtAccelerator.GPU, byPath.getValue(yolo.path).used)
        assertEquals(1.0, byPath.getValue(yolo.path).minCosVsCpu!!, 1e-9)
        assertTrue(byPath.getValue(rec.path).ok)
        assertEquals(LiteRtAccelerator.CPU, byPath.getValue(rec.path).used)
        assertNull(byPath.getValue(rec.path).acceleratedMs)
        assertFalse(byPath.getValue(det.path).ok)
        assertEquals("fell back to CPU", byPath.getValue(det.path).error)
        assertTrue(byPath.getValue(dict.path).ok)
        assertEquals(4, installer.installed.size)
        assertEquals(4, logged.size)
        assertTrue(logged.first().startsWith("OK "), logged.first())
    }

    @Test
    fun selfTestFlagsWrongOutputSizes() = runTest {
        val yolo = LiteRtModelSelection.iosModel(LiteRtModelKind.YOLO11N)!!
        val rows = LiteRtSelfTest(FakeInstaller(), FakeRunner(truncate = true)).run(listOf(yolo))
        assertFalse(rows.single().ok)
        assertTrue(rows.single().error!!.contains("expected"), rows.single().error)
    }

    @Test
    fun cosineEdgeCases() {
        assertEquals(1.0, LiteRtSelfTest.cosine(floatArrayOf(0f, 0f), floatArrayOf(0f, 0f)))
        assertEquals(0.0, LiteRtSelfTest.cosine(floatArrayOf(1f, 0f), floatArrayOf(0f, 0f)))
        assertEquals(-1.0, LiteRtSelfTest.cosine(floatArrayOf(1f, 2f), floatArrayOf(-1f, -2f)), 1e-9)
    }

    private class FakeInstaller : LiteRtModelInstaller {
        val installed = mutableSetOf<String>()
        override fun installedPath(file: LiteRtModelFile): String? = file.localName.takeIf { it in installed }
        override suspend fun install(file: LiteRtModelFile): String = file.localName.also { installed += it }
        override fun programCacheDir(): String = "/cache"
    }

    /** Outputs depend only on the model's output sizes, so CPU and GPU agree unless told otherwise. */
    private class FakeRunner(
        private val fallBackFor: Set<String> = emptySet(),
        private val truncate: Boolean = false,
    ) : LiteRtModelRunner {
        override val gpuAvailable = true

        override fun open(path: String, accelerator: LiteRtAccelerator, precision: LiteRtGpuPrecision?, cacheDir: String?): LiteRtSession {
            val file = LiteRtModelCatalog.files.first { it.localName == path }
            val used = if (path in fallBackFor) LiteRtAccelerator.CPU else accelerator
            return object : LiteRtSession {
                override val accelerator = used
                override fun run(input: FloatArray): List<FloatArray> = file.outputs.map { spec ->
                    FloatArray(if (truncate) spec.elements - 1 else spec.elements) { i -> ((i % 7) + 1) * 0.1f }
                }
                override fun close() {}
            }
        }
    }
}

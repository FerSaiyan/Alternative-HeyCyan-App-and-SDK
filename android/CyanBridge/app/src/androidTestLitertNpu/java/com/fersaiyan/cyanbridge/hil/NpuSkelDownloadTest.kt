package com.fersaiyan.cyanbridge.hil

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fersaiyan.cyanbridge.localmodels.engine.LiteRtNpuSupport
import com.fersaiyan.cyanbridge.localmodels.engine.QnnSkels
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The release path for Snapdragon NPU runtimes: a download-mode NPU AAR (`litert-npu-all-*-dl.aar`, skels not in the
 * APK) fetches this phone's Hexagon skel from the GitHub prerelease, and the DSP loads it through the per-vendor
 * dispatch directory's symlink. Needs network and a Snapdragon phone with a supported Hexagon (V68-V81).
 *
 *   adb shell am instrument -w -e class com.fersaiyan.cyanbridge.hil.NpuSkelDownloadTest \
 *     [-e npu_model <path to a *_Qualcomm_<SoC>_apply_plugin.tflite for this phone; /data/local/tmp is staged>] \
 *     com.fersaiyan.cyanbridge.test/androidx.test.runner.AndroidJUnitRunner
 *
 * Prints NPU_SKEL lines: status before/after, download time, and (with npu_model) whether the model opens and runs.
 */
@RunWith(AndroidJUnit4::class)
class NpuSkelDownloadTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val args = InstrumentationRegistry.getArguments()

    /**
     * Device Run pushes files to /data/local/tmp (the app cannot read it, and its own storage is cleared when the
     * instrumentation starts), so copy the model into the app's external files dir with the shell's permissions.
     */
    private fun stage(path: String): String {
        if (!path.startsWith("/data/local/tmp/")) return path
        val target = File(context.getExternalFilesDir(null), File(path).name)
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("cp $path ${target.path}")
            .use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use { stream -> stream.readBytes() } }
        assertTrue("could not stage $path", target.isFile)
        println("NPU_SKEL staged $path -> $target (${target.length()} B)")
        return target.path
    }

    @Test
    fun downloadsThisPhonesSkelAndTheNpuLoadsIt() {
        val before = LiteRtNpuSupport.status(context)
        println("NPU_SKEL before available=${before.available} downloadable=${before.skelDownloadable} vendor=${before.vendor} soc=${before.socModel} hexagon=${before.hexagon} reason=${before.reason}")
        assumeTrue("not a Snapdragon phone with a supported Hexagon NPU", before.vendor == LiteRtNpuSupport.Vendor.QUALCOMM && before.hexagon != null)
        assumeTrue("this build bundles the skels (use a download-mode NPU AAR)", QnnSkels.manifest(context) != null)

        // Start from a clean download directory, so the test really downloads.
        QnnSkels.dir(context).listFiles()?.forEach { it.delete() }
        val start = System.nanoTime()
        val skel = QnnSkels.ensure(context, before.hexagon!!)
        println("NPU_SKEL downloaded ${skel.name} ${skel.length()} B in ${(System.nanoTime() - start) / 1_000_000} ms")

        val after = LiteRtNpuSupport.status(context)
        println("NPU_SKEL after available=${after.available} reason=${after.reason}")
        assertTrue(after.reason, after.available)
        val dispatch = File(LiteRtNpuSupport.dispatchDir(context, after))
        val link = File(dispatch, skel.name)
        println("NPU_SKEL dispatchDir=$dispatch skelLink=${link.exists()} -> ${runCatching { android.system.Os.readlink(link.path) }.getOrNull()}")
        assertTrue("skel is not linked into the dispatch directory", link.exists())

        val model = stage(args.getString("npu_model").orEmpty())
        if (model.isBlank()) return
        LiteRtNpuSupport.compiledTarget(model)?.let {
            assumeTrue("model is for $it, phone is ${after.modelTag}", it.vendor == after.vendor && it.socModel == after.socModel)
        }
        val loadStart = System.nanoTime()
        val env = Environment.create(mapOf(Environment.Option.DispatchLibraryDir to dispatch.path))
        try {
            val compiled = CompiledModel.create(model, CompiledModel.Options(Accelerator.NPU), env)
            try {
                println("NPU_SKEL model loaded in ${(System.nanoTime() - loadStart) / 1_000_000} ms")
                // One run on the freshly allocated (zero) buffers proves the context executes with the downloaded skel.
                val inputs = compiled.createInputBuffers()
                val outputs = compiled.createOutputBuffers()
                compiled.run(inputs, outputs)
                println("NPU_SKEL model ran: ${inputs.size} inputs, ${outputs.size} outputs")
                (inputs + outputs).forEach { it.close() }
            } finally {
                compiled.close()
            }
        } finally {
            env.close()
        }
    }
}

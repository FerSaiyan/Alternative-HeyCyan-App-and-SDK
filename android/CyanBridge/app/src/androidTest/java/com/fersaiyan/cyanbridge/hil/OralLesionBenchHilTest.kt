package com.fersaiyan.cyanbridge.hil

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fersaiyan.cyanbridge.plugins.orallesion.OralLesionEngine
import com.fersaiyan.cyanbridge.plugins.orallesion.OralLesionModelInstaller
import com.fersaiyan.cyanbridge.shared.plugins.orallesion.CaseDemographics
import com.fersaiyan.cyanbridge.shared.plugins.orallesion.OralLesionAnswer
import com.fersaiyan.cyanbridge.shared.plugins.orallesion.OralLesionGuardrail
import com.fersaiyan.cyanbridge.shared.plugins.orallesion.OralLesionModelState
import com.fersaiyan.cyanbridge.shared.plugins.orallesion.OralLesionModels
import com.fersaiyan.cyanbridge.shared.plugins.orallesion.OralLesionPrompts
import com.fersaiyan.cyanbridge.shared.plugins.orallesion.PatientSex
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Oral Lesion Bench on a real phone: loads an installed bench model (the fine-tuned one first) and
 * answers one photo with the training prompts, then the dentist's answers in the same conversation.
 * Skips on emulators, without `-e hil_local_ai true`, or when no bench model is installed (download
 * it in the plugin first). `-e oral_photo /sdcard/...jpg` uses a real photo; otherwise a synthetic
 * mucosa-coloured image checks the pipeline only.
 *
 *   tools/hil/run_instrumentation.sh <serial> hardware com.fersaiyan.cyanbridge.hil.OralLesionBenchHilTest
 *   (with CYANBRIDGE_HIL_LOCAL_AI=true)
 */
@RunWith(AndroidJUnit4::class)
class OralLesionBenchHilTest {
    @Test
    fun answersOnePhotoOnTheGpu() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val emulator = Build.FINGERPRINT.contains("generic") || Build.HARDWARE.contains("ranchu") || Build.PRODUCT.contains("sdk")
        assumeTrue("Real phone only", !emulator)
        assumeTrue("Pass -e hil_local_ai true to load a model", HilTestSupport.localAiRequired)
        val installed = OralLesionModels.entries.sortedByDescending { it.fineTuned }.firstNotNullOfOrNull { model ->
            (OralLesionModelInstaller.state(context, model) as? OralLesionModelState.Installed)?.let { model to it.path }
        }
        HilTestSupport.requireOrSkip(installed != null, "No Oral Lesion Bench model is installed")
        val (model, path) = installed!!
        val photo = InstrumentationRegistry.getArguments().getString("oral_photo")?.let(::File)?.takeIf { it.isFile }
            ?: syntheticPhoto(File(context.cacheDir, "oral_lesion_hil.jpg"))

        val engine = OralLesionEngine(context)
        try {
            val demographics = CaseDemographics(54, PatientSex.FEMALE)
            val turn1 = engine.start(path, photo.absolutePath, OralLesionPrompts.SYSTEM, OralLesionPrompts.assessment(demographics)) {}
            println("OralLesionBenchHil model=${model.id} backend=${turn1.backend} metrics=${turn1.metrics}\n${turn1.text}")
            assertTrue("Empty turn-1 answer", turn1.text.isNotBlank())
            assertTrue("Expected the GPU backend, got ${turn1.backend}", turn1.backend == "GPU")
            val answer = OralLesionAnswer.parse(turn1.text)
            if (model.fineTuned) {
                assertTrue("Fine-tuned answer lost the training format", answer.findings.isNotEmpty() && answer.hypotheses.isNotEmpty())
            }
            OralLesionGuardrail.evaluate(turn1.text)
            val questions = answer.questions.take(OralLesionPrompts.MAX_QUESTIONS)
            if (questions.isNotEmpty()) {
                val turn2 = engine.continueConversation(
                    OralLesionPrompts.answers(questions.map { com.fersaiyan.cyanbridge.shared.plugins.orallesion.DentistAnswer(it, null) }),
                ) {}
                println("OralLesionBenchHil turn2 metrics=${turn2.metrics}\n${turn2.text}")
                assertTrue("Empty turn-2 answer", turn2.text.isNotBlank())
            }
        } finally {
            engine.close()
        }
    }

    private fun syntheticPhoto(file: File): File {
        val bitmap = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
        for (y in 0 until 480) for (x in 0 until 640) {
            val spot = (x - 320) * (x - 320) + (y - 240) * (y - 240) < 60 * 60
            bitmap.setPixel(x, y, if (spot) Color.rgb(235, 235, 225) else Color.rgb(205, 110 + (y % 20), 120))
        }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        return file
    }
}

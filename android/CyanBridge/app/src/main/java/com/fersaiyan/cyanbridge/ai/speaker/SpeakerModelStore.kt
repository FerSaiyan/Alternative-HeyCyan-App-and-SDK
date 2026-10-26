package com.fersaiyan.cyanbridge.ai.speaker

import android.content.Context
import android.util.Log
import com.fersaiyan.cyanbridge.localmodels.storage.LocalModelFileUtils
import java.io.File

/**
 * Installed speaker models live in `filesDir/speaker_models/`. The exports are not published
 * yet ([SpeakerModelSpec.sourceUrl] is null), so a model is installed by staging the file in
 * the app's external files directory, which needs no storage permission:
 *
 * ```
 * adb push ecapa_spkrec_voxceleb_2s.tflite \
 *   /sdcard/Android/data/com.fersaiyan.cyanbridge/files/speaker_models/
 * ```
 *
 * [installStaged] verifies size and SHA-256 before moving it into private storage.
 */
object SpeakerModelStore {
    private const val TAG = "SpeakerModelStore"
    private const val DIR = "speaker_models"

    fun installedFile(context: Context, spec: SpeakerModelSpec): File =
        File(File(context.filesDir, DIR), spec.fileName)

    fun isInstalled(context: Context, spec: SpeakerModelSpec): Boolean =
        installedFile(context, spec).let { it.isFile && it.length() == spec.sizeBytes }

    fun stagedFile(context: Context, spec: SpeakerModelSpec): File? =
        context.getExternalFilesDir(DIR)?.let { File(it, spec.fileName) }

    /** Returns the installed file, installing a verified staged copy first when one exists. */
    fun resolve(context: Context, spec: SpeakerModelSpec): File? {
        if (isInstalled(context, spec)) return installedFile(context, spec)
        return runCatching { installStaged(context, spec) }
            .onFailure { Log.w(TAG, "Staged ${spec.id} not installed: ${it.message}") }
            .getOrNull()
    }

    fun installStaged(context: Context, spec: SpeakerModelSpec): File? {
        val staged = stagedFile(context, spec)?.takeIf { it.isFile } ?: return null
        check(staged.length() == spec.sizeBytes) {
            "${spec.fileName} has ${staged.length()} bytes, expected ${spec.sizeBytes}"
        }
        check(LocalModelFileUtils.sha256Hex(staged).equals(spec.sha256, ignoreCase = true)) {
            "${spec.fileName} failed SHA-256 verification"
        }
        val target = installedFile(context, spec)
        target.parentFile?.mkdirs()
        val partial = File(target.parentFile, "${target.name}.part")
        staged.copyTo(partial, overwrite = true)
        check(partial.renameTo(target)) { "Could not install ${spec.fileName}" }
        staged.delete()
        Log.i(TAG, "Installed ${spec.id} bytes=${target.length()}")
        return target
    }
}

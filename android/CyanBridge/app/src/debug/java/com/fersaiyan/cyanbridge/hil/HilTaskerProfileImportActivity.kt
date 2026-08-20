package com.fersaiyan.cyanbridge.hil

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import java.io.File

/**
 * Debug-only bridge used by the lab runner to hand the exact branch XML to Tasker.
 *
 * The runner stages files under cache/hil-profiles using `adb run-as`; this activity only
 * accepts a fixed allowlist of filenames and grants Tasker one-shot read access through the
 * app's existing FileProvider. Tasker's normal import confirmation UI is still shown.
 */
class HilTaskerProfileImportActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val profile = intent.getStringExtra(EXTRA_PROFILE).orEmpty()
        if (profile !in ALLOWED_PROFILES) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }

        val file = File(cacheDir, "hil-profiles/$profile")
        if (!file.isFile) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }

        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/xml")
            setPackage(TASKER_PACKAGE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/xml"
            setPackage(TASKER_PACKAGE)
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val importIntent = listOf(viewIntent, sendIntent).firstOrNull {
            it.resolveActivity(packageManager) != null
        }
        if (importIntent == null) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }

        startActivity(importIntent)
        setResult(RESULT_OK)
        finish()
    }

    companion object {
        const val EXTRA_PROFILE = "profile"
        private const val TASKER_PACKAGE = "net.dinglisch.android.taskerm"
        private val ALLOWED_PROFILES = setOf(
            "Tasker_AI.xml",
            "CyanBridge_LocalAgent_Tasker.XML",
            "CyanBridge_AutoDiary_Tasker.XML",
            "CyanBridge_VisualDiary_Tasker.XML",
            "CyanBridge_HIL_Tasker.XML",
        )
    }
}

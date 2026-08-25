package com.fersaiyan.cyanbridge.tasker

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.appcompat.app.AlertDialog

object TaskerProfileGuidance {
    private const val TASKER_PACKAGE = "net.dinglisch.android.taskerm"
    private const val TASKER_IMPORT_ACTIVITY =
        "com.joaomgcd.taskerm.datashare.import.ActivityImportTaskerDataFromXml"
    private const val YOUTUBE_PACKAGE = "com.google.android.youtube"
    private const val TUTORIAL_URL = "https://youtube.com/@fernandosaiyan8650"

    fun showSavedDialog(activity: Activity, fileName: String, uri: Uri) {
        val isProject = fileName.endsWith(".prj.xml", ignoreCase = true)
        val tab = if (isProject) "anywhere on the bottom navigation bar" else "the Profiles tab"
        val action = if (isProject) "Import Project" else "Import Profile"
        AlertDialog.Builder(activity)
            .setTitle("Tasker file saved")
            .setMessage(
                "Saved $fileName.\n\n" +
                    "In Tasker, long-press $tab, choose $action, then select this file.",
            )
            .setNegativeButton("Close", null)
            .setNeutralButton("Setup videos") { _, _ -> openTutorial(activity) }
            .setPositiveButton("Import in Tasker") { _, _ ->
                if (!openImporter(activity, uri, fileName)) openTasker(activity)
            }
            .show()
    }

    fun openImporter(context: Context, uri: Uri, fileName: String): Boolean = runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "text/xml")
            setClassName(TASKER_PACKAGE, TASKER_IMPORT_ACTIVITY)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newRawUri(fileName, uri)
        })
    }.isSuccess

    fun openTutorial(context: Context) {
        val uri = Uri.parse(TUTORIAL_URL)
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage(YOUTUBE_PACKAGE))
        }.recoverCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri))
        }.onFailure {
            Toast.makeText(context, "Could not open the Tasker setup videos", Toast.LENGTH_LONG).show()
        }
    }

    private fun openTasker(context: Context) {
        val intent = context.packageManager.getLaunchIntentForPackage(TASKER_PACKAGE)
        if (intent == null) {
            Toast.makeText(context, "Tasker is not installed", Toast.LENGTH_LONG).show()
            return
        }
        context.startActivity(intent)
    }
}

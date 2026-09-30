package com.fersaiyan.cyanbridge.media

import android.os.Environment

object SyncedMediaFolder {
    private const val SUB_FOLDER = "CyanBridge"

    val relativePath: String = "${Environment.DIRECTORY_DCIM}/$SUB_FOLDER"
    val relativePathWithTrailingSlash: String = "$relativePath/"

    /**
     * Audio collections reject DCIM on Android 10+ (only Alarms, Audiobooks, Music,
     * Notifications, Podcasts, Recordings, Ringtones are allowed), so synced
     * recordings live under Music instead of alongside photos/videos.
     */
    val relativeAudioPath: String = "${Environment.DIRECTORY_MUSIC}/$SUB_FOLDER"

    fun relativePathLikePattern(): String = "$relativePath/%"

    fun legacyAbsolutePathLikePattern(): String = "%/${Environment.DIRECTORY_DCIM}/$SUB_FOLDER/%"
}

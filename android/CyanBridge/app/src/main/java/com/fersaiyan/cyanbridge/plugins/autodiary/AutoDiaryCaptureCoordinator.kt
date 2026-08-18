package com.fersaiyan.cyanbridge.plugins.autodiary

import android.content.Context
import android.util.Log
import com.fersaiyan.cyanbridge.agent.LocalAgentPrefs
import com.fersaiyan.cyanbridge.localagent.LocalAgentDeviceState
import com.fersaiyan.cyanbridge.localagent.memory.LocalAgentMemoryRoomIndex
import com.fersaiyan.cyanbridge.localagent.memory.LocalAgentMemoryStore
import com.fersaiyan.cyanbridge.localagent.tasker.TaskerAgentBridge
import com.fersaiyan.cyanbridge.localagent.tasker.TaskerAgentContract
import com.fersaiyan.cyanbridge.memoryvault.MemoryModeManager
import com.fersaiyan.cyanbridge.memoryvault.MemoryVaultBootstrap
import com.fersaiyan.cyanbridge.memoryvault.VaultLockStateManager

/**
 * Coordinates one AutoDiary screen sample.
 *
 * CyanBridge remains the source of truth for feature state, privacy policy, blacklist,
 * storage and indexing. Tasker/AutoInput is only the Android UI observer.
 */
object AutoDiaryCaptureCoordinator {
    data class CaptureResult(
        val success: Boolean,
        val detail: String,
    )

    suspend fun captureOnce(context: Context): CaptureResult {
        val appContext = context.applicationContext

        if (!AutoDiaryService.isEnabled(appContext)) {
            return CaptureResult(false, "auto_diary_disabled")
        }
        if (!MemoryModeManager.isScreenOcrCaptureEnabled(appContext)) {
            return CaptureResult(false, "screen_memory_disabled")
        }
        if (!LocalAgentDeviceState.isReady(appContext)) {
            return CaptureResult(false, "device_not_ready")
        }
        if (VaultLockStateManager.isLocked(appContext)) {
            return CaptureResult(false, "memory_vault_locked")
        }
        if (!TaskerAgentBridge.isTaskerUiObserverAvailable(appContext)) {
            return CaptureResult(false, "tasker_or_autoinput_missing")
        }

        MemoryVaultBootstrap.ensureInitialized(appContext)

        val response = TaskerAgentBridge.requestAutoDiaryObservation(appContext)
        if (!response.success || response.payload.isNullOrBlank()) {
            return CaptureResult(
                false,
                response.error?.takeIf { it.isNotBlank() } ?: "tasker_observation_failed",
            )
        }

        val observation = runCatching {
            TaskerAgentContract.observationFromJson(response.payload)
        }.getOrElse {
            return CaptureResult(false, "invalid_tasker_observation:${it.javaClass.simpleName}")
        }

        val packageName = observation.packageName
            ?.trim()
            ?.lowercase()
            .orEmpty()
        if (packageName.isBlank()) {
            return CaptureResult(false, "observation_package_missing")
        }

        if (LocalAgentPrefs.getCaptureBlacklistPackages(appContext).contains(packageName)) {
            Log.d(TAG, "Skipping blacklisted package: $packageName")
            return CaptureResult(false, "blacklisted_package:$packageName")
        }
        if (isOverlayPackage(packageName)) {
            Log.d(TAG, "Skipping overlay/system package: $packageName")
            return CaptureResult(false, "overlay_package:$packageName")
        }

        val nodeTexts = observation.screenSnapshot?.nodes.orEmpty().map { it.text }
        val text = normalizedText(observation.screenText, nodeTexts)
        if (text.isBlank()) {
            return CaptureResult(false, "observation_text_empty")
        }

        val timestamp = observation.createdAtMs.takeIf { it > 0L } ?: System.currentTimeMillis()
        LocalAgentMemoryStore.appendScreenCapture(
            context = appContext,
            packageName = packageName,
            text = text,
            tsMs = timestamp,
        )
        LocalAgentMemoryRoomIndex.indexScreenCaptureAsync(
            context = appContext,
            packageName = packageName,
            text = text,
            tsMs = timestamp,
        )

        Log.i(TAG, "Stored Tasker AutoDiary capture: pkg=$packageName chars=${text.length}")
        return CaptureResult(true, "stored:$packageName:${text.length}")
    }

    private fun normalizedText(summary: String?, nodeTexts: List<String>): String {
        val lines = LinkedHashSet<String>()

        fun add(raw: String?) {
            raw.orEmpty()
                .lineSequence()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .forEach { value ->
                    if (lines.size < MAX_LINES) lines += value
                }
        }

        add(summary)
        if (lines.isEmpty()) nodeTexts.forEach(::add)
        return lines.joinToString("\n").take(MAX_CAPTURE_CHARS)
    }

    private fun isOverlayPackage(packageName: String): Boolean {
        if (packageName.isBlank()) return true
        if (OVERLAY_PACKAGE_PREFIXES.any { packageName.startsWith(it) }) return true
        return packageName in OVERLAY_PACKAGE_NAMES
    }

    private const val TAG = "AutoDiaryCapture"
    private const val MAX_LINES = 400
    private const val MAX_CAPTURE_CHARS = 25_000

    private val OVERLAY_PACKAGE_NAMES = setOf(
        "com.android.systemui",
    )
    private val OVERLAY_PACKAGE_PREFIXES = setOf(
        "com.android.launcher",
        "com.google.android.launcher",
        "com.samsung.android.launcher",
    )
}

package com.fersaiyan.cyanbridge.plugins.autodiary

import android.content.Context
import android.util.Log
import com.fersaiyan.cyanbridge.localagent.LocalAgentDeviceState
import com.fersaiyan.cyanbridge.localagent.memory.LocalAgentMemoryRoomIndex
import com.fersaiyan.cyanbridge.localagent.memory.LocalAgentMemoryStore
import com.fersaiyan.cyanbridge.localagent.tasker.TaskerAgentBridge
import com.fersaiyan.cyanbridge.localagent.tasker.TaskerAgentContract
import com.fersaiyan.cyanbridge.memoryvault.MemoryModeManager
import com.fersaiyan.cyanbridge.memoryvault.MemoryVaultBootstrap
import com.fersaiyan.cyanbridge.memoryvault.VaultLockStateManager

/**
 * Coordinates AutoDiary screen-memory ingestion.
 *
 * Tasker owns periodicity, package exclusions and Android UI observation. CyanBridge owns
 * feature state, Memory Mode, encrypted persistence, indexing and downstream daily facts/RAG.
 */
object AutoDiaryCaptureCoordinator {
    data class CaptureResult(
        val success: Boolean,
        val detail: String,
    )

    /** Debug/manual pull path retained for parity testing. Normal periodic operation is Tasker-push. */
    suspend fun captureOnce(context: Context): CaptureResult {
        val appContext = context.applicationContext
        val preflight = preflight(appContext)
        if (preflight != null) return preflight
        if (!TaskerAgentBridge.isTaskerUiObserverAvailable(appContext)) {
            return CaptureResult(false, "tasker_or_autoinput_missing")
        }

        val response = TaskerAgentBridge.requestAutoDiaryObservation(appContext)
        if (!response.success || response.payload.isNullOrBlank()) {
            return CaptureResult(
                false,
                response.error?.takeIf { it.isNotBlank() } ?: "tasker_observation_failed",
            )
        }
        return ingestObservationJson(appContext, response.payload)
    }

    suspend fun ingestObservationJson(context: Context, payload: String): CaptureResult {
        val appContext = context.applicationContext
        val preflight = preflight(appContext)
        if (preflight != null) return preflight

        val observation = runCatching {
            TaskerAgentContract.observationFromJson(payload)
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

        // User-selected exclusions live in Tasker so CyanBridge never needs package inventory
        // visibility. Keep only a fixed launcher/system guard here as defense in depth.
        if (isOverlayPackage(packageName)) {
            Log.d(TAG, "Skipping overlay/system package: $packageName")
            return CaptureResult(false, "overlay_package:$packageName")
        }

        val nodeTexts = observation.screenSnapshot?.nodes.orEmpty().map { it.text }
        val text = normalizedText(observation.screenText, nodeTexts)
        if (text.isBlank()) {
            return CaptureResult(false, "observation_text_empty")
        }

        MemoryVaultBootstrap.ensureInitialized(appContext)
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

    private fun preflight(context: Context): CaptureResult? {
        if (!AutoDiaryService.isEnabled(context)) {
            return CaptureResult(false, "auto_diary_disabled")
        }
        if (!MemoryModeManager.isScreenOcrCaptureEnabled(context)) {
            return CaptureResult(false, "screen_memory_disabled")
        }
        if (!LocalAgentDeviceState.isReady(context)) {
            return CaptureResult(false, "device_not_ready")
        }
        if (VaultLockStateManager.isLocked(context)) {
            return CaptureResult(false, "memory_vault_locked")
        }
        return null
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

    private val OVERLAY_PACKAGE_NAMES = setOf("com.android.systemui")
    private val OVERLAY_PACKAGE_PREFIXES = setOf(
        "com.android.launcher",
        "com.google.android.launcher",
        "com.samsung.android.launcher",
    )
}

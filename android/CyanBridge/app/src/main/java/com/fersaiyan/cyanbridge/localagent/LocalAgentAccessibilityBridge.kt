package com.fersaiyan.cyanbridge.localagent

import android.util.Log
import com.fersaiyan.cyanbridge.localagent.accessibility.LocalAgentAccessibilityService
import kotlinx.coroutines.CancellationException

/**
 * Simple in-process bridge between our foreground [LocalAgentService] and the
 * [LocalAgentAccessibilityService] singleton.
 */
object LocalAgentAccessibilityBridge {
    private const val TAG = "LocalAgentBridge"

    fun isConnected(): Boolean = LocalAgentAccessibilityService.instance != null

    fun snapshotScreenText(maxChars: Int = 12_000): String? {
        val svc = LocalAgentAccessibilityService.instance ?: return null
        return try {
            svc.dumpActiveWindowText()?.take(maxChars)
        } catch (e: Exception) {
            Log.w(TAG, "snapshotScreenText failed: ${e.message}")
            null
        }
    }

    fun snapshotScreen(
        maxNodes: Int = 180,
        maxChars: Int = 12_000,
    ): LocalAgentScreenSnapshot? {
        val svc = LocalAgentAccessibilityService.instance ?: return null
        return try {
            val nodes = svc.dumpScreenNodes(maxNodes = maxNodes)
            val text = svc.dumpActiveWindowText()?.take(maxChars)
            val packageName = svc.getCurrentForegroundPackageName()

            if (nodes.isEmpty() && text.isNullOrBlank() && packageName.isNullOrBlank()) null
            else LocalAgentScreenSnapshot(packageName, text, nodes)
        } catch (e: Exception) {
            Log.w(TAG, "snapshotScreen failed: ${e.message}")
            null
        }
    }

    fun currentForegroundPackageName(): String? {
        val svc = LocalAgentAccessibilityService.instance ?: return null
        return runCatching { svc.getCurrentForegroundPackageName() }
            .onFailure { Log.w(TAG, "currentForegroundPackageName failed: ${it.message}") }
            .getOrNull()
    }

    fun activeWindowPackageName(): String? {
        val svc = LocalAgentAccessibilityService.instance ?: return null
        return runCatching { svc.getActiveWindowPackageName() }
            .onFailure { Log.w(TAG, "activeWindowPackageName failed: ${it.message}") }
            .getOrNull()
    }

    suspend fun captureScreenshot(): LocalAgentScreenshotResult {
        val svc = LocalAgentAccessibilityService.instance
            ?: return LocalAgentScreenshotResult(error = "accessibility_not_connected")
        return try {
            svc.takeScreenshotForPlanning()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "captureScreenshot failed: ${e.message}")
            LocalAgentScreenshotResult(error = "screenshot_capture_failed")
        }
    }

    suspend fun perform(action: LocalAgentAction): Boolean {
        val svc = LocalAgentAccessibilityService.instance ?: return false
        return try {
            when (action) {
                is LocalAgentAction.Wait -> true
                LocalAgentAction.GlobalBack -> svc.pressBack()
                LocalAgentAction.GlobalHome -> svc.pressHome()
                is LocalAgentAction.ClickText -> svc.clickByTextOrDesc(action.text)
                is LocalAgentAction.ClickCoord -> svc.simulateClick(action.x, action.y)
                is LocalAgentAction.TypeText -> svc.typeTextBestEffort(action.text, action.hint)
                LocalAgentAction.PressEnter -> svc.pressEnter()
                is LocalAgentAction.Scroll -> svc.scrollGesture(
                    when (action.direction) {
                        LocalAgentAction.Direction.UP -> LocalAgentAccessibilityService.ScrollDirection.UP
                        LocalAgentAction.Direction.DOWN -> LocalAgentAccessibilityService.ScrollDirection.DOWN
                    }
                )
                is LocalAgentAction.Swipe -> svc.swipe(
                    action.startX, action.startY, action.endX, action.endY, action.durationMs
                )
                is LocalAgentAction.LongPress -> svc.longPress(action.x, action.y, action.durationMs)
                LocalAgentAction.OpenNotifications -> svc.openNotifications()
                LocalAgentAction.OpenRecents -> svc.openRecents()
                is LocalAgentAction.OpenApp -> false
                is LocalAgentAction.Finish -> true
                is LocalAgentAction.SendEmail -> false
                LocalAgentAction.ReadScreenAloud -> false
                is LocalAgentAction.MakeCall -> false
                is LocalAgentAction.SendSms -> false
                is LocalAgentAction.SetAlarm -> false
                LocalAgentAction.OpenContacts -> false
                LocalAgentAction.ToggleWifi -> false
                LocalAgentAction.ToggleBluetooth -> false
                LocalAgentAction.ToggleFlashlight -> false
            }
        } catch (e: Exception) {
            Log.w(TAG, "perform failed: ${e.message}")
            false
        }
    }

    /** Legacy call site kept during the Tasker migration. No privileged fallback remains. */
    suspend fun performWithOptionalShizukuFallback(
        context: android.content.Context,
        action: LocalAgentAction,
    ): Boolean {
        if (!LocalAgentDeviceState.isReady(context)) return false
        return perform(action)
    }
}

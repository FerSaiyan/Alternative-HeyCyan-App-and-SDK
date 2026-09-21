package com.fersaiyan.cyanbridge.media

import com.fersaiyan.cyanbridge.shared.glasses.AdaptiveSyncDiagnosticsUiState
import com.fersaiyan.cyanbridge.shared.glasses.AdaptiveSyncStageStatus
import com.fersaiyan.cyanbridge.shared.glasses.AdaptiveSyncStageUiState
import com.fersaiyan.cyanbridge.shared.glasses.AdaptiveSyncTrialUiState

/**
 * Maps actual transport events to the portable Material 3 dashboard. Presentation never
 * starts retries or infers transport success from elapsed time. Device/IP identifiers and
 * low-level exception messages are deliberately excluded from user-visible diagnostics.
 */
internal object AdaptiveSyncDiagnosticsPresenter {
    private val stages = listOf(
        "BLE connection" to "Confirm the paired glasses are reachable",
        "Transfer mode" to "Request media mode and wait for independent evidence",
        "Wi-Fi Direct" to "Discover the glasses peer and form a P2P group",
        "Glasses address" to "Receive the glasses' own IP over BLE",
        "HTTP media list" to "Find a working route and read media.config",
        "Media transfer" to "Download the files and retain the successful route",
    )

    fun present(session: AdaptiveP2pSyncSession): AdaptiveSyncDiagnosticsUiState {
        val events = session.snapshotEvents()
        val kinds = events.map { it.checkpoint }.toSet()
        val completed = listOf(
            AdaptiveSyncCheckpoint.BLE_READY in kinds,
            AdaptiveSyncCheckpoint.TRANSFER_EVIDENCE in kinds,
            AdaptiveSyncCheckpoint.P2P_GROUP_FORMED in kinds,
            AdaptiveSyncCheckpoint.BLE_IP_RECEIVED in kinds,
            AdaptiveSyncCheckpoint.MEDIA_CONFIG_COMPLETE in kinds,
            AdaptiveSyncCheckpoint.COMPLETE in kinds,
        )
        val succeeded = AdaptiveSyncCheckpoint.COMPLETE in kinds
        val failed = AdaptiveSyncCheckpoint.FAILED in kinds && !succeeded
        val cancelled = AdaptiveSyncCheckpoint.CANCELLED in kinds && !succeeded && !failed
        val firstPending = completed.indexOfFirst { !it }.let { if (it < 0) 5 else it }
        val currentStage = if (succeeded || cancelled) -1 else firstPending
        val stageRows = stages.mapIndexed { index, (title, pending) ->
            val status = when {
                completed[index] -> AdaptiveSyncStageStatus.COMPLETE
                index == currentStage && failed -> AdaptiveSyncStageStatus.FAILED
                index == currentStage -> AdaptiveSyncStageStatus.ACTIVE
                else -> AdaptiveSyncStageStatus.WAITING
            }
            val detail = if (completed[index]) when (index) {
                0 -> "Paired glasses are available"
                1 -> "Transfer mode confirmed by P2P or BLE evidence"
                2 -> "Wi-Fi Direct group formed"
                3 -> "Glasses address received; retained for HTTP trials"
                4 -> "Media list received; successful route retained"
                else -> "Sync finished; route learning updated"
            } else pending
            AdaptiveSyncStageUiState(title, detail, status)
        }

        val latest = events.lastOrNull()
        val explanation = when (latest?.checkpoint) {
            AdaptiveSyncCheckpoint.HTTP_WARMUP_WAIT -> {
                val wait = value(latest.detail, "warmup_ms")?.toLongOrNull() ?: 0L
                if (value(latest.detail, "sticky") == "true") {
                    "HTTP responded on the previous attempt. Keeping that route and checking again after ${wait} ms of warm-up."
                } else {
                    "Waiting ${wait} ms for the glasses' HTTP server, then trying the available routes in learned order."
                }
            }
            AdaptiveSyncCheckpoint.HTTP_ROUTE_TRIAL ->
                "Checking ${routeName(value(latest.detail, "route"))} after the selected warm-up. Earlier successful stages are kept."
            AdaptiveSyncCheckpoint.HTTP_ROUTE_FAILED ->
                if (value(latest.detail, "reachable") == "true")
                    "The HTTP server responded, but the media list was incomplete. Keep this route and retry after the next warm-up."
                else
                    "This HTTP route did not provide a media list. Try the next allowed route, without repeating the successful BLE and P2P stages."
            AdaptiveSyncCheckpoint.MEDIA_CONFIG_HEADERS ->
                "The HTTP server responded. Keeping this route while verifying the complete media list."
            AdaptiveSyncCheckpoint.RECOVERY_STEP -> when (value(latest.detail, "kind")) {
                "command" -> "No transport evidence yet. Retry only the transfer command; preserve the connection state."
                "discovery" -> "Retry Android peer discovery; keep the glasses' current transfer mode."
                "metadata" -> "Wi-Fi Direct is connected. Ask the glasses for their IP again instead of reconnecting."
                "reset" -> "Previous recovery did not establish Wi-Fi Direct. Attempt one bounded glasses P2P reset."
                else -> "Trying the next recovery step while retaining verified checkpoints."
            }
            AdaptiveSyncCheckpoint.COMPLETE -> "Media sync completed. The successful route and warm-up can improve the next sync."
            AdaptiveSyncCheckpoint.CANCELLED -> "Sync was stopped. Completed checkpoints remain visible for diagnosis; no connection trial is continuing."
            AdaptiveSyncCheckpoint.FAILED -> "This sync could not finish. The verified checkpoints remain visible below for diagnosis."
            AdaptiveSyncCheckpoint.MEDIA_FILE_PROGRESS -> "Media data is transferring. The successful HTTP route is already known."
            AdaptiveSyncCheckpoint.MEDIA_FILE_RETRY -> "Retrying an individual media file without repeating Wi-Fi Direct discovery."
            else -> when {
                succeeded -> "Media sync completed."
                failed -> "Sync stopped before all stages completed."
                else -> "Checking each connection stage. Verified progress is retained across trials."
            }
        }
        val profile = session.profile
        val learned = when {
            profile.preferredRoute != null ->
                "Learned route: ${routeName(profile.preferredRoute.name)}; previously confirmed ${profile.completedSyncs} sync(s)."
            profile.candidateRoute != null ->
                "Promising route: ${routeName(profile.candidateRoute.name)} (${profile.candidateSuccesses}/2 successful syncs before promotion)."
            else -> "No confirmed route yet. Starting with the Wi-Fi Direct network, then local socket and system routing."
        }
        val trialKinds = setOf(
            AdaptiveSyncCheckpoint.TRANSFER_COMMAND_SENT,
            AdaptiveSyncCheckpoint.TRANSFER_CALLBACK,
            AdaptiveSyncCheckpoint.P2P_CONNECT_FAILED,
            AdaptiveSyncCheckpoint.HTTP_WARMUP_WAIT,
            AdaptiveSyncCheckpoint.HTTP_ROUTE_TRIAL,
            AdaptiveSyncCheckpoint.HTTP_ROUTE_FAILED,
            AdaptiveSyncCheckpoint.MEDIA_CONFIG_HEADERS,
            AdaptiveSyncCheckpoint.MEDIA_CONFIG_COMPLETE,
            AdaptiveSyncCheckpoint.RECOVERY_STEP,
            AdaptiveSyncCheckpoint.COMPLETE,
            AdaptiveSyncCheckpoint.FAILED,
            AdaptiveSyncCheckpoint.CANCELLED,
        )
        val trials = events.filter { it.checkpoint in trialKinds }
            .takeLast(8)
            .asReversed()
            .map(::trial)
        return AdaptiveSyncDiagnosticsUiState(
            headline = when {
                succeeded -> "Connection learned"
                failed -> "Sync diagnostic complete"
                cancelled -> "Sync stopped"
                else -> "Finding a reliable connection"
            },
            explanation = explanation,
            learnedProfile = learned,
            stages = stageRows,
            trials = trials,
            isTerminal = succeeded || failed || cancelled,
        )
    }

    private fun trial(event: AdaptiveSyncEvent): AdaptiveSyncTrialUiState {
        val route = routeName(value(event.detail, "route"))
        val wait = value(event.detail, "warmup_ms")?.toLongOrNull()
        val info = if (wait == null) "" else " after ${wait} ms warm-up"
        val (title, detail, status) = when (event.checkpoint) {
            AdaptiveSyncCheckpoint.TRANSFER_COMMAND_SENT -> Triple(
                "Transfer request", "Attempt ${value(event.detail, "attempt") ?: "1"}; waiting for transport evidence",
                AdaptiveSyncStageStatus.ACTIVE,
            )
            AdaptiveSyncCheckpoint.TRANSFER_CALLBACK -> Triple(
                "Transfer callback", "The glasses responded; checking for independent connection evidence",
                AdaptiveSyncStageStatus.ACTIVE,
            )
            AdaptiveSyncCheckpoint.P2P_CONNECT_FAILED -> Triple(
                "Wi-Fi Direct retry", "Connection failed; continuing bounded peer discovery",
                AdaptiveSyncStageStatus.FAILED,
            )
            AdaptiveSyncCheckpoint.HTTP_WARMUP_WAIT -> Triple(
                "HTTP warm-up", "Waiting for the glasses' media server before the next trial",
                AdaptiveSyncStageStatus.ACTIVE,
            )
            AdaptiveSyncCheckpoint.HTTP_ROUTE_TRIAL -> Triple(
                "Trying $route", "Reading the media list$info",
                AdaptiveSyncStageStatus.ACTIVE,
            )
            AdaptiveSyncCheckpoint.HTTP_ROUTE_FAILED -> Triple(
                "Route did not complete", if (value(event.detail, "reachable") == "true")
                    "$route responded; retain this route and retry the media list"
                else "$route was unavailable; another route will be tried",
                AdaptiveSyncStageStatus.FAILED,
            )
            AdaptiveSyncCheckpoint.MEDIA_CONFIG_HEADERS -> Triple(
                "HTTP server reached", "$route responded; retaining this connection path",
                AdaptiveSyncStageStatus.COMPLETE,
            )
            AdaptiveSyncCheckpoint.MEDIA_CONFIG_COMPLETE -> Triple(
                "Media list received", "Successful route: $route$info",
                AdaptiveSyncStageStatus.COMPLETE,
            )
            AdaptiveSyncCheckpoint.RECOVERY_STEP -> Triple(
                "Adaptive recovery", when (value(event.detail, "kind")) {
                    "command" -> "Retry transfer command, keep known connection state"
                    "discovery" -> "Retry Android peer scan, keep glasses mode"
                    "metadata" -> "P2P connected; request glasses address again"
                    "reset" -> "One bounded glasses Wi-Fi reset after earlier recovery"
                    else -> "Try a bounded recovery step"
                },
                AdaptiveSyncStageStatus.ACTIVE,
            )
            AdaptiveSyncCheckpoint.COMPLETE -> Triple(
                "Sync completed", "Successful connection can be reused next time",
                AdaptiveSyncStageStatus.COMPLETE,
            )
            AdaptiveSyncCheckpoint.CANCELLED -> Triple(
                "Sync stopped", "Keeping the completed checkpoints for your diagnostic report",
                AdaptiveSyncStageStatus.WAITING,
            )
            else -> Triple("Sync could not complete", "Review the last verified stage above", AdaptiveSyncStageStatus.FAILED)
        }
        return AdaptiveSyncTrialUiState(title, detail, event.elapsedMs, status)
    }

    private fun routeName(raw: String?): String = when (raw) {
        AdaptiveHttpRoute.P2P_NETWORK.name -> "Wi-Fi Direct network"
        AdaptiveHttpRoute.LOCAL_ADDRESS.name -> "P2P-local socket"
        AdaptiveHttpRoute.SYSTEM.name -> "Android system route"
        else -> "HTTP route"
    }

    private fun value(detail: String, key: String): String? =
        detail.split(',').firstOrNull { it.startsWith("$key=") }?.substringAfter('=')
}

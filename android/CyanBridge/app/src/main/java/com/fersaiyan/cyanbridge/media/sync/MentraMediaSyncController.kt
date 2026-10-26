package com.fersaiyan.cyanbridge.media.sync

import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.fersaiyan.cyanbridge.R
import com.fersaiyan.cyanbridge.devices.mentra.MentraGallerySync
import com.fersaiyan.cyanbridge.devices.mentra.MentraGallerySyncResult
import com.fersaiyan.cyanbridge.devices.mentra.MentraLiveManager
import com.fersaiyan.cyanbridge.glasses.GlassesSession
import com.fersaiyan.cyanbridge.glasses.GlassesSessionCoordinator
import com.fersaiyan.cyanbridge.glasses.GlassesSessionLease
import com.fersaiyan.cyanbridge.shared.devices.mentra.MentraGallerySyncPlanner
import com.fersaiyan.cyanbridge.shared.devices.mentra.MentraMediaKind
import com.fersaiyan.cyanbridge.shared.glasses.GlassesSyncFlow
import com.fersaiyan.cyanbridge.ui.hasWifiP2pPermission
import com.fersaiyan.cyanbridge.ui.requestWifiP2pPermission
import com.hjq.permissions.OnPermissionCallback
import java.io.File
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Mentra Live gallery sync on the dashboard: holds the exclusive media-sync session, drives the
 * shared transfer card like the Eyevue and TuneBuds syncs, saves files into the same synced
 * gallery ([GlassesMediaLibrary]) and asks before deleting verified files from the glasses, as
 * the HeyCyan sync does on iOS. The transfer itself is [MentraGallerySync].
 */
internal class MentraMediaSyncController(
    private val activity: AppCompatActivity,
    private val transferUi: TransferProgressPresenter,
    private val mediaLibrary: GlassesMediaLibrary,
    /** Called on the main thread after a sync that reached the glasses (to refresh counts). */
    private val onSynced: () -> Unit,
) {
    private var job: Job? = null
    private var cancelled = false

    val isActive: Boolean get() = job?.isActive == true

    fun start(manager: MentraLiveManager) {
        if (isActive) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return toast(R.string.mentra_sync_needs_android_10)
        if (!manager.ready) return toast(R.string.mentra_not_ready)
        if (manager.isRecordingVideo) return toast(R.string.mentra_sync_stop_recording)
        if (!hasWifiP2pPermission(activity)) {
            requestWifiP2pPermission(activity, object : OnPermissionCallback {
                override fun onGranted(permissions: MutableList<String>, all: Boolean) {
                    if (all) start(manager) else toast(R.string.mentra_sync_permission_required)
                }

                override fun onDenied(permissions: MutableList<String>, never: Boolean) {
                    toast(R.string.mentra_sync_permission_required)
                }
            })
            return
        }
        val lease = GlassesSessionCoordinator.tryAcquireLease(GlassesSession.MEDIA_SYNC)
        if (lease == null) {
            val active = GlassesSessionCoordinator.currentSession()?.label
            Log.w(MentraGallerySync.TAG, "Cannot start the gallery sync; ${active ?: "a glasses command"} is active")
            return toast(R.string.mentra_sync_session_busy, active ?: "the current glasses command")
        }
        Log.i("GlassesSession", "Acquired ${lease.session.label} session")
        cancelled = false
        transferUi.resetTransferUiState()
        transferUi.setTransferUiVisible(true)
        transferUi.setTransferFlowLabel(GlassesSyncFlow.CUSTOM)
        transferUi.setTransferDetail(activity.getString(R.string.mentra_sync_starting))

        val temporaryDirectory = File(activity.cacheDir, "mentra_media_${System.currentTimeMillis()}")
        job = activity.lifecycleScope.launch(Dispatchers.IO, start = CoroutineStart.ATOMIC) {
            var result: Result<MentraGallerySyncResult>? = null
            try {
                result = Result.success(run(manager, temporaryDirectory))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.e(MentraGallerySync.TAG, "Gallery sync failed", error)
                result = Result.failure(error)
            } finally {
                withContext(Dispatchers.Main + NonCancellable) { finish(lease, result) }
            }
        }
    }

    private suspend fun run(manager: MentraLiveManager, temporaryDirectory: File): MentraGallerySyncResult =
        MentraGallerySync(activity, manager, temporaryDirectory).sync(
            phoneGallery = { mediaLibrary.syncedMediaSizes(MentraGallerySyncPlanner.LOCAL_PREFIX) },
            onState = { progress ->
                withContext(Dispatchers.Main) {
                    if (cancelled) return@withContext
                    progress.plan?.let { plan ->
                        transferUi.transferTotalJpg = plan.photoCount
                        transferUi.transferTotalMp4 = plan.videoCount
                        transferUi.transferTotalOpus = 0
                        transferUi.transferDoneJpg = progress.photosDone
                        transferUi.transferDoneMp4 = progress.videosDone
                        transferUi.transferDoneOpus = 0
                        transferUi.renderTransferProgress()
                    }
                    transferUi.setTransferDetail(progress.detail)
                }
            },
            onProgress = { planned, copied, total ->
                withContext(Dispatchers.Main) {
                    if (!cancelled) {
                        transferUi.renderFileProgress(
                            "Downloading ${planned.localName}: ${TransferProgressPresenter.formatTransferBytes(copied)}" +
                                " / ${TransferProgressPresenter.formatTransferBytes(total)}",
                            copied,
                            total,
                        )
                    }
                }
            },
            onFile = { planned, file ->
                mediaLibrary.importVendorMediaFile(
                    file,
                    VendorMediaItem(
                        fileName = planned.localName,
                        type = if (planned.item.kind == MentraMediaKind.VIDEO) VendorMediaType.VIDEO else VendorMediaType.PHOTO,
                        takenTimeMs = planned.item.takenAtMs,
                    ),
                )
            },
            confirmDelete = { count -> withContext(Dispatchers.Main) { confirmDelete(count) } },
        )

    private suspend fun confirmDelete(count: Int): Boolean {
        if (cancelled || activity.isFinishing || activity.isDestroyed) return false
        return suspendCancellableCoroutine { continuation ->
            val dialog = AlertDialog.Builder(activity)
                .setTitle(R.string.mentra_sync_delete_title)
                .setMessage(activity.getString(R.string.mentra_sync_delete_message, count))
                .setPositiveButton(R.string.mentra_sync_delete_confirm) { _, _ ->
                    if (continuation.isActive) continuation.resume(true)
                }
                .setNegativeButton(R.string.mentra_sync_delete_keep) { _, _ ->
                    if (continuation.isActive) continuation.resume(false)
                }
                .setOnCancelListener { if (continuation.isActive) continuation.resume(false) }
                .show()
            continuation.invokeOnCancellation { activity.runOnUiThread { dialog.dismiss() } }
        }
    }

    private fun finish(lease: GlassesSessionLease, result: Result<MentraGallerySyncResult>?) {
        job = null
        transferUi.setTransferUiVisible(false)
        if (GlassesSessionCoordinator.release(lease)) Log.i("GlassesSession", "Released ${lease.session.label} session")
        if (cancelled || activity.isDestroyed) return
        val outcome = result ?: return
        outcome.onSuccess { synced ->
            val summary = if (synced.failed > 0) {
                activity.getString(R.string.mentra_sync_complete_with_failures, synced.downloaded, synced.alreadyOnPhone, synced.failed)
            } else {
                activity.getString(R.string.mentra_sync_complete, synced.downloaded, synced.alreadyOnPhone)
            }
            val deleted = if (synced.deletable > 0 && !synced.deleteDeclined) {
                " " + activity.getString(R.string.mentra_sync_deleted, synced.deleted, synced.deletable)
            } else {
                ""
            }
            Toast.makeText(activity, summary + deleted, Toast.LENGTH_LONG).show()
            onSynced()
        }.onFailure { error ->
            toast(R.string.mentra_sync_failed, error.message?.take(140) ?: error.javaClass.simpleName)
        }
    }

    /** User stop: the sync job closes the hotspot link and releases its session itself. */
    fun stop() {
        if (!isActive) return
        cancelled = true
        job?.cancel()
        transferUi.setTransferUiVisible(false)
    }

    /** Activity teardown: cancel without touching the transfer UI. */
    fun cancelForDestroy() {
        if (!isActive) return
        cancelled = true
        job?.cancel()
    }

    private fun toast(@StringRes message: Int, vararg args: Any) {
        Toast.makeText(activity, activity.getString(message, *args), Toast.LENGTH_LONG).show()
    }
}

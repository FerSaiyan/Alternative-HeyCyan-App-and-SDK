package com.fersaiyan.cyanbridge.localmodels.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.fersaiyan.cyanbridge.agent.LocalModelsConfigureActivity
import com.fersaiyan.cyanbridge.localmodels.catalog.LocalModelCatalogRepository
import com.fersaiyan.cyanbridge.localmodels.engine.NpuCatalogVariants
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class ModelDownloadForegroundService : Service() {

    companion object {
        const val TAG = "ModelDownloadSvc"
        const val CHANNEL_ID = "model_download_progress"
        const val NOTIFICATION_ID = 1001

        const val ACTION_DOWNLOAD = "com.fersaiyan.cyanbridge.action.DOWNLOAD_MODEL"
        const val ACTION_CANCEL = "com.fersaiyan.cyanbridge.action.CANCEL_DOWNLOAD"
        const val EXTRA_MODEL_ID = "model_id"
        const val EXTRA_HF_TOKEN = "hf_token"

        const val BROADCAST_DOWNLOAD_FINISHED = "com.fersaiyan.cyanbridge.DOWNLOAD_FINISHED"
        const val BROADCAST_PROGRESS = "com.fersaiyan.cyanbridge.DOWNLOAD_PROGRESS"
        const val EXTRA_SUCCESS = "success"
        const val EXTRA_ERROR = "error"
        const val EXTRA_PERCENT = "percent"
        const val EXTRA_DOWNLOADED_BYTES = "downloaded_bytes"
        const val EXTRA_TOTAL_BYTES = "total_bytes"

        private var _lastResult: DownloadResult? = null
        val lastResult: DownloadResult? get() = _lastResult

        private var _isDownloading: Boolean = false
        val isDownloading: Boolean get() = _isDownloading

        private var _downloadingModelId: String? = null
        val downloadingModelId: String? get() = _downloadingModelId

        private var _lastPercent: Int? = null
        val lastPercent: Int? get() = _lastPercent

        private var _lastDownloadedBytes: Long? = null
        val lastDownloadedBytes: Long? get() = _lastDownloadedBytes

        private var _lastTotalBytes: Long? = null
        val lastTotalBytes: Long? get() = _lastTotalBytes

        private var _lastStatusMessage: String? = null
        val lastStatusMessage: String? get() = _lastStatusMessage

        data class DownloadResult(
            val success: Boolean,
            val modelId: String,
            val error: String?,
        )

        fun startDownload(context: Context, modelId: String, hfToken: String?) {
            val intent = Intent(context, ModelDownloadForegroundService::class.java).apply {
                action = ACTION_DOWNLOAD
                putExtra(EXTRA_MODEL_ID, modelId)
                putExtra(EXTRA_HF_TOKEN, hfToken)
            }
            context.startForegroundService(intent)
        }

        fun cancelDownload(context: Context) {
            val intent = Intent(context, ModelDownloadForegroundService::class.java).apply {
                action = ACTION_CANCEL
            }
            context.startService(intent)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cancelled = AtomicBoolean(false)
    private var downloadJob: Job? = null
    private val downloadManager = LocalModelDownloadManager()
    private var currentModelId: String? = null
    private var lastProgressUpdateMs = 0L

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                Log.i(TAG, "Cancelling download via notification action")
                cancelled.set(true)
                downloadJob?.cancel()
                _isDownloading = false
                _downloadingModelId = null
                _lastPercent = null
                _lastDownloadedBytes = null
                _lastTotalBytes = null
                _lastStatusMessage = null
                currentModelId?.let { modelId ->
                    _lastResult = DownloadResult(success = false, modelId = modelId, error = "cancelled")
                    sendFinishedBroadcast(success = false, error = "cancelled")
                }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            else -> {
                val modelId = intent?.getStringExtra(EXTRA_MODEL_ID) ?: run {
                    stopSelf()
                    return START_NOT_STICKY
                }
                currentModelId = modelId
                _isDownloading = true
                _downloadingModelId = modelId
                _lastPercent = null
                _lastDownloadedBytes = null
                _lastTotalBytes = null
                _lastStatusMessage = "Starting download..."
                cancelled.set(false)
                Log.i(TAG, "Starting download for model: $modelId")

                try {
                    val notification = buildNotification("Starting download...", 0, true)
                    startForeground(NOTIFICATION_ID, notification)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start foreground service", e)
                }

                downloadJob = scope.launch {
                    performDownload(modelId, intent.getStringExtra(EXTRA_HF_TOKEN))
                }
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun performDownload(modelId: String, hfToken: String?) {
        val catalogEntry = LocalModelCatalogRepository.findById(modelId)
        // Per-SoC NPU builds resolve to this phone's file (or fail with the reason).
        val resolution = catalogEntry?.let { NpuCatalogVariants.forDevice(this, it) }
        val entry = resolution?.entry ?: run {
            val reason = resolution?.reason ?: "Model not found in catalog"
            Log.w(TAG, "Model $modelId cannot be downloaded: $reason")
            _lastResult = DownloadResult(false, modelId, reason)
            _isDownloading = false
            _downloadingModelId = null
            sendFinishedBroadcast(success = false, error = reason)
            delay(2000)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        try {
            val model = downloadManager.downloadCatalogModel(
                context = this@ModelDownloadForegroundService,
                entry = entry,
                authToken = hfToken,
                cancelled = cancelled,
                onProgress = { progress ->
                    val now = System.currentTimeMillis()
                    if (now - lastProgressUpdateMs >= 500L || progress.percent >= 100) {
                        lastProgressUpdateMs = now
                        try {
                            val notification = buildNotification(
                                text = "Downloading ${entry.displayName}: ${progress.percent}%",
                                progress = progress.percent,
                                ongoing = true,
                            )
                            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                            nm.notify(NOTIFICATION_ID, notification)
                        } catch (_: Exception) { }

                        sendProgressBroadcast(
                            modelId = entry.id,
                            percent = progress.percent,
                            downloadedBytes = progress.downloadedBytes,
                            totalBytes = progress.totalBytes,
                        )
                    }
                },
            )

            Log.i(TAG, "Download complete: ${model.displayName}")
            _lastResult = DownloadResult(success = true, modelId = modelId, error = null)
            val notification = buildNotification(
                text = "Download complete: ${model.displayName}",
                progress = 100,
                ongoing = false,
            )
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, notification)
            sendFinishedBroadcast(success = true, error = null)
        } catch (err: Throwable) {
            if (err is CancellationException || cancelled.get()) {
                Log.i(TAG, "Download cancelled")
                _lastResult = DownloadResult(success = false, modelId = modelId, error = "cancelled")
            } else {
                Log.e(TAG, "Download failed: ${err.message}", err)
                _lastResult = DownloadResult(success = false, modelId = modelId, error = err.message)
                val notification = buildNotification(
                    text = "Download failed: ${err.message}",
                    progress = 0,
                    ongoing = false,
                )
                val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(NOTIFICATION_ID, notification)
                sendFinishedBroadcast(success = false, error = err.message)
            }
        }

        _isDownloading = false
        _downloadingModelId = null

        delay(3000)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(text: String, progress: Int, ongoing: Boolean): android.app.Notification {
        val tapIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, LocalModelsConfigureActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val cancelIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, ModelDownloadForegroundService::class.java).apply {
                action = ACTION_CANCEL
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Model Download")
            .setContentText(text)
            .setOngoing(ongoing)
            .setProgress(100, progress, progress == 0 && ongoing)
            .setContentIntent(tapIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancelIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Model Downloads",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shows model download progress"
                setShowBadge(false)
            }
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun sendProgressBroadcast(modelId: String, percent: Int, downloadedBytes: Long, totalBytes: Long) {
        _lastPercent = percent
        _lastDownloadedBytes = downloadedBytes
        _lastTotalBytes = totalBytes
        val entryName = LocalModelCatalogRepository.findById(modelId)?.displayName ?: modelId
        _lastStatusMessage = "Downloading $entryName: $percent%"

        val intent = Intent(BROADCAST_PROGRESS).apply {
            `package` = packageName
            putExtra(EXTRA_MODEL_ID, modelId)
            putExtra(EXTRA_PERCENT, percent)
            putExtra(EXTRA_DOWNLOADED_BYTES, downloadedBytes)
            putExtra(EXTRA_TOTAL_BYTES, totalBytes)
        }
        sendBroadcast(intent)
    }

    private fun sendFinishedBroadcast(success: Boolean, error: String?) {
        val intent = Intent(BROADCAST_DOWNLOAD_FINISHED).apply {
            `package` = packageName
            putExtra(EXTRA_SUCCESS, success)
            currentModelId?.let { putExtra(EXTRA_MODEL_ID, it) }
            error?.let { putExtra(EXTRA_ERROR, it) }
        }
        sendBroadcast(intent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        cancelled.set(true)
        downloadJob?.cancel()
        _isDownloading = false
        _downloadingModelId = null
        scope.cancel()
        super.onDestroy()
    }
}

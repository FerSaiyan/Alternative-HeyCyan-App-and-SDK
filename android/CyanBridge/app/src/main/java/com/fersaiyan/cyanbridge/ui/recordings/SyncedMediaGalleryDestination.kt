package com.fersaiyan.cyanbridge.ui.recordings

import com.fersaiyan.cyanbridge.ui.nav.NavScreen
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Size
import android.widget.Toast
import com.fersaiyan.cyanbridge.ui.nav.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.fersaiyan.cyanbridge.R
import com.fersaiyan.cyanbridge.media.SyncedMediaFolder
import com.fersaiyan.cyanbridge.shared.recordings.SyncedMediaItem
import com.fersaiyan.cyanbridge.shared.ui.recordings.SyncedMediaGalleryScreen
import com.fersaiyan.cyanbridge.shared.ui.recordings.SyncedMediaGalleryViewModel
import com.fersaiyan.cyanbridge.shared.ui.recordings.SyncedMediaSource
import com.fersaiyan.cyanbridge.ui.showToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SyncedMediaGalleryDestination : NavScreen() {
    private val viewModel by viewModels<SyncedMediaGalleryViewModel> {
        viewModelFactory { initializer { SyncedMediaGalleryViewModel(MediaStoreSyncedMedia(applicationContext)) } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch { viewModel.messages.collect { showToast(it) } }
        setContent {
            SyncedMediaGalleryScreen(
                mediaItems = viewModel.items,
                isLoading = viewModel.isLoading,
                folderHint = getString(R.string.synced_media_folder_hint, SyncedMediaFolder.relativePath),
                loadThumbnail = ::loadThumbnail,
                onNavigateBack = ::finish,
                onRefresh = viewModel::refresh,
                onOpenMedia = ::openMediaItem,
                onShareItems = ::shareMediaItems,
                onDeleteItems = viewModel::delete,
            )
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.refresh()
    }

    override fun onStop() {
        viewModel.stopLoading()
        super.onStop()
    }

    private suspend fun loadThumbnail(contentUriString: String): ImageBitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return withContext(Dispatchers.IO) {
            runCatching {
                contentResolver.loadThumbnail(android.net.Uri.parse(contentUriString), Size(420, 420), null)
            }.getOrNull()?.asImageBitmap()
        }
    }

    private fun openMediaItem(item: SyncedMediaItem) {
        val uri = android.net.Uri.parse(item.contentUriString)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, if (item.isVideo) "video/*" else "image/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(intent) }
            .onFailure { Toast.makeText(this, getString(R.string.synced_media_open_failed), Toast.LENGTH_SHORT).show() }
    }

    private fun shareMediaItems(items: List<SyncedMediaItem>) {
        if (items.isEmpty()) return
        val uris = items.map { android.net.Uri.parse(it.contentUriString) }
        val sendIntent = if (uris.size == 1) {
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uris[0], if (uris[0].lastPathSegment?.endsWith(".mp4") == true) "video/*" else "image/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                setType("image/* video/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        runCatching { startActivity(Intent.createChooser(sendIntent, null)) }
            .onFailure { Toast.makeText(this, "Failed to share items", Toast.LENGTH_SHORT).show() }
    }
}

/** The synced folder in MediaStore. */
private class MediaStoreSyncedMedia(private val context: Context) : SyncedMediaSource {
    override suspend fun list(): List<SyncedMediaItem> = withContext(Dispatchers.IO) { SyncedMediaQuery.query(context) }

    override suspend fun delete(items: List<SyncedMediaItem>): List<SyncedMediaItem> = withContext(Dispatchers.IO) {
        items.filter { item ->
            runCatching { context.contentResolver.delete(android.net.Uri.parse(item.contentUriString), null, null) > 0 }
                .getOrDefault(false)
        }
    }
}

package com.fersaiyan.cyanbridge.ui.localagent

import android.content.ContentUris
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.fersaiyan.cyanbridge.media.SyncedMediaFolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

private data class MediaItem(
    val id: Long,
    val contentUri: android.net.Uri,
    val displayName: String,
    val mimeType: String,
    val isVideo: Boolean,
    val takenAtMs: Long,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncedMediaGalleryScreen(
    onNavigateBack: () -> Unit = {},
) {
    val context = LocalContext.current

    var mediaItems by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        isLoading = true
        val items = withContext(Dispatchers.IO) {
            queryMedia(context)
        }
        mediaItems = items
        isLoading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Synced Media") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = "Close",
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else if (mediaItems.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "No synced media",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "Synced photos and videos will appear here",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(mediaItems, key = { "${it.id}_${it.isVideo}" }) { item ->
                    MediaThumbnailCard(
                        item = item,
                        onClick = {
                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(
                                    item.contentUri,
                                    if (item.isVideo) "video/*" else "image/*",
                                )
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            runCatching { context.startActivity(intent) }
                                .onFailure {
                                    Toast.makeText(context, "Cannot open media", Toast.LENGTH_SHORT).show()
                                }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun MediaThumbnailCard(
    item: MediaItem,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    var thumbnail by remember { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(item.id) {
        val bmp = withContext(Dispatchers.IO) {
            loadThumbnail(context, item)
        }
        thumbnail = bmp
    }

    Card(
        modifier = Modifier
            .aspectRatio(1f)
            .clickable(onClick = onClick),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            val bmp = thumbnail
            if (bmp != null) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = item.displayName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.padding(16.dp))
                }
            }

            if (item.isVideo) {
                Text(
                    text = "\u25B6",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(4.dp),
                )
            }

            val dateStr = remember(item.takenAtMs) {
                if (item.takenAtMs > 0L) {
                    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                        .format(Date(item.takenAtMs))
                } else ""
            }

            if (dateStr.isNotEmpty()) {
                Text(
                    text = dateStr,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(4.dp),
                )
            }
        }
    }
}

private fun loadThumbnail(context: android.content.Context, item: MediaItem): Bitmap? {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        runCatching {
            return context.contentResolver.loadThumbnail(item.contentUri, Size(420, 420), null)
        }
    }

    if (item.isVideo) {
        runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, item.contentUri)
                return retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } finally {
                retriever.release()
            }
        }
    }

    runCatching {
        context.contentResolver.openInputStream(item.contentUri)?.use { input ->
            return BitmapFactory.decodeStream(input)
        }
    }

    return null
}

private fun queryMedia(context: android.content.Context): List<MediaItem> {
    val items = mutableListOf<MediaItem>()

    val projection = mutableListOf(
        MediaStore.MediaColumns._ID,
        MediaStore.MediaColumns.DISPLAY_NAME,
        MediaStore.MediaColumns.MIME_TYPE,
        MediaStore.MediaColumns.DATE_TAKEN,
        MediaStore.MediaColumns.DATE_ADDED,
        MediaStore.Files.FileColumns.MEDIA_TYPE,
    )

    val selection: String
    val selectionArgs: Array<String>

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        projection += MediaStore.MediaColumns.RELATIVE_PATH
        selection = buildString {
            append("(")
            append(MediaStore.Files.FileColumns.MEDIA_TYPE)
            append("=? OR ")
            append(MediaStore.Files.FileColumns.MEDIA_TYPE)
            append("=?) AND (")
            append(MediaStore.MediaColumns.RELATIVE_PATH)
            append("=? OR ")
            append(MediaStore.MediaColumns.RELATIVE_PATH)
            append("=? OR ")
            append(MediaStore.MediaColumns.RELATIVE_PATH)
            append(" LIKE ?)")
        }
        selectionArgs = arrayOf(
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
            SyncedMediaFolder.relativePath,
            SyncedMediaFolder.relativePathWithTrailingSlash,
            SyncedMediaFolder.relativePathLikePattern(),
        )
    } else {
        @Suppress("DEPRECATION")
        projection += MediaStore.MediaColumns.DATA
        selection = buildString {
            append("(")
            append(MediaStore.Files.FileColumns.MEDIA_TYPE)
            append("=? OR ")
            append(MediaStore.Files.FileColumns.MEDIA_TYPE)
            append("=?) AND ")
            append(MediaStore.MediaColumns.DATA)
            append(" LIKE ?")
        }
        selectionArgs = arrayOf(
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
            SyncedMediaFolder.legacyAbsolutePathLikePattern(),
        )
    }

    val sortOrder = "${MediaStore.MediaColumns.DATE_TAKEN} DESC, ${MediaStore.MediaColumns.DATE_ADDED} DESC"

    val uri = MediaStore.Files.getContentUri("external")
    context.contentResolver.query(uri, projection.toTypedArray(), selection, selectionArgs, sortOrder)
        ?.use { cursor ->
            val idIdx = cursor.getColumnIndex(MediaStore.MediaColumns._ID)
            val nameIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
            val mimeIdx = cursor.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
            val dateTakenIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_TAKEN)
            val dateAddedIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED)
            val typeIdx = cursor.getColumnIndex(MediaStore.Files.FileColumns.MEDIA_TYPE)

            while (cursor.moveToNext()) {
                if (idIdx < 0 || typeIdx < 0) continue

                val mediaType = cursor.getInt(typeIdx)
                val isVideo = mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                val isImage = mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE
                if (!isVideo && !isImage) continue

                val id = cursor.getLong(idIdx)
                val contentUri = if (isVideo) {
                    ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                } else {
                    ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                }

                val name = if (nameIdx >= 0 && !cursor.isNull(nameIdx)) {
                    cursor.getString(nameIdx)
                } else {
                    "media_$id"
                }

                val mime = if (mimeIdx >= 0 && !cursor.isNull(mimeIdx)) {
                    cursor.getString(mimeIdx)
                } else {
                    if (isVideo) "video/mp4" else "image/jpeg"
                }

                val dateTakenMs = if (dateTakenIdx >= 0 && !cursor.isNull(dateTakenIdx)) {
                    cursor.getLong(dateTakenIdx)
                } else {
                    0L
                }

                val dateAddedMs = if (dateAddedIdx >= 0 && !cursor.isNull(dateAddedIdx)) {
                    cursor.getLong(dateAddedIdx) * 1000L
                } else {
                    0L
                }

                items += MediaItem(
                    id = id,
                    contentUri = contentUri,
                    displayName = name,
                    mimeType = mime,
                    isVideo = isVideo,
                    takenAtMs = if (dateTakenMs > 0L) dateTakenMs else dateAddedMs,
                )
            }
        }

    return items
}

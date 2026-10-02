package com.fersaiyan.cyanbridge.shared.platform

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image
import platform.AVFoundation.AVAssetImageGenerator
import platform.AVFoundation.AVURLAsset
import platform.CoreGraphics.CGSizeMake
import platform.CoreMedia.CMTimeMake
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToFile
import platform.Photos.PHAccessLevelAddOnly
import platform.Photos.PHAssetChangeRequest
import platform.Photos.PHAuthorizationStatusAuthorized
import platform.Photos.PHAuthorizationStatusLimited
import platform.Photos.PHPhotoLibrary
import platform.QuickLook.QLPreviewController
import platform.QuickLook.QLPreviewControllerDataSourceProtocol
import platform.QuickLook.QLPreviewItemProtocol
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIAlertAction
import platform.UIKit.UIAlertActionStyleCancel
import platform.UIKit.UIAlertActionStyleDefault
import platform.UIKit.UIAlertActionStyleDestructive
import platform.UIKit.UIAlertController
import platform.UIKit.UIAlertControllerStyleAlert
import platform.UIKit.UIApplication
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import platform.UIKit.UIPasteboard
import platform.UIKit.UIViewController
import platform.UIKit.popoverPresentationController
import platform.darwin.NSInteger
import platform.darwin.NSObject
import kotlin.coroutines.resume

/** UIKit, Photos and QuickLook helpers used by the iOS host for media features. */
@OptIn(ExperimentalForeignApi::class)
object IosMediaPlatform {
    private const val THUMBNAIL_SIZE = 320.0
    private var previewDataSource: MediaPreviewDataSource? = null

    /** Installs the shared gallery hooks; Android leaves them unset. */
    fun installSharedMediaHooks() {
        SharedMediaHooks.loadThumbnail = ::loadThumbnail
        SharedMediaHooks.loadImage = ::loadImage
        SharedMediaHooks.saveDocument = { name, bytes ->
            val path = PlatformFilePaths.dataDirectory() + "/" + name
            if (writeFile(path, bytes)) path else null
        }
        SharedMediaHooks.openMedia = ::openMedia
        SharedMediaHooks.shareMedia = ::shareMedia
        SharedMediaHooks.deleteMediaFiles = { paths -> paths.forEach(::deleteFile) }
    }

    suspend fun loadThumbnail(path: String): ImageBitmap? = withContext(Dispatchers.Default) {
        val image = if (isVideo(path)) videoFrame(path) else UIImage.imageWithContentsOfFile(path)
        val thumbnail = image?.imageByPreparingThumbnailOfSize(CGSizeMake(THUMBNAIL_SIZE, THUMBNAIL_SIZE)) ?: image
        val jpeg = thumbnail?.let { UIImageJPEGRepresentation(it, 0.8) } ?: return@withContext null
        runCatching { Image.makeFromEncoded(jpeg.toKotlinBytes()).toComposeImageBitmap() }.getOrNull()
    }

    suspend fun loadImage(path: String): ImageBitmap? = withContext(Dispatchers.Default) {
        val data = NSData.dataWithContentsOfFile(path) ?: return@withContext null
        val jpeg = IosChatPlatform.jpegForUpload(data) ?: return@withContext null
        runCatching { Image.makeFromEncoded(jpeg).toComposeImageBitmap() }.getOrNull()
    }

    fun openMedia(path: String) {
        val presenter = topViewController() ?: return
        val dataSource = MediaPreviewDataSource(NSURL.fileURLWithPath(path))
        previewDataSource = dataSource // QLPreviewController holds its data source weakly.
        val preview = QLPreviewController()
        preview.dataSource = dataSource
        presenter.presentViewController(preview, animated = true, completion = null)
    }

    fun shareMedia(paths: List<String>) {
        if (paths.isEmpty()) return
        val presenter = topViewController() ?: return
        val items = paths.map { NSURL.fileURLWithPath(it) }
        val share = UIActivityViewController(activityItems = items, applicationActivities = null)
        share.popoverPresentationController?.sourceView = presenter.view
        presenter.presentViewController(share, animated = true, completion = null)
    }

    fun deleteFile(path: String) {
        NSFileManager.defaultManager.removeItemAtPath(path, error = null)
    }

    /** Adds a downloaded photo or video to the user's library (add-only permission). */
    suspend fun saveToPhotoLibrary(path: String, isVideo: Boolean): Boolean = suspendCancellableCoroutine { continuation ->
        PHPhotoLibrary.requestAuthorizationForAccessLevel(PHAccessLevelAddOnly) { status ->
            if (status != PHAuthorizationStatusAuthorized && status != PHAuthorizationStatusLimited) {
                if (continuation.isActive) continuation.resume(false)
                return@requestAuthorizationForAccessLevel
            }
            PHPhotoLibrary.sharedPhotoLibrary().performChanges(
                changeBlock = {
                    val url = NSURL.fileURLWithPath(path)
                    if (isVideo) {
                        PHAssetChangeRequest.creationRequestForAssetFromVideoAtFileURL(url)
                    } else {
                        PHAssetChangeRequest.creationRequestForAssetFromImageAtFileURL(url)
                    }
                },
                completionHandler = { success, _ -> if (continuation.isActive) continuation.resume(success) },
            )
        }
    }

    /** Native confirmation dialog; returns true when the destructive action is chosen. */
    suspend fun confirm(title: String, message: String, confirmTitle: String, cancelTitle: String): Boolean =
        suspendCancellableCoroutine { continuation ->
            val presenter = topViewController()
            if (presenter == null) {
                continuation.resume(false)
                return@suspendCancellableCoroutine
            }
            val alert = UIAlertController.alertControllerWithTitle(title, message, UIAlertControllerStyleAlert)
            alert.addAction(
                UIAlertAction.actionWithTitle(cancelTitle, UIAlertActionStyleCancel) { _ ->
                    if (continuation.isActive) continuation.resume(false)
                },
            )
            alert.addAction(
                UIAlertAction.actionWithTitle(confirmTitle, UIAlertActionStyleDestructive) { _ ->
                    if (continuation.isActive) continuation.resume(true)
                },
            )
            presenter.presentViewController(alert, animated = true, completion = null)
        }

    /** Shows a message with a button that copies [copyValue] (for example a hotspot password). */
    fun showCopyAlert(title: String, message: String, copyTitle: String, copyValue: String) {
        val presenter = topViewController() ?: return
        val alert = UIAlertController.alertControllerWithTitle(title, message, UIAlertControllerStyleAlert)
        alert.addAction(UIAlertAction.actionWithTitle("OK", UIAlertActionStyleCancel, handler = null))
        alert.addAction(
            UIAlertAction.actionWithTitle(copyTitle, UIAlertActionStyleDefault) { _ ->
                UIPasteboard.generalPasteboard.string = copyValue
            },
        )
        presenter.presentViewController(alert, animated = true, completion = null)
    }

    fun readFile(path: String): ByteArray? = NSData.dataWithContentsOfFile(path)?.toKotlinBytes()

    fun writeFile(path: String, bytes: ByteArray): Boolean = bytes.toNSData().writeToFile(path, atomically = true)

    fun isVideo(path: String): Boolean = path.endsWith(".mp4", ignoreCase = true) || path.endsWith(".mov", ignoreCase = true)

    fun isImage(path: String): Boolean =
        path.endsWith(".jpg", ignoreCase = true) || path.endsWith(".jpeg", ignoreCase = true) ||
            path.endsWith(".png", ignoreCase = true)

    private fun videoFrame(path: String): UIImage? {
        val generator = AVAssetImageGenerator(asset = AVURLAsset(uRL = NSURL.fileURLWithPath(path), options = null))
        generator.appliesPreferredTrackTransform = true
        val frame = generator.copyCGImageAtTime(CMTimeMake(value = 0, timescale = 1), actualTime = null, error = null)
            ?: return null
        return UIImage.imageWithCGImage(frame)
    }

    internal fun topViewController(): UIViewController? {
        @Suppress("DEPRECATION")
        var controller = UIApplication.sharedApplication.keyWindow?.rootViewController ?: return null
        while (true) {
            controller = controller.presentedViewController ?: return controller
        }
    }
}

private class MediaPreviewDataSource(private val url: NSURL) : NSObject(), QLPreviewControllerDataSourceProtocol {
    override fun numberOfPreviewItemsInPreviewController(controller: QLPreviewController): NSInteger = 1

    // NSURL adopts QLPreviewItem only through an Objective-C category, so casting it in Kotlin
    // throws (the chat photo tap crashed); hand QuickLook an explicit item instead.
    private val item = MediaPreviewItem(url)

    override fun previewController(controller: QLPreviewController, previewItemAtIndex: NSInteger): QLPreviewItemProtocol = item
}

private class MediaPreviewItem(private val url: NSURL) : NSObject(), QLPreviewItemProtocol {
    override fun previewItemURL(): NSURL? = url

    override fun previewItemTitle(): String? = url.lastPathComponent
}

@OptIn(ExperimentalForeignApi::class)
internal fun NSData.toKotlinBytes(): ByteArray {
    val size = length.toInt()
    if (size == 0) return ByteArray(0)
    return bytes!!.reinterpret<ByteVar>().readBytes(size)
}

@OptIn(ExperimentalForeignApi::class)
internal fun ByteArray.toNSData(): NSData {
    if (isEmpty()) return NSData()
    return usePinned { pinned -> NSData.create(bytes = pinned.addressOf(0), length = size.toULong()) }
}

package com.fersaiyan.cyanbridge.plugins.orallesion

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.fersaiyan.cyanbridge.BuildConfig
import com.fersaiyan.cyanbridge.ai.questions.AssistantSpeaker
import com.fersaiyan.cyanbridge.bridge.core.DisplayCommand
import com.fersaiyan.cyanbridge.bridge.core.GlassesBridge
import com.fersaiyan.cyanbridge.bridge.core.GlassesCapability
import com.fersaiyan.cyanbridge.devices.DeviceProfileStore
import com.fersaiyan.cyanbridge.devices.metarayban.MetaRaybanManager
import com.fersaiyan.cyanbridge.glasses.GlassesAiPhotoCapture
import com.fersaiyan.cyanbridge.plugins.PluginVoiceRecognizer
import com.fersaiyan.cyanbridge.plugins.settings.nativePluginViewModel
import com.fersaiyan.cyanbridge.shared.devices.GlassesFamilyProfiles
import com.fersaiyan.cyanbridge.shared.plugins.orallesion.OralLesionBenchCase
import com.fersaiyan.cyanbridge.shared.plugins.orallesion.OralLesionBenchScreen
import com.fersaiyan.cyanbridge.shared.plugins.orallesion.OralLesionBenchViewModel
import com.fersaiyan.cyanbridge.shared.plugins.orallesion.OralLesionDeviceInfo
import com.fersaiyan.cyanbridge.shared.plugins.orallesion.OralLesionModel
import com.fersaiyan.cyanbridge.shared.plugins.orallesion.OralLesionModelState
import com.fersaiyan.cyanbridge.shared.plugins.orallesion.OralLesionPlatform
import com.fersaiyan.cyanbridge.shared.plugins.orallesion.OralLesionTurn
import com.fersaiyan.cyanbridge.shared.prefs.PluginPrefs
import com.fersaiyan.cyanbridge.ui.nav.AppRoute
import com.fersaiyan.cyanbridge.ui.nav.NavScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File

/** Oral Lesion Bench (experimental): research use only, Android only, off by default. */
class OralLesionBenchDestination : NavScreen() {
    private var pendingPhoto: File? = null
    private var pendingPhotoResult: ((String?) -> Unit)? = null
    private var pendingGalleryResult: ((String?) -> Unit)? = null
    private var pendingMicResult: ((Boolean) -> Unit)? = null

    private val cameraLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val file = pendingPhoto
        val result = pendingPhotoResult
        pendingPhoto = null
        pendingPhotoResult = null
        result?.invoke(file?.takeIf { saved && it.isFile && it.length() > 0 }?.absolutePath)
    }

    private val galleryLauncher = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val result = pendingGalleryResult
        pendingGalleryResult = null
        result?.invoke(uri?.let(::copyToCache))
    }

    private val micLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        pendingMicResult?.invoke(granted)
        pendingMicResult = null
    }

    private val viewModel by nativePluginViewModel(PluginPrefs.OralLesionBench) { store, catalog, host ->
        OralLesionBenchViewModel(store, catalog, host, AndroidOralLesionPlatform(applicationContext))
    }

    private val platform: AndroidOralLesionPlatform? get() = AndroidOralLesionPlatform.current

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.refreshModels()
        platform?.screen = this
        setContent {
            OralLesionBenchScreen(
                viewModel = viewModel,
                glassesPhotoUnavailableReason = platform?.glassesPhotoUnavailableReason,
                photo = { path -> CasePhoto(path) },
                onBack = ::finish,
            )
        }
    }

    override fun onResume() {
        super.onResume()
        platform?.screen = this
        viewModel.refreshModels()
    }

    override fun onDestroy() {
        if (platform?.screen === this) platform?.screen = null
        super.onDestroy()
    }

    internal fun takePhoto(onResult: (String?) -> Unit) {
        val file = File(File(cacheDir, "oral_lesion").apply { mkdirs() }, "camera_${System.currentTimeMillis()}.jpg")
        pendingPhoto = file
        pendingPhotoResult = onResult
        cameraLauncher.launch(FileProvider.getUriForFile(this, "$packageName.fileprovider", file))
    }

    internal fun pickPhoto(onResult: (String?) -> Unit) {
        pendingGalleryResult = onResult
        galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    internal fun requestMicrophone(onResult: (Boolean) -> Unit) {
        pendingMicResult = onResult
        micLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun copyToCache(uri: Uri): String? = runCatching {
        val file = File(File(cacheDir, "oral_lesion").apply { mkdirs() }, "gallery_${System.currentTimeMillis()}.jpg")
        contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { input.copyTo(it) } } ?: return null
        file.absolutePath
    }.getOrNull()
}

@Composable
private fun CasePhoto(path: String) {
    val bitmap = remember(path) {
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            var sample = 1
            while (bounds.outWidth / sample > 1_024 || bounds.outHeight / sample > 1_024) sample *= 2
            BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
        }.getOrNull()
    }
    bitmap?.let {
        Image(it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp))
    }
}

/** The Android side of the bench. One instance per screen ViewModel; [screen] is the visible screen, if any. */
internal class AndroidOralLesionPlatform(context: Context) : OralLesionPlatform {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val engine = OralLesionEngine(appContext)
    private val store = OralLesionBenchStore(appContext)
    private val speaker = AssistantSpeaker(appContext).also { it.start(appContext) }
    private var recognizer: PluginVoiceRecognizer? = null
    @Volatile var screen: OralLesionBenchDestination? = null

    init {
        current = this
    }

    override val glassesPhotoUnavailableReason: String?
        get() {
            if (DeviceProfileStore.isMetaSelected(appContext)) return null
            val profile = GlassesFamilyProfiles.forClass(DeviceProfileStore.selectedClass(appContext))
            return if (profile.supports(GlassesCapability.AI_PHOTO_CAPTURE)) null
            else profile.cameraUnavailableReason ?: "The selected glasses cannot take a photo for the bench."
        }

    override val deviceInfo = OralLesionDeviceInfo(
        soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else Build.HARDWARE,
        device = "${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})",
        appVersion = BuildConfig.VERSION_NAME,
    )

    override suspend fun modelState(model: OralLesionModel): OralLesionModelState = OralLesionModelInstaller.state(appContext, model)

    override suspend fun installModel(model: OralLesionModel, onState: (OralLesionModelState) -> Unit) =
        OralLesionModelInstaller.install(appContext, model, onState)

    override suspend fun captureGlassesPhoto(): String = withContext(Dispatchers.IO) {
        if (DeviceProfileStore.isMetaSelected(appContext)) {
            val manager = MetaRaybanManager.getInstance(appContext)
            if (!manager.isInitialized.value) manager.initialize()
            return@withContext manager.savePhotoForProcessing(manager.capturePhotoOnce(), "ORAL_LESION").absolutePath
        }
        glassesPhotoUnavailableReason?.let { error(it) }
        val photo = GlassesAiPhotoCapture(appContext).capture(quality = GLASSES_PHOTO_QUALITY)
        val file = File(File(appContext.cacheDir, "oral_lesion").apply { mkdirs() }, "glasses_${System.currentTimeMillis()}.jpg")
        file.writeBytes(photo.jpeg)
        file.absolutePath
    }

    override fun takeCameraPhoto(onResult: (String?) -> Unit) {
        screen?.takePhoto(onResult) ?: onResult(null)
    }

    override fun pickGalleryPhoto(onResult: (String?) -> Unit) {
        screen?.pickPhoto(onResult) ?: onResult(null)
    }

    override suspend fun startConversation(
        modelPath: String,
        imagePath: String,
        system: String,
        user: String,
        onToken: (String) -> Unit,
    ): OralLesionTurn = engine.start(modelPath, imagePath, system, user, onToken)

    override suspend fun continueConversation(user: String, onToken: (String) -> Unit): OralLesionTurn =
        engine.continueConversation(user, onToken)

    override fun cancelGeneration() = engine.cancel()

    override fun speak(text: String, onDone: () -> Unit) {
        speaker.speak(text, languageTag = LANGUAGE, utteranceId = "oral_lesion_${System.nanoTime()}", onDone = { scope.launch { onDone() } })
    }

    /** A blank utterance flushes the queue; the stopped one does not run its completion. */
    override fun stopSpeaking() {
        speaker.speak(" ", languageTag = LANGUAGE, utteranceId = "oral_lesion_stop")
    }

    override fun showOnGlasses(text: String) {
        val profile = GlassesFamilyProfiles.forClass(DeviceProfileStore.selectedClass(appContext))
        if (!profile.showsAssistantAnswers || text.isBlank()) return
        scope.launch(Dispatchers.IO) {
            GlassesBridge.showText(DisplayCommand.Text(text)).onFailure { Log.w(TAG, "Not shown on the glasses", it) }
        }
    }

    override fun listen(onPartial: (String) -> Unit, onFinal: (String) -> Unit, onError: (String) -> Unit) {
        val granted = ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            val host = screen ?: return onError("Microphone permission is needed")
            host.requestMicrophone { ok -> if (ok) listen(onPartial, onFinal, onError) else onError("Microphone permission was denied") }
            return
        }
        stopListening()
        recognizer = PluginVoiceRecognizer(appContext, LANGUAGE, onPartial, onFinal, onError).also { it.start() }
    }

    override fun stopListening() {
        recognizer?.stop()
        recognizer = null
    }

    override suspend fun saveCase(case: OralLesionBenchCase, photoPath: String?): OralLesionBenchCase =
        withContext(Dispatchers.IO) { store.save(case, photoPath) }

    override suspend fun cases(): List<OralLesionBenchCase> = withContext(Dispatchers.IO) { store.all() }

    override suspend fun deleteCase(caseId: String) = withContext(Dispatchers.IO) { store.delete(caseId) }

    override fun exportCases() {
        scope.launch {
            val zip = runCatching { withContext(Dispatchers.IO) { store.exportZip() } }.getOrElse {
                Toast.makeText(appContext, "Export failed: ${it.message}", Toast.LENGTH_LONG).show()
                return@launch
            } ?: return@launch
            val uri = FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", zip)
            val send = Intent(Intent.ACTION_SEND).setType("application/zip").putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val chooser = Intent.createChooser(send, "Oral Lesion Bench").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            (screen?.activity ?: appContext).startActivity(chooser)
        }
    }

    override fun openUrl(url: String) {
        runCatching {
            appContext.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { Toast.makeText(appContext, url, Toast.LENGTH_LONG).show() }
    }

    override fun openModelSettings() {
        appContext.startActivity(AppRoute.LOCAL_MODELS.intent(appContext).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun release() {
        stopListening()
        speaker.shutdown()
        runBlocking(Dispatchers.IO) { runCatching { engine.close() } }
        scope.cancel()
        if (current === this) current = null
    }

    companion object {
        private const val TAG = "OralLesionBench"
        private const val LANGUAGE = "pt-BR"
        /** HeyCyan AI-photo quality 0..5: the largest picture. */
        private const val GLASSES_PHOTO_QUALITY = 5

        /** The platform of the open bench ViewModel, so a recreated screen can attach to it. */
        @Volatile var current: AndroidOralLesionPlatform? = null
            private set
    }
}

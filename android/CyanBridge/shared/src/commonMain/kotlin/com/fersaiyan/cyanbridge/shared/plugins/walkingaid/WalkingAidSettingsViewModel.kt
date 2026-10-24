package com.fersaiyan.cyanbridge.shared.plugins.walkingaid

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fersaiyan.cyanbridge.shared.platform.PlatformPreferences
import com.fersaiyan.cyanbridge.shared.plugins.NativePluginIds
import com.fersaiyan.cyanbridge.shared.prefs.PluginPrefs
import com.fersaiyan.cyanbridge.shared.prefs.PluginPrefs.WalkingAid
import com.fersaiyan.cyanbridge.shared.prefs.PrefValue
import com.fersaiyan.cyanbridge.shared.prefs.get
import com.fersaiyan.cyanbridge.shared.prefs.set
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** A cloud model the relay offers for scene descriptions or depth. */
data class WalkingAidModelOption(val id: String, val label: String)

/** Progress of a model download, worded by the screen. */
sealed interface WalkingAidModelDownload {
    data class Starting(val modelName: String) : WalkingAidModelDownload
    data class Downloading(val percent: Int) : WalkingAidModelDownload
    data class Ready(val modelName: String) : WalkingAidModelDownload
    data class Failed(val message: String) : WalkingAidModelDownload
}

/** Short notices the platform shows (a toast on Android); a null detail reads as "unknown error". */
enum class WalkingAidSettingsMessage { LATENCY_TEST_FAILED, ACQUISITION_TEST_FAILED, MODELS_LOAD_FAILED }

/** The device side of the Walking Aid settings: camera, service, models, benchmarks and relay. */
interface WalkingAidSettingsPlatform {
    val hasCamera: Boolean
    val cameraUnavailableReason: String?

    fun isServiceRunning(): Boolean
    fun checkReadiness(): WalkingAidReadinessResult
    suspend fun installModel(entryId: String, onProgress: (percent: Int) -> Unit)

    /** Captures fresh glasses thumbnails and times the local detector; returns the report text. */
    suspend fun runLatencyBenchmark(depthEnabled: Boolean, depthSource: String, focusDescription: String): String

    /** Times thumbnail captures at every quality level; returns the report text. */
    suspend fun runAcquisitionBenchmark(): String
    suspend fun translateFocusToEnglish(original: String, source: String): String
    suspend fun fetchCloudModels(): List<WalkingAidModelOption>

    /** Deletes the image history and confirms it to the user. */
    fun clearImageHistory()
    fun showMessage(message: WalkingAidSettingsMessage, detail: String?)
}

/**
 * State and actions of the Walking Aid settings screen. Settings are saved to [store] (the Walking
 * Aid preference file) as they change; starting the service is left to the host, which may need
 * permission prompts first.
 */
class WalkingAidSettingsViewModel(
    private val store: PlatformPreferences,
    private val catalog: PlatformPreferences,
    private val platform: WalkingAidSettingsPlatform,
) : ViewModel() {
    val hasCamera = platform.hasCamera
    val cameraUnavailableReason = platform.cameraUnavailableReason

    var enabled by mutableStateOf(store[WalkingAid.enabled])
        private set
    var running by mutableStateOf(platform.isServiceRunning())
        private set
    var videoMode by mutableStateOf(WalkingAidSettings.videoMode(store))
        private set
    var aidMode by mutableStateOf(WalkingAidSettings.mode(store))
        private set
    var captureInterval by mutableIntStateOf(store[WalkingAid.captureIntervalSeconds])
        private set
    var imageDescriptionSource by mutableStateOf(store[WalkingAid.imageDescriptionSource])
        private set
    var imageDescriptionCloudModelId by mutableStateOf(store[WalkingAid.imageDescriptionCloudModelId])
        private set
    var depthEnabled by mutableStateOf(store[WalkingAid.depthEnabled])
        private set
    var depthSource by mutableStateOf(store[WalkingAid.depthSource])
        private set
    var depthCloudModelId by mutableStateOf(store[WalkingAid.depthCloudModelId])
        private set
    var ttsEnabled by mutableStateOf(store[WalkingAid.ttsEnabled])
        private set
    var speechRate by mutableFloatStateOf(store[WalkingAid.speechRate])
        private set
    var announcementPause by mutableFloatStateOf(store[WalkingAid.announcementPauseMs] / 1000f)
        private set
    var safetyDisclaimerEnabled by mutableStateOf(store[WalkingAid.safetyDisclaimerEnabled])
        private set
    var historyMaxCount by mutableIntStateOf(store[WalkingAid.imageHistoryMaxCount])
        private set
    var customPrompt by mutableStateOf(store[WalkingAid.customPrompt])
        private set
    var yoloModelType by mutableStateOf(store[WalkingAid.yoloModelType])
        private set
    var thumbnailQuality by mutableIntStateOf(store[WalkingAid.thumbnailQualityLevel])
        private set
    var settleDelayMs by mutableIntStateOf(store[WalkingAid.photoSettleDelayMs])
        private set

    // Attention focus: the text being edited, and the saved original with its English translation.
    var focusDescriptionText by mutableStateOf(WalkingAidSettings.focusDescription(store))
        private set
    var savedFocusOriginal by mutableStateOf(WalkingAidSettings.focusDescription(store))
        private set
    var savedModelFocusText by mutableStateOf(WalkingAidSettings.modelFocusDescription(store))
        private set
    var focusTranslationSource by mutableStateOf(store[WalkingAid.stateModelSource])
        private set
    var savedFocusTranslationSource by mutableStateOf(store[WalkingAid.stateModelSource])
        private set
    var isTranslatingFocus by mutableStateOf(false)
        private set
    var focusTranslationError by mutableStateOf<String?>(null)
        private set

    var isTestingLatency by mutableStateOf(false)
        private set
    var latencyReport by mutableStateOf<String?>(null)
        private set
    var isTestingAcquisition by mutableStateOf(false)
        private set
    var acquisitionReport by mutableStateOf<String?>(null)
        private set

    var showImageModelPicker by mutableStateOf(false)
        private set
    var showDepthModelPicker by mutableStateOf(false)
        private set
    var availableModels by mutableStateOf<List<WalkingAidModelOption>>(emptyList())
        private set
    var modelsLoading by mutableStateOf(false)
        private set

    /** Set when the switch was turned on before the models or Pro access it needs were ready. */
    var readiness by mutableStateOf<WalkingAidReadinessResult?>(null)
        private set
    var downloadingModelId by mutableStateOf<String?>(null)
        private set
    var modelDownload by mutableStateOf<WalkingAidModelDownload?>(null)
        private set

    /** Unsaved focus edits: different words, or a different translation source than the saved one. */
    val focusUnsaved: Boolean
        get() = focusDescriptionText.trim() != savedFocusOriginal || focusTranslationSource != savedFocusTranslationSource

    /** The saved English focus, while the field still shows the saved words. */
    val understoodFocusText: String
        get() = savedModelFocusText.takeIf { focusDescriptionText.trim() == savedFocusOriginal } ?: ""

    private fun <T> save(pref: PrefValue<T>, value: T) {
        store[pref] = value
    }

    /** The service runs and stops on its own; the screen follows it twice a second while shown. */
    suspend fun followService() {
        while (true) {
            enabled = store[WalkingAid.enabled]
            running = platform.isServiceRunning()
            delay(500)
        }
    }

    /** Turning on first checks the models and Pro access; a missing one opens [readiness] instead. */
    fun onEnabledChange(newValue: Boolean, startService: () -> Unit, stopService: () -> Unit) {
        if (newValue) {
            val check = platform.checkReadiness()
            if (!check.isReady) {
                readiness = check
                return
            }
        }
        enabled = newValue
        save(WalkingAid.enabled, newValue)
        catalog[PluginPrefs.Community.nativePluginEnabled[NativePluginIds.WALKING_AID]] = newValue
        if (newValue) startService() else stopService()
    }

    fun dismissReadiness() {
        readiness = null
    }

    fun onAidModeSelected(mode: WalkingAidMode) {
        aidMode = mode
        save(WalkingAid.mode, mode.name)
    }

    fun onVideoModeSelected(mode: WalkingAidVideoMode) {
        videoMode = mode
        save(WalkingAid.videoMode, mode.name)
    }

    fun onCaptureIntervalSelected(seconds: Int) {
        captureInterval = seconds
        save(WalkingAid.captureIntervalSeconds, seconds)
    }

    fun onThumbnailQualitySelected(level: Int) {
        thumbnailQuality = level
        save(WalkingAid.thumbnailQualityLevel, level)
    }

    fun onSettleDelayChange(delayMs: Int) {
        settleDelayMs = delayMs
        save(WalkingAid.photoSettleDelayMs, delayMs)
    }

    /** Choosing the cloud also opens its model picker. */
    fun onImageDescriptionSourceSelected(source: String) {
        imageDescriptionSource = source
        save(WalkingAid.imageDescriptionSource, source)
        if (source == "cloud") showImageModelPicker = true
    }

    fun onYoloModelTypeSelected(type: String) {
        yoloModelType = type
        save(WalkingAid.yoloModelType, type)
    }

    fun onDepthEnabledChange(enabled: Boolean) {
        depthEnabled = enabled
        save(WalkingAid.depthEnabled, enabled)
    }

    /** Choosing the cloud also opens its model picker. */
    fun onDepthSourceSelected(source: String) {
        depthSource = source
        save(WalkingAid.depthSource, source)
        if (source == "cloud") showDepthModelPicker = true
    }

    fun onTtsEnabledChange(enabled: Boolean) {
        ttsEnabled = enabled
        save(WalkingAid.ttsEnabled, enabled)
    }

    fun onSpeechRateChange(rate: Float) {
        speechRate = rate
        save(WalkingAid.speechRate, rate)
    }

    fun onAnnouncementPauseChange(seconds: Float) {
        announcementPause = seconds
        save(WalkingAid.announcementPauseMs, (seconds * 1000).toLong())
    }

    fun onSafetyDisclaimerChange(enabled: Boolean) {
        safetyDisclaimerEnabled = enabled
        save(WalkingAid.safetyDisclaimerEnabled, enabled)
    }

    fun onCustomPromptChange(prompt: String) {
        if (prompt.length > MAX_CUSTOM_PROMPT_LENGTH) return
        customPrompt = prompt
        save(WalkingAid.customPrompt, prompt)
    }

    fun onHistoryMaxCountChange(count: Int) {
        historyMaxCount = count
        save(WalkingAid.imageHistoryMaxCount, count)
    }

    fun onClearHistory() = platform.clearImageHistory()

    fun onFocusTextChange(text: String) {
        focusDescriptionText = text
        focusTranslationError = null
    }

    /** Picks the translator for the next save; nothing is stored until then. */
    fun onFocusTranslationSourceSelected(source: String) {
        focusTranslationSource = source
    }

    /** Translates the focus to English for the detector rules and saves both; a blank focus clears it. */
    fun onTranslateAndSaveFocus() {
        val original = focusDescriptionText.trim().take(500)
        val source = focusTranslationSource
        if (original.isBlank()) {
            WalkingAidSettings.setFocusDescription(store, "")
            savedFocusOriginal = ""
            savedModelFocusText = ""
            savedFocusTranslationSource = source
            return
        }
        isTranslatingFocus = true
        focusTranslationError = null
        viewModelScope.launch {
            try {
                val translated = platform.translateFocusToEnglish(original, source)
                WalkingAidSettings.setCachedFocusTranslation(store, original, source, translated)
                savedFocusOriginal = original
                savedModelFocusText = translated
                savedFocusTranslationSource = source
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                focusTranslationError = error.message ?: "Translation failed"
            }
            isTranslatingFocus = false
        }
    }

    fun onRunLatencyBenchmark() {
        isTestingLatency = true
        viewModelScope.launch {
            try {
                latencyReport = platform.runLatencyBenchmark(depthEnabled, depthSource, focusDescriptionText)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                platform.showMessage(WalkingAidSettingsMessage.LATENCY_TEST_FAILED, error.message)
            }
            isTestingLatency = false
        }
    }

    fun dismissLatencyReport() {
        latencyReport = null
    }

    fun onRunAcquisitionBenchmark() {
        isTestingAcquisition = true
        viewModelScope.launch {
            try {
                acquisitionReport = platform.runAcquisitionBenchmark()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                platform.showMessage(WalkingAidSettingsMessage.ACQUISITION_TEST_FAILED, error.message)
            }
            isTestingAcquisition = false
        }
    }

    fun dismissAcquisitionReport() {
        acquisitionReport = null
    }

    fun onDownloadModel(entryId: String, displayName: String) {
        downloadingModelId = entryId
        modelDownload = WalkingAidModelDownload.Starting(displayName)
        viewModelScope.launch {
            modelDownload = try {
                platform.installModel(entryId) { percent ->
                    viewModelScope.launch { modelDownload = WalkingAidModelDownload.Downloading(percent) }
                }
                WalkingAidModelDownload.Ready(displayName)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                WalkingAidModelDownload.Failed(error.message ?: "unknown error")
            }
            downloadingModelId = null
        }
    }

    fun openImageModelPicker() {
        showImageModelPicker = true
    }

    fun openDepthModelPicker() {
        showDepthModelPicker = true
    }

    fun dismissImageModelPicker() {
        showImageModelPicker = false
    }

    fun dismissDepthModelPicker() {
        showDepthModelPicker = false
    }

    fun onImageModelSelected(modelId: String) {
        imageDescriptionCloudModelId = modelId
        save(WalkingAid.imageDescriptionCloudModelId, modelId)
        showImageModelPicker = false
    }

    fun onDepthModelSelected(modelId: String) {
        depthCloudModelId = modelId
        save(WalkingAid.depthCloudModelId, modelId)
        showDepthModelPicker = false
    }

    fun refreshCloudModels() {
        modelsLoading = true
        viewModelScope.launch {
            try {
                availableModels = platform.fetchCloudModels()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                platform.showMessage(WalkingAidSettingsMessage.MODELS_LOAD_FAILED, error.message)
            }
            modelsLoading = false
        }
    }

    companion object {
        const val MAX_CUSTOM_PROMPT_LENGTH = 1500
    }
}

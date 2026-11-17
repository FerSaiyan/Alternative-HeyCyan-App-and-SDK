package com.fersaiyan.cyanbridge.shared.customization

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fersaiyan.cyanbridge.shared.billing.subscriptionGateCopy
import com.fersaiyan.cyanbridge.shared.viewmodel.DialogButton
import com.fersaiyan.cyanbridge.shared.viewmodel.ScreenDialog
import com.fersaiyan.cyanbridge.shared.viewmodel.ScreenEffect
import com.fersaiyan.cyanbridge.shared.viewmodel.ScreenEffects
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class CustomizationPhase { IDLE, LOCKED, LOADING, NO_GLASSES, UNSUPPORTED, DISABLED, READY }

/** [hint]: how long a sound the slot holds, when the server knows it (longer sounds are shortened). */
data class ToneChoiceUi(
    val id: String,
    val label: String,
    val fileName: String? = null,
    val error: String? = null,
    val hint: String? = null,
)

data class WakeSampleUi(val index: Int, val durationMs: Long, val peakDbfs: Int, val warning: String? = null)

/** [baseNote]: set when the build uses a newer official base than the glasses reported. */
data class CustomizationJobUi(
    val id: String,
    val title: String,
    val status: String,
    val statusLabel: String,
    val message: String,
    val active: Boolean,
    val baseNote: String? = null,
)

data class GlassesCustomizationUiState(
    val expanded: Boolean = false,
    val phase: CustomizationPhase = CustomizationPhase.IDLE,
    val statusText: String = "",
    /** Shown while READY when the server does not hold this base firmware yet (the request goes to review). */
    val reviewNotice: String? = null,
    val glassesLabel: String? = null,
    val tones: List<ToneChoiceUi> = emptyList(),
    val showDisableLed: Boolean = false,
    val disableLed: Boolean = false,
    val showMuteShutter: Boolean = false,
    val muteShutter: Boolean = false,
    val wakeSupported: Boolean = false,
    val wakeLanguages: List<String> = emptyList(),
    val wakeLanguage: String = "en",
    val wakePhrase: String = "",
    val phonemesNote: String? = null,
    val samples: List<WakeSampleUi> = emptyList(),
    val sampleMin: Int = 8,
    val sampleMax: Int = 12,
    val recording: Boolean = false,
    val level: Float = 0f,
    val recordingHint: String = "",
    val consentChecked: Boolean = false,
    val consentVersion: String = "",
    val busy: Boolean = false,
    val progress: String? = null,
    val jobs: List<CustomizationJobUi> = emptyList(),
    val installing: Boolean = false,
    val installStatus: String? = null,
) {
    val canRecord: Boolean get() = phase == CustomizationPhase.READY && wakeSupported && !recording && !busy && samples.size < sampleMax
    val canSubmit: Boolean get() = phase == CustomizationPhase.READY && consentChecked && !busy && !recording
}

interface GlassesCustomizationActions {
    fun toggleExpanded()
    fun refresh()
    fun upgrade()
    fun pickTone(slotId: String)
    fun clearTone(slotId: String)
    fun setDisableLed(enabled: Boolean)
    fun setMuteShutter(enabled: Boolean)
    fun setWakePhrase(phrase: String)
    fun setWakeLanguage(code: String)
    fun recordSample()
    fun stopRecording()
    fun deleteSample(index: Int)
    fun setConsent(accepted: Boolean)
    fun submit()
    fun cancelJob(id: String)
    fun installFirmware()
}

/** Asks the host for an audio file for tone [slotId]; answer with [GlassesCustomizationViewModel.onTonePicked]. */
data class PickCustomizationTone(val slotId: String) : ScreenEffect

/** Asks the host for the emailed firmware file; answer with [GlassesCustomizationViewModel.onFirmwarePicked]. */
data object PickCustomFirmware : ScreenEffect

/** Opens the plan picker on Standard. */
data object OpenCustomizationUpgrade : ScreenEffect

/**
 * The "Glasses customization" card of the Pro settings: custom tones, LED/shutter options and a
 * wake word recorded through the glasses microphone, sent as a build job to the relay, plus the
 * build list and the install of the emailed firmware.
 */
class GlassesCustomizationViewModel(
    private val platform: CustomizationPlatform,
    private val io: CoroutineDispatcher,
) : ViewModel(), GlassesCustomizationActions {
    private val _state = MutableStateFlow(GlassesCustomizationUiState())
    val state: StateFlow<GlassesCustomizationUiState> = _state.asStateFlow()
    private val effects = ScreenEffects()
    val effectFlow get() = effects.flow

    /** Polling runs only while the screen is visible. */
    var visible = false
        set(value) {
            field = value
            if (value) startPolling() else pollJob?.cancel()
        }

    private var config: CustomizationConfig? = null
    private var glasses: GlassesFirmwareInfo? = null
    private val toneRefs = mutableMapOf<String, Pair<String, String?>>() // slot -> (ref, mime)
    private val takes = mutableListOf<ByteArray>()
    private var stopSignal: CompletableDeferred<Unit>? = null
    private var draftJobId: String? = null
    private var loadJob: Job? = null
    private var pollJob: Job? = null

    override fun toggleExpanded() {
        val expanded = !_state.value.expanded
        _state.update { it.copy(expanded = expanded) }
        if (expanded && _state.value.phase == CustomizationPhase.IDLE) refresh()
    }

    override fun refresh() {
        if (loadJob?.isActive == true || _state.value.recording || _state.value.busy) return
        if (!platform.plan().trim().lowercase().let { it == "standard" || it == "max" }) {
            _state.update {
                it.copy(phase = CustomizationPhase.LOCKED, statusText = "Custom tones, LED and shutter options and your own wake word are included with Standard and Max.")
            }
            return
        }
        _state.update { it.copy(phase = CustomizationPhase.LOADING, statusText = "Checking your glasses…") }
        loadJob = viewModelScope.launch {
            try {
                refreshJobs()
                when (val detection = withContext(io) { platform.detectGlasses() }) {
                    is GlassesDetection.Missing -> {
                        glasses = null
                        _state.update { it.copy(phase = CustomizationPhase.NO_GLASSES, statusText = detection.message, glassesLabel = null) }
                    }
                    is GlassesDetection.Found -> loadConfig(detection.glasses)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: CustomizationApiError) {
                handleApiError(error)
            } catch (error: Throwable) {
                _state.update { it.copy(phase = CustomizationPhase.IDLE, statusText = "Could not load customization: ${error.message ?: "unknown error"}") }
            }
        }
    }

    private suspend fun loadConfig(found: GlassesFirmwareInfo) {
        glasses = found
        val query = buildString {
            append("/api/customization/config?device_family=").append(found.family.wire)
            append("&ble_firmware_version=").append(encodeQuery(found.bleFirmwareVersion))
            found.wifiFirmwareVersion?.takeIf { it.isNotBlank() }?.let { append("&wifi_firmware_version=").append(encodeQuery(it)) }
        }
        val cfg = CustomizationJson.parseConfig(call("GET", query))
        config = cfg
        val phase = when {
            !cfg.eligible && cfg.ineligibleReason == "subscription_required" -> CustomizationPhase.LOCKED
            !cfg.enabled -> CustomizationPhase.DISABLED
            !cfg.supported && !cfg.reviewRequired -> CustomizationPhase.UNSUPPORTED
            else -> CustomizationPhase.READY
        }
        val reviewNotice = if (phase == CustomizationPhase.READY && !cfg.supported) {
            "We don't have your firmware version (${found.label}) yet. You can still send your request: we will review the version " +
                "and email you, usually within a few days. Your recordings are kept for at most ${cfg.reviewMaxDays} days while we review."
        } else {
            null
        }
        val status = when {
            phase == CustomizationPhase.LOCKED -> "The server reports that your plan does not include glasses customization."
            !cfg.eligible && cfg.ineligibleReason == "verified_email_required" -> "Verify your account email (Account card) before sending a customization."
            phase == CustomizationPhase.DISABLED -> CustomizationJson.SERVICE_NOT_OPEN
            phase == CustomizationPhase.UNSUPPORTED -> "We do not have the base firmware of these glasses yet (${found.label}). Update them to the latest official firmware and try again."
            else -> "Choose what to change, then send it. We build and test the firmware and email it to you."
        }
        val languages = cfg.wakeLanguages.map { it.code }
        _state.update { current ->
            current.copy(
                phase = phase,
                statusText = status,
                reviewNotice = reviewNotice,
                glassesLabel = found.label,
                tones = cfg.toneSlots.map { slot ->
                    val picked = current.tones.firstOrNull { it.id == slot.id }
                    ToneChoiceUi(slot.id, slot.label, picked?.fileName?.takeIf { toneRefs.containsKey(slot.id) }, hint = toneHint(slot))
                },
                showDisableLed = cfg.disableLedSupported,
                disableLed = current.disableLed && cfg.disableLedSupported,
                showMuteShutter = cfg.muteShutterSupported,
                muteShutter = current.muteShutter && cfg.muteShutterSupported,
                wakeSupported = cfg.wakeWordSupported,
                wakeLanguages = languages,
                wakeLanguage = current.wakeLanguage.takeIf { it in languages } ?: languages.firstOrNull() ?: "en",
                phonemesNote = cfg.phonemesNote,
                sampleMin = cfg.sampleMin,
                sampleMax = cfg.sampleMax,
                consentVersion = cfg.consentVersion,
            )
        }
        toneRefs.keys.retainAll(cfg.toneSlots.map { it.id }.toSet())
    }

    override fun upgrade() = effects.send(OpenCustomizationUpgrade)

    // ── Tones and options ──

    override fun pickTone(slotId: String) = effects.send(PickCustomizationTone(slotId))

    fun onTonePicked(slotId: String, ref: String?) {
        if (ref == null) return
        val cfg = config ?: return
        if (cfg.toneSlots.none { it.id == slotId }) return
        viewModelScope.launch {
            val result = runCatching { withContext(io) { platform.readDocument(ref) } }
            val doc = result.getOrElse { error ->
                setToneError(slotId, "Could not read the file: ${error.message}")
                return@launch
            }
            val contentType = toneContentType(doc)
            // The upload limit is the server's per-kind cap. The slot's max_bytes is the firmware
            // capacity of the encoded tone: the worker encodes and shortens the sound to fit it.
            val limit = cfg.toneMaxBytes
            val error = when {
                contentType == null -> "Use a WAV, MP3, OGG, AAC, M4A or FLAC file."
                doc.bytes.size > limit -> "The file is too large (max ${sizeLabel(limit)})."
                doc.bytes.isEmpty() -> "The file is empty."
                else -> null
            }
            if (error != null) {
                toneRefs.remove(slotId)
                setToneError(slotId, error)
            } else {
                toneRefs[slotId] = ref to contentType
                _state.update { s -> s.copy(tones = s.tones.map { if (it.id == slotId) it.copy(fileName = doc.name, error = null) else it }) }
            }
        }
    }

    override fun clearTone(slotId: String) {
        toneRefs.remove(slotId)
        _state.update { s -> s.copy(tones = s.tones.map { if (it.id == slotId) it.copy(fileName = null, error = null) else it }) }
    }

    private fun toneHint(slot: ToneSlot): String? = slot.maxDurationMs?.takeIf { it > 0 }?.let { ms ->
        val tenths = (ms + 50) / 100
        "Plays up to about ${tenths / 10}.${tenths % 10} s; longer sounds are shortened to fit."
    }

    private fun sizeLabel(bytes: Long): String {
        if (bytes < 1_048_576) return "${(bytes / 1024).coerceAtLeast(1)} KB"
        val tenths = bytes * 10 / 1_048_576
        return if (tenths % 10 == 0L) "${tenths / 10} MB" else "${tenths / 10}.${tenths % 10} MB"
    }

    private fun setToneError(slotId: String, message: String) =
        _state.update { s -> s.copy(tones = s.tones.map { if (it.id == slotId) it.copy(fileName = null, error = message) else it }) }

    private fun toneContentType(doc: PickedDocument): String? {
        val allowed = config?.toneContentTypes.orEmpty()
        val byExtension = when (doc.name.substringAfterLast('.', "").lowercase()) {
            "wav" -> "audio/wav"
            "mp3" -> "audio/mpeg"
            "ogg", "oga", "opus" -> "audio/ogg"
            "aac" -> "audio/aac"
            "m4a", "mp4" -> "audio/mp4"
            "flac" -> "audio/flac"
            else -> null
        }
        val candidate = doc.mimeType?.lowercase()?.takeIf { allowed.isEmpty() || it in allowed } ?: byExtension
        return candidate?.takeIf { allowed.isEmpty() || it in allowed }
    }

    override fun setDisableLed(enabled: Boolean) = _state.update { it.copy(disableLed = enabled && it.showDisableLed) }

    override fun setMuteShutter(enabled: Boolean) = _state.update { it.copy(muteShutter = enabled && it.showMuteShutter) }

    override fun setWakePhrase(phrase: String) = _state.update { it.copy(wakePhrase = phrase.take(64)) }

    override fun setWakeLanguage(code: String) = _state.update { it.copy(wakeLanguage = code) }

    override fun setConsent(accepted: Boolean) = _state.update { it.copy(consentChecked = accepted) }

    // ── Wake-word samples ──

    override fun recordSample() {
        val current = _state.value
        val found = glasses
        if (!current.canRecord || found == null) return
        val phrase = current.wakePhrase.trim()
        if (phrase.isEmpty()) {
            effects.toast("Type the wake phrase first.")
            return
        }
        val stop = CompletableDeferred<Unit>()
        stopSignal = stop
        _state.update {
            it.copy(recording = true, level = 0f, recordingHint = "Say “$phrase” now, at your normal volume.")
        }
        viewModelScope.launch {
            try {
                val pcm = withContext(io) {
                    platform.micCapture(found.family).record(TAKE_MAX_MS, stop) { chunk ->
                        _state.update { it.copy(level = WakeSampleAudio.meterLevel(chunk)) }
                    }
                }
                acceptTake(pcm)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _state.update { it.copy(recordingHint = "Recording failed: ${error.message ?: "no audio from the glasses"}") }
            } finally {
                stopSignal = null
                _state.update { it.copy(recording = false, level = 0f) }
            }
        }
    }

    private fun acceptTake(raw: ByteArray) {
        val cfg = config
        val maxBytes = ((cfg?.sampleMaxMs ?: 10_000L) * WakeSampleAudio.BYTES_PER_MS).toInt()
        val pcm = WakeSampleAudio.trimHead(raw).let { if (it.size > maxBytes) it.copyOf(maxBytes) else it }
        val duration = WakeSampleAudio.durationMs(pcm)
        val peak = WakeSampleAudio.peakDbfs(pcm)
        val hint = when {
            duration < (cfg?.sampleMinMs ?: 300L) + 200L -> "That take was too short. Hold Record a little longer and try again."
            peak < QUIET_DBFS -> "The glasses heard almost nothing. Speak a little louder and try again."
            else -> null
        }
        if (hint != null) {
            _state.update { it.copy(recordingHint = hint) }
            return
        }
        takes += pcm
        val clipped = WakeSampleAudio.clippedSamples(pcm) > pcm.size / 2 / 200
        publishSamples()
        val count = takes.size
        val min = _state.value.sampleMin
        _state.update {
            it.copy(
                recordingHint = when {
                    clipped -> "Sample $count saved, but it was very loud. Speak a little softer next time."
                    count < min -> "Sample $count saved. Record ${min - count} more (vary your distance and speed a little)."
                    else -> "Sample $count saved. You have enough samples."
                },
            )
        }
    }

    override fun stopRecording() {
        stopSignal?.complete(Unit)
    }

    override fun deleteSample(index: Int) {
        if (_state.value.recording || index !in takes.indices) return
        takes.removeAt(index)
        publishSamples()
    }

    private fun publishSamples() {
        _state.update {
            it.copy(samples = takes.mapIndexed { i, pcm ->
                val peak = WakeSampleAudio.peakDbfs(pcm)
                WakeSampleUi(i, WakeSampleAudio.durationMs(pcm), peak.toInt(), if (peak > -1.0) "loud" else null)
            })
        }
    }

    // ── Submit ──

    override fun submit() {
        val s = _state.value
        val found = glasses
        val cfg = config
        if (!s.canSubmit || found == null || cfg == null) {
            if (!s.consentChecked) effects.toast("Accept the consent first.")
            return
        }
        val phrase = s.wakePhrase.trim().replace(Regex("\\s+"), " ")
        val wantsWake = phrase.isNotEmpty()
        val problem = when {
            wantsWake && !s.wakeSupported -> "These glasses do not support a custom wake word yet."
            wantsWake && takes.size < s.sampleMin -> "Record at least ${s.sampleMin} samples of the wake phrase (${takes.size} so far)."
            wantsWake && takes.size > s.sampleMax -> "Keep at most ${s.sampleMax} samples."
            wantsWake -> validatePhrase(phrase, cfg.wakeLanguages.firstOrNull { it.code == s.wakeLanguage })
            takes.isNotEmpty() -> "Type the wake phrase you recorded, or delete the samples."
            else -> null
        } ?: if (!wantsWake && toneRefs.isEmpty() && !s.disableLed && !s.muteShutter) "Choose at least one change." else null
        if (problem != null) {
            _state.update { it.copy(progress = problem) }
            return
        }
        val request = CustomizationJobRequest(
            glasses = found,
            wakePhrase = phrase.takeIf { wantsWake },
            wakeLanguage = s.wakeLanguage.takeIf { wantsWake },
            wakeSampleCount = takes.size.takeIf { wantsWake },
            toneSlots = cfg.toneSlots.map { it.id }.filter { it in toneRefs },
            disableLedFlash = s.disableLed,
            muteShutter = s.muteShutter,
            consentVersion = cfg.consentVersion,
        )
        _state.update { it.copy(busy = true, progress = "Creating the build request…") }
        viewModelScope.launch {
            try {
                // Size checks first, so an oversized file never leaves a draft on the server.
                val uploads = withContext(io) { buildUploads(request, cfg) }
                val job = createOrReuseJob(request)
                draftJobId = job.id
                uploadAll(job.id, uploads)
                progress("Submitting…")
                val queued = submitWithRetry(job.id, uploads)
                draftJobId = null
                takes.clear()
                toneRefs.clear()
                val sent = "Sent (build ${queued.id.takeLast(8)})."
                _state.update { st ->
                    st.copy(
                        samples = emptyList(),
                        tones = st.tones.map { it.copy(fileName = null, error = null) },
                        consentChecked = false,
                        disableLed = false,
                        muteShutter = false,
                        progress = if (queued.status == "awaiting_base_firmware") {
                            "$sent We don't have your firmware version yet, so we will review it and email you, usually within a few days. " +
                                "Your recordings are kept for at most ${cfg.reviewMaxDays} days while we review."
                        } else {
                            "$sent We will email the firmware to your account address when it is ready."
                        },
                    )
                }
                refreshJobs()
            } catch (error: CancellationException) {
                throw error
            } catch (error: CustomizationApiError) {
                handleApiError(error)
            } catch (error: UploadTooLarge) {
                progress(error.message)
            } catch (error: Throwable) {
                progress("Sending failed: ${error.message ?: "unknown error"}. Try again.")
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    private fun validatePhrase(phrase: String, language: WakeLanguage?): String? {
        val rule = language ?: return "Choose the wake phrase language."
        val letters = phrase.count { it.isLetter() }
        return when {
            phrase.any { it.isDigit() } -> "Spell numbers out in the wake phrase."
            phrase.length > rule.maxChars -> "Use at most ${rule.maxChars} characters."
            phrase.split(' ').size > rule.maxWords -> "Use at most ${rule.maxWords} words."
            letters < rule.minLetters -> "Use at least ${rule.minLetters} letters."
            else -> null
        }
    }

    private suspend fun createOrReuseJob(request: CustomizationJobRequest): CustomizationJob {
        val expected = expectedSlots(request)
        draftJobId?.let { id ->
            val existing = runCatching { CustomizationJson.parseJobEnvelope(call("GET", "/api/customization/jobs/$id")) }.getOrNull()
            if (existing != null && existing.status in setOf("draft", "uploading") &&
                existing.expectedAssets.map { it.kind to it.slot }.toSet() == expected
            ) {
                return existing
            }
            if (existing != null && existing.isActive) runCatching { call("DELETE", "/api/customization/jobs/$id") }
            draftJobId = null
        }
        return CustomizationJson.parseJobEnvelope(call("POST", "/api/customization/jobs", CustomizationJson.jobRequestBody(request)))
    }

    private fun expectedSlots(request: CustomizationJobRequest): Set<Pair<String, String>> =
        (1..(request.wakeSampleCount ?: 0)).map { "wake_sample" to wakeSlot(it) }.toSet() +
            request.toneSlots.map { "tone" to it }

    /** Reads every asset and checks the server's size limits (per kind and per job) before anything is sent. */
    private suspend fun buildUploads(request: CustomizationJobRequest, cfg: CustomizationConfig): List<CustomizationUpload> {
        val uploads = mutableListOf<CustomizationUpload>()
        if (request.wakePhrase != null) {
            takes.forEachIndexed { i, pcm ->
                val wav = WakeSampleAudio.toWav(pcm)
                if (wav.size > cfg.sampleMaxBytes) {
                    throw UploadTooLarge("Sample ${i + 1} is too large to send (max ${sizeLabel(cfg.sampleMaxBytes)}). Delete it and record it again.")
                }
                uploads += CustomizationUpload("wake_sample", wakeSlot(i + 1), "audio/wav", wav, platform.sha256Hex(wav), WakeSampleAudio.durationMs(pcm))
            }
        }
        request.toneSlots.forEach { slot ->
            val (ref, contentType) = toneRefs.getValue(slot)
            val bytes = platform.readDocument(ref).bytes
            if (bytes.size > cfg.toneMaxBytes) {
                val label = cfg.toneSlots.firstOrNull { it.id == slot }?.label ?: CustomizationJson.slotLabel(slot)
                throw UploadTooLarge("The file for “$label” is too large (max ${sizeLabel(cfg.toneMaxBytes)}). Choose a smaller file.")
            }
            uploads += CustomizationUpload("tone", slot, contentType ?: "audio/wav", bytes, platform.sha256Hex(bytes))
        }
        val total = uploads.sumOf { it.bytes.size.toLong() }
        if (total > cfg.jobMaxBytes) {
            throw UploadTooLarge(
                "Your recordings and files add up to ${sizeLabel(total)}, more than the ${sizeLabel(cfg.jobMaxBytes)} one request can send. " +
                    "Use shorter tone files.",
            )
        }
        return uploads
    }

    /** Sends each asset straight to the relay; re-sending a slot replaces it. */
    private suspend fun uploadAll(jobId: String, uploads: List<CustomizationUpload>) {
        uploads.forEachIndexed { index, upload ->
            progress("Uploading ${index + 1} of ${uploads.size}…")
            val headers = buildMap {
                put(SHA256_HEADER, upload.sha256)
                upload.durationMs?.let { put(DURATION_HEADER, it.toString()) }
            }
            val result = withContext(io) {
                platform.apiBytes("PUT", "/api/customization/jobs/$jobId/assets/${upload.slot}", upload.contentType, headers, upload.bytes)
            }
            if (!result.ok) throw CustomizationJson.parseError(result.status, result.body)
        }
    }

    private suspend fun submitWithRetry(jobId: String, uploads: List<CustomizationUpload>): CustomizationJob {
        try {
            return CustomizationJson.parseJobEnvelope(call("POST", "/api/customization/jobs/$jobId/submit"))
        } catch (error: CustomizationApiError) {
            if (error.code != "assets_incomplete" || error.problemSlots.isEmpty()) throw error
            uploadAll(jobId, uploads.filter { it.slot in error.problemSlots })
            return CustomizationJson.parseJobEnvelope(call("POST", "/api/customization/jobs/$jobId/submit"))
        }
    }

    // ── Builds ──

    private suspend fun refreshJobs() {
        val jobs = CustomizationJson.parseJobList(call("GET", "/api/customization/jobs"))
        _state.update { it.copy(jobs = jobs.take(MAX_LISTED_JOBS).map(::jobUi)) }
    }

    override fun cancelJob(id: String) {
        effects.send(
            ScreenDialog(
                title = "Cancel this build?",
                message = "Your recordings and tone files for it are deleted.",
                positive = DialogButton("Cancel build") {
                    viewModelScope.launch {
                        runCatching {
                            call("DELETE", "/api/customization/jobs/$id")
                            if (draftJobId == id) draftJobId = null
                            refreshJobs()
                        }.onFailure { effects.toast("Could not cancel: ${it.message}") }
                    }
                },
                negative = DialogButton("Keep"),
            ),
        )
    }

    private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            while (isActive) {
                delay(POLL_MS)
                if (visible && _state.value.expanded && _state.value.jobs.any { it.active }) {
                    runCatching { refreshJobs() }
                }
            }
        }
    }

    // ── Install ──

    override fun installFirmware() {
        if (_state.value.installing) return
        effects.send(
            ScreenDialog(
                title = "Install custom firmware",
                message = "Pick the firmware file from the email we sent you. Keep the glasses charged, close to the phone and connected until the update finishes.",
                positive = DialogButton("Choose file") { effects.send(PickCustomFirmware) },
                negative = DialogButton("Not now"),
            ),
        )
    }

    fun onFirmwarePicked(ref: String?) {
        if (ref == null) return
        viewModelScope.launch {
            val found = glasses ?: (withContext(io) { platform.detectGlasses() } as? GlassesDetection.Found)?.glasses?.also { glasses = it }
            if (found == null) {
                _state.update { it.copy(installStatus = "Connect your glasses first.") }
                return@launch
            }
            _state.update { it.copy(installing = true, installStatus = "Checking the firmware file…") }
            try {
                val message = withContext(io) {
                    platform.installFirmware(ref, found) { line -> _state.update { it.copy(installStatus = line) } }
                }
                _state.update { it.copy(installStatus = message) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _state.update { it.copy(installStatus = "Install failed: ${error.message ?: "unknown error"}") }
            } finally {
                _state.update { it.copy(installing = false) }
            }
        }
    }

    // ── Helpers ──

    private suspend fun call(method: String, path: String, body: String? = null): String {
        val result = withContext(io) { platform.api(method, path, body) }
        if (!result.ok) throw CustomizationJson.parseError(result.status, result.body)
        return result.body
    }

    private fun progress(text: String) = _state.update { it.copy(progress = text) }

    private fun handleApiError(error: CustomizationApiError) {
        when (error.code) {
            "subscription_required", "subscription_expired" -> {
                _state.update {
                    it.copy(phase = CustomizationPhase.LOCKED, statusText = "Glasses customization needs an active Standard or Max plan.", progress = null)
                }
                val copy = subscriptionGateCopy(error.currentPlan.orEmpty(), "Glasses customization", "Glasses customization", "glasses customization")
                effects.send(
                    ScreenDialog(
                        title = copy.title,
                        message = copy.message,
                        positive = DialogButton(copy.actionLabel) { effects.send(OpenCustomizationUpgrade) },
                        negative = DialogButton("Not now"),
                    ),
                )
            }
            "verified_email_required" -> progress("Verify your account email (Account card) first.")
            "active_job_exists" -> progress("You already have a build in progress. Wait for it, or cancel it in the list below.")
            "customization_consent_required" -> {
                _state.update { it.copy(consentChecked = false, progress = "The consent text changed. Read it again and accept it.") }
                glasses?.let { found -> viewModelScope.launch { runCatching { loadConfig(found) } } }
            }
            "customization_disabled" -> progress(CustomizationJson.SERVICE_NOT_OPEN)
                        "asset_too_large" -> progress("A file is too large for the server${error.maxBytes?.let { " (max ${sizeLabel(it)})" }.orEmpty()}. Choose a smaller file.")
            "job_upload_limit" -> progress(
                "Your recordings and files are over the server's limit for one request${error.maxBytes?.let { " (${sizeLabel(it)})" }.orEmpty()}. Use shorter tone files.",
            )
            "sha256_mismatch" -> progress("A file was damaged while sending. Send the customization again.")
            "job_not_found", "invalid_job_status", "no_uploads_expected" -> {
                draftJobId = null
                progress("This build request can no longer take files. Send the customization again.")
            }
            "rate_limited" -> progress("You reached today's limit of build requests. Try again tomorrow.")
            "missing_token", "invalid_token" -> progress("Sign in to your CyanBridge account first.")
            else -> progress("${error.message} (${error.code})")
        }
        if (_state.value.phase == CustomizationPhase.LOADING) {
            _state.update { it.copy(phase = CustomizationPhase.IDLE, statusText = error.message) }
        }
    }

    companion object {
        const val TAKE_MAX_MS = 3_300L
        const val QUIET_DBFS = -45.0
        const val POLL_MS = 30_000L
        const val MAX_LISTED_JOBS = 5
        const val SHA256_HEADER = "X-Content-SHA256"
        const val DURATION_HEADER = "X-Duration-Ms"

        fun wakeSlot(index: Int) = "wake_" + index.toString().padStart(2, '0')

        fun jobUi(job: CustomizationJob) = CustomizationJobUi(
            id = job.id,
            title = buildString {
                append("Build ").append(job.id.takeLast(8))
                job.wakePhrase?.let { append(" · “").append(it).append("”") }
                if (job.toneSlots.isNotEmpty()) append(" · ").append(job.toneSlots.size).append(" tones")
            },
            status = job.status,
            statusLabel = when (job.status) {
                "draft" -> "Draft"
                "uploading" -> "Uploading"
                "awaiting_base_firmware" -> "Waiting for firmware review"
                "declined" -> "Declined"
                "queued" -> "Queued"
                "building" -> "Building and testing"
                "built" -> "Built, sending the email"
                "emailed" -> "Emailed: check your inbox"
                "gates_failed" -> "Did not pass our safety tests"
                "failed" -> "Failed"
                "cancelled" -> "Cancelled"
                else -> job.status
            },
            message = job.statusMessage.ifBlank {
                when (job.status) {
                    "awaiting_base_firmware" -> "We are reviewing your firmware version and will email you."
                    "declined" -> listOfNotNull(job.declineReason?.let { "Reason: ${it.trimEnd('.')}." }, "Your recordings were deleted.").joinToString(" ")
                    else -> ""
                }
            },
            active = job.isActive,
            baseNote = job.baseBleFirmwareVersion?.takeIf { job.baseFirmwareStatus == "mapped" }?.let { base ->
                val from = job.bleFirmwareVersion?.let { " (an update from your $it)" }.orEmpty()
                "Built on firmware $base$from. Installing it updates your glasses to that version."
            },
        )

        /** Percent-encodes a query value (versions are `[A-Za-z0-9._-]`, so this is mostly a guard). */
        fun encodeQuery(value: String): String = buildString {
            value.encodeToByteArray().forEach { b ->
                val c = (b.toInt() and 0xFF).toChar()
                if (c.isLetterOrDigit() && c.code < 128 || c in "-._~") append(c)
                else append('%').append((b.toInt() and 0xFF).toString(16).uppercase().padStart(2, '0'))
            }
        }
    }
}

/** A client-side size check failed; the message is shown as is. */
private class UploadTooLarge(override val message: String) : Exception(message)

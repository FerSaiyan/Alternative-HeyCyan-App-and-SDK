package com.fersaiyan.cyanbridge.shared.plugins.orallesion

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import com.fersaiyan.cyanbridge.shared.platform.PlatformLogger
import com.fersaiyan.cyanbridge.shared.platform.PlatformPreferences
import com.fersaiyan.cyanbridge.shared.platform.platformCurrentTimeMillis
import com.fersaiyan.cyanbridge.shared.plugins.NativePluginIds
import com.fersaiyan.cyanbridge.shared.plugins.settings.NativePluginHost
import com.fersaiyan.cyanbridge.shared.plugins.settings.NativePluginSettingsViewModel
import com.fersaiyan.cyanbridge.shared.prefs.PluginPrefs.OralLesionBench
import com.fersaiyan.cyanbridge.shared.prefs.get
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Where a bench model stands on this phone. */
sealed interface OralLesionModelState {
    data object Checking : OralLesionModelState
    data object NotInstalled : OralLesionModelState
    data class Downloading(val percent: Int) : OralLesionModelState
    data object Verifying : OralLesionModelState
    data class Installed(val path: String) : OralLesionModelState

    /** A gated model and no Hugging Face token saved in the app. */
    data object NeedsToken : OralLesionModelState

    /** Hugging Face answered 401/403: the user must request access to the gated repo. */
    data object AccessDenied : OralLesionModelState
    data class Failed(val message: String) : OralLesionModelState
}

enum class OralLesionPhotoSource { GLASSES, CAMERA, GALLERY }

enum class OralLesionPhase { IDLE, TURN1, QUESTIONS, TURN2, SUMMARY, DONE }

/** One model turn as the engine ran it. */
data class OralLesionTurn(val text: String, val metrics: OralLesionTurnMetrics, val backend: String)

data class OralLesionDeviceInfo(val soc: String, val device: String, val appVersion: String)

/** The Android side of the bench: photos, the LiteRT engine, speech, model files and the case log. */
interface OralLesionPlatform {
    /** Null when the selected glasses can take a photo for the bench. */
    val glassesPhotoUnavailableReason: String?
    val deviceInfo: OralLesionDeviceInfo

    suspend fun modelState(model: OralLesionModel): OralLesionModelState

    /** Downloads [model] (token from the app's Hugging Face setting), checks its size and SHA-256. */
    suspend fun installModel(model: OralLesionModel, onState: (OralLesionModelState) -> Unit)

    suspend fun captureGlassesPhoto(): String
    fun takeCameraPhoto(onResult: (String?) -> Unit)
    fun pickGalleryPhoto(onResult: (String?) -> Unit)

    /** Opens a new conversation (thinking off, greedy) with [system], the photo and [user]. */
    suspend fun startConversation(modelPath: String, imagePath: String, system: String, user: String, onToken: (String) -> Unit): OralLesionTurn

    /** Adds [user] to the open conversation (the photo stays in its KV cache). */
    suspend fun continueConversation(user: String, onToken: (String) -> Unit): OralLesionTurn
    fun cancelGeneration()

    fun speak(text: String, onDone: () -> Unit = {})
    fun stopSpeaking()

    /** Glasses with a display show [text]; others ignore it. */
    fun showOnGlasses(text: String)
    fun listen(onPartial: (String) -> Unit, onFinal: (String) -> Unit, onError: (String) -> Unit)
    fun stopListening()

    /** Writes or replaces the case's JSONL row; copies the photo next to the log on the first save. */
    suspend fun saveCase(case: OralLesionBenchCase, photoPath: String?): OralLesionBenchCase
    suspend fun cases(): List<OralLesionBenchCase>
    suspend fun deleteCase(caseId: String)

    /** Zips the log and photos and opens the share sheet; nothing is uploaded by the app. */
    fun exportCases()
    fun openUrl(url: String)
    fun openModelSettings()
    fun release()
}

data class OralLesionSettingsState(val enabled: Boolean, val selectedModelId: String, val speakReplies: Boolean)

/**
 * The Oral Lesion Bench screen: a research tool that runs an oral-lesion model on one photo and a
 * spoken consultation, applies the referral guardrail outside the model and logs every case on the
 * phone. Prompts are the training formats ([OralLesionPrompts]); the case is saved after each stage.
 */
class OralLesionBenchViewModel(
    store: PlatformPreferences,
    catalog: PlatformPreferences,
    host: NativePluginHost,
    private val platform: OralLesionPlatform,
) : NativePluginSettingsViewModel<OralLesionSettingsState>(
    NativePluginIds.ORAL_LESION_BENCH,
    store,
    catalog,
    host,
    {
        OralLesionSettingsState(
            enabled = this[OralLesionBench.enabled],
            selectedModelId = OralLesionModels.byId(this[OralLesionBench.selectedModelId])?.id ?: OralLesionModels.default.id,
            speakReplies = this[OralLesionBench.speakReplies],
        )
    },
) {
    val models: List<OralLesionModel> = OralLesionModels.entries
    val modelStates = mutableStateMapOf<String, OralLesionModelState>()

    var photoPath by mutableStateOf<String?>(null)
        private set
    var photoSource by mutableStateOf<OralLesionPhotoSource?>(null)
        private set
    var ageText by mutableStateOf("")
        private set
    var sex by mutableStateOf(PatientSex.UNKNOWN)
        private set
    var site by mutableStateOf("")
        private set

    var phase by mutableStateOf(OralLesionPhase.IDLE)
        private set
    var turn1Text by mutableStateOf("")
        private set
    var turn2Text by mutableStateOf("")
        private set
    var summaryText by mutableStateOf("")
        private set
    var guardrail by mutableStateOf<GuardrailResult?>(null)
        private set

    val questions = mutableStateListOf<String>()
    val answers = mutableStateListOf<String?>()
    var currentQuestion by mutableIntStateOf(-1)
        private set
    var answerDraft by mutableStateOf("")
        private set
    var listening by mutableStateOf(false)
        private set
    val redFlags = mutableStateListOf<RedFlag>()

    var status by mutableStateOf<String?>(null)
        private set
    var busy by mutableStateOf(false)
        private set

    val cases = mutableStateListOf<OralLesionBenchCase>()
    var editingCaseId by mutableStateOf<String?>(null)
        private set
    var dentist by mutableStateOf(OralLesionDentistFields())
        private set

    private var current: OralLesionBenchCase? = null
    private var spokeNotice = false
    private var job: Job? = null

    val selectedModel: OralLesionModel get() = OralLesionModels.byId(state.selectedModelId) ?: OralLesionModels.default

    init {
        refreshModels()
        refreshCases()
    }

    fun onEnabledChange(enabled: Boolean) = switchPlugin(OralLesionBench.enabled, enabled) { copy(enabled = it) }

    fun onSpeakRepliesChange(enabled: Boolean) = save(OralLesionBench.speakReplies, enabled) { copy(speakReplies = it) }

    fun onSelectModel(id: String) = save(OralLesionBench.selectedModelId, id) { copy(selectedModelId = it ?: OralLesionModels.default.id) }

    fun refreshModels() {
        viewModelScope.launch {
            models.forEach { model ->
                if (modelStates[model.id] !is OralLesionModelState.Downloading) {
                    modelStates[model.id] = OralLesionModelState.Checking
                    modelStates[model.id] = runCatching { platform.modelState(model) }
                        .getOrElse { OralLesionModelState.Failed(it.message ?: "check failed") }
                }
            }
        }
    }

    fun onInstall(model: OralLesionModel) {
        val now = modelStates[model.id]
        if (now is OralLesionModelState.Downloading || now is OralLesionModelState.Verifying) return
        viewModelScope.launch {
            runCatching { platform.installModel(model) { modelStates[model.id] = it } }
                .onFailure { if (it is CancellationException) throw it; modelStates[model.id] = OralLesionModelState.Failed(it.message ?: "download failed") }
        }
    }

    fun onRequestAccess(model: OralLesionModel) = platform.openUrl(model.repoUrl)

    fun onOpenModelSettings() = platform.openModelSettings()

    fun onAgeChange(text: String) {
        if (text.length <= 3 && text.all { it.isDigit() }) ageText = text
    }

    fun onSexChange(value: PatientSex) {
        sex = value
    }

    fun onSiteChange(text: String) {
        if (text.length <= 120) site = text
    }

    /** Dictates age, sex and site in one sentence ("mulher, 54 anos, borda lateral da língua"). */
    fun onDictateDemographics() {
        if (listening) return stopListening()
        listening = true
        platform.listen(
            onPartial = { status = it },
            onFinal = { text ->
                stopListening()
                val parsed = OralLesionDictation.parse(text)
                parsed.age?.let { ageText = it.toString() }
                parsed.sex?.let { sex = it }
                parsed.site?.let { site = it }
                status = text
            },
            onError = { stopListening(); status = it },
        )
    }

    fun onCaptureGlasses() {
        if (busy) return
        busy = true
        status = null
        viewModelScope.launch {
            runCatching { platform.captureGlassesPhoto() }
                .onSuccess { setPhoto(it, OralLesionPhotoSource.GLASSES) }
                .onFailure { if (it is CancellationException) throw it; status = it.message ?: "photo failed" }
            busy = false
        }
    }

    fun onTakeCameraPhoto() = platform.takeCameraPhoto { path -> path?.let { setPhoto(it, OralLesionPhotoSource.CAMERA) } }

    fun onPickGallery() = platform.pickGalleryPhoto { path -> path?.let { setPhoto(it, OralLesionPhotoSource.GALLERY) } }

    private fun setPhoto(path: String, source: OralLesionPhotoSource) {
        photoPath = path
        photoSource = source
    }

    private val demographics: CaseDemographics get() = CaseDemographics(ageText.toIntOrNull(), sex)

    val canAssess: Boolean
        get() = state.enabled && !busy && photoPath != null && modelStates[selectedModel.id] is OralLesionModelState.Installed &&
            phase in setOf(OralLesionPhase.IDLE, OralLesionPhase.DONE)

    /** Turn 1: the photo with age and sex, in a new conversation. */
    fun onAssess() {
        val photo = photoPath ?: return
        val model = selectedModel
        val modelPath = (modelStates[model.id] as? OralLesionModelState.Installed)?.path ?: return
        if (!canAssess) return
        resetConsultation()
        val prompt = OralLesionPrompts.assessment(demographics)
        val device = platform.deviceInfo
        val now = platformCurrentTimeMillis()
        current = OralLesionBenchCase(
            caseId = "case_$now",
            createdAtMs = now,
            modelId = model.id,
            modelSha256 = model.sha256,
            modelFile = model.fileName,
            backend = "",
            soc = device.soc,
            device = device.device,
            appVersion = device.appVersion,
            photoSource = photoSource?.name?.lowercase().orEmpty(),
            photoFile = "",
            age = demographics.age,
            sex = sex.name.lowercase(),
            site = site.trim(),
            turn1Prompt = prompt,
            turn1Output = "",
        )
        run(OralLesionPhase.TURN1) {
            val turn = platform.startConversation(modelPath, photo, OralLesionPrompts.SYSTEM, prompt) { turn1Text += it }
            turn1Text = turn.text
            val answer = OralLesionAnswer.parse(turn.text)
            val result = OralLesionGuardrail.evaluate(turn.text, redFlags.toSet())
            onGuardrail(result, "turn1")
            questions.addAll(answer.questions.take(OralLesionPrompts.MAX_QUESTIONS))
            answers.addAll(List(questions.size) { null })
            update(photo) {
                copy(
                    backend = turn.backend,
                    turn1Output = turn.text,
                    metrics = metrics + turn.metrics.copy(turn = "turn1"),
                    guardrail = this.guardrail + result.record("turn1"),
                )
            }
            val spoken = OralLesionSpeech.assessment(answer, result, firstOfSession = !spokeNotice)
            spokeNotice = true
            platform.showOnGlasses(spoken)
            phase = if (questions.isNotEmpty()) OralLesionPhase.QUESTIONS else OralLesionPhase.SUMMARY
            say(spoken, result) { afterTurn { if (questions.isNotEmpty()) askQuestion(0) else onSummary() } }
        }
    }

    private fun askQuestion(index: Int) {
        currentQuestion = index
        answerDraft = answers.getOrNull(index).orEmpty()
        val text = OralLesionSpeech.question(index, questions.size, questions[index])
        platform.showOnGlasses(text)
        say(text) { afterTurn { if (phase == OralLesionPhase.QUESTIONS && currentQuestion == index) listenForAnswer(index) } }
    }

    private fun listenForAnswer(index: Int) {
        if (listening) return
        listening = true
        platform.listen(
            onPartial = { answerDraft = it },
            onFinal = { text -> stopListening(); answerDraft = text; onAnswer(index, text) },
            onError = { stopListening(); status = it },
        )
    }

    fun onListenAnswer() {
        if (listening) stopListening() else if (currentQuestion >= 0) listenForAnswer(currentQuestion)
    }

    fun onAnswerDraftChange(text: String) {
        answerDraft = text
    }

    fun onSubmitAnswer() {
        if (currentQuestion >= 0) onAnswer(currentQuestion, answerDraft)
    }

    /** "Não sei": the training data's "não documentado no prontuário". */
    fun onSkipAnswer() {
        if (currentQuestion >= 0) onAnswer(currentQuestion, null)
    }

    private fun onAnswer(index: Int, text: String?) {
        if (phase != OralLesionPhase.QUESTIONS || index != currentQuestion) return
        stopListening()
        val answer = text?.trim()?.takeIf { it.isNotEmpty() }
        answers[index] = answer
        if (answer != null) {
            OralLesionGuardrail.redFlagsIn(questions[index], answer).forEach { if (it !in redFlags) redFlags += it }
        }
        if (index + 1 < questions.size) askQuestion(index + 1) else onSendAnswers()
    }

    fun onToggleRedFlag(flag: RedFlag) {
        if (flag in redFlags) redFlags -= flag else redFlags += flag
    }

    /** Turn 2: every answer in the turn-1 conversation, then the record. */
    fun onSendAnswers() {
        if (phase != OralLesionPhase.QUESTIONS) return
        stopListening()
        platform.stopSpeaking()
        currentQuestion = -1
        val qa = questions.mapIndexed { i, q -> DentistAnswer(q, answers.getOrNull(i)) }
        val prompt = OralLesionPrompts.answers(qa)
        run(OralLesionPhase.TURN2) {
            val turn = platform.continueConversation(prompt) { turn2Text += it }
            turn2Text = turn.text
            val result = OralLesionGuardrail.evaluate(turn.text, redFlags.toSet())
            onGuardrail(result, "turn2")
            update(null) {
                copy(
                    qa = qa.map { OralLesionQa(it.question, it.answer) },
                    turn2Prompt = prompt,
                    turn2Output = turn.text,
                    metrics = metrics + turn.metrics.copy(turn = "turn2"),
                    guardrail = this.guardrail + result.record("turn2"),
                )
            }
            val spoken = OralLesionSpeech.revision(OralLesionAnswer.parse(turn.text), result, firstOfSession = !spokeNotice)
            spokeNotice = true
            platform.showOnGlasses(spoken)
            phase = OralLesionPhase.SUMMARY
            say(spoken, result) { afterTurn { onSummary() } }
        }
    }

    /** The clinical record: a new conversation with the photo and the recorded facts. */
    fun onSummary() {
        val photo = photoPath ?: return
        val modelPath = (modelStates[selectedModel.id] as? OralLesionModelState.Installed)?.path ?: return
        if (busy || current == null || summaryText.isNotEmpty()) return
        val qa = questions.mapIndexed { i, q -> DentistAnswer(q, answers.getOrNull(i)) }
        val prompt = OralLesionPrompts.summary(demographics, OralLesionPrompts.caseFacts(site, qa, redFlags.toSet()))
        run(OralLesionPhase.SUMMARY) {
            val turn = platform.startConversation(modelPath, photo, OralLesionPrompts.SYSTEM, prompt) { summaryText += it }
            summaryText = turn.text
            val result = OralLesionGuardrail.evaluateRecord(turn.text, redFlags.toSet())
            onGuardrail(result, "summary")
            update(null) {
                copy(
                    qa = qa.map { OralLesionQa(it.question, it.answer) },
                    summaryPrompt = prompt,
                    summaryOutput = turn.text,
                    metrics = metrics + turn.metrics.copy(turn = "summary"),
                    guardrail = this.guardrail + result.record("summary"),
                )
            }
            val spoken = OralLesionSpeech.summary(turn.text, result)
            platform.showOnGlasses(spoken)
            say(spoken, result)
            phase = OralLesionPhase.DONE
            editingCaseId = current?.caseId
            dentist = current?.dentist ?: OralLesionDentistFields()
        }
    }

    private fun onGuardrail(result: GuardrailResult, turn: String) {
        guardrail = result
        if (result.addedReferral) {
            PlatformLogger.i(TAG, "Guardrail fired without the model advising referral (turn=$turn urgent=${result.urgent})")
        }
    }

    fun onStop() {
        job?.cancel()
        platform.cancelGeneration()
        platform.stopSpeaking()
        stopListening()
        busy = false
        if (phase != OralLesionPhase.IDLE) phase = OralLesionPhase.DONE
    }

    /** Clears the consultation for the next patient; saved cases stay in the log. */
    fun onNewCase() {
        onStop()
        resetConsultation()
        phase = OralLesionPhase.IDLE
        photoPath = null
        photoSource = null
        ageText = ""
        sex = PatientSex.UNKNOWN
        site = ""
        redFlags.clear()
        current = null
        editingCaseId = null
        dentist = OralLesionDentistFields()
        spokeNotice = false
    }

    private fun resetConsultation() {
        turn1Text = ""
        turn2Text = ""
        summaryText = ""
        guardrail = null
        questions.clear()
        answers.clear()
        currentQuestion = -1
        answerDraft = ""
        status = null
    }

    fun onEditCase(caseId: String) {
        editingCaseId = caseId
        dentist = cases.firstOrNull { it.caseId == caseId }?.dentist ?: OralLesionDentistFields()
    }

    fun onDentistChange(fields: OralLesionDentistFields) {
        dentist = fields
    }

    fun onSaveDentistFields() {
        val id = editingCaseId ?: return
        val base = if (current?.caseId == id) current else cases.firstOrNull { it.caseId == id }
        base ?: return
        viewModelScope.launch {
            runCatching { platform.saveCase(base.copy(dentist = dentist), null) }
                .onSuccess { saved -> if (current?.caseId == id) current = saved; status = null; refreshCases() }
                .onFailure { if (it is CancellationException) throw it; status = it.message }
        }
    }

    fun onDeleteCase(caseId: String) {
        viewModelScope.launch {
            runCatching { platform.deleteCase(caseId) }.onFailure { if (it is CancellationException) throw it; status = it.message }
            if (editingCaseId == caseId) editingCaseId = null
            if (current?.caseId == caseId) current = null
            refreshCases()
        }
    }

    fun onExport() = platform.exportCases()

    private fun refreshCases() {
        viewModelScope.launch {
            val loaded = runCatching { platform.cases() }.getOrElse { if (it is CancellationException) throw it; emptyList() }
            cases.clear()
            cases.addAll(loaded.sortedByDescending { it.createdAtMs })
        }
    }

    private fun stopListening() {
        if (!listening) return
        listening = false
        platform.stopListening()
    }

    /** Speaks [text] when replies are spoken; the guardrail's referral line is always spoken. */
    private fun say(text: String, guard: GuardrailResult? = null, then: () -> Unit = {}) {
        val spoken = if (state.speakReplies) text else guard?.line
        if (spoken != null) platform.speak(spoken, then) else then()
    }

    /** Runs [action] on the main thread once the running model turn has finished. */
    private fun afterTurn(action: () -> Unit) {
        val running = job
        viewModelScope.launch {
            running?.join()
            action()
        }
    }

    private suspend fun update(photo: String?, change: OralLesionBenchCase.() -> OralLesionBenchCase) {
        val case = current ?: return
        current = platform.saveCase(case.change(), photo)
        refreshCases()
    }

    private fun run(next: OralLesionPhase, block: suspend () -> Unit) {
        busy = true
        phase = next
        job = viewModelScope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                PlatformLogger.w(TAG, "Oral lesion turn failed", error)
                status = error.message ?: "model error"
                phase = OralLesionPhase.DONE
            } finally {
                busy = false
            }
        }
    }

    override fun onCleared() {
        platform.cancelGeneration()
        platform.release()
    }

    private companion object {
        const val TAG = "OralLesionBench"
    }
}

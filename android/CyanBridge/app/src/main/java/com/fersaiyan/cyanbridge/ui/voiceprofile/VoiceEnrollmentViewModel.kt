package com.fersaiyan.cyanbridge.ui.voiceprofile

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fersaiyan.cyanbridge.ai.speaker.EnrollmentInfo
import com.fersaiyan.cyanbridge.ai.speaker.EnrollmentQuality
import com.fersaiyan.cyanbridge.ai.speaker.PhraseIssue
import com.fersaiyan.cyanbridge.ai.speaker.SpeakerMath
import com.fersaiyan.cyanbridge.ai.speaker.SpeakerModelStore
import com.fersaiyan.cyanbridge.ai.speaker.SpeakerModels
import com.fersaiyan.cyanbridge.ai.speaker.VoiceEnrollmentRecorder
import com.fersaiyan.cyanbridge.ai.speaker.VoiceEnrollmentStore
import com.fersaiyan.cyanbridge.ai.speaker.VoiceProfile
import com.fersaiyan.cyanbridge.shared.ui.onboarding.VoiceEnrollmentMessage
import com.fersaiyan.cyanbridge.shared.ui.onboarding.VoiceEnrollmentPhase
import com.fersaiyan.cyanbridge.shared.ui.onboarding.VoiceEnrollmentUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class VoiceEnrollmentViewModel(application: Application) : AndroidViewModel(application) {
    private val store = VoiceEnrollmentStore(application)
    private val recorder = VoiceEnrollmentRecorder(application)
    private var recordingJob: Job? = null
    private var inputRoute = "unknown"
    private var consentAtMs = 0L

    private val _state = MutableStateFlow(VoiceEnrollmentUiState())
    val state: StateFlow<VoiceEnrollmentUiState> = _state.asStateFlow()

    /** Mic loudness 0..1, kept apart from [state] so 50 Hz updates do not rebuild the whole screen. */
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level.asStateFlow()

    init {
        // A previous, abandoned enrollment never leaks into this one.
        store.discardPending()
    }

    fun setMicGranted(granted: Boolean) = _state.update { it.copy(micGranted = granted) }

    fun setConsent(given: Boolean) {
        consentAtMs = if (given) System.currentTimeMillis() else 0L
        _state.update { it.copy(consentGiven = given) }
    }

    fun recordCurrentPhrase() {
        val current = _state.value
        if (!current.consentGiven || !current.micGranted || current.phase != VoiceEnrollmentPhase.READY) return
        val index = current.currentIndex
        recordingJob = viewModelScope.launch {
            _state.update { it.copy(phase = VoiceEnrollmentPhase.LISTENING, message = null, errorDetail = null) }
            try {
                val phrase = recorder.record(onLevel = { _level.value = it })
                if (phrase == null) {
                    fail(VoiceEnrollmentMessage.NO_SPEECH)
                    return@launch
                }
                when (EnrollmentQuality.check(phrase.pcm)) {
                    PhraseIssue.NO_SPEECH -> fail(VoiceEnrollmentMessage.NO_SPEECH)
                    PhraseIssue.TOO_SHORT -> fail(VoiceEnrollmentMessage.TOO_SHORT)
                    PhraseIssue.CLIPPED -> fail(VoiceEnrollmentMessage.CLIPPED)
                    null -> {
                        withContext(Dispatchers.IO) { store.savePendingPhrase(index, phrase.pcm) }
                        inputRoute = phrase.inputRoute
                        accept(index)
                    }
                }
            } catch (cancelled: CancellationException) {
                _state.update { it.copy(phase = VoiceEnrollmentPhase.READY) }
                throw cancelled
            } catch (error: Exception) {
                Log.w(TAG, "Enrollment recording failed: ${error.javaClass.simpleName}")
                _state.update {
                    it.copy(
                        phase = VoiceEnrollmentPhase.READY,
                        message = VoiceEnrollmentMessage.ERROR,
                        errorDetail = error.message ?: error.javaClass.simpleName,
                    )
                }
            }
        }
    }

    fun cancelRecording() {
        recordingJob?.cancel()
    }

    /** Lets the user redo any finished phrase by tapping its indicator. */
    fun selectPhrase(index: Int) {
        if (_state.value.phase != VoiceEnrollmentPhase.READY || index !in 0 until _state.value.phraseCount) return
        _state.update { it.copy(currentIndex = index, message = null, errorDetail = null) }
    }

    private fun fail(message: VoiceEnrollmentMessage) = _state.update {
        it.copy(phase = VoiceEnrollmentPhase.READY, message = message, errorDetail = null)
    }

    private suspend fun accept(index: Int) {
        val completed = _state.value.completed + index
        val next = (0 until _state.value.phraseCount).firstOrNull { it !in completed }
        if (next != null) {
            _state.update { it.copy(phase = VoiceEnrollmentPhase.READY, completed = completed, currentIndex = next, message = null) }
            return
        }
        _state.update { it.copy(phase = VoiceEnrollmentPhase.CHECKING, completed = completed) }
        finish()
    }

    /**
     * With the model installed, embed the phrases first: one phrase that disagrees with the others
     * (another voice, heavy noise) is sent back for re-recording before anything is committed.
     */
    private suspend fun finish() {
        val context = getApplication<Application>()
        val count = _state.value.phraseCount
        val spec = SpeakerModels.DEFAULT
        val phrases = withContext(Dispatchers.IO) { (0 until count).map { store.loadPendingPhrase(it) } }
        if (phrases.any { it == null }) {
            _state.update { it.copy(phase = VoiceEnrollmentPhase.READY, completed = emptySet(), currentIndex = 0) }
            fail(VoiceEnrollmentMessage.NO_SPEECH)
            return
        }
        val modelFile = withContext(Dispatchers.IO) { SpeakerModelStore.resolve(context, spec) }
        val perPhrase = if (modelFile != null) {
            runCatching { VoiceProfile.embedPhrases(context, phrases.filterNotNull(), spec) }
                .onFailure { Log.w(TAG, "Enrollment embedding failed: ${it.javaClass.simpleName}") }
                .getOrNull()
        } else null
        if (perPhrase != null && perPhrase.all { it.isNotEmpty() }) {
            val scores = SpeakerMath.leaveOneOutScores(perPhrase)
            val worst = scores.indices.minBy { scores[it] }
            Log.i(TAG, "Enrollment leave-one-out min=${"%.3f".format(scores[worst])}")
            if (scores[worst] < EnrollmentQuality.MIN_LEAVE_ONE_OUT) {
                _state.update {
                    it.copy(
                        phase = VoiceEnrollmentPhase.READY,
                        completed = it.completed - worst,
                        currentIndex = worst,
                        message = VoiceEnrollmentMessage.INCONSISTENT,
                        errorDetail = null,
                    )
                }
                return
            }
        }
        val now = System.currentTimeMillis()
        withContext(Dispatchers.IO) {
            store.complete(
                EnrollmentInfo(
                    languageTag = context.resources.configuration.locales[0].toLanguageTag(),
                    inputRoute = inputRoute,
                    phraseCount = count,
                    consentAtMs = consentAtMs.takeIf { it > 0 } ?: now,
                    completedAtMs = now,
                ),
            )
            if (perPhrase != null) VoiceProfile.saveTemplate(context, spec, perPhrase)
        }
        _state.update {
            it.copy(
                phase = VoiceEnrollmentPhase.DONE,
                message = if (perPhrase == null) VoiceEnrollmentMessage.DONE_MODEL_PENDING else VoiceEnrollmentMessage.DONE_READY,
                errorDetail = null,
            )
        }
    }

    override fun onCleared() {
        recordingJob?.cancel()
        if (_state.value.phase != VoiceEnrollmentPhase.DONE) store.discardPending()
    }

    private companion object {
        const val TAG = "VoiceEnrollment"
    }
}

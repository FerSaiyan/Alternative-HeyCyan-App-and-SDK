package com.fersaiyan.cyanbridge.shared.ui.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fersaiyan.cyanbridge.shared.generated.resources.*
import com.fersaiyan.cyanbridge.shared.icons.AppIcon
import com.fersaiyan.cyanbridge.shared.icons.imageVector
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

enum class VoiceEnrollmentPhase { READY, LISTENING, CHECKING, DONE }

enum class VoiceEnrollmentMessage {
    NO_SPEECH, TOO_SHORT, CLIPPED, INCONSISTENT, ERROR, DONE_READY, DONE_MODEL_PENDING;

    val isError: Boolean get() = this != DONE_READY && this != DONE_MODEL_PENDING
}

/** Platform-neutral enrollment state; the platform owns recording, the speaker model and storage. */
data class VoiceEnrollmentUiState(
    val phraseCount: Int = VOICE_ENROLLMENT_PHRASE_COUNT,
    val currentIndex: Int = 0,
    val completed: Set<Int> = emptySet(),
    val phase: VoiceEnrollmentPhase = VoiceEnrollmentPhase.READY,
    val consentGiven: Boolean = false,
    val micGranted: Boolean = false,
    val message: VoiceEnrollmentMessage? = null,
    /** Platform error text shown with [VoiceEnrollmentMessage.ERROR]. */
    val errorDetail: String? = null,
)

const val VOICE_ENROLLMENT_PHRASE_COUNT = 3

private val PHRASES = listOf(Res.string.voice_enroll_phrase_1, Res.string.voice_enroll_phrase_2, Res.string.voice_enroll_phrase_3)

/**
 * Voice enrollment step. Follows FeatureOnboardingScreen's layout (header, progress, scrolling
 * body, back/next row) and adds expressive motion with stable APIs only: spring-driven scale and
 * corner morphs, and a breathing voice shape that reacts to the microphone [level] (0..1).
 * [stepIndex]/[stepCount] show the onboarding header; null shows a standalone screen.
 */
@OptIn(ExperimentalResourceApi::class)
@Composable
fun VoiceEnrollmentScreen(
    state: VoiceEnrollmentUiState,
    level: Float,
    stepIndex: Int?,
    stepCount: Int?,
    onRequestMicrophone: () -> Unit,
    onRecord: () -> Unit,
    onCancelRecording: () -> Unit,
    onSelectPhrase: (Int) -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit,
) {
    val done = state.phase == VoiceEnrollmentPhase.DONE
    Scaffold(contentWindowInsets = WindowInsets.safeDrawing) { insets ->
        Column(Modifier.fillMaxSize().padding(insets)) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 18.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("CYANBRIDGE", style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    if (stepIndex != null && stepCount != null) {
                        Text("${stepIndex + 1} / $stepCount", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (stepIndex != null && stepCount != null) {
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = { (stepIndex + 1f) / stepCount.coerceAtLeast(1) },
                        modifier = Modifier.fillMaxWidth().height(6.dp),
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                }
            }
            BoxWithConstraints(Modifier.weight(1f)) {
            // Small screens or large fonts leave little room above the pinned action: the decorative
            // shape goes, and the phrase to say comes before the consent notice so it stays in view.
            val compact = maxHeight < 520.dp || LocalDensity.current.fontScale > 1.3f
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (!compact) {
                    VoiceShape(phase = state.phase, level = level, size = 136.dp, modifier = Modifier.padding(top = 8.dp))
                    Spacer(Modifier.height(16.dp))
                }
                Text(stringResource(Res.string.voice_enroll_title), style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
                Spacer(Modifier.height(8.dp))
                Text(stringResource(Res.string.voice_enroll_desc), style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                if (!done) {
                    Spacer(Modifier.height(16.dp))
                    PhraseIndicators(state, onSelectPhrase)
                    Spacer(Modifier.height(8.dp))
                    PhraseCard(state)
                }
                // A take's result goes right under the phrase, where the eye (and TalkBack) already is.
                state.message?.let { message ->
                    Spacer(Modifier.height(12.dp))
                    MessageCard(message, state.errorDetail)
                }
                if (!done) {
                    // The consent notice: the record button below reads "Agree and record" until the first take.
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(Res.string.voice_enroll_privacy), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                        modifier = Modifier.testTag("voice_enroll_privacy"))
                }
                Spacer(Modifier.height(24.dp))
            }
            }
            // The primary action stays pinned above the navigation row instead of scrolling away. Without
            // microphone access it asks for it; its first take is the consent.
            if (!done) {
                RecordButton(
                    phase = state.phase,
                    micGranted = state.micGranted,
                    consentGiven = state.consentGiven,
                    onRequestMicrophone = onRequestMicrophone,
                    onRecord = onRecord,
                    onCancel = onCancelRecording,
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp),
                )
            }
            Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onBack, Modifier.weight(1f).height(54.dp), shape = MaterialTheme.shapes.medium,
                    enabled = state.phase != VoiceEnrollmentPhase.CHECKING,
                ) {
                    Text(stringResource(if (stepIndex != null) Res.string.onboarding_back else Res.string.voice_enroll_close))
                }
                // Standalone (from Settings) there is nothing to skip to: only Close until done.
                if (stepIndex != null || done) {
                    Button(
                        onNext, Modifier.weight(1.35f).height(54.dp).testTag("voice_enroll_next"),
                        shape = MaterialTheme.shapes.medium,
                        enabled = state.phase == VoiceEnrollmentPhase.READY || done,
                    ) {
                        Text(
                            stringResource(if (done) Res.string.voice_enroll_continue else Res.string.voice_enroll_skip),
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}

/** A rounded shape whose corners, scale and colour spring between idle, listening and done. */
@Composable
private fun VoiceShape(phase: VoiceEnrollmentPhase, level: Float, size: Dp = 168.dp, modifier: Modifier = Modifier) {
    val k = size / 168.dp
    val listening = phase == VoiceEnrollmentPhase.LISTENING
    val done = phase == VoiceEnrollmentPhase.DONE
    val breathing = rememberInfiniteTransition()
    val breath by breathing.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(if (phase == VoiceEnrollmentPhase.CHECKING) 600 else 2_400), RepeatMode.Reverse),
    )
    val bouncy = spring<Float>(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow)
    val voice by animateFloatAsState(if (listening) level else 0f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMedium))
    val scale by animateFloatAsState(if (done) 1.06f else 1f, bouncy)
    val rotation by animateFloatAsState(if (listening) 45f else 0f, bouncy)
    // Squircle while idle, softer while listening, circle when done.
    val corner by animateDpAsState(
        when {
            done -> 80.dp * k
            listening -> 52.dp * k
            else -> 44.dp * k
        },
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
    )
    val container by animateColorAsState(
        when {
            done -> MaterialTheme.colorScheme.tertiaryContainer
            listening -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.primaryContainer
        },
    )
    val content by animateColorAsState(
        when {
            done -> MaterialTheme.colorScheme.onTertiaryContainer
            listening -> MaterialTheme.colorScheme.onPrimary
            else -> MaterialTheme.colorScheme.primary
        },
    )
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        // Halo: grows with the voice, breathes gently otherwise.
        Box(
            Modifier
                .size(148.dp * k)
                .graphicsLayer {
                    val s = 1f + 0.06f * breath + 0.22f * voice
                    scaleX = s; scaleY = s; rotationZ = -rotation / 2
                    alpha = 0.35f + 0.25f * voice
                }
                .clip(RoundedCornerShape(corner))
                .background(MaterialTheme.colorScheme.primaryContainer),
        )
        Box(
            Modifier
                .size(120.dp * k)
                .graphicsLayer {
                    val s = scale * (1f + 0.12f * voice)
                    scaleX = s; scaleY = s; rotationZ = rotation
                }
                .clip(RoundedCornerShape(corner))
                .background(container),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(
                targetState = done,
                transitionSpec = { (fadeIn() + scaleIn(spring(dampingRatio = Spring.DampingRatioMediumBouncy))) togetherWith fadeOut() },
            ) { finished ->
                Icon(
                    (if (finished) AppIcon.Check else AppIcon.Microphone).imageVector(), null,
                    Modifier.size(56.dp * k).graphicsLayer { rotationZ = -rotation },
                    tint = content,
                )
            }
        }
    }
}

/** Pills that stretch for the current phrase and fill when a phrase is accepted; tap one to redo it. */
@OptIn(ExperimentalResourceApi::class)
@Composable
private fun PhraseIndicators(state: VoiceEnrollmentUiState, onSelect: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(state.phraseCount) { index ->
            val current = index == state.currentIndex
            val complete = index in state.completed
            val width by animateDpAsState(if (current) 40.dp else 14.dp, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
            val color by animateColorAsState(
                when {
                    complete -> MaterialTheme.colorScheme.primary
                    current -> MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.outlineVariant
                },
            )
            val label = stringResource(Res.string.voice_enroll_phrase_progress, index + 1, state.phraseCount)
            Box(
                Modifier.minimumInteractiveComponentSize()
                    .clickable(enabled = state.phase == VoiceEnrollmentPhase.READY && complete) { onSelect(index) }
                    .semantics { contentDescription = label },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.height(14.dp).width(width).clip(CircleShape).background(color))
            }
        }
    }
}

@OptIn(ExperimentalResourceApi::class)
@Composable
private fun PhraseCard(state: VoiceEnrollmentUiState) {
    Card(
        Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(Res.string.voice_enroll_phrase_progress, state.currentIndex + 1, state.phraseCount),
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
            )
            AnimatedContent(
                targetState = state.currentIndex,
                transitionSpec = { (fadeIn() + scaleIn(initialScale = 0.92f)) togetherWith fadeOut() },
            ) { index ->
                Text(
                    "“${stringResource(PHRASES[index.coerceIn(PHRASES.indices)])}”",
                    style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@OptIn(ExperimentalResourceApi::class)
@Composable
private fun MessageCard(message: VoiceEnrollmentMessage, detail: String?) {
    val res: StringResource = when (message) {
        VoiceEnrollmentMessage.NO_SPEECH -> Res.string.voice_enroll_retry_no_speech
        VoiceEnrollmentMessage.TOO_SHORT -> Res.string.voice_enroll_retry_short
        VoiceEnrollmentMessage.CLIPPED -> Res.string.voice_enroll_retry_clipped
        VoiceEnrollmentMessage.INCONSISTENT -> Res.string.voice_enroll_retry_inconsistent
        VoiceEnrollmentMessage.ERROR -> Res.string.voice_enroll_error
        VoiceEnrollmentMessage.DONE_READY -> Res.string.voice_enroll_done_ready
        VoiceEnrollmentMessage.DONE_MODEL_PENDING -> Res.string.voice_enroll_done_pending
    }
    val error = message.isError
    Card(
        // Read out when it appears: the result of a take is otherwise silent for TalkBack users.
        Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }, shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.tertiaryContainer,
        ),
    ) {
        Text(
            if (message == VoiceEnrollmentMessage.ERROR) stringResource(res, detail.orEmpty()) else stringResource(res),
            Modifier.padding(16.dp),
            color = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onTertiaryContainer,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/**
 * Large pill that squishes into a squarer shape while pressed or listening, with a spring. Without
 * microphone access it is the "Allow microphone" action, so the step's next action is always on screen.
 */
@OptIn(ExperimentalResourceApi::class)
@Composable
private fun RecordButton(
    phase: VoiceEnrollmentPhase,
    micGranted: Boolean,
    consentGiven: Boolean,
    onRequestMicrophone: () -> Unit,
    onRecord: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val listening = phase == VoiceEnrollmentPhase.LISTENING
    val checking = phase == VoiceEnrollmentPhase.CHECKING
    val corner by animateDpAsState(
        if (pressed || listening) 16.dp else 36.dp,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
    )
    val askMicrophone = !micGranted && phase == VoiceEnrollmentPhase.READY
    Button(
        onClick = when {
            listening -> onCancel
            askMicrophone -> onRequestMicrophone
            else -> onRecord
        },
        enabled = phase == VoiceEnrollmentPhase.READY || listening,
        interactionSource = interaction,
        shape = RoundedCornerShape(corner),
        colors = if (listening) {
            ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)
        } else ButtonDefaults.buttonColors(),
        modifier = modifier.fillMaxWidth().heightIn(min = 72.dp).testTag("voice_enroll_record"),
    ) {
        if (checking) {
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp, color = MaterialTheme.colorScheme.onPrimary)
            Spacer(Modifier.width(12.dp))
        } else {
            Icon((if (listening) AppIcon.Stop else AppIcon.Microphone).imageVector(), null)
            Spacer(Modifier.width(10.dp))
        }
        Text(
            stringResource(
                when {
                    checking -> Res.string.voice_enroll_checking
                    listening -> Res.string.voice_enroll_listening
                    askMicrophone -> Res.string.voice_enroll_mic_grant
                    !consentGiven -> Res.string.voice_enroll_agree_record
                    else -> Res.string.voice_enroll_start
                },
            ),
            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
        )
    }
}

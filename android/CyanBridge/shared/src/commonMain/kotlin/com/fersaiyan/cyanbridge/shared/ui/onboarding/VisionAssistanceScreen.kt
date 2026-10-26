package com.fersaiyan.cyanbridge.shared.ui.onboarding

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccessibilityNew
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.fersaiyan.cyanbridge.shared.generated.resources.*
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.stringResource

/**
 * "Do you need vision assistance?" step. The host shows it only when a screen reader is running and
 * the user has not answered yet; everyone else can find the same choice in Settings > Accessibility.
 * When [screenReaderDetected] is true, "Yes" starts selected, but the user still confirms it.
 */
@OptIn(ExperimentalResourceApi::class)
@Composable
fun VisionAssistanceScreen(
    screenReaderDetected: Boolean,
    showSetupProgress: Boolean,
    onConfirm: (enabled: Boolean) -> Unit,
) {
    var selection by rememberSaveable { mutableStateOf(if (screenReaderDetected) true else null) }
    Scaffold(contentWindowInsets = WindowInsets.safeDrawing) { insets ->
        Column(Modifier.fillMaxSize().padding(insets)) {
            Column(Modifier.padding(horizontal = 24.dp, vertical = 18.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("CYANBRIDGE", style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    if (showSetupProgress) {
                        Text(stringResource(Res.string.onboarding_vision_setup_label), style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (showSetupProgress) {
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(progress = { 0.1f }, Modifier.fillMaxWidth().height(6.dp),
                        trackColor = MaterialTheme.colorScheme.surfaceVariant)
                }
            }
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(10.dp))
                Box(Modifier.size(78.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                    contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.AccessibilityNew, null, Modifier.size(42.dp), tint = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.height(20.dp))
                Text(stringResource(Res.string.onboarding_vision_title),
                    Modifier.semantics { heading() },
                    style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(Res.string.onboarding_vision_body), style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                Spacer(Modifier.height(22.dp))
                Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    VisionAssistanceOption(
                        icon = Icons.Outlined.AccessibilityNew,
                        title = stringResource(Res.string.onboarding_vision_option_yes),
                        body = stringResource(Res.string.onboarding_vision_option_yes_body),
                        selected = selection == true,
                        testTag = "vision_assistance_yes",
                        onSelect = { selection = true },
                    )
                    VisionAssistanceOption(
                        icon = Icons.Outlined.Visibility,
                        title = stringResource(Res.string.onboarding_vision_option_no),
                        body = stringResource(Res.string.onboarding_vision_option_no_body),
                        selected = selection == false,
                        testTag = "vision_assistance_no",
                        onSelect = { selection = false },
                    )
                }
                if (screenReaderDetected) {
                    Text(stringResource(Res.string.onboarding_vision_screen_reader_note),
                        Modifier.padding(top = 14.dp), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center)
                }
                Spacer(Modifier.height(12.dp))
            }
            Column(Modifier.padding(24.dp)) {
                Button({ selection?.let(onConfirm) }, Modifier.fillMaxWidth().height(54.dp).testTag("vision_assistance_continue"),
                    enabled = selection != null, shape = MaterialTheme.shapes.medium) {
                    Text(stringResource(Res.string.onboarding_continue), fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun VisionAssistanceOption(
    icon: ImageVector,
    title: String,
    body: String,
    selected: Boolean,
    testTag: String,
    onSelect: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val container by animateColorAsState(
        if (selected) colors.primaryContainer else colors.surface,
        spring(stiffness = Spring.StiffnessMediumLow),
    )
    val outline by animateColorAsState(
        if (selected) colors.primary else colors.outlineVariant,
        spring(stiffness = Spring.StiffnessMediumLow),
    )
    // The whole card is one radio button for TalkBack/VoiceOver; the inner RadioButton is visual only.
    Card(
        Modifier.fillMaxWidth().testTag(testTag)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = container),
        border = BorderStroke(if (selected) 2.dp else 1.dp, outline),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 76.dp).padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(Modifier.size(46.dp).background(if (selected) colors.primary else colors.primaryContainer, CircleShape),
                contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(24.dp), tint = if (selected) colors.onPrimary else colors.primary)
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(body, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            RadioButton(selected = selected, onClick = null)
        }
    }
}

package com.fersaiyan.cyanbridge.shared.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.fersaiyan.cyanbridge.shared.generated.resources.*
import com.fersaiyan.cyanbridge.shared.icons.AppIcon
import com.fersaiyan.cyanbridge.shared.icons.imageVector
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.stringResource

data class OnboardingLanguageOption(val id: String, val label: String)

@OptIn(ExperimentalResourceApi::class)
@Composable
fun WelcomeScreen(
    languageOptions: List<OnboardingLanguageOption>, selectedLanguageId: String,
    languageSelectionComplete: Boolean, onLanguageSelected: (OnboardingLanguageOption) -> Unit,
    onStartSetup: () -> Unit,
) {
    var languageMenuOpen by remember { mutableStateOf(false) }
    val selectedLanguage = languageOptions.firstOrNull { it.id == selectedLanguageId }
    Scaffold(contentWindowInsets = WindowInsets.safeDrawing) { innerPadding ->
        Column(
            Modifier.fillMaxSize().padding(innerPadding).consumeWindowInsets(innerPadding)
                .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        stringResource(Res.string.onboarding_language_selector_label),
                        Modifier.padding(end = 6.dp, bottom = 5.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Box {
                    Card(
                        Modifier.clickable { languageMenuOpen = true },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(18.dp),
                    ) {
                        Row(
                            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text("🌐", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                selectedLanguage?.label ?: stringResource(Res.string.onboarding_language_title),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text("▾", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    DropdownMenu(languageMenuOpen, { languageMenuOpen = false }) {
                        languageOptions.forEach { option ->
                            DropdownMenuItem({ Text(option.label) }, { onLanguageSelected(option); languageMenuOpen = false })
                        }
                    }
                }
                }
            }
            Spacer(Modifier.height(28.dp))
            Box(
                Modifier.size(116.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) { Icon(AppIcon.Glasses.imageVector(), null, Modifier.size(70.dp), tint = MaterialTheme.colorScheme.primary) }
            Spacer(Modifier.height(24.dp))
            Text(stringResource(Res.string.onboarding_welcome_title), style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Spacer(Modifier.height(10.dp))
            Text(stringResource(Res.string.onboarding_welcome_body), style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            Spacer(Modifier.height(28.dp))
            Card(
                Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(28.dp),
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    WelcomeBenefit(AppIcon.Sync, stringResource(Res.string.onboarding_benefit_bridge_title), stringResource(Res.string.onboarding_benefit_bridge_body))
                    WelcomeBenefit(AppIcon.Model, stringResource(Res.string.onboarding_benefit_ai_title), stringResource(Res.string.onboarding_benefit_ai_body))
                    WelcomeBenefit(AppIcon.Camera, stringResource(Res.string.onboarding_benefit_capture_title), stringResource(Res.string.onboarding_benefit_capture_body))
                }
            }
            Spacer(Modifier.height(28.dp))
            Button(onStartSetup, Modifier.fillMaxWidth().height(56.dp), enabled = languageSelectionComplete,
                shape = RoundedCornerShape(18.dp)) {
                Text(stringResource(Res.string.onboarding_start_setup), fontWeight = FontWeight.Bold)
            }
            Text(stringResource(Res.string.onboarding_duration_note), Modifier.padding(top = 12.dp, bottom = 8.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun WelcomeBenefit(icon: AppIcon, title: String, body: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(46.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center) {
            Icon(icon.imageVector(), null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

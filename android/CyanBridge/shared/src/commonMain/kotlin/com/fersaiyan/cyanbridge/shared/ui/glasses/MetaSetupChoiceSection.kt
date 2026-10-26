package com.fersaiyan.cyanbridge.shared.ui.glasses

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import com.fersaiyan.cyanbridge.shared.generated.resources.*
import com.fersaiyan.cyanbridge.shared.glasses.MetaRegistrationPath
import com.fersaiyan.cyanbridge.shared.glasses.MetaRegistrationPhase
import com.fersaiyan.cyanbridge.shared.glasses.MetaRegistrationStore
import com.fersaiyan.cyanbridge.shared.glasses.MetaSetupChoice
import com.fersaiyan.cyanbridge.shared.glasses.PrefsMetaRegistrationStore
import com.fersaiyan.cyanbridge.shared.glasses.metaSetupChoicePresentation
import com.fersaiyan.cyanbridge.shared.ui.rememberScreenViewModel
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** The saved setup choice and recorded registration path behind [MetaSetupChoiceSection]. */
class MetaSetupChoiceViewModel(private val store: MetaRegistrationStore) : ViewModel() {
    var choice by mutableStateOf(store.choice)
        private set
    var recordedPath by mutableStateOf(store.path)
        private set

    fun choose(choice: MetaSetupChoice) {
        store.choice = choice
        this.choice = choice
    }

    /** Re-reads the store; the platform records the path when registration finishes. */
    fun refresh() {
        choice = store.choice
        recordedPath = store.path
    }
}

/**
 * Meta setup options shown on the pairing screen (Android) and the Meta panel (iOS): Developer
 * Mode or an email invite to the release channel, with the steps for the chosen one. After
 * registration it shows the path Meta AI took. The platform performs [onRegister] and
 * [onRequestAccess] (the beta access form).
 */
@Composable
fun MetaSetupChoiceSection(
    phase: MetaRegistrationPhase,
    appIdConfigured: Boolean,
    onRegister: () -> Unit,
    onRequestAccess: () -> Unit,
    modifier: Modifier = Modifier,
    onChoiceChanged: (MetaSetupChoice) -> Unit = {},
) {
    val viewModel = rememberScreenViewModel("meta_setup_choice") { MetaSetupChoiceViewModel(PrefsMetaRegistrationStore()) }
    LaunchedEffect(phase) { viewModel.refresh() }
    val presentation = metaSetupChoicePresentation(phase, viewModel.choice, viewModel.recordedPath, appIdConfigured)

    if (!presentation.showChoices) {
        val pathLabel = viewModel.recordedPath.labelResource()
        if (pathLabel == null && !presentation.showInvitedOnDeveloperPath) return
        Column(modifier.testTag("meta_setup_choice"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            pathLabel?.let { MetaRegistrationPathText(it) }
            if (presentation.showInvitedOnDeveloperPath) {
                Text(
                    stringResource(Res.string.meta_setup_invited_on_dev_path),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        return
    }

    Card(modifier = modifier.fillMaxWidth().testTag("meta_setup_choice")) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(Res.string.meta_setup_choice_title), fontWeight = FontWeight.SemiBold)
            if (presentation.showDeveloperModeLost) {
                Text(
                    stringResource(
                        if (presentation.choice == MetaSetupChoice.EMAIL_INVITE) Res.string.meta_setup_disconnected_invite
                        else Res.string.meta_setup_dev_mode_lost,
                    ),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("meta_setup_dev_mode_lost"),
                )
                Button(onClick = onRegister, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(stringResource(Res.string.meta_setup_register_again))
                }
            }
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ChoiceRow(
                    title = Res.string.meta_setup_choice_dev_mode,
                    detail = Res.string.meta_setup_choice_dev_mode_detail,
                    selected = presentation.choice == MetaSetupChoice.DEVELOPER_MODE,
                    tag = "meta_setup_choice_dev_mode",
                ) {
                    viewModel.choose(MetaSetupChoice.DEVELOPER_MODE)
                    onChoiceChanged(MetaSetupChoice.DEVELOPER_MODE)
                }
                if (presentation.emailInviteAvailable) {
                    ChoiceRow(
                        title = Res.string.meta_setup_choice_email,
                        detail = Res.string.meta_setup_choice_email_detail,
                        selected = presentation.choice == MetaSetupChoice.EMAIL_INVITE,
                        tag = "meta_setup_choice_email",
                    ) {
                        viewModel.choose(MetaSetupChoice.EMAIL_INVITE)
                        onChoiceChanged(MetaSetupChoice.EMAIL_INVITE)
                    }
                } else {
                    Text(
                        stringResource(Res.string.meta_setup_choice_email_unavailable),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            when (presentation.choice) {
                MetaSetupChoice.DEVELOPER_MODE -> Steps(
                    Res.string.meta_setup_dev_step_1,
                    Res.string.meta_setup_dev_step_2,
                    Res.string.meta_setup_dev_step_3,
                )
                MetaSetupChoice.EMAIL_INVITE -> {
                    Steps(Res.string.meta_setup_email_step_1)
                    OutlinedButton(
                        onClick = onRequestAccess,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("meta_setup_open_form"),
                    ) {
                        Text(stringResource(Res.string.meta_setup_open_form))
                    }
                    Steps(
                        Res.string.meta_setup_email_step_2,
                        Res.string.meta_setup_email_step_3,
                        Res.string.meta_setup_email_step_4,
                    )
                }
                MetaSetupChoice.NONE -> Unit
            }
            if (presentation.canRegister) {
                Button(
                    onClick = onRegister,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("meta_setup_register"),
                ) {
                    Text(stringResource(Res.string.meta_setup_register))
                }
            }
        }
    }
}

/** "Meta AI connection: Developer Mode", for device info. */
@Composable
fun MetaRegistrationPathText(path: StringResource, modifier: Modifier = Modifier) {
    Text(
        stringResource(Res.string.meta_setup_path_label, stringResource(path)),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.testTag("meta_registration_path"),
    )
}

/** The label of a recorded path, or null while it is unknown. */
fun MetaRegistrationPath.labelResource(): StringResource? = when (this) {
    MetaRegistrationPath.DEVELOPER_MODE -> Res.string.meta_setup_path_developer_mode
    MetaRegistrationPath.RELEASE_CHANNEL -> Res.string.meta_setup_path_release_channel
    MetaRegistrationPath.UNKNOWN -> null
}

@Composable
private fun ChoiceRow(
    title: StringResource,
    detail: StringResource,
    selected: Boolean,
    tag: String,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Column {
            Text(stringResource(title), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(detail),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Steps(vararg steps: StringResource) {
    steps.forEach { step -> Text(stringResource(step), style = MaterialTheme.typography.bodyMedium) }
}

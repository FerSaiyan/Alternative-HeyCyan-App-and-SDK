package com.fersaiyan.cyanbridge.shared.ui.glasses

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.fersaiyan.cyanbridge.shared.glasses.MetaPairingIssue
import com.fersaiyan.cyanbridge.shared.glasses.MetaPairingIssueAction
import com.fersaiyan.cyanbridge.shared.glasses.label

/**
 * A Meta pairing failure with every useful way out, stacked (Material 3 for more than two actions): the issue's main
 * action, its next steps (Open Meta AI, Try again, Install Meta AI, Request access on the website), Send logs unless
 * the issue is only a setup step, and Close. Shared by the Meta setup screen and the glasses dashboard.
 */
@Composable
fun MetaPairingIssueDialog(
    issue: MetaPairingIssue,
    onAction: (MetaPairingIssueAction) -> Unit,
    onSendLogs: () -> Unit,
    onDismiss: () -> Unit,
    icon: (@Composable () -> Unit)? = { WarningDialogIcon() },
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("meta_pairing_issue"),
        icon = icon,
        title = { Text(issue.title) },
        text = { Text(issue.message) },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                IssueButton(issue.primaryLabel) { onDismiss(); onAction(issue.action) }
                issue.secondaryActions.forEach { action ->
                    IssueButton(action.label()) { onDismiss(); onAction(action) }
                }
                if (issue.offerLogs) IssueButton("Send logs") { onDismiss(); onSendLogs() }
                IssueButton("Close", onClick = onDismiss)
            }
        },
    )
}

@Composable
private fun IssueButton(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.heightIn(min = 48.dp), shapes = ButtonDefaults.shapes()) {
        Text(label)
    }
}

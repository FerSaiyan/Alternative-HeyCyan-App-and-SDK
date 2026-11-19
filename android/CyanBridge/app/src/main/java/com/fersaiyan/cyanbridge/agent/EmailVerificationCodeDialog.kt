package com.fersaiyan.cyanbridge.agent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MarkEmailRead
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EmailVerificationCodeDialog(
    email: String,
    initialMessage: String,
    verificationLinkAvailable: Boolean,
    verifying: Boolean,
    errorMessage: String?,
    onCodeChanged: () -> Unit,
    onVerify: (String) -> Unit,
    onResend: () -> Unit,
    onOpenLink: (() -> Unit)?,
    onDismissRequest: () -> Unit,
) {
    var code by rememberSaveable(email) { mutableStateOf("") }
    var localError by rememberSaveable(email) { mutableStateOf<String?>(null) }
    val displayedError = errorMessage ?: localError

    AlertDialog(
        onDismissRequest = { if (!verifying) onDismissRequest() },
        icon = {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Box(modifier = Modifier.size(52.dp), contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Outlined.MarkEmailRead,
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
        },
        title = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "SECURE CHECKOUT",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
                Text("Verify your email", style = MaterialTheme.typography.headlineSmall)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (initialMessage.isNotBlank()) {
                    Text(initialMessage, style = MaterialTheme.typography.bodyMedium)
                }
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text("Code sent to", style = MaterialTheme.typography.labelMedium)
                        Text(email, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Text(
                            "The six-digit code expires in 15 minutes. Email links expire in 60 minutes.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                OutlinedTextField(
                    value = code,
                    onValueChange = { value ->
                        code = value.filter(Char::isDigit).take(6)
                        localError = null
                        onCodeChanged()
                    },
                    modifier = Modifier.fillMaxWidth().testTag("email_verification_code"),
                    enabled = !verifying,
                    singleLine = true,
                    label = { Text("6-digit verification code") },
                    placeholder = { Text("123456") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    isError = displayedError != null,
                    supportingText = displayedError?.let { message -> { Text(message) } },
                )
                if (verificationLinkAvailable) {
                    Text(
                        "You can also open the secure link from the verification email.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !verifying,
                onClick = {
                    if (code.length == 6) {
                        onVerify(code)
                    } else {
                        localError = "Enter all 6 digits"
                    }
                },
                shape = MaterialTheme.shapes.large,
            ) {
                if (verifying) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text("Verify")
                }
            }
        },
        dismissButton = {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(enabled = !verifying, onClick = onDismissRequest, shapes = ButtonDefaults.shapes()) {
                    Text("Cancel")
                }
                TextButton(enabled = !verifying, onClick = onResend, shapes = ButtonDefaults.shapes()) {
                    Text("Resend code")
                }
                if (onOpenLink != null) {
                    FilledTonalButton(enabled = !verifying, onClick = onOpenLink, shapes = ButtonDefaults.shapes()) {
                        Text("Open link")
                    }
                }
            }
        },
        shape = MaterialTheme.shapes.extraLarge,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 6.dp,
    )
}

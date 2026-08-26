package com.fersaiyan.cyanbridge.shared.ui.chat

import androidx.compose.runtime.Composable

/** Platform hook so shared chat UI can expose Android-only integration management for now. */
@Composable
expect fun PlatformKnowledgeIntegrationsMenuItem(onDismissRequest: () -> Unit)

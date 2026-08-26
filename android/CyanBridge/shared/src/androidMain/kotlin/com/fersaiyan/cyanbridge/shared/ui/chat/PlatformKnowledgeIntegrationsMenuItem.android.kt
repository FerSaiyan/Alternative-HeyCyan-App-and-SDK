package com.fersaiyan.cyanbridge.shared.ui.chat

import android.content.Intent
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign

@Composable
actual fun PlatformKnowledgeIntegrationsMenuItem(onDismissRequest: () -> Unit) {
    val context = LocalContext.current
    TextButton(
        onClick = {
            onDismissRequest()
            val intent = Intent().setClassName(
                context.packageName,
                "com.fersaiyan.cyanbridge.integrations.knowledge.KnowledgeIntegrationsActivity",
            )
            context.startActivity(intent)
        },
        modifier = Modifier
            .fillMaxWidth()
            .testTag("chat_action_knowledge_integrations"),
    ) {
        Text(
            text = "Notes & AI imports",
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Start,
        )
    }
}

package com.fersaiyan.cyanbridge.shared.ui.chat

import android.content.Intent
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun PlatformKnowledgeIntegrationsTopBarAction() {
    val context = LocalContext.current
    TextButton(
        onClick = {
            val intent = Intent().setClassName(
                context.packageName,
                "com.fersaiyan.cyanbridge.integrations.knowledge.KnowledgeIntegrationsActivity",
            )
            context.startActivity(intent)
        },
    ) {
        Text("Notes")
    }
}

// The only place that opts in to the still-experimental Material 3 Expressive loading indicator
// (androidx material3 1.5 / JetBrains material3 1.13 keep LoadingIndicator experimental).
@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.fersaiyan.cyanbridge.shared.ui.components

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

/**
 * Indeterminate wait for a whole screen or panel (list loading, model loading): the expressive
 * shape-morphing indicator. Small inline spinners inside buttons stay CircularProgressIndicator.
 * [contentDescription] is read by TalkBack/VoiceOver; the indicator already exposes
 * indeterminate progress semantics.
 */
@Composable
fun CyanBridgeLoadingIndicator(
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    LoadingIndicator(
        modifier = if (contentDescription != null) {
            modifier.semantics { this.contentDescription = contentDescription }
        } else {
            modifier
        },
    )
}

package com.fersaiyan.cyanbridge.shared.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.WavyProgressIndicatorDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fersaiyan.cyanbridge.shared.ui.theme.LocalReducedMotion

/**
 * Progress of a running transfer (media sync, firmware update, model download): the Material 3
 * Expressive wavy bar, replacing the thick hand-rounded LinearProgressIndicator these screens
 * drew themselves. [progress] is 0..1, or null while the amount is unknown. With reduced motion
 * the wave is flat and does not travel; the bar keeps its progress semantics for screen readers.
 */
@Composable
fun CyanBridgeTransferProgress(
    progress: Float?,
    modifier: Modifier = Modifier.fillMaxWidth(),
) {
    val still = LocalReducedMotion.current
    if (progress != null) {
        LinearWavyProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = modifier,
            amplitude = if (still) { _ -> 0f } else WavyProgressIndicatorDefaults.indicatorAmplitude,
            waveSpeed = if (still) 0.dp else WavyProgressIndicatorDefaults.LinearDeterminateWavelength,
        )
    } else {
        LinearWavyProgressIndicator(
            modifier = modifier,
            amplitude = if (still) 0f else 1f,
        )
    }
}

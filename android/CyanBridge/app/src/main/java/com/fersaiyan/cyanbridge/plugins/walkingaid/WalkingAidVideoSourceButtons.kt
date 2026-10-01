package com.fersaiyan.cyanbridge.plugins.walkingaid

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.fersaiyan.cyanbridge.R
import com.fersaiyan.cyanbridge.shared.devices.DeviceClass

@Composable
internal fun WalkingAidVideoSourceButtons(
    selected: WalkingAidVideoMode,
    deviceClass: DeviceClass,
    sdk: Int,
    enabled: Boolean,
    onSelected: (WalkingAidVideoMode) -> Unit,
) {
    Column {
        WalkingAidVideoMode.entries.filter { it.supports(deviceClass, sdk) }.forEach { mode ->
            FilterChip(
                selected = selected == mode,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
                onClick = { onSelected(mode) },
                label = { Text(stringResource(when (mode) {
                    WalkingAidVideoMode.PERIODIC_PHOTOS -> R.string.walking_aid_periodic_photos
                    WalkingAidVideoMode.EYEVUE_VIDEO -> R.string.walking_aid_eyevue_video
                    WalkingAidVideoMode.META_VIDEO -> R.string.walking_aid_meta_video
                })) },
            )
        }
    }
}

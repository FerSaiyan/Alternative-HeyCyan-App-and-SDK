package com.fersaiyan.cyanbridge.ui

import com.fersaiyan.cyanbridge.ui.nav.NavScreen
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.addCallback
import com.fersaiyan.cyanbridge.ui.nav.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.fersaiyan.cyanbridge.devices.mentra.MentraOtaAdapter
import com.fersaiyan.cyanbridge.shared.ui.glasses.MentraFirmwareUpdateScreen
import com.fersaiyan.cyanbridge.shared.ui.glasses.MentraFirmwareUpdateViewModel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Mentra Live firmware update, opened from Advanced > Device info when Mentra Live is selected.
 * Separate from the HeyCyan OTA section on purpose. While the update runs the screen stays on and
 * back is refused; the update itself lives in [MentraOtaAdapter.session], not in this activity.
 */
class MentraFirmwareUpdateDestination : NavScreen() {
    private val viewModel by viewModels<MentraFirmwareUpdateViewModel> {
        viewModelFactory { initializer { MentraFirmwareUpdateViewModel(MentraOtaAdapter.session(applicationContext)) } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this) { if (viewModel.onLeaveRequested()) finish() }
        keepScreenOnWhile(viewModel.uiState.map { it.ota.isActive }.distinctUntilChanged())
        setContent {
            MentraFirmwareUpdateScreen(viewModel = viewModel, onClose = ::finish)
        }
    }
}

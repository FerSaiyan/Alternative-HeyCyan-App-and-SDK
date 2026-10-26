package com.fersaiyan.cyanbridge.ui

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.addCallback
import androidx.activity.viewModels
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
class MentraFirmwareUpdateActivity : AppCompatActivity() {
    private val viewModel by viewModels<MentraFirmwareUpdateViewModel> {
        viewModelFactory { initializer { MentraFirmwareUpdateViewModel(MentraOtaAdapter.session(applicationContext)) } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this) { if (viewModel.onLeaveRequested()) finish() }
        lifecycleScope.launch {
            viewModel.uiState.map { it.ota.isActive }.distinctUntilChanged().collect { active ->
                if (active) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
        }
        setThemedComposeScreen {
            MentraFirmwareUpdateScreen(viewModel = viewModel, onClose = ::finish)
        }
    }
}

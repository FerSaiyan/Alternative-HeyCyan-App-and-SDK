package com.fersaiyan.cyanbridge.ui.main

import androidx.appcompat.app.AppCompatActivity
import com.fersaiyan.cyanbridge.databinding.AcitivytMainBinding
import com.fersaiyan.cyanbridge.shared.glasses.GlassesDashboardUiState

/** The parts of the main screen that the glasses dashboard controllers share. */
internal interface MainScreenHost {
    val activity: AppCompatActivity

    /** Legacy views that still back the hidden controls; Compose renders [dashboardState]. */
    val binding: AcitivytMainBinding

    var dashboardState: GlassesDashboardUiState

    /** Applies [transform] to [dashboardState] on the UI thread. */
    fun updateDashboardState(transform: (GlassesDashboardUiState) -> GlassesDashboardUiState)
}

/** Base for controllers that update the main screen through a [MainScreenHost]. */
internal abstract class MainScreenController(protected val host: MainScreenHost) {
    protected val activity: AppCompatActivity
        get() = host.activity

    protected val binding: AcitivytMainBinding
        get() = host.binding

    protected var dashboardState: GlassesDashboardUiState
        get() = host.dashboardState
        set(value) {
            host.dashboardState = value
        }

    protected fun updateDashboardState(
        transform: (GlassesDashboardUiState) -> GlassesDashboardUiState,
    ) {
        host.updateDashboardState(transform)
    }
}

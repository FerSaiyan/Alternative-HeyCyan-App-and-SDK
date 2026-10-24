package com.fersaiyan.cyanbridge.ui.main.glasses

import android.content.Intent
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.fersaiyan.cyanbridge.bridge.core.CameraCapture
import com.fersaiyan.cyanbridge.bridge.core.GlassesConnectionControl
import com.fersaiyan.cyanbridge.devices.metarayban.MetaRaybanManager
import com.fersaiyan.cyanbridge.shared.devices.GlassesFamilyProfiles
import com.fersaiyan.cyanbridge.shared.glasses.GlassesDashboardAction
import com.fersaiyan.cyanbridge.ui.DeviceBindActivity
import com.fersaiyan.cyanbridge.ui.MetaPairingActivity
import com.fersaiyan.cyanbridge.ui.startKtxActivity
import kotlinx.coroutines.launch

/**
 * Meta Ray-Ban glasses over the Meta DAT SDK ([MetaRaybanManager]). The profile rejects
 * HeyCyan SDK controls, so commands without a Meta capability are refused by those controls.
 */
internal class MetaRaybanGlasses(context: GlassesDeviceContext) :
    LegacySdkGlasses(GlassesFamilyProfiles.metaRayban, context) {
    private val activity get() = context.activity

    override val connection: GlassesConnectionControl = object : GlassesConnectionControl {
        override fun scan() {
            activity.startKtxActivity<DeviceBindActivity>()
        }

        override fun reconnect() {
            activity.startActivity(Intent(activity, MetaPairingActivity::class.java))
        }

        override fun disconnect() {
            context.managers.metaRaybanManager?.stopSession()
            context.connection.updateConnectionStatus(false)
        }
    }

    /** The legacy camera control captures a DAT photo into the gallery. */
    override val legacyCameraButton = CameraCapture { context.metaRaybanUi.captureMetaPhotoForGallery() }

    override val cameraPluginGate = CameraPluginGate { start ->
        val manager = context.managers.getOrCreateMetaRaybanManager()
        if (!manager.isInitialized.value) manager.initialize()
        activity.lifecycleScope.launch {
            if (!manager.awaitCameraReady()) {
                Toast.makeText(
                    activity,
                    "Register and connect a Meta camera before starting this plugin.",
                    Toast.LENGTH_LONG,
                ).show()
                return@launch
            }
            context.metaRaybanUi.ensureMetaCameraReady(start)
        }
    }

    /** The Meta panel: DAT registration, camera session, stream, photo and display controls. */
    override fun handlePanelAction(action: GlassesDashboardAction): Boolean {
        when (action) {
            GlassesDashboardAction.MetaRegister -> binding.btnMetaRegister.performClick()
            GlassesDashboardAction.MetaOpenPairing ->
                activity.startActivity(Intent(activity, MetaPairingActivity::class.java))
            GlassesDashboardAction.MetaOpenMetaAi -> context.metaRaybanUi.openMetaAiAppOrStore()
            GlassesDashboardAction.MetaUnregister -> binding.btnMetaUnregister.performClick()
            GlassesDashboardAction.MetaStartSession -> binding.btnMetaSessionStart.performClick()
            GlassesDashboardAction.MetaStopSession -> binding.btnMetaSessionStop.performClick()
            GlassesDashboardAction.MetaStartStream -> binding.btnMetaStreamStart.performClick()
            GlassesDashboardAction.MetaStopStream -> binding.btnMetaStreamStop.performClick()
            GlassesDashboardAction.MetaCapturePhoto -> binding.btnMetaCapturePhoto.performClick()
            GlassesDashboardAction.MetaViewPhoto -> binding.btnMetaViewPhoto.performClick()
            GlassesDashboardAction.MetaStartDisplay -> binding.btnMetaDisplayStart.performClick()
            GlassesDashboardAction.MetaStopDisplay -> binding.btnMetaDisplayStop.performClick()
            GlassesDashboardAction.MetaSendDiagnostics -> context.metaRaybanUi.showMetaDiagnostics()
            else -> return false
        }
        return true
    }

    override fun renderConnectionStatus(connected: Boolean) {
        val manager = context.managers.getOrCreateMetaRaybanManager()
        val status = when {
            !manager.isInitialized.value -> "Meta Ray-Ban selected"
            manager.isCameraReady() -> "Meta Ray-Ban ready"
            manager.registrationState.value == MetaRaybanManager.RegistrationState.REGISTERED ->
                "Meta Ray-Ban registered"
            else -> "Meta Ray-Ban not registered"
        }
        binding.statusText.text = status
        context.screen.updateDashboardState { state -> state.copy(connectionLabel = status) }
        context.connection.updateDeviceClassText()
    }

    override fun onSelectedForDashboard() {
        val manager = context.managers.getOrCreateMetaRaybanManager()
        if (context.metaRaybanUi.metaAndroidPermissionsMissing().isEmpty()) {
            manager.initialize()
        }
        context.metaRaybanUi.updateMetaRaybanUiState()
    }
}

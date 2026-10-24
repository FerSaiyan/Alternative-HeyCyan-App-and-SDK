package com.fersaiyan.cyanbridge.ui.main

import android.content.BroadcastReceiver
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.fersaiyan.cyanbridge.devices.DeviceProfileStore
import com.fersaiyan.cyanbridge.devices.eyevue.EyevueLivePreviewManager
import com.fersaiyan.cyanbridge.devices.eyevue.EyevueManager
import com.fersaiyan.cyanbridge.devices.eyevue.EyevueState
import com.fersaiyan.cyanbridge.devices.meizumyvu.MeizuMyvuFailure
import com.fersaiyan.cyanbridge.devices.meizumyvu.MeizuMyvuManager
import com.fersaiyan.cyanbridge.devices.moyoung.MoyoungW620Manager
import com.fersaiyan.cyanbridge.devices.moyoung.MoyoungW620State
import com.fersaiyan.cyanbridge.devices.tunebuds.TuneBudsLiveForegroundService
import com.fersaiyan.cyanbridge.devices.tunebuds.TuneBudsLivePreviewManager
import com.fersaiyan.cyanbridge.devices.tunebuds.TuneBudsManager
import com.fersaiyan.cyanbridge.devices.tunebuds.TuneBudsState
import com.fersaiyan.cyanbridge.ota.LivePreviewState
import com.fersaiyan.cyanbridge.shared.devices.DeviceClass
import com.fersaiyan.cyanbridge.shared.devices.GlassesFamilyProfile
import com.fersaiyan.cyanbridge.shared.devices.GlassesFamilyProfiles
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

/**
 * Lazily created managers for every supported glasses family. Each manager's state is collected
 * on the activity's lifecycle scope and handed to [listener] while that family is the selected
 * device profile, so the dashboard only mirrors the selected glasses.
 */
internal class DeviceManagerRegistry(
    private val activity: AppCompatActivity,
    private val listener: () -> Listener,
) {
    /**
     * Receives manager state changes on the main thread. Meta and MYVU panel updates and the
     * MYVU failure prompt arrive for any selection; the other callbacks only while their family
     * is selected.
     */
    interface Listener {
        fun onMetaRaybanStateChanged()
        fun onMeizuMyvuStateChanged(state: MeizuMyvuManager.MeizuMyvuUiState)
        fun onMeizuMyvuFailure(failure: MeizuMyvuFailure)

        /** The selected family's manager reported a change its connection status shows. */
        fun onSelectedGlassesStateChanged()
        fun onEyevueStateChanged(eyevue: EyevueState)
        fun onEyevueWakeWord()
        fun onTuneBudsStateChanged(tuneBuds: TuneBudsState)
        fun onMoyoungW620StateChanged(moyoung: MoyoungW620State)
        fun onEyevueLivePreviewStateChanged(manager: EyevueLivePreviewManager, lp: LivePreviewState)
        fun onTuneBudsLivePreviewStateChanged(manager: TuneBudsLivePreviewManager, lp: LivePreviewState)
    }

    var metaRaybanManager: com.fersaiyan.cyanbridge.devices.metarayban.MetaRaybanManager? = null
        private set
    private var metaRaybanUiJob: Job? = null
    var meizuMyvuManager: MeizuMyvuManager? = null
        private set
    private var meizuMyvuUiJob: Job? = null
    private var meizuMyvuFailureJob: Job? = null
    var eyevueManager: EyevueManager? = null
        private set
    private var eyevueUiJob: Job? = null
    private var eyevueWakeWordJob: Job? = null
    var tuneBudsManager: TuneBudsManager? = null
        private set
    private var tuneBudsUiJob: Job? = null
    var moyoungW620Manager: MoyoungW620Manager? = null
        private set
    private var moyoungW620UiJob: Job? = null
    var eyevueLivePreviewManager: EyevueLivePreviewManager? = null
        private set
    private var eyevueLivePreviewUiJob: Job? = null
    var eyevueLiveStopReceiver: BroadcastReceiver? = null
    var tuneBudsLivePreviewManager: TuneBudsLivePreviewManager? = null
        private set
    private var tuneBudsLivePreviewUiJob: Job? = null
    var tuneBudsLiveStopReceiver: BroadcastReceiver? = null

    /** True when [deviceClass] is the selected device profile; filters each family's events. */
    fun isSelected(deviceClass: DeviceClass): Boolean = DeviceProfileStore.selectedClass(activity) == deviceClass

    /** Capability profile of the selected device class. */
    fun selectedProfile(): GlassesFamilyProfile =
        GlassesFamilyProfiles.forClass(DeviceProfileStore.selectedClass(activity))

    // Selection predicates kept for the activity's AI and voice flows.
    fun isMetaRaybanSelected(): Boolean = isSelected(DeviceClass.META_RAYBAN)

    fun isEyevueSelected(): Boolean = isSelected(DeviceClass.EYEVUE)

    fun isTuneBudsSelected(): Boolean = isSelected(DeviceClass.TUNEBUDS)

    fun isHeyCyanSelected(): Boolean = isSelected(DeviceClass.HEY_CYAN)

    fun getOrCreateMetaRaybanManager(): com.fersaiyan.cyanbridge.devices.metarayban.MetaRaybanManager {
        return metaRaybanManager
            ?: com.fersaiyan.cyanbridge.devices.metarayban.MetaRaybanManager
                .getInstance(activity)
                .also { manager ->
                    metaRaybanManager = manager
                    observeMetaRaybanState(manager)
                }
    }

    private fun observeMetaRaybanState(
        manager: com.fersaiyan.cyanbridge.devices.metarayban.MetaRaybanManager,
    ) {
        if (metaRaybanUiJob != null) return
        metaRaybanUiJob = activity.lifecycleScope.launch {
            merge(
                manager.registrationState.map { Unit },
                manager.deviceSessionState.map { Unit },
                manager.streamState.map { Unit },
                manager.isDisplayActive.map { Unit },
                manager.selectedDeviceIsDisplayCapable.map { Unit },
                manager.availableDeviceCount.map { Unit },
                manager.selectedDeviceName.map { Unit },
                manager.lastError.map { Unit },
            ).collect {
                listener().onMetaRaybanStateChanged()
                if (isSelected(DeviceClass.META_RAYBAN)) listener().onSelectedGlassesStateChanged()
            }
        }
    }

    fun getOrCreateMeizuMyvuManager(): MeizuMyvuManager =
        meizuMyvuManager ?: MeizuMyvuManager.getInstance(activity).also { manager ->
            meizuMyvuManager = manager
            if (meizuMyvuUiJob == null) {
                meizuMyvuUiJob = activity.lifecycleScope.launch {
                    manager.state.collect { state ->
                        listener().onMeizuMyvuStateChanged(state)
                        if (isSelected(DeviceClass.MEIZU_MYVU)) listener().onSelectedGlassesStateChanged()
                    }
                }
            }
            if (meizuMyvuFailureJob == null) {
                meizuMyvuFailureJob = activity.lifecycleScope.launch {
                    manager.failurePrompt.collect { failure ->
                        if (failure == null) return@collect
                        listener().onMeizuMyvuFailure(failure)
                    }
                }
            }
        }

    fun getOrCreateEyevueManager(): EyevueManager =
        eyevueManager ?: EyevueManager.getInstance(activity).also { manager ->
            eyevueManager = manager
            if (eyevueUiJob == null) {
                eyevueUiJob = activity.lifecycleScope.launch {
                    manager.state.collect { eyevue ->
                        if (isSelected(DeviceClass.EYEVUE)) listener().onEyevueStateChanged(eyevue)
                    }
                }
            }
            if (eyevueWakeWordJob == null) {
                eyevueWakeWordJob = activity.lifecycleScope.launch {
                    manager.wakeWordEvents.collect {
                        if (isSelected(DeviceClass.EYEVUE)) listener().onEyevueWakeWord()
                    }
                }
            }
        }

    fun getOrCreateTuneBudsManager(): TuneBudsManager =
        tuneBudsManager ?: TuneBudsManager.getInstance(activity).also { manager ->
            tuneBudsManager = manager
            if (tuneBudsUiJob == null) {
                tuneBudsUiJob = activity.lifecycleScope.launch {
                    manager.state.collect { tuneBuds ->
                        if (isSelected(DeviceClass.TUNEBUDS)) listener().onTuneBudsStateChanged(tuneBuds)
                    }
                }
            }
        }

    fun getOrCreateTuneBudsLivePreviewManager(): TuneBudsLivePreviewManager =
        tuneBudsLivePreviewManager ?: TuneBudsLivePreviewManager(activity, getOrCreateTuneBudsManager()).also { manager ->
            tuneBudsLivePreviewManager = manager
            tuneBudsLivePreviewUiJob = activity.lifecycleScope.launch {
                manager.uiState.collect { lp ->
                    if (isSelected(DeviceClass.TUNEBUDS)) listener().onTuneBudsLivePreviewStateChanged(manager, lp)
                }
            }
            val receiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
                    manager.stop()
                }
            }
            tuneBudsLiveStopReceiver = receiver
            androidx.core.content.ContextCompat.registerReceiver(activity, receiver,
                android.content.IntentFilter(TuneBudsLiveForegroundService.ACTION_TUNEBUDS_LIVE_STOP),
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        }

    fun getOrCreateMoyoungW620Manager(): MoyoungW620Manager =
        moyoungW620Manager ?: MoyoungW620Manager.getInstance(activity).also { manager ->
            moyoungW620Manager = manager
            if (moyoungW620UiJob == null) {
                moyoungW620UiJob = activity.lifecycleScope.launch {
                    manager.state.collect { moyoung ->
                        if (isSelected(DeviceClass.MOYOUNG_W620)) listener().onMoyoungW620StateChanged(moyoung)
                    }
                }
            }
        }

    fun getOrCreateEyevueLivePreviewManager(): EyevueLivePreviewManager =
        eyevueLivePreviewManager ?: EyevueLivePreviewManager(
            context = activity,
            eyevueManager = getOrCreateEyevueManager(),
        ).also { manager ->
            eyevueLivePreviewManager = manager
            if (eyevueLivePreviewUiJob == null) {
                eyevueLivePreviewUiJob = activity.lifecycleScope.launch {
                    manager.uiState.collect { lp ->
                        if (isSelected(DeviceClass.EYEVUE)) listener().onEyevueLivePreviewStateChanged(manager, lp)
                    }
                }
            }
            if (eyevueLiveStopReceiver == null) {
                val receiver = object : android.content.BroadcastReceiver() {
                    override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
                        eyevueLivePreviewManager?.stop()
                    }
                }
                eyevueLiveStopReceiver = receiver
                androidx.core.content.ContextCompat.registerReceiver(
                    activity,
                    receiver,
                    android.content.IntentFilter(
                        com.fersaiyan.cyanbridge.devices.eyevue.EyevueLiveForegroundService.ACTION_EYEVUE_LIVE_STOP,
                    ),
                    androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
                )
            }
        }
}

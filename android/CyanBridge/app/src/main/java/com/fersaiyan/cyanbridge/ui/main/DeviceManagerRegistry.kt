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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

/**
 * Lazily created managers for every supported glasses family, plus the device-selection
 * predicates. Each manager's state is collected on the activity's lifecycle scope and handed
 * to [listener] so the dashboard can mirror it.
 */
internal class DeviceManagerRegistry(
    private val activity: AppCompatActivity,
    private val listener: () -> Listener,
) {
    /** Receives manager state changes on the main thread. */
    interface Listener {
        fun onMetaRaybanStateChanged()
        fun onMeizuMyvuStateChanged(state: MeizuMyvuManager.MeizuMyvuUiState)
        fun onMeizuMyvuFailure(failure: MeizuMyvuFailure)
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

    fun isMetaRaybanSelected(): Boolean = DeviceProfileStore.isMetaSelected(activity)

    fun isMeizuMyvuSelected(): Boolean = DeviceProfileStore.isMeizuMyvuSelected(activity)

    fun isEyevueSelected(): Boolean =
        DeviceProfileStore.selectedClass(activity) == com.fersaiyan.cyanbridge.shared.devices.DeviceClass.EYEVUE

    fun isTuneBudsSelected(): Boolean =
        DeviceProfileStore.selectedClass(activity) == com.fersaiyan.cyanbridge.shared.devices.DeviceClass.TUNEBUDS

    fun isMoyoungW620Selected(): Boolean = DeviceProfileStore.isMoyoungW620Selected(activity)

    fun isHeyCyanSelected(): Boolean =
        DeviceProfileStore.selectedClass(activity) == com.fersaiyan.cyanbridge.shared.devices.DeviceClass.HEY_CYAN

    fun isHeyCyanOrEyevueSelected(): Boolean =
        DeviceProfileStore.selectedClass(activity) in setOf(
            com.fersaiyan.cyanbridge.shared.devices.DeviceClass.HEY_CYAN,
            com.fersaiyan.cyanbridge.shared.devices.DeviceClass.EYEVUE,
        )

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
                        listener().onEyevueStateChanged(eyevue)
                    }
                }
            }
            if (eyevueWakeWordJob == null) {
                eyevueWakeWordJob = activity.lifecycleScope.launch {
                    manager.wakeWordEvents.collect {
                        listener().onEyevueWakeWord()
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
                        listener().onTuneBudsStateChanged(tuneBuds)
                    }
                }
            }
        }

    fun getOrCreateTuneBudsLivePreviewManager(): TuneBudsLivePreviewManager =
        tuneBudsLivePreviewManager ?: TuneBudsLivePreviewManager(activity, getOrCreateTuneBudsManager()).also { manager ->
            tuneBudsLivePreviewManager = manager
            tuneBudsLivePreviewUiJob = activity.lifecycleScope.launch {
                manager.uiState.collect { lp ->
                    listener().onTuneBudsLivePreviewStateChanged(manager, lp)
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
                        listener().onMoyoungW620StateChanged(moyoung)
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
                        listener().onEyevueLivePreviewStateChanged(manager, lp)
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

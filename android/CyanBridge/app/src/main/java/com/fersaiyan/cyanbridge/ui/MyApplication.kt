package com.fersaiyan.cyanbridge.ui

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.core.content.ContextCompat
import com.oudmon.ble.base.bluetooth.BleAction
import com.oudmon.ble.base.bluetooth.BleBaseControl
import com.oudmon.ble.base.bluetooth.BleOperateManager
import com.oudmon.ble.base.communication.LargeDataHandler
import com.fersaiyan.cyanbridge.agent.LocalAgentPrefs
import com.fersaiyan.cyanbridge.ai.router.AiProviderPrefs
import com.fersaiyan.cyanbridge.ai.router.AiProviderType
import com.fersaiyan.cyanbridge.localmodels.storage.LocalModelStorageRepository
import com.fersaiyan.cyanbridge.localagent.daily.DailyFactsReminderScheduler
import com.fersaiyan.cyanbridge.plugins.autodiary.AutoDiaryService
import com.fersaiyan.cyanbridge.plugins.localagent.LocalAgentPlugin
import com.fersaiyan.cyanbridge.plugins.PluginVoicePermissions
import com.fersaiyan.cyanbridge.plugins.visualdiary.VisualDiaryPreferences
import com.fersaiyan.cyanbridge.plugins.visualdiary.VisualDiaryService
import com.fersaiyan.cyanbridge.memoryvault.MemoryVaultBootstrap
import com.fersaiyan.cyanbridge.devices.DeviceProfileStore
import com.fersaiyan.cyanbridge.media.autocapture.AutoAudioCapturePrefs
import com.fersaiyan.cyanbridge.media.autocapture.AutoAudioCaptureService
import com.fersaiyan.cyanbridge.localmodels.remote.RemoteOpenAiPrefs
import com.fersaiyan.cyanbridge.studiobridge.StudioApprovalHandler
import com.fersaiyan.cyanbridge.studiobridge.StudioBridgeClient
import com.fersaiyan.cyanbridge.studiobridge.StudioBridgeForegroundService
import com.fersaiyan.cyanbridge.ui.localization.AppLanguagePreferences
import com.fersaiyan.cyanbridge.shared.platform.CyanBridgeServices
import com.fersaiyan.cyanbridge.shared.platform.initPlatformPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import kotlin.properties.Delegates

/**
 * @Author: Hzy
 * @CreateDate: 2021/6/25 11:50
 *
 * "Programs should be written for other people to read,
 * and only incidentally for machines to execute"
 */
class MyApplication : Application(){

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var studioApprovalHandler: StudioApprovalHandler? = null

    var hardwareVersion: String = ""
    var firmwareVersion:String =""

    override fun onCreate() {
        super.onCreate()
        application = this
        instance = this
        CONTEXT = applicationContext
        AppLanguagePreferences.applyStoredLocale(this)
        initBle()

        // Keep the BLE control channel connected while the app process is alive.
        // This matches user expectations from the official HeyCyan companion app.
        AutoPairManager.start(this)

        // Local Agent: ensure daily reminder schedule matches current prefs.
        DailyFactsReminderScheduler.scheduleIfEnabled(
            context = this,
            enabled = LocalAgentPrefs.isDailyFactsReminderEnabled(this),
        )
        LocalAgentPlugin.syncNativePluginState(this)

        // Auto audio capture (glasses recording loop)
        if (AutoAudioCapturePrefs.isEnabled(this) &&
            !DeviceProfileStore.isEyevueSelected(this) &&
            !DeviceProfileStore.isTuneBudsSelected(this) &&
            !AutoAudioCaptureService.isRunning()
        ) {
            AutoAudioCaptureService.start(this)
        }

        runCatching { MemoryVaultBootstrap.ensureInitialized(this) }

        if (AutoDiaryService.isEnabled(this) && !AutoDiaryService.isRunning()) {
            AutoDiaryService.startIfEnabled(this)
        }

        if (VisualDiaryPreferences.isEnabled(this) && !VisualDiaryService.isRunning()) {
            VisualDiaryService.startIfEnabled(this)
        }

        maybePreloadLocalModel()

        // Initialize KMP shared services
        runCatching { initPlatformPreferences(this) }
        runCatching { initSharedServices() }

    }

    /**
     * Start the Studio Bridge WebSocket connection for approval notifications.
     */
    fun startStudioBridge(): Boolean {
        if (!RemoteOpenAiPrefs.isBridgeConfigured(this)) return false
        if (!PluginVoicePermissions.hasRequiredPermissions(this)) return false

        // A settings refresh replaces both the socket and its TTS resources.
        stopStudioBridge()
        val handler = StudioApprovalHandler(applicationContext)
        handler.initialize()
        studioApprovalHandler = handler
        val foregroundStarted = runCatching {
            ContextCompat.startForegroundService(
                this,
                Intent(this, StudioBridgeForegroundService::class.java),
            )
        }.isSuccess
        if (!foregroundStarted) {
            handler.shutdown()
            studioApprovalHandler = null
            return false
        }
        StudioBridgeClient.start(applicationContext, handler)
        return true
    }

    /**
     * Stop the Studio Bridge WebSocket connection.
     */
    fun stopStudioBridge() {
        StudioBridgeClient.stop()
        stopService(Intent(this, StudioBridgeForegroundService::class.java))
        studioApprovalHandler?.shutdown()
        studioApprovalHandler = null
    }

    private fun maybePreloadLocalModel() {
        if (AiProviderPrefs.getProvider(this) != AiProviderType.LOCAL_MODELS) return

        appScope.launch {
            runCatching {
                LocalModelStorageRepository.cleanupMissingModels(this@MyApplication)
            }
        }
    }

    /**
     * Initialize KMP shared services with Android implementations.
     * This bridges the shared module's abstractions to the Android-specific implementations.
     */
    private fun initSharedServices() {
        if (CyanBridgeServices.isInitialized()) return

        // Create Android BLE manager wrapper
        val androidBleManager = com.fersaiyan.cyanbridge.shared.ble.AndroidBleManager(
            bleOperateManager = BleOperateManager.getInstance(),
            largeDataHandler = LargeDataHandler.getInstance(),
            deviceManager = com.oudmon.ble.base.bluetooth.DeviceManager.getInstance(),
        )

        // Create Android Wi-Fi P2P manager wrapper
        val androidWifiP2pManager = AndroidWifiP2pManagerWrapper()

        // Create Android repositories wrapping Room
        val chatRepo = AndroidChatRepositoryWrapper()
        val notesRepo = AndroidNotesRepositoryWrapper()
        val deviceRepo = AndroidDeviceProfileRepositoryWrapper()
        val vaultRepo = AndroidMemoryVaultRepositoryWrapper()
        val mediaRepo = AndroidMediaRecordRepositoryWrapper()

        // Create AI services
        val chatAi = AndroidChatAiService()
        val voiceAi = AndroidVoiceAiService()
        val imageAi = AndroidImageAiService()
        val modelRegistry = AndroidAiModelRegistry()

        CyanBridgeServices.initialize(
            bleManager = androidBleManager,
            wifiP2pManager = androidWifiP2pManager,
            chatRepository = chatRepo,
            notesRepository = notesRepo,
            deviceProfileRepository = deviceRepo,
            memoryVaultRepository = vaultRepo,
            mediaRecordRepository = mediaRepo,
            chatAiService = chatAi,
            voiceAiService = voiceAi,
            imageAiService = imageAi,
            aiModelRegistry = modelRegistry,
        )
    }

    private fun initBle() {
        initReceiver()
        val intentFilter = BleAction.getIntentFilter()
        val myBleReceiver = MyBluetoothReceiver()
        LocalBroadcastManager.getInstance(CONTEXT)
            .registerReceiver(myBleReceiver, intentFilter)
        BleBaseControl.getInstance(CONTEXT).setmContext(this)
    }

    private fun initReceiver() {
        LargeDataHandler.getInstance()
        BleOperateManager.getInstance(this)
        BleOperateManager.getInstance().setApplication(this)
        BleOperateManager.getInstance().init()
        val deviceFilter: IntentFilter = BleAction.getDeviceIntentFilter()
        val deviceReceiver = BluetoothReceiver()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            registerReceiver(deviceReceiver, deviceFilter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(deviceReceiver, deviceFilter)
        }

    }

    fun getDeviceIntentFilter(): IntentFilter? {
        val intentFilter = IntentFilter()
        intentFilter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        intentFilter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        intentFilter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
        intentFilter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        return intentFilter
    }

    fun getAppRootFile(context: Context): File {
        // /storage/emulated/0/Android/data/pack_name/files
        return if(context.getExternalFilesDir("")!=null){
            context.getExternalFilesDir("")!!
        }else{
            val externalSaveDir = context.externalCacheDir
            externalSaveDir ?: context.cacheDir
        }

    }


    companion object {
        private var application: Application? = null
        private var _context: Context? = null
        var CONTEXT: Context
            get() {
                val ctx = _context
                if (ctx != null) return ctx
                val testCtx = runCatching {
                    val clazz = Class.forName("androidx.test.core.app.ApplicationProvider")
                    val method = clazz.getMethod("getApplicationContext")
                    method.invoke(null) as? Context
                }.getOrNull()
                return testCtx ?: throw IllegalStateException("Property CONTEXT should be initialized before being accessed.")
            }
            set(value) {
                _context = value
            }
        private lateinit var instance: MyApplication

        val database: com.fersaiyan.cyanbridge.data.local.AppDatabase by lazy {
            androidx.room.Room.databaseBuilder(
                CONTEXT,
                com.fersaiyan.cyanbridge.data.local.AppDatabase::class.java,
                "cyanbridge-db"
            )
                .addMigrations(
                    com.fersaiyan.cyanbridge.data.local.AppDatabase.MIGRATION_1_2,
                    com.fersaiyan.cyanbridge.data.local.AppDatabase.MIGRATION_2_3,
                    com.fersaiyan.cyanbridge.data.local.AppDatabase.MIGRATION_3_4,
                    com.fersaiyan.cyanbridge.data.local.AppDatabase.MIGRATION_4_5,
                    com.fersaiyan.cyanbridge.data.local.AppDatabase.MIGRATION_5_6,
                    com.fersaiyan.cyanbridge.data.local.AppDatabase.MIGRATION_6_7,
                )
                .addCallback(
                    object : androidx.room.RoomDatabase.Callback() {
                        override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                            super.onCreate(db)
                            // Room doesn't (yet) support declaring FTS5 virtual tables via annotations.
                            // Ensure the FTS5 index exists for fresh installs.
                            runCatching {
                                com.fersaiyan.cyanbridge.data.local.AppDatabase.MIGRATION_4_5.migrate(db)
                                com.fersaiyan.cyanbridge.data.local.AppDatabase.MIGRATION_5_6.migrate(db)
                                com.fersaiyan.cyanbridge.data.local.AppDatabase.MIGRATION_6_7.migrate(db)
                            }
                        }
                    }
                )
                .build()
        }

        val repository: com.fersaiyan.cyanbridge.data.repository.CyanBridgeRepository by lazy {
            com.fersaiyan.cyanbridge.data.repository.CyanBridgeRepository(database)
        }

        // Chapter 7: summarization + notes workflow
        val summarizationService: com.fersaiyan.cyanbridge.shared.notes.SummarizationService by lazy {
            com.fersaiyan.cyanbridge.ai.summarization.AiSummarizationService(CONTEXT)
        }

        val notesRepository: com.fersaiyan.cyanbridge.notes.NotesRepository by lazy {
            com.fersaiyan.cyanbridge.notes.RoomNotesRepository(
                noteDao = database.noteDao(),
                summarizationService = summarizationService,
            )
        }

        fun getApplication(): Application {
            return application
                ?: throw RuntimeException("Application not initialized. onCreate not yet called.")
        }

        fun getInstance(): MyApplication {
            return instance
        }
    }
}

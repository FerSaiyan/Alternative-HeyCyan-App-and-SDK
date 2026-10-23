package com.fersaiyan.cyanbridge.lensmoo

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.content.ContentValues
import android.content.pm.PackageManager
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.*
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import com.fersaiyan.cyanbridge.R
import com.fersaiyan.cyanbridge.devices.DeviceProfileStore
import com.fersaiyan.cyanbridge.shared.glasses.*
import com.fersaiyan.cyanbridge.shared.navigation.AppDestination
import com.fersaiyan.cyanbridge.shared.ui.CyanBridgeApp
import com.fersaiyan.cyanbridge.ui.appearance.AppearancePreferences
import com.fersaiyan.cyanbridge.ui.appearance.rememberAppearanceSettings
import com.fersaiyan.cyanbridge.ui.theme.CyanBridgeTheme
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** LensMoo uses its own RFCOMM session; no HeyCyan GATT or OTA calls enter this screen. */
class LensMooActivity : androidx.appcompat.app.AppCompatActivity(), SurfaceHolder.Callback {
    private val io = Executors.newSingleThreadExecutor()
    private val video = ArrayBlockingQueue<ByteArray>(16)
    private val decoderGeneration = java.util.concurrent.atomic.AtomicInteger(0)
    private val timeoutExecutor = Executors.newSingleThreadScheduledExecutor()
    private val capturePending = AtomicBoolean(false)
    @Volatile private var session: LensMooSession? = null
    @Volatile private var connected = false
    @Volatile private var destroyed = false
    @Volatile private var previewFrames: java.util.concurrent.CountDownLatch? = null
    private var localUi by androidx.compose.runtime.mutableStateOf(LensMooLocalUiState())
    private var ownerId by androidx.compose.runtime.mutableStateOf("")
    private var surface:SurfaceView?=null
    private var previewDialog:android.app.Dialog?=null
    @Volatile private var previewDesired=false
    private val previewGate=LensMooPreviewGate()
    private var address: String? = null
    private var reconnectAfterChat = false
    private lateinit var voice: LensMooVoiceController
    private val prefs by lazy { getSharedPreferences("lensmoo_private", MODE_PRIVATE) }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        address = intent.getStringExtra("address")?.takeIf { it.isNotBlank() } ?: prefs.getString("address", null)
        ownerId=prefs.getString("owner", "").orEmpty()
        localUi=localUi.copy(deviceName=DeviceProfileStore.loadLastSelected(this)?.advertisedName)
        voice=LensMooVoiceController(this,onTranscript={ text -> capture(forChat=true,prompt=text,voiceRequest=true) },onStatus=::report)
        val appearancePreferences=AppearancePreferences(this)
        setContent {
            val appearance by rememberAppearanceSettings(appearancePreferences)
            val presentation=LensMooDashboardState.build(localUi,serviceUiState,ownerId.isNotBlank())
            val state=presentation.copy(connectionLabel=getString(when {
                presentation.lensMoo?.isConnected==true -> R.string.lensmoo_connected
                presentation.lensMoo?.assistantRunning==true -> R.string.lensmoo_connecting
                else -> R.string.lensmoo_disconnected
            }))
            CyanBridgeTheme(appearance) {
                CyanBridgeApp(dashboardState=state,onDashboardAction=::handleDashboardAction,onNavigateToActivity=::navigateToDestination)
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                LensMooHandsFreeService.uiState.collect { serviceUiState=it }
            }
        }
    }
    private var serviceUiState by androidx.compose.runtime.mutableStateOf(LensMooServiceUiState())
    private fun report(message:String) {
        android.util.Log.i("LensMooUI",message)
        runOnUiThread { if(!destroyed) localUi=localUi.copy(message=message,connected=connected) }
    }
    private fun handleDashboardAction(action:GlassesDashboardAction) {
        when(action) {
            is GlassesDashboardAction.Navigate -> navigateToDestination(action.destination)
            GlassesDashboardAction.Scan -> chooseDevice()
            GlassesDashboardAction.Reconnect -> connect()
            GlassesDashboardAction.Disconnect -> disconnect()
            GlassesDashboardAction.CapturePhoto -> capture()
            GlassesDashboardAction.TestImageQuestion -> capture(forChat=true)
            GlassesDashboardAction.TestVoiceQuestion -> {
                if(!permission() || LensMooHandsFreeService.running) return
                io.execute { runCatching { ensureConnected() }.onSuccess {
                    runOnUiThread { if(!destroyed) voice.startListening() }
                }.onFailure { report("Подключение: ${it.message}") } }
            }
            GlassesDashboardAction.StartLivePreview -> showPreview()
            GlassesDashboardAction.StopLivePreview -> closePreview()
            is GlassesDashboardAction.LensMoo -> when(action.command) {
                LensMooDashboardCommand.CONFIGURE -> configureOwner()
                LensMooDashboardCommand.ENABLE_ASSISTANT -> enableHandsFree()
                LensMooDashboardCommand.DISABLE_ASSISTANT -> {
                    stopService(android.content.Intent(this,LensMooHandsFreeService::class.java))
                    report("Выключение помощника…")
                }
                LensMooDashboardCommand.OPEN_AI_SETTINGS -> startActivity(android.content.Intent(this,com.fersaiyan.cyanbridge.agent.LocalModelsConfigureActivity::class.java))
            }
            else -> Unit
        }
    }
    private fun navigateToDestination(destination:AppDestination) {
        val target=when(destination) {
            AppDestination.GLASSES -> return
            AppDestination.CHATS -> com.fersaiyan.cyanbridge.ui.ChatThreadActivity::class.java
            AppDestination.MEDIA -> com.fersaiyan.cyanbridge.ui.recordings.RecordingsListActivity::class.java
            AppDestination.PLUGINS -> com.fersaiyan.cyanbridge.ui.CommunityPluginsActivity::class.java
            AppDestination.SETTINGS -> com.fersaiyan.cyanbridge.ui.SettingsActivity::class.java
        }
        startActivity(android.content.Intent(this,target).addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP or android.content.Intent.FLAG_ACTIVITY_NO_ANIMATION))
        overridePendingTransition(0,0)
    }
    private fun configureOwner() {
        if(LensMooHandsFreeService.running || capturePending.get()) return
        val input=EditText(this).apply {
            inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(ownerId)
            isSaveEnabled=false
            importantForAutofill=android.view.View.IMPORTANT_FOR_AUTOFILL_NO
        }
        AlertDialog.Builder(this).setTitle(R.string.lensmoo_owner_title)
            .setMessage(R.string.lensmoo_owner_hint).setView(input)
            .setPositiveButton(android.R.string.ok) { _,_ ->
                disconnect()
                ownerId=input.text.toString().trim()
                prefs.edit().putString("owner",ownerId).apply()
                localUi=localUi.copy(message=getString(R.string.lensmoo_settings_saved))
            }.setNegativeButton(android.R.string.cancel,null).show()
    }
    private fun showPreview() {
        if(LensMooHandsFreeService.running || destroyed || previewDialog!=null) return
        val view=SurfaceView(this)
        surface=view
        view.holder.addCallback(this)
        val content=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            addView(view,LinearLayout.LayoutParams(-1,0,1f))
            addView(Button(this@LensMooActivity).apply { setText(R.string.lensmoo_close_preview);setOnClickListener { closePreview() } })
        }
        previewDialog=android.app.Dialog(this).apply {
            setTitle(R.string.lensmoo_preview_title)
            setContentView(content)
            setOnDismissListener { previewDialog=null;setPreview(false);surface=null }
            show()
            window?.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT,android.view.ViewGroup.LayoutParams.MATCH_PARENT)
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
    private fun closePreview() {
        previewDesired=false
        previewGate.cancel()
        localUi=localUi.copy(previewRunning=false)
        previewDialog?.dismiss()
    }
    private fun permission(): Boolean {
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT,Manifest.permission.BLUETOOTH_SCAN),91)
            return false
        }
        return true
    }
    @Suppress("MissingPermission")
    private fun chooseDevice() {
        if (!permission()) return
        val devices = BluetoothAdapter.getDefaultAdapter()?.bondedDevices?.toList().orEmpty()
        AlertDialog.Builder(this).setTitle("Сопряжённые устройства")
            .setItems(devices.map { "${it.name ?: "Bluetooth"} — ${it.address}" }.toTypedArray()) { _, i ->
                address=devices[i].address
                prefs.edit().putString("address",address).apply()
                localUi=localUi.copy(deviceName=devices[i].name)
                report("Выбрано: ${devices[i].name}")
            }.show()
    }
    private fun enableHandsFree() {
        if (!permission()) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), LensMooVoiceController.MICROPHONE_PERMISSION_REQUEST)
            report("Разрешите микрофон и снова включите помощника")
            return
        }
        if (!LensMooAiGate.ready(this)) {
            report("Выберите локальную Gemma LiteRT или настройте облачную модель")
            startActivity(android.content.Intent(this, com.fersaiyan.cyanbridge.agent.LocalModelsConfigureActivity::class.java))
            return
        }
        val target=address?.takeIf { it.isNotBlank() } ?: return report("Сначала выберите очки")
        val id=ownerId.trim()
        if (id.isBlank()) return report("Введите ID владельца")
        prefs.edit().putString("address",target).putString("owner",id).apply()
        reconnectAfterChat = false
        voice.cancelListening()
        disconnect()
        val intent=android.content.Intent(this, LensMooHandsFreeService::class.java)
        runCatching {
            if (Build.VERSION.SDK_INT>=26) startForegroundService(intent) else startService(intent)
        }.onSuccess { report("Помощник включается. После подключения можно заблокировать телефон") }
            .onFailure { report("Помощник: ${it.message}") }
    }
    private fun connect() {
        if (!permission()) return
        val target = address?.takeIf { it.isNotBlank() } ?: return report("Сначала выберите очки")
        val id=ownerId.trim()
        if (id.isEmpty()) return report("Введите ID владельца, использованный для LensMoo")
        prefs.edit().putString("owner",id).putString("address",target).apply()
        io.execute {
            runCatching { ensureConnected(target, id) }
                .onFailure { disconnect(); report("Ошибка: ${it.message}") }
        }
    }
    private fun ensureConnected(
        target: String = address ?: prefs.getString("address", null) ?: error("Сначала выберите очки"),
        id: String = prefs.getString("owner", "").orEmpty(),
    ) {
        check(!destroyed) { "Экран закрыт" }
        check(!LensMooHandsFreeService.running) { "Сначала выключите помощника в кармане" }
        if (connected && session?.isConnected == true) return
        connected=false
        check(id.isNotBlank()) { "Сначала подключите очки с ID владельца" }
        session?.close()
        lateinit var next: LensMooSession
        next=LensMooSession(this,onVideo={ bytes ->
            previewFrames?.countDown()
            if (previewDesired && !video.offer(bytes)) { disconnect(); report("Декодер не успевает; подключите видео снова") }
        },onButton={ runOnUiThread { if(lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) capture() } },onStatus={ report(it) },onDisconnected={
            if (session === next) {
                connected=false
                session=null
                video.clear()
                report("Связь закрыта; следующее действие подключится заново")
            }
        })
        session=next
        val timeout=timeoutExecutor.schedule({
            if (session === next) { next.close(); connected=false; report("Время подключения истекло") }
        },45,java.util.concurrent.TimeUnit.SECONDS)
        try {
            next.connect(target,id)
            check(session === next && next.isConnected && !destroyed) { "Подключение отменено" }
            connected=true
            val battery=next.checkConnection()
            runOnUiThread { localUi=localUi.copy(batteryPercent=battery) }
            report("Очки подключены; камера выключена")
        } catch (failure: Throwable) {
            if (session === next) { connected=false; session=null }
            next.close()
            throw failure
        } finally { timeout.cancel(false) }
    }
    private fun setPreview(enabled: Boolean) {
        if (enabled && surface?.holder?.surface?.isValid!=true) return report("Дождитесь готовности окна видео")
        previewDesired=enabled
        val previewToken=if(enabled) previewGate.request() else { previewGate.cancel();0L }
        runOnUiThread { localUi=localUi.copy(previewRunning=enabled) }
        if (destroyed || io.isShutdown) return
        io.execute {
            runCatching {
                if (enabled) {
                    val frames=java.util.concurrent.CountDownLatch(1)
                    previewFrames=frames
                    var current:LensMooSession?=null
                    val started=previewGate.startIfActive(previewToken,
                        connect={ ensureConnected(); current=session },
                        start={
                            check(previewDesired && !destroyed) { "Предпросмотр отменён" }
                            video.clear()
                            (current ?: error("Соединение закрыто")).startPreview()
                        },
                    )
                    if(!started) { previewFrames=null;return@execute }
                    val deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(12)
                    while (!frames.await(100,java.util.concurrent.TimeUnit.MILLISECONDS)) {
                        check(previewGate.isActive(previewToken) && previewDesired) { "Предпросмотр отменён" }
                        check(session === current && !destroyed) { "Предпросмотр отменён" }
                        check(System.nanoTime()<deadline) { "Нет первого кадра предпросмотра" }
                    }
                    report("Предпросмотр включён")
                } else {
                    session?.stopPreview()
                    video.clear()
                    report("Предпросмотр выключен; камера выключена")
                }
            }.onFailure { failure ->
                runCatching { session?.stopPreview() }
                report("Предпросмотр: ${failure.message}")
            }
            previewFrames=null
        }
    }
    private fun capture(forChat: Boolean = false, prompt: String = "Что изображено на фото?", voiceRequest: Boolean = false) {
        if (forChat && !LensMooAiGate.ready(this)) {
            runOnUiThread {
                report("Выберите локальную Gemma LiteRT или настройте облачную модель")
                startActivity(android.content.Intent(this, com.fersaiyan.cyanbridge.agent.LocalModelsConfigureActivity::class.java))
            }
            return
        }
        if (Build.VERSION.SDK_INT <= 28 && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            runOnUiThread { requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),92) }
            return
        }
        if (!permission() || LensMooHandsFreeService.running || destroyed || io.isShutdown || !capturePending.compareAndSet(false,true)) return
        localUi=localUi.copy(busy=true)
        io.execute {
            try {
                val jpeg = if (forChat) kotlinx.coroutines.runBlocking {
                    LensMooQuestionFlow.run(
                        freshSession = {
                            val previous = session
                            session = null
                            connected = false
                            previous?.close()
                            ensureConnected()
                            session ?: error("Подключение отменено")
                        },
                        captureOnce = { it.capture() },
                        answer = { it },
                    )
                } else {
                    ensureConnected()
                    session!!.capture()
                }
                video.clear()
                report("Снимок получен; камера выключена")
                val values=ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME,"LensMoo-${System.currentTimeMillis()}.jpg")
                    put(MediaStore.Images.Media.MIME_TYPE,"image/jpeg")
                    if (Build.VERSION.SDK_INT>=29) { put(MediaStore.Images.Media.RELATIVE_PATH,"DCIM/CyanBridge"); put(MediaStore.Images.Media.IS_PENDING,1) }
                }
                val uri=contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values) ?: error("Не удалось создать фото")
                try {
                    contentResolver.openOutputStream(uri)?.use { it.write(jpeg) } ?: error("Не удалось записать фото")
                    if (Build.VERSION.SDK_INT>=29) contentResolver.update(uri,ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING,0) },null,null)
                } catch (e: Exception) { contentResolver.delete(uri,null,null); throw e }
                report("Оригинальное фото сохранено: ${jpeg.size} байт")
                if (forChat && !destroyed) runOnUiThread {
                    if (!destroyed) {
                        reconnectAfterChat = connected
                        startActivity(android.content.Intent(this, com.fersaiyan.cyanbridge.ui.ChatThreadActivity::class.java)
                            .putExtra(com.fersaiyan.cyanbridge.ui.ChatThreadActivity.EXTRA_LENSMOO_IMAGE_URI, uri.toString())
                            .putExtra("lensmoo_voice_request", voiceRequest)
                            .putExtra(com.fersaiyan.cyanbridge.ui.ChatThreadActivity.EXTRA_CREATE_THREAD_TITLE, "LensMoo — фото")
                            .putExtra(com.fersaiyan.cyanbridge.ui.ChatThreadActivity.EXTRA_PREFILL_MESSAGE, prompt))
                    }
                }
            } catch (e: Exception) { report("Фото: ${e.message}") }
            finally { capturePending.set(false);runOnUiThread { localUi=localUi.copy(busy=false,previewRunning=false) } }
        }
    }
    private fun disconnect() {
        val previous=session
        previewGate.cancel()
        session=null;connected=false;previewDesired=false;video.clear()
        runOnUiThread { localUi=localUi.copy(connected=false,previewRunning=false,batteryPercent=null) }
        if(previous!=null) {
            val cleanup=Runnable { runCatching { previous.stopPreview() };previous.close() }
            if(destroyed || io.isShutdown || capturePending.get()) previous.close() else io.execute(cleanup)
        }
        report("Отключено")
    }
    override fun surfaceCreated(holder: SurfaceHolder) {
        if (reconnectAfterChat) { reconnectAfterChat = false; surface?.post { if (!destroyed) connect() } }
        val generation = decoderGeneration.incrementAndGet()
        if(surface?.holder===holder) setPreview(true)
        Thread({
            var codec: MediaCodec?=null
            try {
                codec=MediaCodec.createDecoderByType("video/avc")
                codec.configure(MediaFormat.createVideoFormat("video/avc",320,240),holder.surface,null,0)
                codec.start()
                val info=MediaCodec.BufferInfo()
                var decodedFrames=0L
                fun frameDecoded() {
                    decodedFrames++
                    if(decodedFrames%30L==0L) android.util.Log.i("LensMooUI", "Decoded preview frames=$decodedFrames")
                }
                while (!destroyed && decoderGeneration.get() == generation) {
                    val bytes=video.poll(20,java.util.concurrent.TimeUnit.MILLISECONDS)
                    if (bytes!=null) {
                        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2)
                        var index = codec.dequeueInputBuffer(10000)
                        while (index < 0 && System.nanoTime() < deadline && decoderGeneration.get() == generation) {
                            var output = codec.dequeueOutputBuffer(info, 0)
                            while (output >= 0) {
                                codec.releaseOutputBuffer(output, true); frameDecoded()
                                output = codec.dequeueOutputBuffer(info, 0)
                            }
                            index = codec.dequeueInputBuffer(10000)
                        }
                        if (decoderGeneration.get() != generation) break
                        check(index>=0) { "Нет входного буфера декодера" }
                        val input=codec.getInputBuffer(index)!!
                        check(bytes.size<=input.capacity()) { "Слишком большой H264 пакет" }
                        input.clear(); input.put(bytes)
                        codec.queueInputBuffer(index,0,bytes.size,System.nanoTime()/1000,0)
                    }
                    var index=codec.dequeueOutputBuffer(info,0)
                    while(index>=0) { codec.releaseOutputBuffer(index,true); frameDecoded(); index=codec.dequeueOutputBuffer(info,0) }
                }
            } catch(e:Exception) {
                if (!destroyed && decoderGeneration.get() == generation) {
                    disconnect()
                    report("Видео: ${e.message}")
                }
            } finally { runCatching { codec?.stop() }; runCatching { codec?.release() } }
        },"LensMooDecoder").start()
    }
    override fun surfaceChanged(holder: SurfaceHolder,format:Int,width:Int,height:Int) = Unit
    override fun surfaceDestroyed(holder: SurfaceHolder) {
        decoderGeneration.incrementAndGet()
        video.clear()
    }
    override fun onResume() {
        super.onResume()
        val profile=DeviceProfileStore.loadLastSelected(this)
        if(profile!=null && profile.selectedClass!=com.fersaiyan.cyanbridge.shared.devices.DeviceClass.LENSMOO) {
            startActivity(android.content.Intent(this,com.fersaiyan.cyanbridge.MainActivity::class.java));finish();return
        }
        if (reconnectAfterChat) {
            reconnectAfterChat = false
            if(!LensMooHandsFreeService.running) connect()
        }
    }
    override fun onStop() {
        if (::voice.isInitialized) voice.cancelListening()
        closePreview()
        disconnect()
        super.onStop()
    }
    override fun onDestroy() {
        if (::voice.isInitialized) voice.close()
        destroyed=true
        decoderGeneration.incrementAndGet()
        disconnect()
        timeoutExecutor.shutdownNow()
        io.shutdown() // Let any queued camera-stop cleanup finish before executor termination.
        super.onDestroy()
    }
}

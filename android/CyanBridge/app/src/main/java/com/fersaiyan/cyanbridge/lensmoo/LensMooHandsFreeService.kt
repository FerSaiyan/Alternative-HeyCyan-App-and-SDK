package com.fersaiyan.cyanbridge.lensmoo

import android.Manifest
import android.app.*
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.*
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.fersaiyan.cyanbridge.R
import com.fersaiyan.cyanbridge.chat.ChatStore
import com.fersaiyan.cyanbridge.localmodels.provider.LocalModelsProvider
import com.fersaiyan.cyanbridge.shared.chat.ChatRole
import kotlinx.coroutines.*
import java.io.File
import java.util.UUID

/** Explicitly enabled while visible; the mic foreground service remains usable with the phone locked. */
class LensMooHandsFreeService : Service() {
    companion object {
        @Volatile var running = false
            private set
        private val mutableUiState=kotlinx.coroutines.flow.MutableStateFlow(LensMooServiceUiState())
        val uiState:kotlinx.coroutines.flow.StateFlow<LensMooServiceUiState> = mutableUiState
        const val ACTION_CHECK = "com.fersaiyan.cyanbridge.lensmoo.CHECK_CONNECTION"
        const val ACTION_STOP = "com.fersaiyan.cyanbridge.lensmoo.STOP_HANDSFREE"
        private const val CHANNEL = "lensmoo_handsfree"
        private const val NOTIFICATION = 8431
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val gate = LensMooTurnGate()
    private var session: LensMooSession? = null
    private var voice: LensMooVoiceController? = null
    private var deadline: Job? = null
    private var ready = false
    private var connectionJob: Job? = null
    private var healthJob: Job? = null
    private var healthBusy = false
    private var pendingPhysicalPress = false
    private var connectionGeneration = 0L
    private val batteryStatus = LensMooBatteryStatus()
    private var destroyed = false
    private var foreground = false
    private var retainedError: String? = null
    private var lastVoiceFailure: String? = null
    private lateinit var wakeLock: PowerManager.WakeLock

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "LensMoo — вопросы кнопкой очков", NotificationManager.IMPORTANCE_LOW))
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:lensmoo-question")
            .apply { setReferenceCounted(false) }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        if (running) {
            if (intent.action == ACTION_CHECK) checkIdleConnection()
            return START_NOT_STICKY
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ||
            (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) ||
            !LensMooAiGate.ready(this)) {
            stopSelf(); return START_NOT_STICKY
        }
        try {
            val types = if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                else if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
            ServiceCompat.startForeground(this, NOTIFICATION, notification("Подключаю очки…"), types)
            foreground = true
        } catch (error: Exception) { stopSelf(); return START_NOT_STICKY }
        running = true
        scheduleConnection()
        healthJob = scope.launch {
            while (isActive) {
                delay(30_000L)
                checkIdleConnection()
            }
        }
        return START_NOT_STICKY
    }

    private fun scheduleConnection() {
        if (destroyed || connectionJob?.isActive == true || gate.phase != LensMooTurnGate.Phase.IDLE) return
        ready=false
        pendingPhysicalPress=false
        session?.close(); session=null
        connectionJob=scope.launch {
            var backoff=5_000L
            var attempt=0
            while (isActive && !destroyed) {
                attempt++
                val generation=++connectionGeneration
                report("Проверяю соединение с очками; попытка $attempt")
                android.util.Log.i("LensMooHandsFree", "Connect attempt=$attempt generation=$generation")
                var candidate: LensMooSession?=null
                try {
                    val prefs=getSharedPreferences("lensmoo_private",MODE_PRIVATE)
                    val address=prefs.getString("address","").orEmpty()
                    val owner=prefs.getString("owner","").orEmpty()
                    check(address.isNotBlank() && owner.isNotBlank()) { "Missing device configuration" }
                    val next=LensMooSession(this@LensMooHandsFreeService,
                        onStatus={ message -> scope.launch {
                            if (generation==connectionGeneration && message.startsWith("Connection closed")) {
                                ready=false
                                retainedError="Соединение с очками потеряно. Восстанавливаю связь."
                                report(retainedError!!)
                                if (gate.phase==LensMooTurnGate.Phase.IDLE) scheduleConnection()
                            }
                        } },
                        onVoiceButton={ scope.launch { if(generation==connectionGeneration) beginQuestion() } })
                    candidate=next
                    session=next
                    val watchdog=scope.launch { delay(45_000L); next.close() }
                    val battery=try {
                        withContext(Dispatchers.IO) { next.connect(address,owner); next.checkConnection() }
                    } finally { watchdog.cancel() }
                    ensureActive()
                    check(session===next && !destroyed && next.isConnected)
                    ready=true
                    if(retainedError=="Соединение с очками потеряно. Восстанавливаю связь.") retainedError=null
                    batteryStatus.update(battery,SystemClock.elapsedRealtime(),System.currentTimeMillis())
                    android.util.Log.i("LensMooHandsFree", "Connection verified generation=$generation battery=$battery")
                    report("Связь проверена. Нажмите AI-кнопку очков.")
                    return@launch
                } catch (error: CancellationException) { candidate?.close(); throw error }
                catch (error: Exception) {
                    candidate?.close()
                    if (session===candidate) session=null
                    ready=false
                    android.util.Log.w("LensMooHandsFree", "Connect failed attempt=$attempt type=${error.javaClass.simpleName} retryMs=$backoff")
                    report("Очки не отвечают. Повтор подключения через ${backoff/1000} секунд; камера не включается.")
                    delay(backoff)
                    backoff=(backoff*2).coerceAtMost(30_000L)
                }
            }
        }
    }

    private fun checkIdleConnection() {
        if (destroyed || healthBusy || connectionJob?.isActive==true) return
        if (gate.phase!=LensMooTurnGate.Phase.IDLE) {
            android.util.Log.i("LensMooHandsFree", "Idle health skipped phase=${gate.phase}")
            return
        }
        if (!ready || session==null) { scheduleConnection(); return }
        healthBusy=true
        val current=session!!
        scope.launch {
            try {
                android.util.Log.i("LensMooHandsFree", "Idle health request generation=$connectionGeneration")
                val battery=withContext(Dispatchers.IO) { current.checkConnection() }
                ensureActive()
                if(session!==current) return@launch
                check(current.isConnected)
                ready=true
                batteryStatus.update(battery,SystemClock.elapsedRealtime(),System.currentTimeMillis())
                android.util.Log.i("LensMooHandsFree", "Idle health verified battery=$battery")
                report(retainedError ?: "Связь проверена. Нажмите AI-кнопку очков.")
            } catch(error: CancellationException) { throw error }
            catch(error: Exception) {
                ready=false
                pendingPhysicalPress=false
                current.close()
                android.util.Log.w("LensMooHandsFree", "Idle health failed type=${error.javaClass.simpleName}")
                report("Очки перестали отвечать. Восстанавливаю соединение.")
                scheduleConnection()
            } finally {
                healthBusy=false
                if(pendingPhysicalPress && ready && !destroyed) {
                    pendingPhysicalPress=false
                    beginQuestion()
                }
            }
        }
    }
    private fun notification(text: String, ongoing: Boolean = true): Notification {
        val battery=batteryStatus.latest()
        val batteryText=if(battery==null) "Заряд ещё не проверен" else {
            val time=java.text.SimpleDateFormat("HH:mm:ss",java.util.Locale.getDefault()).format(java.util.Date(battery.wallMillis))
            val freshness=if(batteryStatus.isFresh(SystemClock.elapsedRealtime(),ready)) "проверено" else "последние данные, связь не подтверждена сейчас"
            "Заряд ${battery.percent}% — $freshness $time"
        }
        val displayText="$text\n$batteryText"
        val stop = PendingIntent.getService(this, 0, Intent(this, javaClass).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val check = PendingIntent.getService(this, 1, Intent(this, javaClass).setAction(ACTION_CHECK), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 0, Intent(this, LensMooActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("LensMoo — голосовые вопросы").setContentText(displayText).setContentIntent(open)
            .setStyle(NotificationCompat.BigTextStyle().bigText(displayText))
            .setOngoing(ongoing).setAutoCancel(!ongoing).setOnlyAlertOnce(true)
            .apply { if (ongoing) { addAction(0, "Проверить связь", check); addAction(0, "Выключить", stop) } }.build()
    }
    private fun report(text: String) {
        mutableUiState.value=LensMooServiceUiState(
            running=running,ready=ready,busy=gate.phase!=LensMooTurnGate.Phase.IDLE,
            batteryPercent=batteryStatus.latest()?.takeIf { batteryStatus.isFresh(SystemClock.elapsedRealtime(),ready) }?.percent,
            message=text,
        )
        // Only locally generated status/error messages enter this method; never transcript or model output.
        android.util.Log.i("LensMooHandsFree", "phase=${gate.phase} ready=$ready status=$text")
        if (foreground && !destroyed) getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(text))
    }
    private fun armDeadline(token: Long, millis: Long) {
        deadline?.cancel()
        if (wakeLock.isHeld) wakeLock.release()
        wakeLock.acquire(millis + 5_000L)
        deadline = scope.launch {
            delay(millis)
            if (gate.active(token)) {
                retainedError="Время вопроса истекло. Включите режим кнопки заново"
                report(retainedError!!)
                // Do not accept another turn while a timed-out blocking transport may still be unwinding.
                stopSelf()
            }
        }
    }
    private fun beginQuestion() {
        if(healthBusy) {
            pendingPhysicalPress=true
            android.util.Log.i("LensMooHandsFree", "Physical question queued until idle health completes")
            return
        }
        if (!ready || destroyed) {
            android.util.Log.i("LensMooHandsFree", "Physical question ignored ready=$ready destroyed=$destroyed")
            return
        }
        if (!LensMooAiGate.ready(this)) { retainedError="Настройки AI изменились; выберите локальную Gemma или облачную модель"; report(retainedError!!); stopSelf(); return }
        val token = gate.begin() ?: run {
            android.util.Log.i("LensMooHandsFree", "Physical question ignored busy phase=${gate.phase}")
            return
        }
        android.util.Log.i("LensMooHandsFree", "Question started token=$token")
        retainedError = null
        lastVoiceFailure = null
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION + 1)
        armDeadline(token, 60_000L)
        voice?.close()
        voice = LensMooVoiceController(this,
            onTranscript = { question -> handleQuestion(token, question) },
            onStatus = { message -> report(message) },
            onFailure = { message ->
                lastVoiceFailure=message
                android.util.Log.w("LensMooHandsFree", "Voice failure token=$token phase=${gate.phase} reason=$message")
                if (gate.phase == LensMooTurnGate.Phase.SPEAKING) {
                    retainedError=retainedError?.takeIf { it != message }?.let { "$it\n$message" } ?: message
                }
            },
            onIdle = {
                android.util.Log.i("LensMooHandsFree", "Voice idle token=$token phase=${gate.phase} failed=${lastVoiceFailure!=null}")
                when (gate.phase) {
                    LensMooTurnGate.Phase.LISTENING -> speakFailure(token, lastVoiceFailure ?: "Распознавание завершилось без вопроса. Нажмите кнопку и попробуйте снова.")
                    LensMooTurnGate.Phase.SPEAKING -> finishTurn(token, resetStatus=retainedError==null)
                    else -> Unit
                }
            })
        report("Подключаю микрофон очков…")
        runCatching { voice?.startListening() }.onFailure { speakFailure(token, "Не удалось запустить распознавание речи. Попробуйте снова.") }
    }
    private suspend fun freshSessionForCapture(token: Long): LensMooSession {
        check(gate.active(token) && gate.phase==LensMooTurnGate.Phase.WORKING)
        ready=false
        pendingPhysicalPress=false
        // Invalidate callbacks before closing the old RFCOMM socket; keep this question's gate intact.
        val generation=++connectionGeneration
        session?.close(); session=null
        report("Обновляю соединение перед снимком…")
        android.util.Log.i("LensMooHandsFree", "Fresh capture connection token=$token generation=$generation")
        val prefs=getSharedPreferences("lensmoo_private",MODE_PRIVATE)
        val address=prefs.getString("address","").orEmpty()
        val owner=prefs.getString("owner","").orEmpty()
        check(address.isNotBlank() && owner.isNotBlank()) { "Missing device configuration" }
        val next=LensMooSession(this,
            onStatus={ message -> scope.launch {
                if(generation==connectionGeneration && message.startsWith("Connection closed")) {
                    ready=false
                    report("Соединение для снимка потеряно")
                    if(gate.phase==LensMooTurnGate.Phase.IDLE) scheduleConnection()
                }
            } },
            onVoiceButton={ scope.launch { if(generation==connectionGeneration) beginQuestion() } })
        session=next
        val watchdog=scope.launch { delay(45_000L); next.close() }
        try {
            val battery=withContext(Dispatchers.IO) { next.connect(address,owner); next.checkConnection() }
            check(gate.active(token) && gate.phase==LensMooTurnGate.Phase.WORKING && !destroyed && session===next && next.isConnected)
            ready=true
            batteryStatus.update(battery,SystemClock.elapsedRealtime(),System.currentTimeMillis())
            android.util.Log.i("LensMooHandsFree", "Fresh capture connection verified token=$token generation=$generation")
            report("Делаю один снимок…")
            return next
        } catch(error: Throwable) {
            next.close()
            if(session===next) session=null
            ready=false
            throw error
        } finally { watchdog.cancel() }
    }

    private fun handleQuestion(token: Long, question: String) {
        android.util.Log.i("LensMooHandsFree", "Transcript received token=$token length=${question.length} phase=${gate.phase}")
        if (!gate.advance(token, LensMooTurnGate.Phase.LISTENING, LensMooTurnGate.Phase.WORKING)) return
        armDeadline(token, 600_000L)
        scope.launch {
            var stage = LensMooFailureMessage.Stage.CAPTURE
            try {
                require(question.isNotBlank())
                check(LensMooAiGate.ready(this@LensMooHandsFreeService)) { "AI не настроен" }
                val answer = LensMooQuestionFlow.run(
                    freshSession={ freshSessionForCapture(token) },
                    captureOnce={ current -> withContext(Dispatchers.IO) { current.capture(restorePreview=false) } },
                    answer={ jpeg -> withContext(Dispatchers.IO) {
                    ensureActive()
                    stage = LensMooFailureMessage.Stage.SAVE
                    android.util.Log.i("LensMooHandsFree", "Capture completed token=$token jpegBytes=${jpeg.size}; saving")
                    val image = saveOriginal(jpeg)
                    val thread = ChatStore.createThread("LensMoo — ${question.take(40)}")
                    ChatStore.addMessage(thread.id, ChatRole.USER, "$question\n[Фото очков: ${image.name}]")
                    ensureActive()
                    check(LensMooAiGate.ready(this@LensMooHandsFreeService)) { "Настройки AI изменились" }
                    // Use the selected on-device model or explicitly enabled cloud endpoint.
                    stage = LensMooFailureMessage.Stage.REMOTE
                    android.util.Log.i("LensMooHandsFree", "Sending selected model image request token=$token")
                    val result = LocalModelsProvider().streamChat(
                        context = this@LensMooHandsFreeService,
                        messages = lensMooMessages(listOf(mapOf("role" to "user", "content" to question))),
                        imagePaths = listOf(image.absolutePath))
                    ensureActive()
                    check(result.isNotBlank()) { "AI вернул пустой ответ" }
                    android.util.Log.i("LensMooHandsFree", "Model answer received token=$token length=${result.length}")
                    ChatStore.addMessage(thread.id, ChatRole.ASSISTANT, result)
                    result
                    } },
                )
                if (gate.advance(token, LensMooTurnGate.Phase.WORKING, LensMooTurnGate.Phase.SPEAKING)) {
                    report("Озвучиваю ответ…")
                    armDeadline(token, 180_000L)
                    voice?.speak(answer)
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                val message=LensMooFailureMessage.forFailure(stage,error)
                android.util.Log.w("LensMooHandsFree", "Question failed stage=$stage type=${error.javaClass.simpleName}")
                if (stage == LensMooFailureMessage.Stage.CAPTURE) {
                    ready=false
                    session?.close()
                }
                speakFailure(token,message)
            }
        }
    }
    private fun speakFailure(token: Long, message: String) {
        val phase=gate.phase
        if (phase != LensMooTurnGate.Phase.LISTENING && phase != LensMooTurnGate.Phase.WORKING) return
        if (!gate.advance(token,phase,LensMooTurnGate.Phase.SPEAKING)) return
        retainedError=message
        report(message)
        armDeadline(token,45_000L)
        runCatching { voice?.speak(message) }.onFailure { finishTurn(token,resetStatus=false) }
    }

    private fun saveOriginal(jpeg: ByteArray): File {
        val directory = File(filesDir, "lensmoo_questions").apply { mkdirs() }
        val file = File(directory, "${UUID.randomUUID()}.jpg")
        file.writeBytes(jpeg)
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "LensMoo-${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= 29) { put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/CyanBridge"); put(MediaStore.Images.Media.IS_PENDING, 1) }
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("Не удалось сохранить фото")
        try {
            contentResolver.openOutputStream(uri)?.use { it.write(jpeg) } ?: error("Не удалось записать фото")
            if (Build.VERSION.SDK_INT >= 29) contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        } catch (error: Exception) { contentResolver.delete(uri, null, null); throw error }
        return file
    }
    private fun finishTurn(token: Long, resetStatus: Boolean = true) {
        if (!gate.finish(token)) return
        android.util.Log.i("LensMooHandsFree", "Question finished token=$token retainedFailure=${retainedError!=null} resetStatus=$resetStatus")
        deadline?.cancel()
        voice?.close(); voice = null
        if (wakeLock.isHeld) wakeLock.release()
        if (resetStatus) report("Готово. Нажмите AI-кнопку для нового вопроса")
        else retainedError?.let(::report)
        if (!ready) scheduleConnection()
    }
    override fun onDestroy() {
        destroyed = true; ready = false; running = false; gate.close()
        mutableUiState.value=LensMooServiceUiState(message=retainedError ?: "Помощник выключен")
        session?.close(); session = null
        voice?.close(); voice = null
        deadline?.cancel(); healthJob?.cancel(); connectionJob?.cancel(); scope.cancel()
        if (::wakeLock.isInitialized && wakeLock.isHeld) wakeLock.release()
        if (foreground) stopForeground(STOP_FOREGROUND_REMOVE)
        retainedError?.let { message ->
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION+1,notification(message,ongoing=false))
        }
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}

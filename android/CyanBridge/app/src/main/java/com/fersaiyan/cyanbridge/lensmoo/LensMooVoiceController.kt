package com.fersaiyan.cyanbridge.lensmoo

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import java.io.Closeable
import java.util.Locale
import android.media.AudioManager
import android.media.AudioDeviceInfo
import android.media.AudioAttributes
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.tts.UtteranceProgressListener

/** One explicit utterance. Android owns microphone selection; SJ SPP carries no microphone audio. */
class LensMooVoiceController(
    private val activity: Context,
    private val onTranscript: (String)->Unit,
    private val onStatus: (String)->Unit,
    private val onIdle: ()->Unit = {},
    private val onFailure: (String)->Unit = {},
): Closeable {
    companion object { const val MICROPHONE_PERMISSION_REQUEST=194 }
    private var idleDelivered=true
    private fun finishIdle() { if(!idleDelivered) { idleDelivered=true; android.util.Log.i("LensMooVoice","Voice operation idle"); onIdle() } }
    private fun terminal(message:String) { stopSpeech(); restoreRoute(); onFailure(message); onStatus(message); finishIdle() }
    private var recognizer: SpeechRecognizer?=null
    private var listening=false
    private var closed=false
    private var ttsReady=false
    private var ttsFailed=false
    private var pendingSpeech:String?=null
    private var tts:TextToSpeech?=null
    private val audio=activity.getSystemService(AudioManager::class.java)
    private val handler=Handler(Looper.getMainLooper())
    private var savedMode:Int?=null
    private var savedCommunicationId:Int?=null
    private var routeToken=0
    private var scoConnected=false
    private var scoReceiver:BroadcastReceiver?=null
    private var speechToken=0L
    private var speechFile:java.io.File?=null
    private var speechPlayer:android.media.MediaPlayer?=null
    private var speechActive=false
    private var speechRecoveries=0
    private val speechChunks=java.util.ArrayDeque<String>()
    private var speechChunkIndex=0
    private var selectedRouteId:Int?=null
    private var selectedRouteNeedsSco=false
    private fun stopScoObservation() {
        scoReceiver?.let { runCatching { activity.unregisterReceiver(it) } }; scoReceiver=null
        scoConnected=false
    }
    private fun restoreRoute() {
        routeToken++
        selectedRouteId=null
        stopScoObservation()
        if(savedMode==null) return
        if(Build.VERSION.SDK_INT>=31) runCatching {
            val previous=audio.availableCommunicationDevices.firstOrNull { it.id==savedCommunicationId }
            if(previous!=null) audio.setCommunicationDevice(previous) else audio.clearCommunicationDevice()
        }
        runCatching { audio.mode=savedMode!! }; savedMode=null; savedCommunicationId=null
    }
    private fun routeGlasses(ready:()->Unit) {
        restoreRoute()
        if(Build.VERSION.SDK_INT<31) { terminal("Маршрут микрофона очков требует Android 12 или новее"); return }
        val target=activity.getSharedPreferences("lensmoo_private",Activity.MODE_PRIVATE).getString("address",null)
        val candidates=audio.availableCommunicationDevices.filter { it.type==AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type==AudioDeviceInfo.TYPE_BLE_HEADSET }
        val device=candidates.firstOrNull { it.address==target } ?: candidates.firstOrNull { it.productName.toString().contains("XKOEN",true) }
        if(device==null) { terminal("Аудиопрофиль очков не подключён. В Bluetooth включите звонки для XKOENPRO"); return }
        savedMode=audio.mode; savedCommunicationId=audio.communicationDevice?.id
        if(device.type==AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
            scoReceiver=object:BroadcastReceiver() {
                override fun onReceive(context:Context?,intent:Intent?) {
                    if(scoReceiver!==this) return
                    val state=intent?.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE,AudioManager.SCO_AUDIO_STATE_ERROR)
                    scoConnected=state==AudioManager.SCO_AUDIO_STATE_CONNECTED
                    android.util.Log.i("LensMooVoice","SCO audio state=$state")
                    if(!scoConnected && speechActive) handleSpeechRouteLoss()
                }
            }
            val filter=IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
            val sticky=if(Build.VERSION.SDK_INT>=33) activity.registerReceiver(scoReceiver,filter,Context.RECEIVER_EXPORTED) else activity.registerReceiver(scoReceiver,filter)
            scoConnected=sticky?.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE,AudioManager.SCO_AUDIO_STATE_ERROR)==AudioManager.SCO_AUDIO_STATE_CONNECTED
        }
        audio.mode=AudioManager.MODE_IN_COMMUNICATION
        if(!audio.setCommunicationDevice(device)) { restoreRoute(); terminal("Android отказал в выборе аудиопрофиля очков"); return }
        val token=++routeToken
        selectedRouteId=device.id
        selectedRouteNeedsSco=device.type==AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        val deadline=android.os.SystemClock.elapsedRealtime()+10_000
        fun checkRoute() {
            if(closed || token!=routeToken) return
            if(audio.communicationDevice?.id==device.id && (device.type!=AudioDeviceInfo.TYPE_BLUETOOTH_SCO || scoConnected)) {
                android.util.Log.i("LensMooVoice","Communication audio ready type=${device.type} id=${device.id}")
                ready()
            } else if(android.os.SystemClock.elapsedRealtime()<deadline) handler.postDelayed({ checkRoute() },150)
            else { restoreRoute(); terminal("Аудиоканал очков не подключился за 10 секунд. Проверьте профиль звонков Bluetooth") }
        }
        checkRoute()
    }
    init {
        tts=TextToSpeech(activity) { status ->
            ttsReady=status==TextToSpeech.SUCCESS
            ttsFailed=!ttsReady
            if(closed) tts?.shutdown()
            else if(ttsReady) pendingSpeech?.let { pendingSpeech=null; speak(it) }
            else if(pendingSpeech!=null) { pendingSpeech=null; terminal("Озвучивание недоступно: движок синтеза речи не запустился") }
        }
    }
    fun startListening() {
        check(!closed)
        idleDelivered=false
        if(activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED) {
            (activity as? Activity)?.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO),MICROPHONE_PERMISSION_REQUEST)
            terminal("Разрешите микрофон и снова нажмите голосовой вопрос")
            return
        }
        if(!SpeechRecognizer.isRecognitionAvailable(activity)) { terminal("В Android не установлен сервис распознавания речи"); return }
        if(listening) return
        stopSpeech()
        tts?.stop()
        if(recognizer==null) recognizer=SpeechRecognizer.createSpeechRecognizer(activity).apply {
            setRecognitionListener(object:RecognitionListener {
                override fun onReadyForSpeech(params:Bundle?) { android.util.Log.i("LensMooVoice","Recognizer ready communicationType=${if(Build.VERSION.SDK_INT>=31) audio.communicationDevice?.type else null}"); onStatus("Выбран аудиопрофиль очков. Говорите; распознавание выполняет Android") }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB:Float) {}
                override fun onBufferReceived(buffer:ByteArray?) {}
                override fun onEndOfSpeech() { onStatus("Распознаю вопрос…") }
                override fun onError(error:Int) { if(!listening) { android.util.Log.i("LensMooVoice","Ignored recognition error after terminal error=$error"); return }; android.util.Log.i("LensMooVoice","Recognition terminal error=$error"); listening=false; restoreRoute(); terminal(errorMessage(error)) }
                override fun onResults(results:Bundle?) {
                    if(!listening) return
                    listening=false
                    restoreRoute()
                    val transcript=results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
                    android.util.Log.i("LensMooVoice","Recognition results textLength=${transcript.length}")
                    if(transcript.isEmpty()) terminal("Речь не распознана. Попробуйте ещё раз")
                    else onTranscript(transcript)
                }
                override fun onPartialResults(partialResults:Bundle?) {}
                override fun onEvent(eventType:Int,params:Bundle?) {}
            })
        }
        routeGlasses {
            val token=routeToken
            val tone=runCatching { android.media.ToneGenerator(AudioManager.STREAM_VOICE_CALL,70) }.getOrNull()
            tone?.startTone(android.media.ToneGenerator.TONE_PROP_BEEP,150)
            android.util.Log.i("LensMooVoice","Ready tone requested on voice-call stream")
            handler.postDelayed({
                tone?.release()
                if(!closed && token==routeToken) beginRecognition()
            },250)
        }
    }
    private fun beginRecognition() {
        listening=true
        runCatching { recognizer?.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE,Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS,1)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS,30_000L)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS,false)
        }) }.onFailure { listening=false; restoreRoute(); terminal("Не удалось запустить распознавание речи") }
    }
    fun speak(answer:String) {
        if(closed || answer.isBlank()) return
        idleDelivered=false
        if(ttsFailed) { terminal("Озвучивание недоступно: движок синтеза речи не запустился"); return }
        if(!ttsReady) { pendingSpeech=answer; return }
        val engine=tts ?: return
        val language=engine.setLanguage(Locale.getDefault())
        if(language==TextToSpeech.LANG_MISSING_DATA || language==TextToSpeech.LANG_NOT_SUPPORTED) {
            terminal("Ответ получен, но для озвучивания не установлен голос выбранного языка"); return
        }
        stopSpeech()
        val token=++speechToken
        speechChunks.addAll(LensMooSpeechText.chunks(answer,TextToSpeech.getMaxSpeechInputLength()))
        speechChunkIndex=0
        speechRecoveries=0
        synthesizeNextChunk(token)
    }
    private fun synthesizeNextChunk(token:Long) {
        if(closed || token!=speechToken) return
        val engine=tts ?: return
        val text=speechChunks.removeFirst()
        val utteranceId="lensmoo-file-$token-${++speechChunkIndex}"
        val file=java.io.File.createTempFile("lensmoo-voice-", ".wav", activity.cacheDir)
        speechFile=file
        onStatus("Подготавливаю голосовой ответ…")
        engine.setOnUtteranceProgressListener(object:UtteranceProgressListener() {
            override fun onStart(id:String?) { android.util.Log.i("LensMooVoice","TTS file synthesis started") }
            override fun onDone(id:String?) { handler.post {
                if(!closed && token==speechToken && id==utteranceId) prepareSpeechPlayer(token,file)
            } }
            @Deprecated("Android callback") override fun onError(id:String?) { handler.post {
                if(token==speechToken && id==utteranceId) { stopSpeech(); restoreRoute(); terminal("Ошибка подготовки голосового ответа") }
            } }
        })
        if(engine.synthesizeToFile(text,Bundle(),file,utteranceId)==TextToSpeech.ERROR) {
            stopSpeech(); terminal("Ответ получен, но подготовить озвучивание не удалось")
        }
    }
    private fun prepareSpeechPlayer(token:Long,file:java.io.File) {
        if(closed || token!=speechToken) return
        if(file.length() !in 44L..32L*1024*1024) { terminal("Синтез речи создал некорректный аудиофайл"); return }
        android.util.Log.i("LensMooVoice","TTS file ready bytes=${file.length()}; playback owned by client UID")
        val player=android.media.MediaPlayer()
        speechPlayer=player
        try {
            player.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            player.setDataSource(file.absolutePath)
            player.setOnPreparedListener {
                if(token==speechToken && !closed) {
                    if(selectedRouteId!=null && audio.communicationDevice?.id==selectedRouteId && (!selectedRouteNeedsSco || scoConnected)) startSpeechPlayer(token,player)
                    else routeGlasses { startSpeechPlayer(token,player) }
                }
            }
            player.setOnCompletionListener {
                if(token==speechToken) {
                    android.util.Log.i("LensMooVoice","Client audio playback completed durationMs=${player.duration}")
                    speechActive=false
                    player.release(); speechPlayer=null
                    speechFile?.delete(); speechFile=null
                    if(speechChunks.isNotEmpty()) synthesizeNextChunk(token)
                    else { stopSpeech(); restoreRoute(); finishIdle() }
                }
            }
            player.setOnErrorListener { _,_,_ ->
                if(token==speechToken) { stopSpeech(); restoreRoute(); terminal("Ошибка воспроизведения голосового ответа") }
                true
            }
            player.prepareAsync()
        } catch(error:Exception) { stopSpeech(); restoreRoute(); terminal("Не удалось подготовить плеер голосового ответа") }
    }
    private fun startSpeechPlayer(token:Long,player:android.media.MediaPlayer,position:Int?=null) {
        if(closed || token!=speechToken || speechPlayer!==player) return
        val device=audio.availableCommunicationDevices.firstOrNull { it.id==selectedRouteId }
        if(device==null || !player.setPreferredDevice(device)) {
            stopSpeech(); restoreRoute(); terminal("Плеер не смог выбрать динамики очков"); return
        }
        runCatching {
            position?.let { player.seekTo(it) }
            player.start()
            speechActive=true
            onStatus("Озвучиваю ответ в очках…")
            android.util.Log.i("LensMooVoice","Client audio playback started positionMs=${position ?: 0}")
            monitorSpeechRoute(token,player)
        }.onFailure { stopSpeech(); restoreRoute(); terminal("Не удалось запустить голосовой ответ") }
    }
    private fun monitorSpeechRoute(token:Long,player:android.media.MediaPlayer) {
        handler.postDelayed({
            if(closed || token!=speechToken || speechPlayer!==player || !speechActive) return@postDelayed
            val routed=player.routedDevice
            if(audio.communicationDevice?.id!=selectedRouteId || (selectedRouteNeedsSco && !scoConnected) || (routed!=null && routed.id!=selectedRouteId)) {
                handleSpeechRouteLoss()
            } else {
                if(player.currentPosition/1000 % 5==0) android.util.Log.i("LensMooVoice","Client playback route type=${routed?.type} positionMs=${player.currentPosition}")
                monitorSpeechRoute(token,player)
            }
        },200)
    }
    private fun handleSpeechRouteLoss() {
        if(!speechActive) return
        speechActive=false
        val player=speechPlayer ?: return
        val token=speechToken
        val position=runCatching { player.currentPosition }.getOrDefault(0)
        runCatching { player.pause() }
        android.util.Log.w("LensMooVoice","Client playback route lost positionMs=$position")
        restoreRoute()
        if(speechRecoveries++==0) {
            onStatus("Аудиоканал очков прервался. Восстанавливаю озвучивание…")
            handler.post { if(!closed && token==speechToken) routeGlasses { startSpeechPlayer(token,player,position) } }
        } else { stopSpeech(); terminal("Аудиоканал очков повторно отключился. Ответ сохранён в чате; озвучивание остановлено.") }
    }
    private fun stopSpeech() {
        speechToken++
        speechChunks.clear()
        speechActive=false
        speechPlayer?.let { player ->
            runCatching { player.setOnCompletionListener(null); player.setOnErrorListener(null); player.release() }
        }
        speechPlayer=null
        speechFile?.delete(); speechFile=null
    }
    fun cancelListening() { if(closed) return; listening=false; recognizer?.cancel(); restoreRoute(); finishIdle() }
    override fun close() { closed=true; stopSpeech(); restoreRoute(); listening=false; pendingSpeech=null; recognizer?.destroy(); recognizer=null; tts?.stop(); tts?.shutdown(); tts=null }
    internal fun errorMessage(error:Int)=when(error) {
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Нет разрешения на микрофон"
        SpeechRecognizer.ERROR_NO_MATCH,SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Речь не распознана. Нажмите кнопку и повторите вопрос"
        SpeechRecognizer.ERROR_NETWORK,SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Сервису распознавания недоступна сеть"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Распознавание занято. Попробуйте ещё раз"
        else -> "Распознавание речи завершилось с ошибкой $error"
    }
}

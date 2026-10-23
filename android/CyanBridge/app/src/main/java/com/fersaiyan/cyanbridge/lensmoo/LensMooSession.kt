package com.fersaiyan.cyanbridge.lensmoo

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import java.io.Closeable
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Blocking operations belong on an IO executor. Closing interrupts every pending operation. */
class LensMooSession(private val context: Context, private val onVideo: (ByteArray)->Unit = {}, private val onButton: ()->Unit = {}, private val onStatus: (String)->Unit = {}, private val onDisconnected: ()->Unit = {}, private val onVoiceButton: ()->Unit = {}): Closeable {
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var failure: Throwable? = null
    @Volatile private var state = "IDLE"
    private val operations = Any()
    private val writeLock = Any()
    private var order=0
    private var pkg=0
    private data class Received(val node:LensMooProtocol.Node,val at:Long,val action:Int)
    private val replies=LinkedBlockingQueue<Received>(256)
    private val events=LinkedBlockingQueue<LensMooProtocol.Node>(256)
    private val seenNodes=java.util.ArrayDeque<ByteArray>()
    private val media=LinkedBlockingQueue<Pair<Int,ByteArray>>(8)
    private val voiceButton=LensMooVoiceButton()
    private var lastButton=0L
    @Volatile private var assistantAt=0L
    private val buttonExecutor=java.util.concurrent.Executors.newSingleThreadScheduledExecutor()
    @Volatile private var pendingButton: java.util.concurrent.ScheduledFuture<*>?=null
    private var photoParts=mutableListOf<ByteArray>()
    private var photoOrder=-1
    private var lastFinal: ByteArray?=null
    private var middle=0
    private class VideoGroup(val started:Long=System.currentTimeMillis(), val chunks:MutableMap<Int,ByteArray> = mutableMapOf(),var last:Int?=null)
    private val videoGroups=mutableMapOf<Int,VideoGroup>()
    private fun send(b:ByteArray) = synchronized(writeLock) { (socket ?: error("Disconnected")).outputStream.write(b) }
    private fun next() = synchronized(writeLock) { order=(order+1)%253; order }
    private fun alive() { failure?.let { throw IllegalStateException("LensMoo connection failed",it) }; check(socket!=null) { "Disconnected" } }
    private var responseFormat=-1
    private fun request(urn:String,data:ByteArray=byteArrayOf(),action:Int=1,format:Int=0):ByteArray {
        alive(); replies.removeIf { it.node.urn == urn }; pkg=(pkg+1) and 65535; val expected=pkg
        android.util.Log.d("LensMoo", "request urn=$urn pkg=$expected action=$action")
        val sentAt=System.nanoTime()
        send(LensMooProtocol.node(next(),expected,urn,data,action,format))
        val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(12)
        while(System.nanoTime()<end) { alive(); val n=replies.poll(100,TimeUnit.MILLISECONDS) ?: continue; if(n.at>=sentAt && n.node.urn==urn && (n.action and 7)!=3) { responseFormat=n.node.format; return n.node.data } }
        close(); error("No operation response for $urn")
    }
    private fun waitState(urn:String,value:ByteArray) {
        val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(15)
        while(System.nanoTime()<end) { alive(); val n=events.poll(100,TimeUnit.MILLISECONDS) ?: continue; if(n.urn==urn) {
            if(urn in setOf("5710","57B1") && n.data.size==1) android.util.Log.i("LensMoo", "Camera wait urn=$urn expected=${value.firstOrNull()?.toInt()?.and(255)} received=${n.data[0].toInt() and 255} state=$state")
            if(n.data.contentEquals(value)) return
        } }
        error("Camera state timeout: $urn")
    }
    private fun confirmCameraIdle() {
        try { LensMooCameraStop.confirm {
            events.clear()
            ok(request("5720",byteArrayOf(0),3))
            val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(15)
            LensMooCameraStop.awaitState(readNext={
                alive()
                val n=events.poll(100,TimeUnit.MILLISECONDS)
                if(n?.urn=="5710") {
                    check(n.format==0 && n.data.size==1) { "Invalid camera state" }
                    val result=n.data[0].toInt() and 255
                    android.util.Log.i("LensMoo","Camera stop confirmation value=$result state=$state")
                    result
                } else null
            },now=System::nanoTime,deadline=end)
        } } catch(error:Throwable) {
            // Do not start another stop cycle from capture's cleanup after this bounded failure.
            close()
            throw error
        }
    }
    private fun ok(data:ByteArray) { check(data.contentEquals(byteArrayOf(0))) { "Device rejected operation" } }
    @SuppressLint("MissingPermission")
    fun connect(address:String,owner:String) = synchronized(operations) {
        require(owner.toByteArray().size in 1..255)
        val adapter=context.getSystemService(BluetoothManager::class.java).adapter ?: error("Bluetooth unavailable")
        val s=adapter.getRemoteDevice(address).createRfcommSocketToServiceRecord(UUID.fromString("00001101-0000-1000-8000-00805f9b34fb"))
        socket=s; failure=null
        try {
            val connectTimeout=buttonExecutor.schedule({ runCatching { s.close() } },20,TimeUnit.SECONDS)
            try { s.connect() } finally { connectTimeout.cancel(false) }
            Thread({ try { val parser=LensMooProtocol.Parser(); val buffer=ByteArray(8192); while(socket===s) { val count=s.inputStream.read(buffer); check(count>0) { "SPP EOF" }; parser.feed(buffer.copyOf(count)).forEach(::handle) } } catch(t:Throwable) {
                val expectedClose=socket!==s
                failure=t
                val safeMessage=when(t.message) {
                    "SPP EOF", "SJ CRC mismatch", "Unknown SJ header", "Check failed.", "Failed requirement." -> t.message
                    else -> "details omitted"
                }
                val location=t.stackTrace.take(5).joinToString(" <- ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
                android.util.Log.e("LensMoo", "Reader stopped expected=$expectedClose state=$state cause=${t.javaClass.simpleName} reason=$safeMessage at=$location")
                runCatching { s.close() }
                if(!expectedClose) { socket=null; state="CLOSED"; onDisconnected() }
                if(!expectedClose) onStatus("Connection closed: ${t.javaClass.simpleName} ($safeMessage)")
            } },"LensMoo-SPP").start()
            val random=SecureRandom(); fun ascii(n:Int)=ByteArray(n) { "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"[random.nextInt(62)].code.toByte() }
            request("0001",ascii(61),3)
            val prefix=ascii(14); val seed=ascii(2); val challenge=ascii(32)
            val auth=prefix+seed+LensMooProtocol.crypt(((seed[0].toInt() and 255) shl 8) or (seed[1].toInt() and 255),challenge)+ascii(16)
            check(LensMooProtocol.verify(auth,request("0002",auth,3))) { "Handshake verification failed" }
            request("7100")
            check(request("102E",byteArrayOf(2)+ByteArray(16)+byteArrayOf(owner.toByteArray().size.toByte())+owner.toByteArray(),3).contentEquals(byteArrayOf(2,0))) { "Owner reconnect rejected" }
            request("7110")
            val now=Date(); val clock=JSONObject().put("currDate",SimpleDateFormat("yyyy-MM-dd",Locale.US).format(now)).put("currTime",SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.US).format(now)).put("timeZoo",TimeZone.getDefault().getDisplayName(false,TimeZone.SHORT)).put("timestamp",now.time/1000)
            ok(request("1007",clock.toString().toByteArray(),3,2))
            val info=request("1001").toString(Charsets.UTF_8); val battery=request("1003"); request("5713")
            val details=runCatching { JSONObject(info) }.getOrNull()
            val model=details?.optString("prod_mode")?.take(80).orEmpty()
            val version=details?.optString("soft_ver")?.take(40).orEmpty()
            state="READY"; onStatus("Connected: $model $version; battery ${battery.getOrNull(1)?.toInt()?.and(255) ?: -1}%")
        } catch(t:Throwable) { close(); throw t }
    }
    /** Explicit read-only health check; not an assumed vendor heartbeat. */
    fun checkConnection():Int = synchronized(operations) {
        alive(); val battery=request("1003")
        val percent=LensMooBattery.percent(responseFormat,battery)
        android.util.Log.i("LensMoo","Health check battery response received")
        percent
    }
    fun startPreview() = synchronized(operations) { state="STARTING"; synchronized(videoGroups) { videoGroups.clear() }; ok(request("5720",byteArrayOf(1),3)); state="STREAMING" }
    val isConnected:Boolean get() = socket!=null && failure==null && state!="CLOSED"
    /** SDK AppAIAssistant.stopRecording: C104, WRITE, empty binary body. */
    fun stopAssistantRecording() = synchronized(operations) {
        alive()
        ok(request("C104",byteArrayOf(),2))
        android.util.Log.i("LensMoo","Device assistant recording stop confirmed")
    }
    fun stopPreview() = synchronized(operations) {
        alive()
        if(state in setOf("STREAMING", "PHOTO_PENDING", "STARTING")) {
            state="STOPPING"; pendingButton?.cancel(false); events.clear()
            try { confirmCameraIdle(); state="READY" } catch(t:Throwable) { close(); throw t }
        }
    }
    fun capture(restorePreview:Boolean=false):ByteArray = synchronized(operations) {
        check(state in setOf("READY", "STREAMING", "PHOTO_PENDING")) { "Glasses are not ready" }
        val wasStreaming=state=="STREAMING" || state=="PHOTO_PENDING"
        val resumePreview=restorePreview && wasStreaming
        pendingButton?.cancel(false)
        state="CAPTURING"
        var cameraStopped=false
        try {
            // A physical AI request can leave camera state active even without preview.
            // Confirm idle before opening media transfer, as well as after the shot.
            confirmCameraIdle()
            LensMooPhotoFlow.capture(
                listPhotos={ listPhotos().toSet() },
                shootAndWaitSaved={ events.clear(); ok(request("57B0",byteArrayOf(),3)); waitState("57B1",byteArrayOf(1)) },
                stopAndWaitIdle={ confirmCameraIdle(); cameraStopped=true },
                download={ download(it) },
            )
        } finally {
            if(socket!=null && failure==null) {
                state="STOPPING"
                val stopped=runCatching {
                    if(!cameraStopped) { confirmCameraIdle() }; state="READY"
                }
                stopped.onFailure {
                    onStatus("Camera stop was not confirmed; disconnecting")
                    close()
                }
                if(stopped.isSuccess && resumePreview) runCatching { startPreview() }.onFailure {
                    state="READY"; onStatus("Photo operation completed; preview restart failed")
                }
            }
        }
    }
    private fun element(names:Boolean=false):ByteArray {
        val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(30)
        while(System.nanoTime()<end) { alive(); val item=media.poll(100,TimeUnit.MILLISECONDS) ?: continue; check((item.first!=0)==names); return item.second }; error("Photo transfer timeout")
    }
    private fun listPhotos():List<String> { try { media.clear(); ok(request("7200",byteArrayOf(0),3)); return element(true).toString(Charsets.UTF_8).split('|').filter { it.isNotEmpty() } } finally { ok(request("7500",byteArrayOf(0),3)) } }
    private fun download(name:String):ByteArray {
        try {
            ok(request("7200",byteArrayOf(0),3)); check(name in element(true).toString(Charsets.UTF_8).split('|'))
            val count=request("7310",name.toByteArray(),2); check(count.size==1 && count[0].toInt() in 1..127)
            val output=java.io.ByteArrayOutputStream()
            for(i in 1..count[0].toInt()) { ok(request("7300",byteArrayOf(i.toByte()),2)); val part=element(); check(output.size()+part.size<=32*1024*1024); output.write(part) }
            val b=output.toByteArray(); check(b.size>=4 && b[0]==0xff.toByte() && b[1]==0xd8.toByte() && b[b.size-2]==0xff.toByte() && b.last()==0xd9.toByte()); return b
        } finally { ok(request("7500",byteArrayOf(0),3)) }
    }
    private fun handle(f:ByteArray) {
        when(f[0].toInt() and 255) {
            0x30 -> if((f[2].toInt() and 255) in 1..2) {
                send(LensMooProtocol.envelope(0x30,next(),4,byteArrayOf(1,f[1])))
                val duplicate=seenNodes.any { it.contentEquals(f) }
                // Log control events before duplicate filtering, never their payloads.
                for(n in LensMooProtocol.nodes(f)) {
                    if(n.urn in setOf("C001","C002","5710","57B1")) {
                        android.util.Log.i("LensMoo","Raw control urn=${n.urn} pkg=${n.pkg} order=${f[1].toInt() and 255} duplicate=$duplicate format=${n.format} length=${n.data.size} state=$state")
                    }
                }
                if(duplicate) return
                seenNodes.addLast(f.copyOf()); if(seenNodes.size>128) seenNodes.removeFirst()
                for(n in LensMooProtocol.nodes(f)) {
                    android.util.Log.d("LensMoo", "node urn=${n.urn} pkg=${n.pkg} action=${f.u16(22)} format=${n.format} length=${n.data.size}")
                    if(n.urn in setOf("5710","57B1") && n.data.size==1) android.util.Log.i("LensMoo", "Camera event urn=${n.urn} value=${n.data[0].toInt() and 255} state=$state")
                    if(n.urn in setOf("5710", "57B1", "5790")) { if(!events.offer(n)) { events.poll(); events.offer(n) } }
                    val received=Received(n,System.nanoTime(),f.u16(22))
                    if(!replies.offer(received)) { replies.poll(); replies.offer(received) }
                    if((n.urn=="C001" || n.urn=="C002") && n.format==0 && n.data.isEmpty()) {
                        send(LensMooProtocol.node(next(),0,n.urn,byteArrayOf(0),100,0,2))
                        if(n.urn=="C001") {
                            val now=System.currentTimeMillis()
                            val trigger=voiceButton.accept(n.urn,n.format,n.data.size,state,now)
                            assistantAt=now; pendingButton?.cancel(false)
                            // A preceding 5710 has already confirmed that the physical AI key stopped preview.
                            if(state=="PHOTO_PENDING") state="READY"
                            if(trigger) { android.util.Log.i("LensMoo","Physical AI start accepted"); onVoiceButton() }
                            else android.util.Log.i("LensMoo","Physical AI start filtered state=$state")
                        }
                    }
                    if(n.urn=="5710" && n.data.contentEquals(byteArrayOf(0)) && state=="STREAMING") {
                        val now=System.currentTimeMillis(); if(now-lastButton>500 && now-assistantAt>500) { lastButton=now; state="PHOTO_PENDING"; pendingButton=buttonExecutor.schedule({ if(state=="PHOTO_PENDING" && System.currentTimeMillis()-assistantAt>500) onButton() },500,TimeUnit.MILLISECONDS) }
                    }
                }
            }
            0x1e -> if(state=="STREAMING" || state=="STARTING" || state=="PHOTO_PENDING") video(f)
            0x4a -> photo(f)
        }
    }
    private fun video(f:ByteArray) = synchronized(videoGroups) {
        val kind=f[4].toInt() and 7; val total=((f[4].toInt() and 248) shl 5) or (f[5].toInt() and 255)
        var data=f.copyOfRange(16,f.size)
        if(kind in 1..3) {
            check(data.size>=4); val index=data.u32(0); check(index in 0..4096 && (kind==1)==(index==0))
            val key=((f[1].toInt() and 255) shl 16) or total
            val group=videoGroups.getOrPut(key) { VideoGroup() }
            check(videoGroups.size<=8 && videoGroups.values.all { System.currentTimeMillis()-it.started<5000 })
            val chunk=data.copyOfRange(4,data.size); group.chunks[index]?.let { check(it.contentEquals(chunk)) }; group.chunks[index]=chunk
            check(group.chunks.values.sumOf { it.size }<=65535)
            if(kind==3) group.last=index
            val last=group.last ?: return@synchronized; if((0..last).any { it !in group.chunks }) return@synchronized
            data=(0..last).flatMap { group.chunks.getValue(it).asIterable() }.toByteArray(); videoGroups.remove(key)
        } else check(kind==0)
        check(data.size>=5 && (total==0 || total==data.size) && data.u32(1)==data.size-5)
        val h264=data.copyOfRange(5,data.size); check(h264.size>=4 && h264[0]==0.toByte() && h264[1]==0.toByte() && (h264[2]==1.toByte() || (h264[2]==0.toByte() && h264[3]==1.toByte())))
        send(LensMooProtocol.envelope(0x1e,order,2,byteArrayOf(1))); onVideo(h264)
    }
    private fun photo(f:ByteArray) {
        check((f.u16(2) and 0x7fff)==1)
        val kind=f[4].toInt() and 7
        if(kind==3 && lastFinal?.contentEquals(f)==true) { send(LensMooProtocol.envelope(0x4a,order,9,byteArrayOf(1))); return }
        if(kind==0 || kind==1) lastFinal=null
        var data=f.copyOfRange(16,f.size)
        if(kind in 1..3) {
            check(data.size>=4); val index=data.u32(0); check(index in 0..8191 && (kind==1)==(index==0)); val chunk=data.copyOfRange(4,data.size)
            if(photoParts.isEmpty()) { check(kind==1); photoOrder=f[1].toInt() }
            check(photoOrder==f[1].toInt())
            if(index<photoParts.size) { check(photoParts[index].contentEquals(chunk)); return }
            check(index==photoParts.size); photoParts.add(chunk); check(photoParts.sumOf { it.size }<=2*1024*1024)
            if(kind==2 && ++middle>20) { send(byteArrayOf(0,1,0,1)); middle=0 }
            if(kind!=3) return
            data=photoParts.flatMap { it.asIterable() }.toByteArray(); photoParts.clear(); send(LensMooProtocol.envelope(0x4a,order,9,byteArrayOf(1))); lastFinal=f.copyOf()
        } else check(kind==0 && photoParts.isEmpty())
        check(data.size>=5 && data.u32(1)==data.size); check(media.offer((data[0].toInt() and 255) to data.copyOfRange(5,data.size)))
    }
    override fun close() { state="CLOSED"; pendingButton?.cancel(false); buttonExecutor.shutdownNow(); val s=socket; socket=null; runCatching { s?.close() } }
}

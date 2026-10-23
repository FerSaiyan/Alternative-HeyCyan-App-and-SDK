package com.fersaiyan.cyanbridge.lensmoo

/** Cancellation checkpoints around blocking connection work; never waits on the UI thread. */
internal class LensMooPreviewGate {
    private val generation=java.util.concurrent.atomic.AtomicLong()
    fun request():Long=generation.incrementAndGet()
    fun cancel() { generation.incrementAndGet() }
    fun isActive(token:Long)=generation.get()==token
    fun startIfActive(token:Long,connect:()->Unit,start:()->Unit):Boolean {
        if(!isActive(token)) return false
        connect()
        if(!isActive(token)) return false
        start()
        return true
    }
}

package com.fersaiyan.cyanbridge.lensmoo

/** Only the SDK's empty binary C001 start event is a voice request. */
internal class LensMooVoiceButton {
    private var lastAccepted=Long.MIN_VALUE
    fun accept(urn:String,format:Int,size:Int,state:String,now:Long):Boolean {
        if(urn!="C001" || format!=0 || size!=0 || state !in setOf("READY","STREAMING","PHOTO_PENDING")) return false
        if(lastAccepted!=Long.MIN_VALUE && now-lastAccepted<1500) return false
        lastAccepted=now
        return true
    }
}

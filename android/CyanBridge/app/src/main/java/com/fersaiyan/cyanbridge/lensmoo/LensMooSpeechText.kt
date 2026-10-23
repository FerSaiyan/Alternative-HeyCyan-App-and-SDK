package com.fersaiyan.cyanbridge.lensmoo

/** Bound each synthesis request without dropping text or splitting UTF-16 pairs. */
internal object LensMooSpeechText {
    fun chunks(text:String,limit:Int):List<String> {
        require(limit>=2)
        val result=mutableListOf<String>()
        var start=0
        while(start<text.length) {
            var end=minOf(start+limit,text.length)
            if(end<text.length) {
                if(text[end-1].isHighSurrogate() && text[end].isLowSurrogate()) end--
                val boundary=(end-1 downTo start+limit/2).firstOrNull { text[it].isWhitespace() }
                if(boundary!=null) end=boundary+1
            }
            result+=text.substring(start,end)
            start=end
        }
        return result
    }
}

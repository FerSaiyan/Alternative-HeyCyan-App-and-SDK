package com.fersaiyan.cyanbridge.lensmoo

/** Each recognized question gets one fresh authenticated transport and at most one capture. */
internal object LensMooQuestionFlow {
    suspend fun <Session, Answer> run(
        freshSession: suspend () -> Session,
        captureOnce: suspend (Session) -> ByteArray,
        answer: suspend (ByteArray) -> Answer,
    ): Answer {
        val session=freshSession()
        val jpeg=captureOnce(session)
        return answer(jpeg)
    }
}

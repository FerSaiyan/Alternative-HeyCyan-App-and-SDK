package com.fersaiyan.cyanbridge.lensmoo

/** Runs media transfer only after a saved photo and camera shutdown have both been confirmed. */
internal object LensMooPhotoFlow {
    fun capture(
        listPhotos: () -> Set<String>,
        shootAndWaitSaved: () -> Unit,
        stopAndWaitIdle: () -> Unit,
        download: (String) -> ByteArray,
    ): ByteArray {
        val before=listPhotos()
        shootAndWaitSaved()
        stopAndWaitIdle()
        val added=listPhotos()-before
        check(added.size==1) { "Cannot identify new photo" }
        return download(added.single())
    }
}

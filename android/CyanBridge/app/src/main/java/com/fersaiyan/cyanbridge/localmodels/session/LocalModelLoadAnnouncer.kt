package com.fersaiyan.cyanbridge.localmodels.session

/**
 * Lets a glasses question say "Initializing local model" only when it actually waits for a cold
 * load. [LocalChatSessionManager] reports cold loads; the question flow [arm]s after the question
 * is recorded and [disarm]s when the question finishes. While armed, the listener fires at most
 * once: immediately if a cold load is already running (for example the preload started during
 * the "Ask" cue), or when one starts. Warm requests never start a cold load, so they stay silent.
 */
class LocalModelLoadAnnouncer(
    private val clock: () -> Long = System::currentTimeMillis,
    /** A question that never disarms (a missed cleanup path) cannot make a later, unrelated load speak. */
    private val armTtlMs: Long = 3 * 60_000L,
) {
    private val lock = Any()
    private var armedAtMs = 0L
    private var loadingModelId: String? = null
    private var listener: ((String) -> Unit)? = null
    private var announced = false

    val isColdLoading: Boolean get() = synchronized(lock) { loadingModelId != null }

    fun onColdLoadStarted(modelId: String) {
        val fire = synchronized(lock) {
            loadingModelId = modelId
            takeListenerLocked()
        }
        fire?.invoke(modelId)
    }

    fun onColdLoadFinished() {
        synchronized(lock) { loadingModelId = null }
    }

    /** Re-arming during the same question keeps the once-only guarantee. */
    fun arm(onColdLoad: (modelId: String) -> Unit) {
        val (fire, id) = synchronized(lock) {
            if (listener == null) armedAtMs = clock()
            listener = onColdLoad
            val id = loadingModelId
            (if (id != null) takeListenerLocked() else null) to id
        }
        if (fire != null && id != null) fire(id)
    }

    fun disarm() {
        synchronized(lock) {
            listener = null
            announced = false
        }
    }

    private fun takeListenerLocked(): ((String) -> Unit)? {
        if (announced) return null
        val current = listener ?: return null
        if (clock() - armedAtMs > armTtlMs) return null
        announced = true
        return current
    }

    companion object {
        val shared = LocalModelLoadAnnouncer()
    }
}

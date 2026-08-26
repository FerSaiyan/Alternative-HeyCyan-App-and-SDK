package com.fersaiyan.cyanbridge.localagent.tasker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Small request/response bridge for Tasker. CyanBridge owns planning and product policy;
 * Tasker observes Android UI or executes requested Android-side effects and reports the
 * concrete result. All transport failures are surfaced back to the caller for debugging.
 */
object TaskerAgentBridge {
    data class Response(
        val success: Boolean,
        val payload: String?,
        val error: String?,
    )

    private data class Pending(
        val token: String,
        val deferred: CompletableDeferred<Response>,
    )

    private val pending = ConcurrentHashMap<String, Pending>()
    private val registrationMutex = Mutex()
    @Volatile private var registered = false

    suspend fun requestObservation(context: Context, timeoutMs: Long = 8_000L): Response =
        request(
            context = context,
            action = TaskerAgentContract.ACTION_OBSERVE,
            payload = "{\"contract_version\":${TaskerAgentContract.VERSION}}",
            timeoutMs = timeoutMs,
        )

    suspend fun requestAutoDiaryObservation(
        context: Context,
        timeoutMs: Long = 8_000L,
    ): Response = request(
        context = context,
        action = TaskerAgentContract.ACTION_AUTO_DIARY_OBSERVE,
        payload = "{\"contract_version\":${TaskerAgentContract.VERSION},\"source\":\"auto_diary\"}",
        timeoutMs = timeoutMs,
    )

    suspend fun executeAction(
        context: Context,
        actionPayload: String,
        timeoutMs: Long = 12_000L,
    ): Response = request(
        context = context,
        action = TaskerAgentContract.ACTION_EXECUTE,
        payload = actionPayload,
        timeoutMs = timeoutMs,
    )

    fun isTaskerInstalled(context: Context): Boolean = packageInstalled(context, TASKER_PACKAGE)

    fun isAutoInputInstalled(context: Context): Boolean = packageInstalled(context, AUTOINPUT_PACKAGE)

    fun isTaskerUiObserverAvailable(context: Context): Boolean =
        isTaskerInstalled(context) && isAutoInputInstalled(context)

    private fun packageInstalled(context: Context, packageName: String): Boolean = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(packageName, 0)
    }.isSuccess

    private suspend fun request(
        context: Context,
        action: String,
        payload: String,
        timeoutMs: Long,
    ): Response {
        ensureReceiver(context.applicationContext)
        val requestId = UUID.randomUUID().toString()
        val callbackToken = randomToken()
        val deferred = CompletableDeferred<Response>()
        pending[requestId] = Pending(callbackToken, deferred)

        val intent = Intent(action).apply {
            setPackage(TASKER_PACKAGE)
            putExtra(TaskerAgentContract.EXTRA_VERSION, TaskerAgentContract.VERSION)
            putExtra(TaskerAgentContract.EXTRA_REQUEST_ID, requestId)
            putExtra(TaskerAgentContract.EXTRA_CALLBACK_TOKEN, callbackToken)
            putExtra(TaskerAgentContract.EXTRA_PAYLOAD, payload)
        }
        context.sendBroadcast(intent)

        val result = withTimeoutOrNull(timeoutMs) { deferred.await() }
        pending.remove(requestId)
        return result ?: Response(
            success = false,
            payload = null,
            error = "tasker_timeout:$action",
        )
    }

    private suspend fun ensureReceiver(context: Context) {
        if (registered) return
        registrationMutex.withLock {
            if (registered) return
            val filter = IntentFilter(TaskerAgentContract.ACTION_RESPONSE)
            ContextCompat.registerReceiver(
                context,
                receiver,
                filter,
                ContextCompat.RECEIVER_EXPORTED,
            )
            registered = true
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != TaskerAgentContract.ACTION_RESPONSE) return

            // Preferred Tasker-friendly response format: one JSON envelope extra. Tasker's
            // JavaScript sendIntent helper has a deliberately small extras surface, so putting
            // correlation metadata and result fields in one envelope keeps the profile simple.
            val compact = intent.getStringExtra(TaskerAgentContract.EXTRA_RESPONSE)
            if (!compact.isNullOrBlank()) {
                completeCompactResponse(compact)
                return
            }

            // Backwards-compatible expanded response while hand-written/debug profiles still use it.
            if (intent.getIntExtra(TaskerAgentContract.EXTRA_VERSION, -1) != TaskerAgentContract.VERSION) return
            val requestId = intent.getStringExtra(TaskerAgentContract.EXTRA_REQUEST_ID) ?: return
            val expected = pending[requestId] ?: return
            val token = intent.getStringExtra(TaskerAgentContract.EXTRA_CALLBACK_TOKEN) ?: return
            if (token != expected.token) return

            expected.deferred.complete(
                Response(
                    success = intent.getBooleanExtra(TaskerAgentContract.EXTRA_SUCCESS, false),
                    payload = intent.getStringExtra(TaskerAgentContract.EXTRA_PAYLOAD),
                    error = intent.getStringExtra(TaskerAgentContract.EXTRA_ERROR),
                )
            )
        }
    }

    private fun completeCompactResponse(raw: String) {
        val envelope = runCatching { JSONObject(raw) }.getOrNull() ?: return
        if (envelope.optInt(TaskerAgentContract.EXTRA_VERSION, -1) != TaskerAgentContract.VERSION) return

        val requestId = envelope.optString(TaskerAgentContract.EXTRA_REQUEST_ID).takeIf { it.isNotBlank() }
            ?: return
        val expected = pending[requestId] ?: return
        val token = envelope.optString(TaskerAgentContract.EXTRA_CALLBACK_TOKEN)
        if (token != expected.token) return

        expected.deferred.complete(
            Response(
                success = envelope.optBoolean(TaskerAgentContract.EXTRA_SUCCESS, false),
                payload = envelope.optString(TaskerAgentContract.EXTRA_PAYLOAD)
                    .takeIf { it.isNotBlank() && it != "null" },
                error = envelope.optString(TaskerAgentContract.EXTRA_ERROR)
                    .takeIf { it.isNotBlank() && it != "null" },
            )
        )
    }

    private fun randomToken(): String {
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private const val TASKER_PACKAGE = "net.dinglisch.android.taskerm"
    private const val AUTOINPUT_PACKAGE = "com.joaomgcd.autoinput"
}

package com.fersaiyan.cyanbridge.ai.questions

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import com.fersaiyan.cyanbridge.localagent.LocalAgentController

/**
 * Keeps LiteRT-LM's cached decision system turn in step with the Local Agent setup. Spoken and photo questions use
 * one of two system turns, with [phone] when the Local Agent can run phone tasks
 * (`LocalAgentController.unavailableReason == null`) and without it otherwise
 * (ImageQuestionDefaults.spokenDecisionRoutes). The engine caches whichever the last question used (the CPU root, the
 * GPU/NPU spare); when phone control is toggled, Tasker is installed or removed, or the app comes back to the front
 * with a different setup, the other one is primed in the background ([GlassesQuestions.primeSpokenSystemTurn]).
 */
internal object SpokenSystemTurnPrimer {
    private const val TAG = "SpokenSystemTurn"

    /** Whether the last decision question's system turn had [phone]; null before the first one. */
    @Volatile private var cachedPhoneTasks: Boolean? = null

    /** A decision question was built with this variant (it is what the engine now caches). */
    fun noteUsed(phoneTasks: Boolean) {
        cachedPhoneTasks = phoneTasks
    }

    /** The Local Agent setup may have changed: re-prime the cache when the variant differs from the cached one. */
    fun onPhoneSetupMaybeChanged(context: Context, reason: String) {
        val app = context.applicationContext
        val cached = cachedPhoneTasks ?: return // nothing cached yet: the next question builds the current variant
        val now = runCatching { LocalAgentController.unavailableReason(app) == null }.getOrDefault(false)
        if (now == cached) return
        cachedPhoneTasks = now
        Log.i(TAG, "Local Agent setup changed ($reason): priming the system turn ${if (now) "with" else "without"} [phone]")
        GlassesQuestions.get(app).primeSpokenSystemTurn()
    }

    /** Tasker installed, updated or removed while the app runs. */
    fun registerPackageWatcher(context: Context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.data?.schemeSpecificPart == TASKER_PACKAGE) onPhoneSetupMaybeChanged(context, intent.action.orEmpty())
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.applicationContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.applicationContext.registerReceiver(receiver, filter)
            }
        }.onFailure { Log.w(TAG, "Package watcher not registered", it) }
    }

    private const val TASKER_PACKAGE = "net.dinglisch.android.taskerm"
}

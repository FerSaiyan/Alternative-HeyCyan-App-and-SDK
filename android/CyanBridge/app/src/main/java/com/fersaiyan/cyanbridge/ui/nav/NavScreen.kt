package com.fersaiyan.cyanbridge.ui.nav

import android.content.ContextWrapper
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.result.ActivityResultCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelLazy
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * One screen of the single [com.fersaiyan.cyanbridge.MainActivity]; each used to be its own Activity.
 *
 * It keeps the Activity shape on purpose (`onCreate`/`onStart`/`onResume`…, `intent`,
 * `onNewIntent`, `registerForActivityResult`, `viewModels`, `finish()`), so the screens moved
 * over with the same behavior. The [AppNavigator] drives the lifecycle the way the system drove
 * the Activity: RESUMED while on top of a resumed host, CREATED (stopped) while another screen
 * covers it, DESTROYED when it leaves the back stack or the host goes away. A rotation recreates
 * the screen with its saved state, as it recreated the Activity; ViewModels survive it.
 *
 * The screen is a [ContextWrapper] around the host, so `this` still works as a Context;
 * APIs that need the Activity itself take [activity].
 */
abstract class NavScreen : ContextWrapper(null), LifecycleOwner, ViewModelStoreOwner {
    lateinit var activity: AppCompatActivity
        private set
    internal lateinit var entry: NavEntry
        private set
    private lateinit var navigator: AppNavigator

    private val registry = LifecycleRegistry(this)
    private val launchers = mutableListOf<ScreenResultLauncher<*, *>>()
    private var attached = false
    private var finishing = false
    private var pendingRestore: Bundle? = null
    private var screenContent: (@Composable () -> Unit)? by mutableStateOf(null)
    private var contentView: View? = null

    override val lifecycle: Lifecycle get() = registry
    override val viewModelStore: ViewModelStore get() = entry.viewModelStore

    /** The intent that opened this screen (its extras were the Activity's extras). */
    val intent: Intent get() = entry.intent

    fun setIntent(newIntent: Intent) {
        entry.intent = newIntent
    }

    val window: Window get() = activity.window
    val onBackPressedDispatcher: OnBackPressedDispatcher get() = activity.onBackPressedDispatcher

    /** The host's root view, for click sounds and posts that used the Activity's views. */
    val view: View get() = activity.window.decorView

    val isFinishing: Boolean get() = finishing || activity.isFinishing
    val isDestroyed: Boolean get() = registry.currentState == Lifecycle.State.DESTROYED

    open fun onCreate(savedInstanceState: Bundle?) = Unit
    open fun onStart() = Unit
    open fun onRestoreInstanceState(savedInstanceState: Bundle) = Unit
    open fun onResume() = Unit
    open fun onPostResume() = Unit
    open fun onPause() = Unit
    open fun onStop() = Unit
    open fun onDestroy() = Unit
    open fun onNewIntent(intent: Intent) = Unit
    open fun onSaveInstanceState(outState: Bundle) = Unit

    /** What the screen shows; the host already applied the app theme. */
    @Composable
    open fun Content() {
        screenContent?.invoke()
    }

    /** Sets what [Content] shows, like the Activity's `setContent`. */
    fun setContent(content: @Composable () -> Unit) {
        screenContent = content
    }

    /** For the screens still built from an XML layout. */
    fun setContentView(layoutResId: Int) =
        setContentView(LayoutInflater.from(activity).inflate(layoutResId, null, false))

    fun setContentView(view: View) {
        contentView = view
        setContent { AndroidView(factory = { view }, modifier = Modifier.fillMaxSize()) }
    }

    fun <T : View> findViewById(id: Int): T =
        checkNotNull(contentView) { "setContentView first" }.findViewById(id)

    /** Leaves the back stack, like finishing the Activity. */
    fun finish() {
        if (finishing || !::navigator.isInitialized) return
        finishing = true
        navigator.finish(entry)
    }

    /**
     * Keeps the screen on while [active] says so and this screen is in front. The window is
     * shared now, so the flag is dropped when another screen covers this one.
     */
    fun keepScreenOnWhile(active: Flow<Boolean>) {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                try {
                    active.collect { on ->
                        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                } finally {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
        }
    }

    fun runOnUiThread(action: Runnable) = activity.runOnUiThread(action)

    fun shouldShowRequestPermissionRationale(permission: String): Boolean =
        activity.shouldShowRequestPermissionRationale(permission)

    /**
     * Same contract as the Activity method, and safe from a property initializer: the launcher
     * registers with the host's registry once the screen is attached, under a key that survives
     * rotation and process death, so a result that arrives meanwhile is still delivered.
     */
    fun <I, O> registerForActivityResult(
        contract: ActivityResultContract<I, O>,
        callback: ActivityResultCallback<O>,
    ): ActivityResultLauncher<I> = ScreenResultLauncher(contract, callback, launchers.size).also { launcher ->
        launchers += launcher
        if (attached) launcher.register()
    }

    internal fun attach(host: AppCompatActivity, navigator: AppNavigator, entry: NavEntry) {
        attachBaseContext(host)
        activity = host
        this.navigator = navigator
        this.entry = entry
        pendingRestore = entry.savedState
        attached = true
        launchers.forEach { it.register() }
    }

    internal fun saveState(): Bundle = Bundle().also(::onSaveInstanceState)

    internal fun moveTo(target: Lifecycle.State) {
        while (registry.currentState < target && registry.currentState != Lifecycle.State.DESTROYED) stepUp()
        while (registry.currentState > target) stepDown()
    }

    internal val currentState: Lifecycle.State get() = registry.currentState

    private fun stepUp() {
        when (registry.currentState) {
            Lifecycle.State.INITIALIZED -> {
                onCreate(entry.savedState)
                registry.currentState = Lifecycle.State.CREATED
            }
            Lifecycle.State.CREATED -> {
                onStart()
                registry.currentState = Lifecycle.State.STARTED
                pendingRestore?.let {
                    pendingRestore = null
                    onRestoreInstanceState(it)
                }
            }
            Lifecycle.State.STARTED -> {
                onResume()
                registry.currentState = Lifecycle.State.RESUMED
                onPostResume()
            }
            else -> Unit
        }
    }

    private fun stepDown() {
        when (registry.currentState) {
            Lifecycle.State.RESUMED -> {
                registry.currentState = Lifecycle.State.STARTED
                onPause()
            }
            Lifecycle.State.STARTED -> {
                registry.currentState = Lifecycle.State.CREATED
                onStop()
            }
            Lifecycle.State.CREATED -> {
                registry.currentState = Lifecycle.State.DESTROYED
                onDestroy()
                launchers.forEach { it.unregister() }
            }
            Lifecycle.State.INITIALIZED -> {
                // Never created: nothing to tear down, but the screen is done.
                registry.currentState = Lifecycle.State.CREATED
                registry.currentState = Lifecycle.State.DESTROYED
            }
            else -> Unit
        }
    }

    private inner class ScreenResultLauncher<I, O>(
        override val contract: ActivityResultContract<I, O>,
        private val callback: ActivityResultCallback<O>,
        private val index: Int,
    ) : ActivityResultLauncher<I>() {
        private var registered: ActivityResultLauncher<I>? = null

        fun register() {
            if (registered == null) {
                registered = activity.activityResultRegistry.register("nav:${entry.id}:$index", contract, callback)
            }
        }

        override fun launch(input: I, options: ActivityOptionsCompat?) {
            checkNotNull(registered) { "Launch the result request after the screen is created" }.launch(input, options)
        }

        override fun unregister() {
            registered?.unregister()
            registered = null
        }
    }
}

/** `by viewModels { factory }` for a [NavScreen], stored with its back-stack entry. */
inline fun <reified VM : ViewModel> NavScreen.viewModels(
    noinline factoryProducer: (() -> ViewModelProvider.Factory)? = null,
): Lazy<VM> = ViewModelLazy(
    VM::class,
    { viewModelStore },
    factoryProducer ?: { ViewModelProvider.NewInstanceFactory() },
)

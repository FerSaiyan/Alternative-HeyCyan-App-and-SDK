package com.fersaiyan.cyanbridge.ui.nav

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import java.util.UUID

/** One back-stack entry: a route, the intent that opened it and its saved state. */
class NavEntry internal constructor(
    val id: String,
    val route: AppRoute,
    intent: Intent,
    internal var savedState: Bundle?,
    private val stores: NavViewModelStores,
) {
    var intent: Intent = intent
        internal set
    internal var screen: NavScreen? by mutableStateOf(null)
    internal val viewModelStore: ViewModelStore get() = stores.store(id)
}

/** ViewModel stores of the back-stack entries; retained across rotation like an Activity's. */
class NavViewModelStores : ViewModel() {
    private val stores = mutableMapOf<String, ViewModelStore>()

    fun store(id: String): ViewModelStore = stores.getOrPut(id) { ViewModelStore() }

    fun clear(id: String) {
        stores.remove(id)?.clear()
    }

    override fun onCleared() {
        stores.values.forEach(ViewModelStore::clear)
        stores.clear()
    }
}

/** The glasses screen under the back stack; the host owns what its lifecycle does. */
interface NavRoot {
    fun moveTo(state: Lifecycle.State)
}

/**
 * The back stack of the single Activity.
 *
 * [open] keeps the Activity-start rules the screens were written for, so every former
 * `startActivity(Intent(this, XActivity::class.java))` behaves as before:
 * - a plain start pushes the route;
 * - `FLAG_ACTIVITY_CLEAR_TOP` returns to the newest entry of that route, dropping what is
 *   above it, and with `FLAG_ACTIVITY_SINGLE_TOP` (or a single-task route) delivers the intent
 *   to it through `onNewIntent`; without it the entry is replaced;
 * - `FLAG_ACTIVITY_SINGLE_TOP` alone delivers to the top entry when it shows that route;
 * - `FLAG_ACTIVITY_CLEAR_TASK` clears the whole stack first;
 * - an intent without a route opens the glasses screen (the root).
 */
class AppNavigator(
    private val activity: AppCompatActivity,
    private val stores: NavViewModelStores,
    private val root: NavRoot,
    private val onTopChanged: (NavEntry?) -> Unit,
) {
    val entries = mutableStateListOf<NavEntry>()

    /** False while the stack replaces the glasses screen (onboarding): finishing its last screen closes the app. */
    var hasRoot by mutableStateOf(true)
        private set

    /** Entries that left the stack; the Compose host drops their saved UI state. */
    internal val removedIds = mutableStateListOf<String>()

    private var hostState = Lifecycle.State.INITIALIZED

    val top: NavEntry? get() = entries.lastOrNull()
    val topScreen: NavScreen? get() = top?.screen
    val isRootVisible: Boolean get() = entries.isEmpty()

    /** Back drops a screen, except the last onboarding screen: the system handles that one. */
    val canGoBack: Boolean get() = entries.size > if (hasRoot) 0 else 1

    init {
        activity.lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_DESTROY -> onHostDestroyed()
                    else -> setHostState(event.targetState)
                }
            },
        )
    }

    /** Called from the host's onCreate, before it opens anything, so new screens are created at once. */
    fun onHostCreated() = setHostState(Lifecycle.State.CREATED)

    /** Starts the stack without the glasses screen under it (onboarding owns the task). */
    fun startWithoutRoot(intent: Intent) = mutate {
        hasRoot = false
        push(AppRoute.from(intent) ?: return@mutate, intent)
    }

    /** Applies an intent for this Activity. Returns false when it has no route (the caller shows the root). */
    fun open(intent: Intent): Boolean {
        val route = AppRoute.from(intent)
        val flags = intent.flags
        mutate {
            if (flags and Intent.FLAG_ACTIVITY_CLEAR_TASK != 0 || route == null) {
                removeAbove(-1)
                hasRoot = true
            }
            if (route == null) return@mutate
            val clearTop = flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0 || route.singleTask
            val singleTop = flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0 || route.singleTask
            val existing = if (clearTop) entries.indexOfLast { it.route == route } else -1
            when {
                existing >= 0 -> {
                    removeAbove(existing)
                    if (singleTop) {
                        deliverNewIntent(entries[existing], intent)
                    } else {
                        removeAbove(existing - 1)
                        push(route, intent)
                    }
                }
                singleTop && top?.route == route -> deliverNewIntent(top!!, intent)
                else -> push(route, intent)
            }
        }
        return route != null
    }

    /** Back to the glasses screen. */
    fun popToRoot() = mutate {
        removeAbove(-1)
        hasRoot = true
    }

    /** Back: drops the top screen. Returns false when the Activity should handle it. */
    fun pop(): Boolean {
        val entry = top ?: return false
        finish(entry)
        return true
    }

    internal fun finish(entry: NavEntry) {
        if (entry !in entries) return
        if (entries.size == 1 && !hasRoot) {
            // The last onboarding screen: the Activity was the whole task, as the screen used to be.
            activity.finish()
            return
        }
        mutate { entries.remove(entry) }
    }

    fun save(outState: Bundle) {
        val saved = ArrayList<Bundle>(entries.size)
        entries.forEach { entry ->
            entry.screen?.takeUnless { it.isDestroyed }?.let { entry.savedState = it.saveState() }
            saved += Bundle().apply {
                putString(KEY_ID, entry.id)
                putString(KEY_ROUTE, entry.route.name)
                putParcelable(KEY_INTENT, entry.intent)
                putBundle(KEY_STATE, entry.savedState)
            }
        }
        outState.putBundle(
            KEY_NAV,
            Bundle().apply {
                putParcelableArrayList(KEY_ENTRIES, saved)
                putBoolean(KEY_HAS_ROOT, hasRoot)
            },
        )
    }

    /** Rebuilds the stack after a rotation or process death. Only the top screen is created now. */
    fun restore(savedInstanceState: Bundle?): Boolean {
        val nav = savedInstanceState?.getBundle(KEY_NAV) ?: return false
        val saved = nav.parcelableList<Bundle>(KEY_ENTRIES).orEmpty()
        mutate {
            hasRoot = nav.getBoolean(KEY_HAS_ROOT, true)
            saved.forEach { bundle ->
                val route = bundle.getString(KEY_ROUTE)?.let { name -> AppRoute.entries.firstOrNull { it.name == name } }
                    ?: return@forEach
                val intent = bundle.parcelable<Intent>(KEY_INTENT) ?: route.intent(activity)
                entries += NavEntry(bundle.getString(KEY_ID) ?: newId(), route, intent, bundle.getBundle(KEY_STATE), stores)
            }
        }
        return true
    }

    /** All changes go through here, so the old top pauses before the new one shows, then stops. */
    private fun mutate(change: () -> Unit) {
        val before = entries.toList()
        val oldTop = top
        change()
        val newTop = top
        val removed = before.filter { it !in entries }
        if (oldTop !== newTop) {
            if (oldTop == null) root.moveTo(minOf(hostState, Lifecycle.State.STARTED)) else oldTop.screen?.moveTo(minOf(hostState, Lifecycle.State.STARTED))
            if (newTop == null) root.moveTo(hostState) else screenOf(newTop).moveTo(hostState)
            if (oldTop == null) root.moveTo(minOf(hostState, Lifecycle.State.CREATED))
            else if (oldTop in entries) oldTop.screen?.moveTo(minOf(hostState, Lifecycle.State.CREATED))
        }
        removed.forEach(::discard)
        // `top`, not `newTop`: a screen may have opened another from its onCreate meanwhile.
        if (oldTop !== newTop || removed.isNotEmpty()) onTopChanged(top)
    }

    private fun push(route: AppRoute, intent: Intent) {
        entries += NavEntry(newId(), route, intent, null, stores)
    }

    private fun removeAbove(index: Int) {
        while (entries.size > index + 1) entries.removeAt(entries.lastIndex)
    }

    private fun deliverNewIntent(entry: NavEntry, intent: Intent) {
        val screen = entry.screen
        if (screen == null || screen.isDestroyed) {
            entry.intent = intent
            return
        }
        // Like the Activity: paused around onNewIntent when it is resumed.
        val resumed = screen.currentState == Lifecycle.State.RESUMED
        if (resumed) screen.moveTo(Lifecycle.State.STARTED)
        screen.onNewIntent(intent)
        if (resumed) screen.moveTo(Lifecycle.State.RESUMED)
    }

    private fun screenOf(entry: NavEntry): NavScreen {
        entry.screen?.takeUnless { it.isDestroyed }?.let { return it }
        return entry.route.createScreen().also { screen ->
            screen.attach(activity, this, entry)
            entry.screen = screen
        }
    }

    private fun discard(entry: NavEntry) {
        entry.screen?.moveTo(Lifecycle.State.DESTROYED)
        entry.screen = null
        stores.clear(entry.id)
        removedIds += entry.id
    }

    private fun setHostState(state: Lifecycle.State) {
        if (state == Lifecycle.State.DESTROYED) return
        hostState = state
        val current = top
        if (current == null) root.moveTo(state) else screenOf(current).moveTo(state)
    }

    /** The Activity is going away (rotation or finish): screens end with it, their entries stay saved. */
    private fun onHostDestroyed() {
        hostState = Lifecycle.State.DESTROYED
        entries.forEach { entry ->
            entry.screen?.moveTo(Lifecycle.State.DESTROYED)
            entry.screen = null
        }
    }

    private fun newId(): String = UUID.randomUUID().toString()

    private companion object {
        const val KEY_NAV = "cyanbridge:nav"
        const val KEY_ENTRIES = "entries"
        const val KEY_HAS_ROOT = "has_root"
        const val KEY_ID = "id"
        const val KEY_ROUTE = "route"
        const val KEY_INTENT = "intent"
        const val KEY_STATE = "state"
    }
}

@Suppress("DEPRECATION")
private inline fun <reified T : android.os.Parcelable> Bundle.parcelable(key: String): T? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) getParcelable(key, T::class.java) else getParcelable(key)

@Suppress("DEPRECATION")
private inline fun <reified T : android.os.Parcelable> Bundle.parcelableList(key: String): List<T>? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) getParcelableArrayList(key, T::class.java) else getParcelableArrayList(key)

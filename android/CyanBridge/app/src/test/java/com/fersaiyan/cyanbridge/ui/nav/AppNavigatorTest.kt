package com.fersaiyan.cyanbridge.ui.nav

import android.content.ContentProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import com.fersaiyan.cyanbridge.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController

/** The back stack keeps the Activity start rules and lifecycles the screens were written for. */
@RunWith(RobolectricTestRunner::class)
class AppNavigatorTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun startComposeResources() {
        // Robolectric starts no library content providers; shared screens read their strings through this one.
        @Suppress("UNCHECKED_CAST")
        val provider = Class.forName("org.jetbrains.compose.resources.AndroidContextProvider") as Class<ContentProvider>
        Robolectric.setupContentProvider(provider)
    }

    // Resumed without attaching the window: shared Compose resources cannot load under Robolectric.
    private fun launch(route: AppRoute, saved: Bundle? = null): ActivityController<MainActivity> =
        Robolectric.buildActivity(MainActivity::class.java, route.intent(context))
            .create(saved).start().apply { if (saved != null) restoreInstanceState(saved) }.postCreate(saved).resume()

    private val ActivityController<MainActivity>.navigator get() = get().navigator

    private fun ActivityController<MainActivity>.routes() = navigator.entries.map { it.route }

    @Test
    fun startingAScreenStopsTheOneBelowAndBackDestroysIt() {
        val controller = launch(AppRoute.APPEARANCE)
        val first = controller.navigator.topScreen!!
        assertEquals(Lifecycle.State.RESUMED, first.lifecycle.currentState)

        controller.get().startActivity(AppRoute.PUBLISH_PLUGIN.intent(controller.get()))
        val second = controller.navigator.topScreen!!
        assertEquals(listOf(AppRoute.APPEARANCE, AppRoute.PUBLISH_PLUGIN), controller.routes())
        assertEquals(Lifecycle.State.RESUMED, second.lifecycle.currentState)
        assertEquals(Lifecycle.State.CREATED, first.lifecycle.currentState)

        controller.get().onBackPressedDispatcher.onBackPressed()
        assertEquals(listOf(AppRoute.APPEARANCE), controller.routes())
        assertEquals(Lifecycle.State.DESTROYED, second.lifecycle.currentState)
        assertSame(first, controller.navigator.topScreen)
        assertEquals(Lifecycle.State.RESUMED, first.lifecycle.currentState)
    }

    @Test
    fun finishRemovesTheScreenFromAnywhereInTheStack() {
        val controller = launch(AppRoute.APPEARANCE)
        controller.get().startActivity(AppRoute.PUBLISH_PLUGIN.intent(controller.get()))
        val below = controller.navigator.entries.first().screen!!

        below.finish()

        assertEquals(listOf(AppRoute.PUBLISH_PLUGIN), controller.routes())
        assertEquals(Lifecycle.State.DESTROYED, below.lifecycle.currentState)
    }

    @Test
    fun clearTopWithSingleTopReturnsToTheSameScreenWithTheNewIntent() {
        val controller = launch(AppRoute.APPEARANCE)
        val activity = controller.get()
        val first = controller.navigator.topScreen!!
        val firstStore = first.viewModelStore
        activity.startActivity(AppRoute.PUBLISH_PLUGIN.intent(activity))
        val removedStore = controller.navigator.topScreen!!.viewModelStore
        activity.startActivity(AppRoute.DAILY_FACTS.intent(activity))

        activity.startActivity(
            AppRoute.APPEARANCE.intent(activity)
                .putExtra("marker", 7)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )

        assertEquals(listOf(AppRoute.APPEARANCE), controller.routes())
        assertSame(first, controller.navigator.topScreen)
        assertSame(firstStore, first.viewModelStore)
        assertNotSame(removedStore, controller.navigator.entries.single().viewModelStore)
        assertEquals(Lifecycle.State.RESUMED, first.lifecycle.currentState)
    }

    @Test
    fun clearTopWithoutSingleTopReplacesTheScreen() {
        val controller = launch(AppRoute.APPEARANCE)
        val activity = controller.get()
        val first = controller.navigator.topScreen!!
        activity.startActivity(AppRoute.PUBLISH_PLUGIN.intent(activity))

        activity.startActivity(AppRoute.APPEARANCE.intent(activity).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))

        assertEquals(listOf(AppRoute.APPEARANCE), controller.routes())
        assertNotSame(first, controller.navigator.topScreen)
        assertEquals(Lifecycle.State.DESTROYED, first.lifecycle.currentState)
    }

    @Test
    fun singleTopDeliversToTheTopScreenOnly() {
        val controller = launch(AppRoute.APPEARANCE)
        val activity = controller.get()
        val top = controller.navigator.topScreen!!

        activity.startActivity(AppRoute.APPEARANCE.intent(activity).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP))
        assertEquals(1, controller.navigator.entries.size)
        assertSame(top, controller.navigator.topScreen)

        activity.startActivity(AppRoute.APPEARANCE.intent(activity))
        assertEquals(2, controller.navigator.entries.size)
    }

    @Test
    fun rotationKeepsTheStackAndTheViewModels() {
        val controller = launch(AppRoute.APPEARANCE)
        controller.get().startActivity(AppRoute.PUBLISH_PLUGIN.intent(controller.get()))
        val oldTop = controller.navigator.topScreen!!
        val store = oldTop.viewModelStore

        // A rotation: the retained ViewModels move to the new Activity with the saved state.
        val saved = Bundle()
        controller.saveInstanceState(saved)
        controller.recreate()

        assertEquals(listOf(AppRoute.APPEARANCE, AppRoute.PUBLISH_PLUGIN), controller.routes())
        val newTop = controller.navigator.topScreen!!
        assertNotSame(oldTop, newTop)
        assertEquals(Lifecycle.State.DESTROYED, oldTop.lifecycle.currentState)
        assertSame(store, newTop.viewModelStore)
        assertEquals(Lifecycle.State.RESUMED, newTop.lifecycle.currentState)
        // Screens under the top are recreated only when they are shown again, like Activities.
        assertEquals(null, controller.navigator.entries.first().screen)
    }

    @Test
    fun processDeathRestoresTheStackFromSavedState() {
        val controller = launch(AppRoute.APPEARANCE)
        controller.get().startActivity(AppRoute.PUBLISH_PLUGIN.intent(controller.get()))
        val saved = Bundle()
        controller.saveInstanceState(saved)

        val restored = launch(AppRoute.DAILY_FACTS, saved)

        assertEquals(listOf(AppRoute.APPEARANCE, AppRoute.PUBLISH_PLUGIN), restored.routes())
        assertTrue(restored.navigator.top!!.route == AppRoute.PUBLISH_PLUGIN)
    }

    @Test
    fun launcherOpensOnboardingAloneUntilSetupIsDone() {
        context.getSharedPreferences("cyanbridge_prefs", Context.MODE_PRIVATE).edit().putBoolean("onboarding_completed", false).commit()
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .setClassName(context, "com.fersaiyan.cyanbridge.ui.WelcomeActivity")
        val controller = Robolectric.buildActivity(MainActivity::class.java, launcher)
            .create().start().postCreate(null).resume()

        assertEquals(listOf(AppRoute.WELCOME), controller.routes())
        assertEquals(false, controller.navigator.hasRoot)
        // Back on the only onboarding screen is the system's (it leaves the app), not a pop to the glasses screen.
        assertEquals(false, controller.navigator.canGoBack)

        controller.navigator.topScreen!!.finish()
        assertTrue(controller.get().isFinishing)
    }

    @Test
    fun onboardingStepsReplaceEachOtherAndTheEndShowsTheGlassesScreen() {
        context.getSharedPreferences("cyanbridge_prefs", Context.MODE_PRIVATE).edit().putBoolean("onboarding_completed", false).commit()
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .setClassName(context, "com.fersaiyan.cyanbridge.ui.WelcomeActivity")
        val controller = Robolectric.buildActivity(MainActivity::class.java, launcher).create()
        val welcome = controller.navigator.topScreen!!

        welcome.startActivity(AppRoute.BATTERY_GUIDE.intent(welcome))
        welcome.finish()
        assertEquals(listOf(AppRoute.BATTERY_GUIDE), controller.routes())
        assertEquals(false, controller.get().isFinishing)

        val guide = controller.navigator.topScreen!!
        guide.startActivity(Intent(guide, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        assertTrue(controller.navigator.isRootVisible)
        assertEquals(true, controller.navigator.hasRoot)
    }
}

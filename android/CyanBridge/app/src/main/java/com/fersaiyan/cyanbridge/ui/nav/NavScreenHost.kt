package com.fersaiyan.cyanbridge.ui.nav

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner

/**
 * Shows the top back-stack screen full-window, as its own Activity window did: the screen draws
 * its own bars and insets. Its `rememberSaveable` state is kept while other screens cover it,
 * and the pane title tells screen readers which screen opened, as a new window did.
 */
@Composable
fun NavScreenHost(navigator: AppNavigator) {
    val holder = rememberSaveableStateHolder()
    val removed = navigator.removedIds
    LaunchedEffect(removed.size) {
        if (removed.isEmpty()) return@LaunchedEffect
        val ids = removed.toList()
        removed.removeAll(ids)
        ids.forEach(holder::removeState)
    }

    val entry = navigator.top ?: return
    val screen = entry.screen ?: return
    val title = entry.route.label(LocalContext.current)
    key(entry.id) {
        holder.SaveableStateProvider(entry.id) {
            CompositionLocalProvider(
                LocalLifecycleOwner provides screen,
                LocalViewModelStoreOwner provides screen,
            ) {
                Box(modifier = Modifier.fillMaxSize().semantics { paneTitle = title }) {
                    screen.Content()
                }
            }
        }
    }
}

package com.fersaiyan.cyanbridge.shared.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory

/**
 * A screen model owned by the composition: created for [key] and cleared when it leaves.
 * Hosts without an Activity (the iOS shell) use this for their destinations.
 */
@Composable
inline fun <reified VM : ViewModel> rememberScreenViewModel(key: Any?, noinline create: () -> VM): VM {
    val store = remember(key) { ViewModelStore() }
    DisposableEffect(store) { onDispose { store.clear() } }
    return remember(store) {
        ViewModelProvider.create(store, viewModelFactory { initializer { create() } })[VM::class]
    }
}

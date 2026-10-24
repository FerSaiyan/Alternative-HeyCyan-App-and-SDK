package com.fersaiyan.cyanbridge.ui

import android.app.Activity
import android.content.DialogInterface
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.fersaiyan.cyanbridge.shared.viewmodel.CloseScreen
import com.fersaiyan.cyanbridge.shared.viewmodel.DialogButton
import com.fersaiyan.cyanbridge.shared.viewmodel.ScreenDialog
import com.fersaiyan.cyanbridge.shared.viewmodel.ScreenEffect
import com.fersaiyan.cyanbridge.shared.viewmodel.ScreenToast
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/** A factory for one shared ViewModel class. */
inline fun <reified VM : ViewModel> sharedViewModelFactory(crossinline create: () -> VM): ViewModelProvider.Factory =
    viewModelFactory { initializer { create() } }

/**
 * Shows a shared screen ViewModel's effects while this Activity exists. Toasts, dialogs and close
 * requests are handled here; [handle] gets the screen's own effects.
 */
fun ComponentActivity.collectScreenEffects(effects: Flow<ScreenEffect>, handle: (ScreenEffect) -> Unit = {}) {
    lifecycleScope.launch {
        effects.collect { effect ->
            when (effect) {
                is ScreenToast -> Toast.makeText(this@collectScreenEffects, effect.text, if (effect.long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
                is ScreenDialog -> showScreenDialog(effect)
                CloseScreen -> finish()
                else -> handle(effect)
            }
        }
    }
}

fun Activity.showScreenDialog(dialog: ScreenDialog) {
    AlertDialog.Builder(this).apply {
        setTitle(dialog.title)
        dialog.message?.let { setMessage(it) }
        if (dialog.items.isNotEmpty()) setItems(dialog.items.toTypedArray()) { _, which -> dialog.onItem(which) }
        dialog.positive?.let { button -> setPositiveButton(button.label, button.listener()) }
        dialog.negative?.let { button -> setNegativeButton(button.label, button.listener()) }
        dialog.neutral?.let { button -> setNeutralButton(button.label, button.listener()) }
    }.show()
}

private fun DialogButton.listener() = onClick?.let { click -> DialogInterface.OnClickListener { _, _ -> click() } }

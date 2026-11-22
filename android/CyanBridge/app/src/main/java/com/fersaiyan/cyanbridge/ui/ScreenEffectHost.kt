package com.fersaiyan.cyanbridge.ui

import android.app.Activity
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.fersaiyan.cyanbridge.shared.viewmodel.CloseScreen
import com.fersaiyan.cyanbridge.shared.viewmodel.DialogButton
import com.fersaiyan.cyanbridge.shared.viewmodel.ScreenDialog
import com.fersaiyan.cyanbridge.shared.viewmodel.ScreenEffect
import com.fersaiyan.cyanbridge.shared.viewmodel.ShowMessage
import com.fersaiyan.cyanbridge.ui.dialog.ExpressiveAlert
import com.fersaiyan.cyanbridge.ui.dialog.ExpressiveDialogs
import com.fersaiyan.cyanbridge.ui.nav.NavScreen
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Shows a shared screen ViewModel's effects while this Activity exists. Toasts, dialogs and close
 * requests are handled here; [handle] gets the screen's own effects.
 */
fun ComponentActivity.collectScreenEffects(effects: Flow<ScreenEffect>, handle: (ScreenEffect) -> Unit = {}) {
    lifecycleScope.launch {
        effects.collect { effect ->
            when (effect) {
                is ShowMessage -> showToast(effect.message)
                is ScreenDialog -> showScreenDialog(effect)
                CloseScreen -> finish()
                else -> handle(effect)
            }
        }
    }
}

/** The same for a screen of the single Activity; it lasts while the screen is in the back stack. */
fun NavScreen.collectScreenEffects(effects: Flow<ScreenEffect>, handle: (ScreenEffect) -> Unit = {}) {
    lifecycleScope.launch {
        effects.collect { effect ->
            when (effect) {
                is ShowMessage -> showToast(effect.message)
                is ScreenDialog -> activity.showScreenDialog(effect)
                CloseScreen -> finish()
                else -> handle(effect)
            }
        }
    }
}

fun Activity.showScreenDialog(dialog: ScreenDialog) {
    // As with the View builder, a message replaces the item list.
    val items = if (dialog.message == null) dialog.items else emptyList()
    ExpressiveDialogs.show(this) { close ->
        ExpressiveAlert(
            title = dialog.title,
            message = dialog.message,
            confirm = dialog.positive?.action(),
            dismiss = dialog.negative?.action(),
            neutral = dialog.neutral?.action(),
            cancelable = true,
            close = close,
            body = if (items.isEmpty()) null else {
                {
                    Column {
                        items.forEachIndexed { which, item ->
                            Text(
                                text = item,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .clickable(role = Role.Button) {
                                        close()
                                        dialog.onItem(which)
                                    }
                                    .padding(vertical = 12.dp),
                            )
                        }
                    }
                }
            },
        )
    }
}

private fun DialogButton.action() = ExpressiveDialogs.Action(label) { onClick?.invoke() }

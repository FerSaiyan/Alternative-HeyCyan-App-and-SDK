package com.fersaiyan.cyanbridge.ui.dialog

import android.app.Activity
import android.view.ViewGroup
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.fersaiyan.cyanbridge.ui.appearance.AppearancePreferences
import com.fersaiyan.cyanbridge.ui.appearance.rememberAppearanceSettings
import com.fersaiyan.cyanbridge.ui.theme.CyanBridgeTheme

/**
 * Material 3 Expressive dialogs for Activity and coordinator code.
 *
 * App rule (android/AGENTS.md): every popup is a Compose Material 3 dialog in the app theme; no
 * android.app / AppCompat / MaterialComponents AlertDialog (ArchitectureGuardrailSourceTest enforces it).
 * The dialog is composed in a ComposeView attached to the activity's content view and themed with
 * [CyanBridgeTheme], so it follows the user's appearance settings, the expressive shapes and the motion scheme.
 */
object ExpressiveDialogs {
    /** Closes a shown dialog (no-op once closed). */
    class Handle internal constructor(private val close: () -> Unit) {
        fun dismiss() = close()
    }

    /**
     * A dialog button. [onClick] runs when it is tapped; the dialog closes first unless [dismissOnClick] is false
     * (for validation: the action then calls [Handle.dismiss] itself).
     */
    data class Action(
        val label: String,
        val dismissOnClick: Boolean = true,
        val onClick: () -> Unit = {},
    )

    /** Low level: [content] draws the dialog and receives the function that closes it. */
    fun show(
        activity: Activity,
        onDismiss: (() -> Unit)? = null,
        content: @Composable (dismiss: () -> Unit) -> Unit,
    ): Handle? {
        if (activity.isFinishing || activity.isDestroyed) return null
        val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return null
        val appearancePreferences = AppearancePreferences(activity)
        val view = ComposeView(activity)
        var closed = false
        val close: () -> Unit = {
            if (!closed) {
                closed = true
                // Removing the host disposes the composition, which closes the dialog window.
                root.post { root.removeView(view) }
                onDismiss?.invoke()
            }
        }
        view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        view.setContent {
            val appearance by rememberAppearanceSettings(appearancePreferences)
            CyanBridgeTheme(appearance) { content(close) }
        }
        root.addView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return Handle(close)
    }

    /** Title and message (or a custom [body]) with up to three buttons; replaces the View builder's setMessage. */
    fun alert(
        activity: Activity,
        title: String?,
        message: CharSequence? = null,
        confirm: Action? = null,
        dismiss: Action? = null,
        neutral: Action? = null,
        cancelable: Boolean = true,
        onDismiss: (() -> Unit)? = null,
        body: (@Composable () -> Unit)? = null,
    ): Handle? = show(activity, onDismiss) { close ->
        ExpressiveAlert(
            title = title,
            message = message?.toString(),
            confirm = confirm,
            dismiss = dismiss,
            neutral = neutral,
            cancelable = cancelable,
            close = close,
            body = body,
        )
    }

    /** Tap one entry to choose it and close the dialog; replaces the View builder's setItems. */
    fun list(
        activity: Activity,
        title: String?,
        items: List<String>,
        onSelect: (Int) -> Unit,
        dismiss: Action? = Action("Cancel"),
        neutral: Action? = null,
        message: CharSequence? = null,
        onDismiss: (() -> Unit)? = null,
    ): Handle? = show(activity, onDismiss) { close ->
        ExpressiveAlert(
            title = title,
            message = null,
            confirm = null,
            dismiss = dismiss,
            neutral = neutral,
            cancelable = true,
            close = close,
            body = {
                Column {
                    message?.let {
                        Text(it.toString(), modifier = Modifier.padding(bottom = 8.dp))
                    }
                    items.forEachIndexed { index, item ->
                        Text(
                            text = item,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .clickable(role = Role.Button) {
                                    close()
                                    onSelect(index)
                                }
                                .padding(vertical = 12.dp),
                        )
                    }
                }
            },
        )
    }

    /**
     * Radio-button choice; replaces the View builder's setSingleChoiceItems. With [confirm] the choice is applied
     * on that button ([onChosen] gets the selected index); without it, tapping an entry chooses and closes.
     */
    fun singleChoice(
        activity: Activity,
        title: String?,
        items: List<String>,
        initialIndex: Int,
        onChosen: (Int) -> Unit,
        confirm: String? = null,
        dismiss: Action? = Action("Cancel"),
        onDismiss: (() -> Unit)? = null,
    ): Handle? = show(activity, onDismiss) { close ->
        var selected by remember { mutableIntStateOf(initialIndex) }
        ExpressiveAlert(
            title = title,
            message = null,
            confirm = confirm?.let { Action(it) { onChosen(selected) } },
            dismiss = dismiss,
            neutral = null,
            cancelable = true,
            close = close,
            body = {
                SingleChoiceList(items, selected) { index ->
                    selected = index
                    if (confirm == null) {
                        close()
                        onChosen(index)
                    }
                }
            },
        )
    }

    /**
     * One text field; replaces the View builder's setView(EditText). [onConfirm] gets the text and returns an
     * error to show (the dialog stays open) or null to close.
     */
    fun textInput(
        activity: Activity,
        title: String?,
        confirmLabel: String,
        onConfirm: (String) -> String?,
        message: CharSequence? = null,
        initialText: String = "",
        label: String? = null,
        singleLine: Boolean = true,
        keyboardType: KeyboardType = KeyboardType.Text,
        dismiss: Action? = Action("Cancel"),
        neutral: Action? = null,
        onDismiss: (() -> Unit)? = null,
    ): Handle? = show(activity, onDismiss) { close ->
        var text by remember { mutableStateOf(initialText) }
        var error by remember { mutableStateOf<String?>(null) }
        ExpressiveAlert(
            title = title,
            message = null,
            confirm = Action(confirmLabel, dismissOnClick = false) {
                val problem = onConfirm(text)
                if (problem == null) close() else error = problem
            },
            dismiss = dismiss,
            neutral = neutral,
            cancelable = true,
            close = close,
            body = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    message?.let { Text(it.toString()) }
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it; error = null },
                        label = label?.let { { Text(it) } },
                        singleLine = singleLine,
                        minLines = if (singleLine) 1 else 3,
                        isError = error != null,
                        supportingText = error?.let { { Text(it) } },
                        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
        )
    }
}

/** The Material 3 alert used by [ExpressiveDialogs]; also usable directly inside a [ExpressiveDialogs.show] block. */
@Composable
fun ExpressiveAlert(
    title: String?,
    message: String?,
    confirm: ExpressiveDialogs.Action?,
    dismiss: ExpressiveDialogs.Action?,
    neutral: ExpressiveDialogs.Action?,
    cancelable: Boolean,
    close: () -> Unit,
    body: (@Composable () -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = { if (cancelable) close() },
        properties = DialogProperties(dismissOnBackPress = cancelable, dismissOnClickOutside = cancelable),
        title = title?.let { { Text(it) } },
        text = if (message == null && body == null) null else {
            {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    message?.let { Text(it) }
                    body?.invoke()
                }
            }
        },
        confirmButton = { confirm?.let { DialogButton(it, close) } },
        dismissButton = if (dismiss == null && neutral == null) null else {
            {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    neutral?.let { DialogButton(it, close) }
                    dismiss?.let { DialogButton(it, close) }
                }
            }
        },
    )
}

@Composable
private fun DialogButton(action: ExpressiveDialogs.Action, close: () -> Unit) {
    TextButton(
        onClick = {
            if (action.dismissOnClick) close()
            action.onClick()
        },
        modifier = Modifier.heightIn(min = 48.dp),
        shapes = ButtonDefaults.shapes(),
    ) {
        Text(action.label)
    }
}

/** Radio list used by [ExpressiveDialogs.singleChoice] and by dialogs that embed a choice in a larger body. */
@Composable
fun SingleChoiceList(items: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Column {
        items.forEachIndexed { index, item ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .selectable(selected = index == selected, role = Role.RadioButton) { onSelect(index) },
            ) {
                RadioButton(selected = index == selected, onClick = null)
                Text(item, modifier = Modifier.padding(start = 12.dp))
            }
        }
    }
}

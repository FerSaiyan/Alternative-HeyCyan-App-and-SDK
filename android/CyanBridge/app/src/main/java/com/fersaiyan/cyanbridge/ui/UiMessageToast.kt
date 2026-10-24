package com.fersaiyan.cyanbridge.ui

import android.content.Context
import android.widget.Toast
import com.fersaiyan.cyanbridge.shared.ui.UiMessage

/** Shows a screen model's notice as a toast. */
fun Context.showToast(message: UiMessage) {
    Toast.makeText(this, message.text, if (message.long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
}

package com.fersaiyan.cyanbridge.shared.viewmodel

import com.fersaiyan.cyanbridge.shared.ui.UiMessage
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * A one-off request from a screen ViewModel to whatever hosts the screen (an Activity today, a
 * shared navigation destination later). Each screen adds its own effects next to these.
 */
interface ScreenEffect

/** A short notice (a toast on Android). */
data class ShowMessage(val message: UiMessage) : ScreenEffect

/** A plain alert: a message or a list of [items], with up to three buttons. */
data class ScreenDialog(
    val title: String,
    val message: String? = null,
    val positive: DialogButton? = null,
    val negative: DialogButton? = null,
    val neutral: DialogButton? = null,
    val items: List<String> = emptyList(),
    val onItem: (Int) -> Unit = {},
) : ScreenEffect

/** A dialog button; every button also dismisses the dialog. */
data class DialogButton(val label: String, val onClick: (() -> Unit)? = null)

/** The screen asks its host to close it. */
data object CloseScreen : ScreenEffect

/** Effects queued until the host collects them, in order. */
class ScreenEffects {
    private val channel = Channel<ScreenEffect>(Channel.UNLIMITED)
    val flow: Flow<ScreenEffect> = channel.receiveAsFlow()

    fun send(effect: ScreenEffect) {
        channel.trySend(effect)
    }

    fun toast(text: String, long: Boolean = false) = send(ShowMessage(UiMessage(text, long)))
}

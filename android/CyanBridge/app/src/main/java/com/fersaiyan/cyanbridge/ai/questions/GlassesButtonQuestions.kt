package com.fersaiyan.cyanbridge.ai.questions

import android.content.Context
import com.fersaiyan.cyanbridge.devices.DeviceProfileStore
import com.fersaiyan.cyanbridge.shared.devices.DeviceClass

/**
 * Glasses buttons that ask the assistant while the main screen does not exist (the app was closed,
 * the connection kept by the family's service). While the screen exists, the family's screen handler
 * presses the dashboard's question buttons instead, as before.
 */
internal object GlassesButtonQuestions {
    /** [family]'s button asked a spoken question. */
    fun onVoiceButton(context: Context, family: DeviceClass, source: String) {
        withoutScreen(context, family)?.askByVoiceInBackground(source)
    }

    /** [family]'s button asked about what the glasses see; [photo] is the family's photo transport. */
    fun onPhotoButton(context: Context, family: DeviceClass, source: String, photo: () -> QuestionPhotoSource) {
        withoutScreen(context, family)?.askAboutPhotoInBackground(source, photo())
    }

    private fun withoutScreen(context: Context, family: DeviceClass): GlassesQuestions? {
        if (DeviceProfileStore.selectedClass(context) != family) return null
        return GlassesQuestions.get(context).takeIf { it.host == null }
    }
}

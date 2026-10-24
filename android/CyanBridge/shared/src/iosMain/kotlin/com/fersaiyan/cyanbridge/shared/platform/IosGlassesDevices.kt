package com.fersaiyan.cyanbridge.shared.platform

import com.fersaiyan.cyanbridge.shared.devices.GlassesFamilyProfile
import com.fersaiyan.cyanbridge.shared.glasses.GlassesDevice

internal val VIDEO_DURATION_OPTIONS_SECONDS = listOf(15, 30, 60, 180, 540, 720)

/** Dashboard controls the iOS host does not implement. */
internal const val IOS_CONTROL_NOT_IMPLEMENTED = "This control is not implemented in the iOS host yet"

/** Image questions on iOS without a glasses photo source (the message predates the other families). */
internal const val IMAGE_QUESTION_NEEDS_GLASSES =
    "Image question needs HeyCyan glasses connected through the vendor SDK"

/** iOS side of a glasses family: the shared [GlassesDevice] plus what the iOS host reads. */
internal interface IosGlassesDevice : GlassesDevice {
    /** True while the glasses are linked: the BLE link is up, or the vendor SDK reports a connection. */
    val isLinked: Boolean

    /** Shown for an image question when [canTakeAiPhoto] is false. */
    val aiPhotoUnavailableMessage: String get() = IMAGE_QUESTION_NEEDS_GLASSES
}

/**
 * [base] under another family's profile. Like Android, iOS drives unclassified and audio-only
 * glasses through the HeyCyan path.
 */
internal class IosProfiledGlasses(
    override val profile: GlassesFamilyProfile,
    base: IosGlassesDevice,
) : IosGlassesDevice by base

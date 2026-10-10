package com.fersaiyan.cyanbridge.plugins.walkingaid

import com.fersaiyan.cyanbridge.plugins.walkingaid.vision.VisionFrame
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel

/** At most one pending frame, independently owned from the decoder and the active inference. */
internal class WalkingAidFrameQueue {
    val frames = Channel<VisionFrame>(
        capacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
        onUndeliveredElement = { it.bitmap.recycle() },
    )

    fun offer(frame: VisionFrame) {
        if (frames.trySend(frame).isFailure) frame.bitmap.recycle()
    }

    fun clear() {
        while (true) (frames.tryReceive().getOrNull() ?: break).bitmap.recycle()
    }
}

package com.fersaiyan.cyanbridge.shared.media.rtmp

import com.fersaiyan.cyanbridge.shared.media.ts.MpegTsMuxer

/** Transport stream packets for one video frame. */
class TsChunk(val bytes: ByteArray, val keyframe: Boolean, val dtsMs: Long)

/**
 * Turns RTMP video messages (FLV H.264) into MPEG-TS for a local player: keeps the AVC
 * configuration, converts each frame to Annex B, puts SPS/PPS in front of keyframes and waits
 * for the first keyframe. Timestamps stay monotonic across publisher reconnects
 * ([onPublishStarted]), so a player that stays connected does not see time jump backwards.
 */
class RtmpToTsRemuxer(private val muxer: MpegTsMuxer = MpegTsMuxer()) {
    private var config: AvcDecoderConfig? = null
    private var waitingForKeyframe = true
    private var lastDtsMs = -1L
    private var offsetMs = 0L
    private var rebasePending = false

    /** Set once a frame used a codec this remuxer cannot pass on (for example HEVC). */
    var unsupportedCodec: String? = null
        private set

    val configuration: AvcDecoderConfig? get() = config

    /** A publisher (re)started: its timestamps restart, and decoding must restart at a keyframe. */
    fun onPublishStarted() {
        waitingForKeyframe = true
        rebasePending = lastDtsMs >= 0
    }

    /** Returns TS packets for the frame, or null for configuration, unsupported data or pre-keyframe frames. */
    fun onVideo(timestampMs: Long, payload: ByteArray): TsChunk? =
        when (val packet = FlvVideoTag.parse(payload)) {
            is FlvVideoPacket.AvcSequenceHeader -> {
                config = packet.config
                null
            }
            is FlvVideoPacket.Unsupported -> {
                if (unsupportedCodec == null && packet.description.contains("codec")) unsupportedCodec = packet.description
                null
            }
            FlvVideoPacket.EndOfSequence -> {
                waitingForKeyframe = true
                null
            }
            is FlvVideoPacket.AvcFrame -> frame(timestampMs, packet)
        }

    private fun frame(timestampMs: Long, packet: FlvVideoPacket.AvcFrame): TsChunk? {
        val config = config ?: return null
        val units = AvcNal.splitAvcc(packet.data, config.nalLengthSize)
        if (units.isEmpty()) return null
        val keyframe = packet.keyframe || units.any { AvcNal.type(it) == AvcNal.TYPE_IDR }
        if (waitingForKeyframe) {
            if (!keyframe) return null
            waitingForKeyframe = false
        }
        if (lastDtsMs < 0) {
            // The first frame starts the stream clock at zero, whatever the publisher's clock says.
            offsetMs = -timestampMs
        } else if (rebasePending) {
            rebasePending = false
            offsetMs = lastDtsMs + REBASE_GAP_MS - timestampMs
        }
        var dts = timestampMs + offsetMs
        if (dts <= lastDtsMs) dts = lastDtsMs + 1
        lastDtsMs = dts
        val pts = dts + packet.compositionTimeMs.coerceAtLeast(0)
        val accessUnit = AvcNal.accessUnit(units, config)
        return TsChunk(muxer.muxVideo(accessUnit, ptsMs = pts, dtsMs = dts, keyframe = keyframe), keyframe, dts)
    }

    private companion object {
        const val REBASE_GAP_MS = 100L
    }
}

package com.fersaiyan.cyanbridge.glasses

/**
 * Minimum-length guard for vendor cmd-65 (`GlassModelControl` / `glassesControl`) BLE notify
 * frames, applied at the app BLE receive seam BEFORE the bytes are consumed.
 *
 * Why this exists
 * ---------------
 * The vendor Oudmon SDK parses cmd-65 notifications at FIXED byte offsets with no length check:
 *   com.oudmon.ble.base.communication.bigData.resp.GlassModelControlResponse.acceptData(byte[])
 * reads `data[7]` as the dataType and then indexes further offsets / `Arrays.copyOfRange(...)`
 * for each dataType. A short or truncated frame (seen under flaky P2P Wi-Fi during media sync)
 * makes those reads run off the end of the array, throwing
 *   ArrayIndexOutOfBoundsException (length=8 index=8)  /  arraycopy length=-1.
 *
 * The coordinator requests this notify in ALL sync flows (OFFICIAL_HEYCYAN / ADAPTIVE / CUSTOM)
 * via LargeDataHandler.glassesControl(...), so the hazard is mode-agnostic.
 *
 * A frame shorter than the minimum its declared dataType requires is treated as an INCOMPLETE,
 * RETRIABLE status: it is dropped at the app seam (never fed to the app's own notify consumers)
 * and never allowed to throw. A well-formed notify that arrives afterwards proceeds normally.
 *
 * Frame layout (post-reassembly, as the vendor parser sees it)
 * ------------------------------------------------------------
 *   data[0] = 0xBC            big-data magic byte
 *   data[1] = command id      65 (0x41) routes to GlassModelControlResponse
 *   data[7] = dataType        selects which trailing fields are read
 *
 * Minimum lengths (derived from the vendor offset reads; pinned by add.217):
 *   dataType 1  -> 10   (reads data[8], guarded data[9]/data[10])
 *   dataType 2  -> 11   (reads data[8], copyOfRange(data, 9, 11))
 *   dataType 3  -> 13   (reads data[10], data[11], data[12])
 *   dataType 4  -> 14   (copyOfRange(data, 8,10 / 10,12 / 12,14))
 *   dataType 6  -> 11   (copyOfRange(data, 9, 11))
 * Any frame must be at least [HEADER_MIN_LEN] bytes simply to read the dataType at data[7].
 */
object GlassModelControlFrameGuard {

    /** Big-data notify magic byte at offset 0. */
    const val BIG_DATA_MAGIC: Int = 0xBC

    /** Command id (offset 1) that the vendor routes to GlassModelControlResponse. */
    const val CMD_GLASS_MODEL_CONTROL: Int = 65

    /** A frame must hold at least this many bytes just to read dataType at data[7]. */
    const val HEADER_MIN_LEN: Int = 8

    /**
     * Minimum total frame length required for each dataType, keyed by dataType value.
     * dataTypes not listed here only need [HEADER_MIN_LEN] (they do no further indexing).
     */
    private val MIN_LEN_BY_DATA_TYPE: Map<Int, Int> =
        mapOf(
            1 to 10,
            2 to 11,
            3 to 13,
            4 to 14,
            6 to 11,
        )

    /** The minimum total length a cmd-65 frame of [dataType] must have to be parsed safely. */
    fun minLengthForDataType(dataType: Int): Int =
        MIN_LEN_BY_DATA_TYPE[dataType] ?: HEADER_MIN_LEN

    /**
     * True when [frame] is a cmd-65 GlassModelControl big-data notify (magic 0xBC, command 65).
     * Non-cmd-65 frames are not this guard's concern and return false.
     */
    fun isGlassModelControlFrame(frame: ByteArray): Boolean =
        frame.size >= 2 &&
            (frame[0].toInt() and 0xFF) == BIG_DATA_MAGIC &&
            (frame[1].toInt() and 0xFF) == CMD_GLASS_MODEL_CONTROL

    /**
     * True when [frame] is long enough for the vendor parser to read every offset its declared
     * dataType requires. Never throws, whatever the input (empty / 1-byte / truncated).
     *
     * Returns false (incomplete, retriable) when the frame is too short to even read dataType, or
     * too short for the trailing fields of the dataType it declares.
     */
    fun isCompleteStatusFrame(frame: ByteArray): Boolean {
        if (frame.size < HEADER_MIN_LEN) return false
        val dataType = frame[7].toInt() and 0xFF
        return frame.size >= minLengthForDataType(dataType)
    }

    /**
     * Decides whether a raw notify [frame] seen at the app BLE receive seam should be dropped as
     * an incomplete, retriable cmd-65 status. Only cmd-65 frames are ever dropped; every other
     * frame (and every well-formed cmd-65 frame) is allowed through. Never throws.
     *
     * @return true to DROP the frame (short cmd-65 status), false to allow it through.
     */
    fun shouldDropIncompleteFrame(frame: ByteArray): Boolean =
        isGlassModelControlFrame(frame) && !isCompleteStatusFrame(frame)
}

package com.fersaiyan.cyanbridge.plugins.walkingaid.ocr

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.fersaiyan.cyanbridge.R
import com.fersaiyan.cyanbridge.ai.vision.ImageQuestionPreferences
import com.fersaiyan.cyanbridge.plugins.walkingaid.vision.DetectedObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Ride and bus help in Walking Aid (owner design 2026-10-28 / 2026-11-23): YOLO keeps describing the surroundings;
 * in ride mode a car box, in bus mode a bus box, that stays big and in view for a few frames triggers PP-OCR on a
 * crop of the full-resolution frame (lower middle of a car for the plate, top strip of a bus for its sign). Reads
 * are voted across frames per tracked object and matched against the target (OcrText); the result is spoken once
 * per vehicle. OCR runs off the YOLO thread, one read at a time; frames arriving meanwhile are skipped.
 */
class WalkingAidReader(
    private val context: Context,
    private val scope: CoroutineScope,
    private val speak: (String) -> Unit,
) : AutoCloseable {
    @Volatile var goal: WalkingAidGoal = WalkingAidGoal.Describe
        set(value) {
            field = value
            synchronized(tracks) { tracks.clear() }
        }

    private class Track(var frames: Int = 0, val reads: ArrayDeque<String> = ArrayDeque(), var announcedAtMs: Long = 0L)

    private val tracks = HashMap<Long, Track>()
    private val busy = AtomicBoolean(false)
    @Volatile private var engine: PpOcrEngine? = null
    @Volatile private var engineFailed = false

    /** Call for every analysed frame with its tracked detections (normalized boxes); never blocks. */
    fun onFrame(frame: Bitmap, objects: List<DetectedObject>) {
        val goal = goal
        val wanted = when (goal) {
            WalkingAidGoal.Describe -> return
            is WalkingAidGoal.Ride -> setOf("car")
            is WalkingAidGoal.Bus -> setOf("bus")
        }
        val candidate = objects.filter { it.label in wanted && it.trackId != null }
            .maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
        val seen = objects.mapNotNull { it.trackId }.toSet()
        val track = synchronized(tracks) {
            tracks.keys.retainAll(seen)
            candidate?.let { c -> tracks.getOrPut(c.trackId!!) { Track() }.also { it.frames++ } }
        } ?: return
        val box = candidate!!.boundingBox
        val area = box.width() * box.height()
        if (track.frames < STABLE_FRAMES || area < (if (goal is WalkingAidGoal.Bus) MIN_BUS_AREA else MIN_CAR_AREA)) return
        if (SystemClock.elapsedRealtime() - track.announcedAtMs < REANNOUNCE_MS) return
        if (engineFailed || !busy.compareAndSet(false, true)) return
        val crop = cropRegion(frame, candidate, goal) ?: run { busy.set(false); return }
        scope.launch(Dispatchers.Default) {
            try {
                val engine = engine ?: OcrModelStore.open(context)?.also { engine = it } ?: run {
                    engineFailed = true
                    Log.w(TAG, "Reader models not installed; ride/bus reading is off for this session")
                    return@launch
                }
                handleRead(track, candidate, goal, readCrop(engine, crop, goal))
            } catch (error: Exception) {
                Log.w(TAG, "Read failed: ${error.message}")
            } finally {
                crop.recycle()
                busy.set(false)
            }
        }
    }

    private fun readCrop(engine: PpOcrEngine, crop: Bitmap, goal: WalkingAidGoal): String = when (goal) {
        is WalkingAidGoal.Ride -> {
            val lines = engine.read(crop, plate = true).map { it.text }
            // A plate may be one line, two rows (motorcycles, old city band) or split by the detector.
            (lines + lines.joinToString("")).map(OcrText::plateFormatFix)
                .firstOrNull { it.length == 7 && WalkingAidGoalParser.plateIn(it) != null }.orEmpty()
        }
        is WalkingAidGoal.Bus -> engine.read(crop).joinToString(" ") { it.text }.trim()
        WalkingAidGoal.Describe -> ""
    }

    private fun handleRead(track: Track, obj: DetectedObject, goal: WalkingAidGoal, read: String) {
        val message = synchronized(tracks) {
            if (read.isNotBlank()) {
                track.reads.addLast(read)
                while (track.reads.size > VOTE_WINDOW) track.reads.removeFirst()
            }
            val position = position(obj.position)
            when (goal) {
                is WalkingAidGoal.Ride -> {
                    val target = goal.plate ?: RideNotificationPlate.current()
                    if (target != null) {
                        val hits = track.reads.count { OcrText.plateMatches(it, target) }
                        if (hits >= 2) str(R.string.walking_aid_ride_match, spell(OcrText.plateFormatFix(target)), position) else null
                    } else {
                        OcrText.vote(track.reads)?.let { str(R.string.walking_aid_plate_read, spell(it), position) }
                    }
                }
                is WalkingAidGoal.Bus -> {
                    val target = goal.route
                    if (target != null) {
                        val hits = track.reads.count { OcrText.busSignMatches(it, target) }
                        if (hits >= 2) str(R.string.walking_aid_bus_match, target, position) else null
                    } else {
                        val normalized = track.reads.map { OcrText.normalize(it) }
                        OcrText.vote(normalized)?.let { winner ->
                            track.reads.lastOrNull { OcrText.normalize(it) == winner }
                        }?.let { str(R.string.walking_aid_bus_read, it, position) }
                    }
                }
                WalkingAidGoal.Describe -> null
            }?.also { track.announcedAtMs = SystemClock.elapsedRealtime() }
        }
        message?.let(speak)
    }

    private fun cropRegion(frame: Bitmap, obj: DetectedObject, goal: WalkingAidGoal): Bitmap? {
        if (frame.isRecycled) return null
        val b = obj.boundingBox
        val (x0, y0, x1, y1) = when (goal) {
            // Plates sit in the lower middle of a car seen from the front or back.
            is WalkingAidGoal.Ride -> listOf(b.left + 0.1f * b.width(), b.top + 0.4f * b.height(), b.right - 0.1f * b.width(), b.bottom)
            // The route sign is the top strip of the bus front.
            is WalkingAidGoal.Bus -> listOf(b.left, b.top, b.right, b.top + 0.4f * b.height())
            WalkingAidGoal.Describe -> return null
        }
        val left = (x0 * frame.width).toInt().coerceIn(0, frame.width - 1)
        val top = (y0 * frame.height).toInt().coerceIn(0, frame.height - 1)
        val right = (x1 * frame.width).toInt().coerceIn(left + 1, frame.width)
        val bottom = (y1 * frame.height).toInt().coerceIn(top + 1, frame.height)
        if (right - left < 32 || bottom - top < 16) return null
        return runCatching { Bitmap.createBitmap(frame, left, top, right - left, bottom - top) }.getOrNull()
            ?.let { if (it === frame) it.copy(it.config ?: Bitmap.Config.ARGB_8888, false) else it }
    }

    private fun localized(): Context {
        val tag = ImageQuestionPreferences.get(context).appLanguageTag
        return context.createConfigurationContext(Configuration(context.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(tag))
        })
    }

    private fun str(id: Int, vararg args: Any): String = localized().getString(id, *args)

    private fun position(position: String): String = localized().getString(
        when (position) {
            "left" -> R.string.walking_aid_position_left
            "right" -> R.string.walking_aid_position_right
            else -> R.string.walking_aid_position_center
        },
    )

    /** "ABC1D23" -> "A B C 1 D 2 3", so speech reads characters, not a word. */
    private fun spell(plate: String) = plate.toCharArray().joinToString(" ")

    override fun close() {
        engine?.close()
        engine = null
    }

    private companion object {
        const val TAG = "WalkingAidReader"
        const val STABLE_FRAMES = 3
        const val MIN_CAR_AREA = 0.03f
        const val MIN_BUS_AREA = 0.06f
        const val VOTE_WINDOW = 5
        const val REANNOUNCE_MS = 30_000L
    }
}

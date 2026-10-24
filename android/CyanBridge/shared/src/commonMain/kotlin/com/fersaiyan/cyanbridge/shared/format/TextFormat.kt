package com.fersaiyan.cyanbridge.shared.format

import com.fersaiyan.cyanbridge.shared.platform.platformUtcOffsetMinutes
import kotlin.math.abs

/**
 * `String.format(Locale.US, "%.Nf", value)` (with `%+.Nf` when [plusSign]): the shortest decimal
 * form of [value] rounded half-up to [decimals] places, as Java's formatter does.
 */
fun formatFixed(value: Double, decimals: Int, plusSign: Boolean = false): String {
    val negative = value < 0.0 || (value == 0.0 && 1.0 / value < 0.0)
    val sign = if (negative) "-" else if (plusSign) "+" else ""
    if (value.isNaN()) return "NaN"
    if (value.isInfinite()) return "${sign}Infinity"

    val parts = abs(value).toString().split('E', 'e')
    val mantissa = parts[0]
    val exponent = parts.getOrNull(1)?.toInt() ?: 0
    val intPart = mantissa.substringBefore('.')
    var digits = intPart + mantissa.substringAfter('.', "")
    var point = intPart.length + exponent
    if (point < 0) {
        digits = "0".repeat(-point) + digits
        point = 0
    }
    val keep = point + decimals
    if (digits.length <= keep) digits = digits.padEnd(keep + 1, '0')

    val kept = digits.substring(0, keep).toCharArray()
    var carry = digits[keep] >= '5'
    var index = kept.lastIndex
    while (carry && index >= 0) {
        if (kept[index] == '9') {
            kept[index] = '0'
            index--
        } else {
            kept[index]++
            carry = false
        }
    }
    val rounded = (if (carry) "1" else "") + kept.concatToString()
    val integerDigits = rounded.length - decimals
    val integer = rounded.substring(0, integerDigits).trimStart('0').ifEmpty { "0" }
    val fraction = rounded.substring(integerDigits)
    return if (decimals == 0) "$sign$integer" else "$sign$integer.$fraction"
}

private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

/** Calendar fields of an instant in the device's time zone. */
private class LocalDateTime(epochMillis: Long) {
    val year: Int
    val month: Int
    val day: Int
    val hour: Int
    val minute: Int

    init {
        val localMillis = epochMillis + platformUtcOffsetMinutes(epochMillis) * 60_000L
        val days = localMillis.floorDiv(DAY_MS)
        val minuteOfDay = ((localMillis - days * DAY_MS) / 60_000L).toInt()
        hour = minuteOfDay / 60
        minute = minuteOfDay % 60
        // Proleptic Gregorian date from days since 1970-01-01 (H. Hinnant, civil_from_days).
        val z = days + 719_468L
        val era = z.floorDiv(146_097L)
        val doe = z - era * 146_097L
        val yoe = (doe - doe / 1_460L + doe / 36_524L - doe / 146_096L) / 365L
        val doy = doe - (365L * yoe + yoe / 4L - yoe / 100L)
        val mp = (5L * doy + 2L) / 153L
        day = (doy - (153L * mp + 2L) / 5L + 1L).toInt()
        month = (if (mp < 10L) mp + 3L else mp - 9L).toInt()
        year = (yoe + era * 400L + if (month <= 2) 1L else 0L).toInt()
    }

    companion object {
        const val DAY_MS = 86_400_000L
    }
}

private fun Int.twoDigits() = toString().padStart(2, '0')

/** `yyyy-MM-dd` in the device's time zone. */
fun formatLocalDate(epochMillis: Long): String = with(LocalDateTime(epochMillis)) {
    "${year.toString().padStart(4, '0')}-${month.twoDigits()}-${day.twoDigits()}"
}

/** `yyyy-MM-dd HH:mm` in the device's time zone. */
fun formatLocalDateTime(epochMillis: Long): String = with(LocalDateTime(epochMillis)) {
    "${formatLocalDate(epochMillis)} ${hour.twoDigits()}:${minute.twoDigits()}"
}

/** `MMM d, HH:mm` (US English month) in the device's time zone. */
fun formatLocalMonthDayTime(epochMillis: Long): String = with(LocalDateTime(epochMillis)) {
    "${MONTHS[month - 1]} $day, ${hour.twoDigits()}:${minute.twoDigits()}"
}

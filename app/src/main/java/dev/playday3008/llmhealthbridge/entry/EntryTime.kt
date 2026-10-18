package dev.playday3008.llmhealthbridge.entry

import kotlin.math.abs

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import java.util.Locale

private const val SECONDS_PER_MINUTE = 60
private const val SECONDS_PER_HOUR = 3600

/** A time already moved to the phone's zone, with the offset the reply gave, if any. */
private class ParsedTime(val time: OffsetDateTime, val givenOffset: ZoneOffset?)

/**
 * Reads the entry time from [raw] as a time in [zone], the zone the review shows and the records
 * are saved in. A missing or unreadable time becomes [now]; an unreadable one also adds a
 * warning, as does an offset that differs from the phone's.
 */
internal fun resolveTime(
    raw: JsonElement?,
    now: Instant,
    zone: ZoneId,
    warnings: MutableList<String>
): OffsetDateTime {
    val parsed = raw?.let { parseTime(it, zone) }
    if (raw != null && parsed == null) warnings += "time: unreadable, using now"
    if (parsed == null) return now.truncatedTo(ChronoUnit.SECONDS).atZone(zone).toOffsetDateTime()
    val given = parsed.givenOffset
    val phoneOffset = parsed.time.offset
    if (given != null && given != phoneOffset) {
        warnings += "time: offset ${formatOffset(given)} differs from this phone " +
            "(${formatOffset(phoneOffset)})"
    }
    return parsed.time
}

// java.time parses years up to 999999999, but moving such a time to another offset can pass the
// largest representable date and throw DateTimeException; that counts as unreadable too.
private fun parseTime(raw: JsonElement, zone: ZoneId): ParsedTime? {
    val text = (raw as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim() ?: return null
    return try {
        val given = OffsetDateTime.parse(text)
        ParsedTime(given.atZoneSameInstant(zone).toOffsetDateTime(), given.offset)
    } catch (_: DateTimeParseException) {
        parseLocalTime(text, zone)
    } catch (_: DateTimeException) {
        null
    }
}

private fun parseLocalTime(text: String, zone: ZoneId): ParsedTime? = try {
    ParsedTime(LocalDateTime.parse(text).atZone(zone).toOffsetDateTime(), givenOffset = null)
} catch (_: DateTimeException) {
    null
}

internal fun formatOffset(offset: ZoneOffset): String {
    val total = offset.totalSeconds
    val sign = if (total < 0) '-' else '+'
    val abs = abs(total)
    return "%c%02d:%02d".format(
        Locale.ROOT,
        sign,
        abs / SECONDS_PER_HOUR,
        abs % SECONDS_PER_HOUR / SECONDS_PER_MINUTE
    )
}

package dev.playday3008.llmhealthbridge.send

import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

import dev.playday3008.llmhealthbridge.entry.RequestIds

private val exifFormat = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")

/**
 * Converts EXIF `DateTimeOriginal` (`yyyy:MM:dd HH:mm:ss`) and the optional `OffsetTimeOriginal`
 * (`+hh:mm`) to an [OffsetDateTime]. Without a usable offset, [zone] applies. Returns null when
 * the date is absent or malformed.
 */
fun exifCaptureTime(
    dateTimeOriginal: String?,
    offsetTimeOriginal: String?,
    zone: ZoneId
): OffsetDateTime? {
    val local = dateTimeOriginal?.let {
        try {
            LocalDateTime.parse(it.trim(), exifFormat)
        } catch (_: DateTimeParseException) {
            null
        }
    }
    val offset = offsetTimeOriginal?.let {
        try {
            ZoneOffset.of(it.trim())
        } catch (_: DateTimeException) {
            null
        }
    }
    return if (offset != null) local?.atOffset(offset) else local?.atZone(zone)?.toOffsetDateTime()
}

/**
 * The default time written into the instructions of a request sent at [now], for a set of photo
 * capture times: the earliest one an entry of the request may have, that is not after [now] and
 * not more than [RequestIds.LOOKBACK] before it, else (including when there are no photos) [now]
 * in [zone] truncated to seconds.
 */
fun defaultTime(captures: List<OffsetDateTime?>, now: Instant, zone: ZoneId): OffsetDateTime {
    val earliest = now.minus(RequestIds.LOOKBACK)
    return captures.filterNotNull()
        .filter { !it.toInstant().isAfter(now) && !it.toInstant().isBefore(earliest) }
        .minOrNull()
        ?: now.atZone(zone).toOffsetDateTime().truncatedTo(ChronoUnit.SECONDS)
}

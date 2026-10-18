package dev.playday3008.llmhealthbridge.send

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId

class DefaultTimeTest {
    private val zone = ZoneId.of("Europe/Warsaw")
    private val now = Instant.parse("2026-10-18T12:00:00Z")

    @Test
    fun exifWithOffset() = assertEquals(
        OffsetDateTime.parse("2026-10-18T09:15:00-04:00"),
        exifCaptureTime("2026:10:18 09:15:00", "-04:00", zone)
    )

    @Test
    fun exifWithoutOffsetUsesZone() = assertEquals(
        OffsetDateTime.parse("2026-10-18T09:15:00+02:00"),
        exifCaptureTime("2026:10:18 09:15:00", null, zone)
    )

    @Test
    fun malformedIsNull() = assertNull(exifCaptureTime("garbage", null, zone))

    @Test
    fun earliestCaptureWinsAndMissingIsSkipped() = assertEquals(
        OffsetDateTime.parse("2026-10-18T12:40:00+02:00"),
        defaultTime(
            listOf(
                null,
                OffsetDateTime.parse("2026-10-18T13:00:00+02:00"),
                OffsetDateTime.parse("2026-10-18T12:40:00+02:00")
            ),
            now,
            zone
        )
    )

    @Test
    fun noCaptureFallsBackToNow() =
        assertEquals(now.atZone(zone).toOffsetDateTime(), defaultTime(listOf(null), now, zone))

    @Test
    fun noPhotosMeansNow() = assertEquals(
        OffsetDateTime.parse("2026-10-18T14:00:00+02:00"),
        defaultTime(emptyList(), Instant.parse("2026-10-18T12:00:00.750Z"), zone)
    )

    @Test
    fun captureMoreThan30DaysOldIsIgnored() = assertEquals(
        OffsetDateTime.parse("2026-09-18T14:00:00+02:00"),
        defaultTime(
            listOf(
                OffsetDateTime.parse("2000-01-01T00:00:00+01:00"),
                OffsetDateTime.parse("2026-09-18T13:59:59+02:00"),
                OffsetDateTime.parse("2026-09-18T14:00:00+02:00")
            ),
            now,
            zone
        )
    )

    @Test
    fun futureCaptureIsIgnored() = assertEquals(
        now.atZone(zone).toOffsetDateTime(),
        defaultTime(listOf(OffsetDateTime.parse("2026-10-18T15:00:00+02:00")), now, zone)
    )
}

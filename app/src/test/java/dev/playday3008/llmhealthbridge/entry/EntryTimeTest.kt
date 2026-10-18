package dev.playday3008.llmhealthbridge.entry

import kotlin.test.Test
import kotlin.test.assertEquals

import kotlinx.serialization.json.JsonPrimitive

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId

class EntryTimeTest {
    private val now = Instant.parse("2026-10-18T12:00:00Z")
    private val zone = ZoneId.of("Europe/Warsaw")

    private fun resolve(raw: String?, warnings: MutableList<String> = mutableListOf()) =
        resolveTime(raw?.let { JsonPrimitive(it) }, now, zone, warnings)

    @Test fun matchingOffsetIsKeptWithoutWarning() {
        val warnings = mutableListOf<String>()
        assertEquals(
            OffsetDateTime.parse("2026-10-18T13:00:00+02:00"),
            resolve("2026-10-18T13:00:00+02:00", warnings)
        )
        assertEquals(emptyList(), warnings)
    }

    // The review shows the phone's zone, so the record must be saved in it too.
    @Test fun foreignOffsetIsMovedToThePhoneZoneWithAWarning() {
        val warnings = mutableListOf<String>()
        assertEquals(
            OffsetDateTime.parse("2026-10-18T06:00:00+02:00"),
            resolve("2026-10-18T13:00:00+09:00", warnings)
        )
        assertEquals(listOf("time: offset +09:00 differs from this phone (+02:00)"), warnings)
    }

    @Test fun offsetIsComparedWithThePhoneOffsetOfItsOwnDate() {
        val warnings = mutableListOf<String>()
        assertEquals(
            OffsetDateTime.parse("2026-10-26T13:00:00+01:00"),
            resolve("2026-10-26T13:00:00+01:00", warnings)
        )
        assertEquals(emptyList(), warnings)
    }

    @Test fun localTimeTakesTheOffsetOfItsOwnDate() {
        assertEquals(
            OffsetDateTime.parse("2026-10-24T13:00:00+02:00"),
            resolve("2026-10-24T13:00:00")
        )
        assertEquals(
            OffsetDateTime.parse("2026-10-26T13:00:00+01:00"),
            resolve("2026-10-26T13:00:00")
        )
    }

    @Test fun unreadableTimeIsNowWithAWarning() {
        val warnings = mutableListOf<String>()
        assertEquals(now, resolve("yesterday", warnings).toInstant())
        assertEquals(listOf("time: unreadable, using now"), warnings)
    }

    // A wrong but real year is kept, so the time check blocks it instead of saving it at now.
    @Test fun distantYearIsKept() {
        val warnings = mutableListOf<String>()
        assertEquals(1026, resolve("1026-10-18T13:00:00+01:00", warnings).year)
        assertEquals(1026, resolve("1026-10-18T13:00:00").year)
    }

    @Test fun yearBeyondTheZoneConversionIsUnreadable() {
        val warnings = mutableListOf<String>()
        assertEquals(now, resolve("+999999999-12-31T23:59:59-18:00", warnings).toInstant())
        assertEquals(listOf("time: unreadable, using now"), warnings)
    }

    @Test fun nonStringTimeIsUnreadable() {
        val warnings = mutableListOf<String>()
        assertEquals(now, resolveTime(JsonPrimitive(5), now, zone, warnings).toInstant())
        assertEquals(listOf("time: unreadable, using now"), warnings)
    }

    @Test fun missingTimeIsNowWithoutWarning() {
        val warnings = mutableListOf<String>()
        assertEquals(now, resolve(null, warnings).toInstant())
        assertEquals(emptyList(), warnings)
    }
}

package dev.playday3008.llmhealthbridge.send

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

import java.io.File
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId

import dev.playday3008.llmhealthbridge.entry.EntryType
import dev.playday3008.llmhealthbridge.entry.ReplyResult
import dev.playday3008.llmhealthbridge.entry.parseReply
import dev.playday3008.llmhealthbridge.measurement.MeasurementEntry
import dev.playday3008.llmhealthbridge.measurement.MeasurementTypes
import dev.playday3008.llmhealthbridge.nutrition.Meal
import dev.playday3008.llmhealthbridge.nutrition.MealType
import dev.playday3008.llmhealthbridge.nutrition.Nutrients

class InstructionsTest {
    private val asset = File("src/main/assets/${Instructions.ASSET}").readText()

    @Test
    fun listsExactlyTheKnownKeys() {
        val keysSection = asset.substringAfter("\nKEYS\n")
        val found = Regex("`([a-z][a-z0-9_]*)`")
            .findAll(keysSection)
            .map { it.groupValues[1] }
            .toSet()
        val expected = Nutrients.all.map { it.key }.toSet() +
            MeasurementTypes.all.flatMap { t ->
                t.fields.flatMap { listOf(it.jsonKey) + it.altKeys.keys }
            }
        assertEquals(expected, found)
    }

    private fun example(): ReplyResult.Parsed =
        parseReply(Instructions.fill(asset, TIME, ZONE, REQUEST), NOW, ZONE) as ReplyResult.Parsed

    // The filled instructions parse as a reply, which is why the app labels its own clip.
    @Test
    fun exampleParsesAsEveryEntryType() =
        assertEquals(EntryType.entries.toSet(), example().groups.map { it.id.type }.toSet())

    @Test
    fun exampleParsesCleanlyUnderItsRequest() {
        val r = example()
        assertEquals(emptyList(), r.warnings)
        assertTrue(r.groups.all { it.id.request == REQUEST && it.warnings.isEmpty() })
        r.groups.filterIsInstance<Meal>().forEach { meal ->
            assertEquals(emptyList(), meal.issues(NOW) + meal.items.flatMap { it.issues() })
        }
        r.groups.filterIsInstance<MeasurementEntry>().forEach { entry ->
            assertEquals(emptyList(), entry.issues(NOW))
            assertEquals(emptyList(), entry.values.flatMap { it.warnings() })
        }
    }

    @Test
    fun listsExactlyTheKnownTypesAndMealTypes() {
        fun oneOf(label: String) = Regex("""$label is one of: ([a-z, ]+)\.""")
            .find(asset)?.groupValues?.get(1)?.split(", ")?.toSet()
        assertEquals(EntryType.entries.map { it.jsonName }.toSet(), oneOf("\"type\""))
        assertEquals(MealType.entries.map { it.jsonName }.toSet(), oneOf("\"meal_type\""))
    }

    @Test
    fun hasPlaceholderAndJsonFence() {
        assertTrue(Instructions.PLACEHOLDER in asset)
        assertTrue(Instructions.REQUEST_PLACEHOLDER in asset)
        assertTrue(Instructions.ZONE_PLACEHOLDER in asset)
        assertTrue("```json" in asset)
        assertFalse("://" in asset)
    }

    @Test
    fun fillReplacesPlaceholders() = assertEquals(
        "at 2026-10-18T13:30:00+02:00 in Europe/Warsaw for $REQUEST.",
        Instructions.fill(
            "at {{DEFAULT_TIME}} in {{TIME_ZONE}} for {{REQUEST_ID}}.",
            TIME,
            ZONE,
            REQUEST
        )
    )

    private companion object {
        val TIME: OffsetDateTime = OffsetDateTime.parse("2026-10-18T13:30:00+02:00")
        val NOW: Instant = Instant.parse("2026-10-18T12:00:00Z")
        val ZONE: ZoneId = ZoneId.of("Europe/Warsaw")
        const val REQUEST = "mvdrjvpc-7f2a"
    }
}

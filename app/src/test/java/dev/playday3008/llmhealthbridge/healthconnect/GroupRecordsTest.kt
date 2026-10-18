package dev.playday3008.llmhealthbridge.healthconnect

import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.WeightRecord

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId

import dev.playday3008.llmhealthbridge.entry.EntryParse
import dev.playday3008.llmhealthbridge.entry.EntryType
import dev.playday3008.llmhealthbridge.entry.GroupId
import dev.playday3008.llmhealthbridge.entry.RecordIds
import dev.playday3008.llmhealthbridge.measurement.MeasurementEntry
import dev.playday3008.llmhealthbridge.measurement.MeasurementTypes
import dev.playday3008.llmhealthbridge.measurement.parseMeasurementEntry
import dev.playday3008.llmhealthbridge.nutrition.Meal
import dev.playday3008.llmhealthbridge.nutrition.MealItem
import dev.playday3008.llmhealthbridge.nutrition.MealType

class GroupRecordsTest {
    private val now = Instant.parse("2026-10-18T12:00:00Z")
    private val zone = ZoneId.of("Europe/Warsaw")
    private val all = ALL_WRITE_PERMISSIONS
    private val request = "mvdrjvpc-7f2a"

    private fun body(
        time: String,
        entryId: String = "e1",
        request: String = this.request
    ): MeasurementEntry {
        val json = """{"time":"$time","weight_kg":82.4,"body_fat_percent":18.6}"""
        val parsed = parseMeasurementEntry(
            Json.parseToJsonElement(json).jsonObject,
            GroupId(EntryType.BODY, request, entryId),
            now,
            zone
        ) as EntryParse.Valid
        return parsed.group as MeasurementEntry
    }

    private val entry = body("2026-10-18T07:12:00+02:00")
    private val records = measurementRecords(entry, now, 1, all)
    private val bodyFatId = RecordIds.clientRecordId(entry.id, "body_fat")

    @Test fun droppedMeasurementIsStale() {
        val stale = staleClientIds(
            records,
            entry.id,
            setOf("weight"),
            setOf(WeightRecord::class, BodyFatRecord::class)
        )
        assertEquals(
            mapOf<kotlin.reflect.KClass<out Record>, List<String>>(
                BodyFatRecord::class to listOf(bodyFatId)
            ),
            stale
        )
    }

    @Test fun deniedClassIsNeverStale() {
        assertTrue(
            staleClientIds(records, entry.id, setOf("weight"), setOf(WeightRecord::class)).isEmpty()
        )
    }

    @Test fun otherGroupsAreNeverStale() {
        val other =
            measurementRecords(body("2026-10-18T07:12:00+02:00", entryId = "e2"), now, 1, all)
        assertTrue(
            staleClientIds(
                other,
                entry.id,
                emptySet(),
                setOf(WeightRecord::class, BodyFatRecord::class)
            ).isEmpty()
        )
    }

    @Test fun versionIncreasesPastClock() {
        val ahead = measurementRecords(entry, now, now.toEpochMilli() + 5000, all)
        assertEquals(now.toEpochMilli() + 5001, nextVersion(ahead, now))
        assertEquals(now.toEpochMilli(), nextVersion(records, now))
    }

    @Test fun mixedRecordsRegroup() {
        val meal = Meal(
            GroupId(EntryType.NUTRITION, request, "e2"),
            MealType.LUNCH,
            null,
            OffsetDateTime.parse("2026-10-18T12:00:00+02:00"),
            listOf(MealItem(RecordIds.itemKey("Rice", 0), "Rice", mapOf("energy_kcal" to 260.0))),
            emptyList()
        )
        val mixed: List<Record> = buildRecords(meal, meal.items, now, 1) + records
        val groups = groupsFromRecords(mixed)
        assertEquals(2, groups.size)
        assertTrue(groups[0] is Meal, "meal is newer")
        assertTrue(groups[1] is MeasurementEntry)
    }

    @Test fun recordClassesFollowTheRegistry() {
        assertEquals(setOf(NutritionRecord::class), recordClasses(EntryType.NUTRITION))
        assertEquals(
            MeasurementTypes.forEntryType(EntryType.BODY).map { it.recordClass }.toSet(),
            recordClasses(EntryType.BODY)
        )
        assertTrue(writePermissions(EntryType.NUTRITION).all { it in all })
    }

    @Test fun grantedClassesKeepOnlyGrantedTypesOfTheEntryType() {
        val weightOnly = setOf(
            HealthPermission.getWritePermission(WeightRecord::class),
            HealthPermission.getWritePermission(NutritionRecord::class)
        )
        assertEquals(setOf(WeightRecord::class), grantedClasses(EntryType.BODY, weightOnly))
        assertEquals(emptySet(), grantedClasses(EntryType.VITALS, weightOnly))
        assertEquals(recordClasses(EntryType.VITALS), grantedClasses(EntryType.VITALS, all))
    }

    @Test fun requestGroupsKeepOnlyThatRequest() {
        val sibling = body("2026-10-18T09:00:00+02:00", entryId = "e2")
        val foreign = body("2026-10-18T09:00:00+02:00", request = "mvdrjvpd-0000")
        val saved = requestGroups(
            request,
            records + measurementRecords(sibling, now, 1, all) +
                measurementRecords(foreign, now, 1, all)
        )
        assertEquals(listOf(sibling.id, entry.id), saved.map { it.id })
        assertEquals("Weight 82.4 kg + 1 more", (saved[1] as MeasurementEntry).summary())
    }
}

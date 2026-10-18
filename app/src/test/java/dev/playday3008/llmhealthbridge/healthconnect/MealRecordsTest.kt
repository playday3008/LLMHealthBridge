package dev.playday3008.llmhealthbridge.healthconnect

import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.metadata.Metadata

import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId

import dev.playday3008.llmhealthbridge.entry.EntryType
import dev.playday3008.llmhealthbridge.entry.GroupId
import dev.playday3008.llmhealthbridge.entry.RecordIds
import dev.playday3008.llmhealthbridge.entry.ReplyResult
import dev.playday3008.llmhealthbridge.entry.parseReply
import dev.playday3008.llmhealthbridge.nutrition.Meal
import dev.playday3008.llmhealthbridge.nutrition.MealItem
import dev.playday3008.llmhealthbridge.nutrition.MealType
import dev.playday3008.llmhealthbridge.nutrition.Nutrients

import androidx.health.connect.client.records.MealType as HcMealType

class MealRecordsTest {
    private val now = Instant.parse("2026-10-18T12:00:00Z")
    private val meal = Meal(
        GroupId(EntryType.NUTRITION, "mvdrjvpc-7f2a", "e1"),
        MealType.LUNCH,
        null,
        OffsetDateTime.parse("2026-10-18T13:30:00+02:00"),
        listOf(
            MealItem(
                RecordIds.itemKey("Rice", 0),
                "Rice",
                mapOf(
                    "energy_kcal" to 260.0,
                    "protein_g" to 5.0,
                    "sodium_mg" to 380.0,
                    "selenium_mcg" to 12.0
                )
            ),
            MealItem(RecordIds.itemKey("Chicken", 0), "Chicken", mapOf("energy_kcal" to 220.0))
        ),
        emptyList()
    )

    @Test fun mapsFieldsAndUnits() {
        val r = buildRecords(meal, meal.items, now, version = 7).first()
        assertEquals(260.0, r.energy!!.inKilocalories, 1e-9)
        assertEquals(0.38, r.sodium!!.inGrams, 1e-9)
        assertEquals(12.0, r.selenium!!.inMicrograms, 1e-9)
        assertEquals(HcMealType.MEAL_TYPE_LUNCH, r.mealType)
        assertEquals(
            RecordIds.clientRecordId(meal.id, meal.items[0].key),
            r.metadata.clientRecordId
        )
        assertEquals(7L, r.metadata.clientRecordVersion)
    }

    @Test fun timeClampedIntoPast() {
        val recent = meal.copy(time = OffsetDateTime.parse("2026-10-18T13:59:50+02:00"))
        val r = buildRecords(recent, recent.items, now, 1).first()
        assertEquals(now.minusSeconds(60), r.startTime)
        assertEquals(now, r.endTime)
    }

    @Test fun samePasteSameIds() {
        val text = """{"request":"mvdrjvpc-7f2a","id":"e1",""" +
            """"time":"2026-10-18T13:30:00+02:00","items":""" +
            """[{"name":"Rice","energy_kcal":1},{"name":"Rice","energy_kcal":2}]}"""
        val zone = ZoneId.of("Europe/Warsaw")
        fun ids() = ((parseReply(text, now, zone) as ReplyResult.Parsed).groups.single() as Meal)
            .let { m -> buildRecords(m, m.items, now, 1).map { it.metadata.clientRecordId } }
        assertEquals(ids(), ids())
    }

    @Test fun editedTimeKeepsIds() {
        val moved = meal.copy(time = OffsetDateTime.parse("2026-10-18T12:00:00+02:00"))
        assertEquals(
            buildRecords(meal, meal.items, now, 1).map { it.metadata.clientRecordId },
            buildRecords(moved, moved.items, now, 2).map { it.metadata.clientRecordId }
        )
    }

    @Test fun roundTrip() {
        val back = mealsFromRecords(buildRecords(meal, meal.items, now, 1)).single()
        assertEquals(meal.id, back.id)
        assertEquals(meal.items.map { it.key }.toSet(), back.items.map { it.key }.toSet())
        assertEquals(380.0, back.items.first { it.name == "Rice" }.base["sodium_mg"]!!, 1e-9)
    }

    @Test fun allKeysRoundTrip() {
        val base = Nutrients.all.mapIndexed { i, n -> n.key to i + 1.0 }.toMap()
        val full = meal.copy(items = listOf(MealItem(RecordIds.itemKey("Full", 0), "Full", base)))
        val back = mealsFromRecords(buildRecords(full, full.items, now, 1)).single().items.single()
        assertEquals(base.keys, back.base.keys)
        base.forEach { (key, value) -> assertEquals(value, back.base.getValue(key), 1e-9, key) }
    }

    @Test fun droppedItemsAreDeleted() {
        val sibling = meal.id.copy(entry = "e2")
        val existing: List<Record> = buildRecords(meal, meal.items, now, 1) +
            buildRecords(meal.copy(id = sibling), meal.items, now, 1) +
            NutritionRecord(
                startTime = now,
                startZoneOffset = null,
                endTime = now.plusSeconds(60),
                endZoneOffset = null,
                metadata = Metadata.manualEntry(clientRecordId = "llmhb1/999/0-aaaa")
            )
        assertEquals(
            mapOf<KClass<out Record>, List<String>>(
                NutritionRecord::class to
                    listOf(RecordIds.clientRecordId(meal.id, RecordIds.itemKey("Chicken", 0)))
            ),
            staleClientIds(
                existing,
                meal.id,
                setOf(RecordIds.itemKey("Rice", 0)),
                setOf(NutritionRecord::class)
            )
        )
    }

    @Test fun outOfRangeReportedPerItem() {
        val bad = meal.copy(items = listOf(meal.items[0].copy(base = mapOf("energy_kcal" to 1e9))))
        assertEquals(setOf(bad.items[0].key), recordProblems(bad, bad.items, now).keys)
    }

    @Test fun mappingCoversAllKeys() {
        assertEquals(Nutrients.all.map { it.key }, nutrientFields.map { it.key })
        nutrientFields.forEach { assertEquals(Nutrients.byKey(it.key)!!.unit, it.unit, it.key) }
    }
}

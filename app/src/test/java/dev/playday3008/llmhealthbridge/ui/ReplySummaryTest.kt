package dev.playday3008.llmhealthbridge.ui

import kotlin.test.Test
import kotlin.test.assertEquals

import java.time.OffsetDateTime

import dev.playday3008.llmhealthbridge.entry.EntryType
import dev.playday3008.llmhealthbridge.entry.GroupId
import dev.playday3008.llmhealthbridge.measurement.MeasurementEntry
import dev.playday3008.llmhealthbridge.measurement.MeasurementTypes
import dev.playday3008.llmhealthbridge.measurement.MeasurementValue
import dev.playday3008.llmhealthbridge.nutrition.Meal
import dev.playday3008.llmhealthbridge.nutrition.MealItem
import dev.playday3008.llmhealthbridge.nutrition.MealType

class ReplySummaryTest {
    private val time = OffsetDateTime.parse("2026-10-18T13:05:00+02:00")

    private fun meal(type: MealType, vararg kcal: Double) = Meal(
        GroupId(EntryType.NUTRITION, "mvdrjvpc-7f2a", "e1"),
        type,
        null,
        time,
        kcal.mapIndexed { i, k -> MealItem("$i", "item $i", mapOf("energy_kcal" to k)) },
        emptyList()
    )

    private val water = MeasurementEntry(
        GroupId(EntryType.HYDRATION, "mvdrjvpc-7f2a", "e2"),
        time,
        listOf(
            MeasurementValue(
                checkNotNull(MeasurementTypes.byKey("hydration")),
                mapOf(
                    "volume" to 500.0
                ),
                emptyMap()
            )
        ),
        emptyList()
    )

    @Test
    fun mealIsTypeAndRoundedKcal() {
        assertEquals("Lunch 1140 kcal", replySummary(listOf(meal(MealType.LUNCH, 1000.0, 139.6))))
    }

    @Test
    fun mealWithoutEnergyShowsZero() {
        assertEquals("Snack 0 kcal", replySummary(listOf(meal(MealType.SNACK))))
    }

    @Test
    fun measurementUsesItsSummary() {
        assertEquals("Hydration 500 ml", replySummary(listOf(water)))
    }

    @Test
    fun furtherGroupsAreCounted() {
        assertEquals(
            "Dinner 600 kcal, and 2 more entries",
            replySummary(listOf(meal(MealType.DINNER, 600.0), water, water))
        )
        assertEquals(
            "Hydration 500 ml, and 1 more entry",
            replySummary(listOf(water, meal(MealType.LUNCH)))
        )
    }
}

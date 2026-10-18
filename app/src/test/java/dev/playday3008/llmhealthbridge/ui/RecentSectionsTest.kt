package dev.playday3008.llmhealthbridge.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

import java.time.OffsetDateTime

import dev.playday3008.llmhealthbridge.entry.EntryType
import dev.playday3008.llmhealthbridge.entry.Group
import dev.playday3008.llmhealthbridge.entry.GroupId
import dev.playday3008.llmhealthbridge.entry.Section
import dev.playday3008.llmhealthbridge.measurement.MeasurementEntry
import dev.playday3008.llmhealthbridge.measurement.MeasurementTypes
import dev.playday3008.llmhealthbridge.measurement.MeasurementValue
import dev.playday3008.llmhealthbridge.nutrition.Meal
import dev.playday3008.llmhealthbridge.nutrition.MealItem
import dev.playday3008.llmhealthbridge.nutrition.MealType

class RecentSectionsTest {
    private fun at(time: String) = OffsetDateTime.parse(time)

    private fun id(type: EntryType, entry: String) = GroupId(type, "mvdrjvpc-7f2a", entry)

    private fun measurement(
        type: EntryType,
        key: String,
        time: String,
        vararg v: Pair<String, Double>
    ) = MeasurementEntry(
        id(type, time.take(10)),
        at(time),
        listOf(
            MeasurementValue(checkNotNull(MeasurementTypes.byKey(key)), mapOf(*v), emptyMap())
        ),
        emptyList()
    )

    private val lunchTime = "2026-10-18T13:05:00+02:00"
    private val lunch = Meal(
        id(EntryType.NUTRITION, "e1"),
        MealType.LUNCH,
        null,
        at(lunchTime),
        listOf(MealItem("0", "item", mapOf("energy_kcal" to 1140.0))),
        emptyList()
    )
    private val water = measurement(
        EntryType.HYDRATION,
        "hydration",
        "2026-10-18T15:00:00+02:00",
        "volume" to 500.0
    )
    private val weight = measurement(
        EntryType.BODY,
        "weight",
        "2026-10-17T07:12:00+02:00",
        "weight" to 82.4
    )
    private val olderWeight = measurement(
        EntryType.BODY,
        "weight",
        "2026-10-16T07:12:00+02:00",
        "weight" to 83.0
    )

    @Test fun groupsAreSplitBySectionNewestFirst() {
        val sections = groupsBySection(listOf(olderWeight, lunch, weight, water))
        assertEquals(
            listOf(Section.NUTRITION, Section.BODY, Section.VITALS),
            sections.keys.toList()
        )
        assertEquals(listOf<Group>(water, lunch), sections[Section.NUTRITION])
        assertEquals(listOf<Group>(weight, olderWeight), sections[Section.BODY])
        assertEquals(emptyList(), sections[Section.VITALS])
    }

    @Test fun newestSummaryUsesTheNewestGroup() {
        assertEquals("Hydration 500 ml", newestSummary(listOf(lunch, water)))
        assertEquals("Lunch 1140 kcal", newestSummary(listOf(lunch)))
        assertEquals("Weight 82.4 kg", newestSummary(listOf(olderWeight, weight)))
    }

    @Test fun emptySectionHasNoSummary() {
        assertNull(newestSummary(emptyList()))
    }

    @Test fun keyKeepsTypesAndEntriesApart() {
        assertNotEquals(
            recentKey(id(EntryType.NUTRITION, "e1")),
            recentKey(id(EntryType.HYDRATION, "e1"))
        )
        assertNotEquals(recentKey(id(EntryType.BODY, "e1")), recentKey(id(EntryType.BODY, "e2")))
    }

    @Test fun tappingASectionOpensItAloneAndTappingItAgainClosesIt() {
        assertEquals(Section.BODY, toggleSection(Section.NUTRITION, Section.BODY))
        assertEquals(Section.VITALS, toggleSection(null, Section.VITALS))
        assertNull(toggleSection(Section.BODY, Section.BODY))
    }
}

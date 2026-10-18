package dev.playday3008.llmhealthbridge.review

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

import java.time.Instant
import java.time.OffsetDateTime

import dev.playday3008.llmhealthbridge.entry.EntryType
import dev.playday3008.llmhealthbridge.entry.GroupId
import dev.playday3008.llmhealthbridge.nutrition.Issue
import dev.playday3008.llmhealthbridge.nutrition.Meal
import dev.playday3008.llmhealthbridge.nutrition.MealItem
import dev.playday3008.llmhealthbridge.nutrition.MealType

class ReviewStateTest {
    private val now = Instant.parse("2026-10-18T12:00:00Z")

    private fun item(key: String, kcal: Double?, p: Double = 0.0) = MealItem(
        key,
        key,
        buildMap {
            kcal?.let { put("energy_kcal", it) }
            put("protein_g", p)
        }
    )

    private fun state(vararg items: MealItem) = MealReview(
        Meal(
            GroupId(EntryType.NUTRITION, "mvdrjvpc-7f2a", "e1"),
            MealType.LUNCH,
            null,
            OffsetDateTime.parse("2026-10-18T13:00:00+02:00"),
            items.toList(),
            emptyList()
        )
    )

    @Test
    fun portionScalesEveryNutrient() {
        val s = state(item("a", 520.0, 10.0)).withPortion("a", 0.5)
        assertEquals(Totals(260.0, 5.0, 0.0, 0.0), s.totals())
    }

    @Test
    fun nutrientEditUnderPortionStoresBase() {
        val s = state(
            item("a", 500.0)
        ).withPortion("a", 0.5).withNutrient("a", "energy_kcal", 300.0)
        assertEquals(300.0, s.meal.items.single().nutrients["energy_kcal"])
        assertEquals(600.0, s.meal.items.single().base["energy_kcal"])
    }

    @Test
    fun totalsOnlySelected() =
        assertEquals(100.0, state(item("a", 100.0), item("b", 50.0)).toggle("b").totals().kcal)

    @Test
    fun missingEnergyBlocksUntilFixedOrUnticked() {
        val s = state(item("a", 100.0), item("b", null))
        assertFalse(s.canSave(now))
        assertTrue(s.withNutrient("b", "energy_kcal", 80.0).canSave(now))
        assertTrue(s.toggle("b").canSave(now))
    }

    @Test
    fun nothingSelectedCannotSave() = assertFalse(state(item("a", 1.0)).toggle("a").canSave(now))

    @Test
    fun renameKeepsKey() =
        assertEquals("a", state(item("a", 1.0)).withName("a", "Jollof").meal.items.single().key)

    @Test
    fun zeroOrNegativePortionIsIgnored() {
        val s = state(item("a", 100.0))
        listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY).forEach {
            assertEquals(s.totals(), s.withPortion("a", it).totals())
        }
    }

    @Test
    fun negativeNutrientBecomesIssue() {
        val s = state(item("a", 100.0)).withNutrient("a", "protein_g", -5.0)
        assertTrue(Issue("protein_g", "must be a number >= 0") in s.meal.items.single().issues())
        assertTrue(s.blockingIssues(now).any { it.message == "must be a number >= 0" })
        assertFalse(s.canSave(now))
    }
}

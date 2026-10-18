@file:Suppress("TooManyFunctions")

package dev.playday3008.llmhealthbridge.review

import java.time.Instant
import java.time.OffsetDateTime

import dev.playday3008.llmhealthbridge.entry.GroupId
import dev.playday3008.llmhealthbridge.nutrition.Issue
import dev.playday3008.llmhealthbridge.nutrition.Meal
import dev.playday3008.llmhealthbridge.nutrition.MealItem
import dev.playday3008.llmhealthbridge.nutrition.MealType
import dev.playday3008.llmhealthbridge.nutrition.Nutrients

/** Sums of the headline nutrients over the selected items. */
data class Totals(val kcal: Double, val protein: Double, val carbs: Double, val fat: Double)

/** A [meal] under review; items whose key is in [unticked] are excluded from totals and saving. */
data class MealReview(val meal: Meal, val unticked: Set<String> = emptySet()) : ReviewEntry {
    override val id: GroupId get() = meal.id
}

/** Items that are currently ticked. */
fun MealReview.selectedItems(): List<MealItem> = meal.items.filter { it.key !in unticked }

/** Totals of the selected items, with portions applied. */
fun MealReview.totals(): Totals {
    val items = selectedItems()
    fun sum(key: String) = items.sumOf { it.nutrients[key] ?: 0.0 }
    return Totals(
        sum(Nutrients.ENERGY),
        sum(Nutrients.PROTEIN),
        sum(Nutrients.CARBS),
        sum(Nutrients.FAT)
    )
}

/** Flips the tick of the item with [itemKey]. */
fun MealReview.toggle(itemKey: String): MealReview =
    copy(unticked = if (itemKey in unticked) unticked - itemKey else unticked + itemKey)

/** Drops the item with [itemKey] from the meal. */
fun MealReview.remove(itemKey: String): MealReview = copy(
    meal = meal.copy(items = meal.items.filter { it.key != itemKey }),
    unticked =
    unticked - itemKey
)

private fun MealReview.mapItem(itemKey: String, transform: (MealItem) -> MealItem): MealReview =
    copy(meal = meal.copy(items = meal.items.map { if (it.key == itemKey) transform(it) else it }))

/** Sets the portion factor of the item with [itemKey]; a non-finite or non-positive factor is ignored. */
fun MealReview.withPortion(itemKey: String, portion: Double): MealReview =
    if (!portion.isFinite() ||
        portion <= 0.0
    ) {
        this
    } else {
        mapItem(itemKey) { it.copy(portion = portion) }
    }

/**
 * Sets the displayed (portion-scaled) value of [nutrientKey] on the item, storing it back as a
 * per-portion base value. A null [value] removes the nutrient and clears the invalid marker. A
 * negative or non-finite [value] is not stored; the nutrient is marked invalid instead.
 */
fun MealReview.withNutrient(itemKey: String, nutrientKey: String, value: Double?): MealReview =
    mapItem(itemKey) {
        when {
            value == null -> it.copy(
                base = it.base - nutrientKey,
                invalid =
                it.invalid - nutrientKey
            )
            !value.isFinite() || value < 0.0 -> it.copy(
                base = it.base - nutrientKey,
                invalid = it.invalid + (nutrientKey to "must be a number >= 0")
            )
            else -> it.copy(
                base = it.base + (nutrientKey to value / it.portion),
                invalid = it.invalid - nutrientKey
            )
        }
    }

/** Renames the item; its key stays the same. */
fun MealReview.withName(itemKey: String, name: String): MealReview =
    mapItem(itemKey) { it.copy(name = name) }

/** Replaces the meal time. */
fun MealReview.withTime(time: OffsetDateTime): MealReview = copy(meal = meal.copy(time = time))

/** Sets the meal type and clears any unrecognized raw value. */
fun MealReview.withMealType(type: MealType): MealReview =
    copy(meal = meal.copy(mealType = type, invalidMealType = null))

/** Meal-level issues plus issues of selected items, the latter prefixed with the item name. */
fun MealReview.blockingIssues(now: Instant): List<Issue> =
    meal.issues(now) + selectedItems().flatMap { item ->
        item.issues().map { it.copy(field = "${item.name}: ${it.field}") }
    }

/** True when at least one item is selected and nothing blocks saving. */
fun MealReview.canSave(now: Instant): Boolean =
    selectedItems().isNotEmpty() && blockingIssues(now).isEmpty()

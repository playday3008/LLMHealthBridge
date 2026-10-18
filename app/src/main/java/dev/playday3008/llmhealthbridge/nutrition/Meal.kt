package dev.playday3008.llmhealthbridge.nutrition

import java.time.Instant
import java.time.OffsetDateTime

import dev.playday3008.llmhealthbridge.entry.Group
import dev.playday3008.llmhealthbridge.entry.GroupId
import dev.playday3008.llmhealthbridge.entry.timeIssue

/** Meal category as named in the entry JSON. */
enum class MealType(val jsonName: String) {
    BREAKFAST("breakfast"),
    LUNCH("lunch"),
    DINNER("dinner"),
    SNACK("snack"),
    UNKNOWN("unknown")
}

/** A problem the user has to fix before saving, attached to the [field] it concerns. */
data class Issue(val field: String, val message: String)

/**
 * One food item of a [Meal].
 *
 * [base] holds the valid nutrient values for one portion; [invalid] maps nutrient keys whose raw
 * value was unusable to the reason. [portion] is a multiplier applied to [base].
 */
data class MealItem(
    val key: String,
    val name: String,
    val base: Map<String, Double>,
    val portion: Double = 1.0,
    val invalid: Map<String, String> = emptyMap()
) {
    /** Nutrient values scaled by [portion]. */
    val nutrients: Map<String, Double> get() = base.mapValues { it.value * portion }

    /** Problems that block saving this item. */
    fun issues(): List<Issue> = buildList {
        if (name.isBlank()) add(Issue("name", "required"))
        if (Nutrients.ENERGY !in base && Nutrients.ENERGY !in invalid) {
            add(Issue(Nutrients.ENERGY, "required"))
        }
        invalid.forEach { (key, reason) -> add(Issue(key, reason)) }
    }
}

/**
 * A parsed meal. [invalidMealType] keeps the raw meal type when it was not recognized, in which
 * case [mealType] is [MealType.UNKNOWN]. [warnings] are informational and never block saving.
 */
data class Meal(
    override val id: GroupId,
    val mealType: MealType,
    val invalidMealType: String?,
    override val time: OffsetDateTime,
    val items: List<MealItem>,
    override val warnings: List<String>
) : Group {
    /** Meal-level problems that block saving, evaluated against the current instant [now]. */
    fun issues(now: Instant): List<Issue> = buildList {
        timeIssue(id, time, now)?.let { add(Issue("time", it)) }
        if (invalidMealType != null) add(Issue("meal_type", "unknown value: $invalidMealType"))
    }
}

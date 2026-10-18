package dev.playday3008.llmhealthbridge.ui

import kotlin.math.roundToInt

import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

import dev.playday3008.llmhealthbridge.entry.Group
import dev.playday3008.llmhealthbridge.measurement.MeasurementEntry
import dev.playday3008.llmhealthbridge.nutrition.Meal
import dev.playday3008.llmhealthbridge.review.MealReview
import dev.playday3008.llmhealthbridge.review.totals

private val GROUP_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

/** Time of [group] in the phone's zone, for example "2026-10-18 13:30". */
internal fun groupTime(group: Group): String =
    GROUP_TIME_FORMAT.format(group.time.atZoneSameInstant(ZoneId.systemDefault()))

/** Meal type and total energy, for example "Lunch 1140 kcal". */
internal fun mealSummary(meal: Meal): String {
    val type = meal.mealType.jsonName.replaceFirstChar { it.titlecase(Locale.ROOT) }
    return "$type ${MealReview(meal).totals().kcal.roundToInt()} kcal"
}

/** One line for [group]: the meal summary or the measurement entry summary. */
internal fun groupSummary(group: Group): String = when (group) {
    is Meal -> mealSummary(group)
    is MeasurementEntry -> group.summary()
    else -> ""
}

/** One line for a parsed reply: the first group's summary plus a count of the other groups. */
internal fun replySummary(groups: List<Group>): String {
    val first = groupSummary(groups.first())
    return when (val others = groups.size - 1) {
        0 -> first
        1 -> "$first, and 1 more entry"
        else -> "$first, and $others more entries"
    }
}

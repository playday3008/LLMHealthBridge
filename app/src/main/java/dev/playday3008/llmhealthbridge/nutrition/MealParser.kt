package dev.playday3008.llmhealthbridge.nutrition

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

import java.time.Instant
import java.time.ZoneId

import dev.playday3008.llmhealthbridge.entry.EntryParse
import dev.playday3008.llmhealthbridge.entry.EntryType
import dev.playday3008.llmhealthbridge.entry.GroupId
import dev.playday3008.llmhealthbridge.entry.RecordIds
import dev.playday3008.llmhealthbridge.entry.given
import dev.playday3008.llmhealthbridge.entry.resolveTime
import dev.playday3008.llmhealthbridge.measurement.misplacedKeyWarning

private val TOP_LEVEL_KEYS = setOf("type", "id", "meal_type", "time", "items")

/**
 * Parses a nutrition entry object as entry [id]. Invalid items are returned as errors; everything
 * else is fixable or a warning.
 */
internal fun parseMeal(obj: JsonObject, id: GroupId, now: Instant, zone: ZoneId): EntryParse {
    val items = obj["items"]
    val errors = itemsErrors(items)
    if (errors.isNotEmpty()) return EntryParse.Invalid(errors)

    val warnings = mutableListOf<String>()
    obj.keys.filter { it !in TOP_LEVEL_KEYS && obj.given(it) != null }
        .forEach { warnings += misplacedKeyWarning(it, id.type) ?: "Ignored unknown key: $it" }

    val time = resolveTime(obj.given("time"), now, zone, warnings)
    val (mealType, invalidMealType) = resolveMealType(obj.given("meal_type"))
    val mealItems = parseItems(items as JsonArray, warnings)
    val meal = Meal(
        id,
        mealType,
        invalidMealType,
        time,
        mealItems,
        warnings
    )
    return EntryParse.Valid(meal)
}

private fun itemsErrors(items: JsonElement?): List<String> = when {
    items == null -> listOf("items: required")
    items !is JsonArray || items.isEmpty() -> listOf("items: must be a non-empty array")
    else -> items.withIndex().filter { it.value !is JsonObject }
        .map { "items[${it.index}]: must be an object" }
}

private fun resolveMealType(raw: JsonElement?): Pair<MealType, String?> {
    if (raw == null) return MealType.UNKNOWN to null
    val text = (raw as? JsonPrimitive)?.takeIf { it.isString }?.content
    val type = MealType.entries.firstOrNull { it.jsonName.equals(text?.trim(), ignoreCase = true) }
    return if (type != null) type to null else MealType.UNKNOWN to (text ?: raw.toString())
}

private fun parseItems(items: JsonArray, warnings: MutableList<String>): List<MealItem> {
    val seen = mutableMapOf<String, Int>()
    return items.mapIndexed { index, element ->
        val obj = element as JsonObject
        val name = (obj["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
        val occurrence = seen.merge(name.trim(), 1, Int::plus)!! - 1
        obj.keys.filter { it != "name" && Nutrients.byKey(it) == null && obj.given(it) != null }
            .forEach {
                val label = "items[$index].$it"
                warnings += misplacedKeyWarning(it, EntryType.NUTRITION, label)
                    ?: "Ignored unknown key: $label"
            }
        buildItem(RecordIds.itemKey(name, occurrence), name, obj)
    }
}

private fun buildItem(key: String, name: String, obj: JsonObject): MealItem {
    val base = mutableMapOf<String, Double>()
    val invalid = mutableMapOf<String, String>()
    for ((key1) in Nutrients.all) {
        val raw = obj.given(key1) ?: continue
        val value = numberOrNull(raw)
        if (value != null && value.isFinite() && value >= 0) {
            base[key1] = value
        } else {
            invalid[key1] = "must be a number >= 0"
        }
    }
    return MealItem(key, name, base, 1.0, invalid)
}

private fun numberOrNull(raw: JsonElement): Double? =
    (raw as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull

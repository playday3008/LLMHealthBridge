package dev.playday3008.llmhealthbridge.healthconnect

import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Mass

import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime

import dev.playday3008.llmhealthbridge.entry.RecordIds
import dev.playday3008.llmhealthbridge.nutrition.Meal
import dev.playday3008.llmhealthbridge.nutrition.MealItem
import dev.playday3008.llmhealthbridge.nutrition.NutrientUnit
import dev.playday3008.llmhealthbridge.nutrition.Nutrients

import androidx.health.connect.client.records.MealType as HcMealType
import dev.playday3008.llmhealthbridge.nutrition.MealType as AppMealType

private val RECORD_LENGTH: Duration = Duration.ofMinutes(1)

/** Reads one nutrient of a [NutritionRecord] in the unit of its key. */
internal class NutrientField(
    val key: String,
    val unit: NutrientUnit,
    val read: (NutritionRecord) -> Double?
)

private fun mass(key: String, get: (NutritionRecord) -> Mass?): NutrientField {
    val unit = checkNotNull(Nutrients.byKey(key)).unit
    return NutrientField(key, unit) { r ->
        get(r)?.let {
            when (unit) {
                NutrientUnit.GRAM -> it.inGrams
                NutrientUnit.MILLIGRAM -> it.inMilligrams
                else -> it.inMicrograms
            }
        }
    }
}

private fun energy(key: String, get: (NutritionRecord) -> Energy?) =
    NutrientField(key, NutrientUnit.KILOCALORIE) { r -> get(r)?.inKilocalories }

/** Reverse mapping table, in the same order as [Nutrients.all]. */
internal val nutrientFields: List<NutrientField> = listOf(
    energy("energy_kcal") { it.energy },
    energy("energy_from_fat_kcal") { it.energyFromFat },
    mass("protein_g") { it.protein },
    mass("carbs_g") { it.totalCarbohydrate },
    mass("fat_g") { it.totalFat },
    mass("saturated_fat_g") { it.saturatedFat },
    mass("unsaturated_fat_g") { it.unsaturatedFat },
    mass("monounsaturated_fat_g") { it.monounsaturatedFat },
    mass("polyunsaturated_fat_g") { it.polyunsaturatedFat },
    mass("trans_fat_g") { it.transFat },
    mass("fiber_g") { it.dietaryFiber },
    mass("sugar_g") { it.sugar },
    mass("cholesterol_mg") { it.cholesterol },
    mass("sodium_mg") { it.sodium },
    mass("potassium_mg") { it.potassium },
    mass("calcium_mg") { it.calcium },
    mass("iron_mg") { it.iron },
    mass("magnesium_mg") { it.magnesium },
    mass("phosphorus_mg") { it.phosphorus },
    mass("zinc_mg") { it.zinc },
    mass("copper_mg") { it.copper },
    mass("manganese_mg") { it.manganese },
    mass("chloride_mg") { it.chloride },
    mass("caffeine_mg") { it.caffeine },
    mass("selenium_mcg") { it.selenium },
    mass("chromium_mcg") { it.chromium },
    mass("iodine_mcg") { it.iodine },
    mass("molybdenum_mcg") { it.molybdenum },
    mass("vitamin_a_mcg") { it.vitaminA },
    mass("vitamin_b6_mg") { it.vitaminB6 },
    mass("vitamin_b12_mcg") { it.vitaminB12 },
    mass("vitamin_c_mg") { it.vitaminC },
    mass("vitamin_d_mcg") { it.vitaminD },
    mass("vitamin_e_mg") { it.vitaminE },
    mass("vitamin_k_mcg") { it.vitaminK },
    mass("thiamin_mg") { it.thiamin },
    mass("riboflavin_mg") { it.riboflavin },
    mass("niacin_mg") { it.niacin },
    mass("pantothenic_acid_mg") { it.pantothenicAcid },
    mass("biotin_mcg") { it.biotin },
    mass("folate_mcg") { it.folate },
    mass("folic_acid_mcg") { it.folicAcid }
)

private val MEAL_TYPES = mapOf(
    AppMealType.BREAKFAST to HcMealType.MEAL_TYPE_BREAKFAST,
    AppMealType.LUNCH to HcMealType.MEAL_TYPE_LUNCH,
    AppMealType.DINNER to HcMealType.MEAL_TYPE_DINNER,
    AppMealType.SNACK to HcMealType.MEAL_TYPE_SNACK,
    AppMealType.UNKNOWN to HcMealType.MEAL_TYPE_UNKNOWN
)

private fun Map<String, Double>.massOf(key: String): Mass? = get(key)?.let {
    when (Nutrients.byKey(key)?.unit) {
        NutrientUnit.GRAM -> Mass.grams(it)
        NutrientUnit.MILLIGRAM -> Mass.milligrams(it)
        else -> Mass.micrograms(it)
    }
}

private fun Map<String, Double>.energyOf(key: String): Energy? =
    get(key)?.let { Energy.kilocalories(it) }

@Suppress("LongMethod")
private fun buildRecord(meal: Meal, item: MealItem, now: Instant, version: Long): NutritionRecord {
    val start = minOf(meal.time.toInstant(), now.minus(RECORD_LENGTH))
    val offset = meal.time.offset
    val n = item.nutrients
    return NutritionRecord(
        startTime = start,
        startZoneOffset = offset,
        endTime = start.plus(RECORD_LENGTH),
        endZoneOffset = offset,
        metadata = Metadata.manualEntry(
            clientRecordId = RecordIds.clientRecordId(meal.id, item.key),
            clientRecordVersion = version
        ),
        name = item.name,
        mealType = MEAL_TYPES.getValue(meal.mealType),
        energy = n.energyOf("energy_kcal"),
        energyFromFat = n.energyOf("energy_from_fat_kcal"),
        protein = n.massOf("protein_g"),
        totalCarbohydrate = n.massOf("carbs_g"),
        totalFat = n.massOf("fat_g"),
        saturatedFat = n.massOf("saturated_fat_g"),
        unsaturatedFat = n.massOf("unsaturated_fat_g"),
        monounsaturatedFat = n.massOf("monounsaturated_fat_g"),
        polyunsaturatedFat = n.massOf("polyunsaturated_fat_g"),
        transFat = n.massOf("trans_fat_g"),
        dietaryFiber = n.massOf("fiber_g"),
        sugar = n.massOf("sugar_g"),
        cholesterol = n.massOf("cholesterol_mg"),
        sodium = n.massOf("sodium_mg"),
        potassium = n.massOf("potassium_mg"),
        calcium = n.massOf("calcium_mg"),
        iron = n.massOf("iron_mg"),
        magnesium = n.massOf("magnesium_mg"),
        phosphorus = n.massOf("phosphorus_mg"),
        zinc = n.massOf("zinc_mg"),
        copper = n.massOf("copper_mg"),
        manganese = n.massOf("manganese_mg"),
        chloride = n.massOf("chloride_mg"),
        caffeine = n.massOf("caffeine_mg"),
        selenium = n.massOf("selenium_mcg"),
        chromium = n.massOf("chromium_mcg"),
        iodine = n.massOf("iodine_mcg"),
        molybdenum = n.massOf("molybdenum_mcg"),
        vitaminA = n.massOf("vitamin_a_mcg"),
        vitaminB6 = n.massOf("vitamin_b6_mg"),
        vitaminB12 = n.massOf("vitamin_b12_mcg"),
        vitaminC = n.massOf("vitamin_c_mg"),
        vitaminD = n.massOf("vitamin_d_mcg"),
        vitaminE = n.massOf("vitamin_e_mg"),
        vitaminK = n.massOf("vitamin_k_mcg"),
        thiamin = n.massOf("thiamin_mg"),
        riboflavin = n.massOf("riboflavin_mg"),
        niacin = n.massOf("niacin_mg"),
        pantothenicAcid = n.massOf("pantothenic_acid_mg"),
        biotin = n.massOf("biotin_mcg"),
        folate = n.massOf("folate_mcg"),
        folicAcid = n.massOf("folic_acid_mcg")
    )
}

/**
 * Builds one [NutritionRecord] per item. The start time is [Meal.time] clamped to one minute before
 * [now] so Health Connect never sees a record ending in the future. Throws
 * [IllegalArgumentException] if a value is out of range.
 */
fun buildRecords(
    meal: Meal,
    items: List<MealItem>,
    now: Instant,
    version: Long
): List<NutritionRecord> = items.map { buildRecord(meal, it, now, version) }

/** Maps item key to the validation message for each item whose record cannot be built. */
fun recordProblems(meal: Meal, items: List<MealItem>, now: Instant): Map<String, String> =
    buildMap {
        items.forEach { item ->
            try {
                buildRecord(meal, item, now, 0)
            } catch (e: IllegalArgumentException) {
                put(item.key, e.message.orEmpty())
            }
        }
    }

/**
 * Reassembles meals from records written by this app. Records with foreign client IDs are
 * ignored. Newest meal first.
 */
fun mealsFromRecords(records: List<NutritionRecord>): List<Meal> {
    val parsed = records.mapNotNull { r ->
        r.metadata.clientRecordId?.let(RecordIds::parse)?.let { (group, key) ->
            Triple(group, key, r)
        }
    }
    return parsed.groupBy { it.first }.map { (id, group) ->
        val ordered = group.sortedWith(compareBy({ it.third.startTime }, { it.second }))
        val first = ordered.first().third
        val earliest = ordered.minBy { it.third.startTime }.third
        val reverseType = MEAL_TYPES.entries.firstOrNull { it.value == first.mealType }?.key
        Meal(
            id = id,
            mealType = reverseType ?: AppMealType.UNKNOWN,
            invalidMealType = null,
            time = OffsetDateTime.ofInstant(
                earliest.startTime,
                earliest.startZoneOffset ?: java.time.ZoneOffset.UTC
            ),
            items = ordered.map { (_, key, r) ->
                MealItem(
                    key = key,
                    name = r.name.orEmpty(),
                    base = nutrientFields.mapNotNull { f -> f.read(r)?.let { f.key to it } }.toMap()
                )
            },
            warnings = emptyList()
        )
    }.sortedByDescending { it.time.toInstant() }
}

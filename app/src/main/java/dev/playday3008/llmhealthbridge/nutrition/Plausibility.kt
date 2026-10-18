package dev.playday3008.llmhealthbridge.nutrition

import kotlin.math.abs

private const val MAX_KCAL = 3000.0
private const val MAX_GRAMS = 500.0
private const val MAX_MILLIGRAMS = 5000.0
private const val MAX_MICROGRAMS = 5000.0
private const val ENERGY_REL_TOLERANCE = 0.2
private const val ENERGY_ABS_TOLERANCE = 25.0
private const val KCAL_PER_G_PROTEIN_CARBS = 4.0
private const val KCAL_PER_G_FAT = 9.0

private fun capFor(unit: NutrientUnit): Double = when (unit) {
    NutrientUnit.KILOCALORIE -> MAX_KCAL
    NutrientUnit.GRAM -> MAX_GRAMS
    NutrientUnit.MILLIGRAM -> MAX_MILLIGRAMS
    NutrientUnit.MICROGRAM -> MAX_MICROGRAMS
}

private fun formatValue(value: Double): String =
    if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()

/**
 * Warnings for the nutrient values of [item] (after portion scaling): values that exceed per-unit
 * caps, and energy far from what protein, carbs and fat give.
 */
fun plausibilityWarnings(item: MealItem): List<String> {
    val caps = item.nutrients.mapNotNull { (key, value) ->
        val nutrient = Nutrients.byKey(key) ?: return@mapNotNull null
        if (value > capFor(nutrient.unit)) {
            "${nutrient.label} looks too high: ${formatValue(value)} ${nutrient.unit.suffix}"
        } else {
            null
        }
    }
    val energy = if (energyMismatch(item.nutrients)) listOf(ENERGY_MISMATCH) else emptyList()
    return caps + energy
}

private const val ENERGY_MISMATCH =
    "Energy differs from 4 x protein + 4 x carbs + 9 x fat; expected with alcohol or fiber, " +
        "else check the values"

private fun energyMismatch(values: Map<String, Double>): Boolean {
    val kcal = values[Nutrients.ENERGY]
    val keys = listOf(Nutrients.PROTEIN, Nutrients.CARBS, Nutrients.FAT)
    val macros = keys.mapNotNull { values[it] }
    if (kcal == null || macros.size != keys.size) return false
    val diff = abs(
        kcal - (KCAL_PER_G_PROTEIN_CARBS * (macros[0] + macros[1]) + KCAL_PER_G_FAT * macros[2])
    )
    return diff > ENERGY_REL_TOLERANCE * kcal && diff > ENERGY_ABS_TOLERANCE
}

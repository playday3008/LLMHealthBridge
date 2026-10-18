package dev.playday3008.llmhealthbridge.nutrition

/** Unit of measurement for a nutrient. The [suffix] is used in nutrient keys. */
enum class NutrientUnit(
    /** Suffix used in the nutrient key to indicate this unit (e.g., "kcal", "g", "mg", "mcg"). */
    val suffix: String
) {
    KILOCALORIE("kcal"),
    GRAM("g"),
    MILLIGRAM("mg"),
    MICROGRAM("mcg")
}

/** A nutrient entry with its key, unit, and human-readable label. The key's suffix always matches its unit. */
data class Nutrient(val key: String, val unit: NutrientUnit, val label: String)

/** Registry of all known nutrients. */
object Nutrients {
    /** All nutrients in canonical order used by instructions and record mapping. */
    val all: List<Nutrient> = listOf(
        Nutrient("energy_kcal", NutrientUnit.KILOCALORIE, "Energy"),
        Nutrient("energy_from_fat_kcal", NutrientUnit.KILOCALORIE, "Energy from fat"),
        Nutrient("protein_g", NutrientUnit.GRAM, "Protein"),
        Nutrient("carbs_g", NutrientUnit.GRAM, "Carbohydrates"),
        Nutrient("fat_g", NutrientUnit.GRAM, "Fat"),
        Nutrient("saturated_fat_g", NutrientUnit.GRAM, "Saturated fat"),
        Nutrient("unsaturated_fat_g", NutrientUnit.GRAM, "Unsaturated fat"),
        Nutrient("monounsaturated_fat_g", NutrientUnit.GRAM, "Monounsaturated fat"),
        Nutrient("polyunsaturated_fat_g", NutrientUnit.GRAM, "Polyunsaturated fat"),
        Nutrient("trans_fat_g", NutrientUnit.GRAM, "Trans fat"),
        Nutrient("fiber_g", NutrientUnit.GRAM, "Dietary fiber"),
        Nutrient("sugar_g", NutrientUnit.GRAM, "Sugar"),
        Nutrient("cholesterol_mg", NutrientUnit.MILLIGRAM, "Cholesterol"),
        Nutrient("sodium_mg", NutrientUnit.MILLIGRAM, "Sodium"),
        Nutrient("potassium_mg", NutrientUnit.MILLIGRAM, "Potassium"),
        Nutrient("calcium_mg", NutrientUnit.MILLIGRAM, "Calcium"),
        Nutrient("iron_mg", NutrientUnit.MILLIGRAM, "Iron"),
        Nutrient("magnesium_mg", NutrientUnit.MILLIGRAM, "Magnesium"),
        Nutrient("phosphorus_mg", NutrientUnit.MILLIGRAM, "Phosphorus"),
        Nutrient("zinc_mg", NutrientUnit.MILLIGRAM, "Zinc"),
        Nutrient("copper_mg", NutrientUnit.MILLIGRAM, "Copper"),
        Nutrient("manganese_mg", NutrientUnit.MILLIGRAM, "Manganese"),
        Nutrient("chloride_mg", NutrientUnit.MILLIGRAM, "Chloride"),
        Nutrient("caffeine_mg", NutrientUnit.MILLIGRAM, "Caffeine"),
        Nutrient("selenium_mcg", NutrientUnit.MICROGRAM, "Selenium"),
        Nutrient("chromium_mcg", NutrientUnit.MICROGRAM, "Chromium"),
        Nutrient("iodine_mcg", NutrientUnit.MICROGRAM, "Iodine"),
        Nutrient("molybdenum_mcg", NutrientUnit.MICROGRAM, "Molybdenum"),
        Nutrient("vitamin_a_mcg", NutrientUnit.MICROGRAM, "Vitamin A"),
        Nutrient("vitamin_b6_mg", NutrientUnit.MILLIGRAM, "Vitamin B6"),
        Nutrient("vitamin_b12_mcg", NutrientUnit.MICROGRAM, "Vitamin B12"),
        Nutrient("vitamin_c_mg", NutrientUnit.MILLIGRAM, "Vitamin C"),
        Nutrient("vitamin_d_mcg", NutrientUnit.MICROGRAM, "Vitamin D"),
        Nutrient("vitamin_e_mg", NutrientUnit.MILLIGRAM, "Vitamin E"),
        Nutrient("vitamin_k_mcg", NutrientUnit.MICROGRAM, "Vitamin K"),
        Nutrient("thiamin_mg", NutrientUnit.MILLIGRAM, "Thiamin"),
        Nutrient("riboflavin_mg", NutrientUnit.MILLIGRAM, "Riboflavin"),
        Nutrient("niacin_mg", NutrientUnit.MILLIGRAM, "Niacin"),
        Nutrient("pantothenic_acid_mg", NutrientUnit.MILLIGRAM, "Pantothenic acid"),
        Nutrient("biotin_mcg", NutrientUnit.MICROGRAM, "Biotin"),
        Nutrient("folate_mcg", NutrientUnit.MICROGRAM, "Folate"),
        Nutrient("folic_acid_mcg", NutrientUnit.MICROGRAM, "Folic acid")
    )

    /** Look up a nutrient by key. Returns null if the key is not found. */
    fun byKey(key: String): Nutrient? = all.find { it.key == key }

    /** Key for energy (in kilocalories). */
    const val ENERGY: String = "energy_kcal"

    /** Key for protein (in grams). */
    const val PROTEIN: String = "protein_g"

    /** Key for carbohydrates (in grams). */
    const val CARBS: String = "carbs_g"

    /** Key for total fat (in grams). */
    const val FAT: String = "fat_g"
}

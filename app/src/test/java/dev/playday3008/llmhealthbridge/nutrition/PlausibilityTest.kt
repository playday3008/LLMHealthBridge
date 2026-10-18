package dev.playday3008.llmhealthbridge.nutrition

import kotlin.test.Test
import kotlin.test.assertEquals

class PlausibilityTest {
    @Test
    fun sodiumUnitSlipIsFlagged() = assertEquals(
        1,
        plausibilityWarnings(
            MealItem("k", "Rice", mapOf("energy_kcal" to 520.0, "sodium_mg" to 610000.0))
        ).size
    )

    @Test
    fun normalValuesPass() = assertEquals(
        emptyList<String>(),
        plausibilityWarnings(
            MealItem("k", "Rice", mapOf("energy_kcal" to 520.0, "sodium_mg" to 610.0))
        )
    )

    @Test
    fun energyFarFromMacrosIsFlagged() = assertEquals(
        listOf(
            "Energy differs from 4 x protein + 4 x carbs + 9 x fat; " +
                "expected with alcohol or fiber, else check the values"
        ),
        plausibilityWarnings(
            MealItem(
                "k",
                "A",
                mapOf(
                    "energy_kcal" to 100.0,
                    "protein_g" to 20.0,
                    "carbs_g" to 20.0,
                    "fat_g" to 10.0
                )
            )
        )
    )

    // The check follows edits: a corrected energy clears it.
    @Test
    fun energyMatchingMacrosPasses() = assertEquals(
        emptyList<String>(),
        plausibilityWarnings(
            MealItem(
                "k",
                "A",
                mapOf(
                    "energy_kcal" to 250.0,
                    "protein_g" to 20.0,
                    "carbs_g" to 20.0,
                    "fat_g" to 10.0
                )
            )
        )
    )
}

package dev.playday3008.llmhealthbridge.nutrition

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NutrientsTest {
    @Test fun hasFortyTwoUniqueKeys() {
        assertEquals(42, Nutrients.all.size)
        assertEquals(42, Nutrients.all.map { it.key }.toSet().size)
    }

    @Test fun suffixMatchesUnit() {
        Nutrients.all.forEach { assertTrue(it.key.endsWith("_" + it.unit.suffix), it.key) }
    }

    @Test fun lookupByKey() {
        assertEquals(NutrientUnit.MICROGRAM, Nutrients.byKey("selenium_mcg")?.unit)
        assertNull(Nutrients.byKey("total"))
    }

    @Test fun energyComesFirst() = assertEquals(Nutrients.ENERGY, Nutrients.all.first().key)
}

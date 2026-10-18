package dev.playday3008.llmhealthbridge.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class NumbersTest {
    @Test
    fun wholeValuesHaveNoFraction() {
        assertEquals("82", plainNumber(82.0))
        assertEquals("0", plainNumber(0.0))
        assertEquals("10000000000", plainNumber(1e10))
    }

    @Test
    fun keepsEveryStoredDigitWithoutExponent() {
        assertEquals("82.375", plainNumber(82.375))
        assertEquals("5.5507", plainNumber(5.5507))
        assertEquals("0.0000001", plainNumber(1e-7))
        assertEquals("82.37237439", plainNumber(82.37237439))
    }

    @Test
    fun capsDecimalsWhenAsked() {
        assertEquals("82.372", plainNumber(82.37237439, maxDecimals = 3))
        assertEquals("36.6", plainNumber(36.6, maxDecimals = 3))
        assertEquals("5.55", plainNumber(5.5499999, maxDecimals = 3))
    }
}
